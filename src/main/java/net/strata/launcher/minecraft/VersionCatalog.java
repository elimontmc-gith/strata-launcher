package net.strata.launcher.minecraft;

import java.time.Instant;
import java.util.List;

public record VersionCatalog(
        String latestRelease,
        String latestSnapshot,
        List<MinecraftVersion> versions,
        boolean fromCache,
        boolean stale,
        Instant lastUpdated) {
    public VersionCatalog {
        versions = List.copyOf(versions);
    }
}
