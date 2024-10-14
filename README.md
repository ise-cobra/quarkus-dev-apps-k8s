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
    <groupId>com.ise-online.quarkus</groupId>
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
  reverseProxy:
    - localPort: ${quarkus.http.port}
      service:
        port: 8080
        name: quarkus-app

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

## Port forwarding

To communicate with the services in the cluster, the block `portforwarding` in the cluster will be used. This block is evaluated from the extension and creates tunnels to and from the cluster.

There are two tunnel types: into the cluster, called `services` (as they connect to the services) and `reverseProxy` to provide access to a local service.

### Service Tunnels

The service tunnels are configured the following way:

```yaml
portforwarding:
  services:
    - name: keycloak
      localPort: 8081
      service:
        name: keycloak
        port: 80
```

Where the variables are defined as followed:

| Variable | Definition |
|----------|------------|
| name | Used for variable overrding during quarkus startup, a variable `${name}.url` with the url to the target will be provided. |
| localPort | The port on which the service should be accessible locally. If 0, a random port will be used. |
| service.name | The name of the service in the k8s cluster to connect with. |
| service.port | The port of the service in the k8s cluster to connect with. |

### Reverse proxy

If a service inside of the cluster needs to communicate with your application, you can be provide a tunnel from the cluster to the local environment:

```yaml
portforwarding:
  reverseProxy: 
    - localPort: ${quarkus.http.port:=8080}
      service:
        port: 8080
        name: quarkus-app
```

With this, a service will be created whose port will be forwarded to the local port.

| Variable | Definition |
|----------|------------|
| localPort | The local port which should be exposed to the cluster. |
| service.name | The name of the service in the k8s cluster whose port should be forwarded to your local environment. |
| service.port | The port of the service in the k8s cluster which should be forwarded to your local environment. |

Known limitations: currently, there can be only one port per service. So even if you define multiple of them in different objects, the service will still only forward a single port.

## Developing at two Quarkus apps at the same time

There may be cases, where you are developing two Quarkus applications at the same time, e.g. app A communicates with app B.

In the standard case, you have in app A configured, that app B should be started automatically as a pod with this extension and in app B, that it should start app A. When developing against each other you want to start the environment only once and don't change anything in the configuration.

In this case the optimal solution is to have for both apps the same helm chart deployment but different port forwardings and in the cluster a service for app A and B, which is then forwarded to the local apps.

App A:

`Chart.yaml`:

```yaml
apiVersion: v2
name: ise-dev
version: 0.0.1
appVersion: 0.0.1
type: application
```

`values.yaml`:

```yaml
portforwarding:
  services:
    - name: B
      localPort: 0
      service:
        port: 8080
        name: appb
  reverseProxy: 
    - localPort: ${quarkus.http.port}
      service:
        port: 8080
        name: appa
```

App B:

`Chart.yaml`:

```yaml
apiVersion: v2
name: ise-dev
version: 0.0.1
appVersion: 0.0.1
type: application
```

`values.yaml`:

```yaml
portforwarding:
  services:
    - name: B
      localPort: 0
      service:
        port: 8080
        name: appa
  reverseProxy: 
    - localPort: ${quarkus.http.port}
      service:
        port: 8080
        name: appb
        scaleDown: true
```

The option `service.scaleDown` in the reverseProxy will scale down a deployment or statefulset with the same name as the deployment. This might be helpful if the service is not allowed to run even if the service doesn't send any traffic to it, since it listens e.g. on a message broker.

### Handling different configuration when working with two apps

If you are developing two apps and they have different dependencies and you want to avoid having the one startup kill the deployments of the other you can also use multiple deployments by using the following structure:

```folder
├ quarkus-app
├── src
└── dev
  ├── chart1
  │ ├── Chart.yaml
  │ └── values.yaml
  └── chart2
    ├── Chart.yaml
    └── values.yaml
```

The quarkus extension will the deploy all the charts in the direct subfolders of the `dev` directory, as long as a `Chart.yaml` file exists there. The name of the deployment will be the folder name and the port configurations will be merged together from all `values.yaml`.
