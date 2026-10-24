package net.strata.launcher.minecraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class VersionManifestService {
    public static final URI OFFICIAL_MANIFEST_URI =
            URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");

    private static final Duration CACHE_LIFETIME = Duration.ofHours(6);
    private static final int MAX_MANIFEST_BYTES = 10 * 1024 * 1024;
    private static final Pattern VERSION_ID = Pattern.compile("[A-Za-z0-9._ -]{1,100}");
    private static final Pattern SHA1 = Pattern.compile("[A-Fa-f0-9]{40}");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final Path cacheFile;
    private final URI manifestUri;
    private final Clock clock;
    private final ManifestFetcher fetcher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VersionManifestService(Path cacheFile) {
        this(cacheFile, OFFICIAL_MANIFEST_URI, Clock.systemUTC(), VersionManifestService::fetchOfficial);
    }

    VersionManifestService(Path cacheFile, URI manifestUri, Clock clock, ManifestFetcher fetcher) {
        if (!"https".equalsIgnoreCase(manifestUri.getScheme()) || manifestUri.getHost() == null
                || manifestUri.getUserInfo() != null) {
            throw new IllegalArgumentException("Minecraft metadata must be retrieved over HTTPS.");
        }
        this.cacheFile = cacheFile.toAbsolutePath().normalize();
        this.manifestUri = manifestUri;
        this.clock = clock;
        this.fetcher = fetcher;
    }

    public VersionCatalog loadCatalog(boolean forceRefresh) throws IOException {
        VersionCatalog cached = null;
        IOException cacheError = null;
        if (Files.exists(cacheFile, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (Files.isSymbolicLink(cacheFile)
                        || !Files.isRegularFile(cacheFile, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Version cache is not a regular file: " + cacheFile);
                }
                if (Files.size(cacheFile) > MAX_MANIFEST_BYTES) {
                    throw new IOException("Cached Minecraft version manifest exceeds the size limit.");
                }
                Instant modifiedAt = Files.getLastModifiedTime(cacheFile, LinkOption.NOFOLLOW_LINKS)
                        .toInstant();
                cached = parseCatalog(Files.readAllBytes(cacheFile), modifiedAt, true, false);
                if (!forceRefresh && isFresh(modifiedAt)) {
                    return cached;
                }
            } catch (IOException exception) {
                cacheError = exception;
            }
        }

        try {
            byte[] response = fetcher.fetch(manifestUri);
            VersionCatalog fresh = parseCatalog(response, clock.instant(), false, false);
            writeCache(response);
            return fresh;
        } catch (IOException exception) {
            if (cached != null) {
                return new VersionCatalog(cached.latestRelease(), cached.latestSnapshot(),
                        cached.versions(), true, true, cached.lastUpdated());
            }
            if (cacheError != null) {
                exception.addSuppressed(cacheError);
            }
            throw exception;
        }
    }

    private boolean isFresh(Instant modifiedAt) {
        Duration age = Duration.between(modifiedAt, clock.instant());
        return !age.isNegative() && age.compareTo(CACHE_LIFETIME) < 0;
    }

    private VersionCatalog parseCatalog(
            byte[] bytes, Instant lastUpdated, boolean fromCache, boolean stale) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_MANIFEST_BYTES) {
            throw new IOException("Minecraft version manifest has an invalid size.");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(bytes);
        } catch (IOException exception) {
            throw new IOException("Minecraft version manifest is not valid JSON.", exception);
        }
        String latestRelease = text(root, "latest", "release");
        String latestSnapshot = text(root, "latest", "snapshot");
        JsonNode versionsNode = root.get("versions");
        if (!(versionsNode instanceof ArrayNode versionsArray) || versionsArray.isEmpty()) {
            throw new IOException("Minecraft version manifest does not contain any versions.");
        }

        List<MinecraftVersion> versions = new ArrayList<>(versionsArray.size());
        for (JsonNode entry : versionsArray) {
            String id = requiredText(entry, "id");
            if (!VERSION_ID.matcher(id).matches() || id.equals(".") || id.equals("..")
                    || !id.equals(id.trim())) {
                throw new IOException("Minecraft metadata contains an invalid version identifier.");
            }
            String type = requiredText(entry, "type");
            String url = requiredText(entry, "url");
            URI metadataUri;
            try {
                metadataUri = URI.create(url);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Minecraft metadata contains an invalid version URL.", exception);
            }
            if (!"https".equalsIgnoreCase(metadataUri.getScheme())
                    || metadataUri.getHost() == null || metadataUri.getUserInfo() != null) {
                throw new IOException("Minecraft version metadata URL must use HTTPS: " + id);
            }
            String sha1 = optionalText(entry, "sha1");
            if (sha1 != null && !SHA1.matcher(sha1).matches()) {
                throw new IOException("Minecraft metadata contains an invalid SHA-1 for " + id);
            }
            JsonNode complianceLevel = entry.get("complianceLevel");
            if (complianceLevel != null
                    && (!complianceLevel.isIntegralNumber() || complianceLevel.asInt() < 0)) {
                throw new IOException("Minecraft metadata contains an invalid compliance level for " + id);
            }
            versions.add(new MinecraftVersion(id, type, url,
                    optionalText(entry, "time"), optionalText(entry, "releaseTime"),
                    sha1, complianceLevel == null ? 0 : complianceLevel.asInt()));
        }
        Set<String> versionIds = versions.stream().map(MinecraftVersion::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!versionIds.contains(latestRelease) || !versionIds.contains(latestSnapshot)) {
            throw new IOException("Minecraft version manifest latest entries are not in its catalog.");
        }
        return new VersionCatalog(latestRelease, latestSnapshot, versions,
                fromCache, stale, lastUpdated);
    }

    private void writeCache(byte[] contents) throws IOException {
        Path parent = cacheFile.getParent();
        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, "versions-", ".tmp");
        try {
            Files.write(temporaryFile, contents);
            try {
                Files.move(temporaryFile, cacheFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static String text(JsonNode parent, String objectName, String fieldName)
            throws IOException {
        JsonNode object = parent.get(objectName);
        if (object == null) {
            throw new IOException("Minecraft version manifest is missing " + objectName + ".");
        }
        return requiredText(object, fieldName);
    }

    private static String requiredText(JsonNode parent, String fieldName) throws IOException {
        String value = optionalText(parent, fieldName);
        if (value == null || value.isBlank()) {
            throw new IOException("Minecraft version metadata is missing " + fieldName + ".");
        }
        return value;
    }

    private static String optionalText(JsonNode parent, String fieldName) {
        JsonNode value = parent.get(fieldName);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static byte[] fetchOfficial(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<java.io.InputStream> response =
                    HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("Minecraft version service returned HTTP "
                        + response.statusCode() + ".");
            }
            try (InputStream body = response.body()) {
                byte[] content = body.readNBytes(MAX_MANIFEST_BYTES + 1);
                if (content.length > MAX_MANIFEST_BYTES) {
                    throw new IOException("Minecraft version manifest exceeds the size limit.");
                }
                return content;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Minecraft version request was interrupted.", exception);
        }
    }

    @FunctionalInterface
    interface ManifestFetcher {
        byte[] fetch(URI uri) throws IOException;
    }
}
