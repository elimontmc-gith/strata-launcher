package net.strata.launcher.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.strata.launcher.domain.MinecraftInstance;
import net.strata.launcher.domain.InstanceConfiguration;

public final class InstanceService {
    private static final String CONFIG_FILE = "instance.json";
    private static final List<String> GAME_DIRECTORIES =
            List.of("mods", "resourcepacks", "shaderpacks", "saves", "screenshots", "logs");

    private final Path instancesRoot;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    public InstanceService(Path instancesRoot) {
        this.instancesRoot = instancesRoot.toAbsolutePath().normalize();
    }

    public List<MinecraftInstance> listInstances() throws IOException {
        if (!Files.exists(instancesRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!Files.isDirectory(instancesRoot)) {
            throw new IOException("Instance storage is not a directory: " + instancesRoot);
        }

        List<Path> directories;
        try (var paths = Files.list(instancesRoot)) {
            directories = paths
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }

        List<MinecraftInstance> instances = new ArrayList<>();
        for (Path directory : directories) {
            Path configFile = directory.resolve(CONFIG_FILE);
            if (Files.isSymbolicLink(configFile)) {
                throw new IOException("Instance configuration cannot be a symbolic link: " + configFile);
            }
            if (Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS)) {
                MinecraftInstance instance = read(configFile);
                if (!directory.getFileName().toString().equals(instance.getId().toString())) {
                    throw new IOException("Instance ID does not match its directory: " + directory);
                }
                instances.add(instance);
            }
        }
        instances.sort(Comparator.comparing(MinecraftInstance::getName,
                String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(instances);
    }

    public MinecraftInstance createInstance(String name) throws IOException {
        String validName = validateName(name);
        UUID id = UUID.randomUUID();
        Path directory = instanceDirectory(id);
        Files.createDirectories(instancesRoot);
        Files.createDirectory(directory);
        try {
            Path gameDirectory = directory.resolve("game");
            Files.createDirectory(gameDirectory);
            for (String child : GAME_DIRECTORIES) {
                Files.createDirectory(gameDirectory.resolve(child));
            }
            MinecraftInstance instance = new MinecraftInstance(id, validName, null,
                    "VANILLA", null, false, Instant.now());
            write(instance);
            return instance;
        } catch (IOException exception) {
            try {
                deleteTree(directory);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

    public MinecraftInstance renameInstance(UUID id, String name) throws IOException {
        MinecraftInstance updated = readInstance(id).withName(validateName(name));
        write(updated);
        return updated;
    }

    public MinecraftInstance setFavorite(UUID id, boolean favorite) throws IOException {
        MinecraftInstance updated = readInstance(id).withFavorite(favorite);
        write(updated);
        return updated;
    }

    public MinecraftInstance setMinecraftVersion(UUID id, String version) throws IOException {
        MinecraftInstance updated = readInstance(id).withMinecraftVersion(version);
        write(updated);
        return updated;
    }

    public MinecraftInstance setLoader(UUID id, String type, String version) throws IOException {
        return setLoader(id, type, version, null);
    }

    public MinecraftInstance setLoader(UUID id, String type, String version, String profileId)
            throws IOException {
        if (!List.of("VANILLA", "FABRIC", "QUILT", "NEOFORGE").contains(type)) {
            throw new IllegalArgumentException("Unsupported mod loader: " + type);
        }
        if (!type.equals("VANILLA") && (version == null || version.isBlank())) {
            throw new IllegalArgumentException("A loader version is required.");
        }
        MinecraftInstance updated = readInstance(id).withLoader(type, version, profileId);
        write(updated);
        return updated;
    }

    public MinecraftInstance setConfiguration(UUID id, InstanceConfiguration configuration)
            throws IOException {
        MinecraftInstance updated = readInstance(id).withConfiguration(configuration);
        write(updated);
        return updated;
    }

    public MinecraftInstance duplicateInstance(UUID id, String name, boolean copyGameFiles)
            throws IOException {
        MinecraftInstance source = readInstance(id);
        String validName = validateName(name);
        UUID duplicateId = UUID.randomUUID();
        Path sourceDirectory = instanceDirectory(source.getId());
        Path duplicateDirectory = instanceDirectory(duplicateId);
        if (Files.isSymbolicLink(sourceDirectory)) {
            throw new IOException("Instance directory cannot be a symbolic link: " + sourceDirectory);
        }

        Files.createDirectories(instancesRoot);
        Files.createDirectory(duplicateDirectory);
        try {
            if (copyGameFiles) {
                copyTree(sourceDirectory.resolve("game"), duplicateDirectory.resolve("game"));
            } else {
                Path gameDirectory = duplicateDirectory.resolve("game");
                Files.createDirectory(gameDirectory);
                for (String child : GAME_DIRECTORIES) {
                    Files.createDirectory(gameDirectory.resolve(child));
                }
            }
            MinecraftInstance duplicate = new MinecraftInstance(duplicateId, validName,
                    source.getMinecraftVersion(), source.getLoaderType(), source.getLoaderVersion(),
                    false, Instant.now());
            duplicate.getAdditionalProperties().putAll(source.getAdditionalProperties());
            write(duplicate);
            return duplicate;
        } catch (IOException exception) {
            try {
                deleteTree(duplicateDirectory);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

    public void deleteInstance(UUID id) throws IOException {
        Path directory = instanceDirectory(id);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Instance does not exist: " + id);
        }
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Instance directory cannot be a symbolic link: " + directory);
        }
        readInstance(id);
        deleteTree(directory);
    }

    public Path getInstanceDirectory(UUID id) {
        return instanceDirectory(id);
    }

    public Path getGameDirectory(UUID id) {
        return instanceDirectory(id).resolve("game");
    }

    private MinecraftInstance readInstance(UUID id) throws IOException {
        Path directory = instanceDirectory(id);
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Instance directory cannot be a symbolic link: " + directory);
        }
        Path configFile = directory.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(configFile)) {
            throw new IOException("Instance configuration not found: " + configFile);
        }
        MinecraftInstance instance = read(configFile);
        if (!directory.getFileName().toString().equals(instance.getId().toString())) {
            throw new IOException("Instance ID does not match its directory: " + directory);
        }
        return instance;
    }

    private MinecraftInstance read(Path configFile) throws IOException {
        return objectMapper.readValue(configFile.toFile(), MinecraftInstance.class);
    }

    private void write(MinecraftInstance instance) throws IOException {
        Path directory = instanceDirectory(instance.getId());
        Files.createDirectories(directory);
        Path configFile = directory.resolve(CONFIG_FILE);
        Path temporaryFile = Files.createTempFile(directory, "instance-", ".tmp");
        try {
            objectMapper.writeValue(temporaryFile.toFile(), instance);
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

    private Path instanceDirectory(UUID id) {
        Path directory = instancesRoot.resolve(id.toString()).normalize();
        if (!directory.startsWith(instancesRoot) || directory.equals(instancesRoot)) {
            throw new IllegalArgumentException("Invalid instance identifier");
        }
        return directory;
    }

    private static String validateName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Enter an instance name.");
        }
        String normalized = name.trim();
        if (normalized.isEmpty() || normalized.length() > 64
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "Instance names must contain 1-64 characters and no control characters.");
        }
        return normalized;
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(source)) {
            throw new IOException("Source game directory is missing or unsafe: " + source);
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new IOException("Cannot copy a symbolic link: " + directory);
                }
                Files.createDirectories(destination.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
                    throw new IOException("Cannot copy a non-regular file: " + file);
                }
                Files.copy(file, destination.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
