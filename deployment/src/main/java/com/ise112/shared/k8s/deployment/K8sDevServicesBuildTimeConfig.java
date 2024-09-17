package com.ise112.shared.k8s.deployment;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "ise.dev.k8s.devservices")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface K8sDevServicesBuildTimeConfig {

    /**
     * If Dev Services for K8S has been explicitly enabled or disabled.
     * Dev Services are generally enabled
     * by default, unless there is an existing configuration present.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * If Dev Services for K8S has been explicitly enabled or disabled.
     * Dev Services are generally enabled
     * by default, unless there is an existing configuration present.
     */
    @WithDefault("dev")
    String chartPath();

    /**
     * If Dev Services for K8S has been explicitly enabled or disabled.
     * Dev Services are generally enabled
     * by default, unless there is an existing configuration present.
     */
    @WithDefault("rancher-desktop")
    String kubeContext();

    /**
     * The namespace into which everything should be deployed.
     */
    @WithDefault("dev-services")
    String namespace();

    /**
     * Whether the helm deployed should be enabled, true by default.
     * It may be disabled if you want to develop two services at the same time and
     * connect to the existing deployments of another instance running.
     */
    @WithDefault("true")
    @WithName("helm.enabled")
    boolean helmEnabled();

    /**
     * If set to true, the extension will continue even it if fails to establish
     * port forwardings from/to the cluster. This may be useful if an extensions is
     * already running and provides the same ports.
     */
    @WithDefault("false")
    boolean ignorePortFailures();

    /**
     * The secret to access required registries. Also used for helm chart
     * dependencies. Must be in the form
     * <code>
     * {
     *     "auths": {
     *         "registry.example.org": {
     *             "username":"user",
     *             "password":"token"
     *         }
     *     }
     * }
     * </code>
     */
    Optional<String> registrySecret();

    /**
     * The name of the registry secret in the cluster.
     */
    @WithDefault("registry-secret")
    String registrySecretName();

    /**
     * Whether the whole deployed dev context should be stopped and cleaned before
     * the dev-services are started. Useful, if there is some old data in it which
     * you don't want anymore.
     */
    @WithDefault("false")
    boolean stopCleanRestart();

    /**
     * Whether the whole deployed dev context should be shutdown after quarkus:dev
     * has finished.
     */
    @WithDefault("false")
    boolean shutdown();

    /**
     * The image of the ssh server to user
     */
    @WithDefault("linuxserver/openssh-server:latest")
    String sshImage();

    /**
     * The username to access the openssh server. Since it is only reachable if
     * already tunneled into the k8s cluster, it does not need to be secure.
     */
    @WithDefault("quarkus-dev-apps")
    String sshUsername();

    /**
     * The password to access the openssh server. Since it is only reachable if
     * already tunneled into the k8s cluster, it does not need to be secure.
     */
    @WithDefault("quarkus-dev-apps")
    String sshPassword();
}
