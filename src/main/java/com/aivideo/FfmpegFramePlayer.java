package com.aivideo;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 窗口内视频渲染器：用随包 ffmpeg 软解 H.264/HEVC 等任意视频 → raw RGB 帧 →
 * JavaFX Canvas。绕过本 OpenJFX 构建的媒体栈缺陷——它的 JavaFX MediaPlayer 走
 * GStreamer-lite 管线（gstreamer-lite.dll 只带封装/解析器，**不带任何视频解码器**），
 * 任何媒体都报 ERROR_MEDIA_INVALID；WebEngine 的 WebKit 媒体栈同样解不了 H.264。
 * ffmpeg 什么都能解，帧是原始像素（不转码、不降质）。
 *
 * 音频：单独一条 ffmpeg 管道输出 44.1k/16bit/双声道 PCM，经 javax.sound 播放。
 *
 * 线程模型：探测/帧读/音频写都在普通线程；Canvas 更新与回调经 Platform.runLater。
 * 暂停/跳转 = 杀掉两路进程再以 -ss 重启（ffmpeg 管道无法原地暂停，重启即可且无漂移积累）。
 */
public final class FfmpegFramePlayer {
    private static final Logger log = LoggerFactory.getLogger(FfmpegFramePlayer.class);

    public interface Listener {
        /** 回调可能来自工作线程——调用方自己切 FX 线程。 */
        void onTime(double currentSec, double durationSec);

        /** playing / paused / ended / error */
        void onState(String state, String detail);
    }

    private final String ffmpeg;
    private final String file;
    private final Canvas canvas;
    private final GraphicsContext gc;
    private final Listener listener;

    private int w = 640, h = 360;
    private double fps = 25.0;
    private double duration = Double.NaN;

    // ── 解码/渲染解耦（学 ExoPlayer：帧不经 UI 事件队列，渲染走独立 vsync 定时器）──
    // 双图池：解码线程写"另一张"，发布后渲染定时器才 draw 当前张 → 零撕裂
    private WritableImage wiA, wiB;
    private final java.util.concurrent.atomic.AtomicReference<WritableImage> published =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicLong imgSeq =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private volatile long lastRenderSeq = -1L;
    private volatile javafx.animation.AnimationTimer renderTimer;
    /** 解码分辨率上限：超过 1280 宽的源视频降到 1280 内（1080p 软解 rawdata 6MB/帧 必爆 CPU） */
    private int decodeW, decodeH;
    /** 源视频原始分辨率（scale 滤镜判断用） */
    private int srcW = 0, srcH = 0;

    private volatile Process videoProc, audioProc;
    private volatile boolean userStopped = false; // 主动暂停/关闭（进程退出≠错误）
    private volatile double segBase = 0.0;        // 本段起点（秒）
    private volatile int frameCount = 0;
    private volatile double lastReported = -1.0;

    private volatile SourceDataLine audioLine;
    private volatile boolean muted = false;
    private volatile String state = "idle";
    /** 管线原地冻结（暂停/续播不重启进程）：true 时帧/音频线程停止消费管道 →
     *  ffmpeg 管道背压 → 整条解码管线停在原地。零黑屏、零漂移积累、续播毫秒级。 */
    private volatile boolean paused = false;
    private final Object pauseLock = new Object();

    public FfmpegFramePlayer(String ffmpeg, String file, Canvas canvas, Listener listener) {
        this.ffmpeg = ffmpeg;
        this.file = file;
        this.canvas = canvas;
        this.gc = canvas.getGraphicsContext2D();
        this.listener = listener;
    }

    /** 探测（宽高/帧率/时长）→ 开画布 → 起两路 ffmpeg。调用线程：任意（内部自己切）。 */
    public void start() {
        new Thread(() -> {
            trace("start: " + file + " (ffmpeg=" + ffmpeg + ")");
            try {
                probe();
                Platform.runLater(() -> {
                    sizeCanvas();
                    installRenderLoop(); // vsync 渲染循环（FX 线程起）
                });
                runSegment(0.0, false);
            } catch (Exception e) {
                log.warn("frame player start failed: {}", file, e);
                trace("start FAILED: " + e);
                fireState("error", e.getMessage());
            }
        }, "yucl-frame-player-boot").start();
    }

