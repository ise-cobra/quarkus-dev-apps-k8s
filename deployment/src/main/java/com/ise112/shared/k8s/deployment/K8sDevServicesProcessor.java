package com.ise112.shared.k8s.deployment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

import org.apache.commons.io.IOUtils;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.marcnuri.helm.Helm;

import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.NamedContext;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.internal.KubeConfigUtils;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.CuratedApplicationShutdownBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem.RunningDevService;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.console.ConsoleInstalledBuildItem;
import io.quarkus.deployment.console.StartupLogCompressor;
import io.quarkus.deployment.dev.devservices.GlobalDevServicesConfig;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;

@BuildSteps(onlyIfNot = IsNormal.class, onlyIf = GlobalDevServicesConfig.Enabled.class)
public class K8sDevServicesProcessor {
    private static final Logger log = Logger.getLogger(K8sDevServicesProcessor.class);
    private static final String FEATURE = "quarkus-dev-apps-k8s";

    private static volatile RunningDevService devService;
    private static volatile boolean first = true;

    private static volatile Session session = null;
    private static volatile KubernetesClient k8sClient;

    @BuildStep
    public DevServicesResultBuildItem startK8sDevService(
            K8sDevServicesBuildTimeConfig devServicesBuildTimeConfig,
            DockerStatusBuildItem dockerStatusBuildItem,
            Optional<ConsoleInstalledBuildItem> consoleInstalledBuildItem,
            LaunchModeBuildItem launchMode,
            CuratedApplicationShutdownBuildItem closeBuildItem,
            LoggingSetupBuildItem loggingSetupBuildItem,
            GlobalDevServicesConfig devServicesConfig) {

        log.info("starting k8s-devservice...");

        StartupLogCompressor compressor = new StartupLogCompressor(
                (launchMode.isTest() ? "(test) " : "") + "K8S Dev Services Starting:",
                consoleInstalledBuildItem,
                loggingSetupBuildItem);
        try {
            DevServicesResultBuildItem.RunningDevService newDevService = startServices(devServicesBuildTimeConfig);
            if (newDevService != null) {
                devService = newDevService;
                if (newDevService.isOwner()) {
                    log.info("Dev Services for K8S JetStream started.");
                }
            }
            if (devService == null) {
                compressor.closeAndDumpCaptured();
            } else {
                compressor.close();
            }
        } catch (Throwable t) {
            compressor.closeAndDumpCaptured();
            throw new RuntimeException(t);
        }

        if (devService == null) {
            return null;
        }

        // Configure the watch dog
        if (first) {
            first = false;
            Runnable closeTask = () -> {
                if (devService != null) {
                    shutdownBroker();

                    log.info("Dev Services for K8S JetStream shut down.");
                }
                first = true;
                devService = null;
            };
            closeBuildItem.addCloseTask(closeTask, true);
        }
        return devService.toBuildItem();
    }

    private void shutdownBroker() {
        if (devService != null) {
            try {
                if (session != null) {
                    session.disconnect();
                }
                if (k8sClient != null) {
                    k8sClient.close();
                }
            } catch (Throwable e) {
                log.error("Failed to stop the K8S JetStream broker", e);
            } finally {
                devService = null;
            }
        }
    }

    private RunningDevService startServices(K8sDevServicesBuildTimeConfig config) throws Exception {
        if (!config.enabled()) {
            // explicitly disabled
            log.debug("Not starting Dev Services for K8S, as it has been disabled in the config.");
            return null;
        }

        // TODO: path from configuration, especially "target"
        Path kubeConfigPath = Path.of("target", "kubeconfig.yaml");
        Config k8sConfig = Config.autoConfigure(config.kubeContext());
        // TODO: if k8sClient null only
        k8sClient = new KubernetesClientBuilder()
                .withConfig(k8sConfig)
                .build();

        // TODO: helm undeploy if required
        saveKubeConfig(config.kubeContext(), kubeConfigPath, k8sConfig);
        Helm helm = new Helm(Paths.get(config.chartPath()));
        upgradeDeployment(config, kubeConfigPath, helm);

        int localPort = getFreePort();
        deploySshPod(config, localPort);
        Map<String, String> overrideConfigs = new HashMap<>();
        connectSSH(config, localPort, overrideConfigs);

        // TODO: kontrollieren, ob irgendwas geschlossen werden muss
        return new RunningDevService(FEATURE, null, null, overrideConfigs);
    }

    private void upgradeDeployment(K8sDevServicesBuildTimeConfig config, Path kubeConfigPath, Helm helm) {
        long time = System.currentTimeMillis();
        helm.dependency().build();
        log.infof("Time for dependency build: %d", (System.currentTimeMillis() - time));
        time = System.currentTimeMillis();
        helm.upgrade()
                .withKubeConfig(kubeConfigPath)
                .withName("quarkus-dev-k8s")
                .withNamespace(config.namespace())
                .install()
                .createNamespace()
                // There is currently a bug which prevents the wait, resulting in the error
                // "beginning wait for resources with timeout of 0s" and "client rate limiter
                // would exceed context time"
                // .waitReady()
                // .debug()
                .call();

        // Wait till all pods in the namespace are ready, since helm waitReady is
        // currently not working
        waitTill(5 * 60000, () -> k8sClient.pods()
                .inNamespace(config.namespace())
                .resources()
                .allMatch(p -> p.isReady()));

        log.infof("Time for helm install: %d", (System.currentTimeMillis() - time));
    }

