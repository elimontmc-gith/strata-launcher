package net.strata.launcher.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import net.strata.launcher.config.AppConfig;
import net.strata.launcher.config.AppPaths;
import net.strata.launcher.auth.AccountManager;
import net.strata.launcher.auth.AccountProfile;
import net.strata.launcher.domain.InstanceConfiguration;
import net.strata.launcher.domain.MinecraftInstance;
import net.strata.launcher.instance.InstanceService;
import net.strata.launcher.launch.JavaRuntime;
import net.strata.launcher.launch.JavaRuntimeService;
import net.strata.launcher.launch.LaunchArguments;
import net.strata.launcher.launch.LaunchService;
import net.strata.launcher.loader.LoaderVersion;
import net.strata.launcher.loader.ModLoaderService;
import net.strata.launcher.minecraft.MinecraftInstallationService;
import net.strata.launcher.minecraft.MinecraftVersion;
import net.strata.launcher.minecraft.VersionCatalog;
import net.strata.launcher.minecraft.VersionManifestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InstanceLibraryView extends BorderPane {
    private static final Logger logger = LoggerFactory.getLogger(InstanceLibraryView.class);

    private final InstanceService instanceService;
    private final VersionManifestService versionManifestService;
    private final MinecraftInstallationService installationService;
    private final Executor backgroundExecutor;
    private final AccountManager accountManager;
    private final JavaRuntimeService javaRuntimeService;
    private final LaunchService launchService;
    private final ModLoaderService modLoaderService = new ModLoaderService();
    private final TableView<MinecraftInstance> table = new TableView<>();
    private final Label status = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final VBox details = new VBox(14);
    private final TextField searchField = new TextField();
    private List<MinecraftInstance> allInstances = List.of();
    private VersionCatalog versionCatalog;

    public InstanceLibraryView(AppConfig config, Executor backgroundExecutor,
            AccountManager accountManager, JavaRuntimeService javaRuntimeService,
            LaunchService launchService) {
        this.instanceService = new InstanceService(Path.of(config.getInstancesDirectory()));
        this.versionManifestService = new VersionManifestService(
                AppPaths.dataDirectory().resolve("cache/version_manifest_v2.json"));
        this.installationService = new MinecraftInstallationService(
                AppPaths.dataDirectory().resolve("cache/minecraft"), versionManifestService);
        this.backgroundExecutor = backgroundExecutor;
        this.accountManager = accountManager;
        this.javaRuntimeService = javaRuntimeService;
        this.launchService = launchService;
        getStyleClass().add("instances-page");
        setPadding(new Insets(0));
        setTop(createToolbar());
        setCenter(createLibrary());
        refreshInstances();
        refreshVersionCatalog(false);
    }

    private HBox createToolbar() {
        Label title = new Label("Your instances");
        title.getStyleClass().add("section-title");
        searchField.setPromptText("Search instances");
        searchField.getStyleClass().add("text-field");
        searchField.textProperty().addListener((observable, oldValue, newValue) ->
                applyFilter(newValue));
        Button createButton = new Button("Create instance");
        createButton.getStyleClass().add("primary-button");
        createButton.setOnAction(event -> showCreateDialog());
        Button refreshButton = new Button("Refresh versions");
        refreshButton.getStyleClass().add("secondary-button");
        refreshButton.setOnAction(event -> refreshVersionCatalog(true));
        HBox toolbar = new HBox(14, title, searchField, refreshButton, createButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0, 0, 20, 0));
        HBox.setHgrow(searchField, Priority.ALWAYS);
        return toolbar;
    }

    private BorderPane createLibrary() {
        configureTable();
        details.getStyleClass().add("instance-details");
        details.setPadding(new Insets(22));
        details.setPrefWidth(295);
        Label emptyDetails = new Label("Select an instance to see its details and actions.");
        emptyDetails.getStyleClass().add("body-copy");
        emptyDetails.setWrapText(true);
        details.getChildren().setAll(emptyDetails);

        ScrollPane detailScrollPane = new ScrollPane(details);
        detailScrollPane.setFitToWidth(true);
        detailScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailScrollPane.getStyleClass().add("instance-details-scroll");
        detailScrollPane.setPrefWidth(310);
        detailScrollPane.setMinWidth(280);
        HBox content = new HBox(18, table, detailScrollPane);
        HBox.setHgrow(table, Priority.ALWAYS);
        BorderPane library = new BorderPane(content);
        HBox progressArea = new HBox(12, status, progressBar);
        progressArea.setAlignment(Pos.CENTER_LEFT);
        library.setBottom(progressArea);
        status.getStyleClass().add("status-message");
        progressBar.setPrefWidth(180);
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        BorderPane.setMargin(progressArea, new Insets(12, 0, 0, 0));
        return library;
    }

    private void configureTable() {
        TableColumn<MinecraftInstance, String> nameColumn = new TableColumn<>("Name");
        nameColumn.setCellValueFactory(data ->
                new javafx.beans.property.SimpleStringProperty(data.getValue().getName()));
        nameColumn.setPrefWidth(220);

        TableColumn<MinecraftInstance, String> versionColumn = new TableColumn<>("Minecraft");
        versionColumn.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().getMinecraftVersion() == null
                        ? "Not selected" : data.getValue().getMinecraftVersion()));
        versionColumn.setPrefWidth(130);

        TableColumn<MinecraftInstance, String> loaderColumn = new TableColumn<>("Loader");
        loaderColumn.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().getLoaderType()));
        loaderColumn.setPrefWidth(100);

        TableColumn<MinecraftInstance, String> favoriteColumn = new TableColumn<>("Favorite");
        favoriteColumn.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().isFavorite() ? "Yes" : ""));
        favoriteColumn.setPrefWidth(85);

        table.getColumns().setAll(List.of(nameColumn, versionColumn, loaderColumn, favoriteColumn));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.getStyleClass().add("instance-table");
        table.setPlaceholder(new Label("No instances yet. Create one to get started."));
        table.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> showDetails(newValue));
    }

    private void showDetails(MinecraftInstance instance) {
        if (instance == null) {
            details.getChildren().setAll(new Label("Select an instance to see its details and actions."));
            return;
        }
        Label name = new Label(instance.getName());
        name.getStyleClass().add("section-title");
        Label version = new Label("Minecraft " + (instance.getMinecraftVersion() == null
                ? "version not selected" : instance.getMinecraftVersion()));
        version.getStyleClass().add("body-copy");
        String loaderName = instance.getLoaderType();
        if (instance.getLoaderVersion() != null) {
            loaderName += " " + displayLoaderVersion(instance);
        }
        Label loader = new Label("Loader: " + loaderName);
        loader.getStyleClass().add("body-copy");
        Label path = new Label(instanceService.getInstanceDirectory(instance.getId()).toString());
        path.getStyleClass().add("path-label");
        path.setWrapText(true);
        boolean baseInstalled = instance.getMinecraftVersion() != null
                && installationService.isInstalled(instanceService.getGameDirectory(instance.getId()),
                        instance.getMinecraftVersion());
        boolean installed = baseInstalled && loaderInstalled(instance);
        Label note = new Label(instance.getMinecraftVersion() == null
                ? "This instance has an isolated folder. Choose a Minecraft version to prepare it."
                : !baseInstalled ? "Minecraft files have not been installed."
                        : installed ? "Minecraft and loader files are installed."
                                : "Install the selected mod loader before launching.");
        note.getStyleClass().add("body-copy");
        note.setWrapText(true);

        Button versionButton = new Button("Choose Minecraft version");
        versionButton.getStyleClass().add("secondary-button");
        Button installButton = new Button(baseInstalled ? "Reinstall Minecraft" : "Install Minecraft");
        installButton.getStyleClass().add("primary-button");
        installButton.setDisable(instance.getMinecraftVersion() == null);
        Button loaderButton = new Button("Choose mod loader");
        loaderButton.getStyleClass().add("secondary-button");
        loaderButton.setDisable(instance.getMinecraftVersion() == null);
        loaderButton.setOnAction(event -> chooseLoader(instance));
        Button configurationButton = new Button("Instance settings");
        configurationButton.getStyleClass().add("secondary-button");
        configurationButton.setOnAction(event -> editConfiguration(instance));
        boolean running = launchService.isRunning(instance.getId());
        Button contentButton = new Button("Manage content");
        contentButton.getStyleClass().add("secondary-button");
        contentButton.setDisable(running);
        contentButton.setOnAction(event -> new InstanceContentDialog(
                instanceService.getGameDirectory(instance.getId()), backgroundExecutor,
                getScene() == null ? null : getScene().getWindow(), instance.getName()).show());
        Button launchButton = new Button(running ? "Stop Minecraft" : "Launch Minecraft");
        launchButton.getStyleClass().add("primary-button");
        launchButton.setDisable(!running && (!installed || accountManager.selected() == null));
        launchButton.setOnAction(event -> {
            if (launchService.isRunning(instance.getId())) {
                stopGame(instance);
            } else {
                launchGame(instance);
            }
        });
        Button favoriteButton = new Button(instance.isFavorite()
                ? "Remove favorite" : "Add to favorites");
        Button renameButton = new Button("Rename");
        Button duplicateButton = new Button("Duplicate");
        Button openButton = new Button("Open folder");
        Button deleteButton = new Button("Delete instance");
        deleteButton.getStyleClass().add("danger-button");
        favoriteButton.getStyleClass().add("secondary-button");
        versionButton.setOnAction(event -> chooseVersion(instance));
        installButton.setOnAction(event -> installVersion(instance));
        renameButton.getStyleClass().add("secondary-button");
        duplicateButton.getStyleClass().add("secondary-button");
        openButton.getStyleClass().add("secondary-button");

        favoriteButton.setOnAction(event -> runOperation(
                () -> instanceService.setFavorite(instance.getId(), !instance.isFavorite()),
                ignored -> refreshInstances(instance.getId()), "Updating favorite"));
        renameButton.setOnAction(event -> showRenameDialog(instance));
        duplicateButton.setOnAction(event -> showDuplicateDialog(instance));
        openButton.setOnAction(event -> openFolder(instance));
        deleteButton.setOnAction(event -> confirmDelete(instance));

        details.getChildren().setAll(name, version, loader, path, note,
                versionButton, installButton, loaderButton, configurationButton, contentButton,
                launchButton, favoriteButton, renameButton, duplicateButton, openButton, deleteButton);
    }

    private void editConfiguration(MinecraftInstance instance) {
        InstanceConfiguration configuration;
        try {
            configuration = instance.getConfiguration();
        } catch (IllegalArgumentException exception) {
            status.setText("Instance settings are invalid: " + exception.getMessage());
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Instance settings");
        dialog.setHeaderText("Configure " + instance.getName());
        ButtonType saveType = new ButtonType("Save settings", ButtonType.OK.getButtonData());
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        TextField memory = new TextField(Integer.toString(configuration.memoryMegabytes()));
        TextField width = new TextField(Integer.toString(configuration.windowWidth()));
        TextField height = new TextField(Integer.toString(configuration.windowHeight()));
        TextField javaExecutable = new TextField(configuration.javaExecutable() == null
                ? "" : configuration.javaExecutable());
        javaExecutable.setPromptText("Automatic (JAVA_HOME / PATH)");
        Button browseJava = new Button("Browse...");
        browseJava.setOnAction(event -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("Select Java executable");
            java.io.File selected = chooser.showOpenDialog(
                    getScene() == null ? null : getScene().getWindow());
            if (selected != null) {
                javaExecutable.setText(selected.toPath().toAbsolutePath().normalize().toString());
            }
        });
        HBox javaRow = new HBox(8, javaExecutable, browseJava);
        HBox.setHgrow(javaExecutable, Priority.ALWAYS);
        CheckBox fullscreen = new CheckBox("Start in fullscreen");
        fullscreen.setSelected(configuration.fullscreen());
        GridPane fields = new GridPane();
        fields.setHgap(12);
        fields.setVgap(14);
        fields.addRow(0, new Label("Maximum memory (MB):"), memory);
        fields.addRow(1, new Label("Window width:"), width);
        fields.addRow(2, new Label("Window height:"), height);
        fields.addRow(3, new Label("Java executable:"), javaRow);
        fields.add(fullscreen, 1, 4);
        dialog.getDialogPane().setContent(fields);
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.showAndWait().filter(saveType::equals).ifPresent(ignored -> {
            try {
                InstanceConfiguration updated = new InstanceConfiguration(
                        Integer.parseInt(memory.getText().trim()),
                        Integer.parseInt(width.getText().trim()),
                        Integer.parseInt(height.getText().trim()),
                        fullscreen.isSelected(),
                        javaExecutable.getText().trim().isEmpty()
                                ? null : javaExecutable.getText().trim());
                runOperation(() -> instanceService.setConfiguration(instance.getId(), updated),
                        saved -> refreshInstances(saved.getId()), "Saving instance settings");
            } catch (IllegalArgumentException exception) {
                status.setText("Could not save instance settings: " + exception.getMessage());
            }
        });
    }

    private void showCreateDialog() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Create instance");
        dialog.setHeaderText("Name your new instance");
        dialog.setContentText("Instance name:");
        dialog.showAndWait().ifPresent(name -> runOperation(
                () -> instanceService.createInstance(name),
                instance -> refreshInstances(instance.getId()), "Creating instance"));
    }

    private void showRenameDialog(MinecraftInstance instance) {
        TextInputDialog dialog = new TextInputDialog(instance.getName());
        dialog.setTitle("Rename instance");
        dialog.setHeaderText("Choose a new name");
        dialog.setContentText("Instance name:");
        dialog.showAndWait().ifPresent(name -> runOperation(
                () -> instanceService.renameInstance(instance.getId(), name),
                updated -> refreshInstances(updated.getId()), "Renaming instance"));
    }

    private void showDuplicateDialog(MinecraftInstance instance) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Duplicate instance");
        dialog.setHeaderText("Create an independent copy of " + instance.getName());
        ButtonType duplicateType = new ButtonType("Duplicate", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(duplicateType, ButtonType.CANCEL);

        TextField nameField = new TextField(instance.getName() + " Copy");
        CheckBox copyFiles = new CheckBox("Copy game files and content");
        copyFiles.setSelected(true);
        GridPane fields = new GridPane();
        fields.setHgap(12);
        fields.setVgap(14);
        fields.addRow(0, new Label("New name:"), nameField);
        fields.add(copyFiles, 1, 1);
        dialog.getDialogPane().setContent(fields);
        dialog.showAndWait().filter(duplicateType::equals).ifPresent(button -> runOperation(
                () -> instanceService.duplicateInstance(instance.getId(),
                        nameField.getText(), copyFiles.isSelected()),
                duplicate -> refreshInstances(duplicate.getId()), "Duplicating instance"));
    }

    private void confirmDelete(MinecraftInstance instance) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Delete instance");
        alert.setHeaderText("Delete " + instance.getName() + "?");
        alert.setContentText("This permanently removes this instance folder and its game data.");
        ButtonType deleteType = new ButtonType("Delete instance", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(deleteType, ButtonType.CANCEL);
        alert.showAndWait().filter(deleteType::equals).ifPresent(button -> runOperation(
                () -> {
                    instanceService.deleteInstance(instance.getId());
                    return null;
                },
                ignored -> refreshInstances(), "Deleting instance"));
    }

    private void openFolder(MinecraftInstance instance) {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("Opening folders is not supported on this system.");
            }
            Desktop.getDesktop().open(instanceService.getInstanceDirectory(instance.getId()).toFile());
            status.setText("");
        } catch (IOException | UnsupportedOperationException exception) {
            logger.error("Unable to open instance folder {}", instance.getId(), exception);
            status.setText("Could not open the instance folder: " + exception.getMessage());
        }
    }

    private void refreshInstances() {
        refreshInstances(null);
    }

    private void refreshInstances(java.util.UUID selectedId) {
        runOperation(instanceService::listInstances, instances -> {
            allInstances = instances;
            applyFilter(searchField.getText());
            if (selectedId != null) {
                instances.stream().filter(instance -> instance.getId().equals(selectedId))
                        .findFirst().ifPresent(table.getSelectionModel()::select);
            }
        }, "Loading instances");
    }

    private void applyFilter(String query) {
        String normalizedQuery = query == null
                ? "" : query.trim().toLowerCase(Locale.ROOT);
        table.getItems().setAll(allInstances.stream()
                .filter(instance -> instance.getName().toLowerCase(Locale.ROOT)
                        .contains(normalizedQuery))
                .toList());
    }

    private void refreshVersionCatalog(boolean forceRefresh) {
        runOperation(() -> versionManifestService.loadCatalog(forceRefresh), catalog -> {
            versionCatalog = catalog;
            if (catalog.stale()) {
                status.setText("Network unavailable; using cached versions from "
                        + catalog.lastUpdated() + ".");
            } else if (catalog.fromCache()) {
                status.setText("Using cached Minecraft versions, refreshed "
                        + catalog.lastUpdated() + ".");
            } else {
                status.setText("Minecraft version catalog refreshed.");
            }
        }, forceRefresh ? "Refreshing versions" : "Loading Minecraft versions");
    }

    private void chooseVersion(MinecraftInstance instance) {
        if (versionCatalog == null) {
            refreshVersionCatalog(false);
            return;
        }
        List<MinecraftVersion> versions = versionCatalog.versions();
        MinecraftVersion selected = versions.stream()
                .filter(version -> version.id().equals(instance.getMinecraftVersion()))
                .findFirst().orElse(null);
        javafx.scene.control.ChoiceDialog<MinecraftVersion> dialog =
                new javafx.scene.control.ChoiceDialog<>(selected, versions);
        dialog.setTitle("Minecraft version");
        dialog.setHeaderText("Select a version for " + instance.getName());
        dialog.setContentText("Available releases and snapshots:");
        dialog.showAndWait().ifPresent(version -> runOperation(
                () -> instanceService.setMinecraftVersion(instance.getId(), version.id()),
                updated -> refreshInstances(updated.getId()), "Saving Minecraft version"));
    }

    private void installVersion(MinecraftInstance instance) {
        if (instance.getMinecraftVersion() == null) {
            status.setText("Choose a Minecraft version first.");
            return;
        }
        installSelectedVersion(instance);
    }

    private void launchGame(MinecraftInstance instance) {
        AccountProfile account = accountManager.selected();
        if (account == null) {
            status.setText("Add and select an account in Accounts before launching.");
            return;
        }
        Task<List<JavaRuntime>> task = new Task<>() {
            @Override
            protected List<JavaRuntime> call() throws Exception {
                InstanceConfiguration configuration = instance.getConfiguration();
                if (configuration.javaExecutable() != null) {
                    return List.of(javaRuntimeService.inspect(
                            Path.of(configuration.javaExecutable())));
                }
                return javaRuntimeService.detectInstalledRuntimes();
            }
        };
        status.setText("Detecting Java runtimes...");
        task.setOnSucceeded(event -> {
            List<JavaRuntime> runtimes = task.getValue();
            if (runtimes.isEmpty()) {
                status.setText("No usable Java runtime was found. Install a compatible Java runtime.");
                return;
            }
            int required;
            try {
                required = javaRuntimeService.requiredMajorVersion(instance.getMinecraftVersion());
            } catch (IOException exception) {
                status.setText(exception.getMessage());
                return;
            }
            List<JavaRuntime> compatible = runtimes.stream()
                    .filter(runtime -> runtime.majorVersion() >= required)
                    .toList();
            if (compatible.isEmpty()) {
                status.setText("Minecraft " + instance.getMinecraftVersion() + " requires Java "
                        + required + " or newer. Install a compatible runtime.");
                return;
            }
            if (instance.getConfiguration().javaExecutable() != null) {
                startGame(instance, account, compatible.getFirst());
                return;
            }
            JavaRuntime preferred = compatible.getFirst();
            javafx.scene.control.ChoiceDialog<JavaRuntime> dialog =
                    new javafx.scene.control.ChoiceDialog<>(preferred, compatible);
            dialog.setTitle("Choose Java runtime");
            dialog.setHeaderText("Launch " + instance.getName());
            dialog.setContentText("Compatible runtimes:");
            dialog.showAndWait().ifPresent(runtime -> startGame(instance, account, runtime));
        });
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            logger.error("Java runtime detection failed", failure);
            status.setText("Could not detect Java runtimes: " + failure.getMessage());
        });
        backgroundExecutor.execute(task);
    }

    private void startGame(MinecraftInstance instance, AccountProfile account, JavaRuntime runtime) {
        Task<LaunchArguments> task = new Task<>() {
            @Override
            protected LaunchArguments call() throws Exception {
                return launchService.buildCommand(instance,
                        instanceService.getGameDirectory(instance.getId()), runtime, account,
                        accountManager.sessionFor(account), instance.getConfiguration(), List.of());
            }
        };
        status.setText("Preparing Minecraft launch...");
        task.setOnSucceeded(event -> {
            try {
                launchService.launch(instance.getId(), task.getValue(),
                        new LaunchService.ProcessListener() {
                            @Override
                            public void output(String line) {
                                // Game output is intentionally not persisted; it can contain credentials.
                            }

                            @Override
                            public void exited(int code, String outputError) {
                                javafx.application.Platform.runLater(() -> {
                                    status.setText(code == 0 ? "Minecraft closed."
                                            : "Minecraft exited with code " + code + ".");
                                    refreshInstances(instance.getId());
                                });
                            }

                            @Override
                            public void failed(Exception exception) {
                                logger.error("Could not monitor Minecraft process for instance {}",
                                        instance.getId(), exception);
                                javafx.application.Platform.runLater(() ->
                                        status.setText("Could not monitor Minecraft process."));
                            }
                        });
                status.setText("Minecraft is running.");
                showDetails(instance);
            } catch (IOException exception) {
                logger.error("Minecraft launch failed for instance {}", instance.getId(), exception);
                status.setText("Could not launch Minecraft: " + exception.getMessage());
            }
        });
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            logger.error("Could not prepare Minecraft launch for instance {}", instance.getId(),
                    failure);
            status.setText("Could not prepare Minecraft launch: " + failure.getMessage());
        });
        backgroundExecutor.execute(task);
    }

    private void stopGame(MinecraftInstance instance) {
        try {
            launchService.stop(instance.getId());
            status.setText("Stopping Minecraft...");
        } catch (IOException exception) {
            logger.error("Could not stop Minecraft for instance {}", instance.getId(), exception);
            status.setText("Could not stop Minecraft: " + exception.getMessage());
        }
    }

    private boolean loaderInstalled(MinecraftInstance instance) {
        if (instance.getLoaderType().equalsIgnoreCase("VANILLA")) {
            return true;
        }
        String profileId = instance.loaderProfileId();
        if (profileId == null || !profileId.matches("[A-Za-z0-9._ -]{1,100}")) {
            return false;
        }
        Path root = instanceService.getGameDirectory(instance.getId()).resolve("versions");
        Path profileDirectory = root.resolve(profileId).normalize();
        Path profile = profileDirectory.resolve(profileId + ".json");
        return profile.startsWith(root)
                && !java.nio.file.Files.isSymbolicLink(root)
                && !java.nio.file.Files.isSymbolicLink(profileDirectory)
                && java.nio.file.Files.isRegularFile(profile,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && !java.nio.file.Files.isSymbolicLink(profile);
    }

    private String displayLoaderVersion(MinecraftInstance instance) {
        String version = instance.getLoaderVersion();
        String type = instance.getLoaderType().toUpperCase(Locale.ROOT);
        if ("FABRIC".equals(type) || "QUILT".equals(type)) {
            String prefix = type.toLowerCase(Locale.ROOT) + "-loader-";
            String suffix = "-" + instance.getMinecraftVersion();
            if (version.startsWith(prefix) && version.endsWith(suffix)) {
                return version.substring(prefix.length(), version.length() - suffix.length());
            }
        } else if ("NEOFORGE".equals(type) && version.startsWith("neoforge-")) {
            return version.substring("neoforge-".length());
        }
        return version;
    }

    private void chooseLoader(MinecraftInstance instance) {
        List<String> choices = List.of("VANILLA", "FABRIC", "QUILT", "NEOFORGE");
        javafx.scene.control.ChoiceDialog<String> dialog =
                new javafx.scene.control.ChoiceDialog<>(instance.getLoaderType(), choices);
        dialog.setTitle("Mod loader");
        dialog.setHeaderText("Choose a loader for " + instance.getName());
        dialog.setContentText("Loader:");
        dialog.showAndWait().ifPresent(loader -> {
            if ("VANILLA".equals(loader)) {
                runOperation(() -> instanceService.setLoader(instance.getId(), "VANILLA", null),
                        updated -> refreshInstances(updated.getId()), "Selecting vanilla");
                return;
            }
            Task<List<LoaderVersion>> task = new Task<>() {
                @Override
                protected List<LoaderVersion> call() throws Exception {
                    return modLoaderService.versions(loader, instance.getMinecraftVersion());
                }
            };
            status.setText("Loading " + loader + " versions...");
            task.setOnSucceeded(event -> {
                List<LoaderVersion> versions = task.getValue();
                if (versions.isEmpty()) {
                    status.setText(loader + " does not list a compatible version for Minecraft "
                            + instance.getMinecraftVersion() + ".");
                    return;
                }
                javafx.scene.control.ChoiceDialog<LoaderVersion> versionDialog =
                        new javafx.scene.control.ChoiceDialog<>(versions.getFirst(), versions);
                versionDialog.setTitle(loader + " version");
                versionDialog.setHeaderText("Install " + loader + " for Minecraft "
                        + instance.getMinecraftVersion());
                versionDialog.setContentText("Loader version:");
                versionDialog.showAndWait().ifPresent(version -> installLoader(instance, loader, version));
            });
            task.setOnFailed(event -> {
                Throwable failure = task.getException();
                logger.error("Could not load {} versions", loader, failure);
                status.setText("Could not load " + loader + " versions: " + failure.getMessage());
            });
            backgroundExecutor.execute(task);
        });
    }

    private void installLoader(MinecraftInstance instance, String loader, LoaderVersion version) {
        if (!installationService.isInstalled(instanceService.getGameDirectory(instance.getId()),
                instance.getMinecraftVersion())) {
            status.setText("Install vanilla Minecraft " + instance.getMinecraftVersion()
                    + " before adding a mod loader.");
            return;
        }
        Task<MinecraftInstance> task = new Task<>() {
            @Override
            protected MinecraftInstance call() throws Exception {
                int requiredJava = javaRuntimeService.requiredMajorVersion(instance.getMinecraftVersion());
                JavaRuntime runtime = javaRuntimeService.detectInstalledRuntimes().stream()
                        .filter(candidate -> candidate.majorVersion() >= requiredJava)
                        .findFirst()
                        .orElseThrow(() -> new IOException("Install Java " + requiredJava
                                + " or newer before installing this loader."));
                String profileId = modLoaderService.install(loader,
                        instance.getMinecraftVersion(), version.version(),
                        instanceService.getGameDirectory(instance.getId()),
                        AppPaths.dataDirectory().resolve("cache/minecraft"), runtime);
                return instanceService.setLoader(instance.getId(), loader, version.version(), profileId);
            }
        };
        status.setText("Installing " + loader + " " + version.version() + "...");
        task.setOnSucceeded(event -> {
            status.setText(loader + " " + version.version() + " installed.");
            refreshInstances(task.getValue().getId());
        });
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            logger.error("{} installation failed for instance {}", loader, instance.getId(), failure);
            status.setText(loader + " installation failed: " + failure.getMessage());
        });
        backgroundExecutor.execute(task);
    }

    private void installSelectedVersion(MinecraftInstance instance) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                installationService.install(
                        instanceService.getGameDirectory(instance.getId()),
                        instance.getMinecraftVersion(),
                        (stage, completed, total) -> {
                            updateMessage(stage);
                            updateProgress(completed, total);
                        });
                return null;
            }
        };
        progressBar.progressProperty().unbind();
        progressBar.setProgress(0);
        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressBar.progressProperty().bind(task.progressProperty());
        task.messageProperty().addListener((observable, oldValue, message) -> status.setText(message));
        task.setOnSucceeded(event -> {
            progressBar.progressProperty().unbind();
            progressBar.setVisible(false);
            progressBar.setManaged(false);
            status.setText("Minecraft " + instance.getMinecraftVersion() + " installed.");
            refreshInstances(instance.getId());
        });
        task.setOnFailed(event -> {
            progressBar.progressProperty().unbind();
            progressBar.setVisible(false);
            progressBar.setManaged(false);
            Throwable failure = task.getException();
            logger.error("Minecraft installation failed for instance {}", instance.getId(), failure);
            status.setText("Minecraft installation failed: " + failure.getMessage());
        });
        backgroundExecutor.execute(task);
    }

    private <T> void runOperation(
            IoOperation<T> operation, Consumer<T> onSuccess, String progressMessage) {
        status.setText(progressMessage + "...");
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return operation.run();
            }
        };
        task.setOnSucceeded(event -> {
            status.setText("");
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            logger.error("{} failed", progressMessage, failure);
            status.setText(progressMessage + " failed: " + failure.getMessage());
        });
        backgroundExecutor.execute(task);
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws Exception;
    }
}
