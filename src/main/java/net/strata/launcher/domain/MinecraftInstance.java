package net.strata.launcher.domain;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class MinecraftInstance {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final UUID id;
    private final String name;
    private final String minecraftVersion;
    private final String loaderType;
    private final String loaderVersion;
    private final boolean favorite;
    private final Instant createdAt;
    private final Map<String, JsonNode> additionalProperties = new LinkedHashMap<>();

    @JsonCreator
    public MinecraftInstance(
            @JsonProperty("id") UUID id,
            @JsonProperty("name") String name,
            @JsonProperty("minecraftVersion") String minecraftVersion,
            @JsonProperty("loaderType") String loaderType,
            @JsonProperty("loaderVersion") String loaderVersion,
            @JsonProperty("favorite") boolean favorite,
            @JsonProperty("createdAt") Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.minecraftVersion = minecraftVersion;
        this.loaderType = Objects.requireNonNull(loaderType, "loaderType");
        this.loaderVersion = loaderVersion;
        this.favorite = favorite;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getMinecraftVersion() {
        return minecraftVersion;
    }

    public String getLoaderType() {
        return loaderType;
    }

    public String getLoaderVersion() {
        return loaderVersion;
    }

    public String loaderProfileId() {
        JsonNode value = additionalProperties.get("loaderProfileId");
        return value != null && value.isTextual() ? value.asText() : null;
    }

    public InstanceConfiguration getConfiguration() {
        JsonNode value = additionalProperties.get("configuration");
        return value == null ? InstanceConfiguration.defaults()
                : InstanceConfiguration.fromJson(value);
    }

    public boolean isFavorite() {
        return favorite;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String name, JsonNode value) {
        additionalProperties.put(name, value);
    }

    @JsonAnyGetter
    public Map<String, JsonNode> getAdditionalProperties() {
        return additionalProperties;
    }

    public MinecraftInstance withName(String newName) {
        return copy(newName, favorite, createdAt);
    }

    public MinecraftInstance withFavorite(boolean isFavorite) {
        return copy(name, isFavorite, createdAt);
    }

    public MinecraftInstance withMinecraftVersion(String version) {
        MinecraftInstance copy = new MinecraftInstance(id, name, version,
                "VANILLA", null, favorite, createdAt);
        copy.additionalProperties.putAll(additionalProperties);
        copy.additionalProperties.remove("loaderProfileId");
        return copy;
    }

    public MinecraftInstance withLoader(String type, String version) {
        MinecraftInstance copy = new MinecraftInstance(id, name, minecraftVersion,
                type, version, favorite, createdAt);
        copy.additionalProperties.putAll(additionalProperties);
        return copy;
    }

    public MinecraftInstance withLoader(String type, String version, String profileId) {
        MinecraftInstance copy = withLoader(type, version);
        if (profileId == null) {
            copy.additionalProperties.remove("loaderProfileId");
        } else {
            copy.additionalProperties.put("loaderProfileId", TextNode.valueOf(profileId));
        }
        return copy;
    }

    public MinecraftInstance withConfiguration(InstanceConfiguration configuration) {
        MinecraftInstance copy = copy(name, favorite, createdAt);
        copy.additionalProperties.put("configuration", JSON.valueToTree(configuration));
        return copy;
    }

    private MinecraftInstance copy(String newName, boolean isFavorite, Instant newCreatedAt) {
        MinecraftInstance copy = new MinecraftInstance(id, newName, minecraftVersion,
                loaderType, loaderVersion, isFavorite, newCreatedAt);
        copy.additionalProperties.putAll(additionalProperties);
        return copy;
    }
}
