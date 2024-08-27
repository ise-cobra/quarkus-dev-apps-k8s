package com.ise112.shared.k8s.deployment.utils;

import java.util.function.Supplier;

public class K8sDevServicesUtils {

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
}
