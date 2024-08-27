package com.ise112.shared.k8s.deployment.helm;

import java.io.Closeable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import com.ise112.shared.k8s.deployment.K8sDevServicesBuildTimeConfig;
import com.ise112.shared.k8s.deployment.K8sDevServicesProcessor;
import com.ise112.shared.k8s.deployment.ssh.SshDeployer;
import com.ise112.shared.k8s.deployment.utils.K8sDevServicesUtils;
import com.marcnuri.helm.Helm;

import io.fabric8.kubernetes.api.model.NamedContext;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.internal.KubeConfigUtils;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem.RunningDevService;

@ApplicationScoped
public class HelmDeployer implements Closeable {
    private static final String HELM_RELEASE_NAME = "quarkus-dev-k8s";

    private static final Logger log = Logger.getLogger(HelmDeployer.class);

    private K8sDevServicesBuildTimeConfig config;

    private Helm helm;

    private Path kubeConfigPath;

    private RunningDevService devService;

    public RunningDevService startServices(K8sDevServicesBuildTimeConfig config) {
        if (devService != null) {
            // currently no update of configuration implemented
            return devService;
        }
        this.config = config;
        kubeConfigPath = Path.of("target", "kubeconfig.yaml");
        saveKubeConfig(config.kubeContext(), kubeConfigPath);

        helm = new Helm(Paths.get(config.chartPath()));
        if (config.stopCleanRestart()) {
            uninstall();
        }
        upgradeDeployment();

        devService = new RunningDevService(K8sDevServicesProcessor.FEATURE, null, this::close, Collections.emptyMap());
        return devService;
    }

    @Override
    public void close() {
        if (config.shutdown()) {
            uninstall();
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
        helm.dependency().build();
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
                    .filter(p -> !p.item().getMetadata().getName().equals(SshDeployer.SSH_POD_NAME))
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
