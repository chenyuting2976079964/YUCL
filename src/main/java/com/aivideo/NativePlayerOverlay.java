package com.aivideo;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 原生视频播放浮层（随包 ffmpeg 软解 + Canvas 逐帧绘制，绕开 WebEngine/JavaFX 媒体栈缺陷）。
 *
 * 背景：本 OpenJFX 构建的两条媒体路都走不通——WebEngine（WebKit）缺 H.264/MPEG-4
 * 解码器；JavaFX MediaPlayer 的 GStreamer-lite 管线不带任何视频解码器（任何媒体
 * 都 ERROR_MEDIA_INVALID，已用冒烟测试验证）。ffmpeg 什么编码都能解，本浮层把
 * 原始像素画进窗口：不转码、不降质，H.264/HEVC/VP9 全支持。
 *
 * 用法（全部在 JavaFX 线程）：
 *   overlay.open(path, title)  // 显示浮层并播放
 *   overlay.close()            // 停止并隐藏
 *
 * 外层 StackPane 铺满整个 WebView 区域但 setPickOnBounds(false)：
 * 只有居中的卡片拦截鼠标，其余区域点击仍穿透到下方网页 UI。
 */
public final class NativePlayerOverlay extends StackPane {
    private static final Logger log = LoggerFactory.getLogger(NativePlayerOverlay.class);
    private static final double CARD_W = 640;
    private static final double TOP_H = 36;
    private static final double BAR_H = 44;
    private static final double VIDEO_H = 322;

    private FfmpegFramePlayer player;
    private final Canvas canvas;
    private Timeline tick;
    private boolean seeking = false;
    private boolean mutedState = false;
    private final Label titleLabel;
    private final Label timeLabel;
    private final Button playBtn;
    private final Button muteBtn;
    private final Slider seek;
    private final StackPane videoArea;

    public NativePlayerOverlay() {
        // ── 卡片 ──
        VBox card = new VBox();
        card.setPrefSize(CARD_W, TOP_H + VIDEO_H + BAR_H);
        card.setMinSize(CARD_W, TOP_H + VIDEO_H + BAR_H);
        card.setMaxSize(CARD_W, TOP_H + VIDEO_H + BAR_H);
        card.setStyle("-fx-background-color:#0d1120; -fx-background-radius:10; -fx-border-color:#243052; -fx-border-radius:10; -fx-effect: dropshadow(gaussian, rgba(0,0,0,.55), 28, 0, 0, 6);");

        // ── 顶栏：标题 + 静音 + 关闭 ──
        titleLabel = new Label("");
        titleLabel.setStyle("-fx-font-size:12; -fx-text-fill:#9aa6c4;");
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, javafx.scene.layout.Priority.ALWAYS);
        String miniBtn = "-fx-background-color:transparent; -fx-text-fill:#c2c8d6; -fx-font-size:15; -fx-cursor:hand; -fx-background-radius:6;";
        muteBtn = new Button("🔊");
        muteBtn.setStyle(miniBtn);
        Tooltip.install(muteBtn, new Tooltip("静音 / 取消静音"));
        muteBtn.setOnAction(e -> {
            if (player == null) {
                return;
            }
            mutedState = !mutedState;
            player.setMute(mutedState);
            muteBtn.setText(mutedState ? "🔇" : "🔊");
        });
        Button closeBtn = new Button("✕");
        closeBtn.setStyle(miniBtn + " -fx-font-size:14;");
        Tooltip.install(closeBtn, new Tooltip("关闭原生播放器"));
        closeBtn.setOnAction(e -> this.close());
        HBox topBar = new HBox(8, titleLabel, topSpacer, muteBtn, closeBtn);
        topBar.setPadding(new Insets(0, 10, 0, 12));
        topBar.setMinHeight(TOP_H); topBar.setPrefHeight(TOP_H); topBar.setMaxHeight(TOP_H);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle("-fx-background-color:#10152a; -fx-background-radius:10 10 0 0;");

        // ── 视频区（黑色信箱底；ffmpeg 解码帧画到 Canvas，等比居中）──
        videoArea = new StackPane();
        videoArea.setPrefSize(CARD_W, VIDEO_H);
        videoArea.setMinSize(CARD_W, VIDEO_H);
        videoArea.setMaxSize(CARD_W, VIDEO_H);
        videoArea.setStyle("-fx-background-color:#000000;");
        canvas = new Canvas(640, 318);
        videoArea.getChildren().add(canvas);

