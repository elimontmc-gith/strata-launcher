package net.strata.launcher.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionManifestServiceTest {
    private static final URI TEST_MANIFEST_URI =
            URI.create("https://piston-meta.mojang.com/test-manifest.json");
    private static final byte[] VALID_MANIFEST = """
            {
              "latest": {"release": "1.21.9", "snapshot": "26w01a"},
              "versions": [
                {
                  "id": "1.21.9", "type": "release",
                  "url": "https://piston-meta.mojang.com/version.json",
                  "time": "2025-10-01T00:00:00+00:00",
                  "releaseTime": "2025-10-01T00:00:00+00:00",
                  "sha1": "0123456789012345678901234567890123456789",
                  "complianceLevel": 1
                },
                {
                  "id": "26w01a", "type": "snapshot",
                  "url": "https://piston-meta.mojang.com/snapshot.json",
                  "time": "2025-12-01T00:00:00+00:00",
                  "releaseTime": "2025-12-01T00:00:00+00:00"
                },
                {
                  "id": "1.14.2 Pre-Release 4", "type": "snapshot",
                  "url": "https://piston-meta.mojang.com/legacy-snapshot.json",
                  "time": "2019-05-01T00:00:00+00:00",
                  "releaseTime": "2019-05-01T00:00:00+00:00"
                }
              ]
            }
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;

    @Test
    void retrievesOfficialVersionMetadataAndUsesTheFreshCache() throws Exception {
        AtomicInteger fetchCount = new AtomicInteger();
        VersionManifestService service = new VersionManifestService(
                temporaryDirectory.resolve("cache/manifest.json"),
                TEST_MANIFEST_URI,
                java.time.Clock.systemUTC(),
                uri -> {
                    assertEquals(TEST_MANIFEST_URI, uri);
                    fetchCount.incrementAndGet();
                    return VALID_MANIFEST;
                });

        VersionCatalog downloaded = service.loadCatalog(false);
        VersionCatalog cached = service.loadCatalog(false);

        assertEquals("1.21.9", downloaded.latestRelease());
        assertEquals("26w01a", downloaded.latestSnapshot());
        assertEquals(3, downloaded.versions().size());
        assertEquals("snapshot", downloaded.versions().get(1).type());
        assertEquals("1.14.2 Pre-Release 4", downloaded.versions().get(2).id());
        assertFalse(downloaded.fromCache());
        assertTrue(cached.fromCache());
        assertFalse(cached.stale());
        assertEquals(1, fetchCount.get());
    }

    @Test
    void fallsBackToStaleCachedVersionsWhenNetworkRefreshFails() throws Exception {
        Path cacheFile = temporaryDirectory.resolve("manifest.json");
        VersionManifestService online = new VersionManifestService(
                cacheFile, TEST_MANIFEST_URI, java.time.Clock.systemUTC(), uri -> VALID_MANIFEST);
        online.loadCatalog(true);
        Files.setLastModifiedTime(cacheFile,
                FileTime.from(Instant.now().minus(1, ChronoUnit.DAYS)));

        VersionManifestService offline = new VersionManifestService(
                cacheFile, TEST_MANIFEST_URI, java.time.Clock.systemUTC(),
                uri -> { throw new IOException("offline"); });
        VersionCatalog catalog = offline.loadCatalog(false);

        assertTrue(catalog.fromCache());
        assertTrue(catalog.stale());
        assertEquals("1.21.9", catalog.latestRelease());
    }

    @Test
    void rejectsMalformedOrInsecureMetadataWithoutCachingIt() {
        Path cacheFile = temporaryDirectory.resolve("manifest.json");
        byte[] insecureManifest = new String(VALID_MANIFEST, java.nio.charset.StandardCharsets.UTF_8)
                .replace("https://piston-meta.mojang.com/version.json", "http://example.invalid/version.json")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        VersionManifestService service = new VersionManifestService(
                cacheFile, TEST_MANIFEST_URI, java.time.Clock.systemUTC(), uri -> insecureManifest);

        assertThrows(IOException.class, () -> service.loadCatalog(true));
        assertFalse(Files.exists(cacheFile));
        assertThrows(IllegalArgumentException.class, () -> new VersionManifestService(
                cacheFile, URI.create("http://example.invalid/manifest.json"),
                java.time.Clock.systemUTC(), uri -> VALID_MANIFEST));
    }
}
