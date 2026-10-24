package net.strata.launcher.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class AccountStore {
    private final Path profileFile;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    public AccountStore(Path profileFile) {
        this.profileFile = profileFile.toAbsolutePath().normalize();
    }

    public List<AccountProfile> load() throws IOException {
        if (!Files.exists(profileFile, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (Files.isSymbolicLink(profileFile)
                || !Files.isRegularFile(profileFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Account profile store is not a regular file.");
        }
        AccountProfile[] accounts = mapper.readValue(profileFile.toFile(), AccountProfile[].class);
        return List.of(accounts);
    }

    public AccountProfile addOffline(String username) throws IOException {
        if (username == null || !username.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException(
                    "Offline usernames may contain 1-16 letters, numbers, or underscores.");
        }
        List<AccountProfile> accounts = new ArrayList<>(load());
        AccountProfile account = new AccountProfile(UUID.randomUUID(), username,
                null, AccountType.OFFLINE, java.time.Instant.now());
        accounts.add(account);
        save(accounts);
        return account;
    }

    public AccountProfile addMicrosoft(String username, String minecraftUuid) throws IOException {
        List<AccountProfile> accounts = new ArrayList<>(load());
        accounts.removeIf(account -> account.type() == AccountType.MICROSOFT
                && account.minecraftUuid().equalsIgnoreCase(minecraftUuid));
        AccountProfile account = new AccountProfile(UUID.randomUUID(), username,
                minecraftUuid, AccountType.MICROSOFT, java.time.Instant.now());
        accounts.add(account);
        save(accounts);
        return account;
    }

    public void remove(UUID accountId) throws IOException {
        List<AccountProfile> accounts = new ArrayList<>(load());
        if (!accounts.removeIf(account -> account.id().equals(accountId))) {
            throw new IOException("Account profile was not found.");
        }
        save(accounts);
    }

    private void save(List<AccountProfile> accounts) throws IOException {
        Path parent = profileFile.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "accounts-", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), accounts.stream()
                    .sorted(Comparator.comparing(AccountProfile::username,
                            String.CASE_INSENSITIVE_ORDER)).toList());
            try {
                Files.move(temporary, profileFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, profileFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
