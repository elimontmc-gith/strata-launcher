package net.strata.launcher.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndLoadsOfflineAccountProfiles() throws Exception {
        AccountStore store = new AccountStore(temporaryDirectory.resolve("nested/accounts.json"));

        AccountProfile created = store.addOffline("LocalPlayer");
        AccountProfile loaded = store.load().getFirst();

        assertEquals(created.id(), loaded.id());
        assertEquals("LocalPlayer", loaded.username());
        assertEquals(AccountType.OFFLINE, loaded.type());
        assertEquals(created.createdAt(), loaded.createdAt());
    }

    @Test
    void savesAndReplacesMicrosoftProfileWithoutPersistingTokens() throws Exception {
        Path profileFile = temporaryDirectory.resolve("accounts.json");
        AccountStore store = new AccountStore(profileFile);
        String minecraftUuid = "0123456789abcdef0123456789abcdef";

        AccountProfile created = store.addMicrosoft("OnlinePlayer", minecraftUuid);
        String savedFile = java.nio.file.Files.readString(profileFile);
        AccountProfile refreshed = store.addMicrosoft("RenamedPlayer", minecraftUuid);

        assertEquals("RenamedPlayer", store.load().getFirst().username());
        assertEquals(1, store.load().size());
        assertEquals(AccountType.MICROSOFT, refreshed.type());
        org.junit.jupiter.api.Assertions.assertFalse(savedFile.contains("accessToken"));
        org.junit.jupiter.api.Assertions.assertFalse(savedFile.contains("token"));
    }

    @Test
    void rejectsInvalidOfflineUsernames() {
        AccountStore store = new AccountStore(temporaryDirectory.resolve("accounts.json"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.addOffline("name with spaces"));
    }
}
