package net.strata.launcher.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.strata.launcher.launch.JavaRuntime;

public final class ProfileLoaderProvider implements LoaderProvider {
    private static final int MAX_METADATA_BYTES = 10 * 1024 * 1024;
    private static final long MAX_LIBRARY_BYTES = 256L * 1024 * 1024;

    private final String id;
    private final URI metadataBase;
    private final Set<String> allowedHosts;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public ProfileLoaderProvider(String id, URI metadataBase, Set<String> allowedHosts) {
        this.id = id;
        this.metadataBase = metadataBase;
        this.allowedHosts = Set.copyOf(allowedHosts);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<LoaderVersion> versions(String minecraftVersion)
            throws IOException, InterruptedException {
        validateComponent(minecraftVersion);
        JsonNode response = getJson(metadataBase.resolve("versions/loader/"
                + encodePath(minecraftVersion)));
        if (!response.isArray()) {
            throw new IOException(id + " metadata returned an invalid version list.");
        }
        List<LoaderVersion> result = new ArrayList<>();
        for (JsonNode item : response) {
            String version = item.path("loader").path("version").asText(null);
            if (version != null && version.matches("[A-Za-z0-9._+-]{1,80}")) {
                result.add(new LoaderVersion(version, minecraftVersion));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public String install(String minecraftVersion, String loaderVersion, Path gameDirectory,
            Path sharedCache, JavaRuntime javaRuntime) throws IOException, InterruptedException {
        validateComponent(minecraftVersion);
        validateComponent(loaderVersion);
        Path gameRoot = gameDirectory.toAbsolutePath().normalize();
        Path versionsRoot = gameRoot.resolve("versions").normalize();
        Path vanillaMetadata = versionsRoot.resolve(minecraftVersion)
                .resolve(minecraftVersion + ".json");
        if (!Files.isRegularFile(vanillaMetadata, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(vanillaMetadata)) {
            throw new IOException("Install vanilla Minecraft " + minecraftVersion + " first.");
        }

        URI profileUri = metadataBase.resolve("versions/loader/" + encodePath(minecraftVersion)
                + "/" + encodePath(loaderVersion) + "/profile/json");
        byte[] profileData = getBytes(profileUri, MAX_METADATA_BYTES);
        JsonNode profile = mapper.readTree(profileData);
        String profileId = requiredText(profile, "id");
        String inheritedVersion = requiredText(profile, "inheritsFrom");
        validateComponent(profileId);
        if (!minecraftVersion.equals(inheritedVersion)
                || !profileId.toLowerCase(java.util.Locale.ROOT).contains(id.toLowerCase())) {
            throw new IOException(id + " profile does not match the selected Minecraft version.");
        }
        downloadLibraries(profile.path("libraries"), sharedCache.resolve("libraries"));
        Path profileDirectory = versionsRoot.resolve(profileId);
        if (Files.isSymbolicLink(gameRoot) || Files.isSymbolicLink(versionsRoot)
                || Files.isSymbolicLink(profileDirectory)) {
            throw new IOException("Mod-loader installation directory cannot be a symbolic link.");
        }
        Files.createDirectories(profileDirectory);
        Path target = profileDirectory.resolve(profileId + ".json");
        Path temporary = Files.createTempFile(profileDirectory, "loader-", ".tmp");
        try {
            Files.write(temporary, profileData);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return profileId;
    }

    private void downloadLibraries(JsonNode libraries, Path librariesRoot)
            throws IOException, InterruptedException {
        if (!libraries.isArray()) {
            return;
        }
        for (JsonNode library : libraries) {
            JsonNode artifact = library.path("downloads").path("artifact");
            String relative = artifact.path("path").asText(null);
            String coordinate = library.path("name").asText(null);
            if (relative == null && coordinate != null) {
                relative = mavenPath(coordinate);
            }
            if (relative == null) {
                continue;
            }
            Path target = safeResolve(librariesRoot, relative);
            String expected = artifact.path("sha1").asText(library.path("sha1").asText(null));
            long expectedSize = artifact.path("size").asLong(library.path("size").asLong(-1));
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(target)) {
                if ((expected == null || expected.equalsIgnoreCase(sha1(target)))
                        && (expectedSize < 0 || Files.size(target) == expectedSize)) {
                    continue;
                }
            }
            URI uri = artifact.path("url").isTextual()
                    ? URI.create(artifact.path("url").asText())
                    : MavenUrl.forLibrary(library, relative, metadataBase);
            requireAllowed(uri);
            byte[] contents = getBytes(uri, MAX_LIBRARY_BYTES);
            if (expectedSize >= 0 && contents.length != expectedSize) {
                throw new IOException("Size mismatch for loader dependency " + relative);
            }
            if (expected != null && !expected.equalsIgnoreCase(sha1(contents))) {
                throw new IOException("Checksum mismatch for loader dependency " + relative);
            }
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "loader-", ".tmp");
            try {
                Files.write(temporary, contents);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private JsonNode getJson(URI uri) throws IOException, InterruptedException {
        return mapper.readTree(getBytes(uri, MAX_METADATA_BYTES));
    }

    private byte[] getBytes(URI uri, long maxBytes) throws IOException, InterruptedException {
        requireAllowed(uri);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).GET().build();
        HttpResponse<java.io.InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException(id + " metadata request failed with HTTP "
                        + response.statusCode());
            }
            java.io.ByteArrayOutputStream content = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = body.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) {
                    throw new IOException(id + " download exceeds its size limit.");
                }
                content.write(buffer, 0, count);
            }
            return content.toByteArray();
        }
    }

    private void requireAllowed(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getHost() == null || !allowedHosts.contains(uri.getHost().toLowerCase())) {
            throw new IOException("Refusing an untrusted mod-loader URL.");
        }
    }

    private static Path safeResolve(Path root, String relative) throws IOException {
        if (relative.startsWith("/") || relative.startsWith("\\")
                || List.of(relative.replace('\\', '/').split("/")).contains("..")) {
            throw new IOException("Unsafe mod-loader library path.");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result = normalizedRoot.resolve(relative).normalize();
        if (!result.startsWith(normalizedRoot) || Files.isSymbolicLink(normalizedRoot)) {
            throw new IOException("Mod-loader library path escapes the library cache.");
        }
        Path current = normalizedRoot;
        for (Path component : normalizedRoot.relativize(result)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Mod-loader library path cannot contain symbolic links.");
            }
        }
        return result;
    }

    private static String mavenPath(String coordinate) throws IOException {
        String[] parts = coordinate.split(":", -1);
        if (parts.length < 3 || !parts[0].matches("[A-Za-z0-9_.-]+")
                || !parts[1].matches("[A-Za-z0-9_.-]+")) {
            throw new IOException("Mod-loader metadata contains an invalid Maven coordinate.");
        }
        String version = parts[2];
        String extension = "jar";
        int separator = version.indexOf('@');
        if (separator >= 0) {
            extension = version.substring(separator + 1);
            version = version.substring(0, separator);
        }
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + version + "/"
                + parts[1] + "-" + version + classifier + "." + extension;
    }

    private static String sha1(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (var input = Files.newInputStream(path);
                    var digestInput = new java.security.DigestInputStream(input, digest)) {
                digestInput.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable.", exception);
        }
    }

    private static String sha1(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable.", exception);
        }
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IOException("Mod-loader profile is missing " + field + ".");
        }
        return value;
    }

    private static String encodePath(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static void validateComponent(String value) throws IOException {
        if (value == null || !Pattern.matches("[A-Za-z0-9._+-]{1,100}", value)
                || value.equals(".") || value.equals("..")) {
            throw new IOException("Invalid mod-loader version identifier.");
        }
    }

    private static final class MavenUrl {
        private static URI forLibrary(JsonNode library, String path, URI defaultBase)
                throws IOException {
            String base = library.path("url").asText(defaultBase.toString());
            if (!base.endsWith("/")) {
                base += "/";
            }
            return URI.create(base).resolve(path);
        }
    }
}
