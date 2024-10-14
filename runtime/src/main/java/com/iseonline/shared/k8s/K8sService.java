package com.iseonline.shared.k8s;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.logging.Log;

@ApplicationScoped
public class K8sService {

    @Inject
    K8sService() {
        Log.infof("Quarkus Dev Apps K8s started");
        // TODO: provide information about started services
    }
}
