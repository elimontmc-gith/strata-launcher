package net.strata.launcher.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.strata.launcher.auth.AccountProfile;
import net.strata.launcher.auth.AccountType;
import net.strata.launcher.domain.MinecraftInstance;
import net.strata.launcher.domain.InstanceConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchServiceTest {
    private static final String VERSION = "1.20.1";

    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsVanillaLaunchCommandFromVersionMetadataWithoutPersistingCredentials()
            throws Exception {
        Path cache = temporaryDirectory.resolve("cache");
        Path game = temporaryDirectory.resolve("game");
        installBaseFiles(cache, game);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        JavaRuntime runtime = new JavaRuntime(java, 21, "21");
        AccountProfile account = offlineAccount("Player_One");
        LaunchService service = new LaunchService(cache, new JavaRuntimeService());

        LaunchArguments result = service.buildCommand(
                instance("VANILLA", null, null), game, runtime, account, null,
                new InstanceConfiguration(6144, 1600, 900, true, null), List.of());

        assertEquals(game.toAbsolutePath().normalize(), result.workingDirectory());
        assertEquals(java.toAbsolutePath().normalize().toString(), result.command().getFirst());
        assertTrue(result.command().contains("net.minecraft.client.main.Main"));
        assertTrue(result.command().contains("Player_One"));
        assertTrue(result.command().contains("1.20.1"));
        assertTrue(result.command().stream().anyMatch(argument -> argument.startsWith("--accessToken")));
        assertTrue(result.command().stream().anyMatch(
                argument -> argument.startsWith("-Dlog4j.configurationFile=")));
        assertTrue(result.command().contains("-Xmx6144M"));
        assertTrue(result.command().containsAll(List.of(
                "--width", "1600", "--height", "900", "--fullscreen")));
        assertFalse(Files.exists(cache.resolve("accounts.json")));
    }

    @Test
    void resolvesInheritedLoaderProfileAndUsesParentClientJar() throws Exception {
        Path cache = temporaryDirectory.resolve("cache");
        Path game = temporaryDirectory.resolve("game");
        installBaseFiles(cache, game);
        Path profile = game.resolve("versions/fabric-loader-0.16.0-1.20.1");
        Files.createDirectories(profile);
        Files.writeString(profile.resolve("fabric-loader-0.16.0-1.20.1.json"),
                """
                {
                  "id": "fabric-loader-0.16.0-1.20.1",
                  "inheritsFrom": "1.20.1",
                  "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                  "arguments": {
                    "game": [
                      {"value": "--username"},
                      {"value": "${auth_player_name}"}
                    ]
                  },
                  "libraries": []
                }
                """);
        MinecraftInstance instance = instance("FABRIC", "0.16.0", "fabric-loader-0.16.0-1.20.1");
        LaunchService service = new LaunchService(cache, new JavaRuntimeService());

        LaunchArguments result = service.buildCommand(instance, game,
                new JavaRuntime(Path.of(System.getProperty("java.home"), "bin", "java"),
                        21, "21"), offlineAccount("Builder"), null, List.of());

        assertTrue(result.command().contains(
                "net.fabricmc.loader.impl.launch.knot.KnotClient"));
        assertTrue(result.command().contains("Builder"));
        assertTrue(result.command().contains(
                game.resolve("versions/1.20.1/1.20.1.jar").toString()));
    }

    @Test
    void rejectsOnlineAccountWhenThereIsNoLiveAuthenticationSession() throws Exception {
        Path cache = temporaryDirectory.resolve("cache");
        Path game = temporaryDirectory.resolve("game");
        installBaseFiles(cache, game);
        AccountProfile account = new AccountProfile(UUID.randomUUID(), "OnlinePlayer",
                "0123456789abcdef0123456789abcdef", AccountType.MICROSOFT, Instant.now());
        LaunchService service = new LaunchService(cache, new JavaRuntimeService());

        assertThrows(IOException.class, () -> service.buildCommand(
                instance("VANILLA", null, null), game,
                new JavaRuntime(Path.of(System.getProperty("java.home"), "bin", "java"),
                        21, "21"), account, null, List.of()));
    }

    @Test
    void launchesAndTracksASeparateJavaProcess() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        LaunchService service = new LaunchService(temporaryDirectory, new JavaRuntimeService());
        CountDownLatch exited = new CountDownLatch(1);
        AtomicInteger exitCode = new AtomicInteger(-1);

        service.launch(UUID.randomUUID(),
                new LaunchArguments(List.of(java.toString(), "-version"), temporaryDirectory),
                new LaunchService.ProcessListener() {
                    @Override
                    public void output(String line) {
                    }

                    @Override
                    public void exited(int code, String outputError) {
                        exitCode.set(code);
                        exited.countDown();
                    }

                    @Override
                    public void failed(Exception exception) {
                        exited.countDown();
                    }
                });

        assertTrue(exited.await(10, TimeUnit.SECONDS));
        assertEquals(0, exitCode.get());
    }

    @Test
    void launchArgumentsRedactAuthenticationValuesWhenRendered() {
        LaunchArguments arguments = new LaunchArguments(List.of("java", "--accessToken",
                "sensitive-token", "token:sensitive-token:uuid"), temporaryDirectory);

        assertFalse(arguments.toString().contains("sensitive-token"));
    }

    private void installBaseFiles(Path cache, Path game) throws IOException {
        Path version = game.resolve("versions").resolve(VERSION);
        Files.createDirectories(version);
        Files.createDirectories(game.resolve("natives").resolve(VERSION));
        Files.createDirectories(cache.resolve("libraries"));
        Path loggingDirectory = Files.createDirectories(cache.resolve("assets/log_configs"));
        Files.writeString(loggingDirectory.resolve("client-test.xml"), "<Configuration/>");
        Files.write(version.resolve(VERSION + ".jar"), new byte[] {1});
        Files.writeString(version.resolve(VERSION + ".json"),
                """
                {
                  "id": "1.20.1",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "javaVersion": {"majorVersion": 17},
                  "assets": "5",
                  "logging": {
                    "client": {
                      "argument": "-Dlog4j.configurationFile=${path}",
                      "file": {"id": "client-test.xml"}
                    }
                  },
                  "libraries": [],
                  "arguments": {
                    "game": [
                      {"value": "--username"},
                      {"value": "${auth_player_name}"},
                      {"value": "--version"},
                      {"value": "${version_name}"},
                      {"value": "--accessToken"},
                      {"value": "${auth_access_token}"}
                    ]
                  }
                }
                """);
    }

    private static MinecraftInstance instance(String loader, String loaderVersion, String profileId) {
        MinecraftInstance instance = new MinecraftInstance(UUID.randomUUID(), "Test", VERSION,
                loader, loaderVersion, false, Instant.now());
        if (profileId != null) {
            instance.getAdditionalProperties().put("loaderProfileId",
                    com.fasterxml.jackson.databind.node.TextNode.valueOf(profileId));
        }
        return instance;
    }

    private static AccountProfile offlineAccount(String username) {
        return new AccountProfile(UUID.randomUUID(), username, null,
                AccountType.OFFLINE, Instant.now());
    }
}
