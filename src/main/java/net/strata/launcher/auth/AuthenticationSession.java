package net.strata.launcher.auth;

import java.time.Instant;
import java.util.Objects;

public record AuthenticationSession(
        AccountProfile profile,
        String accessToken,
        Instant expiresAt,
        String xuid) {
    public AuthenticationSession {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (profile.type() != AccountType.MICROSOFT) {
            throw new IllegalArgumentException("Authentication sessions are for online accounts only.");
        }
    }

    public AuthenticationSession(AccountProfile profile, String accessToken, Instant expiresAt) {
        this(profile, accessToken, expiresAt, "");
    }

    public boolean isExpired() {
        return !expiresAt.isAfter(Instant.now());
    }

    @Override
    public String toString() {
        return "AuthenticationSession[profile=" + profile.username()
                + ", accessToken=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
