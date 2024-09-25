package com.ise112.shared.k8s.deployment.ssh;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.jboss.logging.Logger;

import com.ise112.shared.k8s.deployment.K8sDevServicesBuildTimeConfig;
import com.ise112.shared.k8s.deployment.ssh.PortsConfiguration.PortForwarding;
import com.ise112.shared.k8s.deployment.ssh.PortsConfiguration.ReverseProxy;
import com.ise112.shared.k8s.deployment.utils.K8sDevServicesUtils;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem.RunningDevService;
import io.quarkus.deployment.dev.devservices.GlobalDevServicesConfig;

@BuildSteps(onlyIfNot = IsNormal.class, onlyIf = GlobalDevServicesConfig.Enabled.class)
public class SshDeployer implements Closeable {
    private static final Logger log = Logger.getLogger(SshDeployer.class);

    private static final String FEATURE = "K8sDevServicesSshTunnel";

    public static final String SSH_DEPLOYMENT_NAME = "quarkus-dev-ssh";

    private static volatile K8sDevServicesBuildTimeConfig config;

    private static volatile KubernetesClient k8sClient;

    private static volatile RunningDevService devService;

    private static volatile Session session = null;

    private static volatile PortsConfiguration portsConfg;

    private static volatile LocalPortForward portForward;

    private static volatile ScheduledFuture<?> k8sFuture;

    @BuildStep
    public DevServicesResultBuildItem clusterConnection(K8sDevServicesBuildTimeConfig config) {
        if (!config.enabled()) {
            return null;
        }
        if (devService != null) {
            // currently no update of configuration implemented
            return devService.toBuildItem();
        }
        SshDeployer.config = config;

        if (k8sClient == null) {
            Config k8sConfig = Config.autoConfigure(config.kubeContext());
            k8sClient = new KubernetesClientBuilder()
                    .withConfig(k8sConfig)
                    .build();
        }

        deploySsh();
        Map<String, String> overrideConfigs = connectSSH();

        devService = new RunningDevService(FEATURE, null, this::close, overrideConfigs);

        return devService.toBuildItem();
    }

    @Override
    public void close() throws IOException {
        closeSsh();
        if (k8sClient != null) {
            try {
                k8sClient.close();
            } catch (Exception e) {
                log.warn("Error during closing kuberne connection for dev apps k8s:", e);
            }
        }
    }

    private void closeSsh() {
        if (session != null) {
            Session tempSession = session;
            session = null;
            // Make sure, all port forwardings are deleted to free the local ports,
            // otherwise they are still blocked
            try {
                for (String pf : tempSession.getPortForwardingL()) {
                    tempSession.delPortForwardingL(Integer.parseInt(pf.split(":")[0]));
                }
                for (String rp : tempSession.getPortForwardingR()) {
                    String[] split = rp.split(":");
                    tempSession.delPortForwardingR(split[1], Integer.parseInt(split[0]));
                }
            } catch (JSchException e) {
                log.warn("Cloud not close all port forwardings:", e);
            }
            tempSession.disconnect();
        }
    }

    private void deploySsh() {
        log.infof("Starting SSH pod...");

        List<ContainerPort> ports = new ArrayList<>();
        ports.add(new ContainerPortBuilder()
                .withContainerPort(22)
                .withName("ssh")
                .build());

        // make sure the namespace exists before deploying anything
        Namespace namespace = new NamespaceBuilder()
                .withNewMetadata()
                .withName(config.namespace())
                .endMetadata()
                .build();
        k8sClient.namespaces()
                .resource(namespace)
                .createOr(t -> namespace);

        RollableScalableResource<Deployment> deploymentResource = getResource(k8sClient.apps().deployments(),
                "sshdeploy.yaml",
                config.namespace(),
                config.sshImage(),
                config.sshUsername(),
                config.sshPassword());

        Path valuesYamlPath = Path.of(config.chartPath(), "values.yaml");
        portsConfg = PortsConfiguration.parseConfig(valuesYamlPath);

        addPorts(deploymentResource);

        deploymentResource.createOr(t -> t.patch());

        // Kubernetes might need a short time, before it triggers the recalculation of
        // the replicaset, give it a little bit of time, otherwise we might connect to
        // an old pod
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
        }

