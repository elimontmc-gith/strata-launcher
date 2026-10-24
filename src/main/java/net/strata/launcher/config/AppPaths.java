package net.strata.launcher.config;

import java.nio.file.Path;

public final class AppPaths {
    private AppPaths() {
    }

    public static Path dataDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            return Path.of(System.getenv().getOrDefault("APPDATA", home), "StrataLauncher");
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "StrataLauncher");
        }
        return Path.of(System.getenv().getOrDefault("XDG_CONFIG_HOME",
                Path.of(home, ".config").toString()), "strata-launcher");
    }
}
