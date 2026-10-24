package net.strata.launcher.instance;

import java.util.Locale;

public enum InstanceContentCategory {
    MODS("Mods", "mods", ".jar"),
    RESOURCE_PACKS("Resource packs", "resourcepacks", ".zip"),
    SHADER_PACKS("Shader packs", "shaderpacks", ".zip");

    private final String displayName;
    private final String directoryName;
    private final String extension;

    InstanceContentCategory(String displayName, String directoryName, String extension) {
        this.displayName = displayName;
        this.directoryName = directoryName;
        this.extension = extension;
    }

    public String displayName() {
        return displayName;
    }

    public String directoryName() {
        return directoryName;
    }

    public boolean accepts(String filename) {
        return filename.toLowerCase(Locale.ROOT).endsWith(extension);
    }

    public String extensionDescription() {
        return extension;
    }
}
