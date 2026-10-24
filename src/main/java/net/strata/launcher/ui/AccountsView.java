package net.strata.launcher.ui;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import net.strata.launcher.auth.AccountManager;
import net.strata.launcher.auth.AccountProfile;
import net.strata.launcher.auth.AccountType;
import net.strata.launcher.auth.MicrosoftDeviceCodeAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AccountsView extends VBox {
    private static final Logger logger = LoggerFactory.getLogger(AccountsView.class);
    private final AccountManager accountManager;
    private final Executor backgroundExecutor;
    private final ListView<AccountProfile> accountList = new ListView<>();
    private final Label status = new Label();

    public AccountsView(AccountManager accountManager, Executor backgroundExecutor) {
        this.accountManager = accountManager;
        this.backgroundExecutor = backgroundExecutor;
        setSpacing(18);
        getStyleClass().add("settings-panel");
        setPadding(new Insets(26));
        Label heading = new Label("Accounts");
        heading.getStyleClass().add("section-title");
        Label description = new Label("Choose an account to use when launching Minecraft.");
        description.getStyleClass().add("body-copy");
        Label microsoftSetup = new Label("Microsoft online sign-in requires a device-code-enabled "
                + "public-client app ID in STRATA_MICROSOFT_CLIENT_ID.");
        microsoftSetup.getStyleClass().add("body-copy");
        microsoftSetup.setWrapText(true);
        Button offlineButton = new Button("Add offline account");
        offlineButton.getStyleClass().add("secondary-button");
        offlineButton.setOnAction(event -> addOfflineAccount());
        Button microsoftButton = new Button("Sign in with Microsoft");
        microsoftButton.getStyleClass().add("primary-button");
        microsoftButton.setOnAction(event -> beginMicrosoftSignIn());
        Button selectButton = new Button("Use selected account");
        selectButton.getStyleClass().add("secondary-button");
        selectButton.setOnAction(event -> selectAccount());
        Button signOutButton = new Button("Sign out");
        signOutButton.getStyleClass().add("secondary-button");
        signOutButton.setOnAction(event -> {
            accountManager.signOut();
            status.setText("Microsoft credentials cleared from memory. Sign in again to play online.");
        });
        Button removeButton = new Button("Remove account");
        removeButton.getStyleClass().add("danger-button");
        removeButton.setOnAction(event -> removeAccount());
        accountList.setCellFactory(ignored -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(AccountProfile account, boolean empty) {
                super.updateItem(account, empty);
                if (empty || account == null) {
                    setText(null);
                } else {
                    setText(account.username() + "  ·  "
                            + (account.type() == AccountType.MICROSOFT
                                    ? "Microsoft" : "Offline")
                            + (accountManager.selected() != null
                                    && account.id().equals(accountManager.selected().id())
                                    ? "  ·  Selected" : ""));
                }
            }
        });
        accountList.setPrefHeight(270);
        status.getStyleClass().add("status-message");
        HBox accountActions = new HBox(10, selectButton, removeButton, signOutButton);
        accountActions.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(heading, description, microsoftSetup, accountList,
                new HBox(10, offlineButton, microsoftButton), accountActions, status);
        refreshAccounts();
    }

    private void addOfflineAccount() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Add offline account");
        dialog.setHeaderText("Create a local player profile");
        dialog.setContentText("Player name:");
        dialog.showAndWait().ifPresent(username -> runAsync(
                () -> accountManager.addOffline(username), "Adding offline account"));
    }

    private void beginMicrosoftSignIn() {
        Task<MicrosoftDeviceCodeAuthenticator.DeviceCode> task = new Task<>() {
            @Override
            protected MicrosoftDeviceCodeAuthenticator.DeviceCode call() throws Exception {
                String clientId = System.getenv("STRATA_MICROSOFT_CLIENT_ID");
                if (clientId == null || clientId.isBlank()) {
                    throw new IOException("Online sign-in is not configured. Set "
                            + "STRATA_MICROSOFT_CLIENT_ID to a Microsoft Entra public-client "
                            + "application ID.");
                }
                return new MicrosoftDeviceCodeAuthenticator(clientId).begin();
            }
        };
        task.setOnSucceeded(event -> showDeviceCode(task.getValue()));
        task.setOnFailed(event -> showError("Could not start Microsoft sign-in", task.getException()));
        status.setText("Connecting to Microsoft...");
        backgroundExecutor.execute(task);
    }

    private void showDeviceCode(MicrosoftDeviceCodeAuthenticator.DeviceCode code) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Microsoft sign-in");
        alert.setHeaderText("Enter code " + code.userCode() + " at " + code.verificationUri());
        alert.setContentText(code.message()
                + "\n\nKeep this window open while sign-in completes.");
        alert.show();
        Task<AccountProfile> task = new Task<>() {
            @Override
            protected AccountProfile call() throws Exception {
                return accountManager.authenticate(code, () -> {
                    if (isCancelled() || Thread.currentThread().isInterrupted()) {
                        throw new java.util.concurrent.CancellationException(
                                "Microsoft sign-in was cancelled.");
                    }
                    updateMessage("Waiting for Microsoft sign-in...");
                }).profile();
            }
        };
        task.messageProperty().addListener(
                (observable, oldValue, message) -> status.setText(message));
        task.setOnSucceeded(event -> {
            alert.close();
            status.setText("Signed in as " + task.getValue().username() + ".");
            refreshAccounts();
        });
        task.setOnFailed(event -> {
            alert.close();
            showError("Microsoft sign-in failed", task.getException());
        });
        task.setOnCancelled(event -> alert.close());
        backgroundExecutor.execute(task);
    }

    private void selectAccount() {
        AccountProfile selected = accountList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status.setText("Select an account first.");
            return;
        }
        runAsync(() -> {
            accountManager.select(selected.id());
            return selected;
        }, "Selecting account");
    }

    private void removeAccount() {
        AccountProfile selected = accountList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status.setText("Select an account first.");
            return;
        }
        runAsync(() -> {
            accountManager.remove(selected.id());
            return null;
        }, "Removing account");
    }

    private void refreshAccounts() {
        runAsync(accountManager::accounts, "Loading accounts", accounts -> {
            accountList.getItems().setAll(accounts);
            accountList.refresh();
        });
    }

    private <T> void runAsync(CheckedSupplier<T> operation, String operationName) {
        runAsync(operation, operationName, ignored -> refreshAccounts());
    }

    private <T> void runAsync(
            CheckedSupplier<T> operation, String operationName, Consumer<T> success) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return operation.get();
            }
        };
        status.setText(operationName + "...");
        task.setOnSucceeded(event -> {
            status.setText("");
            success.accept(task.getValue());
        });
        task.setOnFailed(event -> showError(operationName + " failed", task.getException()));
        backgroundExecutor.execute(task);
    }

    private void showError(String title, Throwable failure) {
        logger.error("{} failed", title, failure);
        status.setText(title + ": " + failure.getMessage());
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
