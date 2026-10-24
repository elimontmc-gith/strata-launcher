package net.strata.launcher.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinecraftInstallationServiceTest {
    private static final String VERSION_ID = "1.21.9";
    private static final URI MANIFEST_URI =
            URI.create("https://piston-meta.mojang.com/test-manifest.json");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void installsVerifiedClientLibrariesAssetsAndNativeFiles() throws Exception {
        Fixture fixture = createFixture(false);
        VersionManifestService manifestService = createManifestService(
                temporaryDirectory.resolve("cache/manifest.json"), fixture.manifest());
        MinecraftInstallationService installer = new MinecraftInstallationService(
                temporaryDirectory.resolve("cache/minecraft"), manifestService,
                fixture.fileTransfer());
        Path game = temporaryDirectory.resolve("instance/game");
        var progress = new ArrayList<String>();

        installer.install(game, VERSION_ID,
                (stage, completed, total) -> progress.add(stage + " " + completed + "/" + total));

        assertTrue(installer.isInstalled(game, VERSION_ID));
        assertEquals("client jar", Files.readString(
                game.resolve("versions").resolve(VERSION_ID).resolve(VERSION_ID + ".jar")));
        assertEquals("library", Files.readString(
                temporaryDirectory.resolve("cache/minecraft/libraries/test/library/1.0/library-1.0.jar")));
        assertEquals("asset", Files.readString(
                temporaryDirectory.resolve("cache/minecraft/assets/objects")
                        .resolve(fixture.assetHash().substring(0, 2))
                        .resolve(fixture.assetHash())));
        assertEquals("native", Files.readString(game.resolve("natives")
                .resolve(VERSION_ID).resolve("native/sample.bin")));
        assertFalse(Files.exists(game.resolve("natives")
                .resolve(VERSION_ID).resolve("META-INF/test.txt")));
        assertFalse(Files.exists(game.resolve("versions").resolve(VERSION_ID).resolve("natives")));
        assertTrue(progress.getLast().contains("installation complete"));
    }

    @Test
    void failedChecksumDoesNotReplaceAnExistingInstallation() throws Exception {
        Fixture fixture = createFixture(false);
        Path game = temporaryDirectory.resolve("instance/game");
        VersionManifestService firstManifest = createManifestService(
                temporaryDirectory.resolve("cache/manifest.json"), fixture.manifest());
        MinecraftInstallationService firstInstaller = new MinecraftInstallationService(
                temporaryDirectory.resolve("cache/minecraft"), firstManifest, fixture.fileTransfer());
        firstInstaller.install(game, VERSION_ID, (stage, completed, total) -> { });

        Fixture corrupted = fixture.withCorruptedClient();
        VersionManifestService secondManifest = createManifestService(
                temporaryDirectory.resolve("cache/manifest.json"), corrupted.manifest());
        MinecraftInstallationService secondInstaller = new MinecraftInstallationService(
                temporaryDirectory.resolve("cache/minecraft"), secondManifest,
                corrupted.fileTransfer());

        assertThrows(IOException.class, () -> secondInstaller.install(game, VERSION_ID,
                (stage, completed, total) -> { }));
        assertTrue(secondInstaller.isInstalled(game, VERSION_ID));
        assertEquals("client jar", Files.readString(
                game.resolve("versions").resolve(VERSION_ID).resolve(VERSION_ID + ".jar")));
    }

    @Test
    void rejectsLibraryPathTraversalBeforeWritingOutsideTheCache() throws Exception {
        Fixture fixture = createFixture(true);
        Path cache = temporaryDirectory.resolve("cache/minecraft");
        MinecraftInstallationService installer = new MinecraftInstallationService(cache,
                createManifestService(temporaryDirectory.resolve("cache/manifest.json"),
                        fixture.manifest()),
                fixture.fileTransfer());

        assertThrows(IOException.class, () -> installer.install(
                temporaryDirectory.resolve("instance/game"), VERSION_ID,
                (stage, completed, total) -> { }));
        assertFalse(Files.exists(temporaryDirectory.resolve("outside.jar")));
    }

    private Fixture createFixture(boolean traversalPath) throws Exception {
        byte[] client = "client jar".getBytes(StandardCharsets.UTF_8);
        byte[] library = "library".getBytes(StandardCharsets.UTF_8);
        byte[] asset = "asset".getBytes(StandardCharsets.UTF_8);
        byte[] nativeJar = createNativeJar();
        String assetHash = sha1(asset);

        ObjectNode assetIndex = objectMapper.createObjectNode();
        assetIndex.put("id", "test-assets");
        assetIndex.put("url", "https://piston-meta.mojang.com/test-assets.json");
        assetIndex.put("sha1", sha1(assetIndexBytes(assetHash, asset.length)));
        byte[] assetIndexJson = assetIndexBytes(assetHash, asset.length);
        assetIndex.put("size", assetIndexJson.length);

        ObjectNode clientDownload = download("https://launcher.mojang.com/test-client.jar", client);
        ObjectNode libraryArtifact = download("https://libraries.minecraft.net/test/library/1.0/library-1.0.jar",
                library);
        libraryArtifact.put("path", traversalPath
                ? "../../outside.jar" : "test/library/1.0/library-1.0.jar");
        ObjectNode nativeArtifact = download(
                "https://libraries.minecraft.net/test/library/1.0/library-1.0-natives-linux.jar",
                nativeJar);
        nativeArtifact.put("path", "test/library/1.0/library-1.0-natives-linux.jar");

        ObjectNode libraryNode = objectMapper.createObjectNode();
        libraryNode.put("name", "test:library:1.0");
        ObjectNode libraryDownloads = libraryNode.putObject("downloads");
        libraryDownloads.set("artifact", libraryArtifact);
        libraryDownloads.putObject("classifiers").set("natives-linux", nativeArtifact);
        libraryNode.putObject("natives").put("linux", "natives-linux");
        ArrayNode excludes = libraryNode.putObject("extract").putArray("exclude");
        excludes.add("META-INF/");

        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("id", VERSION_ID);
        metadata.put("type", "release");
        metadata.put("mainClass", "net.minecraft.client.main.Main");
        metadata.put("assets", "test-assets");
        metadata.set("assetIndex", assetIndex);
        metadata.putObject("downloads").set("client", clientDownload);
        metadata.putArray("libraries").add(libraryNode);
        metadata.putObject("javaVersion").put("majorVersion", 21);
        byte[] metadataJson = objectMapper.writeValueAsBytes(metadata);

        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.putObject("latest").put("release", VERSION_ID).put("snapshot", VERSION_ID);
        ObjectNode version = manifest.putArray("versions").addObject();
        version.put("id", VERSION_ID);
        version.put("type", "release");
        version.put("url", "https://piston-meta.mojang.com/test-version.json");
        version.put("sha1", sha1(metadataJson));
        version.put("time", "2025-10-01T00:00:00+00:00");
        version.put("releaseTime", "2025-10-01T00:00:00+00:00");
        byte[] manifestJson = objectMapper.writeValueAsBytes(manifest);

        Map<URI, byte[]> files = new HashMap<>();
        files.put(URI.create("https://piston-meta.mojang.com/test-version.json"), metadataJson);
        files.put(URI.create("https://launcher.mojang.com/test-client.jar"), client);
        files.put(URI.create("https://libraries.minecraft.net/test/library/1.0/library-1.0.jar"),
                library);
        files.put(URI.create("https://libraries.minecraft.net/test/library/1.0/library-1.0-natives-linux.jar"),
                nativeJar);
        files.put(URI.create("https://piston-meta.mojang.com/test-assets.json"), assetIndexJson);
        files.put(URI.create("https://resources.download.minecraft.net/"
                + assetHash.substring(0, 2) + "/" + assetHash), asset);
        return new Fixture(manifestJson, files, assetHash);
    }

    private ObjectNode download(String url, byte[] bytes) throws Exception {
        ObjectNode download = objectMapper.createObjectNode();
        download.put("url", url);
        download.put("sha1", sha1(bytes));
        download.put("size", bytes.length);
        return download;
    }

    private byte[] assetIndexBytes(String assetHash, int assetSize) throws Exception {
        ObjectNode index = objectMapper.createObjectNode();
        index.putObject("objects").putObject("sample").put("hash", assetHash)
                .put("size", assetSize);
        return objectMapper.writeValueAsBytes(index);
    }

    private static byte[] createNativeJar() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("native/sample.bin"));
            zip.write("native".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/test.txt"));
            zip.write("not extracted".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    private VersionManifestService createManifestService(Path cache, byte[] manifest) {
        return new VersionManifestService(cache, MANIFEST_URI, Clock.systemUTC(), uri -> manifest);
    }

    private record Fixture(byte[] manifest, Map<URI, byte[]> files, String assetHash) {
        MinecraftInstallationService.FileTransfer fileTransfer() {
            return (uri, destination, expectedSize) -> {
                byte[] bytes = files.get(uri);
                if (bytes == null) {
                    throw new IOException("Unexpected test download: " + uri);
                }
                Files.write(destination, bytes);
            };
        }

        Fixture withCorruptedClient() {
            Map<URI, byte[]> changedFiles = new HashMap<>(files);
            changedFiles.put(URI.create("https://launcher.mojang.com/test-client.jar"),
                    "corrupt jar".getBytes(StandardCharsets.UTF_8));
            return new Fixture(manifest, changedFiles, assetHash);
        }
    }
}
