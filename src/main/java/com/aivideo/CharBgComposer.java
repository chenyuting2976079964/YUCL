/*
 * Decompiled with CFR 0.152.
 */
package com.aivideo;

import com.aivideo.ModelGateway;
import com.aivideo.FfmpegCore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CharBgComposer {
    public static final Progress NO_PROGRESS = new Progress(){

        @Override
        public void report(int step, int total, String message) {
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    };

    private CharBgComposer() {
    }

    static Map<String, Object> lookup(Map<String, Map<String, Object>> entities, Object key) {
        if (key == null) {
            return null;
        }
        String s = String.valueOf(key).trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.chars().allMatch(Character::isDigit) && !s.isEmpty()) {
            int idx;
            try {
                idx = Integer.parseInt(s);
            }
            catch (NumberFormatException e) {
                idx = -1;
            }
            if (idx >= 0 && idx < entities.size()) {
                return entities.values().stream().skip(idx).findFirst().orElse(null);
            }
        }
        if (entities.containsKey(s)) {
            return entities.get(s);
        }
        for (Map.Entry<String, Map<String, Object>> e : entities.entrySet()) {
            if (!e.getKey().equalsIgnoreCase(s)) continue;
            return e.getValue();
        }
        return null;
    }

    static Map<String, Map<String, Object>> entitiesMap(List<Map<String, Object>> lst) {
        LinkedHashMap<String, Map<String, Object>> m = new LinkedHashMap<String, Map<String, Object>>();
        if (lst != null) {
            for (Map<String, Object> e : lst) {
                String name = FfmpegCore.str(e == null ? null : e.get("name")).trim();
                if (name.isEmpty()) continue;
                m.put(name, e);
            }
        }
        return m;
    }

    static String resolveLocalImg(String img, String url, Path runDir, String label, int shotN, List<String> notes, String who) {
        if (url == null || url.isEmpty() || !url.startsWith("http://") && !url.startsWith("https://")) {
            return img;
        }
        Object ext = ".png";
        String tail = url.split("\\?")[0];
        int slash = tail.lastIndexOf(47);
        if (slash >= 0 && slash + 5 < tail.length()) {
            String e = tail.substring(slash + 1).toLowerCase(Locale.ROOT);
            for (String ok : new String[]{"png", "jpg", "jpeg", "webp", "gif"}) {
                if (!e.startsWith(ok) || e.length() > ok.length() + 3) continue;
                ext = "." + ok;
                break;
            }
        }
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15L)).build();
            HttpResponse<byte[]> resp = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60L)).build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] data = resp.body();
            if (resp.statusCode() != 200 || data == null || data.length == 0 || (long)data.length > 0xF00000L) {
                throw new IOException("HTTP " + resp.statusCode());
            }
            Path dst = runDir.resolve("ref_" + label + (String)ext);
            Files.write(dst, data, new OpenOption[0]);
            return dst.toString();
        }
        catch (Exception e) {
            notes.add("\u7b2c" + shotN + "\u955c\uff1a" + who + "\u7684 URL \u56fe\u7247\u4e0b\u8f7d\u5931\u8d25\uff08" + e.getMessage() + "\uff09\uff0c\u65e0\u6cd5\u672c\u5730\u5408\u6210");
            return img;
        }
    }

    public static Map<String, Object> makeCharBgVideo(List<Map<String, Object>> shots, List<Map<String, Object>> characters, List<Map<String, Object>> backgrounds, String orientation, boolean subtitles, String bgm, double bgmVol, boolean doTts, Map<String, String> voices, Map<String, String> localAudio, Map<String, String> charVoices, String defaultVoice, String model, String providerId, int fps, double charScale, String charPos, String styleHint, String outDir, double pollDeadlineS, int queueRetries, String quality, int subFontSize, Progress progress) throws IOException {
        Map<String, Object> rc;
        String firstText;
        if (progress == null) {
            progress = NO_PROGRESS;
        }
        int totalSteps = (shots == null ? 0 : shots.size()) + 3;
        long t0 = System.currentTimeMillis();
        int[] wh = FfmpegCore.geom(orientation, quality);
        int w = wh[0];
        int h = wh[1];
        String ratio = orientation;
        Path base = Paths.get(outDir == null || outDir.isEmpty() ? FfmpegCore.assetRoot().toString() : outDir, new String[0]);
        String string = firstText = shots == null || shots.isEmpty() ? "" : FfmpegCore.str(shots.get(0).get("text"));
        if (firstText.isEmpty()) {
            firstText = "film";
        }
        Path runDir = base.resolve("charbg_" + FfmpegCore.slug(firstText.substring(0, Math.min(16, firstText.length())), 20)).resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Files.createDirectories(runDir, new FileAttribute[0]);
        if (shots == null || shots.isEmpty()) {
            return Map.of("ok", false, "error", "\u9700\u8981\u81f3\u5c11\u4e00\u4e2a\u955c\u5934 shots");
        }
        if (FfmpegCore.ffmpeg() == null) {
            return Map.of("ok", false, "error", "ffmpeg not found on PATH");
        }
        if (voices == null) {
            voices = Map.of();
        }
        if (defaultVoice == null || defaultVoice.isEmpty()) {
            defaultVoice = FfmpegCore.VOCES.get("zh");
        }
        Map<String, Map<String, Object>> chars = CharBgComposer.entitiesMap(characters);
        Map<String, Map<String, Object>> bgs = CharBgComposer.entitiesMap(backgrounds);
        ArrayList<String> clipPaths = new ArrayList<String>();
        ArrayList<Map<String, Object>> processed = new ArrayList<Map<String, Object>>();
        ArrayList<String> engineFallbacks = new ArrayList<String>();
        LinkedHashMap<String, Integer> audioSrcCount = new LinkedHashMap<String, Integer>();
        ArrayList<String> voiceWarnings = new ArrayList<String>();
        String subsBurnError = null; // 字幕烧录失败原因（成功为 null）
        for (int i = 0; i < shots.size(); ++i) {
            Map<String, Object> comp;
            Double alen;
            Map<String, Object> shot = shots.get(i);
            int n1 = i + 1;
            String text = FfmpegCore.str(shot.get("text")).trim();
            int sec = Math.max(4, Math.min(12, FfmpegCore.intOf(shot.get("seconds"), 5)));
            String engine = FfmpegCore.str(shot.get("engine")).toLowerCase(Locale.ROOT).isEmpty() ? "local" : FfmpegCore.str(shot.get("engine")).toLowerCase(Locale.ROOT);
            progress.report(i, totalSteps, "Shot " + n1 + "/" + shots.size() + " \u00b7 " + (engine.equals("ai") ? "AI video generation (may queue 1\u20133 min)" : "local compositing"));
            if (progress.cancelled()) {
                return CharBgComposer.cancelledStage(i + 1, totalSteps);
            }
            Map<String, Object> ch = CharBgComposer.lookup(chars, shot.get("character"));
            Map<String, Object> ch2 = CharBgComposer.lookup(chars, shot.get("character2"));
            Map<String, Object> map = CharBgComposer.lookup(bgs, shot.get("background"));
            String chName = ch != null ? FfmpegCore.str(ch.get("name")) : FfmpegCore.str(shot.get("character")).trim();
            String ch2Name = ch2 != null ? FfmpegCore.str(ch2.get("name")) : FfmpegCore.str(shot.get("character2")).trim();
            String chImg = ch != null ? FfmpegCore.str(ch.get("image")) : "";
            String ch2Img = ch2 != null ? FfmpegCore.str(ch2.get("image")) : "";
            String chUrl = ch != null ? FfmpegCore.str(ch.get("url")) : "";
            String ch2Url = ch2 != null ? FfmpegCore.str(ch2.get("url")) : "";
            String bgImg = map != null ? FfmpegCore.str(map.get("image")) : "";
            String bgUrl = map != null ? FfmpegCore.str(map.get("url")) : "";
            String audio = null;
            String audioSrc = "none";
            if (doTts) {
                String loc;
                String cv = chName.isEmpty() || charVoices == null ? null : charVoices.get(chName);
                String cvResolved = cv == null || cv.isEmpty() || cv.startsWith("tts:") ? null : FfmpegCore.resolveVoicePath(cv);
                if (cvResolved != null) {
                    audio = cvResolved;
                    audioSrc = "local";
                } else if (cv != null && cv.startsWith("tts:")) {
                    voiceWarnings.add("\u7b2c" + n1 + "\u955c\uff08" + (chName.isEmpty() ? "\u9ed8\u8ba4" : chName) + "\uff09\uff1a\u8be5\u4eba\u7269\u97f3\u6e90\u9009\u4e86\u7cfb\u7edf\u97f3\u8272 " + cv.substring(4) + "\uff0c\u672c\u6784\u5efa\u65e0 TTS\uff0c\u5df2\u7528\u9ed8\u8ba4\u58f0\u6e90");
                } else if (cv != null && !cv.isEmpty()) {
                    voiceWarnings.add("\u7b2c" + n1 + "\u955c\uff08" + (chName.isEmpty() ? "\u9ed8\u8ba4" : chName) + "\uff09\uff1a\u8bed\u97f3\u6587\u4ef6\u300c" + cv + "\u300d\u5728\u8bed\u97f3\u5e93\u672a\u627e\u5230\uff0c\u5df2\u7528\u9ed8\u8ba4\u58f0\u6e90");
                }
                if (audio == null && (loc = FfmpegCore.resolveLocalAudio(i, chName.isEmpty() ? null : chName, localAudio)) != null) {
                    audio = loc;
                    audioSrc = "local";
                }
            }
            if (!text.isEmpty() && doTts && audioSrc.equals("none")) {
                voiceWarnings.add("\u7b2c" + n1 + "\u955c\uff08" + (chName.isEmpty() ? "\u9ed8\u8ba4" : chName) + "\uff09\u65e0\u65c1\u767d\u58f0\uff1aTTS \u4e0d\u53ef\u7528\u4e14\u672a\u914d\u8bed\u97f3\u6587\u4ef6");
            }
            String usedEngine = engine;
            if (engine.equals("ai")) {
                ArrayList<String> refs = new ArrayList<String>();
                if (!chUrl.isEmpty()) {
                    refs.add(chUrl);
                }
                if (!ch2Url.isEmpty()) {
                    refs.add(ch2Url);
                }
                if (!bgUrl.isEmpty()) {
                    refs.add(bgUrl);
                }
                if (!refs.isEmpty()) {
                    // 被引用但无公网 URL 的实体不作为 AI 参考（本地上传图），给出说明
                    if (!chName.isEmpty() && chUrl.isEmpty()) {
                        engineFallbacks.add("第" + n1 + "镜：人物「" + chName + "」为本地上传（无公网 URL），未作为 AI 参考图");
                    }
                    if (!ch2Name.isEmpty() && ch2Url.isEmpty()) {
                        engineFallbacks.add("第" + n1 + "镜：人物「" + ch2Name + "」为本地上传（无公网 URL），未作为 AI 参考图");
                    }
                    // Agnes Video API 规范：参考素材用 <Picture N>/<Audio N> 标记，顺序 = refs 数组顺序
                    StringBuilder refDesc = new StringBuilder();
                    int pic = 1;
                    if (!chUrl.isEmpty()) {
                        refDesc.append("<Picture ").append(pic++).append("> = the character \"").append(chName).append("\" (keep appearance consistent)");
                    }
                    if (!ch2Url.isEmpty()) {
                        if (refDesc.length() > 0) {
                            refDesc.append("; ");
                        }
                        refDesc.append("<Picture ").append(pic++).append("> = the character \"").append(ch2Name).append("\" (keep appearance consistent)");
                    }
                    if (!bgUrl.isEmpty()) {
                        if (refDesc.length() > 0) {
                            refDesc.append("; ");
                        }
                        refDesc.append("<Picture ").append(pic).append("> = the background scene");
                    }
                    // 参考音频（API 要求公网 URL）：音色源为公网链接时作为 <Audio 1>
                    ArrayList<String> audioRefs = new ArrayList<String>();
                    String cvRaw = chName.isEmpty() || charVoices == null ? "" : FfmpegCore.str(charVoices.get(chName));
                    if (cvRaw.startsWith("http://") || cvRaw.startsWith("https://")) {
                        audioRefs.add(cvRaw);
                    }
                    String prompt = styleHint + ". Scene: " + text + "." + (refDesc.length() > 0 ? " References: " + refDesc + "." : "") + (audioRefs.isEmpty() ? "" : " Match the narrator's voice to <Audio 1>.") + " Keep each character's appearance consistent across the clip.";
                    // 输出分辨率档位：quality 720p->720P，1080p->1080P，2k->2K（flash 仅 720P，更高档位由 ModelGateway 自动升 base 模型）
                    String q = quality == null ? "" : quality.trim().toLowerCase(Locale.ROOT);
                    String tier = q.equals("1080p") ? "1080P" : (q.equals("2k") ? "2K" : "720P");
                    String string3 = model == null || model.isEmpty() ? (tier.equals("720P") ? "agnes-video-2.5-flash" : "agnes-video-2.5") : model;
                    if (string3 == null || string3.isEmpty()) {
                        string3 = "agnes-video-2.5-flash";
                    }
                    Map<String, Object> gen = ModelGateway.generate(prompt, refs, audioRefs, string3, sec, ratio, runDir.toString(), pollDeadlineS, queueRetries, progress::cancelled, providerId, tier);
                    if (Boolean.TRUE.equals(gen.get("cancelled"))) {
                        return CharBgComposer.cancelledStage(n1 + 1, totalSteps);
                    }
                    if (Boolean.TRUE.equals(gen.get("ok"))) {
                        String clip = FfmpegCore.str(gen.get("video"));
                        String norm = runDir.resolve("c" + i + "_n.mp4").toString();
                        Map<String, Object> nz = FfmpegCore.normalize(clip, audio, norm, w, h, fps, sec);
                        if (Boolean.TRUE.equals(nz.get("ok")) && Files.exists(Paths.get(norm, new String[0]), new LinkOption[0])) {
                            clipPaths.add(norm);
                            Map<String, Object> pend = new LinkedHashMap<String, Object>();
                            pend.put("index", i);
                            pend.put("character", chName);
                            pend.put("character2", ch2Name);
                            pend.put("background", map != null ? FfmpegCore.str(map.get("name")) : "");
                            pend.put("caption", text);
                            pend.put("duration", sec);
                            pend.put("engine", "ai");
                            pend.put("audio_src", audioSrc);
                            pend.put("gen", FfmpegCore.str(gen.get("video_id")));
                            pend.put("model", FfmpegCore.str(gen.get("model")));
                            pend.put("size", FfmpegCore.str(gen.get("size")));
                            processed.add(pend);
                            CharBgComposer.bump(audioSrcCount, audioSrc);
                            continue;
                        }
                        // 归一化失败：裸 AI 片段（720P/任意 fps/编码）混进 concat -c copy 会直接炸，
                        // 改走本地合成兜底
                        engineFallbacks.add("\u7b2c" + n1 + "\u955c\uff1aAI \u89c6\u9891\u5f52\u4e00\u5316\u5931\u8d25\uff08" + nz.get("error") + "\uff09\uff0c\u5df2\u6539\u7528\u672c\u5730\u5408\u6210");
                    } else {
                        engineFallbacks.add("\u7b2c" + n1 + "\u955c\uff1aAI \u751f\u6210\u5931\u8d25\uff08" + gen.get("error") + "\uff09\uff0c\u5df2\u6539\u7528\u672c\u5730\u5408\u6210");
                        if (gen.get("note") != null) {
                            engineFallbacks.add(FfmpegCore.str(gen.get("note")));
                        }
                    }
                } else {
                    engineFallbacks.add("\u7b2c" + n1 + "\u955c\uff1aAI \u9700\u516c\u7f51\u56fe\u7247 URL\uff08\u63d0\u793a\u8bcd\u751f\u6210\u7684\u56fe\uff09\uff0c\u6240\u9009\u4e3a\u672c\u5730\u4e0a\u4f20\uff0c\u5df2\u6539\u7528\u672c\u5730\u5408\u6210");
                }
                usedEngine = "local";
            }
            double dur = sec;
            if (audio != null && (alen = FfmpegCore.ffprobeDuration(audio)) != null && alen > 0.0) {
                double want = (double)Math.round((alen + 0.3) * 100.0) / 100.0;
                // Cap: a very long audio file must not stretch one shot into
                // a multi-minute clip (ffmpeg 900s timeout, runaway video).
                if (want > 60.0) {
                    voiceWarnings.add("\u7b2c" + n1 + "\u955c\uff1a\u58f0\u97f3\u6587\u4ef6 " + Math.round(alen) + "s \u8d85\u957f\uff0c\u622a\u53d6\u5230 60s");
                    want = 60.0;
                }
                dur = Math.max((double)sec, want);
            }
            if (!(!chImg.isEmpty() && Files.exists(Paths.get(chImg, new String[0]), new LinkOption[0]) || chUrl.isEmpty())) {
                chImg = CharBgComposer.resolveLocalImg(chImg, chUrl, runDir, "ch" + i + "a", n1, engineFallbacks, "\u4eba\u7269\u300c" + (chName.isEmpty() ? "\u9ed8\u8ba4" : chName) + "\u300d");
            }
            if (!(!ch2Img.isEmpty() && Files.exists(Paths.get(ch2Img, new String[0]), new LinkOption[0]) || ch2Url.isEmpty())) {
                ch2Img = CharBgComposer.resolveLocalImg(ch2Img, ch2Url, runDir, "ch" + i + "b", n1, engineFallbacks, "\u4eba\u7269\u300c" + (ch2Name.isEmpty() ? "\u9ed8\u8ba4" : ch2Name) + "\u300d");
            }
            if (!(!bgImg.isEmpty() && Files.exists(Paths.get(bgImg, new String[0]), new LinkOption[0]) || bgUrl.isEmpty())) {
                bgImg = CharBgComposer.resolveLocalImg(bgImg, bgUrl, runDir, "bg" + i, n1, engineFallbacks, "\u80cc\u666f\u300c" + (map != null ? FfmpegCore.str(map.get("name")) : "\u9ed8\u8ba4") + "\u300d");
            }
            if (!Boolean.TRUE.equals((comp = FfmpegCore.composeLocal(bgImg.isEmpty() ? null : bgImg, chImg.isEmpty() ? null : chImg, ch2Img.isEmpty() ? null : ch2Img, w, h, dur, charScale, charPos, fps, audio, runDir.resolve("c" + i + ".mp4").toString())).get("ok"))) {
                LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
                r.put("ok", false);
                r.put("stage", "compose-shot-" + n1);
                r.put("error", comp.get("error"));
                r.put("workdir", runDir.toString());
                return r;
            }
            clipPaths.add(runDir.resolve("c" + i + ".mp4").toString());
            processed.add(Map.of("index", i, "character", chName, "character2", ch2Name, "background", map != null ? FfmpegCore.str(map.get("name")) : "", "caption", text, "duration", dur, "engine", usedEngine, "audio_src", audioSrc));
            CharBgComposer.bump(audioSrcCount, audioSrc);
        }
        String concatOut = runDir.resolve("concat.mp4").toString();
        progress.report(shots.size(), totalSteps, "Merging all shots");
        if (progress.cancelled()) {
            return CharBgComposer.cancelledStage(shots.size() + 1, totalSteps);
        }
        try {
            rc = FfmpegCore.concat(clipPaths, concatOut);
        }
        catch (Exception e) {
            rc = Map.of("ok", false, "error", "concat write failed: " + e);
        }
        if (!Boolean.TRUE.equals(rc.get("ok"))) {
            LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("ok", false);
            r.put("stage", "concat");
            r.put("error", rc.get("error"));
            r.put("workdir", runDir.toString());
            return r;
        }
        String finalOut = runDir.resolve("final.mp4").toString();
        progress.report(shots.size() + 1, totalSteps, bgm != null && !bgm.isEmpty() && Files.exists(Paths.get(bgm, new String[0]), new LinkOption[0]) ? "Mixing background music" : "Final rendering");
        if (progress.cancelled()) {
            return CharBgComposer.cancelledStage(shots.size() + 2, totalSteps);
        }
        if (bgm != null && !bgm.isEmpty() && Files.exists(Paths.get(bgm, new String[0]), new LinkOption[0])) {
            Map<String, Object> r2 = FfmpegCore.mixBgm(concatOut, bgm, finalOut, bgmVol);
            if (!Boolean.TRUE.equals(r2.get("ok"))) {
                LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
                r.put("ok", false);
                r.put("stage", "bgm");
                r.put("error", r2.get("error"));
                r.put("workdir", runDir.toString());
                return r;
            }
        } else {
            Files.copy(Paths.get(concatOut, new String[0]), Paths.get(finalOut, new String[0]), new CopyOption[0]);
        }
        if (subtitles) {
            progress.report(shots.size() + 2, totalSteps, "Burning in subtitles");
            if (progress.cancelled()) {
                return CharBgComposer.cancelledStage(shots.size() + 3, totalSteps);
            }
            String srt = runDir.resolve("subs.srt").toString();
            try {
                FfmpegCore.writeSrt(processed, srt);
            }
            catch (Exception e) {
                return Map.of("ok", false, "stage", "srt", "error", "write srt failed: " + e, "workdir", runDir.toString());
            }
            String finalSubs = runDir.resolve("final_subs.mp4").toString();
            Map<String, Object> r3 = FfmpegCore.burnSrt(finalOut, srt, finalSubs, subFontSize);
            if (Boolean.TRUE.equals(r3.get("ok"))) {
                finalOut = finalSubs;
            } else {
                // 静默降级会困惑（勾了字幕成品却没有）→ 把失败原因透传到结果
                subsBurnError = String.valueOf(r3.getOrDefault("error", "unknown"));
            }
        }
        double durTotal = (double)Math.round(processed.stream().mapToDouble(p -> FfmpegCore.num(p.get("duration"))).sum() * 100.0) / 100.0;
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("mode", "char-bg");
        out.put("video", finalOut);
        out.put("workdir", runDir.toString());
        out.put("orientation", orientation);
        out.put("size", w + "x" + h);
        out.put("shots", processed.size());
        out.put("duration_s", durTotal);
        out.put("audio_sources", audioSrcCount);
        out.put("voice_warnings", voiceWarnings);
        out.put("engine_fallbacks", engineFallbacks);
        out.put("subtitles", subtitles && finalOut.contains("subs"));
        if (subsBurnError != null) {
            // 勾了字幕但烧录失败 → 结果里带上原因，UI 可展示（而不是静默没有字幕）
            out.put("subtitle_warning", "字幕烧录失败（" + subsBurnError + "），最终视频无字幕");
        }
        // 本地合成任务不应显示「请求的 AI 模型」——按实际是否用了 AI 镜头标注；AI 镜头记录实际使用的模型/档位
        boolean usedAi = processed.stream().anyMatch(p -> "ai".equals(p.get("engine")));
        String modelUsed;
        if (!usedAi) {
            modelUsed = "local";
        } else {
            String first = (String) processed.stream().map(p -> FfmpegCore.str(((Map)p).get("model"))).filter(s -> !s.isEmpty()).findFirst().orElse("");
            modelUsed = first.isEmpty() ? (model == null || model.isEmpty() ? "ai" : model) : first;
        }
        out.put("model", modelUsed);
        out.put("elapsed_s", (double)Math.round((double)(System.currentTimeMillis() - t0) / 100.0) / 10.0);
        out.put("size_bytes", Files.exists(Paths.get(finalOut, new String[0]), new LinkOption[0]) ? Long.valueOf(Files.size(Paths.get(finalOut, new String[0]))) : null);
        ArrayList processedOut = new ArrayList();
        for (Map map : processed) {
            LinkedHashMap q = new LinkedHashMap();
            for (String k : new String[]{"index", "character", "character2", "background", "caption", "duration", "engine", "audio_src"}) {
                q.put(k, map.get(k));
            }
            processedOut.add(q);
        }
        out.put("processed", processedOut);
        return out;
    }

    private static Map<String, Object> cancelledStage(int step, int totalSteps) {
        LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("ok", false);
        r.put("cancelled", true);
        r.put("stage", "cancelled");
        r.put("error", "\u7528\u6237\u5df2\u53d6\u6d88\uff08\u7b2c " + step + "/" + totalSteps + " \u6b65\uff09");
        return r;
    }

    private static void bump(Map<String, Integer> d, String k) {
        d.merge(k, 1, Integer::sum);
    }

    public static interface Progress {
        public void report(int var1, int var2, String var3);

        public boolean cancelled();
    }
}
