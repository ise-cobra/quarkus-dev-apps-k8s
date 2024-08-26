# Quarkus Dev Apps K8s

A Quarkus extension to start dependent services in a Kubernetes cluster during quarkus dev mode

## Description

With Quarkus Dev Extensions, services are started as Docker containers and each extension manages its own docker container. The lifecycle of the docker container is bound to the lifetime of the Quarkus dev runtime, meaning all services have to start again if you have to restart the Quarkus dev runtime. Additionally you have to create a dev extension for each service you depend on (if it doesn't already exist).

Therefore, this extension wants to overcome the following problems:

- Long startup times of `quarkus dev`
- Easy adding of new services, for which no quarkus dev extension exists yet
- Flexibility in configuration of services (e.g. if you have special configuration requirements of dependent services)
- Computer doesn't has the resources to run the whole stack

Additionally, we wanted to use Kubernetes instead of Docker where the containers should run, so the development environment is more similar to our final stack. Of course, this is only the case if you use Kubernetes to deploy yours apps to, too.
