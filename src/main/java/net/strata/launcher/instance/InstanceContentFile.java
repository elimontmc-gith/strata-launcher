package net.strata.launcher.instance;

public record InstanceContentFile(String filename, long sizeBytes) {
    @Override
    public String toString() {
        double megabytes = sizeBytes / (1024.0 * 1024.0);
        return "%s  ·  %.1f MB".formatted(filename, megabytes);
    }
}