        // ── 控制栏：播放/暂停 + 进度 + 时间 ──
        playBtn = new Button("▶");
        playBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:#e6ebff; -fx-font-size:18; -fx-cursor:hand; -fx-background-radius:6; -fx-padding:6;");
        Tooltip.install(playBtn, new Tooltip("播放 / 暂停"));
        playBtn.setOnAction(e -> togglePlay());
        seek = new Slider(0, 1, 0);
        HBox.setHgrow(seek, javafx.scene.layout.Priority.ALWAYS);
        seek.setPrefHeight(BAR_H);
        seek.setOnMousePressed(e2 -> {
            seeking = true;
            if (player != null && Double.isFinite(seek.getMax()) && seek.getMax() > 0) {
                player.seekTo(seek.getValue());
            }
        });
        seek.setOnMouseReleased(e2 -> seeking = false);
        timeLabel = new Label("--:-- / --:--");
        timeLabel.setStyle("-fx-font-size:11; -fx-text-fill:#7c86a5;");
        HBox bar = new HBox(10, playBtn, seek, timeLabel);
        bar.setPadding(new Insets(0.0, 12.0, 0.0, 12.0));
        bar.setMinHeight(BAR_H); bar.setPrefHeight(BAR_H); bar.setMaxHeight(BAR_H);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color:#10152a; -fx-background-radius:0 0 10 10;");

        card.getChildren().addAll(topBar, videoArea, bar);

        // ── 外层：铺满 WebView 区域，但透明不抢点击 ──
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        setAlignment(Pos.CENTER);
        setStyle("-fx-background-color:transparent;");
        setPickOnBounds(false);
        getChildren().add(card);
        setVisible(false);
        setManaged(false);

        // UI 心跳：同步进度条与时间（150ms，足够顺滑且不费 CPU）
        tick = new Timeline(new KeyFrame(Duration.millis(150L), e2 -> this.uiTick()));
        tick.setCycleCount(Timeline.INDEFINITE);
    }

    /** 显示浮层并播放本地文件。重复调用会先停掉上一段。 */
    public void open(String path, String title) {
        this.disposeMedia();
        titleLabel.setText(title == null || title.isEmpty() ? path : title);
        mutedState = false;
        muteBtn.setText("🔊");
        String ffmpegExe;
        try {
            ffmpegExe = FfmpegCore.ffmpeg();
        } catch (Exception e) {
            ffmpegExe = "";
        }
        if (ffmpegExe == null || ffmpegExe.isEmpty()) {
            titleLabel.setText("⚠ 未找到 ffmpeg，无法启动原生播放器");
            setVisible(true);
            setManaged(true);
            return;
        }
        try {
            player = new FfmpegFramePlayer(ffmpegExe, path, canvas, new FfmpegFramePlayer.Listener() {
                @Override
                public void onTime(double c, double d) {
                    Platform.runLater(() -> {
                        if (Double.isFinite(d) && d > 0) seek.setMax(d);
                        if (!seeking) seek.setValue(c);
                        timeLabel.setText(NativePlayerOverlay.fmt(c) + " / " + (Double.isFinite(d) ? NativePlayerOverlay.fmt(d) : "--:--"));
                    });
                }

                @Override
                public void onState(String s, String detail) {
                    Platform.runLater(() -> {
                        playBtn.setText("playing".equals(s) ? "⏸" : "▶");
                        if (detail != null && !detail.isEmpty()) {
                            titleLabel.setText("⚠ " + detail);
                        } else if ("ended".equals(s)) {
                            seek.setValue(seek.getMax());
                        }
                    });
                }
            });
            player.start();
            tick.play();
            setVisible(true);
            setManaged(true);
            FfmpegFramePlayer.trace("overlay open: " + path);
            log.info("native player open: {}", path);
        } catch (Throwable t) {
            log.error("native player open failed: {}", path, t);
            titleLabel.setText("⚠ 播放器初始化失败：" + t.getMessage());
            setVisible(true);
            setManaged(true);
        }
    }

    /** 停止播放并隐藏浮层。可重复调用。 */
    public void close() {
        this.disposeMedia();
        mutedState = false;
        titleLabel.setText("");
        timeLabel.setText("--:-- / --:--");
        seek.setValue(0);
        seek.setMax(1);
        playBtn.setText("▶");
        muteBtn.setText("🔊");
        setVisible(false);
        setManaged(false);
    }

    private void togglePlay() {
        if (player != null) player.toggle();
    }

    private void uiTick() {
        if (player == null) return;
        double d = player.durationSec();
        double t = player.currentSec();
        if (Double.isFinite(d) && d > 0) seek.setMax(d);
        if (!seeking) seek.setValue(t);
        timeLabel.setText(NativePlayerOverlay.fmt(t) + " / " + (Double.isFinite(d) ? NativePlayerOverlay.fmt(d) : "--:--"));
    }

    private void disposeMedia() {
        if (tick != null) tick.stop();
        if (player != null) {
            try {
                player.close();
            } catch (Exception ignored) {
            }
        }
        player = null;
        Platform.runLater(() -> {
            javafx.scene.canvas.GraphicsContext g = canvas.getGraphicsContext2D();
            g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        });
    }

    private static String fmt(double s) {
        if (Double.isNaN(s) || s < 0) s = 0;
        int m = (int) (s / 60);
        int r = (int) (s % 60);
        return m + ":" + (r < 10 ? "0" : "") + r;
    }
}
