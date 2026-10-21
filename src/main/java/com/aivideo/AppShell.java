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
    public static final long S0 = System.currentTimeMillis();

    /** 启动耗时探针：直接写文件（slf4j 在 Spring 初始化前不写文件） */
    private static final java.io.File TRACE = resolveTraceFile();

    static java.io.File resolveTraceFile() {
        String appPath = System.getProperty("jpackage.app-path");
        java.nio.file.Path base = appPath != null
                ? java.nio.file.Paths.get(appPath).toAbsolutePath().getParent().resolve("data")
                : java.nio.file.Paths.get(System.getProperty("user.dir")).resolve("data");
        base.toFile().mkdirs();
        return base.resolve("boot-trace.log").toFile();
    }

    static synchronized void trace(String msg) {
        try (java.io.FileWriter w = new java.io.FileWriter(TRACE, true)) {
            w.write(new java.util.Date().toString() + " t+" + (System.currentTimeMillis() - S0) + "ms " + msg + System.lineSeparator());
        } catch (Exception ignored) { }
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String baseUrl;
    private final Runnable onShutdown;
    private Stage stage;
    private NativeWindow nativeWindow;
    private double outScale = 1.0;
    public static volatile Runnable windowResetHook;

    public AppShell(String baseUrl, Runnable onShutdown) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.onShutdown = onShutdown;
    }

    public void start(Stage s) {
        this.stage = s;
        this.outScale = Math.max(1.0, javafx.stage.Screen.getPrimary().getOutputScaleY());
        s.initStyle(javafx.stage.StageStyle.UNDECORATED);
        s.setTitle("YUCL \u00b7 AI \u77ed\u89c6\u9891\u5de5\u4f5c\u5ba4");
        s.setMinWidth(960.0 / this.outScale);
        s.setMinHeight(600.0 / this.outScale);
        Image icon = AppShell.loadIcon();
        if (icon != null) {
            s.getIcons().add(icon);
        }
        double[] geo = this.loadGeometry();
        s.setWidth(geo[0] / this.outScale);
        s.setHeight(geo[1] / this.outScale);
        WebView view = new WebView();
        // WebView 在任何窗口尺寸下都必须铺满内容区（StackPane 按 max 约束摆放子节点），
        // 否则拉大/拉小窗口后 WebView 停留在首选尺寸，露出 Scene 背景（"空白带"根因之一）
        view.setMinWidth(0.0);
        view.setMinHeight(0.0);
        view.setMaxWidth(Double.MAX_VALUE);
        view.setMaxHeight(Double.MAX_VALUE);
        trace("webview ctor start");
        WebEngine engine = view.getEngine();
        trace("webview ctor done");
        trace("window shown " + (int) s.getWidth() + "x" + (int) s.getHeight()
                + " scale=" + javafx.stage.Screen.getPrimary().getOutputScaleY()
                + " bounds=" + javafx.stage.Screen.getPrimary().getBounds());
        log.info("[boot] window shown t+{}ms", System.currentTimeMillis() - S0);
        final java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        // 预热 WebView2（与 Spring 启动并行），避免首帧渲染再等几十秒
        engine.load("about:blank");
        engine.getLoadWorker().stateProperty().addListener((o2, o, n) -> {
            if (n == javafx.concurrent.Worker.State.SUCCEEDED) {
                trace("load#" + loads.incrementAndGet() + " done: " + engine.getLocation());
            }
        });
        // ── 品牌启动画面（保底展示 5s，即使后端瞬间就绪）──
        javafx.scene.layout.VBox splash = new javafx.scene.layout.VBox(16.0);
        splash.setAlignment(javafx.geometry.Pos.CENTER);
        Label brand = new Label("YUCL");
        brand.setAlignment(javafx.geometry.Pos.CENTER);
        brand.setStyle("-fx-font-size: 52; -fx-font-weight: 800; -fx-text-fill: linear-gradient(to right, #4f7cff, #8b5cf6, #c084fc); -fx-letter-spacing: 8;");
        Label tag = new Label("AI \u77ed\u89c6\u9891\u5de5\u4f5c\u5ba4");
        tag.setAlignment(javafx.geometry.Pos.CENTER);
        tag.setStyle("-fx-font-size: 14; -fx-text-fill: #a5aec9; -fx-letter-spacing: 6;");
        // 自绘进度条（Region 双层，彻底绕开 modena 默认样式）
        javafx.scene.layout.Region barTrack = new javafx.scene.layout.Region();
        barTrack.setPrefSize(320.0, 8.0);
        barTrack.setStyle("-fx-background-color: #1a2138; -fx-background-radius: 4;");
        javafx.scene.layout.Region barFill = new javafx.scene.layout.Region();
        barFill.setPrefSize(6.0, 8.0);
        barFill.setStyle("-fx-background-color: linear-gradient(to right, #4f7cff, #8b5cf6); -fx-background-radius: 4; -fx-effect: dropshadow(gaussian, #4f7cff, 18, 0.45, 0, 0);");
        javafx.scene.layout.StackPane barBox = new javafx.scene.layout.StackPane(barTrack, barFill);
        barBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        barBox.setPrefWidth(320.0);
        Label hint = new Label("\u6b63\u5728\u542f\u52a8\uff0c\u8bf7\u7a0d\u5019\u2026");
        hint.setAlignment(javafx.geometry.Pos.CENTER);
        hint.setStyle("-fx-font-size: 12; -fx-text-fill: #6d7894;");
        splash.getChildren().addAll(brand, tag, barBox, hint);
        javafx.scene.layout.StackPane splashRoot = new javafx.scene.layout.StackPane(splash);
        splashRoot.setStyle("-fx-background-color: radial-gradient(center 50% 35%, radius 420, #1d2547, #0b0d14 85%);");
        // 进度条 4.5s 内爬到 85%（真实完成时直接 100%）
        javafx.animation.Timeline anim = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(4500L),
                        new javafx.animation.KeyValue(barFill.prefWidthProperty(), 272.0, javafx.animation.Interpolator.EASE_OUT)));
        anim.setCycleCount(1);
        anim.play();
        StackPane root = new StackPane();
        root.getChildren().addAll(view, splashRoot);
        // ── 自定义深色标题栏（去装饰窗口 + 原生浅标题栏不搭）──
        javafx.scene.layout.HBox titleBar = new javafx.scene.layout.HBox(8.0);
        titleBar.setPrefHeight(40.0);
        titleBar.setMinHeight(40.0);
        titleBar.setMaxHeight(40.0);
        titleBar.setPadding(new javafx.geometry.Insets(0.0, 12.0, 0.0, 14.0));
        titleBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        titleBar.setStyle("-fx-background-color: #0d1120; -fx-border-color: #1c2440; -fx-border-width: 0 0 1 0;");
        Label tbIcon = new Label("YUCL");
        tbIcon.setStyle("-fx-font-size: 13; -fx-font-weight: 700; -fx-text-fill: #8b5cf6;");
        Label tbTitle = new Label("AI \u77ed\u89c6\u9891\u5de5\u4f5c\u5ba4");
        tbTitle.setStyle("-fx-font-size: 12; -fx-text-fill: #7c86a5;");
        javafx.scene.layout.Region tbSpacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(tbSpacer, javafx.scene.layout.Priority.ALWAYS);
        // 窗口控制按钮：纯矢量绘制（不依赖字体字形），深色圆角底 + 亮色图形
        // 最左按钮位：「设置」齿轮（原 UI 头部按钮挪到此，取代原最小化按钮）——点击经 WebView JS 钩子打开设置面板
        javafx.scene.layout.StackPane bSet = AppShell.winBtn(36.0, "#1a2138");
        bSet.getChildren().add(AppShell.gearIcon());
        javafx.scene.control.Tooltip.install(bSet, new javafx.scene.control.Tooltip("\u8bbe\u7f6e"));
        bSet.setOnMouseEntered(e -> bSet.setStyle("-fx-background-color: #2a3350; -fx-background-radius: 6; -fx-cursor: hand;"));
        bSet.setOnMouseExited(e -> bSet.setStyle("-fx-background-color: #1a2138; -fx-background-radius: 6; -fx-cursor: hand;"));
        bSet.setOnMouseClicked(e -> {
            final javafx.scene.web.WebEngine eng = view.getEngine();
            javafx.application.Platform.runLater(() -> {
                try {
                    // 本 OpenJFX 构建的 WebEngine 没有 eval(String)，JS 注入走 executeScript
                    eng.executeScript("window.__yuclOpenSettings && window.__yuclOpenSettings();");
                } catch (Exception e2) {
                    log.warn("open settings from title bar failed: {}", e2.toString());
                }
            });
        });
        javafx.scene.layout.StackPane bMax = AppShell.winBtn(36.0, "#1a2138");
        javafx.scene.layout.Region mxBox = new javafx.scene.layout.Region();
        mxBox.setPrefSize(10.0, 10.0);
        mxBox.setStyle("-fx-border-color: #dfe4f5; -fx-border-width: 1.5; -fx-background-color: transparent;");
        bMax.getChildren().add(mxBox);
        bMax.setOnMouseEntered(e -> bMax.setStyle("-fx-background-color: #2a3350; -fx-background-radius: 6; -fx-cursor: hand;"));
        bMax.setOnMouseExited(e -> bMax.setStyle("-fx-background-color: #1a2138; -fx-background-radius: 6; -fx-cursor: hand;"));
        bMax.setOnMouseClicked(e -> AppShell.this.toggleMaximizeWindow());
        javafx.scene.layout.StackPane bClose = AppShell.winBtn(36.0, "#1a2138");
        javafx.scene.shape.Line cl1 = new javafx.scene.shape.Line(-5.5, -5.5, 5.5, 5.5);
        javafx.scene.shape.Line cl2 = new javafx.scene.shape.Line(-5.5, 5.5, 5.5, -5.5);
        cl1.setStroke(javafx.scene.paint.Color.web("#dfe4f5"));
        cl2.setStroke(javafx.scene.paint.Color.web("#dfe4f5"));
        cl1.setStrokeWidth(1.8);
        cl2.setStrokeWidth(1.8);
        bClose.getChildren().addAll(cl1, cl2);
        bClose.setOnMouseEntered(e -> bClose.setStyle("-fx-background-color: #c0392b; -fx-background-radius: 6; -fx-cursor: hand;"));
        bClose.setOnMouseExited(e -> bClose.setStyle("-fx-background-color: #1a2138; -fx-background-radius: 6; -fx-cursor: hand;"));
        bClose.setOnMouseClicked(e -> this.doExit());
        titleBar.getChildren().addAll(tbIcon, tbTitle, tbSpacer, bSet, bMax, bClose);
        double[] drag = new double[2];
        titleBar.setOnMousePressed(ev -> {
            drag[0] = ev.getScreenX() - s.getX();
            drag[1] = ev.getScreenY() - s.getY();
        });
        titleBar.setOnMouseDragged(ev -> {
            s.setX(ev.getScreenX() - drag[0]);
            s.setY(ev.getScreenY() - drag[1]);
        });
        javafx.scene.layout.VBox outer = new javafx.scene.layout.VBox();
        // 关键：root（WebView 内容区）必须吃满剩余高度。VBox 只把多出的空间分给
        // Vgrow=ALWAYS 的子节点，否则内容停在首选高度、多出来的区域露出 Scene 白底——
        // 这就是上下拉窗口出现"空白带"的根因
        javafx.scene.layout.VBox.setVgrow(root, javafx.scene.layout.Priority.ALWAYS);
        outer.getChildren().addAll(titleBar, root);
        // 双保险：根节点画应用暗色底，缩放中途任何亚像素缝隙显示为深色而非白色
        outer.setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(
                javafx.scene.paint.Color.web("#0b0d14"), null, null)));
        s.setScene(new Scene(outer, geo[0] / this.outScale, geo[1] / this.outScale));
        // 缩放后可观测性：布局真正重排完成（250ms 后、当帧脉冲结束）才记录最终尺寸，
        // 直接读属性变更瞬间会抓到同一帧内的中间值（1s 节流 + 去重，避免拖拽刷屏）
        final java.util.concurrent.atomic.AtomicBoolean settlePending = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.function.Consumer<Object> queueSettleLog = o -> {
            if (settlePending.compareAndSet(false, true)) {
                new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                        javafx.util.Duration.millis(250), ev -> {
                            settlePending.set(false);
                            trace("layout settled: outer " + outer.getWidth() + "x" + outer.getHeight()
                                    + " root " + root.getWidth() + "x" + root.getHeight()
                                    + " webview " + view.getWidth() + "x" + view.getHeight());
                        })).play();
            }
        };
        root.widthProperty().addListener((o, a, b) -> queueSettleLog.accept(b));
        root.heightProperty().addListener((o, a, b) -> queueSettleLog.accept(b));
        view.widthProperty().addListener((o, a, b) -> queueSettleLog.accept(b));
        view.heightProperty().addListener((o, a, b) -> queueSettleLog.accept(b));
        // 缩放由 Windows 框架接管（NativeWindow 的 WM_NCCALCSIZE/WM_NCHITTEST 子类化），
        // 全程跟手、可超长拖拽；JavaFX 侧不需要任何缩放句柄节点。
        // 双击标题栏 = 最大化/还原（OS 标准行为）
        titleBar.setOnMouseReleased(e -> {
            if (e.getClickCount() == 2 && (e.getTarget() == titleBar || e.getTarget() instanceof javafx.scene.layout.Region)) {
                AppShell.this.toggleMaximizeWindow();
            }
        });
        s.setOnCloseRequest(ev -> {
            this.doExit();
        });
        AppShell.windowResetHook = () -> {
            javafx.application.Platform.runLater(() -> {
                // 原生（ShowWindow）最大化必须走 OS 还原；JavaFX setMaximized(false) 还原不了它
                if (AppShell.this.nativeWindow != null) {
                    AppShell.this.nativeWindow.restore();
                } else {
                    s.setMaximized(false);
                }
                s.setWidth(1220.0 / this.outScale);
                s.setHeight(780.0 / this.outScale);
            });
        };
        s.show();
        // 原生框架级缩放（JNA 子类化该窗口 HWND）：1.5s 后安装，HWND 已稳定
        final double outScale = this.outScale;
        new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                new javafx.util.Duration(1500),
                e2 -> {
                    try {
                        // 原生框架级缩放：NCA 边框环 border px（物理）；
                        // 标题栏带高度 = 标题栏逻辑 40px × outScale；
                        // 右侧按钮区宽度 = 3×36 按钮 + 间距 + 右 padding（约 150 逻辑 px）
                        int border = (int) (6 * outScale);
                        int titleBarH = (int) (40 * outScale);
                        int rightButtonsW = (int) (150 * outScale);
                        NativeWindow nw = NativeWindow.install("YUCL \u00b7 AI \u77ed\u89c6\u9891\u5de5\u4f5c\u5ba4", border, titleBarH, rightButtonsW);
                        if (nw != null) {
                            AppShell.this.nativeWindow = nw;
                            trace("native resize installed (border=" + border + "px titleBar=" + titleBarH + "px)");
                        } else {
                            trace("native resize install FAILED (FindWindow/JNA unavailable)");
                        }
                    } catch (Throwable t) {
                        trace("native resize install error: " + t);
                    }
                }))
                .play();
        Thread probeThread = new Thread(() -> {
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException ignored) { }
            javafx.application.Platform.runLater(() -> {
                try {
                    trace("layout bSet[localX=" + (int) bSet.getLayoutX() + ",w=" + bSet.getWidth() + "] bMax[localX=" + (int) bMax.getLayoutX() + "] bClose[localX=" + (int) bClose.getLayoutX() + ",w=" + bClose.getWidth() + "]"
                            + " sceneW=" + s.getScene().getWidth()
                            + " rootW=" + (int) root.getWidth() + " titleBarW=" + (int) titleBar.getWidth()
                            + " tbH=" + (int) titleBar.getHeight());
                    // JavaFX 自己渲染场景快照（不经过 PrintWindow，排除捕获工具干扰）
                    javafx.scene.image.WritableImage wi = s.getScene().getRoot()
                            .snapshot(null, null);
                    int sw = (int) wi.getWidth(), sh = (int) wi.getHeight();
                    java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(sw, sh, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    javafx.scene.image.PixelReader pr = wi.getPixelReader();
                    for (int py = 0; py < sh; py++) {
                        for (int px = 0; px < sw; px++) {
                            bi.setRGB(px, py, pr.getArgb(px, py));
                        }
                    }
                    // 快照写到当前数据目录（不再写死 D:/YUCL/data，其它安装位置也能出诊断图）
                    java.nio.file.Path snapDir = com.aivideo.DataDirs.dataDir();
                    java.nio.file.Files.createDirectories(snapDir);
                    javax.imageio.ImageIO.write(bi, "png", snapDir.resolve("scene-snapshot.png").toFile());
                    trace("scene snapshot saved " + sw + "x" + sh + " -> " + snapDir.resolve("scene-snapshot.png"));
                } catch (Exception ex) {
                    trace("snapshot failed: " + ex);
                }
            });
        });
        probeThread.setDaemon(true);
        probeThread.start();
        engine.setOnError(e -> log.error("\u9875\u9762 JS \u9519\u8bef: {}", (Object)e));
        engine.getLoadWorker();
        final long t0 = System.currentTimeMillis();
        Thread boot = new Thread(() -> {
            boolean up = false;
            int tries = 0;
            while (tries++ < 300) {
                if (this.healthzUp()) {
                    up = true;
                    break;
                }
                try {
                    Thread.sleep(100L);
                }
                catch (InterruptedException e) {
                    // empty catch block
                    break;
                }
            }
            // 启动画面保底 5s：后端就绪了也留足品牌展示时间
            long hold = Math.max(0L, 5000L - (System.currentTimeMillis() - t0));
            try {
                Thread.sleep(hold + 500L);
            }
            catch (InterruptedException ignored) {
            }
            trace(up ? "healthz ok" : "healthz timeout");
            log.info("[boot] healthz {} t+{}ms — releasing splash", up ? "ok" : "TIMEOUT", System.currentTimeMillis() - S0);
            Platform.runLater(() -> {
                anim.stop();
                barFill.setPrefWidth(320.0);
                hint.setText("\u5c31\u7eea");
                hint.setStyle("-fx-font-size: 12; -fx-text-fill: #58c48a;");
                engine.load(this.baseUrl + "/");
                new Thread(() -> {
                    try {
                        Thread.sleep(400L);
                    }
                    catch (InterruptedException ignored2) {
                    }
                    Platform.runLater(() -> {
                        splashRoot.setVisible(false);
                        splashRoot.setManaged(false);
                        trace("splash hidden, page up");
                    });
                }).start();
            });
        }, "yucl-shell-boot");
        boot.setDaemon(true);
        boot.start();
    }

    // 无边框窗口的边缘拖拽缩放句柄（透明 8px，按 side 决定拖拽行为）
    private static javafx.scene.layout.StackPane winBtn(double size, String bg) {
        javafx.scene.layout.StackPane b = new javafx.scene.layout.StackPane();
        b.setPrefSize(size, 40.0);
        b.setStyle("-fx-background-color: " + bg + "; -fx-cursor: hand; -fx-background-radius: 6;");
        return b;
    }

    private void doExit() {
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
    }

    /** 标准最大化/还原：优先走 NativeWindow 的 OS 行为（ShowWindow SW_MAXIMIZE/SW_RESTORE，
     *  和其它 Windows 应用一致：最大化 = 显示器工作区，再点还原回原位置/大小）；JNA 不可用时降级 JavaFX。 */
    private void toggleMaximizeWindow() {
        if (this.nativeWindow != null) {
            this.nativeWindow.toggleMaximize();
        }
        else {
            this.stage.setMaximized(!this.stage.isMaximized());
        }
    }

    /** 设置齿轮矢量图标（Canvas 绘制：8 齿 + 圆环 + 中心点；不用字体字形，JVM 默认字体缺 ⚙ 会空白。
     *  注：本 OpenJFX 构建没有 shape.Group / Node.setPivot，用 Canvas 2D 等价实现） */
    private static javafx.scene.Node gearIcon() {
        javafx.scene.canvas.Canvas cv = new javafx.scene.canvas.Canvas(24.0, 24.0);
        javafx.scene.canvas.GraphicsContext g = cv.getGraphicsContext2D();
        g.translate(12.0, 12.0);
        javafx.scene.paint.Color c = javafx.scene.paint.Color.web("#dfe4f5");
        g.setFill(c);
        for (int i = 0; i < 8; i++) {
            g.save();
            g.rotate((double)i * 45.0);
            g.fillRoundRect(-1.5, -9.0, 3.0, 4.2, 1.0, 1.0);
            g.restore();
        }
        g.setStroke(c);
        g.setLineWidth(1.7);
        g.strokeOval(-5.2, -5.2, 10.4, 10.4);
        g.setFill(c);
        g.fillOval(-1.9, -1.9, 3.8, 3.8);
        return cv;
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
                        // 合法性检查：window.json 被写坏 / 窗口被拖到已断开的显示器上时，
                        // 盲恢复会让窗口掉到屏幕外且无法找回（任务管理器杀进程）
                        double x = n.get("x").asDouble(0.0);
                        double y = n.get("y").asDouble(0.0);
                        if (x > -4000 && x < 4000 && y > -1000 && y < 3000 && Double.isFinite(x) && Double.isFinite(y)) {
                            this.stage.setX(x);
                            this.stage.setY(y);
                        }
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
            n.put("w", Math.round(this.stage.getWidth() * this.outScale));
            n.put("h", Math.round(this.stage.getHeight() * this.outScale));
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
