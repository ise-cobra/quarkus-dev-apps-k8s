package com.ise112.shared.k8s.deployment.utils;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

public class K8sDevServicesUtils {
    private static final Logger log = Logger.getLogger(K8sDevServicesUtils.class);

    public static boolean waitTill(long timeout, Supplier<Boolean> condition) {
        long timeoutAt = System.currentTimeMillis() + timeout;
        boolean result = false;
        while (timeoutAt > System.currentTimeMillis() &&
                !(result = condition.get())) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
        }

        return result || condition.get();
    }

    private static ScheduledExecutorService executor;

    /**
     * Will create a resource and watch it via the given condition in a fixed time
     * interval. If it fails, it'll try to recreate the resource.
     * Creator will be invoked
     *
     * @param creator the interface to (re)create the resource
     * @param condition whether the resource is still running
     * @param period the time period in which to check
     * @param unit the time unit in which to check
     * @return
     */
    public static ScheduledFuture<?> createAndWatch(Runnable creator, Supplier<Boolean> condition, int period,
            TimeUnit unit) {
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor();
        }

        // Create the resource at the beginning. If there is already an error, it should
        // be handled by the source.
        creator.run();

        return executor.scheduleAtFixedRate(() -> {
            try {
                if (!condition.get()) {
                    creator.run();
                }
            } catch (Exception e) {
                log.warn("Error during createAndWatch", e);
            }
        }, period, period, unit);
    }
}
