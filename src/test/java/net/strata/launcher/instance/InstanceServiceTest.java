package net.strata.launcher.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.strata.launcher.domain.InstanceConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsRenamesFavoritesListsAndDeletesAnIsolatedInstance() throws Exception {
        InstanceService service = new InstanceService(temporaryDirectory.resolve("instances"));

        var created = service.createInstance("  Survival  ");

        assertEquals("Survival", created.getName());
        assertNull(created.getMinecraftVersion());
        assertTrue(Files.isDirectory(service.getGameDirectory(created.getId()).resolve("mods")));
        assertEquals(1, service.listInstances().size());

        var renamed = service.renameInstance(created.getId(), "Builder");
        assertEquals("Builder", renamed.getName());
        assertTrue(service.setFavorite(created.getId(), true).isFavorite());
        assertEquals("Builder", service.listInstances().getFirst().getName());

        service.deleteInstance(created.getId());

        assertTrue(service.listInstances().isEmpty());
        assertFalse(Files.exists(service.getInstanceDirectory(created.getId())));
    }

    @Test
    void duplicationCopiesFilesWithoutSharingMutableContent() throws Exception {
        InstanceService service = new InstanceService(temporaryDirectory.resolve("instances"));
        var original = service.createInstance("Original");
        Path originalWorld = service.getGameDirectory(original.getId())
                .resolve("saves/world/level.dat");
        Files.createDirectories(originalWorld.getParent());
        Files.writeString(originalWorld, "original");

        var duplicate = service.duplicateInstance(original.getId(), "Copy", true);
        Path duplicateWorld = service.getGameDirectory(duplicate.getId())
                .resolve("saves/world/level.dat");

        assertFalse(original.getId().equals(duplicate.getId()));
        assertEquals("original", Files.readString(duplicateWorld));
        Files.writeString(duplicateWorld, "copy");
        assertEquals("original", Files.readString(originalWorld));
    }

    @Test
    void refusesDeletionOfUnregisteredDirectoriesAndInvalidNames() throws Exception {
        Path instances = temporaryDirectory.resolve("instances");
        InstanceService service = new InstanceService(instances);
        UUID unrelatedId = UUID.randomUUID();
        Files.createDirectories(instances);
        Path unrelated = Files.createDirectory(instances.resolve(unrelatedId.toString()));

        assertThrows(IOException.class, () -> service.deleteInstance(unrelatedId));
        assertTrue(Files.isDirectory(unrelated));
        assertThrows(IllegalArgumentException.class, () -> service.createInstance(" \n "));
    }

    @Test
    void refusesAnInstanceDescriptorWhoseIdDoesNotMatchItsDirectory() throws Exception {
        InstanceService service = new InstanceService(temporaryDirectory.resolve("instances"));
        var instance = service.createInstance("Safe");
        Path directory = service.getInstanceDirectory(instance.getId());
        Path descriptor = directory.resolve("instance.json");
        String content = Files.readString(descriptor);
        Files.writeString(descriptor, content.replace(instance.getId().toString(),
                UUID.randomUUID().toString()));

        assertThrows(IOException.class, service::listInstances);
        assertThrows(IOException.class, () -> service.deleteInstance(instance.getId()));
        assertTrue(Files.isDirectory(directory));
    }

    @Test
    void savesLoaderProfileAndResetsToVanillaWhenMinecraftVersionChanges() throws Exception {
        InstanceService service = new InstanceService(temporaryDirectory.resolve("instances"));
        var instance = service.createInstance("Modded");
        service.setMinecraftVersion(instance.getId(), "1.20.1");
        var modded = service.setLoader(instance.getId(), "FABRIC", "0.16.0",
                "fabric-loader-0.16.0-1.20.1");

        assertEquals("FABRIC", modded.getLoaderType());
        assertEquals("fabric-loader-0.16.0-1.20.1", modded.loaderProfileId());
        assertEquals(modded.loaderProfileId(), service.listInstances().getFirst().loaderProfileId());

        var changedVersion = service.setMinecraftVersion(instance.getId(), "1.21.4");
        assertEquals("VANILLA", changedVersion.getLoaderType());
        assertNull(changedVersion.getLoaderVersion());
        assertNull(changedVersion.loaderProfileId());
    }

    @Test
    void persistsInstanceConfigurationAndCopiesItWhenDuplicating() throws Exception {
        InstanceService service = new InstanceService(temporaryDirectory.resolve("instances"));
        var instance = service.createInstance("Configured");
        InstanceConfiguration configuration =
                new InstanceConfiguration(6144, 1600, 900, true, null);

        service.setConfiguration(instance.getId(), configuration);
        var reloaded = service.listInstances().getFirst();
        assertEquals(configuration, reloaded.getConfiguration());

        var duplicate = service.duplicateInstance(instance.getId(), "Copy", false);
        assertEquals(configuration, duplicate.getConfiguration());
        assertNotNull(duplicate.getConfiguration());
    }

    @Test
    void validatesInstanceConfigurationBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new InstanceConfiguration(256, 1280, 720, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> new InstanceConfiguration(4096, 320, 720, false, null));
    }
}
