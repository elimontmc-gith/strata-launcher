package net.strata.launcher.minecraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class MinecraftInstallationService {
    private static final String LIBRARIES_BASE_URL = "https://libraries.minecraft.net/";
    private static final String ASSETS_BASE_URL = "https://resources.download.minecraft.net/";
    private static final int MAX_METADATA_BYTES = 10 * 1024 * 1024;
    private static final long MAX_DOWNLOAD_BYTES = 1024L * 1024 * 1024;
    private static final long MAX_NATIVE_EXTRACTED_BYTES = 512L * 1024 * 1024;
    private static final Pattern SHA1 = Pattern.compile("[a-fA-F0-9]{40}");

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final ConcurrentMap<Path, ReentrantLock> INSTALL_LOCKS =
            new ConcurrentHashMap<>();

    private final Path sharedCache;
    private final VersionManifestService manifestService;
    private final FileTransfer fileTransfer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MinecraftInstallationService(
            Path sharedCache, VersionManifestService manifestService) {
        this(sharedCache, manifestService, MinecraftInstallationService::transferFile);
    }

    MinecraftInstallationService(
            Path sharedCache, VersionManifestService manifestService, FileTransfer fileTransfer) {
        this.sharedCache = sharedCache.toAbsolutePath().normalize();
        this.manifestService = manifestService;
        this.fileTransfer = fileTransfer;
    }

    public void install(Path gameDirectory, String versionId, ProgressListener progress)
            throws IOException {
        Path gameRoot = gameDirectory.toAbsolutePath().normalize();
        ReentrantLock lock = INSTALL_LOCKS.computeIfAbsent(gameRoot, ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new IOException("An installation is already running for this game directory.");
        }
        try {
            installLocked(gameRoot, versionId, progress);
        } finally {
            lock.unlock();
        }
    }

    private void installLocked(Path gameRoot, String versionId, ProgressListener progress)
            throws IOException {
        if (Files.isSymbolicLink(gameRoot)) {
            throw new IOException("Instance game directory cannot be a symbolic link: " + gameRoot);
        }
        String platform = currentPlatform();
        if (!Set.of("windows", "osx", "linux").contains(platform)) {
            throw new IOException("Minecraft installation is not supported on this operating system.");
        }
        createSafeDirectories(gameRoot);
        VersionCatalog catalog = manifestService.loadCatalog(false);
        MinecraftVersion version = catalog.versions().stream()
                .filter(item -> item.id().equals(versionId))
                .findFirst()
                .orElseThrow(() -> new IOException(
                        "Minecraft version is not present in the official version catalog: " + versionId));

        Path versionsRoot = gameRoot.resolve("versions");
        createSafeDirectories(versionsRoot);
        Path nativesRoot = gameRoot.resolve("natives");
        createSafeDirectories(nativesRoot);
        Path stagingDirectory = versionsRoot.resolve(".install-" + UUID.randomUUID());
        Files.createDirectory(stagingDirectory);
        Path stagedNatives = nativesRoot.resolve(".install-" + UUID.randomUUID());
        try {
            checkCancelled();
            report(progress, "Downloading version metadata", 0, 1);
            Path metadataFile = stagingDirectory.resolve(versionId + ".json");
            download(new DownloadFile(URI.create(version.url()), metadataFile, version.sha1(),
                    -1, "version metadata", MAX_METADATA_BYTES));
            byte[] metadataBytes = Files.readAllBytes(metadataFile);
            if (metadataBytes.length > MAX_METADATA_BYTES) {
                throw new IOException("Minecraft version metadata exceeds the size limit.");
            }
            JsonNode metadata = parseJson(metadataBytes, "Minecraft version metadata");
            if (!versionId.equals(requiredText(metadata, "id"))) {
                throw new IOException("Minecraft version metadata ID does not match the selected version.");
            }

            JsonNode client = requiredObject(metadata, "downloads").get("client");
            if (client == null || !client.isObject()) {
                throw new IOException("Minecraft version metadata does not include a client download.");
            }
            Path stagedClient = stagingDirectory.resolve(versionId + ".jar");
            List<DownloadFile> initialDownloads = new ArrayList<>();
            initialDownloads.add(downloadTo(client, stagedClient, "Minecraft client"));

            Path librariesRoot = sharedCache.resolve("libraries");
            List<NativeLibrary> nativeLibraries = new ArrayList<>();
            collectLibraries(metadata, librariesRoot, initialDownloads, nativeLibraries);

            JsonNode assetIndex = requiredObject(metadata, "assetIndex");
            String assetIndexId = requiredText(assetIndex, "id");
            validatePathComponent(assetIndexId);
            if (nonNegativeSize(assetIndex, "size", false) > MAX_METADATA_BYTES) {
                throw new IOException("Minecraft asset index exceeds the size limit.");
            }
            Path assetIndexFile = sharedCache.resolve("assets/indexes")
                    .resolve(assetIndexId + ".json");
            initialDownloads.add(downloadTo(assetIndex, assetIndexFile,
                    "asset index " + assetIndexId, MAX_METADATA_BYTES));

            JsonNode loggingFile = metadata.path("logging").path("client").path("file");
            if (loggingFile.isObject()) {
                String loggingId = requiredText(loggingFile, "id");
                validatePathComponent(loggingId);
                Path loggingConfig = sharedCache.resolve("assets/log_configs").resolve(loggingId);
                initialDownloads.add(downloadTo(loggingFile, loggingConfig,
                        "client logging configuration " + loggingId, MAX_METADATA_BYTES));
            }

            downloadGroup(initialDownloads, "Downloading Minecraft files", progress);

            byte[] assetIndexBytes = Files.readAllBytes(assetIndexFile);
            if (assetIndexBytes.length > MAX_METADATA_BYTES) {
                throw new IOException("Minecraft asset index exceeds the size limit.");
            }
            JsonNode assets = parseJson(assetIndexBytes, "Minecraft asset index");
            List<DownloadFile> assetDownloads = collectAssets(assets);
            downloadGroup(assetDownloads, "Downloading Minecraft assets", progress);

            Files.createDirectory(stagedNatives);
            for (NativeLibrary nativeLibrary : nativeLibraries) {
                checkCancelled();
                extractNatives(nativeLibrary.jarPath(), stagedNatives, nativeLibrary.excludes());
            }

            Files.write(stagingDirectory.resolve("install-info.json"),
                    objectMapper.writeValueAsBytes(Map.of(
                            "versionId", versionId,
                            "assetIndex", assetIndexId,
                            "metadataUrl", version.url())));
            commitInstallation(gameRoot, stagingDirectory, stagedNatives, versionId);
            report(progress, "Minecraft " + versionId + " installation complete", 1, 1);
        } finally {
            deleteTree(stagingDirectory);
            deleteTree(stagedNatives);
        }
    }

    public boolean isInstalled(Path gameDirectory, String versionId) {
        try {
            validatePathComponent(versionId);
            Path versionDirectory = gameDirectory.toAbsolutePath().normalize()
                    .resolve("versions").resolve(versionId);
            if (Files.isSymbolicLink(versionDirectory)) {
                return false;
            }
            Path info = versionDirectory.resolve("install-info.json");
            Path client = versionDirectory.resolve(versionId + ".jar");
            Path metadata = versionDirectory.resolve(versionId + ".json");
            boolean installed = Files.isRegularFile(info, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(client, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS);
            if (!installed) {
                return false;
            }
            JsonNode loggingFile = objectMapper.readTree(metadata.toFile())
                    .path("logging").path("client").path("file");
            if (!loggingFile.isObject()) {
                return true;
            }
            String loggingId = requiredText(loggingFile, "id");
            validatePathComponent(loggingId);
            Path loggingConfig = sharedCache.resolve("assets/log_configs").resolve(loggingId);
            return Files.isRegularFile(loggingConfig, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(loggingConfig);
        } catch (IOException | IllegalArgumentException exception) {
            return false;
        }
    }

    private List<DownloadFile> collectAssets(JsonNode index) throws IOException {
        JsonNode objects = requiredObject(index, "objects");
        List<DownloadFile> downloads = new ArrayList<>();
        var iterator = objects.properties().iterator();
        Path objectsRoot = sharedCache.resolve("assets/objects");
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            String hash = requiredText(entry.getValue(), "hash").toLowerCase(Locale.ROOT);
            if (!SHA1.matcher(hash).matches()) {
                throw new IOException("Asset index contains an invalid object hash.");
            }
            long size = nonNegativeSize(entry.getValue(), "size", true);
            String relativePath = hash.substring(0, 2) + "/" + hash;
            Path destination = resolveSafe(objectsRoot, relativePath);
            URI url = URI.create(ASSETS_BASE_URL + relativePath);
            downloads.add(new DownloadFile(url, destination, hash, size,
                    "asset " + entry.getKey()));
        }
        return deduplicate(downloads);
    }

    private void collectLibraries(
            JsonNode metadata,
            Path librariesRoot,
            List<DownloadFile> downloads,
            List<NativeLibrary> nativeLibraries) throws IOException {
        JsonNode libraries = metadata.get("libraries");
        if (libraries == null || !libraries.isArray()) {
            throw new IOException("Minecraft version metadata does not contain a library list.");
        }
        for (JsonNode library : libraries) {
            if (!includeLibrary(library)) {
                continue;
            }
            JsonNode libraryDownloads = library.get("downloads");
            JsonNode artifact = libraryDownloads == null ? null : libraryDownloads.get("artifact");
            if (artifact != null && artifact.isObject()) {
                downloads.add(downloadFromMetadata(artifact, librariesRoot,
                        requiredText(artifact, "path"), library.path("name").asText("library")));
            } else if (library.has("name")) {
                downloads.add(legacyLibrary(library, librariesRoot));
            }

            JsonNode natives = library.get("natives");
            if (natives == null || !natives.isObject()) {
                continue;
            }
            String platform = currentPlatform();
            String classifier = optionalText(natives, platform);
            if (classifier == null) {
                continue;
            }
            classifier = classifier.replace("${arch}",
                    System.getProperty("os.arch", "").contains("64") ? "64" : "32");
            JsonNode classifiers = libraryDownloads == null
                    ? null : libraryDownloads.get("classifiers");
            JsonNode nativeArtifact = classifiers == null ? null : classifiers.get(classifier);
            if (nativeArtifact == null || !nativeArtifact.isObject()) {
                throw new IOException("Minecraft metadata is missing native library "
                        + library.path("name").asText() + " for " + platform + ".");
            }
            DownloadFile nativeFile = downloadFromMetadata(nativeArtifact, librariesRoot,
                    requiredText(nativeArtifact, "path"),
                    library.path("name").asText("native library"));
            downloads.add(nativeFile);
            nativeLibraries.add(new NativeLibrary(nativeFile.destination(),
                    exclusionPatterns(library)));
        }
    }

    private DownloadFile legacyLibrary(JsonNode library, Path librariesRoot) throws IOException {
        String[] parts = requiredText(library, "name").split(":", -1);
        if (parts.length < 3) {
            throw new IOException("Minecraft metadata contains an invalid library coordinate.");
        }
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        String extension = "jar";
        int extensionSeparator = version.indexOf('@');
        if (extensionSeparator >= 0) {
            extension = version.substring(extensionSeparator + 1);
            version = version.substring(0, extensionSeparator);
        }
        String fileName = artifact + "-" + version + classifier + "." + extension;
        String relativePath = group + "/" + artifact + "/" + version + "/" + fileName;
        String baseUrl = optionalText(library, "url");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = LIBRARIES_BASE_URL;
        }
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        URI uri = secureUri(baseUrl + relativePath);
        return new DownloadFile(uri, resolveSafe(librariesRoot, relativePath),
                null, -1, requiredText(library, "name"));
    }

    private DownloadFile downloadFromMetadata(
            JsonNode metadata, Path destinationRoot, String description) throws IOException {
        return downloadFromMetadata(metadata, destinationRoot,
                requiredText(metadata, "path"), description);
    }

    private DownloadFile downloadFromMetadata(
            JsonNode metadata, Path destinationRoot, String relativePath, String description)
            throws IOException {
        return downloadTo(metadata, resolveSafe(destinationRoot, relativePath), description);
    }

    private DownloadFile downloadTo(JsonNode metadata, Path destination, String description)
            throws IOException {
        return downloadTo(metadata, destination, description, MAX_DOWNLOAD_BYTES);
    }

    private DownloadFile downloadTo(
            JsonNode metadata, Path destination, String description, long maximumSize)
            throws IOException {
        URI uri = secureUri(requiredText(metadata, "url"));
        String sha1 = optionalText(metadata, "sha1");
        if (sha1 != null && !SHA1.matcher(sha1).matches()) {
            throw new IOException("Minecraft metadata contains an invalid SHA-1 for " + description);
        }
        long size = nonNegativeSize(metadata, "size", false);
        return new DownloadFile(uri, destination, sha1, size, description, maximumSize);
    }

    private void downloadGroup(
            List<DownloadFile> files, String stage, ProgressListener progress) throws IOException {
        List<DownloadFile> uniqueFiles = deduplicate(files);
        for (int index = 0; index < uniqueFiles.size(); index++) {
            checkCancelled();
            DownloadFile file = uniqueFiles.get(index);
            report(progress, stage + " (" + (index + 1) + "/" + uniqueFiles.size()
                    + "): " + file.description(), index, uniqueFiles.size());
            download(file);
            report(progress, stage, index + 1, uniqueFiles.size());
        }
    }

    private void download(DownloadFile file) throws IOException {
        checkCancelled();
        validateDestination(file.destination());
        if (isValidFile(file.destination(), file.sha1(), file.size())) {
            return;
        }
        Path parent = file.destination().getParent();
        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, "download-", ".tmp");
        try {
            long maximumSize = Math.min(file.maximumSize(),
                    file.size() >= 0 ? file.size() : file.maximumSize());
            fileTransfer.transfer(file.uri(), temporaryFile, maximumSize);
            if (!isValidFile(temporaryFile, file.sha1(), file.size())
                    && !isUnverifiableDownloadPresent(
                            temporaryFile, file.sha1(), file.size())) {
                throw new IOException("Downloaded file failed size or checksum verification: "
                        + file.description());
            }
            try {
                Files.move(temporaryFile, file.destination(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, file.destination(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static boolean isValidFile(Path file, String sha1, long expectedSize) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        long actualSize = Files.size(file);
        if (expectedSize >= 0 && actualSize != expectedSize) {
            return false;
        }
        if (sha1 == null) {
            return expectedSize >= 0;
        }
        return sha1.equalsIgnoreCase(computeSha1(file));
    }

    private static boolean isUnverifiableDownloadPresent(
            Path file, String sha1, long expectedSize) throws IOException {
        return sha1 == null && expectedSize < 0
                && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                && Files.size(file) > 0;
    }

    private static String computeSha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 support is unavailable.", exception);
        }
    }

    private static void extractNatives(Path archive, Path destination, List<String> excludes)
            throws IOException {
        try (ZipFile zipFile = new ZipFile(archive.toFile())) {
            var entries = zipFile.entries();
            long totalExtracted = 0;
            while (entries.hasMoreElements()) {
                checkCancelled();
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || name.startsWith("META-INF/")
                        || excludes.stream().anyMatch(name::startsWith)) {
                    continue;
                }
                Path output = resolveSafe(destination, name);
                Files.createDirectories(output.getParent());
                try (InputStream input = zipFile.getInputStream(entry)) {
                    try (var target = Files.newOutputStream(output)) {
                        byte[] buffer = new byte[16 * 1024];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            totalExtracted += read;
                            if (totalExtracted > MAX_NATIVE_EXTRACTED_BYTES) {
                                throw new IOException("Native libraries exceed the extraction size limit.");
                            }
                            target.write(buffer, 0, read);
                        }
                    }
                }
            }
        }
    }

    private static List<String> exclusionPatterns(JsonNode library) throws IOException {
        JsonNode excludes = library.path("extract").get("exclude");
        if (excludes == null) {
            return List.of();
        }
        if (!excludes.isArray()) {
            throw new IOException("Minecraft metadata contains invalid native extraction rules.");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode exclude : excludes) {
            if (!exclude.isTextual()) {
                throw new IOException("Minecraft metadata contains invalid native extraction rules.");
            }
            result.add(exclude.asText().replace('\\', '/'));
        }
        return result;
    }

    private static boolean includeLibrary(JsonNode library) throws IOException {
        JsonNode rules = library.get("rules");
        if (rules == null) {
            return true;
        }
        if (!rules.isArray()) {
            throw new IOException("Minecraft metadata contains invalid library rules.");
        }
        boolean included = false;
        for (JsonNode rule : rules) {
            String action = requiredText(rule, "action");
            if (!action.equals("allow") && !action.equals("disallow")) {
                throw new IOException("Minecraft metadata contains an unknown library rule.");
            }
            if (ruleMatches(rule.get("os"))) {
                included = action.equals("allow");
            }
        }
        return included;
    }

    private static boolean ruleMatches(JsonNode operatingSystem) throws IOException {
        if (operatingSystem == null || operatingSystem.isNull()) {
            return true;
        }
        if (!operatingSystem.isObject()) {
            return false;
        }
        String name = optionalText(operatingSystem, "name");
        if (name != null && !name.equals(currentPlatform())) {
            return false;
        }
        String architecture = optionalText(operatingSystem, "arch");
        String version = optionalText(operatingSystem, "version");
        try {
            if (architecture != null && !Pattern.matches(architecture,
                    System.getProperty("os.arch", ""))) {
                return false;
            }
            return version == null || Pattern.matches(version,
                    System.getProperty("os.version", ""));
        } catch (java.util.regex.PatternSyntaxException exception) {
            throw new IOException("Minecraft metadata contains an invalid platform rule.", exception);
        }
    }

    private static String currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "osx";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return "unknown";
    }

    private static List<DownloadFile> deduplicate(List<DownloadFile> files) throws IOException {
        Map<Path, DownloadFile> byPath = new HashMap<>();
        for (DownloadFile file : files) {
            DownloadFile existing = byPath.putIfAbsent(file.destination(), file);
            if (existing != null && !equalMetadata(existing, file)) {
                throw new IOException("Minecraft metadata maps conflicting downloads to "
                        + file.destination());
            }
        }
        return List.copyOf(byPath.values());
    }

    private static boolean equalMetadata(DownloadFile first, DownloadFile second) {
        return first.uri().equals(second.uri())
                && Objects.equals(first.sha1(), second.sha1())
                && first.size() == second.size();
    }

    private static void commitInstallation(
            Path gameRoot, Path stagedVersion, Path stagedNatives, String versionId)
            throws IOException {
        Path versionRoot = gameRoot.resolve("versions");
        Path destinationVersion = versionRoot.resolve(versionId);
        Path backupVersion = versionRoot.resolve(".backup-" + UUID.randomUUID());
        Path nativesRoot = gameRoot.resolve("natives");
        Files.createDirectories(nativesRoot);
        Path destinationNatives = nativesRoot.resolve(versionId);
        Path backupNatives = nativesRoot.resolve(".backup-" + UUID.randomUUID());

        validateReplaceableDirectory(destinationVersion);
        validateReplaceableDirectory(destinationNatives);
        boolean versionBackedUp = false;
        boolean nativesBackedUp = false;
        boolean versionCommitted = false;
        boolean nativesCommitted = false;
        try {
            if (Files.exists(destinationVersion, LinkOption.NOFOLLOW_LINKS)) {
                movePath(destinationVersion, backupVersion);
                versionBackedUp = true;
            }
            if (Files.exists(destinationNatives, LinkOption.NOFOLLOW_LINKS)) {
                movePath(destinationNatives, backupNatives);
                nativesBackedUp = true;
            }
            movePath(stagedVersion, destinationVersion);
            versionCommitted = true;
            movePath(stagedNatives, destinationNatives);
            nativesCommitted = true;
        } catch (IOException exception) {
            if (nativesCommitted) {
                addCleanupFailure(exception, () -> deleteTree(destinationNatives));
            }
            if (versionCommitted) {
                addCleanupFailure(exception, () -> deleteTree(destinationVersion));
            }
            if (nativesBackedUp) {
                addCleanupFailure(exception, () -> movePath(backupNatives, destinationNatives));
            }
            if (versionBackedUp) {
                addCleanupFailure(exception, () -> movePath(backupVersion, destinationVersion));
            }
            throw exception;
        }
        if (nativesBackedUp) {
            deleteTree(backupNatives);
        }
        if (versionBackedUp) {
            deleteTree(backupVersion);
        }
    }

    private static void validateReplaceableDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(path)
                        || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Managed installation path is not a safe directory: " + path);
        }
    }

    private static void movePath(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private static void addCleanupFailure(IOException primary, IoOperation cleanup) {
        try {
            cleanup.run();
        } catch (IOException exception) {
            primary.addSuppressed(exception);
        }
    }

    private static void validateDestination(Path destination) throws IOException {
        Path absolute = destination.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("Download destination has no parent directory.");
        }
        Path cursor = parent;
        while (cursor != null) {
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("Download destination contains a symbolic link: " + cursor);
            }
            cursor = cursor.getParent();
        }
        if (Files.isSymbolicLink(absolute)) {
            throw new IOException("Download destination cannot be a symbolic link: " + absolute);
        }
    }

    private static void createSafeDirectories(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        validateDestination(normalized.resolve(".strata-path-check"));
        Files.createDirectories(normalized);
        if (Files.isSymbolicLink(normalized)) {
            throw new IOException("Managed directory cannot be a symbolic link: " + normalized);
        }
    }

    private static Path resolveSafe(Path root, String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank() || relativePath.startsWith("/")
                || relativePath.startsWith("\\")
                || List.of(relativePath.replace('\\', '/').split("/"))
                        .stream().anyMatch(part -> part.equals("..") || part.equals("."))) {
            throw new IOException("Unsafe path in Minecraft metadata.");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path destination = normalizedRoot.resolve(relativePath).normalize();
        if (!destination.startsWith(normalizedRoot) || destination.equals(normalizedRoot)) {
            throw new IOException("Minecraft metadata path escapes its managed directory.");
        }
        return destination;
    }

    private static URI secureUri(String text) throws IOException {
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Minecraft metadata contains an invalid download URL.", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw new IOException("Minecraft download URL must use HTTPS with a valid host.");
        }
        return uri;
    }

    private static JsonNode parseJson(byte[] json, String description) throws IOException {
        try {
            return new ObjectMapper().readTree(json);
        } catch (IOException exception) {
            throw new IOException(description + " is not valid JSON.", exception);
        }
    }

    private static JsonNode requiredObject(JsonNode parent, String field) throws IOException {
        JsonNode node = parent.get(field);
        if (node == null || !node.isObject()) {
            throw new IOException("Minecraft metadata is missing " + field + ".");
        }
        return node;
    }

    private static String requiredText(JsonNode parent, String field) throws IOException {
        String value = optionalText(parent, field);
        if (value == null || value.isBlank()) {
            throw new IOException("Minecraft metadata is missing " + field + ".");
        }
        return value;
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static long nonNegativeSize(JsonNode parent, String field, boolean required)
            throws IOException {
        JsonNode node = parent.get(field);
        if (node == null && !required) {
            return -1;
        }
        if (node == null || !node.isIntegralNumber() || node.asLong() < 0) {
            throw new IOException("Minecraft metadata contains an invalid file size.");
        }
        return node.asLong();
    }

    private static void validatePathComponent(String value) throws IOException {
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || !value.matches("[A-Za-z0-9._ -]{1,100}")
                || value.equals(".") || value.equals("..")) {
            throw new IOException("Minecraft metadata contains an unsafe path component.");
        }
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException(
                    "Minecraft installation was cancelled.");
        }
    }

    private static void report(
            ProgressListener progress, String message, long completed, long total) {
        progress.update(message, completed, total);
    }

    private static void transferFile(URI uri, Path destination, long maximumSize) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofMinutes(2))
                .header("Accept", "*/*")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response =
                    HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("Download returned HTTP " + response.statusCode() + ".");
            }
            if (response.headers().firstValueAsLong("Content-Length").orElse(-1) > maximumSize) {
                response.body().close();
                throw new IOException("Download exceeds its expected size.");
            }
            try (InputStream input = response.body();
                    var output = Files.newOutputStream(destination)) {
                byte[] buffer = new byte[32 * 1024];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled();
                    total += read;
                    if (total > maximumSize) {
                        throw new IOException("Download exceeds its allowed size.");
                    }
                    output.write(buffer, 0, read);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Download was interrupted.", exception);
        }
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

    @FunctionalInterface
    interface FileTransfer {
        void transfer(URI uri, Path destination, long maximumSize) throws IOException;
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws IOException;
    }

    @FunctionalInterface
    public interface ProgressListener {
        void update(String stage, long completed, long total);
    }

    private record DownloadFile(
            URI uri, Path destination, String sha1, long size, String description,
            long maximumSize) {
        private DownloadFile(URI uri, Path destination, String sha1, long size, String description) {
            this(uri, destination, sha1, size, description, MAX_DOWNLOAD_BYTES);
        }
    }

    private record NativeLibrary(Path jarPath, List<String> excludes) {
    }
}
