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
    /** 嵌入模式（"在播放器框内播放"）：卡片定位到网页播放器框，顶栏透明悬浮 */
    private boolean embedded = false;
    /** 定位已定死（position:fixed 语义）：首次 embedAt 生效后，滚动上报不再移动浮层；open()/unembed() 重置 */
    private boolean pinned = false;
    /** 当前加载的文件（同路径重开拦截用） */
    private String currentFile;
    private javafx.scene.layout.VBox cardBox;
    private javafx.scene.layout.HBox topBarNode;
    private javafx.scene.layout.HBox barNode;

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
                FfmpegFramePlayer.trace("mute click: player==null（播放器未启动/已退出）");
                return;
            }
            mutedState = !mutedState;
            FfmpegFramePlayer.trace("mute click -> " + mutedState);
            player.setMute(mutedState);
            muteBtn.setText(mutedState ? "🔇" : "🔊");
        });
        Button closeBtn = new Button("✕");
        closeBtn.setStyle(miniBtn + " -fx-font-size:14;");
        Tooltip.install(closeBtn, new Tooltip("关闭原生播放器"));
        closeBtn.setOnAction(e -> {
            FfmpegFramePlayer.trace("close click");
            this.close();
        });
        HBox topBar = new HBox(8, titleLabel, topSpacer, muteBtn, closeBtn);
        topBar.setPadding(new Insets(0, 10, 0, 12));
        topBar.setMinHeight(TOP_H); topBar.setPrefHeight(TOP_H); topBar.setMaxHeight(TOP_H);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle("-fx-background-color:#10152a; -fx-background-radius:10 10 0 0;");
        topBarNode = topBar;

        // ── 视频区（黑色信箱底；ffmpeg 解码帧画到 Canvas，等比居中）──
        videoArea = new StackPane();
        videoArea.setPrefSize(CARD_W, VIDEO_H);
        videoArea.setMinSize(CARD_W, VIDEO_H);
        videoArea.setMaxSize(CARD_W, VIDEO_H);
        videoArea.setStyle("-fx-background-color:#000000;");
        canvas = new Canvas(640, 318);
        videoArea.getChildren().add(canvas);
        // 点视频区直接 暂停/继续（同网页 <video> 的点击语义，⏸ 按钮保留为备用入口）：
        // 画面本体 + 黑边信箱区都算视频区。reloading（重播冷启动中）点击无效——toggle 无此分支。
        videoArea.setOnMouseClicked(e -> {
            FfmpegFramePlayer.trace("vid click (state=" + (player == null ? "null" : FfmpegFramePlayer.stateOf(player)) + ")");
            togglePlay();
        });
        // （本 OpenJFX 裁剪构建无 javafx.scene.input.Cursor，手型光标不可用）

        // ── 控制栏：播放/暂停 + 进度 + 时间 ──
        playBtn = new Button("▶");
        playBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:#e6ebff; -fx-font-size:18; -fx-cursor:hand; -fx-background-radius:6; -fx-padding:6;");
        Tooltip.install(playBtn, new Tooltip("播放 / 暂停"));
        playBtn.setOnAction(e -> {
            FfmpegFramePlayer.trace("play click (state=" + (player == null ? "null" : FfmpegFramePlayer.stateOf(player)) + ")");
            togglePlay();
        });
        seek = new Slider(0, 1, 0);
        HBox.setHgrow(seek, javafx.scene.layout.Priority.ALWAYS);
        seek.setPrefHeight(BAR_H);
        seek.setOnMousePressed(e2 -> {
            seeking = true; // 拖动中 uiTick 不覆盖 Slider，保留用户拖到的位置
        });
        seek.setOnMouseReleased(e2 -> {
            seeking = false;
            // seek 在"释放时"才执行：按下瞬间 value 还是旧位置（点了没反应/拖不动的根因）；
            // 释放时 value 才是用户拖到的目标位置
            if (player != null && Double.isFinite(seek.getMax()) && seek.getMax() > 0) {
                FfmpegFramePlayer.trace("seek release -> " + String.format(java.util.Locale.ROOT, "%.2f", seek.getValue()));
                player.seekTo(seek.getValue());
            }
        });
        timeLabel = new Label("--:-- / --:--");
        timeLabel.setStyle("-fx-font-size:11; -fx-text-fill:#7c86a5;");
        HBox bar = new HBox(10, playBtn, seek, timeLabel);
        bar.setPadding(new Insets(0.0, 12.0, 0.0, 12.0));
        bar.setMinHeight(BAR_H); bar.setPrefHeight(BAR_H); bar.setMaxHeight(BAR_H);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color:#10152a; -fx-background-radius:0 0 10 10;");
        barNode = bar;

        card.getChildren().addAll(topBar, videoArea, bar);
        cardBox = card;

        // ── 外层：铺满 WebView 区域，但透明不抢点击 ──
        // 关键：外层绝不能有任何背景填充——连 "-fx-background-color:transparent" 都算
        // 非 null 的 fill。对带 fill 的 Region，pickOnBounds(false) 并不生效（命中测试
        // 仍把整块区域当"已绘制"→ 卡片以外区域的点击被外层吞掉，下方网页点不动，
        // 日志里 target=NativePlayerOverlay 即此症状）。只有 pickOnBounds(false) + 无
        // fill 才是真穿透：非卡片区域点击穿透到下方 WebView，卡片（子节点、自带实色
        // 背景）仍完全可交互。
        // （不能用 setMouseTransparent(true)：JavaFX 文档明确它作用于"本节点及其全部
        //   子节点"，会把卡片一起弄死）
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        setAlignment(Pos.CENTER);
        setBackground(null);
        setPickOnBounds(false);
        getChildren().add(card);
        setVisible(false);
        setManaged(false);

        // UI 心跳：同步进度条与时间（150ms，足够顺滑且不费 CPU）
        tick = new Timeline(new KeyFrame(Duration.millis(150L), e2 -> this.uiTick()));
        tick.setCycleCount(Timeline.INDEFINITE);
    }

    /** 嵌入模式：把卡片定位到网页播放器框（root 坐标系 = WebView 视口坐标系，CSS px 1:1）。
     *  顶栏变透明悬浮、卡片去阴影、尺寸=框尺寸；视频区 letterbox 铺满剩余空间。
     *  非法 rect（过小）自动退回居中卡片。
     *  定位语义对齐 CSS position:fixed（滚动悬浮视频的标准做法：视频定在视口内，
     *  页面内容在下方滚动）——打开时定位一次即「定死」，此后 JS 持续上报的
     *  滚动/缩放 rect 不再移动浮层（用户要求：界面向下滑，视频不能动）；
     *  只有 open() 切到新源时重置并重新定位。 */
    public void embedAt(double x, double y, double w, double h) {
        if (pinned) {
            FfmpegFramePlayer.trace("embedAt ignored (pinned: 定位已定死，不随页面滚动)");
            return;
        }
        if (w < 240 || h < 160 || !(w > 0) || !(h > 0)) {
            this.unembed();
            return;
        }
        // 钳制到 root（= WebView 视口）边界：网页播放框被向上/向左滚出视口时
        // （<video> rect.top<0，JS 持续上报会带着负坐标进来），旧逻辑原样跟随
        // → 卡片整体甩出窗口上缘，用户"播放器消失了"。钳制后卡片贴住视口边缘。
        if (this.getParent() instanceof javafx.scene.layout.Region pn && pn.getWidth() > 0 && pn.getHeight() > 0) {
            double vw = pn.getWidth(), vh = pn.getHeight();
            if (w <= vw) x = Math.max(0.0, Math.min(x, vw - w));
            y = Math.max(0.0, Math.min(y, vh - h));
        }
        embedded = true;
        FfmpegFramePlayer.trace("embedAt " + (int) x + "," + (int) y + " " + (int) w + "x" + (int) h);
        // 卡片尺寸 = 框尺寸（内容被顶栏 36 + 控制栏 44 压缩，视频区 letterbox）
        cardBox.setPrefSize(w, h);
        cardBox.setMinSize(w, h);
        cardBox.setMaxSize(w, h);
        cardBox.setStyle("-fx-background-color:#000; -fx-background-radius:8; -fx-border-color:#243052; -fx-border-radius:8; -fx-border-width:1;");
        // 顶栏透明（悬浮在视频上，保留 标题/静音/关闭 操作）
        topBarNode.setStyle("-fx-background-color:transparent; -fx-background-radius:8 8 0 0;");
        videoArea.setPrefSize(w, h - TOP_H - BAR_H);
        videoArea.setMinSize(w, h - TOP_H - BAR_H);
        videoArea.setMaxSize(w, h - TOP_H - BAR_H);
        // 定位：本 overlay 铺满 root（StackPane 居中放卡片）→ 用 translate 把卡片左上角挪到 (x, y)。
        // 注意：刚 open() 后同脉冲调用时 overlay 尚未布局（getWidth()=0），居中偏移量算错
        // 会把卡片甩到右下——此时退回用父节点（root StackPane，启动即已布局）的尺寸。
        double ow = this.getWidth();
        double oh = this.getHeight();
        if ((ow <= 1 || oh <= 1) && this.getParent() instanceof javafx.scene.layout.Region pn) {
            ow = pn.getWidth();
            oh = pn.getHeight();
        }
        double cx = x - (ow - w) / 2.0;
        double cy = y - (oh - h) / 2.0;
        cardBox.setTranslateX(cx);
        cardBox.setTranslateY(cy);
        // 视频区内的 canvas 重新 letterbox（显示尺寸跟随视频区）
        if (player != null) {
            player.fitToArea(w, h - TOP_H - BAR_H);
        }
        // 定位一次即定死：后续滚动/缩放上报的 embedAt 全部忽略（pinned 闸）
        pinned = true;
    }

    /** 退出嵌入模式：恢复居中卡片外观。 */
    public void unembed() {
        pinned = false;
        if (!embedded) {
            return;
        }
        embedded = false;
        FfmpegFramePlayer.trace("unembed -> centered card");
        cardBox.setPrefSize(CARD_W, TOP_H + VIDEO_H + BAR_H);
        cardBox.setMinSize(CARD_W, TOP_H + VIDEO_H + BAR_H);
        cardBox.setMaxSize(CARD_W, TOP_H + VIDEO_H + BAR_H);
        cardBox.setStyle("-fx-background-color:#0d1120; -fx-background-radius:10; -fx-border-color:#243052; -fx-border-radius:10; -fx-effect: dropshadow(gaussian, rgba(0,0,0,.55), 28, 0, 0, 6);");
        topBarNode.setStyle("-fx-background-color:#10152a; -fx-background-radius:10 10 0 0;");
        videoArea.setPrefSize(CARD_W, VIDEO_H);
        videoArea.setMinSize(CARD_W, VIDEO_H);
        videoArea.setMaxSize(CARD_W, VIDEO_H);
        cardBox.setTranslateX(0);
        cardBox.setTranslateY(0);
        if (player != null) {
            player.fitToArea(CARD_W, VIDEO_H);
        }
    }

    /** 显示浮层并播放本地文件。重复调用会先停掉上一段。
     *  例外：同一文件且管线还活着（playing/paused/reloading）→ 不重启——
     *  JS 侧 3s 保底/手动 ⚡/error 事件可能对同一作品重复触发 open，
     *  旧逻辑每次都 dispose+重建 → 视频"刚点开又从 0 重播"（切换再播放体验坏的根因）。
     *  ended 不算活着：播完再点同一作品 = 从头重看，走正常重建。 */
    public void open(String path, String title) {
        if (player != null && path.equals(this.currentFile)) {
            String st = FfmpegFramePlayer.stateOf(player);
            if (!"ended".equals(st) && !"idle".equals(st) && !"error".equals(st)) {
                FfmpegFramePlayer.trace("open skip: same file active (" + st + ") — no restart");
                return;
            }
        }
        this.disposeMedia();
        this.unembed(); // 新一段播放从居中卡片开始；AppShell 有最新 rect 会立即 embedAt
        // 切源即重置进度 UI（规范对齐：HTMLMediaElement.load() 重置 currentTime/播放状态；
        //  ExoPlayer 切 MediaItem 重置 position 显示）——新管线 probe 要 0.5~2s，
        //  不复位会先显示上一段的进度/时间，"新视频从 80% 进度开始"的错乱观感
        this.seeking = false;
        this.seek.setValue(0.0);
        this.seek.setMax(1.0);
        this.timeLabel.setText("--:-- / --:--");
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
                        } else if ("reloading".equals(s)) {
                            timeLabel.setText("正在重播…");
                        }
                    });
                }
            });
            player.start();
            this.currentFile = path;
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
        if (FfmpegFramePlayer.stateOf(player).equals("reloading")) {
            return; // 重播冷启动期（画面停驻最后一帧）：不刷进度条/时间，保留"正在重播…"指示
        }
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
        this.currentFile = null;
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