        // Wait till ssh deployment is in ready state
        K8sDevServicesUtils.waitTill(5 * 60000, () -> k8sClient.apps()
                .deployments()
                .inNamespace(config.namespace())
                .withName(SSH_DEPLOYMENT_NAME)
                .isReady());
    }

    private void addPorts(RollableScalableResource<Deployment> deploymentResource) {
        Deployment deployment = deploymentResource.get();
        List<Integer> existingPorts = Collections.emptyList();
        if (deployment != null) {
            existingPorts = deployment.getSpec().getTemplate()
                    .getSpec().getContainers().stream()
                    .flatMap(c -> c.getPorts().stream())
                    // All other properties than the port number are dismissed by purpose
                    .map(cp -> cp.getContainerPort())
                    .toList();
        }

        List<Integer> proxyPorts = portsConfg.getReverseProxies().stream()
                .map(p -> p.getServicePort())
                .toList();

        List<ContainerPort> ports = Stream.concat(proxyPorts.stream(), existingPorts.stream())
                .distinct()
                .map(p -> new ContainerPortBuilder()
                        .withContainerPort(p)
                        .build())
                .toList();

        deploymentResource.item()
                .getSpec().getTemplate()
                .getSpec().getContainers()
                .get(0).getPorts().addAll(ports);
    }

    private int getFreePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> connectSSH() {
        try {
            int localSshPort = getFreePort();

            // It can take quite some time before the ssh server is really ready to accept
            // connections, therefore retry some times...
            int retryCount = 30;
            for (int i = 0; i < retryCount && session == null; i++) {
                try {
                    createK8sTunnel(localSshPort);
                    createSshSession(config, localSshPort);
                } catch (Exception e) {
                    if (i < retryCount - 1) {
                        log.warnf("Tunnel to k8s cluster failed, retrying %d/%d", (i + 1), retryCount);
                        try {
                            Thread.sleep(1000);
                        } catch (Exception e2) {
                        }
                    } else {
                        throw new RuntimeException(e);
                    }
                }
            }

            Map<String, String> overrideConfigs = new HashMap<>();
            List<Exception> errors = portsConfg.getPortForwardings().stream()
                    .map(p -> createPortForwarding(overrideConfigs, p))
                    .filter(e -> e != null)
                    .toList();
            if (!errors.isEmpty()) {
                throw errors.get(0);
            }
            errors = portsConfg.getReverseProxies().stream()
                    .map(p -> createReverseProxy(p))
                    .filter(e -> e != null)
                    .toList();
            if (!errors.isEmpty()) {
                throw errors.get(0);
            }

            return overrideConfigs;
        } catch (JSchException e) {
            log.warn("Failed to establish ssh connection", e);
            throw new RuntimeException(e);
        } catch (IOException e) {
            log.warn("Failed to read values.yaml", e);
            throw new RuntimeException(e);
        } catch (Exception e) {
            log.warn("Error during connect ssh", e);
            throw new RuntimeException(e);
        }
    }

    private void createK8sTunnel(int localSshPort) {
        if (k8sFuture != null) {
            k8sFuture.cancel(false);
        }
        Pod[] sshPod = new Pod[1];
        k8sFuture = K8sDevServicesUtils.createAndWatch(() -> {
            if (portForward != null) {
                try {
                    portForward.close();
                } catch (Exception e2) {
                }
            }
            PodList podList = k8sClient.pods()
                    .inNamespace(config.namespace())
                    .withLabel("app", SSH_DEPLOYMENT_NAME)
                    .list();
            if (podList.getItems().size() > 1) {
                throw new IllegalStateException("More than one ssh pod found, did not start correctly?");
            }
            sshPod[0] = podList.getItems().get(0);
            portForward = k8sClient.pods()
                    .inNamespace(config.namespace())
                    .withName(sshPod[0].getMetadata().getName())
                    .portForward(2222, localSshPort);
            if (!portForward.isAlive()) {
                log.warn("Portforwarding to SSH pod did not succeed!");
            }
        }, () -> {
            if (!portForward.isAlive()) {
                return false;
            }
            // isAlive returns true, even if the target pod is already deleted
            if (k8sClient.pods()
                    .inNamespace(config.namespace())
                    .withName(sshPod[0].getMetadata().getName()).get() == null) {
                return false;
            }
            return true;
        }, 10, TimeUnit.SECONDS);
    }

    private void createSshSession(K8sDevServicesBuildTimeConfig config, int localSshPort) throws JSchException {
        K8sDevServicesUtils.createAndWatch(() -> {
            closeSsh();
            log.infof("Connecting ssh on port %d", localSshPort);
            try {
                session = new JSch().getSession(config.sshUsername(), "127.0.0.1", localSshPort);
                session.setPassword(config.sshPassword());
                session.setConfig("StrictHostKeyChecking", "no");
                session.connect(15000);
            } catch (JSchException e) {
                throw new RuntimeException("Could not initiate SSH session", e);
            }
        }, () -> {
            if (session == null || !session.isConnected()) {
                return false;
            }
            try {
                session.sendKeepAliveMsg();
            } catch (Exception e) {
                return false;
            }
            return true;
        }, 10, TimeUnit.SECONDS);
    }

    private Exception createPortForwarding(Map<String, String> overrideConfigs, PortForwarding pf) {
        // Real port should be used again, if the connection is lost
        K8sDevServicesUtils.createAndWatch(() -> {
            try {
                pf.setRealPort(
                        session.setPortForwardingL(pf.getRealLocalPort(), pf.getServiceName(), pf.getServicePort()));
                overrideConfigs.put(pf.getName() + ".url", "localhost:" + pf.getRealLocalPort());
                log.infof("Port forwarding active for %s on %d", pf.getName(), pf.getRealLocalPort());
            } catch (JSchException exc) {
                log.warnf(exc, "Failed to create port forwarding for %s:", pf.getName());
            }
        }, () -> {
            // Checks whether the connection is still established
            try {
                String[] portForwardings = session.getPortForwardingL();
                String ourconfig = pf.getJschString();
                if (!Arrays.stream(portForwardings).anyMatch(ourconfig::equals)) {
                    log.warnf("SSH port forwarding lost, trying to recreate for %s on %d", pf.getName(),
                            pf.getRealLocalPort());
                    return false;
                }
            } catch (JSchException e) {
                log.warnf("SSH port forwarding lost, trying to recreate for %s on %d", pf.getName(),
                        pf.getRealLocalPort());
                return false;
            }
            return true;
        }, 10, TimeUnit.SECONDS);
        return null;
    }

    private Exception createReverseProxy(ReverseProxy p) {
        // SSH tunnel from cluster to localhost
        K8sDevServicesUtils.createAndWatch(() -> {
            try {
                session.setPortForwardingR("0.0.0.0", p.getLocalPort(), "localhost", p.getLocalPort());
                log.infof("Reverse proxy active for service %s:%d to local port %d", p.getServiceName(),
                        p.getServicePort(), p.getLocalPort());
            } catch (JSchException e) {
                log.warnf(e, "Could not create reverse proxy for service %s:%d to local port %d", p.getServiceName(),
                        p.getServicePort(), p.getLocalPort());
            }
        }, () -> {
            // Checks whether the connection is still established
            try {
                String[] portForwardings = session.getPortForwardingR();
                String ourconfig = p.getJschString();
                if (!Arrays.stream(portForwardings).anyMatch(ourconfig::equals)) {
                    log.warnf("Lost reverse proxy connection for service %s:%d to local port %d", p.getServiceName(),
                            p.getServicePort(), p.getLocalPort());
                    return false;
                }
            } catch (JSchException e) {
                return false;
            }
            return true;
        }, 10, TimeUnit.SECONDS);

        ServiceResource<Service> serviceResource = getResource(k8sClient.services(), "sshservice.yaml",
                p.getServiceName(),
                config.namespace(),
                p.getServicePort(),
                p.getLocalPort());
        // Service creation inside the cluster
        K8sDevServicesUtils.createAndWatch(() -> {
            serviceResource.createOr(t -> t.patch());
        }, () -> {
            // Easiest way here to just recreate it instead of real checking, wheter it
            // still exists
            serviceResource.createOr(t -> t.patch());
            return true;
        },
                // TODO: should the time interval be configurable?
                10, TimeUnit.SECONDS);

        return null;
    }

    private <T extends Resource<?>> T getResource(MixedOperation<?, ?, T> loader, String yamlFile, Object... args) {
        String yaml;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(yamlFile)) {
            yaml = IOUtils.toString(is, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        yaml = yaml.formatted(args);
        T resource;
        try (InputStream is = new ByteArrayInputStream(yaml.getBytes())) {
            resource = loader.load(is);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return resource;
    }
}