    /** 探测视频流参数：优先 ffprobe（JSON），失败回退 ffmpeg -i 的 stderr 解析。 */
    private void probe() throws Exception {
        String ffprobe;
        try {
            ffprobe = FfmpegCore.ffprobe();
        } catch (Exception e) {
            ffprobe = null;
        }
        if (ffprobe != null && !ffprobe.isEmpty() && tryProbeFfprobe(ffprobe)) {
            applyDecodeCap();
            return;
        }
        probeViaFfmpegStderr();
        applyDecodeCap();
    }

    /** 软解 CPU 保护：宽超 1280 的源视频（如 1080p/4K）降到 1280 内解码。
     *  raw RGB 管道体积 = w*h*3*fps：1080p≈6MB/帧×30=180MB/s 必爆，720p≈2.8MB 可承受。
     *  显示侧仍按视频区 letterbox 缩放，清晰度在 1280 档内无感知损失。 */
    private void applyDecodeCap() {
        int ow = w, oh = h;
        srcW = ow;
        srcH = oh;
        if (ow > 1280) {
            decodeW = 1280;
            decodeH = Math.max(2, (int) Math.round(oh * 1280.0 / ow / 2) * 2);
        } else {
            decodeW = ow;
            decodeH = oh;
        }
        if ((decodeW & 1) == 1) decodeW--;
        if ((decodeH & 1) == 1) decodeH--;
        w = decodeW;
        h = decodeH;
        if (decodeW != ow || decodeH != oh) {
            log.info("decode capped {}x{} -> {}x{} (soft-decode CPU guard)", ow, oh, w, h);
        }
    }

    private boolean tryProbeFfprobe(String ffprobe) {
        try {
            ProcessBuilder pb = new ProcessBuilder(ffprobe, "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=width,height,avg_frame_rate,duration", "-of", "json", file);
            Process p = pb.start();
            String out;
            try (InputStream os = p.getInputStream()) {
                out = new String(os.readAllBytes());
            }
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(out).path("streams").path(0);
            if (n.isMissingNode()) {
                return false;
            }
            if (n.hasNonNull("width")) w = n.get("width").asInt(w);
            if (n.hasNonNull("height")) h = n.get("height").asInt(h);
            if (n.hasNonNull("duration")) duration = n.get("duration").asDouble(duration);
            String rate = n.path("avg_frame_rate").asText(n.path("r_frame_rate").asText(""));
            int slash = rate.indexOf('/');
            if (slash > 0) {
                double num = Double.parseDouble(rate.substring(0, slash));
                double den = Double.parseDouble(rate.substring(slash + 1));
                if (den > 0 && num > 0) fps = num / den;
            }
            if (w < 16 || h < 16) {
                throw new IllegalStateException("无法解析视频尺寸（" + w + "x" + h + "）");
            }
            if (fps <= 0 || !Double.isFinite(fps)) fps = 25.0;
            log.info("frame player probe (ffprobe): {} {}x{} fps={} dur={}", file, w, h, fps, duration);
            return true;
        } catch (Exception e) {
            log.info("ffprobe probe failed ({}): {}", file, e.getMessage());
            return false;
        }
    }

