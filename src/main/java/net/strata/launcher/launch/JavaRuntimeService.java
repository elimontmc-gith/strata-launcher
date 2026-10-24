package net.strata.launcher.launch;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaRuntimeService {
    private static final Pattern VERSION = Pattern.compile("version \"([^\"]+)\"");

    public List<JavaRuntime> detectInstalledRuntimes() {
        Set<Path> candidates = new LinkedHashSet<>();
        String configured = System.getenv("JAVA_HOME");
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured).resolve("bin").resolve(executableName()));
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(Pattern.quote(System.getProperty("path.separator")))) {
                if (!entry.isBlank()) {
                    candidates.add(Path.of(entry).resolve(executableName()));
                }
            }
        }
        for (String root : List.of("/usr/lib/jvm", "/opt/java", "/Library/Java/JavaVirtualMachines")) {
            Path directory = Path.of(root);
            if (Files.isDirectory(directory)) {
                try (var entries = Files.list(directory)) {
                    entries.forEach(candidate -> {
                        candidates.add(candidate.resolve("bin").resolve(executableName()));
                        candidates.add(candidate.resolve("Contents/Home/bin").resolve(executableName()));
                    });
                } catch (IOException ignored) {
                    // Runtime discovery is best-effort across optional system directories.
                }
            }
        }

        List<JavaRuntime> runtimes = new ArrayList<>();
        for (Path candidate : candidates) {
            Path executable = candidate.toAbsolutePath().normalize();
            if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                continue;
            }
            try {
                JavaRuntime runtime = inspect(executable);
                if (runtimes.stream().noneMatch(existing ->
                        existing.executable().equals(runtime.executable()))) {
                    runtimes.add(runtime);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return List.copyOf(runtimes);
            } catch (IOException ignored) {
                // Ignore a candidate that cannot be queried as a Java runtime.
            }
        }
        runtimes.sort(java.util.Comparator.comparingInt(JavaRuntime::majorVersion).reversed()
                .thenComparing(runtime -> runtime.executable().toString()));
        return List.copyOf(runtimes);
    }

    public JavaRuntime inspect(Path executable) throws IOException, InterruptedException {
        Path normalized = executable.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || !Files.isExecutable(normalized)) {
            throw new IOException("Java executable is not an executable file: " + normalized);
        }
        Process process = new ProcessBuilder(normalized.toString(), "-version")
                .redirectErrorStream(true).start();
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (process.waitFor() != 0) {
            throw new IOException("Java executable failed its version check: " + normalized);
        }
        Matcher matcher = VERSION.matcher(output);
        if (!matcher.find()) {
            throw new IOException("Could not parse Java version from: " + normalized);
        }
        String version = matcher.group(1);
        int major = parseMajor(version);
        return new JavaRuntime(normalized, major, version);
    }

    public int requiredMajorVersion(int minecraftMajor, int minor, int patch) {
        if (minecraftMajor < 1) {
            throw new IllegalArgumentException("Minecraft version must be a release version.");
        }
        if (minecraftMajor > 1 || minor >= 20 && (minor > 20 || patch >= 5)) {
            return 21;
        }
        if (minor >= 18) {
            return 17;
        }
        if (minor >= 17) {
            return 16;
        }
        if (minor >= 12) {
            return 8;
        }
        return 8;
    }

    public int requiredMajorVersion(String minecraftVersion) throws IOException {
        Matcher matcher = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?.*$")
                .matcher(minecraftVersion);
        if (!matcher.matches()) {
            throw new IOException("Cannot determine Java compatibility for Minecraft "
                    + minecraftVersion + ".");
        }

        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
            return requiredMajorVersion(major, minor, patch);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid Minecraft version: " + minecraftVersion, exception);
        }
    }

    public int requiredMajorVersion(Iterable<JsonNode> metadata, String minecraftVersion)
            throws IOException {
        for (JsonNode version : metadata) {
            JsonNode major = version.path("javaVersion").path("majorVersion");
            if (major.canConvertToInt() && major.asInt() > 0) {
                return major.asInt();
            }
        }
        return requiredMajorVersion(minecraftVersion);
    }

    private static int parseMajor(String version) throws IOException {
        String normalized = version.startsWith("1.") ? version.substring(2) : version;
        int end = normalized.indexOf('.');
        if (end < 0) {
            end = normalized.indexOf('-');
        }
        if (end < 0) {
            end = normalized.length();
        }
        try {
            return Integer.parseInt(normalized.substring(0, end));
        } catch (NumberFormatException exception) {
            throw new IOException("Unsupported Java version: " + version, exception);
        }
    }

    private static String executableName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
    }
}
