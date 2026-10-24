package net.strata.launcher.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import net.strata.launcher.config.AppConfig;
import net.strata.launcher.config.AppConfigStore;
import net.strata.launcher.auth.AccountManager;
import net.strata.launcher.launch.JavaRuntimeService;
import net.strata.launcher.launch.LaunchService;
import net.strata.launcher.ui.LauncherView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StrataLauncherApplication extends Application {
    static {
        System.setProperty("strata.launcher.dataDir",
                net.strata.launcher.config.AppPaths.dataDirectory().toString());
    }

    private static final Logger logger = LoggerFactory.getLogger(StrataLauncherApplication.class);

    private AppConfigStore configStore;
    private AppConfig config;
    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(
            2, Thread.ofPlatform().name("strata-io-", 0).factory());

    @Override
    public void start(Stage stage) throws Exception {
        configStore = new AppConfigStore();
        config = configStore.load();

        AccountManager accountManager = new AccountManager(
                net.strata.launcher.config.AppPaths.dataDirectory().resolve("accounts.json"));
        JavaRuntimeService javaRuntimeService = new JavaRuntimeService();
        LaunchService launchService = new LaunchService(
                net.strata.launcher.config.AppPaths.dataDirectory().resolve("cache/minecraft"),
                javaRuntimeService);
        LauncherView launcherView = new LauncherView(config, configStore, backgroundExecutor,
                accountManager, javaRuntimeService, launchService);
        Scene scene = new Scene(launcherView, 1180, 760);
        scene.getStylesheets().add(
                StrataLauncherApplication.class.getResource("/com/strata/launcher/launcher.css")
                        .toExternalForm());

        stage.setTitle("Strata Launcher");
        stage.setMinWidth(900);
        stage.setMinHeight(620);
        stage.getIcons().add(new Image(
                StrataLauncherApplication.class.getResourceAsStream(
                        "/com/strata/launcher/logo.png")));
        stage.setScene(scene);
        stage.show();
        logger.info("Strata Launcher started");
    }

    @Override
    public void stop() throws Exception {
        if (configStore != null && config != null) {
            configStore.save(config);
        }
        backgroundExecutor.shutdown();
        logger.info("Strata Launcher stopped");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