    private void probeViaFfmpegStderr() throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        cmd.add("-i");
        cmd.add(file);
        cmd.add("-f");
        cmd.add("null");
        cmd.add("-");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        String err;
        try (InputStream es = p.getErrorStream()) {
            err = new String(es.readAllBytes());
        }
        drainStdout(p);
        p.waitFor();
        // 信息在 stderr 里（即使 -f null 成功/失败都会打印）
        Matcher dm = Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").matcher(err);
        if (dm.find()) {
            duration = Long.parseLong(dm.group(1)) * 3600 + Long.parseLong(dm.group(2)) * 60 + Double.parseDouble(dm.group(3));
        }
        Matcher vm = Pattern.compile("Video:.*?(\\d{2,5})x(\\d{2,5})").matcher(err);
        if (vm.find()) {
            w = Integer.parseInt(vm.group(1));
            h = Integer.parseInt(vm.group(2));
        }
        Matcher fm = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*fps").matcher(err);
        if (fm.find()) {
            fps = Double.parseDouble(fm.group(1));
        } else {
            Matcher tm = Pattern.compile("(\\d+)/(\\d+)\\s*tbr").matcher(err);
            if (tm.find()) {
                fps = Double.parseDouble(tm.group(1)) / Double.parseDouble(tm.group(2));
            }
        }
        if (fps <= 0 || !Double.isFinite(fps)) fps = 25.0;
        if (w < 16 || h < 16) {
            throw new IllegalStateException("无法解析视频尺寸（" + w + "x" + h + "）: " + firstLine(err));
        }
        log.info("frame player probe (ffmpeg stderr): {} {}x{} fps={} dur={}", file, w, h, fps, duration);
    }

    private static String firstLine(String s) {
        int i = s == null ? -1 : s.indexOf('\n');
        return (i > 0 ? s.substring(0, i) : String.valueOf(s)).trim();
    }

    /** 自诊断日志：写 D:\YUCL\data\native-player.log（排查"原生播放器没反应"第一步看这里） */
    static void trace(String msg) {
        try {
            java.nio.file.Path d = DataDirs.dataDir().resolve("native-player.log");
            java.nio.file.Files.createDirectories(d.getParent());
            java.nio.file.Files.write(d,
                    (new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()) + " " + msg + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    /** 视频区尺寸（letterbox 区域）——可被 NativePlayerOverlay.fitToArea 动态更新（嵌入框时） */
    private volatile double areaW = 640.0, areaH = 318.0;

    /** 重新按视频区 letterbox 适配 canvas 显示尺寸（FX 线程调用）。 */
    public void fitToArea(double newW, double newH) {
        if (!(newW >= 40) || !(newH >= 40)) {
            return;
        }
        areaW = newW;
        areaH = newH;
        if (w >= 16 && h >= 16) {
            sizeCanvas();
        }
    }

    private void sizeCanvas() {
        // 视频区（当前 areaW×areaH，默认卡片中间 640x318）等比适配；Canvas 只显示区大小，
        // 解码图保持解码分辨率（drawImage 时缩放）——
        // 之前误把 WritableImage 建成显示区大小，setPixels(w,h) 越界异常，帧永远画不上
        double s = Math.min(areaW / w, areaH / h);
        double cw = Math.max(16, Math.round(w * s));
        double ch = Math.max(16, Math.round(h * s));
        canvas.setWidth(cw);
        canvas.setHeight(ch);
        ensureImagePool();
        gc.clearRect(0, 0, cw, ch);
        trace("canvas sized " + (int) cw + "x" + (int) ch + " (video " + w + "x" + h + ", area " + (int) areaW + "x" + (int) areaH + ")");
    }

    /** 双图池（解码分辨率）：FFMPEG 帧线程写"另一张"，发布后渲染定时器才画 —— 零撕裂。 */
    private synchronized void ensureImagePool() {
        if (wiA == null) {
            wiA = new WritableImage(w, h);
            wiB = new WritableImage(w, h);
        }
    }

    /** 安装 vsync 渲染循环（FX 线程调用）。帧就绪（imgSeq 变化）就 draw ——
     *  解码线程只写数据（跨线程安全的 PixelWriter），UI 事件/布局永不与逐帧渲染抢队列。 */
    public void installRenderLoop() {
        if (this.renderTimer != null) {
            return;
        }
        this.renderTimer = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                long seq = imgSeq.get();
                if (seq == lastRenderSeq) {
                    return;
                }
                lastRenderSeq = seq;
                WritableImage img = published.get();
                if (img != null && !userStopped) {
                    gc.drawImage(img, 0, 0, canvas.getWidth(), canvas.getHeight());
                }
            }
        };
        this.renderTimer.start();
    }

    /** 停渲染循环（FX 线程调用；close 时）。 */
    public void stopRenderLoop() {
        if (this.renderTimer != null) {
            this.renderTimer.stop();
            this.renderTimer = null;
        }
    }

    /** 从 fromSec 秒重启两路管道（fromSec=0 且 firstOpen 走首开路径）。 */
    private void runSegment(double fromSec, boolean firstOpen) {
        killProcesses();
        userStopped = false;
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
        segBase = fromSec;
        frameCount = 0;
        lastReported = -1.0;

        // 视频帧
        List<String> vc = baseCmd(fromSec);
        vc.add("-f");
        vc.add("rawvideo");
        vc.add("-pix_fmt");
        vc.add("rgb24");
        vc.add("pipe:1");
        Thread vt = new Thread(() -> {
            try {
                Process v = startQuiet(vc);
                videoProc = v;
                InputStream in = v.getInputStream();
                // 双缓冲：解码线程与 FX 线程各持一块，避免"帧被下一帧覆盖"
                byte[] bufA = new byte[w * h * 3];
                byte[] bufB = new byte[w * h * 3];
                byte[] cur = bufA;
                // 按帧率限速：不限速会"快放"完所有帧，画面一闪而过、与音频严重失步
                long intervalMs = Math.max(1L, Math.round(1000.0 / fps));
                long nextDraw = System.currentTimeMillis();
                boolean tracedFrame = false;
                // 双图池：解码线程写"另一张"（PixelWriter 跨线程安全），发布后由
                // AnimationTimer（FX 专属渲染定时器）draw —— 帧不再进 UI 事件队列排队，
                // 控制/拖动/滚动与渲染互不阻塞（ExoPlayer 式解码/渲染分离）
                ensureImagePool();
                while (!userStopped) {
                    awaitUnpaused(); // 暂停：停止读管道 → ffmpeg 背压 → 管线原地冻结（续播零重启）
                    if (userStopped) break;
                    if (in.readNBytes(cur, 0, cur.length) != cur.length) break;
                    frameCount++;
                    WritableImage target = (published.get() == wiA) ? wiB : wiA;
                    target.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getByteRgbInstance(),
                            cur, 0, w * 3); // rgb24 步长 = w*3（传 w 会撕裂成彩色竖条纹）
                    published.set(target);
                    imgSeq.incrementAndGet();
                    if (!tracedFrame) {
                        tracedFrame = true;
                        trace("first frame ready (" + w + "x" + h + " @ " + fps + "fps)");
                    }
                    cur = (cur == bufA) ? bufB : bufA;
                    nextDraw += intervalMs;
                    long sleep = nextDraw - System.currentTimeMillis();
                    if (sleep > 0) {
                        try {
                            Thread.sleep(sleep);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else {
                        nextDraw = System.currentTimeMillis(); // 落后了不追赶
                    }
                }
                int code = userStopped ? 0 : v.waitFor();
                if (!userStopped && code == 0) {
                    fireState("ended", null);
                } else if (!userStopped && code != 0) {
                    trace("video pipe exit code=" + code + " (ffmpeg error)");
                    fireState("error", "视频解码失败（ffmpeg 退出码 " + code + "）");
                }
            } catch (Exception e) {
                if (!userStopped) fireState("error", e.getMessage());
            }
        }, "yucl-frame-video");

        // 音频（44.1k/16bit/立体声 PCM；无声轨则进程立即退出、静默忽略）
        List<String> ac = baseCmd(fromSec);
        ac.add("-vn");
        ac.add("-f");
        ac.add("s16le");
        ac.add("-ar");
        ac.add("44100");
        ac.add("-ac");
        ac.add("2");
        ac.add("pipe:1");
        Thread at = new Thread(() -> {
            SourceDataLine line = null;
            try {
                Process a = startQuiet(ac);
                audioProc = a;
                AudioFormat fmt = new AudioFormat(44100.0f, 16, 2, false, false);
                line = AudioSystem.getSourceDataLine(fmt);
                audioLine = line;
                line.open(fmt, 4096);
                line.start();
                InputStream in = a.getInputStream();
                byte[] chunk = new byte[65536];
                byte[] zeros = new byte[65536];
                int n;
                while (!userStopped) {
                    boolean froze = awaitUnpaused();
                    if (userStopped) return;
                    if (froze) {
                        // 刚从冻结恢复：丢弃冻结期间 ffmpeg 写进管道的音频（背压残留，≤0.4s），
                        // 否则续播后音频会比视频"超前"——丢掉的正好是冻结段，音视频对齐
                        long avail = in.available();
                        while (avail > 0) {
                            int rd = (int) Math.min(avail, chunk.length);
                            int n2 = in.read(chunk, 0, rd);
                            if (n2 <= 0) break;
                            avail -= n2;
                        }
                    }
                    n = in.read(chunk);
                    if (n <= 0) break;
                    int wrote = 0;
                    while (wrote < n) {
                        if (userStopped) return;
                        if (muted) {
                            // 静音 = 写零数据（保持流连续，不产生漂移）
                            wrote += line.write(zeros, 0, n - wrote);
                        } else {
                            wrote += line.write(chunk, wrote, n - wrote);
                        }
                    }
                }
                int code = userStopped ? 0 : a.waitFor();
                if (!userStopped && code != 0 && firstOpen) {
                    // 首次且音频进程失败：多半是无声轨——上面已静默处理
                }
            } catch (LineUnavailableException e) {
                if (!userStopped) log.warn("audio line unavailable: {}", e.getMessage());
            } catch (Exception e) {
                if (!userStopped && firstOpen) {
                    log.info("audio pipe silent (no audio track?): {}", e.getMessage());
                }
            } finally {
                if (line != null) {
                    try {
                        if (line.isRunning()) line.stop();
                    } catch (Exception ignored) {
                    }
                    try {
                        line.close();
                    } catch (Exception ignored) {
                    }
                    // 只清掉"本段的" line：新段可能已开新 line，旧段退出不能误清
                    if (audioLine == line) {
                        audioLine = null;
                    }
                }
            }
        }, "yucl-frame-audio");

        vt.start();
        at.start();
        fireState(firstOpen ? "playing" : "playing", null);
        fireTime();
    }

    private List<String> baseCmd(double fromSec) {
        List<String> c = new ArrayList<>();
        c.add(ffmpeg);
        c.add("-hide_banner");
        c.add("-loglevel");
        c.add("error");
        if (fromSec > 0.05) {
            c.add("-ss");
            c.add(String.format(java.util.Locale.ROOT, "%.2f", fromSec));
        }
        c.add("-i");
        c.add(file);
        // 降到解码档：rawdata 管道体积 = 解码分辨率×3，是 CPU 占用大头
        if (decodeW > 0 && decodeW < srcW) {
            c.add("-vf");
            c.add("scale=" + decodeW + ":" + decodeH + ":flags=bilinear");
        }
        return c;
    }

    private static Process startQuiet(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true); // 合并到 stdout，随管道排空，不会堵
        return pb.start();
    }

    private static void drainStdout(Process p) {
        new Thread(() -> {
            try (InputStream os = p.getInputStream()) {
                os.readAllBytes();
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void killProcesses() {
        userStopped = true;
        Process v = videoProc, a = audioProc;
        if (v != null) v.destroy();
        if (a != null) a.destroy();
        videoProc = null;
        audioProc = null;
    }

    public double currentSec() {
        return segBase + frameCount / fps;
    }

    public double durationSec() {
        return duration;
    }

    private void fireTime() {
        double t = currentSec();
        lastReported = t;
        listener.onTime(t, duration);
    }

    private void fireState(String s, String detail) {
        state = s;
        listener.onState(s, detail);
    }

    /** 等待解冻（暂停线程门）。返回 true = 刚才确实冻结过（调用方可做"恢复后"处理）。 */
    private boolean awaitUnpaused() {
        boolean froze = false;
        synchronized (pauseLock) {
            while (paused && !userStopped) {
                froze = true;
                try {
                    pauseLock.wait(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return froze;
    }

    /** 播放中 → 暂停；否则（含结束/暂停）→ 从头或暂停点继续。 */
    public void toggle() {
        if (state.equals("playing")) {
            pause();
        } else if (state.equals("paused")) {
            resume();
        } else if (state.equals("ended")) {
            // 从头重播：冷启动管线放后台，不卡 UI
            new Thread(() -> runSegment(0.0, false), "yucl-frame-restart").start();
        }
    }

    /** 原地冻结（零进程重启）：暂停=线程停止消费管道 → ffmpeg 背压整条管线停在原地。
     *  续播（resume）时直接解冻——无黑屏、无冷启动、无漂移积累。 */
    public void pause() {
        if (!state.equals("playing")) return;
        double t = currentSec();
        synchronized (pauseLock) {
            paused = true;
            pauseLock.notifyAll();
        }
        SourceDataLine l = audioLine;
        if (l != null) {
            try {
                if (l.isRunning()) l.stop();
            } catch (Exception ignored) {
            }
        }
        state = "paused";
        listener.onState("paused", null);
        listener.onTime(t, duration);
        trace("pause (pipeline frozen @ " + String.format(java.util.Locale.ROOT, "%.2f", t) + ")");
    }

    /** 续播：管线冻结中 → 解冻即播（毫秒级无缝）；管线活着（后台重启中）→ 只置状态；
     *  管线不存在/已结束 → 后台冷启动。 */
    public void resume() {
        if (!state.equals("paused") && !state.equals("ended")) return;
        Process v = videoProc;
        if (paused) {
            synchronized (pauseLock) {
                paused = false;
                pauseLock.notifyAll();
            }
            SourceDataLine l = audioLine;
            if (l != null) {
                try {
                    l.start(); // 从暂停点继续；音频线程会丢掉冻结段的背压残留，对齐无漂移
                } catch (Exception ignored) {
                }
            }
            state = "playing";
            listener.onState("playing", null);
            trace("resume (seamless, pipeline kept)");
        } else if (v != null && v.isAlive()) {
            // 后台 seek/重播重启中（管线已活）→ 只置 playing，不打扰
            state = "playing";
            listener.onState("playing", null);
        } else {
            double from = state.equals("ended") ? 0.0 : segBase;
            new Thread(() -> runSegment(from, false), "yucl-frame-restart").start();
            trace("resume (cold restart @ " + from + ")");
        }
    }

    /** 跳到 t 秒（≥0）。无缝实现：
     *  1) 冻结/停掉当前管线（canvas 保留旧帧，不黑屏）；
     *  2) 单帧闪帧：独立 ffmpeg 进程取目标位置一帧立即画上去（~0.3s 用户就看见目标画面）；
     *  3) 播放态同时重启全管线（-ss t 视频+音频），首帧到达时自然覆盖闪帧（同一帧，无跳变）。 */
    public void seekTo(double t) {
        if (!Double.isFinite(t) || t < 0) t = 0;
        if (Double.isFinite(duration) && t > duration) t = duration;
        boolean wasPlaying = state.equals("playing");
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
        killProcesses();
        state = "paused";
        segBase = t;
        frameCount = 0;
        fireTime();
        flashFrameAt(t);
        if (wasPlaying) {
            // 全管线重启放后台（进程冷启动不卡 UI）；闪帧已到，首帧到达即无缝接管
            double t0 = t;
            new Thread(() -> runSegment(t0, false), "yucl-frame-seek").start();
            trace("seek (seamless) -> " + String.format(java.util.Locale.ROOT, "%.2f", t));
        } else {
            listener.onState("paused", null);
            trace("seek (paused) -> " + String.format(java.util.Locale.ROOT, "%.2f", t));
        }
    }

    /** 在 t 秒处取一帧立即画到 canvas（独立短命 ffmpeg 进程；用于 seek 即时反馈）。 */
    private void flashFrameAt(double t) {
        if (w < 16 || h < 16) {
            return;
        }
        int w0 = w, h0 = h;
        new Thread(() -> {
            try {
                List<String> c = baseCmd(t);
                c.add("-frames:v");
                c.add("1");
                c.add("-f");
                c.add("rawvideo");
                c.add("-pix_fmt");
                c.add("rgb24");
                c.add("pipe:1");
                Process p = startQuiet(c);
                InputStream in = p.getInputStream();
                byte[] buf = new byte[w0 * h0 * 3];
                int total = 0;
                int r;
                long deadline = System.currentTimeMillis() + 3000L;
                while (total < buf.length && (r = in.read(buf, total, buf.length - total)) > 0
                        && System.currentTimeMillis() < deadline) {
                    total += r;
                }
                p.destroy();
                if (total == buf.length) {
                    // 写进"另一张"并发布：渲染定时器下一 vsync 画出 —— 与主帧同一条渲染路径
                    ensureImagePool();
                    WritableImage target = (published.get() == wiA) ? wiB : wiA;
                    target.getPixelWriter().setPixels(0, 0, w0, h0,
                            PixelFormat.getByteRgbInstance(), buf, 0, w0 * 3);
                    published.set(target);
                    imgSeq.incrementAndGet();
                    trace("flash-frame ready @ " + String.format(java.util.Locale.ROOT, "%.2f", t));
                }
            } catch (Exception ignored) {
            }
        }, "yucl-frame-flash").start();
    }

    public void setMute(boolean m) {
        muted = m; // 写循环每 64KB 块检查一次，静音即时生效（写零数据，不漂移）
    }

    /** 当前播放状态（idle/playing/paused/ended/error）——按钮埋点用 */
    public static String stateOf(FfmpegFramePlayer p) {
        try {
            java.lang.reflect.Field f = FfmpegFramePlayer.class.getDeclaredField("state");
            f.setAccessible(true);
            return String.valueOf(f.get(p));
        } catch (Exception e) {
            return "?";
        }
    }

    public void close() {
        userStopped = true;
        killProcesses();
        synchronized (pauseLock) {
            paused = false; // 释放冻结中的帧/音频线程，让它们退出循环
            pauseLock.notifyAll();
        }
        state = "idle";
        stopRenderLoop();
        trace("close");
        GraphicsContext g = gc;
        Platform.runLater(() -> g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight()));
    }
}
