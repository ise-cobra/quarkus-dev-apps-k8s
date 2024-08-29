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

## How to use

Add the dependency:

```xml
<dependency>
    <groupId>com.ise112.quarkus</groupId>
    <artifactId>quarkus-dev-apps-k8s</artifactId>
    <version>${version}</version>
</dependency>
```

Then, by default, the application will check for a `dev` folder in your project in which it expects a helm chart, which will be deployed to the kubernetes context "rancher-desktop". Folder and kubernetes context are configurable.

E.g. if you need keycloak, you could use the following `Chart.yaml` and `values.yaml` files:

`Chart.yaml`:

```yaml
apiVersion: v2
name: ise-dev
version: 0.0.1
appVersion: 0.0.1
type: application
dependencies:
  - name: postgresql
    version: 15.5.23
    repository: oci://registry-1.docker.io/bitnamicharts
  - name: keycloak
    version: 22.1.2
    repository: oci://registry-1.docker.io/bitnamicharts
```

`values.yaml`:

```yaml
global:
  defaultStorageClass: local-path

portforwarding:
  services:
    - name: keycloak
      localPort: 8081
      service:
        port: 80
        name: keycloak
  reverse: 
    - localPort: 8080
      service:
        port: 8080
        name: keycloak

postgresql:
  fullnameOverride: postgresql
  auth:
    # Passwort fuer den "postgres" admin user
    postgresPassword: "postgres"
  architecture: standalone
  primary:
    persistentVolumeClaimRetentionPolicy:
      enabled: true
      whenDeleted: Delete
    initdb:
     scripts:
       my_init_script.sql: |
          CREATE DATABASE keycloak;
          CREATE USER keycloak WITH ENCRYPTED PASSWORD 'keycloak';
          GRANT ALL PRIVILEGES ON DATABASE keycloak TO keycloak;
          \c keycloak;
          CREATE SCHEMA keycloak AUTHORIZATION keycloak;
          \c postgres;

keycloak:
  fullnameOverride: keycloak
  resourcesPreset: "none"
  auth:
    adminUser: admin
    adminPassword: password
  externalDatabase:
    host: postgresql
    database: keycloak
    user: keycloak
    password: keycloak
  extraEnvVars:
    - name: KC_DB_SCHEMA
      value: keycloak
  postgresql:
    enabled: false
```

The section `portforwarding` is a special section evaluated by the extension itself and manages, which ports should be forwarded from localhost to which services in the cluster.




