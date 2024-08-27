package com.ise112.shared.k8s.deployment.ssh;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import org.apache.commons.io.IOUtils;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.ise112.shared.k8s.deployment.K8sDevServicesBuildTimeConfig;
import com.ise112.shared.k8s.deployment.K8sDevServicesProcessor;
import com.ise112.shared.k8s.deployment.utils.K8sDevServicesUtils;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem.RunningDevService;

public class SshDeployer implements Closeable {
    private static final Logger log = Logger.getLogger(SshDeployer.class);

    public static final String SSH_POD_NAME = "quarkus-dev-ssh";

    private KubernetesClient k8sClient;

    private RunningDevService devService;

    private Session session = null;

    public RunningDevService clusterConnection(K8sDevServicesBuildTimeConfig config) {
        if (devService != null) {
            // currently no update of configuration implemented
            return devService;
        }

        Map<String, String> overrideConfigs = new HashMap<>();

        if (k8sClient == null) {
            Config k8sConfig = Config.autoConfigure(config.kubeContext());
            k8sClient = new KubernetesClientBuilder()
                    .withConfig(k8sConfig)
                    .build();
        }

        int localPort = getFreePort();
        deploySshPod(config, localPort);
        connectSSH(config, localPort, overrideConfigs);

        return new RunningDevService(K8sDevServicesProcessor.FEATURE, null, this::close, overrideConfigs);
    }

    @Override
    public void close() throws IOException {
        if (session != null) {
            try {
                session.disconnect();
            } catch (Exception e) {
                log.warn("Error during closing ssh connection for dev apps k8s:", e);
            }
        }
        if (k8sClient != null) {
            try {
                k8sClient.close();
            } catch (Exception e) {
                log.warn("Error during closing kuberne connection for dev apps k8s:", e);
            }
        }
    }

    private void deploySshPod(K8sDevServicesBuildTimeConfig config, int localSshPort) {
        log.infof("Starting SSH tunnel with port %d", localSshPort);

        List<ContainerPort> ports = new ArrayList<>();
        ports.add(new ContainerPortBuilder()
                .withContainerPort(22)
                .withName("ssh")
                .build());

        String sshPodYaml;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("sshpod.yaml")) {
            sshPodYaml = IOUtils.toString(is, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        sshPodYaml = sshPodYaml.formatted(config.namespace(), config.sshImage(), config.sshUsername(),
                config.sshPassword());
        PodResource podResource;
        try (InputStream is = new ByteArrayInputStream(sshPodYaml.getBytes())) {
            podResource = k8sClient.pods().load(is);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Pod podInstance = podResource.get();
        if (podInstance == null) {
            podResource.create();
        } else if (!similarPods(podResource, podInstance)) {
            podResource.delete();
            // Wait till the ssh pod is deleted
            K8sDevServicesUtils.waitTill(5 * 60000, () -> k8sClient.pods()
                    .inNamespace(config.namespace())
                    .withName(SSH_POD_NAME)
                    .get() != null);
            podResource.create();
        }

        // Wait till ssh pod is in ready state
        K8sDevServicesUtils.waitTill(5 * 60000, () -> k8sClient.pods()
                .inNamespace(config.namespace())
                .withName(SSH_POD_NAME)
                .isReady());

        // Give a little extra time for the pod, otherwise the ssh connection fails.
        // Don't know why.
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
        }

        LocalPortForward portForward = k8sClient.pods().inNamespace(config.namespace())
                .withName(SSH_POD_NAME)
                .portForward(2222, localSshPort);
        if (!portForward.isAlive()) {
            log.warn("Portforwarding to SSH pod did not succeed!");
        }
    }

    private boolean similarPods(PodResource podResource, Pod podInstance) {
        // TODO: check whether the configurations are the same
        return true;
    }

    private int getFreePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void connectSSH(K8sDevServicesBuildTimeConfig config, int localSshPort,
            Map<String, String> overrideConfigs) {
        try {
            log.infof("Connecting ssh on port %d", localSshPort);
            session = new JSch().getSession(config.sshUsername(), "127.0.0.1", localSshPort);
            session.setPassword(config.sshPassword());
            session.setConfig("StrictHostKeyChecking", "no");

            session.connect(15000);

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
            throw new RuntimeException(e);
        } catch (IOException e) {
            log.warnf("Failed to read values.yaml:", e);
            throw new RuntimeException(e);
        }
    }
}
