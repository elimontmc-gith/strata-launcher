package net.strata.launcher.loader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.strata.launcher.launch.JavaRuntime;

public interface LoaderProvider {
    String id();

    List<LoaderVersion> versions(String minecraftVersion) throws IOException, InterruptedException;

    String install(String minecraftVersion, String loaderVersion, Path gameDirectory,
            Path sharedCache, JavaRuntime javaRuntime) throws IOException, InterruptedException;
}
