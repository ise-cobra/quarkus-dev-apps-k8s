package com.ise112.shared.k8s.deployment.ssh;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.ConfigValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import lombok.Getter;

/**
 * Parses the ports configurations in the values.yaml file.
 *
 */
@Getter
public class PortsConfiguration {

    private static final String NODE_REVERSE_PROXY = "reverseProxy";

    private static final String NODE_SERVICES = "services";

    private static final String NODE_PORTFORWARDING = "portforwarding";

    private Path valuesFile;

    private List<PortForwarding> portForwardings;

    private List<ReverseProxy> reverseProxies;

    @Getter
    public static class PortForwarding {
        private String name;
        private int localPort;
        private int realLocalPort;
        private int servicePort;
        private String serviceName;

        public void setRealPort(int realPort) {
            if (this.realLocalPort == 0) {
                this.realLocalPort = realPort;
            }
        }

        public String getJschString() {
            return realLocalPort + ":" + serviceName + ":" + servicePort;
        }
    }

    @Getter
    public static class ReverseProxy {
        private int localPort;
        private int servicePort;
        private String serviceName;

        public String getJschString() {
            return servicePort + ":localhost:" + localPort;
        }
    }

    private PortsConfiguration(Path valuesFile, List<PortForwarding> portForwardings,
            List<ReverseProxy> reverseProxies) {
        this.valuesFile = valuesFile;
        this.portForwardings = portForwardings;
        this.reverseProxies = reverseProxies;
    }

    public static PortsConfiguration parseConfig(Path valuesFile) {
        ObjectMapper yamlMapper = new ObjectMapper(
                new YAMLFactory().disable(YAMLGenerator.Feature.USE_NATIVE_TYPE_ID));
        JsonNode valuesYaml;
        try {
            valuesYaml = yamlMapper.readTree(valuesFile.toFile());
        } catch (IOException e) {
            throw new RuntimeException("Error during parsing values.yaml file", e);
        }

        List<PortForwarding> portForwardings = Optional.of(valuesYaml.get(NODE_PORTFORWARDING))
                .map(n -> n.get(NODE_SERVICES))
                .map(s -> StreamSupport.stream(s.spliterator(), false)
                        .map(e -> {
                            PortForwarding portForwarding = new PortForwarding();
                            portForwarding.name = getString(e.path("name"));
                            portForwarding.localPort = getInt(e.path("localPort"));
                            portForwarding.realLocalPort = portForwarding.localPort;
                            portForwarding.servicePort = getInt(e.path("service").path("port"));
                            portForwarding.serviceName = getString(e.path("service").path("name"));
                            return portForwarding;
                        })
                        .filter(e -> e != null)
                        .toList())
                .orElseGet(() -> Collections.emptyList());

        List<ReverseProxy> reverseProxies = Optional.of(valuesYaml.get(NODE_PORTFORWARDING))
                .map(n -> n.get(NODE_REVERSE_PROXY))
                .map(s -> StreamSupport.stream(s.spliterator(), false)
                        .map(e -> {
                            ReverseProxy reverseProxy = new ReverseProxy();
                            reverseProxy.localPort = getInt(e.path("localPort"));
                            reverseProxy.servicePort = getInt(e.path("service").path("port"));
                            reverseProxy.serviceName = getString(e.path("service").path("name"));
                            return reverseProxy;
                        })
                        .filter(e -> e != null)
                        .toList())
                .orElseGet(() -> Collections.emptyList());

        PortsConfiguration portsConfiguration = new PortsConfiguration(valuesFile, portForwardings, reverseProxies);

        return portsConfiguration;
    }

    private static int getInt(JsonNode e) {
        return Integer.parseInt(getString(e));
    }

    /**
     * Only very basic property lookup, but better than nothing. Quarkus does not
     * seem to have an easy public way to expand properties.
     */
    private static String getString(JsonNode e) {
        String value = e.asText();
        Pattern pattern = Pattern.compile("\\$\\{([\\w\\.-]+)\\}");
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            String key = matcher.group(1);
            ConfigValue property = ConfigProvider.getConfig().getConfigValue(key);
            if (property.getRawValue() != null) {
                value = value.replace(matcher.group(0), property.getValue());
            }
        }
        return value;
    }
}