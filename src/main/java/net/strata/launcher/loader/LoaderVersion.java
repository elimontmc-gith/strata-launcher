package net.strata.launcher.loader;

public record LoaderVersion(String version, String minecraftVersion) {
    @Override
    public String toString() {
        return version;
    }
}
