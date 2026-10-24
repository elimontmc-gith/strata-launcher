package net.strata.launcher.auth;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public final class AccountManager {
    private final AccountStore store;
    private AccountProfile selected;
    private AuthenticationSession session;

    public AccountManager(Path accountFile) {
        store = new AccountStore(accountFile);
    }

    public synchronized List<AccountProfile> accounts() throws IOException {
        return store.load();
    }

    public synchronized AccountProfile selected() {
        return selected;
    }

    public synchronized AuthenticationSession sessionFor(AccountProfile account) {
        return session != null && selected != null && account != null
                && selected.id().equals(account.id()) && !session.isExpired() ? session : null;
    }

    public synchronized AccountProfile addOffline(String username) throws IOException {
        selected = store.addOffline(username);
        session = null;
        return selected;
    }

    public synchronized AuthenticationSession authenticate(
            MicrosoftDeviceCodeAuthenticator.DeviceCode deviceCode,
            MicrosoftDeviceCodeAuthenticator.PollListener listener)
            throws IOException, InterruptedException {
        String clientId = System.getenv("STRATA_MICROSOFT_CLIENT_ID");
        if (clientId == null || clientId.isBlank()) {
            throw new IOException("Online sign-in is not configured. Set STRATA_MICROSOFT_CLIENT_ID "
                    + "to a Microsoft Entra public-client application ID.");
        }
        AuthenticationSession authenticated =
                new MicrosoftDeviceCodeAuthenticator(clientId).authenticate(deviceCode, store, listener);
        selected = authenticated.profile();
        session = authenticated;
        return authenticated;
    }

    public synchronized void select(UUID accountId) throws IOException {
        selected = store.load().stream()
                .filter(account -> account.id().equals(accountId))
                .findFirst()
                .orElseThrow(() -> new IOException("Account profile was not found."));
        if (session != null && !session.profile().id().equals(selected.id())) {
            session = null;
        }
    }

    public synchronized void remove(UUID accountId) throws IOException {
        store.remove(accountId);
        if (selected != null && selected.id().equals(accountId)) {
            selected = null;
            session = null;
        }
    }

    public synchronized void signOut() {
        session = null;
    }

    public AccountStore store() {
        return store;
    }
}
