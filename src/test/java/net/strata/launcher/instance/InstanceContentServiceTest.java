package net.strata.launcher.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceContentServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void importsAndRemovesContentOnlyWithinItsInstanceCategory() throws Exception {
        Path firstGame = Files.createDirectories(temporaryDirectory.resolve("instance-one/game"));
        Path secondGame = Files.createDirectories(temporaryDirectory.resolve("instance-two/game"));
        Path source = temporaryDirectory.resolve("example.jar");
        Files.writeString(source, "mod-data");
        InstanceContentService first = new InstanceContentService(firstGame);
        InstanceContentService second = new InstanceContentService(secondGame);

        InstanceContentFile imported = first.add(InstanceContentCategory.MODS, source);

        assertEquals("example.jar", imported.filename());
        assertEquals(1, first.list(InstanceContentCategory.MODS).size());
        assertTrue(second.list(InstanceContentCategory.MODS).isEmpty());
        assertEquals("mod-data", Files.readString(firstGame.resolve("mods/example.jar")));
        assertTrue(Files.exists(source));

        first.remove(InstanceContentCategory.MODS, imported.filename());
        assertTrue(first.list(InstanceContentCategory.MODS).isEmpty());
        assertTrue(Files.exists(source));
    }

    @Test
    void rejectsUnsupportedExtensionsAndDuplicateFilenames() throws Exception {
        Path game = Files.createDirectories(temporaryDirectory.resolve("game"));
        InstanceContentService service = new InstanceContentService(game);
        Path zip = temporaryDirectory.resolve("pack.zip");
        Path jar = temporaryDirectory.resolve("mod.jar");
        Files.writeString(zip, "pack");
        Files.writeString(jar, "mod");

        assertThrows(IOException.class,
                () -> service.add(InstanceContentCategory.MODS, zip));
        service.add(InstanceContentCategory.MODS, jar);
        assertThrows(IOException.class,
                () -> service.add(InstanceContentCategory.MODS, jar));
    }

    @Test
    void refusesSymbolicLinkContentRootsAndRemovalPaths() throws Exception {
        Path game = Files.createDirectories(temporaryDirectory.resolve("game"));
        InstanceContentService service = new InstanceContentService(game);
        Path external = Files.createDirectories(temporaryDirectory.resolve("external"));
        try {
            Files.createSymbolicLink(game.resolve("mods"), external);
        } catch (UnsupportedOperationException | IOException exception) {
            return;
        }

        assertThrows(IOException.class, () -> service.list(InstanceContentCategory.MODS));
        assertThrows(IOException.class, () -> service.remove(
                InstanceContentCategory.RESOURCE_PACKS, "../outside.zip"));
        assertFalse(Files.exists(external.resolve("outside.zip")));
    }
}
