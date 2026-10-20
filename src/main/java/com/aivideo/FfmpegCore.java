/*
 * Decompiled with CFR 0.152.
 */
package com.aivideo;

import com.aivideo.DataDirs;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class FfmpegCore {
    public static final Map<String, int[]> GEOM = new LinkedHashMap<String, int[]>();
    public static final Map<String, int[]> GEOM_720 = new LinkedHashMap<String, int[]>();
    private static final Pattern SLUG_RE;
    public static final Map<String, String> VOCES;
    private static final String ffmpegBinary;
    private static final String ffprobeBinary;
    private static volatile Process currentFfmpeg;
    public static final Map<String, String[]> CHAR_POS;

    private FfmpegCore() {
    }

    public static Path hermesHome() {
        String env = System.getenv("HERMES_HOME");
        if (env != null && !env.isBlank()) {
            return Paths.get(env, new String[0]);
        }
        Path d = Paths.get("D:\\hermes", new String[0]);
        if (Files.isDirectory(d, new LinkOption[0])) {
            return d;
        }
        return Paths.get(System.getProperty("user.home"), ".hermes");
    }

    public static Path assetRoot() {
        return DataDirs.cacheRoot();
    }

    public static int[] geom(String orientation) {
        return FfmpegCore.geom(orientation, "1080p");
    }

    public static int[] geom(String orientation, String quality) {
        int[] nArray;
        Map<String, int[]> t;
        boolean low = "720p".equalsIgnoreCase(quality == null ? "" : quality.trim());
        Map<String, int[]> map = t = low ? GEOM_720 : GEOM;
        if (low) {
            int[] nArray2 = new int[2];
            nArray2[0] = 720;
            nArray = nArray2;
            nArray2[1] = 1280;
        } else {
            int[] nArray3 = new int[2];
            nArray3[0] = 1080;
            nArray = nArray3;
            nArray3[1] = 1920;
        }
        int[] dflt = nArray;
        return t.getOrDefault(orientation == null ? "vertical" : orientation.toLowerCase(Locale.ROOT), dflt);
    }

    public static String slug(String topic, int n) {
        String s = SLUG_RE.matcher(topic == null ? "video" : topic.trim()).replaceAll("-");
        s = s.substring(0, Math.min(n, s.length())).replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "video" : s;
    }

    public static List<Map<String, String>> voiceCatalog() {
        ArrayList<Map<String, String>> v = new ArrayList<Map<String, String>>();
        v.add(FfmpegCore.voice("zh-CN-YunxiNeural", "\u7537\u00b7\u4e91\u5e0c\uff08\u6c89\u7a33\uff09", "zh-CN"));
        v.add(FfmpegCore.voice("zh-CN-YunyangNeural", "\u7537\u00b7\u4e91\u626c\uff08\u89e3\u8bf4\uff09", "zh-CN"));
        v.add(FfmpegCore.voice("zh-CN-YunjianNeural", "\u7537\u00b7\u4e91\u5065\uff08\u7535\u53f0\uff09", "zh-CN"));
        v.add(FfmpegCore.voice("zh-CN-YunfengNeural", "\u7537\u00b7\u4e91\u98ce\uff08\u60c5\u611f\uff09", "zh-CN"));
        v.add(FfmpegCore.voice("zh-CN-XiaoxiaoNeural", "\u5973\u00b7\u6653\u6653\uff08\u4eb2\u548c\uff09", "zh-CN"));
        v.add(FfmpegCore.voice("zh-CN-XiaoyiNeural", "\u5973\u00b7\u6653\u4f0a\uff08\u6d3b\u6cfc\uff09", "zh-CN"));
        v.add(FfmpegCore.voice("zh-CN-XiaoshuangNeural", "\u5973\u00b7\u6653\u53cc\uff08\u4fcf\u76ae\uff09", "zh-CN"));
        v.add(FfmpegCore.voice("zh-CN-YatingNeural", "\u5973\u00b7\u96c5\u5a77\uff08\u67d4\u548c\uff09", "zh-CN"));
        v.add(FfmpegCore.voice("zh-CN-liaoning-XiaobeiNeural", "\u5973\u00b7\u4e1c\u5317\u00b7\u5c0f\u5317", "zh-CN"));
        v.add(FfmpegCore.voice("yue-HK-HoYeuNeural", "\u7ca4\u00b7\u6653\u76c8", "yue"));
        v.add(FfmpegCore.voice("en-US-AndrewMultilingualNeural", "\u7537\u00b7Andrew", "en-US"));
        v.add(FfmpegCore.voice("en-US-AriaNeural", "\u5973\u00b7Aria", "en-US"));
        v.add(FfmpegCore.voice("en-GB-SoniaNeural", "\u5973\u00b7Sonia\uff08\u82f1\u97f3\uff09", "en-GB"));
        v.add(FfmpegCore.voice("ja-JP-NanamiNeural", "\u5973\u00b7\u4e03\u6d77\uff08\u65e5\uff09", "ja-JP"));
        return v;
    }

    private static Map<String, String> voice(String value, String label, String lang) {
        LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
        m.put("value", value);
        m.put("label", label);
        m.put("lang", lang);
        return m;
    }

    public static String resolveLocalAudio(int i, String role, Map<String, String> localAudio) {
        if (localAudio == null || localAudio.isEmpty()) {
            return null;
        }
        ArrayList<String> keys = new ArrayList<String>();
        keys.add("line_" + (i + 1));
        if (role != null && !role.isEmpty()) {
            keys.add(role);
        }
        keys.add("_default");
        for (String string : keys) {
            String p = (String) localAudio.get(string);
            if (p == null || p.isEmpty()) continue;
            if (Files.exists(Paths.get(p, new String[0]), new LinkOption[0])) {
                return p;
            }
            System.err.println("[ai-video] local_audio[" + string + "] not found on disk: " + p);
        }
        return null;
    }

    private static String resolveFfmpegTool(String exe, String bareName) {
        ArrayList<Path> candidates = new ArrayList<Path>();
        candidates.add(Paths.get(System.getProperty("user.dir"), "ffmpeg", exe));
        Path fromJar = FfmpegCore.ffmpegFromJarLocation(exe);
        if (fromJar != null) {
            candidates.add(fromJar);
        }
        for (Path p : candidates) {
            if (!Files.isRegularFile(p, new LinkOption[0])) continue;
            return p.toString();
        }
        return FfmpegCore.findOnPath(bareName);
    }

    private static Path ffmpegFromJarLocation(String tool) {
        try {
            Path jar;
            Path root;
            Path candidate;
            int bang;
            URL loc = FfmpegCore.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc == null) {
                return null;
            }
            String pathPart = loc.toString();
            for (String pre : new String[]{"jar:nested:", "jar:file:", "nested:", "file:"}) {
                if (!pathPart.startsWith(pre)) continue;
                pathPart = pathPart.substring(pre.length());
                break;
            }
            if ((bang = pathPart.indexOf(33)) >= 0) {
                pathPart = pathPart.substring(0, bang);
            }
            if (pathPart.matches("^/[A-Za-z]:/.*")) {
                pathPart = pathPart.substring(1);
            }
            return Files.isRegularFile(candidate = (root = (jar = Paths.get(pathPart, new String[0])).toAbsolutePath().getParent().getParent()).resolve("ffmpeg").resolve(tool), new LinkOption[0]) ? candidate : null;
        }
        catch (Exception e) {
            return null;
        }
    }

    private static String firstRegularFile(Path ... candidates) {
        for (Path p : candidates) {
            if (p == null || !Files.isRegularFile(p, new LinkOption[0])) continue;
            return p.toString();
        }
        return candidates.length > 0 && candidates[0] != null ? candidates[0].toString() : "";
    }

    public static String ffmpeg() {
        return ffmpegBinary;
    }

    public static String ffprobe() {
        return ffprobeBinary;
    }

    private static String findOnPath(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String exe = name + ".exe";
            for (String dir : pathEnv.split(File.pathSeparator)) {
                Path p;
                if (dir.isBlank() || !Files.isRegularFile(p = Paths.get(dir, new String[0]).resolve(exe), new LinkOption[0])) continue;
                return p.toString();
            }
        }
        return name;
    }

    public static Map<String, Object> runFfmpeg(List<String> args, String workdir) {
        String ff = FfmpegCore.ffmpeg();
        if (ff == null || ff.isEmpty()) {
            return Map.of("ok", false, "error", "ffmpeg not found on PATH");
        }
        ArrayList<String> cmd = new ArrayList<String>();
        cmd.add(ff);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-y");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workdir != null && !workdir.isEmpty()) {
            pb.directory(new File(workdir));
        }
        pb.redirectErrorStream(false);
        try {
            Process p;
            currentFfmpeg = p = pb.start();
            StringBuilder err = new StringBuilder();
            Thread errDrain = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));){
                    String line;
                    while ((line = r.readLine()) != null && err.length() < 20000) {
                        err.append(line).append('\n');
                    }
                }
                catch (Exception exception) {
                    // empty catch block
                }
            });
            errDrain.start();
            try (InputStream in = p.getInputStream();){
                // Drain the whole stdout stream so the pipe never fills and
                // ffmpeg does not stall (the old single 8KB read + close
                // could break the pipe if ffmpeg ever printed more).
                byte[] sink = new byte[8192];
                while (in.read(sink) > 0) {
                }
            }
            boolean done = p.waitFor(900L, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return Map.of("ok", false, "error", "ffmpeg timed out (900s)");
            }
            errDrain.join(5000L);
            if (currentFfmpeg == p) {
                currentFfmpeg = null;
            }
            if (p.exitValue() != 0) {
                String tail = err.toString().trim();
                return Map.of("ok", false, "error", tail.length() > 800 ? tail.substring(tail.length() - 800) : tail);
            }
            return Map.of("ok", true);
        }
        catch (Exception exc) {
            if (currentFfmpeg != null) {
                currentFfmpeg = null;
            }
            return Map.of("ok", false, "error", "ffmpeg launch failed: " + exc);
        }
    }

    public static void killCurrentFfmpeg() {
        Process p = currentFfmpeg;
        if (p != null && p.isAlive()) {
            try {
                p.destroyForcibly();
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
    }

    public static Double ffprobeDuration(String path) {
        String fp = FfmpegCore.ffprobe();
        if (fp == null || fp.isEmpty() || path == null) {
            return null;
        }
        try {
            String out;
            Process p = new ProcessBuilder(fp, "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", path).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));){
                String line;
                StringBuilder sb = new StringBuilder();
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                out = sb.toString().trim();
            }
            p.waitFor(30L, TimeUnit.SECONDS);
            String first = out.split("\n", 2)[0].trim();
            return first.isEmpty() ? null : Double.valueOf(Double.parseDouble(first));
        }
        catch (Exception e) {
            return null;
        }
    }

    public static Map<String, Object> composeLocal(String bg, String ch1, String ch2, int w, int h, double dur, double charScale, String charPos, int fps, String audio, String out) {
        String[] p2;
        String[] p1;
        boolean hasC2;
        if (FfmpegCore.ffmpeg() == null) {
            return Map.of("ok", false, "error", "ffmpeg not found on PATH");
        }
        dur = Math.max(1.0, dur);
        int ch = (int)Math.round(charScale * (double)h);
        boolean hasBg = bg != null && !bg.isEmpty() && Files.exists(Paths.get(bg, new String[0]), new LinkOption[0]);
        boolean hasC1 = ch1 != null && !ch1.isEmpty() && Files.exists(Paths.get(ch1, new String[0]), new LinkOption[0]);
        boolean bl = hasC2 = ch2 != null && !ch2.isEmpty() && Files.exists(Paths.get(ch2, new String[0]), new LinkOption[0]);
        if (!(hasBg || hasC1 || hasC2)) {
            String hint = (String)(bg != null && !bg.isEmpty() ? "\u80cc\u666f\u56fe " + bg + " \u4e0d\u5b58\u5728" : "") + (String)(!hasC1 && ch1 != null && !ch1.isEmpty() ? "\uff1b\u4eba\u7269\u56fe " + ch1 + " \u4e0d\u5b58\u5728" : "");
            return Map.of("ok", false, "error", "\u9700\u8981\u80cc\u666f\u56fe\u6216\u4eba\u7269\u56fe\u81f3\u5c11\u5176\u4e00\uff08" + (String)(hint.isEmpty() ? "\u5168\u90e8\u56fe\u7247\u8def\u5f84\u7f3a\u5931" : hint) + "\uff09");
        }
        if (hasC1 && hasC2) {
            p1 = CHAR_POS.get("bottom-left");
            p2 = CHAR_POS.get("bottom-right");
        } else if (hasC1) {
            p1 = CHAR_POS.getOrDefault(charPos, CHAR_POS.get("bottom-left"));
            p2 = null;
        } else {
            p1 = null;
            p2 = CHAR_POS.getOrDefault(charPos, CHAR_POS.get("bottom-left"));
        }
        ArrayList<String[]> imgInputs = new ArrayList<String[]>();
        if (hasBg) {
            imgInputs.add(new String[]{"bg", bg});
        }
        if (hasC1) {
            imgInputs.add(new String[]{"c1", ch1});
        }
        if (hasC2) {
            imgInputs.add(new String[]{"c2", ch2});
        }
        LinkedHashMap<String, Integer> idx = new LinkedHashMap<String, Integer>();
        ArrayList<String> args = new ArrayList<String>();
        for (String[] in : imgInputs) {
            args.add("-loop");
            args.add("1");
            args.add("-t");
            args.add(String.format(Locale.ROOT, "%.3f", dur));
            args.add("-i");
            args.add(in[1]);
            idx.put(in[0], idx.size());
        }
        int audioIdx = imgInputs.size();
        if (audio != null && Files.exists(Paths.get(audio, new String[0]), new LinkOption[0])) {
            args.add("-i");
            args.add(audio);
        } else {
            args.add("-f");
            args.add("lavfi");
            args.add("-t");
            args.add(String.format(Locale.ROOT, "%.3f", dur));
            args.add("-i");
            args.add("anullsrc=r=48000:cl=stereo");
        }
        String pan = "x='(in_w-" + w + ")*t/" + String.format(Locale.ROOT, "%.3f", dur) + "':y='(in_h-" + h + ")*t/" + String.format(Locale.ROOT, "%.3f", dur) + "'";
        ArrayList<String> fc = new ArrayList<String>();
        if (hasBg) {
            fc.add("[" + idx.get("bg") + ":v]scale=" + (int)((double)w * 1.15) + ":" + (int)((double)h * 1.15) + ":force_original_aspect_ratio=increase,crop=" + w + ":" + h + ":" + pan + ",setsar=1,format=yuv420p[base]");
        } else if (hasC1) {
            fc.add("[" + idx.get("c1") + ":v]scale=" + (int)((double)w * 1.25) + ":" + (int)((double)h * 1.25) + ":force_original_aspect_ratio=increase,crop=" + w + ":" + h + ",boxblur=24:6,setsar=1,format=yuv420p[base]");
        } else {
            fc.add("[" + idx.get("c2") + ":v]scale=" + (int)((double)w * 1.25) + ":" + (int)((double)h * 1.25) + ":force_original_aspect_ratio=increase,crop=" + w + ":" + h + ",boxblur=24:6,setsar=1,format=yuv420p[base]");
        }
        String prev = "base";
        if (hasC1) {
            fc.add("[" + idx.get("c1") + ":v]scale=-2:" + ch + ":force_original_aspect_ratio=decrease,format=rgba[c1]");
            fc.add("[" + prev + "][c1]overlay=" + p1[0] + ":" + p1[1] + ":shortest=1[o1]");
            prev = "o1";
        }
        if (hasC2) {
            fc.add("[" + idx.get("c2") + ":v]scale=-2:" + ch + ":force_original_aspect_ratio=decrease,format=rgba[c2]");
            fc.add("[" + prev + "][c2]overlay=" + p2[0] + ":" + p2[1] + ":shortest=1[o2]");
            prev = "o2";
        }
        fc.add("[" + prev + "]format=yuv420p[vout]");
        fc.add("[" + audioIdx + ":a]aresample=48000,atrim=0:" + String.format(Locale.ROOT, "%.3f", dur) + ",asetpts=PTS-STARTPTS[aout]");
        args.add("-filter_complex");
        args.add(String.join(";", fc));
        args.add("-map");
        args.add("[vout]");
        args.add("-map");
        args.add("[aout]");
        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add("veryfast");
        args.add("-crf");
        args.add("23");
        args.add("-pix_fmt");
        args.add("yuv420p");
        // pin h264 to MAIN profile: JavaFX WebView's media pipeline
        // rejects HIGH profile (ERROR_MEDIA_INVALID -> black player).
        args.add("-profile:v");
        args.add("main");
        args.add("-r");
        args.add(String.valueOf(fps));
        args.add("-c:a");
        args.add("aac");
        args.add("-ar");
        args.add("48000");
        args.add("-ac");
        args.add("2");
        args.add("-t");
        args.add(String.format(Locale.ROOT, "%.3f", dur));
        // faststart: moov box up front so the WebView player can start
        // rendering before the whole file has been downloaded.
        args.add("-movflags");
        args.add("+faststart");
        args.add(out);
        Map<String, Object> p = FfmpegCore.runFfmpeg(args, null);
        boolean ok = Boolean.TRUE.equals(p.get("ok")) && Files.exists(Paths.get(out, new String[0]), new LinkOption[0]);
        return ok ? Map.of("ok", true) : Map.of("ok", false, "error", p.getOrDefault("error", "local compose failed"));
    }

    public static Map<String, Object> normalize(String clip, String audio, String out, int w, int h, int fps, double dur) {
        if (FfmpegCore.ffmpeg() == null) {
            return Map.of("ok", false, "error", "ffmpeg not found");
        }
        dur = Math.max(1.0, dur);
        ArrayList<String> args = new ArrayList<String>();
        args.add("-i");
        args.add(clip);
        int aidx = 1;
        if (audio != null && Files.exists(Paths.get(audio, new String[0]), new LinkOption[0])) {
            args.add("-i");
            args.add(audio);
        } else {
            args.add("-f");
            args.add("lavfi");
            args.add("-t");
            args.add(String.format(Locale.ROOT, "%.3f", dur));
            args.add("-i");
            args.add("anullsrc=r=48000:cl=stereo");
        }
        String fc = "[0:v]scale=" + w + ":" + h + ":force_original_aspect_ratio=decrease,pad=" + w + ":" + h + ",setsar=1,fps=" + fps + ",format=yuv420p[v];[" + aidx + ":a]aresample=48000,atrim=0:" + String.format(Locale.ROOT, "%.3f", dur) + ",asetpts=PTS-STARTPTS[a]";
        args.add("-filter_complex");
        args.add(fc);
        args.add("-map");
        args.add("[v]");
        args.add("-map");
        args.add("[a]");
        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add("veryfast");
        args.add("-crf");
        args.add("23");
        args.add("-pix_fmt");
        args.add("yuv420p");
        args.add("-c:a");
        args.add("aac");
        args.add("-ar");
        args.add("48000");
        args.add("-ac");
        args.add("2");
        args.add("-t");
        args.add(String.format(Locale.ROOT, "%.3f", dur));
        // pin h264 to MAIN profile + faststart (see composeLocal comment)
        args.add("-profile:v");
        args.add("main");
        args.add("-movflags");
        args.add("+faststart");
        args.add(out);
        FfmpegCore.runFfmpeg(args, null);
        boolean ok = Files.exists(Paths.get(out, new String[0]), new LinkOption[0]);
        return ok ? Map.of("ok", true) : Map.of("ok", false, "error", "normalize failed");
    }

    public static Map<String, Object> concat(List<String> clips, String out) throws Exception {
        String lst = Paths.get(out, new String[0]).toAbsolutePath().getParent() + "/concat.txt";
        StringBuilder sb = new StringBuilder();
        for (String c : clips) {
            sb.append("file '").append(c.replace('\\', '/')).append("'\n");
        }
        Files.writeString(Path.of(lst, new String[0]), sb.toString(), StandardCharsets.UTF_8);
        ArrayList<String> args = new ArrayList<String>(List.of("-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", out));
        return FfmpegCore.runFfmpeg(args, null);
    }

    public static Map<String, Object> mixBgm(String base, String bgm, String out, double vol) {
        List<String> args = List.of("-i", base, "-stream_loop", "-1", "-i", bgm, "-filter_complex",
                "[1:a]atrim=0,volume=" + vol + ",asetpts=PTS-STARTPTS[bg];[0:a][bg]amix=inputs=2:duration=first:dropout_transition=0.5:normalize=0[a]",
                "-map", "0:v", "-map", "[a]", "-c:v", "copy", "-c:a", "aac", "-ar", "48000", "-ac", "2",
                "-movflags", "+faststart", out);
        return FfmpegCore.runFfmpeg(args, null);
    }

    public static Map<String, Object> burnSrt(String base, String srt, String out) {
        return FfmpegCore.burnSrt(base, srt, out, 16);
    }

    public static Map<String, Object> burnSrt(String base, String srt, String out, int fontSize) {
        String baseName = Paths.get(srt, new String[0]).getFileName().toString();
        String vf = "subtitles='" + baseName + "':force_style='FontSize=" + fontSize + ",Outline=2,Shadow=0,MarginV=48'";
        // re-encodes video -> pin h264 MAIN profile + faststart so the
        // final file stays playable inside the JavaFX WebView player.
        List<String> args = List.of("-i", base, "-vf", vf, "-c:v", "libx264", "-preset", "veryfast",
                "-crf", "23", "-profile:v", "main", "-movflags", "+faststart", "-c:a", "copy", out);
        return FfmpegCore.runFfmpeg(args, Paths.get(srt, new String[0]).toAbsolutePath().getParent().toString());
    }

    public static void writeSrt(List<Map<String, Object>> shots, String out) throws Exception {
        StringBuilder sb = new StringBuilder();
        double t = 0.0;
        int i = 1;
        for (Map<String, Object> sc : shots) {
            String cap = FfmpegCore.str(sc.get("caption")).trim();
            if (!cap.isEmpty()) {
                sb.append(i).append('\n').append(FfmpegCore.srtTs(t)).append(" --> ").append(FfmpegCore.srtTs(t + FfmpegCore.num(sc.get("duration")))).append('\n').append(cap).append("\n\n");
                ++i;
            }
            t += FfmpegCore.num(sc.get("duration"));
        }
        Files.writeString(Path.of(out, new String[0]), sb.toString(), StandardCharsets.UTF_8);
    }

    private static String srtTs(double sec) {
        long ms = Math.round(sec * 1000.0);
        long h = ms / 3600000L;
        long m = (ms %= 3600000L) / 60000L;
        long s = (ms %= 60000L) / 1000L;
        long millis = ms % 1000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, millis);
    }

    public static String findCjkFont() {
        String[] cands;
        for (String c : cands = new String[]{"C:\\Windows\\Fonts\\msyhbd.ttc", "C:\\Windows\\Fonts\\msyh.ttc", "C:\\Windows\\Fonts\\simhei.ttf", "C:\\Windows\\Fonts\\simkai.ttf", "C:\\Windows\\Fonts\\Dengb.ttf", "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc", "/System/Library/Fonts/PingFang.ttc"}) {
            if (!Files.exists(Path.of(c, new String[0]), new LinkOption[0])) continue;
            return c;
        }
        return null;
    }

    public static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    public static double num(Object o) {
        if (o instanceof Number) {
            Number n = (Number)o;
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        }
        catch (Exception e) {
            return 0.0;
        }
    }

    public static int intOf(Object o, int dflt) {
        if (o instanceof Number) {
            Number n = (Number)o;
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        }
        catch (Exception e) {
            return dflt;
        }
    }

    public static String safeStem(String filename, String dflt) {
        String fn = (filename == null ? "" : filename).replace('\\', '/');
        int slash = fn.lastIndexOf(47);
        String base = slash >= 0 ? fn.substring(slash + 1) : fn;
        int dot = base.lastIndexOf(46);
        String stem = dot >= 0 ? base.substring(0, dot) : base;
        String safe = stem.replaceAll("[^0-9A-Za-z\u4e00-\u9fff._-]+", "_").replaceAll("^[._]+|[._]+$", "");
        return safe.isEmpty() ? dflt : safe;
    }

    static {
        GEOM.put("vertical", new int[]{1080, 1920});
        GEOM.put("9:16", new int[]{1080, 1920});
        GEOM.put("landscape", new int[]{1920, 1080});
        GEOM.put("16:9", new int[]{1920, 1080});
        GEOM.put("square", new int[]{1080, 1080});
        GEOM.put("1:1", new int[]{1080, 1080});
        GEOM_720.put("vertical", new int[]{720, 1280});
        GEOM_720.put("9:16", new int[]{720, 1280});
        GEOM_720.put("landscape", new int[]{1280, 720});
        GEOM_720.put("16:9", new int[]{1280, 720});
        GEOM_720.put("square", new int[]{720, 720});
        GEOM_720.put("1:1", new int[]{720, 720});
        SLUG_RE = Pattern.compile("[^\\w\u4e00-\u9fff]+");
        VOCES = Map.of("zh", "zh-CN-YunxiNeural", "zh-CN", "zh-CN-YunxiNeural", "zh-TW", "zh-TW-GuanxinNeural", "en", "en-US-AndrewMultilingualNeural", "ja", "ja-JP-NanamiNeural");
        String dir = System.getenv("AI_VIDEO_FFMPEG_DIR");
        if (dir != null && !dir.isBlank()) {
            ffmpegBinary = FfmpegCore.firstRegularFile(Paths.get(dir, "ffmpeg.exe"), Paths.get(dir, "ffmpeg"));
            ffprobeBinary = FfmpegCore.firstRegularFile(Paths.get(dir, "ffprobe.exe"), Paths.get(dir, "ffprobe"));
        } else {
            ffmpegBinary = FfmpegCore.resolveFfmpegTool("ffmpeg.exe", "ffmpeg");
            ffprobeBinary = FfmpegCore.resolveFfmpegTool("ffprobe.exe", "ffprobe");
        }
        CHAR_POS = Map.of("bottom-left", new String[]{"40", "main_h-overlay_h-40"}, "bottom-center", new String[]{"(main_w-overlay_w)/2", "main_h-overlay_h-40"}, "bottom-right", new String[]{"main_w-overlay_w-40", "main_h-overlay_h-40"}, "center", new String[]{"(main_w-overlay_w)/2", "(main_h-overlay_h)/2"});
    }
}
