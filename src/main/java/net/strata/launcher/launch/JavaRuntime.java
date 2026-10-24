package net.strata.launcher.launch;

import java.nio.file.Path;

public record JavaRuntime(Path executable, int majorVersion, String version) {
    public JavaRuntime {
        executable = executable.toAbsolutePath().normalize();
    }

    public String displayName() {
        return "Java " + majorVersion + " (" + executable + ")";
    }
}
