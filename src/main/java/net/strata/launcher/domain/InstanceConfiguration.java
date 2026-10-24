package net.strata.launcher.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;

public record InstanceConfiguration(
        int memoryMegabytes,
        int windowWidth,
        int windowHeight,
        boolean fullscreen,
        String javaExecutable) {
    public InstanceConfiguration {
        if (memoryMegabytes < 512 || memoryMegabytes > 32768) {
            throw new IllegalArgumentException("Memory must be between 512 and 32768 MB.");
        }
        if (windowWidth < 640 || windowWidth > 7680) {
            throw new IllegalArgumentException("Window width must be between 640 and 7680 pixels.");
        }
        if (windowHeight < 480 || windowHeight > 4320) {
            throw new IllegalArgumentException("Window height must be between 480 and 4320 pixels.");
        }
        if (javaExecutable != null && !javaExecutable.isBlank()) {
            javaExecutable = Path.of(javaExecutable).toAbsolutePath().normalize().toString();
        } else {
            javaExecutable = null;
        }
    }

    public static InstanceConfiguration defaults() {
        return new InstanceConfiguration(4096, 1280, 720, false, null);
    }

    public static InstanceConfiguration fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Instance configuration must be a JSON object.");
        }
        return new InstanceConfiguration(
                integer(node, "memoryMegabytes", 4096),
                integer(node, "windowWidth", 1280),
                integer(node, "windowHeight", 720),
                bool(node, "fullscreen", false),
                text(node, "javaExecutable"));
    }

    private static int integer(JsonNode node, String field, int defaultValue) {
        JsonNode value = node.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException("Instance setting " + field + " must be an integer.");
        }
        return value.asInt();
    }

    private static boolean bool(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("Instance setting " + field + " must be true or false.");
        }
        return value.asBoolean();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Instance setting " + field + " must be text.");
        }
        return value.asText();
    }
}
