/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  com.fasterxml.jackson.databind.ObjectMapper
 *  com.fasterxml.jackson.databind.node.ObjectNode
 *  javafx.application.Application
 *  javafx.application.Platform
 *  javafx.scene.Node
 *  javafx.scene.Parent
 *  javafx.scene.Scene
 *  javafx.scene.control.Label
 *  javafx.scene.control.ProgressIndicator
 *  javafx.scene.image.Image
 *  javafx.scene.layout.StackPane
 *  javafx.scene.paint.Color
 *  javafx.scene.paint.Paint
 *  javafx.scene.web.WebEngine
 *  javafx.scene.web.WebView
 *  javafx.stage.Stage
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package com.aivideo;

import com.aivideo.DataDirs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AppShell
extends Application {
    private static final Logger log = LoggerFactory.getLogger(AppShell.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String baseUrl;
    private final Runnable onShutdown;
    private Stage stage;

    public AppShell(String baseUrl, Runnable onShutdown) {
        this.baseUrl = baseUrl;
        this.onShutdown = onShutdown;
    }

    public void start(Stage s) {
        this.stage = s;
        s.setTitle("YUCL \u00b7 AI \u77ed\u89c6\u9891\u5de5\u4f5c\u5ba4");
        s.setMinWidth(960.0);
        s.setMinHeight(600.0);
        Image icon = AppShell.loadIcon();
        if (icon != null) {
            s.getIcons().add(icon);
        }
        double[] geo = this.loadGeometry();
        s.setWidth(geo[0]);
        s.setHeight(geo[1]);
        WebView view = new WebView();
        WebEngine engine = view.getEngine();
        javafx.scene.layout.VBox splash = new javafx.scene.layout.VBox(10.0);
        Label brand = new Label("YUCL");
        brand.setStyle("-fx-font-size: 26; -fx-font-weight: 800; -fx-text-fill: #eaedf7; -fx-letter-spacing: 2;");
        Label tag = new Label("AI \u77ed\u89c6\u9891\u5de5\u4f5c\u5ba4");
        tag.setStyle("-fx-font-size: 12; -fx-text-fill: #7c86a5;");
        ProgressIndicator spinner = new ProgressIndicator(26.0);
        spinner.setStyle("-fx-progress-indicator: 26;");
        Label hint = new Label("\u670d\u52a1\u6b63\u5728\u542f\u52a8\uff0c\u7a0d\u5019\u2026");
        hint.setStyle("-fx-font-size: 12; -fx-text-fill: #535d78;");
        splash.getChildren().addAll(brand, tag, spinner, hint);
        splash.setPrefWidth(220.0);
        javafx.scene.layout.BorderPane splashRoot = new javafx.scene.layout.BorderPane();
        splashRoot.setCenter(splash);
        splashRoot.setStyle("-fx-background-color: #0b0d14;");
        StackPane root = new StackPane();
        root.getChildren().addAll(view, splashRoot);
        s.setScene(new Scene(root, geo[0], geo[1]));
        s.setOnCloseRequest(ev -> {
            this.saveGeometry();
            if (this.onShutdown != null) {
                try {
                    this.onShutdown.run();
                }
                catch (Exception e) {
                    log.warn("shutdown hook failed: {}", (Throwable)e);
                }
            }
            Platform.exit();
            System.exit(0);
        });
        s.show();
        engine.setOnError(e -> log.error("\u9875\u9762 JS \u9519\u8bef: {}", (Object)e));
        engine.getLoadWorker();
        Thread boot = new Thread(() -> {
            int tries = 0;
            while (tries++ < 300 && !this.healthzUp()) {
                try {
                    Thread.sleep(100L);
                }
                catch (InterruptedException e) {
                    // empty catch block
                    break;
                }
            }
            Platform.runLater(() -> {
                splashRoot.setVisible(false);
                splashRoot.setManaged(false);
                engine.load(this.baseUrl);
            });
        }, "yucl-shell-boot");
        boot.setDaemon(true);
        boot.start();
    }

    private boolean healthzUp() {
        try {
            HttpURLConnection c = (HttpURLConnection)new URL(this.baseUrl + "/api/plugins/ai-video/healthz").openConnection();
            c.setConnectTimeout(400);
            c.setReadTimeout(800);
            boolean ok = c.getResponseCode() == 200;
            c.disconnect();
            return ok;
        }
        catch (Exception e) {
            return false;
        }
    }

    private Path windowState() {
        return DataDirs.dataDir().resolve("window.json");
    }

    private double[] loadGeometry() {
        try {
            Path p = this.windowState();
            if (Files.exists(p, new LinkOption[0])) {
                JsonNode n = JSON.readTree(p.toFile());
                double w = n.path("w").asDouble(0.0);
                double h = n.path("h").asDouble(0.0);
                if (w >= 960.0 && h >= 600.0) {
                    double[] out = new double[]{w, h};
                    if (n.has("x") && n.has("y")) {
                        this.stage.setX(n.get("x").asDouble());
                        this.stage.setY(n.get("y").asDouble());
                    }
                    return out;
                }
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return new double[]{1220.0, 780.0};
    }

    private void saveGeometry() {
        try {
            Path p = this.windowState();
            Files.createDirectories(p.getParent(), new FileAttribute[0]);
            ObjectNode n = JSON.createObjectNode();
            n.put("w", Math.round(this.stage.getWidth()));
            n.put("h", Math.round(this.stage.getHeight()));
            n.put("x", Math.round(this.stage.getX()));
            n.put("y", Math.round(this.stage.getY()));
            Files.write(p, JSON.writeValueAsBytes((Object)n), new OpenOption[0]);
        }
        catch (Exception e) {
            log.warn("window geometry save failed: {}", (Throwable)e);
        }
    }

    private static Image loadIcon() {
        Image image;
        block8: {
            InputStream is = AppShell.class.getResourceAsStream("/app-icon.png");
            try {
                Image image2 = image = is != null ? new Image(is) : null;
                if (is == null) break block8;
            }
            catch (Throwable throwable) {
                try {
                    if (is != null) {
                        try {
                            is.close();
                        }
                        catch (Throwable throwable2) {
                            throwable.addSuppressed(throwable2);
                        }
                    }
                    throw throwable;
                }
                catch (Exception e) {
                    return null;
                }
            }
            try {
                is.close();
            }
            catch (Exception ignored) {
            }
        }
        return image;
    }

    public static void launch(String baseUrl, Runnable onShutdown) {
        try {
            Platform.startup(() -> {
                try {
                    AppShell app = new AppShell(baseUrl, onShutdown);
                    app.start(new Stage());
                }
                catch (Throwable t) {
                    log.error("FX window failed to open: {}", t);
                }
            });
        }
        catch (Throwable t) {
            log.warn("JavaFX toolkit unavailable on this machine: {} \u2014 falling back to the browser", t);
            AppShell.openBrowserFallback(baseUrl);
        }
    }

    private static void openBrowserFallback(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb = os.contains("win") ? new ProcessBuilder("cmd", "/c", "start", "", url) : new ProcessBuilder("xdg-open", url);
            pb.inheritIO().start();
        }
        catch (Exception exception) {
            // empty catch block
        }
    }
}
