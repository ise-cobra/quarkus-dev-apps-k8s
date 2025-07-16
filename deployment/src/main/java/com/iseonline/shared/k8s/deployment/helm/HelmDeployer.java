package com.iseonline.shared.k8s.deployment.helm;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.WrappedIOException;
import com.iseonline.shared.k8s.deployment.K8sDevServicesBuildTimeConfig;
import com.iseonline.shared.k8s.deployment.utils.K8sDevServicesUtils;
import com.marcnuri.helm.Helm;
import com.marcnuri.helm.Release;
import com.marcnuri.helm.UpgradeCommand;

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
import io.quarkus.deployment.dev.devservices.DevServicesConfig;
import io.quarkus.deployment.pkg.builditem.BuildSystemTargetBuildItem;
import io.quarkus.runtime.util.StringUtil;

@BuildSteps(onlyIfNot = IsNormal.class, onlyIf = DevServicesConfig.Enabled.class)
public class HelmDeployer implements Closeable {
    private static final String HELM_RELEASE_NAME = "quarkus-dev-k8s";

    private static final String FEATURE = "K8sDevServicesSshTunnel";

    private static final Logger log = Logger.getLogger(HelmDeployer.class);

    private static volatile K8sDevServicesBuildTimeConfig config;

    private static volatile KubernetesClient k8sClient;

    private static volatile Path kubeConfigPath;

    private static volatile RunningDevService devService;

    private static volatile String[] profiles;

