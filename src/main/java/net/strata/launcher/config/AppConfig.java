package net.strata.launcher.config;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AppConfig {
    private int schemaVersion = 1;
    private String instancesDirectory = defaultInstancesDirectory();
    private final Map<String, JsonNode> additionalProperties = new LinkedHashMap<>();

    public AppConfig() {
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getInstancesDirectory() {
        return instancesDirectory;
    }

    public void setInstancesDirectory(String instancesDirectory) {
        this.instancesDirectory = instancesDirectory;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String name, JsonNode value) {
        additionalProperties.put(name, value);
    }

    @JsonAnyGetter
    public Map<String, JsonNode> getAdditionalProperties() {
        return additionalProperties;
    }

    private static String defaultInstancesDirectory() {
        return AppPaths.dataDirectory().resolve("instances").toString();
    }
}
