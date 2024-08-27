package com.ise112.shared.k8s.deployment;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

import com.ise112.shared.k8s.deployment.helm.HelmDeployer;
import com.ise112.shared.k8s.deployment.ssh.SshDeployer;

import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.CuratedApplicationShutdownBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem.RunningDevService;
import io.quarkus.deployment.console.ConsoleInstalledBuildItem;
import io.quarkus.deployment.console.StartupLogCompressor;
import io.quarkus.deployment.dev.devservices.GlobalDevServicesConfig;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;

@BuildSteps(onlyIfNot = IsNormal.class, onlyIf = GlobalDevServicesConfig.Enabled.class)
public class K8sDevServicesProcessor {
    private static final Logger log = Logger.getLogger(K8sDevServicesProcessor.class);
    public static final String FEATURE = "quarkus-dev-apps-k8s";

    private static volatile HelmDeployer helmDeployer;
    private static volatile SshDeployer sshDeployer;

    @BuildStep
    public void startK8sDevService(
            K8sDevServicesBuildTimeConfig config,
            CuratedApplicationShutdownBuildItem closeBuildItem,
            Optional<ConsoleInstalledBuildItem> consoleInstalledBuildItem,
            LoggingSetupBuildItem loggingSetupBuildItem,
            BuildProducer<DevServicesResultBuildItem> producer) {

        StartupLogCompressor compressor = new StartupLogCompressor("Dev Apps K8S Services Starting:",
                consoleInstalledBuildItem,
                loggingSetupBuildItem);
        log.info("starting dev apps k8s devservice...");
        if (!config.enabled()) {
            // explicitly disabled
            log.debug("Not starting Dev Services for K8S, as it has been disabled in the config.");
            compressor.close();
            return;
        }

        try {
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(() -> {
                        if (helmDeployer == null) {
                            helmDeployer = new HelmDeployer();
                        }
                        RunningDevService helmDevService = helmDeployer.startServices(config);
                        producer.produce(helmDevService.toBuildItem());
                    }),
                    CompletableFuture.runAsync(() -> {
                        if (sshDeployer == null) {
                            sshDeployer = new SshDeployer();
                        }
                        RunningDevService sshDevService = sshDeployer.clusterConnection(config);
                        producer.produce(sshDevService.toBuildItem());
                    }))
                    .get(10, TimeUnit.MINUTES);

            compressor.close();
        } catch (Throwable t) {
            compressor.closeAndDumpCaptured();
            throw new RuntimeException(t);
        }
    }
}