    @BuildStep
    public DevServicesResultBuildItem startServices(BuildSystemTargetBuildItem bst,
            K8sDevServicesBuildTimeConfig config) {
        if (!config.enabled()) {
            return null;
        }
        if (devService != null) {
            // currently no update of configuration implemented
            return devService.toBuildItem();
        }
        String profilesString = bst.getBuildSystemProps().getProperty("quarkus.profile");
        profiles = profilesString != null ? profilesString.trim().split("\\s*,\\s*") : new String[0];

        HelmDeployer.config = config;
        // We need our own kubeconfig.yaml definition, as this helm plugin cannot
        // specify the context to use
        kubeConfigPath = bst.getOutputDirectory().resolve("kubeconfig.yaml");
        saveKubeConfig(config.kubeContext(), kubeConfigPath);

        if (k8sClient == null) {
            Config k8sConfig = Config.autoConfigure(config.kubeContext());
            k8sClient = new KubernetesClientBuilder()
                    .withConfig(k8sConfig)
                    .build();
        }

        Path chartsDir = Path.of(config.chartPath());
        try {
            if (config.stopCleanRestart()) {
                uninstall();
            }
            installSecret();
            helmRegistryLogin();
            // If a Chart.yaml is found in the dev directory, install this chart,
            // otherwise check one more level whether they are charts to allow more
            // installations
            if (Files.exists(chartsDir.resolve("Chart.yaml"))) {
                upgradeDeployment(chartsDir, HELM_RELEASE_NAME);
            } else if (Files.exists(chartsDir)) {
                List<Exception> exceptions = Files.walk(chartsDir, 1)
                        .filter(dir -> Files.exists(dir.resolve("Chart.yaml")))
                        .parallel()
                        .map(dir -> {
                            try {
                                upgradeDeployment(dir, dir.getFileName().toString());
                            } catch (Exception e) {
                                return e;
                            }
                            return null;
                        })
                        .filter(e -> e != null)
                        .collect(Collectors.toList());

                if (!exceptions.isEmpty()) {
                    throw new RuntimeException("Helm deployment failed", exceptions.get(0));
                }
            }

            devService = new RunningDevService(FEATURE, null, this::close,
                    Collections.emptyMap());
            return devService.toBuildItem();
        } catch (IOException e) {
            log.warnf(e, "Could not check charts dir %s", config.chartPath());
            throw new RuntimeException(e);
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
        if (StringUtil.isNullOrEmpty(registrySecret)) {
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
        if (StringUtil.isNullOrEmpty(registrySecret)) {
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
     * @param kubeContext    the kubernetes context to use
     * @param kubeConfigPath path to which the kubernetes config should be written
     * @param config         the kubernetes config read by fabric8
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
        NamedContext currentContext = kubeConfig.getContexts().stream()
                .filter(c -> Objects.equals(c.getName(), kubeContext))
                .findAny()
                .orElseThrow(() -> new IllegalArgumentException("No kube context named '" + kubeContext + "' exists"));

        kubeConfig.setContexts(Arrays.asList(currentContext));
        kubeConfig.setClusters(kubeConfig.getClusters().stream()
                .filter(c -> Objects.equals(c.getName(), currentContext.getContext().getCluster()))
                .toList());
        kubeConfig.setUsers(kubeConfig.getUsers().stream()
                .filter(u -> Objects.equals(u.getName(), currentContext.getContext().getUser()))
                .toList());
        try {
            KubeConfigUtils.persistKubeConfigIntoFile(kubeConfig, new File(kubeConfigPath.toString()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void upgradeDeployment(Path chartDir, String releaseName) throws IOException {
        Helm helm = new Helm(chartDir);
        UpgradeCommand upgrade = helm.upgrade();
        for (String profile : profiles) {
            Path profileValuesFile = chartDir.resolve(String.format("values-%s.yaml", profile));
            if (Files.exists(profileValuesFile)) {
                upgrade.withValuesFile(profileValuesFile);
            }
        }

        // Make sure, the dependencies are the same as in Chart.yaml
        helmDependencyUpdate(helm);
        upgrade.withKubeConfig(kubeConfigPath)
                .withName(releaseName)
                .withNamespace(config.namespace())
                .install()
                // This is not sufficicent, as this does not re-download updated dependencies
                // .dependencyUpdate()
                .createNamespace()
                .waitReady()
                // .debug()
                .resetValues();

        K8sDevServicesUtils.Retry(3, 0, () -> {
            try {
                upgrade.call();
            } catch (Exception e) {
                if (e.getMessage().contains("another operation (install/upgrade/rollback) is in progress")) {
                    // As we should be the only ones deploying here, we assume this is an error.
                    // Simplest way is to
                    // delete the corresponding helm secret. As this is only a dev environment, this
                    // should not be a problem.
                    List<Release> releases = Helm.list()
                            .withNamespace(config.namespace())
                            .all()
                            .call();
                    releases.stream()
                            .filter(r -> releaseName.equals(r.getName()))
                            .map(hr -> "sh.helm.release.v1." + releaseName + ".v" + hr.getRevision())
                            .map(secretName -> k8sClient.secrets()
                                    .inNamespace(config.namespace())
                                    .withName(secretName)
                                    .get())
                            .filter(secret -> secret != null
                                    // Magic number: the old release must be at least 60 seconds old, to be sure
                                    // that there really is no other process in progress.
                                    // As we don't wait for the release with helm, this should be more than enough
                                    // time.
                                    && Instant.parse(secret.getMetadata().getCreationTimestamp())
                                            .isBefore(Instant.now().minus(Duration.ofSeconds(60))))
                            .forEach(secret -> k8sClient.secrets()
                                    .inNamespace(config.namespace())
                                    .withName(secret.getMetadata().getName())
                                    .delete());
                }
                throw e;
            }
        });
    }

    /**
     * The used helm library does not support, to set the path for the helm manager
     * cache.
     * As we don't want to have them in the project main dir, we have to move them
     * manually
     * before and afterwards.
     */
    private synchronized void helmDependencyUpdate(Helm helm) throws IOException {
        Path cachepath = Path.of(config.helmCachePath());
        Path basepath = Path.of(".");
        Files.createDirectories(cachepath);
        try {
            moveFiles(cachepath, basepath);
            // The helm repo update is
            // Helm.repo().update().call();
            helm.dependency().update().call();
        } finally {
            moveFiles(basepath, cachepath);
        }
    }

    private static final Pattern HELM_MANAGER_FILES = Pattern.compile("helm-manager-.+\\.(txt|yaml)");

    private void moveFiles(Path source, Path target) throws IOException {
        Files.list(source)
                .filter(path -> HELM_MANAGER_FILES.matcher(path.getFileName().toString()).matches())
                .forEach(path -> {
                    Path targetPath = target.resolve(path.getFileName());
                    try {
                        Files.move(path, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        log.warnf("Could not move helm manager file %s -> %s, error was: %s", path, targetPath,
                                e.getMessage());
                    }
                });
    }

    private void uninstall() {
        List<Release> helmReleases = Helm.list()
                .withNamespace(config.namespace())
                .call();

        // Will uninstall all helmReleases in the specified namespace
        // TODO: is this really the desired behavior or do we want to determine, which
        // helmreleases should be uninstalled?
        for (Release helmRelease : helmReleases) {
            Helm.uninstall(helmRelease.getName())
                    .withKubeConfig(kubeConfigPath)
                    .withNamespace(config.namespace())
                    .call();
        }

    }
}
