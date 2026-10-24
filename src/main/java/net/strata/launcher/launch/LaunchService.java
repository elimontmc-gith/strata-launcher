package net.strata.launcher.launch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import net.strata.launcher.auth.AccountProfile;
import net.strata.launcher.auth.AccountType;
import net.strata.launcher.auth.AuthenticationSession;
import net.strata.launcher.domain.InstanceConfiguration;
import net.strata.launcher.domain.MinecraftInstance;

public final class LaunchService {
    private static final Pattern VERSION_ID = Pattern.compile("[A-Za-z0-9._ -]{1,100}");

    private final Path sharedCache;
    private final JavaRuntimeService runtimeService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentMap<UUID, Process> processes = new ConcurrentHashMap<>();

    public LaunchService(Path sharedCache, JavaRuntimeService runtimeService) {
        this.sharedCache = sharedCache.toAbsolutePath().normalize();
        this.runtimeService = runtimeService;
    }

    public LaunchArguments buildCommand(MinecraftInstance instance, Path gameDirectory,
            JavaRuntime runtime, AccountProfile account, AuthenticationSession session,
            List<String> additionalJvmArguments) throws IOException {
        return buildCommand(instance, gameDirectory, runtime, account, session,
                InstanceConfiguration.defaults(), additionalJvmArguments);
    }

