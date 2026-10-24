package net.strata.launcher.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppConfigStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndLoadsApplicationSettings() throws Exception {
        Path configFile = temporaryDirectory.resolve("nested/config.json");
        AppConfigStore store = new AppConfigStore(configFile);
        AppConfig config = new AppConfig();
        config.setInstancesDirectory(temporaryDirectory.resolve("games").toString());

        store.save(config);

        AppConfig loaded = store.load();
        assertEquals(1, loaded.getSchemaVersion());
        assertEquals(config.getInstancesDirectory(), loaded.getInstancesDirectory());
        try (var files = Files.list(configFile.getParent())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void returnsDefaultsWhenConfigurationDoesNotExist() throws Exception {
        AppConfig config = new AppConfigStore(temporaryDirectory.resolve("missing.json")).load();

        assertEquals(1, config.getSchemaVersion());
        assertEquals(AppPaths.dataDirectory().resolve("instances").toString(),
                config.getInstancesDirectory());
    }

    @Test
    void preservesPropertiesIntroducedByNewerLauncherVersions() throws Exception {
        Path configFile = temporaryDirectory.resolve("config.json");
        Files.writeString(configFile, """
                {
                  "schemaVersion": 1,
                  "instancesDirectory": "/games",
                  "futurePreference": {"enabled": true}
                }
                """);
        AppConfigStore store = new AppConfigStore(configFile);
        AppConfig config = store.load();

        store.save(config);

        JsonNode saved = new ObjectMapper().readTree(configFile.toFile());
        assertTrue(saved.path("futurePreference").path("enabled").asBoolean());
    }
}
