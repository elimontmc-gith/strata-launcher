package net.strata.launcher.loader;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.strata.launcher.launch.JavaRuntime;

public final class ModLoaderService {
    private final Map<String, LoaderProvider> providers = Map.of(
            "FABRIC", new ProfileLoaderProvider("fabric",
                    URI.create("https://meta.fabricmc.net/v2/"),
                    java.util.Set.of("meta.fabricmc.net", "maven.fabricmc.net")),
            "QUILT", new ProfileLoaderProvider("quilt",
                    URI.create("https://meta.quiltmc.org/v3/"),
                    java.util.Set.of("meta.quiltmc.org", "maven.quiltmc.org")),
            "NEOFORGE", new NeoForgeLoaderProvider());

    public List<LoaderVersion> versions(String loader, String minecraftVersion)
            throws IOException, InterruptedException {
        return provider(loader).versions(minecraftVersion);
    }

    public String install(String loader, String minecraftVersion, String loaderVersion,
            Path gameDirectory, Path sharedCache, JavaRuntime runtime)
            throws IOException, InterruptedException {
        return provider(loader).install(minecraftVersion, loaderVersion,
                gameDirectory, sharedCache, runtime);
    }

    private LoaderProvider provider(String loader) throws IOException {
        LoaderProvider provider = providers.get(loader);
        if (provider == null) {
            throw new IOException("Unsupported mod loader: " + loader);
        }
        return provider;
    }
}
