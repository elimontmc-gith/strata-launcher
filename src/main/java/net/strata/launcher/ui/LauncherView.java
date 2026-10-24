package net.strata.launcher.ui;

import java.util.concurrent.Executor;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import net.strata.launcher.config.AppConfig;
import net.strata.launcher.config.AppConfigStore;
import net.strata.launcher.auth.AccountManager;
import net.strata.launcher.launch.JavaRuntimeService;
import net.strata.launcher.launch.LaunchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LauncherView extends BorderPane {
    private static final Logger logger = LoggerFactory.getLogger(LauncherView.class);

    private final AppConfig config;
    private final AppConfigStore configStore;
    private final Executor backgroundExecutor;
    private final AccountManager accountManager;
    private final JavaRuntimeService javaRuntimeService;
    private final LaunchService launchService;
    private final StackPane pageContainer = new StackPane();
    private final Label pageTitle = new Label();
    private Button homeButton;
    private Button instancesButton;
    private Button settingsButton;
    private Button accountsButton;

    public LauncherView(AppConfig config, AppConfigStore configStore, Executor backgroundExecutor,
            AccountManager accountManager, JavaRuntimeService javaRuntimeService,
            LaunchService launchService) {
        this.config = config;
        this.configStore = configStore;
        this.backgroundExecutor = backgroundExecutor;
        this.accountManager = accountManager;
        this.javaRuntimeService = javaRuntimeService;
        this.launchService = launchService;
        getStyleClass().add("app-shell");
        setLeft(createSidebar());
        setCenter(createMainContent());
        showHome();
    }

    private VBox createSidebar() {
        VBox sidebar = new VBox(24);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(28, 18, 20, 18));
        sidebar.setPrefWidth(236);

        Image logoImage = new Image(
                LauncherView.class.getResourceAsStream("/com/strata/launcher/logo.png"));
        ImageView logo = new ImageView(logoImage);
        logo.setViewport(new javafx.geometry.Rectangle2D(224, 685, 1600, 508));
        logo.setPreserveRatio(true);
        logo.setFitWidth(184);
        logo.setFitHeight(60);
        Rectangle logoClip = new Rectangle(184, 60);
        logoClip.setArcWidth(8);
        logoClip.setArcHeight(8);
        logo.setClip(logoClip);
        StackPane logoSurface = new StackPane(logo);
        logoSurface.getStyleClass().add("logo-surface");
        logoSurface.setAlignment(Pos.CENTER);
        logoSurface.setPrefHeight(74);

        Label sectionLabel = new Label("WORKSPACE");
        sectionLabel.getStyleClass().add("sidebar-caption");

        homeButton = navigationButton("Home", true);
        instancesButton = navigationButton("Instances", false);
        accountsButton = navigationButton("Accounts", false);
        settingsButton = navigationButton("Settings", false);
        homeButton.setOnAction(event -> showHome());
        instancesButton.setOnAction(event -> showInstances());
        accountsButton.setOnAction(event -> showAccounts());
        settingsButton.setOnAction(event -> showSettings());

        Label versionLabel = new Label("STRATA LAUNCHER  ·  0.1.0");
        versionLabel.getStyleClass().add("sidebar-version");
        VBox.setVgrow(versionLabel, Priority.ALWAYS);
        sidebar.getChildren().addAll(logoSurface, sectionLabel, homeButton,
                instancesButton, accountsButton, settingsButton, versionLabel);
        return sidebar;
    }

    private Button navigationButton(String text, boolean selected) {
        Button button = new Button(text);
        button.getStyleClass().add("nav-button");
        if (selected) {
            button.getStyleClass().add("selected");
        }
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        return button;
    }

    private VBox createMainContent() {
        VBox content = new VBox(28);
        content.getStyleClass().add("main-content");
        content.setPadding(new Insets(34, 44, 36, 44));

        HBox heading = new HBox();
        heading.setAlignment(Pos.CENTER_LEFT);
        pageTitle.getStyleClass().add("page-title");
        Label subtitle = new Label("Your Minecraft workspace");
        subtitle.getStyleClass().add("page-subtitle");
        VBox titles = new VBox(5, pageTitle, subtitle);
        heading.getChildren().add(titles);

        content.getChildren().addAll(heading, pageContainer);
        VBox.setVgrow(pageContainer, Priority.ALWAYS);
        return content;
    }

    private void showHome() {
        selectNavigation(homeButton);
        pageTitle.setText("Welcome to Strata");
        Label eyebrow = new Label("YOUR WORLD, YOUR WAY");
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label("A better home for every\nMinecraft adventure.");
        title.getStyleClass().add("hero-title");
        title.setWrapText(true);
        Label description = new Label(
                "Create separate, organized game environments and keep each one ready to play.");
        description.getStyleClass().add("body-copy");
        description.setWrapText(true);

        VBox hero = new VBox(18, eyebrow, title, description);
        hero.getStyleClass().add("hero-panel");
        hero.setPadding(new Insets(42));
        hero.setMaxWidth(Double.MAX_VALUE);

        Label sectionTitle = new Label("INSTANCE LIBRARY");
        sectionTitle.getStyleClass().add("section-label");
        Label emptyTitle = new Label("Manage your Minecraft environments");
        emptyTitle.getStyleClass().add("empty-title");
        Label emptyDescription = new Label(
                "Create independent instance folders, save per-instance settings, and keep your game files organized.");
        emptyDescription.getStyleClass().add("body-copy");
        emptyDescription.setWrapText(true);
        Button viewInstances = new Button("Open instance library");
        viewInstances.getStyleClass().add("primary-button");
        viewInstances.setOnAction(event -> showInstances());
        VBox emptyState = new VBox(14, emptyTitle, emptyDescription, viewInstances);
        emptyState.getStyleClass().add("empty-state");
        emptyState.setPadding(new Insets(26));

        VBox home = new VBox(24, hero, sectionTitle, emptyState);
        pageContainer.getChildren().setAll(home);
    }

    private void showSettings() {
        selectNavigation(settingsButton);
        pageTitle.setText("Settings");
        Label heading = new Label("Instance storage");
        heading.getStyleClass().add("section-title");
        Label description = new Label(
                "Choose where Strata will keep game instances. Each instance will have its own files.");
        description.getStyleClass().add("body-copy");
        description.setWrapText(true);
        TextField directoryField = new TextField(config.getInstancesDirectory());
        directoryField.setPromptText("Instance directory");
        directoryField.getStyleClass().add("text-field");
        Label status = new Label();
        status.getStyleClass().add("status-message");
        Button saveButton = new Button("Save location");
        saveButton.getStyleClass().add("primary-button");
        saveButton.setOnAction(event -> {
            String directory = directoryField.getText().trim();
            if (directory.isEmpty()) {
                status.setText("Enter a folder path before saving.");
                return;
            }
            config.setInstancesDirectory(directory);
            try {
                configStore.save(config);
                status.setText("Instance location saved.");
            } catch (java.io.IOException exception) {
                logger.error("Unable to save application settings", exception);
                status.setText("Could not save settings. See the launcher log for details.");
            }
        });
        VBox settings = new VBox(14, heading, description, directoryField,
                new HBox(12, saveButton, status));
        settings.getStyleClass().add("settings-panel");
        settings.setPadding(new Insets(28));
        settings.setMaxWidth(680);
        pageContainer.getChildren().setAll(settings);
    }

    private void selectNavigation(Button selectedButton) {
        homeButton.getStyleClass().remove("selected");
        instancesButton.getStyleClass().remove("selected");
        settingsButton.getStyleClass().remove("selected");
        accountsButton.getStyleClass().remove("selected");
        selectedButton.getStyleClass().add("selected");
    }

    private void showInstances() {
        selectNavigation(instancesButton);
        pageTitle.setText("Instances");
        pageContainer.getChildren().setAll(new InstanceLibraryView(config, backgroundExecutor,
                accountManager, javaRuntimeService, launchService));
    }

    private void showAccounts() {
        selectNavigation(accountsButton);
        pageTitle.setText("Accounts");
        pageContainer.getChildren().setAll(new AccountsView(accountManager, backgroundExecutor));
    }
}
