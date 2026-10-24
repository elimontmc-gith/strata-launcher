package net.strata.launcher.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import net.strata.launcher.instance.InstanceContentCategory;
import net.strata.launcher.instance.InstanceContentFile;
import net.strata.launcher.instance.InstanceContentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class InstanceContentDialog {
    private static final Logger logger = LoggerFactory.getLogger(InstanceContentDialog.class);

    private final InstanceContentService contentService;
    private final Executor backgroundExecutor;
    private final Window owner;
    private final String instanceName;

    InstanceContentDialog(Path gameDirectory, Executor backgroundExecutor,
            Window owner, String instanceName) {
        this.contentService = new InstanceContentService(gameDirectory);
        this.backgroundExecutor = backgroundExecutor;
        this.owner = owner;
        this.instanceName = instanceName;
    }

    void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Instance content");
        dialog.setHeaderText("Manage content for " + instanceName);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        TabPane tabs = new TabPane();
        for (InstanceContentCategory category : InstanceContentCategory.values()) {
            tabs.getTabs().add(createTab(category));
        }
        dialog.getDialogPane().setContent(tabs);
        dialog.getDialogPane().setPrefSize(650, 430);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.showAndWait();
    }

    private Tab createTab(InstanceContentCategory category) {
        ListView<InstanceContentFile> files = new ListView<>();
        files.setPlaceholder(new Label("No " + category.displayName().toLowerCase() + " installed."));
        Label location = new Label(contentService.directory(category).toString());
        location.getStyleClass().add("path-label");
        location.setWrapText(true);
        Label status = new Label();
        status.getStyleClass().add("status-message");
        Button add = new Button("Add files");
        add.getStyleClass().add("primary-button");
        Button remove = new Button("Remove selected");
        remove.getStyleClass().add("danger-button");
        Button open = new Button("Open folder");
        open.getStyleClass().add("secondary-button");
        remove.disableProperty().bind(files.getSelectionModel().selectedItemProperty().isNull());
        add.setOnAction(event -> chooseFiles(category, files, status));
        remove.setOnAction(event -> confirmRemove(category, files, status));
        open.setOnAction(event -> openFolder(category, status));
        HBox actions = new HBox(10, add, remove, open);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(12, location, files, actions, status);
        content.setPadding(new Insets(12));
        VBox.setVgrow(files, Priority.ALWAYS);
        refresh(category, files, status);
        Tab tab = new Tab(category.displayName(), content);
        tab.setClosable(false);
        return tab;
    }

    private void chooseFiles(InstanceContentCategory category,
            ListView<InstanceContentFile> files, Label status) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add " + category.displayName().toLowerCase());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                category.displayName() + " (*" + category.extensionDescription() + ")",
                "*" + category.extensionDescription()));
        List<java.io.File> selected = chooser.showOpenMultipleDialog(owner);
        if (selected == null || selected.isEmpty()) {
            return;
        }
        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                int imported = 0;
                java.util.List<String> failures = new java.util.ArrayList<>();
                for (java.io.File file : selected) {
                    try {
                        contentService.add(category, file.toPath());
                        imported++;
                    } catch (IOException exception) {
                        failures.add(file.getName() + ": " + exception.getMessage());
                    }
                }
                String summary = "Imported " + imported + " of " + selected.size() + " file(s).";
                return failures.isEmpty() ? summary : summary + " " + String.join("; ", failures);
            }
        };
        status.setText("Importing content...");
        task.setOnSucceeded(event -> {
            refresh(category, files, status);
            status.setText(task.getValue());
        });
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            logger.error("Importing content failed for instance {}", instanceName, failure);
            refresh(category, files, status);
            status.setText("Importing content failed: " + failure.getMessage());
        });
        backgroundExecutor.execute(task);
    }

    private void confirmRemove(InstanceContentCategory category,
            ListView<InstanceContentFile> files, Label status) {
        InstanceContentFile selected = files.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Remove content");
        alert.setHeaderText("Remove " + selected.filename() + "?");
        alert.setContentText("This deletes the file from this instance only.");
        ButtonType remove = new ButtonType("Remove", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(remove, ButtonType.CANCEL);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.showAndWait().filter(remove::equals).ifPresent(ignored ->
                runAsync("Removing content", status,
                        () -> contentService.remove(category, selected.filename()),
                        () -> refresh(category, files, status)));
    }

    private void openFolder(InstanceContentCategory category, Label status) {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("Opening folders is not supported on this system.");
            }
            Desktop.getDesktop().open(contentService.directory(category).toFile());
        } catch (IOException | UnsupportedOperationException exception) {
            logger.error("Could not open {} content folder for {}", category, instanceName, exception);
            status.setText("Could not open folder: " + exception.getMessage());
        }
    }

    private void refresh(InstanceContentCategory category,
            ListView<InstanceContentFile> files, Label status) {
        try {
            files.getItems().setAll(contentService.list(category));
            status.setText("");
        } catch (IOException exception) {
            logger.error("Could not list {} content for {}", category, instanceName, exception);
            status.setText("Could not list content: " + exception.getMessage());
        }
    }

    private void runAsync(String message, Label status, IoOperation operation, Runnable onSuccess) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                operation.run();
                return null;
            }
        };
        status.setText(message + "...");
        task.setOnSucceeded(event -> onSuccess.run());
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            logger.error("{} failed for instance {}", message, instanceName, failure);
            status.setText(message + " failed: " + failure.getMessage());
        });
        backgroundExecutor.execute(task);
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws Exception;
    }
}
