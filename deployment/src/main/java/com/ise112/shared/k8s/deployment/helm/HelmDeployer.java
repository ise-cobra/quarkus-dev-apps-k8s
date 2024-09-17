package com.ise112.shared.k8s.deployment.helm;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Objects;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.ise112.shared.k8s.deployment.K8sDevServicesBuildTimeConfig;
import com.ise112.shared.k8s.deployment.ssh.SshDeployer;
import com.ise112.shared.k8s.deployment.utils.K8sDevServicesUtils;
import com.marcnuri.helm.Helm;

import io.fabric8.kubernetes.api.model.NamedContext;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.internal.KubeConfigUtils;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem.RunningDevService;
import io.quarkus.deployment.dev.devservices.GlobalDevServicesConfig;
import io.quarkus.deployment.pkg.builditem.BuildSystemTargetBuildItem;

@BuildSteps(onlyIfNot = IsNormal.class, onlyIf = GlobalDevServicesConfig.Enabled.class)
public class HelmDeployer implements Closeable {
    private static final String HELM_RELEASE_NAME = "quarkus-dev-k8s";

    private static final String FEATURE = "K8sDevServicesSshTunnel";

    private static final Logger log = Logger.getLogger(HelmDeployer.class);

    private static volatile K8sDevServicesBuildTimeConfig config;

    private static volatile Helm helm;

    private static volatile Path kubeConfigPath;

    private static volatile RunningDevService devService;

    @BuildStep
    public DevServicesResultBuildItem startServices(BuildSystemTargetBuildItem bst, K8sDevServicesBuildTimeConfig config) {
        if (devService != null) {
            // currently no update of configuration implemented
            return devService.toBuildItem();
        }
        HelmDeployer.config = config;
        kubeConfigPath = bst.getOutputDirectory().resolve("kubeconfig.yaml");
        saveKubeConfig(config.kubeContext(), kubeConfigPath);

        try {
            helm = new Helm(Paths.get(config.chartPath()));
            if (config.stopCleanRestart()) {
                uninstall();
            }
            installSecret();
            helmRegistryLogin();
            upgradeDeployment();

            devService = new RunningDevService(FEATURE, null, this::close,
                    Collections.emptyMap());
            return devService.toBuildItem();
        } finally {
            // Kubeconfig should be deleted after we don't need it anymore, so no secret
            // information gets accidentally leaked
            try {
                Files.delete(kubeConfigPath);
            } catch (IOException e) {
                // can be ignored
            }
        }
    }

    @Override
    public void close() {
        if (config.shutdown()) {
            uninstall();
        }
    }

    /**
     * There might be the requirement to have a secret installed into kubernetes, to
     * access some images. This should be done with a registry secret type
     */
    private void installSecret() {
        String registrySecret = config.registrySecret().orElse(null);
        if (Strings.isNullOrEmpty(registrySecret)) {
            return;
        }
        Config k8sConfig = Config.autoConfigure(config.kubeContext());
        try (KubernetesClient k8sClient = new KubernetesClientBuilder()
                .withConfig(k8sConfig)
                .build()) {

            String credentials = Base64.getEncoder()
                    .encodeToString(registrySecret.getBytes(StandardCharsets.UTF_8));

            log.infof("Creating or patching registry secret %s/%s", config.namespace(), config.registrySecretName());

            k8sClient.namespaces()
                    .resource(new NamespaceBuilder()
                            .withNewMetadata()
                            .withName(config.namespace())
                            .endMetadata()
                            .build())
                    .serverSideApply();
            k8sClient.secrets()
                    .resource(new SecretBuilder()
                            .withNewMetadata()
                            .withName(config.registrySecretName())
                            .withNamespace(config.namespace())
                            .endMetadata()
                            .withData(Collections.singletonMap(".dockerconfigjson", credentials))
                            .withType("kubernetes.io/dockerconfigjson")
                            .build())
                    .serverSideApply();
        }
    }

    private void helmRegistryLogin() {
        String registrySecret = config.registrySecret().orElse(null);
        if (Strings.isNullOrEmpty(registrySecret)) {
            return;
        }
        try {
            JsonNode json = new ObjectMapper().readTree(registrySecret);
            JsonNode auths = json.get("auths");
            if (auths == null) {
                throw new RuntimeException("No \"auths\" field found in registry secret");
            }
            auths.fields().forEachRemaining(auth -> {
                String host = auth.getKey();
                JsonNode value = auth.getValue();
                String username = value.get("username") != null ? value.get("username").asText() : null;
                String password = value.get("password") != null ? value.get("password").asText() : null;
                Helm.registry().login()
                        .withHost(host)
                        .withUsername(username)
                        .withPassword(password)
                        .call();
            });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Could not parse registry secret, is it valid json?", e);
        }
    }

    /**
     * Helm requires a kubeconfig file and can't set a kube context here. Therefore
     * we must export the required kube context into a file and load it from there.
     *
     * @param kubeContext the kubernetes context to use
     * @param kubeConfigPath path to which the kubernetes config should be written
     * @param config the kubernetes config read by fabric8
     */
    private void saveKubeConfig(String kubeContext, Path kubeConfigPath) {

        Config k8sConfig = Config.autoConfigure(kubeContext);
        io.fabric8.kubernetes.api.model.Config kubeConfig;
        try {
            kubeConfig = KubeConfigUtils.parseConfig(k8sConfig.getFile());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        kubeConfig.setCurrentContext(kubeContext);
        NamedContext currentContext = KubeConfigUtils.getCurrentContext(kubeConfig);
        kubeConfig.setContexts(Arrays.asList(currentContext));
        kubeConfig.setClusters(kubeConfig.getClusters().stream()
                .filter(c -> Objects.equals(c.getName(), currentContext.getContext().getCluster()))
                .toList());
        kubeConfig.setUsers(kubeConfig.getUsers().stream()
                .filter(u -> Objects.equals(u.getName(), currentContext.getContext().getUser()))
                .toList());
        try {
            KubeConfigUtils.persistKubeConfigIntoFile(kubeConfig, kubeConfigPath.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void upgradeDeployment() {
        long time = System.currentTimeMillis();
        helm.dependency().update().call();
        log.infof("Time for dependency build: %d", (System.currentTimeMillis() - time));
        time = System.currentTimeMillis();
        helm.upgrade()
                .withKubeConfig(kubeConfigPath)
                .withName(HELM_RELEASE_NAME)
                .withNamespace(config.namespace())
                .install()
                .createNamespace()
                // There is currently a bug which prevents the wait, resulting in the error
                // "beginning wait for resources with timeout of 0s" and "client rate limiter
                // would exceed context time"
                // .waitReady()
                // .debug()
                .call();

        Config k8sConfig = Config.autoConfigure(config.kubeContext());
        try (KubernetesClient k8sClient = new KubernetesClientBuilder()
                .withConfig(k8sConfig)
                .build()) {
            // Wait till all pods in the namespace are ready, since helm waitReady is
            // currently not working
            K8sDevServicesUtils.waitTill(5 * 60000, () -> k8sClient.pods()
                    .inNamespace(config.namespace())
                    .resources()
                    .filter(p -> (p.item().getMetadata().getLabels().get("app") != SshDeployer.SSH_DEPLOYMENT_NAME))
                    .allMatch(p -> p.isReady()));
        }

        log.infof("Time for helm install: %d", (System.currentTimeMillis() - time));
    }

    private void uninstall() {
        Helm.uninstall(HELM_RELEASE_NAME)
                .withKubeConfig(kubeConfigPath)
                .withNamespace(config.namespace())
                .call();
    }
}
