package net.strata.launcher.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccountProfile(
        UUID id,
        String username,
        String minecraftUuid,
        AccountType type,
        Instant createdAt) {
    public AccountProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
        if (username.isBlank() || username.length() > 16) {
            throw new IllegalArgumentException("Minecraft profile names must contain 1-16 characters.");
        }
        if (type == AccountType.MICROSOFT && (minecraftUuid == null || minecraftUuid.isBlank())) {
            throw new IllegalArgumentException("Microsoft profiles require a Minecraft profile ID.");
        }
    }
}