    /**
     * Helm requires a kubeconfig file and can't set a kube context here. Therefore
     * we must export the required kube context into a file and load it from there.
     * 
     * @param kubeContext    the kubernetes context to use
     * @param kubeConfigPath path to which the kubernetes config should be written
     * @param config         the kubernetes config read by fabric8
     * @throws IOException
     */
    private void saveKubeConfig(String kubeContext, Path kubeConfigPath, Config config)
            throws IOException {
        io.fabric8.kubernetes.api.model.Config kubeConfig = KubeConfigUtils.parseConfig(config.getFile());
        kubeConfig.setCurrentContext(kubeContext);
        NamedContext currentContext = KubeConfigUtils.getCurrentContext(kubeConfig);
        kubeConfig.setContexts(Arrays.asList(currentContext));
        kubeConfig.setClusters(kubeConfig.getClusters().stream()
                .filter(c -> Objects.equals(c.getName(), currentContext.getContext().getCluster()))
                .toList());
        kubeConfig.setUsers(kubeConfig.getUsers().stream()
                .filter(u -> Objects.equals(u.getName(), currentContext.getContext().getUser()))
                .toList());
        KubeConfigUtils.persistKubeConfigIntoFile(kubeConfig, kubeConfigPath.toString());
    }

    private void deploySshPod(K8sDevServicesBuildTimeConfig config, int localSshPort)
            throws IOException {
        log.infof("Starting SSH tunnel with port %d", localSshPort);

        List<ContainerPort> ports = new ArrayList<>();
        ports.add(new ContainerPortBuilder()
                .withContainerPort(22)
                .withName("ssh")
                .build());

        String sshPodYaml;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("file.txt")) {
            sshPodYaml = IOUtils.toString(is, StandardCharsets.UTF_8);
        }

        sshPodYaml = sshPodYaml.formatted(config.namespace(), config.sshImage(), config.sshUsername(),
                config.sshPassword());
        PodResource podResource;
        try (InputStream is = new ByteArrayInputStream(sshPodYaml.getBytes())) {
            podResource = k8sClient.pods().load(is);
        }
        Pod podInstance = podResource.get();
        if (podInstance == null) {
            podResource.create();
        } else if (!similarPods(podResource, podInstance)) {
            podResource.delete();
            // Wait till the ssh pod is deleted
            waitTill(5 * 60000, () -> k8sClient.pods()
                    .inNamespace(config.namespace())
                    .withName(config.sshPodName())
                    .get() != null);
            podResource.create();
        }

        // Wait till ssh pod is in ready state
        waitTill(5 * 60000, () -> k8sClient.pods()
                .inNamespace(config.namespace())
                .withName(config.sshPodName())
                .isReady());

        k8sClient.pods().inNamespace(config.namespace())
                .withName(config.sshPodName())
                .portForward(2222, localSshPort);
    }

    private boolean waitTill(long timeout, Supplier<Boolean> condition) {
        long timeoutAt = System.currentTimeMillis() + timeout;
        boolean result = false;
        while (timeoutAt < System.currentTimeMillis() &&
                !(result = condition.get())) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
        }

        return result || condition.get();
    }

    private boolean similarPods(PodResource podResource, Pod podInstance) {
        // TODO: check whether the configurations are the same
        return true;
    }

    private void connectSSH(K8sDevServicesBuildTimeConfig config, int localSshPort, Map<String, String> overrideConfigs)
            throws JSchException, IOException {
        try {
            log.infof("Connecting ssh on port %d", localSshPort);
            session = new JSch().getSession(config.sshUsername(), "127.0.0.1", localSshPort);
            session.setPassword(config.sshPassword());
            session.setConfig("StrictHostKeyChecking", "no");

            session.connect();

            log.infof("Starting Port Forwarding for tunnels");
            Path valuesYamlPath = Path.of(config.chartPath(), "values.yaml");
            ObjectMapper yamlMapper = new ObjectMapper(
                    new YAMLFactory().disable(YAMLGenerator.Feature.USE_NATIVE_TYPE_ID));
            JsonNode valuesYaml = yamlMapper.readTree(valuesYamlPath.toFile());
            List<JSchException> errors = Optional.of(valuesYaml.get("portforwarding"))
                    .map(n -> n.get("services"))
                    .map(s -> StreamSupport.stream(s.spliterator(), false)
                            .map(e -> {
                                try {
                                    String name = e.get("name").asText();
                                    int localPort = e.get("localPort").asInt();
                                    int servicePort = e.get("service").get("port").asInt();
                                    String serviceName = e.get("service").get("name").asText();
                                    int realPort = session.setPortForwardingL(localPort, serviceName, servicePort);
                                    overrideConfigs.put(name + ".url", "localhost:" + realPort);
                                    log.infof("Port forwarding active for %s on %d", name, realPort);
                                } catch (JSchException exc) {
                                    return exc;
                                }
                                return null;
                            }).filter(e -> e != null).toList())
                    .orElseGet(() -> Collections.emptyList());

            if (!errors.isEmpty()) {
                throw errors.get(0);
            }
        } catch (JSchException e) {
            log.warnf("Failed to establish ssh connection:", e);
            throw e;
        } catch (IOException e) {
            log.warnf("Failed to read values.yaml:", e);
            throw e;
        }
    }

    private int getFreePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
