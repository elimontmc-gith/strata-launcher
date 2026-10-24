package net.strata.launcher.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class AppConfigStore {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Path configFile;

    public AppConfigStore() {
        this(AppPaths.dataDirectory().resolve("config.json"));
    }

    public AppConfigStore(Path configFile) {
        this.configFile = configFile.toAbsolutePath().normalize();
    }

    public AppConfig load() throws IOException {
        if (!Files.exists(configFile)) {
            return new AppConfig();
        }
        return objectMapper.readValue(configFile.toFile(), AppConfig.class);
    }

    public void save(AppConfig config) throws IOException {
        Path parent = configFile.getParent();
        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, "config-", ".tmp");
        try {
            objectMapper.writeValue(temporaryFile.toFile(), config);
            try {
                Files.move(temporaryFile, configFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    public Path getConfigFile() {
        return configFile;
    }
}