    public LaunchArguments buildCommand(MinecraftInstance instance, Path gameDirectory,
            JavaRuntime runtime, AccountProfile account, AuthenticationSession session,
            InstanceConfiguration configuration, List<String> additionalJvmArguments)
            throws IOException {
        if (instance.getMinecraftVersion() == null || instance.getMinecraftVersion().isBlank()) {
            throw new IOException("Choose a Minecraft version before launching this instance.");
        }
        validateVersionId(instance.getMinecraftVersion());
        if (account == null) {
            throw new IOException("Select an account before launching Minecraft.");
        }
        if (account.type() == AccountType.MICROSOFT
                && (session == null || session.isExpired()
                        || !session.profile().id().equals(account.id()))) {
            throw new IOException("Sign in to the selected Microsoft account before launching.");
        }

        Path gameRoot = gameDirectory.toAbsolutePath().normalize();
        Path versionsRoot = gameRoot.resolve("versions");
        String installedVersion = instance.getLoaderType().equalsIgnoreCase("VANILLA")
                ? instance.getMinecraftVersion()
                : instance.loaderProfileId() == null
                        ? instance.getLoaderVersion() : instance.loaderProfileId();
        if (installedVersion == null || installedVersion.isBlank()) {
            throw new IOException("Install the selected mod loader before launching.");
        }
        validateVersionId(installedVersion);
        Path selectedJson = versionsRoot.resolve(installedVersion)
                .resolve(installedVersion + ".json");
        if (!Files.isRegularFile(selectedJson, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(selectedJson)) {
            throw new IOException("Minecraft version is not installed for this instance.");
        }

        Map<String, JsonNode> chain = loadVersionChain(selectedJson, versionsRoot);
        if (!chain.containsKey(instance.getMinecraftVersion())) {
            throw new IOException("Installed loader profile does not inherit the selected Minecraft version.");
        }
        int requiredJava = runtimeService.requiredMajorVersion(
                chain.values(), instance.getMinecraftVersion());
        if (runtime.majorVersion() < requiredJava) {
            throw new IOException("Minecraft " + instance.getMinecraftVersion()
                    + " requires Java " + requiredJava + " or newer; selected runtime is Java "
                    + runtime.majorVersion() + ".");
        }
        JsonNode leaf = mapper.readTree(selectedJson.toFile());
        String leafId = requiredText(leaf, "id");
        Path clientJar = null;
        for (String versionId : chain.keySet()) {
            Path candidate = versionsRoot.resolve(versionId).resolve(versionId + ".jar");
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                clientJar = candidate;
                break;
            }
        }
        if (clientJar == null) {
            clientJar = versionsRoot.resolve(instance.getMinecraftVersion())
                    .resolve(instance.getMinecraftVersion() + ".jar");
        }
        if (!Files.isRegularFile(clientJar, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Minecraft client file is missing for version " + leafId + ".");
        }
        Path librariesDirectory = sharedCache.resolve("libraries");
        Path nativesDirectory = gameRoot.resolve("natives").resolve(instance.getMinecraftVersion());
        if (!Files.isDirectory(nativesDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Minecraft native libraries are missing. Reinstall this version.");
        }
        Path assetsDirectory = sharedCache.resolve("assets");
        String assetIndex = firstText(chain.values(), "assets", null);
        JsonNode index = findField(chain.values(), "assetIndex");
        if (index != null && optionalText(index, "id") != null) {
            assetIndex = optionalText(index, "id");
        }
        if (assetIndex == null) {
            throw new IOException("Minecraft metadata does not identify its asset index.");
        }

        String mainClass = firstText(chain.values(), "mainClass", null);
        if (mainClass == null) {
            throw new IOException("Minecraft metadata does not define a main class.");
        }
        List<String> classpath = buildClasspath(chain.values(), clientJar, librariesDirectory,
                gameRoot.resolve("libraries"));
        Map<String, String> values = placeholders(instance, chain.values(), gameRoot,
                assetsDirectory, assetIndex, nativesDirectory, classpath, account, session);
        int initialMemory = Math.min(512, configuration.memoryMegabytes());
        List<String> jvmArguments = new ArrayList<>(List.of(
                "-Xms" + initialMemory + "M",
                "-Xmx" + configuration.memoryMegabytes() + "M"));
        if (platformName().equals("osx")) {
            jvmArguments.add("-XstartOnFirstThread");
        }
        jvmArguments.addAll(additionalJvmArguments);
        appendMetadataArguments(chain.values(), "jvm", values, jvmArguments);
        JsonNode logging = findField(chain.values(), "logging");
        String loggingArgument = logging == null
                ? null : optionalText(logging.path("client"), "argument");
        if (loggingArgument != null) {
            String loggingPath = values.get("path");
            if (loggingPath == null || !Files.isRegularFile(Path.of(loggingPath),
                    LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Minecraft client logging configuration is missing.");
            }
            jvmArguments.add(replace(loggingArgument, values));
        }
        if (!containsOption(jvmArguments, "-Djava.library.path=")) {
            jvmArguments.add("-Djava.library.path=" + nativesDirectory);
        }
        if (!containsOption(jvmArguments, "-cp") && !containsOption(jvmArguments, "-classpath")) {
            jvmArguments.add("-cp");
            jvmArguments.add(String.join(System.getProperty("path.separator"), classpath));
        }
        jvmArguments.add(mainClass);

        List<String> command = new ArrayList<>();
        command.add(runtime.executable().toString());
        command.addAll(jvmArguments);
        List<String> gameArguments = new ArrayList<>();
        appendMetadataArguments(chain.values(), "game", values, gameArguments);
        if (gameArguments.isEmpty()) {
            String legacyArguments = firstText(chain.values(), "minecraftArguments", null);
            if (legacyArguments == null) {
                throw new IOException("Minecraft metadata does not define game arguments.");
            }
            for (String argument : tokenize(legacyArguments)) {
                gameArguments.add(replace(argument, values));
            }
        }
        replaceGameOption(gameArguments, "--width",
                Integer.toString(configuration.windowWidth()));
        replaceGameOption(gameArguments, "--height",
                Integer.toString(configuration.windowHeight()));
        removeGameOption(gameArguments, "--fullscreen");
        if (configuration.fullscreen()) {
            gameArguments.add("--fullscreen");
        }
        command.addAll(gameArguments);
        return new LaunchArguments(command, gameRoot);
    }

    public Process launch(UUID instanceId, LaunchArguments launchArguments, ProcessListener listener)
            throws IOException {
        if (processes.containsKey(instanceId)) {
            throw new IOException("This instance is already running.");
        }
        ProcessBuilder processBuilder = new ProcessBuilder(launchArguments.command())
                .directory(launchArguments.workingDirectory().toFile());
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        if (processes.putIfAbsent(instanceId, process) != null) {
            process.destroy();
            throw new IOException("This instance is already running.");
        }
        AtomicReference<String> outputFailure = new AtomicReference<>();
        Thread outputThread = Thread.ofVirtual().name("minecraft-output-" + instanceId).start(() -> {
            try (var reader = process.inputReader(java.nio.charset.StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    listener.output(line);
                }
            } catch (IOException exception) {
                outputFailure.set(exception.getMessage());
            }
        });
        Thread.ofVirtual().name("minecraft-wait-" + instanceId).start(() -> {
            try {
                int exitCode = process.waitFor();
                outputThread.join();
                listener.exited(exitCode, outputFailure.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                listener.failed(exception);
            } finally {
                processes.remove(instanceId, process);
            }
        });
        return process;
    }

    public boolean isRunning(UUID instanceId) {
        Process process = processes.get(instanceId);
        return process != null && process.isAlive();
    }

    public void stop(UUID instanceId) throws IOException {
        Process process = processes.get(instanceId);
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping Minecraft.", exception);
        }
    }

    private Map<String, JsonNode> loadVersionChain(Path selected, Path versionsRoot)
            throws IOException {
        Map<String, JsonNode> chain = new java.util.LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        Path current = selected;
        while (current != null) {
            if (Files.isSymbolicLink(current) || !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Minecraft version metadata is missing: " + current);
            }
            JsonNode metadata = mapper.readTree(current.toFile());
            String id = requiredText(metadata, "id");
            validateVersionId(id);
            if (!current.getParent().getFileName().toString().equals(id)) {
                throw new IOException("Minecraft metadata ID does not match its version directory.");
            }
            if (!seen.add(id)) {
                throw new IOException("Minecraft version metadata contains an inheritance cycle.");
            }
            chain.putIfAbsent(id, metadata);
            String parent = optionalText(metadata, "inheritsFrom");
            if (parent != null) {
                validateVersionId(parent);
            }
            current = parent == null ? null
                    : versionsRoot.resolve(parent).resolve(parent + ".json").normalize();
            if (seen.size() > 16) {
                throw new IOException("Minecraft metadata inheritance chain is too deep.");
            }
        }
        return chain;
    }

    private List<String> buildClasspath(Iterable<JsonNode> chain, Path clientJar, Path librariesRoot,
            Path instanceLibrariesRoot)
            throws IOException {
        List<String> classpath = new ArrayList<>();
        classpath.add(clientJar.toString());
        Set<Path> seen = new HashSet<>();
        for (JsonNode metadata : chain) {
            JsonNode libraries = metadata.path("libraries");
            if (!libraries.isArray()) {
                continue;
            }
            for (JsonNode library : libraries) {
                if (!libraryAllowed(library)) {
                    continue;
                }
                JsonNode artifact = library.path("downloads").path("artifact");
                String relative = artifact.isObject()
                        ? optionalText(artifact, "path")
                        : legacyLibraryPath(optionalText(library, "name"));
                if (relative == null) {
                    continue;
                }
                Path path = resolveLibraryPath(librariesRoot, relative);
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    path = resolveLibraryPath(instanceLibrariesRoot, relative);
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Required Minecraft library is missing: " + relative);
                }
                if (seen.add(path)) {
                    classpath.add(path.toString());
                }
            }
        }
        return classpath;
    }

    private static Path resolveLibraryPath(Path root, String relative) throws IOException {
        if (relative.startsWith("/") || relative.startsWith("\\")
                || List.of(relative.replace('\\', '/').split("/")).contains("..")) {
            throw new IOException("Unsafe Minecraft library path.");
        }
        Path result = root.resolve(relative).normalize();
        if (!result.startsWith(root) || Files.isSymbolicLink(root)
                || Files.isSymbolicLink(result)) {
            throw new IOException("Minecraft library path escapes its cache.");
        }
        return result;
    }

    private static String legacyLibraryPath(String coordinate) throws IOException {
        if (coordinate == null) {
            return null;
        }
        String[] parts = coordinate.split(":", -1);
        if (parts.length < 3 || !parts[0].matches("[A-Za-z0-9_.-]+")
                || !parts[1].matches("[A-Za-z0-9_.-]+")) {
            throw new IOException("Minecraft metadata contains an invalid library coordinate.");
        }
        String version = parts[2];
        String extension = "jar";
        int separator = version.indexOf('@');
        if (separator >= 0) {
            extension = version.substring(separator + 1);
            version = version.substring(0, separator);
        }
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + version + "/"
                + parts[1] + "-" + version + classifier + "." + extension;
    }

    private void appendMetadataArguments(
            Iterable<JsonNode> chain, String field, Map<String, String> placeholders,
            List<String> destination) throws IOException {
        List<JsonNode> metadataList = new ArrayList<>();
        chain.forEach(metadataList::add);
        java.util.Collections.reverse(metadataList);
        for (JsonNode metadata : metadataList) {
            JsonNode arguments = metadata.path("arguments").path(field);
            if (arguments.isArray()) {
                for (JsonNode item : arguments) {
                    appendArgument(item, metadata, placeholders, destination);
                }
            }
        }
    }

    private void appendArgument(JsonNode item, JsonNode metadata,
            Map<String, String> placeholders, List<String> destination) throws IOException {
        if (item.isTextual()) {
            destination.add(replace(item.asText(), placeholders));
            return;
        }
        if (!item.isObject()) {
            throw new IOException("Minecraft metadata contains an invalid launch argument.");
        }
        if (!rulesAllow(item.path("rules"))) {
            return;
        }
        JsonNode value = item.get("value");
        if (value == null) {
            throw new IOException("Minecraft metadata launch argument has no value.");
        }
        if (value.isArray()) {
            for (JsonNode part : value) {
                if (!part.isTextual()) {
                    throw new IOException("Minecraft metadata contains an invalid launch argument.");
                }
                destination.add(replace(part.asText(), placeholders));
            }
        } else if (value.isTextual()) {
            destination.add(replace(value.asText(), placeholders));
        } else {
            throw new IOException("Minecraft metadata contains an invalid launch argument.");
        }
    }

    private static boolean rulesAllow(JsonNode rules) throws IOException {
        if (!rules.isArray() || rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (JsonNode rule : rules) {
            String action = requiredText(rule, "action");
            if (!action.equals("allow") && !action.equals("disallow")) {
                throw new IOException("Minecraft launch metadata contains an unknown rule.");
            }
            JsonNode os = rule.get("os");
            boolean matches = os == null || os.isNull()
                    || optionalText(os, "name") == null
                    || optionalText(os, "name").equals(platformName());
            String architecture = os == null ? null : optionalText(os, "arch");
            if (architecture != null && !Pattern.matches(architecture,
                    System.getProperty("os.arch", ""))) {
                matches = false;
            }
            JsonNode features = rule.get("features");
            if (features != null && features.isObject()) {
                var featureNames = features.fieldNames();
                while (featureNames.hasNext()) {
                    String feature = featureNames.next();
                    if (features.path(feature).asBoolean()) {
                        matches = false;
                    }
                }
            }
            String version = os == null ? null : optionalText(os, "version");
            if (version != null && !Pattern.matches(version, System.getProperty("os.version", ""))) {
                matches = false;
            }
            if (matches) {
                allowed = action.equals("allow");
            }
        }
        return allowed;
    }

    private Map<String, String> placeholders(MinecraftInstance instance, Iterable<JsonNode> chain,
            Path gameRoot,
            Path assetsRoot, String assetIndex, Path natives, List<String> classpath,
            AccountProfile account, AuthenticationSession session) throws IOException {
        String uuid;
        String accessToken;
        String userType;
        String username;
        if (account.type() == AccountType.OFFLINE) {
            username = account.username();
            uuid = offlineUuid(username).toString().replace("-", "");
            accessToken = "0";
            userType = "legacy";
        } else {
            username = session.profile().username();
            uuid = normalizeUuid(session.profile().minecraftUuid());
            accessToken = session.accessToken();
            userType = "msa";
        }
        String versionType = firstText(chain, "type", "release");
        Map<String, String> values = new HashMap<>();
        values.put("auth_player_name", username);
        values.put("auth_uuid", uuid);
        values.put("auth_access_token", accessToken);
        values.put("auth_session", "token:" + accessToken + ":" + uuid);
        values.put("user_type", userType);
        values.put("version_name", instance.getMinecraftVersion());
        values.put("version_type", versionType);
        values.put("game_directory", gameRoot.toString());
        values.put("assets_root", assetsRoot.toString());
        values.put("assets_index_name", assetIndex);
        values.put("natives_directory", natives.toString());
        values.put("library_directory", sharedCache.resolve("libraries").toString());
        values.put("classpath_separator", System.getProperty("path.separator"));
        values.put("classpath", String.join(System.getProperty("path.separator"), classpath));
        values.put("auth_xuid", session == null ? "" : session.xuid());
        values.put("clientid", System.getenv().getOrDefault("STRATA_MICROSOFT_CLIENT_ID", ""));
        values.put("user_properties", "{}");
        values.put("launcher_name", "strata-launcher");
        values.put("launcher_version", "0.1.0");
        values.put("resolution_width", "1280");
        values.put("resolution_height", "720");
        JsonNode logging = findField(chain, "logging");
        String loggingId = logging == null
                ? null : optionalText(logging.path("client").path("file"), "id");
        if (loggingId != null) {
            validateVersionId(loggingId);
            values.put("path", assetsRoot.resolve("log_configs").resolve(loggingId).toString());
        }
        return values;
    }

    private static String installedVersion(MinecraftInstance instance) throws IOException {
        String version = instance.getLoaderType().equalsIgnoreCase("VANILLA")
                ? instance.getMinecraftVersion()
                : instance.loaderProfileId() == null
                        ? instance.getLoaderVersion() : instance.loaderProfileId();
        validateVersionId(version);
        return version;
    }

    private static String replace(String value, Map<String, String> placeholders) throws IOException {
        String result = value;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            result = result.replace("${" + placeholder.getKey() + "}", placeholder.getValue());
        }
        if (result.contains("${")) {
            throw new IOException("Minecraft launch argument contains an unresolved placeholder.");
        }
        return result;
    }

    private static List<String> tokenize(String arguments) throws IOException {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < arguments.length(); index++) {
            char character = arguments.charAt(index);
            if (character == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(character) && !quoted) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (quoted) {
            throw new IOException("Minecraft launch arguments contain an unmatched quote.");
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static boolean containsOption(List<String> arguments, String prefix) {
        return arguments.stream().anyMatch(argument -> argument.equals(prefix)
                || argument.startsWith(prefix));
    }

    private static void replaceGameOption(List<String> arguments, String option, String value) {
        removeGameOption(arguments, option);
        arguments.addAll(List.of(option, value));
    }

    private static void removeGameOption(List<String> arguments, String option) {
        for (int index = 0; index < arguments.size();) {
            String argument = arguments.get(index);
            if (argument.equals(option)) {
                arguments.remove(index);
                if (index < arguments.size() && !arguments.get(index).startsWith("--")) {
                    arguments.remove(index);
                }
            } else if (argument.startsWith(option + "=")) {
                arguments.remove(index);
            } else {
                index++;
            }
        }
    }

    private static boolean libraryAllowed(JsonNode library) throws IOException {
        JsonNode rules = library.get("rules");
        if (rules == null) {
            return true;
        }
        return rulesAllow(rules);
    }

    private static String firstText(Iterable<JsonNode> nodes, String field, String fallback) {
        for (JsonNode node : nodes) {
            String value = optionalText(node, field);
            if (value != null) {
                return value;
            }
        }
        return fallback;
    }

    private static JsonNode findField(Iterable<JsonNode> nodes, String field) {
        for (JsonNode node : nodes) {
            if (node.has(field)) {
                return node.get(field);
            }
        }
        return null;
    }

    private static String optionalText(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new IOException("Minecraft metadata is missing " + field + ".");
        }
        return value;
    }

    private static String platformName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "osx";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return "unknown";
    }

    private static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String normalizeUuid(String uuid) throws IOException {
        String normalized = uuid.replace("-", "");
        if (!normalized.matches("[a-fA-F0-9]{32}")) {
            throw new IOException("Account profile has an invalid Minecraft UUID.");
        }
        return normalized;
    }

    private static void validateVersionId(String versionId) throws IOException {
        if (!VERSION_ID.matcher(versionId).matches()
                || !versionId.equals(versionId.trim())
                || versionId.equals(".") || versionId.equals("..")) {
            throw new IOException("Minecraft version identifier is invalid.");
        }
    }

    public interface ProcessListener {
        void output(String line);

        void exited(int code, String outputError);

        void failed(Exception exception);
    }
}
