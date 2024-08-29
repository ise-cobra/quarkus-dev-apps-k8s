package com.ise112.shared.k8s.deployment;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

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
     * The namespace into which everything should be deployed
     */
    @WithDefault("dev-services")
    String namespace();

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
