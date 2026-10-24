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
    private WritableImage wi;
    private PixelWriter pw;

    private volatile Process videoProc, audioProc;
    private volatile boolean userStopped = false; // 主动暂停/关闭（进程退出≠错误）
    private volatile double segBase = 0.0;        // 本段起点（秒）
    private volatile int frameCount = 0;
    private volatile double lastReported = -1.0;

    private SourceDataLine audioLine;
    private volatile boolean muted = false;
    private volatile String state = "idle";

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
                Platform.runLater(() -> sizeCanvas());
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
            return;
        }
        probeViaFfmpegStderr();
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

    private void sizeCanvas() {
        // 视频区（卡片中间 640x318）等比适配；Canvas 只显示区大小，
        // WritableImage 保持视频原生分辨率（drawImage 时缩放）——
        // 之前误把 WritableImage 建成显示区大小，setPixels(w,h) 越界异常，帧永远画不上
        double areaW = 640.0, areaH = 318.0;
        double s = Math.min(areaW / w, areaH / h);
        double cw = Math.max(16, Math.round(w * s));
        double ch = Math.max(16, Math.round(h * s));
        canvas.setWidth(cw);
        canvas.setHeight(ch);
        wi = new WritableImage(w, h);
        pw = wi.getPixelWriter();
        gc.clearRect(0, 0, cw, ch);
        trace("canvas sized " + (int) cw + "x" + (int) ch + " (video " + w + "x" + h + ")");
    }

    /** 从 fromSec 秒重启两路管道（fromSec=0 且 firstOpen 走首开路径）。 */
    private void runSegment(double fromSec, boolean firstOpen) {
        killProcesses();
        userStopped = false;
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
                while (!userStopped && in.readNBytes(cur, 0, cur.length) == cur.length) {
                    frameCount++;
                    if (pw != null) {
                        final byte[] snap = cur;
                        Platform.runLater(() -> {
                            // rgb24 = 3 字节/像素 → 扫描行步长是 w*3 字节（不是 w，
                            // 传 w 会让每行错位 2/3 行宽 → 画面撕裂成彩色竖条纹）
                            pw.setPixels(0, 0, w, h, PixelFormat.getByteRgbInstance(), snap, 0, w * 3);
                            gc.drawImage(wi, 0, 0, canvas.getWidth(), canvas.getHeight());
                        });
                        if (!tracedFrame) {
                            tracedFrame = true;
                            trace("first frame enqueued (" + w + "x" + h + " @ " + fps + "fps)");
                        }
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
            try {
                Process a = startQuiet(ac);
                audioProc = a;
                AudioFormat fmt = new AudioFormat(44100.0f, 16, 2, false, false);
                SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
                audioLine = line;
                line.open(fmt, 4096);
                line.start();
                InputStream in = a.getInputStream();
                byte[] chunk = new byte[65536];
                byte[] zeros = new byte[65536];
                int n;
                while (!userStopped && (n = in.read(chunk)) > 0) {
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
                try {
                    if (audioLine != null && audioLine.isRunning()) audioLine.stop();
                } catch (Exception ignored) {
                }
                try {
                    if (audioLine != null) audioLine.close();
                } catch (Exception ignored) {
                }
                audioLine = null;
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

    /** 播放中 → 暂停；否则（含结束/暂停）→ 从头或暂停点继续。 */
    public void toggle() {
        if (state.equals("playing")) {
            pause();
        } else if (state.equals("paused") || state.equals("ended")) {
            resume();
        }
    }

    public void pause() {
        if (!state.equals("playing")) return;
        double t = currentSec();
        killProcesses();
        state = "paused";
        listener.onState("paused", null);
        listener.onTime(t, duration);
    }

    public void resume() {
        if (state.equals("ended")) {
            runSegment(0.0, false);
        } else {
            runSegment(currentSec(), false);
        }
    }

    /** 跳到 t 秒（≥0）；播放态下无缝续播。 */
    public void seekTo(double t) {
        boolean wasPlaying = state.equals("playing");
        killProcesses();
        state = "paused";
        if (wasPlaying) runSegment(t, false);
        else {
            // 暂停态：只挪到 t，等用户点播放
            segBase = t;
            frameCount = 0;
            fireTime();
        }
    }

    public void setMute(boolean m) {
        muted = m; // 写循环每 64KB 块检查一次，静音即时生效（写零数据，不漂移）
    }

    public void close() {
        userStopped = true;
        killProcesses();
        state = "idle";
        trace("close");
        GraphicsContext g = gc;
        Platform.runLater(() -> g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight()));
    }
}
