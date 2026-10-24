package net.strata.launcher.instance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

public final class InstanceContentService {
    private final Path gameDirectory;

    public InstanceContentService(Path gameDirectory) {
        this.gameDirectory = gameDirectory.toAbsolutePath().normalize();
    }

    public List<InstanceContentFile> list(InstanceContentCategory category) throws IOException {
        Path directory = categoryDirectory(category);
        ensureDirectory(directory);
        java.util.ArrayList<InstanceContentFile> files = new java.util.ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) {
                    continue;
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && category.accepts(path.getFileName().toString())) {
                    files.add(new InstanceContentFile(path.getFileName().toString(),
                            Files.size(path)));
                }
            }
        }
        files.sort(Comparator.comparing(InstanceContentFile::filename,
                String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(files);
    }

    public InstanceContentFile add(InstanceContentCategory category, Path source)
            throws IOException {
        Path input = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(input)
                || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Selected content must be a regular, non-symbolic-link file.");
        }
        String filename = input.getFileName().toString();
        if (!category.accepts(filename)) {
            throw new IOException("Select a " + category.extensionDescription() + " file for "
                    + category.displayName().toLowerCase() + ".");
        }
        Path directory = categoryDirectory(category);
        ensureDirectory(directory);
        Path destination = directory.resolve(filename).normalize();
        if (!destination.getParent().equals(directory)) {
            throw new IOException("Selected filename is not safe.");
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(filename + " already exists in this instance.");
        }
        Path temporary = Files.createTempFile(directory, ".strata-import-", ".tmp");
        try {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new InstanceContentFile(filename, Files.size(destination));
    }

    public void remove(InstanceContentCategory category, String filename) throws IOException {
        if (filename == null || filename.isBlank()
                || filename.contains("/") || filename.contains("\\")
                || filename.equals(".") || filename.equals("..")) {
            throw new IOException("Content filename is invalid.");
        }
        Path directory = categoryDirectory(category);
        ensureDirectory(directory);
        Path file = directory.resolve(filename).normalize();
        if (!file.getParent().equals(directory) || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Selected content file does not exist or is unsafe.");
        }
        Files.delete(file);
    }

    public Path directory(InstanceContentCategory category) {
        return categoryDirectory(category);
    }

    private Path categoryDirectory(InstanceContentCategory category) {
        Path directory = gameDirectory.resolve(category.directoryName()).normalize();
        if (!directory.startsWith(gameDirectory)) {
            throw new IllegalArgumentException("Content directory escapes the instance.");
        }
        return directory;
    }

    private void ensureDirectory(Path directory) throws IOException {
        for (Path current = gameDirectory; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Instance content path cannot contain symbolic links.");
            }
        }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Instance content path is not a directory.");
        }
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Instance content path is not a directory.");
        }
    }
}
