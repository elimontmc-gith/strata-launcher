package net.strata.launcher.loader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import net.strata.launcher.launch.JavaRuntime;

public final class NeoForgeLoaderProvider implements LoaderProvider {
    private static final String MAVEN = "https://maven.neoforged.net/releases/";
    private static final URI METADATA = URI.create(MAVEN
            + "net/neoforged/neoforge/maven-metadata.xml");
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public String id() {
        return "NEOFORGE";
    }

    @Override
    public List<LoaderVersion> versions(String minecraftVersion)
            throws IOException, InterruptedException {
        String prefix = neoForgeVersionPrefix(minecraftVersion);
        HttpRequest request = HttpRequest.newBuilder(METADATA)
                .timeout(Duration.ofSeconds(30)).GET().build();
        byte[] metadata;
        HttpResponse<java.io.InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("NeoForge metadata request failed with HTTP "
                        + response.statusCode());
            }
            metadata = readBounded(body, 10 * 1024 * 1024, "NeoForge metadata");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(metadata));
            var nodes = document.getElementsByTagName("version");
            List<LoaderVersion> versions = new ArrayList<>();
            for (int index = 0; index < nodes.getLength(); index++) {
                String version = nodes.item(index).getTextContent().trim();
                if (version.startsWith(prefix) && version.matches("[A-Za-z0-9._+-]{1,80}")) {
                    versions.add(new LoaderVersion(version, minecraftVersion));
                }
            }
            versions.sort((left, right) -> compareVersions(right.version(), left.version()));
            return List.copyOf(versions);
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException exception) {
            throw new IOException("Could not parse NeoForge Maven metadata.", exception);
        }
    }

    @Override
    public String install(String minecraftVersion, String loaderVersion, Path gameDirectory,
            Path sharedCache, JavaRuntime javaRuntime) throws IOException, InterruptedException {
        if (!loaderVersion.startsWith(neoForgeVersionPrefix(minecraftVersion))
                || !loaderVersion.matches("[A-Za-z0-9._+-]{1,80}")) {
            throw new IOException("NeoForge version does not match Minecraft " + minecraftVersion);
        }
        Path gameRoot = gameDirectory.toAbsolutePath().normalize();
        Path vanillaMetadata = gameRoot.resolve("versions").resolve(minecraftVersion)
                .resolve(minecraftVersion + ".json");
        if (!Files.isRegularFile(vanillaMetadata, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(vanillaMetadata)) {
            throw new IOException("Install vanilla Minecraft " + minecraftVersion + " first.");
        }
        Path installerCache = sharedCache.resolve("installers/neoforge");
        Files.createDirectories(installerCache);
        Path installer = installerCache.resolve("neoforge-" + loaderVersion + "-installer.jar");
        if (!Files.isRegularFile(installer, LinkOption.NOFOLLOW_LINKS)) {
            URI uri = URI.create(MAVEN + "net/neoforged/neoforge/" + loaderVersion
                    + "/neoforge-" + loaderVersion + "-installer.jar");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(2)).GET().build();
            HttpResponse<java.io.InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] installerBytes;
            try (var body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("NeoForge installer download failed with HTTP "
                            + response.statusCode());
                }
                installerBytes = readBounded(body, 256L * 1024 * 1024, "NeoForge installer");
            }
            if (installerBytes.length == 0) {
                throw new IOException("NeoForge installer has an invalid size.");
            }
            Path temporary = Files.createTempFile(installerCache, "neoforge-", ".tmp");
            try {
                Files.write(temporary, installerBytes);
                Files.move(temporary, installer, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        if (Files.isSymbolicLink(gameRoot) || Files.isSymbolicLink(installer)) {
            throw new IOException("NeoForge installation paths cannot be symbolic links.");
        }
        Process process = new ProcessBuilder(javaRuntime.executable().toString(), "-jar",
                installer.toString(), "--installClient", gameRoot.toString())
                .directory(gameRoot.toFile()).redirectErrorStream(true).start();
        java.util.concurrent.atomic.AtomicReference<IOException> outputFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread outputDrainer = Thread.ofVirtual().start(() -> {
            try (var input = process.getInputStream()) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException exception) {
                outputFailure.set(exception);
            }
        });
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
                process.destroyForcibly();
                outputDrainer.join();
                throw new IOException("NeoForge installer timed out.");
            }
            outputDrainer.join();
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            outputDrainer.interrupt();
            Thread.currentThread().interrupt();
            throw exception;
        }
        if (outputFailure.get() != null) {
            throw new IOException("Could not read NeoForge installer output.", outputFailure.get());
        }
        if (process.exitValue() != 0) {
            throw new IOException("NeoForge installer exited with code " + process.exitValue() + ".");
        }
        Path versionsRoot = gameRoot.resolve("versions");
        try (var paths = Files.list(versionsRoot)) {
            return paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isSymbolicLink(path))
                    .map(path -> path.getFileName().toString())
                    .filter(versionId -> versionId.toLowerCase(Locale.ROOT).contains("neoforge"))
                    .filter(versionId -> {
                        Path json = versionsRoot.resolve(versionId).resolve(versionId + ".json");
                        try {
                            com.fasterxml.jackson.databind.JsonNode metadata =
                                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json.toFile());
                            return minecraftVersion.equals(metadata.path("inheritsFrom").asText());
                        } catch (IOException exception) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "NeoForge installer completed but no compatible version profile was created."));
        }
    }

    private static String neoForgeVersionPrefix(String minecraftVersion) throws IOException {
        var matcher = java.util.regex.Pattern.compile("^1\\.(\\d+)(?:\\.(\\d+))?.*$")
                .matcher(minecraftVersion);
        if (!matcher.matches()) {
            throw new IOException("Unsupported Minecraft version for NeoForge: " + minecraftVersion);
        }
        return matcher.group(1) + "." + (matcher.group(2) == null ? "0" : matcher.group(2)) + ".";
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.split("[.-]");
        String[] rightParts = right.split("[.-]");
        for (int index = 0; index < Math.min(leftParts.length, rightParts.length); index++) {
            try {
                int comparison = Integer.compare(Integer.parseInt(leftParts[index]),
                        Integer.parseInt(rightParts[index]));
                if (comparison != 0) {
                    return comparison;
                }
            } catch (NumberFormatException exception) {
                int comparison = leftParts[index].compareToIgnoreCase(rightParts[index]);
                if (comparison != 0) {
                    return comparison;
                }
            }
        }
        return Integer.compare(leftParts.length, rightParts.length);
    }

    private static byte[] readBounded(java.io.InputStream input, long maxBytes, String description)
            throws IOException {
        java.io.ByteArrayOutputStream content = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) {
                throw new IOException(description + " exceeds its size limit.");
            }
            content.write(buffer, 0, count);
        }
        return content.toByteArray();
    }
}
