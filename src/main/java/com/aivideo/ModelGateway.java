/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  com.fasterxml.jackson.databind.ObjectMapper
 *  com.fasterxml.jackson.databind.node.ObjectNode
 *  org.yaml.snakeyaml.Yaml
 */
package com.aivideo;

import com.aivideo.Providers;
import com.aivideo.FfmpegCore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.time.Duration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.yaml.snakeyaml.Yaml;

public final class ModelGateway {
    public static final String DEFAULT_MODEL = "agnes-video-2.5-flash";
    public static final String DEFAULT_BASE_URL = "https://apihub.agnes-ai.com/v1";
    public static final List<String> IMAGE_MODELS = List.of("agnes-image-2.5-flash", "agnes-image-2.1-flash", "agnes-image-2.0-flash");
    public static final String DEFAULT_IMAGE_MODEL = "agnes-image-2.5-flash";
    public static final List<String> VIDEO_MODELS = List.of("agnes-video-2.5-flash", "agnes-video-2.5");
    public static final List<String> TEXT_MODELS = List.of("agnes-3.0-flash", "agnes-2.5-flash", "agnes-3.0-pro", "agnes-2.5-pro-alpha");
    public static final String DEFAULT_TEXT_MODEL = "agnes-3.0-flash";
    private static final int MAX_SECONDS = 12;
    private static final int MIN_SECONDS = 4;
    private static final Map<String, String> RATIO = Map.of("vertical", "9:16", "9:16", "9:16", "landscape", "16:9", "16:9", "16:9", "square", "1:1", "1:1", "1:1", "21:9", "21:9", "4:3", "4:3", "3:4", "3:4");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final java.util.concurrent.atomic.AtomicLong IMG_SEQ = new java.util.concurrent.atomic.AtomicLong();

    private ModelGateway() {
    }

    static List<String[]> endpoints() {
        Path cfg;
        ArrayList<String[]> out = new ArrayList<String[]>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        String envKey = System.getenv("AGNES_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            String base = System.getenv("AGNES_BASE_URL");
            if (base == null || base.isBlank()) {
                base = DEFAULT_BASE_URL;
            }
            out.add(new String[]{base, envKey.trim()});
            seen.add(base + "|" + envKey.trim());
        }
        if (Files.exists(cfg = FfmpegCore.hermesHome().resolve("config.yaml"), new LinkOption[0])) {
            try {
                Map root;
                Object provs;
                Object loaded;
                byte[] raw = Files.readAllBytes(cfg);
                if (raw.length >= 3 && (raw[0] & 0xFF) == 239 && (raw[1] & 0xFF) == 187 && (raw[2] & 0xFF) == 191) {
                    raw = Arrays.copyOfRange(raw, 3, raw.length);
                }
                if ((loaded = new Yaml().load(new String(raw, StandardCharsets.UTF_8))) instanceof Map && (provs = (root = (Map)loaded).get("custom_providers")) instanceof List) {
                    List list = (List)provs;
                    for (Object o : list) {
                        Map prov;
                        if (!(o instanceof Map)) continue;
                        Map pm = prov = (Map)o;
                        String name = String.valueOf(pm.getOrDefault("name", "")).toLowerCase(Locale.ROOT);
                        String base = String.valueOf(pm.getOrDefault("base_url", ""));
                        String key = String.valueOf(pm.getOrDefault("api_key", ""));
                        boolean isAgnes = name.contains("agnes") || ((String)base).contains("agnes-ai");
                        if (!isAgnes || key.isBlank() || ((String)base).isBlank()) continue;
                        Object object = base = ((String)base).endsWith("/") ? ((String)base).substring(0, ((String)base).length() - 1) : base;
                        if (!((String)base).endsWith("/v1")) {
                            base = base + "/v1";
                        }
                        if (seen.contains(base + "|" + key)) continue;
                        out.add(new String[]{base, key});
                        seen.add(base + "|" + key);
                    }
                }
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        return out;
    }

    public static Map<String, Object> credentialStatus() {
        List<String[]> eps = ModelGateway.endpoints();
        if (eps.isEmpty()) {
            LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("ok", false);
            r.put("reason", "缺少模型凭据");
            r.put("hint", "\u5728 config.yaml \u7684 custom_providers \u52a0\u4e00\u4e2a agnes \u6761\u76ee\uff08api_key + base_url\uff09\uff0c\u6216\u8bbe\u73af\u5883\u53d8\u91cf AGNES_API_KEY / AGNES_BASE_URL");
            return r;
        }
        HttpClient http = ModelGateway.newClient(20);
        String lastRejected = "";
        for (String[] ep : eps) {
            String host = ModelGateway.splitHost(ep[0]);
            try {
                // 显式请求超时：连接建立后对端黑洞/慢响应不能无限挂起
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(host + "/v1/models")).header("Authorization", "Bearer " + ep[1]).GET().timeout(Duration.ofSeconds(15L)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    LinkedHashMap<String, Object> ok = new LinkedHashMap<String, Object>();
                    ok.put("ok", true);
                    ok.put("endpoints", eps.size());
                    ok.put("base", host);
                    ok.put("note", "credentials valid");
                    return ok;
                }
                if (r.statusCode() == 401 || r.statusCode() == 403) {
                    // key rejected — remember it; try the next endpoint
                    lastRejected = "API key rejected (HTTP " + r.statusCode() + ")";
                    continue;
                }
            }
            catch (Exception exception) {}
        }
        LinkedHashMap<String, Object> r2 = new LinkedHashMap<String, Object>();
        r2.put("ok", false);
        r2.put("reason", lastRejected.isEmpty() ? "无法连接内置模型网关（凭据已存）" : lastRejected);
        r2.put("endpoints", eps.size());
        r2.put("hint", "\u68c0\u67e5\u7f51\u7edc\uff0c\u6216\u5230 platform.agnes-ai.com \u6362\u4e00\u4e2a\u65b0 key");
        return r2;
    }

    public static String splitHost(String baseUrl) {
        String host = (baseUrl == null ? "" : baseUrl.trim()).replaceAll("/+$", "");
        return host.endsWith("/v1") ? host.substring(0, host.length() - 3) : host;
    }

    private static HttpClient newClient(int timeoutSec) {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    static List<String[]> providerEndpoints() {
        return ModelGateway.providerEndpoints("");
    }

    /** 开放 harness：按 id 解析供应商（空/未知 → 当前 active；内置且卡片无 key → 回落内置凭据）。 */
    static List<String[]> providerEndpoints(String providerId) {
        Map<String, Object> p;
        boolean builtin = false;
        if (providerId != null && !providerId.trim().isEmpty()) {
            Map<String, Object> st = Providers.state();
            p = null;
            for (Map<String, Object> x : (List<Map<String, Object>>)st.get("providers")) {
                if (providerId.trim().equals(String.valueOf(x.get("id")))) {
                    p = x;
                    break;
                }
            }
            if (p == null) {
                p = Providers.active();
            }
        } else {
            p = Providers.active();
        }
        builtin = Boolean.TRUE.equals(p.get("builtin"));
        List<String[]> out = new ArrayList<String[]>();
        String base = Providers.str(p, "base_url");
        String key = Providers.str(p, "api_key");
        if (!((String)base).isEmpty() && !key.isEmpty()) {
            if (!((String)(base = ((String)base).replaceAll("/+$", ""))).endsWith("/v1")) {
                base = base + "/v1";
            }
            out.add(new String[]{base, key});
        }
        if (out.isEmpty() && builtin) {
            out = ModelGateway.endpoints();
        }
        return out;
    }

    public static Map<String, Object> providerStatus() {
        Map<String, Object> p = Providers.active();
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("provider", Providers.displayName(p));
        out.put("provider_id", Providers.str(p, "id"));
        List<String[]> eps = ModelGateway.providerEndpoints();
        if (eps.isEmpty()) {
            out.put("ok", false);
            out.put("reason", "no provider credentials");
            return out;
        }
        HttpClient http = ModelGateway.newClient(20);
        for (String[] ep : eps) {
            String host = ModelGateway.splitHost(ep[0]);
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(host + "/v1/models")).header("Authorization", "Bearer " + ep[1]).GET().timeout(Duration.ofSeconds(15L)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    out.put("ok", true);
                    out.put("base", host);
                    out.put("note", "connection ok");
                    return out;
                }
                if (r.statusCode() != 401 && r.statusCode() != 403) continue;
                out.put("ok", false);
                out.put("reason", "API key rejected (HTTP " + r.statusCode() + ")");
                return out;
            }
            catch (Exception exception) {
            }
        }
        out.put("ok", false);
        out.put("reason", "cannot reach the model service (network or address)");
        return out;
    }

    public static Map<String, Object> generateImage(String prompt, String size, String model, String outDir) {
        return ModelGateway.generateImage(prompt, size, model, outDir, "");
    }

    public static Map<String, Object> generateImage(String prompt, String size, String model, String outDir, String providerId) {
        String string = prompt = prompt == null ? "" : prompt.trim();
        if (prompt.isEmpty()) {
            LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("ok", false);
            r.put("error", "图像提示词为空");
            r.put("stage", "prompt");
            return r;
        }
        List<String[]> eps = ModelGateway.providerEndpoints(providerId);
        if (eps.isEmpty()) {
            LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("ok", false);
            r.put("error", "\u5f53\u524d\u9009\u4e2d\u7684\u6a21\u578b\u4f9b\u5e94\u5546\u6ca1\u6709\u53ef\u7528\u7684 API Key\uff08\u8bf7\u5230\u300c\u6a21\u578b\u914d\u7f6e\u300d\u9875\u586b\u5199\uff09");
            r.put("stage", "credentials");
            return r;
        }
        if (model == null || model.isEmpty()) {
            model = Providers.defaultImageModel(Providers.active());
        }
        Path baseDir = Paths.get(outDir == null || outDir.isEmpty() ? FfmpegCore.assetRoot().resolve("images").toString() : outDir, new String[0]);
        try {
            Files.createDirectories(baseDir, new FileAttribute[0]);
        }
        catch (Exception exception) {
            // empty catch block
        }
        Object lastErr = "";
        for (String[] ep : eps) {
            String host = ModelGateway.splitHost(ep[0]);
            try {
                String url;
                HttpClient http;
                HttpResponse<String> r;
                ObjectNode body = JSON.createObjectNode();
                body.put("model", model);
                body.put("prompt", prompt);
                body.put("n", 1);
                if (size != null && !size.isEmpty()) {
                    body.put("size", size);
                }
                if ((r = (http = ModelGateway.newClient(180)).send(HttpRequest.newBuilder(URI.create(host + "/v1/images/generations")).header("Authorization", "Bearer " + ep[1]).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).timeout(Duration.ofSeconds(120L)).build(), HttpResponse.BodyHandlers.ofString())).statusCode() >= 400) {
                    lastErr = "图像模型服务拒绝（HTTP " + r.statusCode() + "): " + r.body().substring(0, Math.min(300, r.body().length()));
                    continue;
                }
                JsonNode cj = JSON.readTree(r.body());
                JsonNode data = cj.get("data");
                String string2 = url = data != null && data.size() > 0 ? data.get(0).path("url").asText("") : "";
                if (url.isEmpty()) {
                    lastErr = "图像模型服务未返回图片地址: " + r.body().substring(0, Math.min(200, r.body().length()));
                    continue;
                }
                HttpResponse<byte[]> dr = http.send(HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(60L)).build(), HttpResponse.BodyHandlers.ofByteArray());
                if (dr.statusCode() >= 400) {
                    lastErr = "图像下载失败（HTTP " + dr.statusCode() + ")";
                    continue;
                }
                byte[] bytes = dr.body();
                String ct = String.valueOf(dr.headers().firstValue("Content-Type").orElse(""));
                String ext = url.toLowerCase().contains(".png") || ct.startsWith("image/png") ? ".png" : ".jpg";
                // 秒级时间戳会同秒生成互覆盖 → 毫秒 + 进程内序号保证唯一
                Path dest = baseDir.resolve("img_" + System.currentTimeMillis() + "_" + IMG_SEQ.incrementAndGet() + ext);
                Files.write(dest, bytes, new OpenOption[0]);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("ok", true);
                out.put("url", url);
                out.put("local_path", dest.toString());
                out.put("model", model);
                out.put("size_bytes", bytes.length);
                out.put("task_id", cj.path("task_id").asText(""));
                return out;
            }
            catch (Exception exc) {
                lastErr = "图像模型异常: " + exc;
            }
        }
        LinkedHashMap<String, Object> fin = new LinkedHashMap<String, Object>();
        fin.put("ok", false);
        fin.put("error", ((String)lastErr).isEmpty() ? "图像模型生成失败" : lastErr);
        fin.put("stage", "agnesa-image");
        return fin;
    }

    public static Map<String, Object> generate(String prompt, List<String> imageRefs, List<String> audioRefs, String model, int seconds, String aspectRatio, String outDir, double pollDeadlineS, int queueRetries) {
        return ModelGateway.generate(prompt, imageRefs, audioRefs, model, seconds, aspectRatio, outDir, pollDeadlineS, queueRetries, () -> false, "");
    }

    public static Map<String, Object> generate(String prompt, List<String> imageRefs, List<String> audioRefs, String model, int seconds, String aspectRatio, String outDir, double pollDeadlineS, int queueRetries, BooleanSupplier cancel) {
        return ModelGateway.generate(prompt, imageRefs, audioRefs, model, seconds, aspectRatio, outDir, pollDeadlineS, queueRetries, cancel, "");
    }

    public static Map<String, Object> generate(String prompt, List<String> imageRefs, List<String> audioRefs, String model, int seconds, String aspectRatio, String outDir, double pollDeadlineS, int queueRetries, BooleanSupplier cancel, String providerId) {
        String string = prompt = prompt == null ? "" : prompt.trim();
        if (prompt.isEmpty()) {
            LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("ok", false);
            r.put("error", "agena: prompt is empty");
            return r;
        }
        seconds = Math.max(4, Math.min(12, seconds == 0 ? 5 : seconds));
        String ratio = RATIO.getOrDefault(aspectRatio == null ? "9:16" : aspectRatio, aspectRatio == null ? "9:16" : aspectRatio);
        if (model == null || model.isEmpty()) {
            model = providerId != null && !providerId.trim().isEmpty() ? Providers.defaultVideoModel(Providers.byid(providerId)) : Providers.defaultVideoModel(Providers.active());
        }
        List refs = imageRefs == null ? List.of() : imageRefs.stream().filter(u -> u != null && !u.isEmpty()).toList();
        List auds = audioRefs == null ? List.of() : audioRefs.stream().filter(u -> u != null && !u.isEmpty()).toList();
        List<String[]> eps = ModelGateway.providerEndpoints(providerId);
        if (eps.isEmpty()) {
            LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("ok", false);
            r.put("error", "\u5f53\u524d\u9009\u4e2d\u7684\u6a21\u578b\u4f9b\u5e94\u5546\u6ca1\u6709\u53ef\u7528\u7684 API Key\uff08\u8bf7\u5230\u300c\u6a21\u578b\u914d\u7f6e\u300d\u9875\u586b\u5199\uff09");
            r.put("stage", "credentials");
            return r;
        }
        Path baseDir = Paths.get(outDir == null || outDir.isEmpty() ? FfmpegCore.assetRoot().resolve("agnes").toString() : outDir, new String[0]);
        try {
            Files.createDirectories(baseDir, new FileAttribute[0]);
        }
        catch (Exception exception) {
            // empty catch block
        }
        if (cancel.getAsBoolean()) {
            return ModelGateway.cancelledResult();
        }
        Set<String> terminal = Set.of("completed", "succeeded", "success", "failed", "error", "cancelled", "canceled");
        Object lastErr = "";
        int totalPasses = 1 + Math.max(0, queueRetries);
        for (int attempt = 0; attempt < totalPasses; ++attempt) {
            for (String[] ep : eps) {
                String host = ModelGateway.splitHost(ep[0]);
                try {
                    HttpClient http;
                    HttpResponse<String> cr;
                    ObjectNode body = JSON.createObjectNode();
                    body.put("model", model);
                    body.put("prompt", prompt);
                    body.put("seconds", String.valueOf(seconds));
                    body.put("mode", refs.isEmpty() && auds.isEmpty() ? "text" : "reference");
                    body.put("size", "720P");
                    body.put("aspect_ratio", ratio);
                    body.put("n", 1);
                    if (!refs.isEmpty()) {
                        body.set("images", JSON.valueToTree(refs.subList(0, Math.min(5, refs.size()))));
                    }
                    if (!auds.isEmpty()) {
                        body.set("audios", JSON.valueToTree(auds.subList(0, Math.min(3, auds.size()))));
                    }
                    if ((cr = (http = ModelGateway.newClient(60)).send(HttpRequest.newBuilder(URI.create(host + "/v1/videos")).header("Authorization", "Bearer " + ep[1]).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).timeout(Duration.ofSeconds(60L)).build(), HttpResponse.BodyHandlers.ofString())).statusCode() == 503 && cr.body().contains("queue_full")) {
                        lastErr = "模型服务队列已满（503）";
                        continue;
                    }
                    if (cr.statusCode() >= 400) {
                        lastErr = "模型服务拒绝请求（HTTP " + cr.statusCode() + "): " + cr.body().substring(0, Math.min(300, cr.body().length()));
                        continue;
                    }
                    JsonNode cj = JSON.readTree(cr.body());
                    String vid = ModelGateway.firstNonBlank(cj.path("video_id").asText(""), cj.path("id").asText(""), cj.path("data").path("video_id").asText(""));
                    if (vid.isEmpty()) {
                        lastErr = "模型服务未返回任务ID: " + cr.body().substring(0, Math.min(200, cr.body().length()));
                        continue;
                    }
                    long deadline = System.nanoTime() / 1000000L + (long)(pollDeadlineS * 1000.0);
                    String lastStatus = "queued";
                    String url = null;
                    while (System.nanoTime() / 1000000L < deadline) {
                        if (cancel.getAsBoolean()) {
                            return ModelGateway.cancelledResult();
                        }
                        String modelParam = java.net.URLEncoder.encode(model, java.nio.charset.StandardCharsets.UTF_8);
                    HttpResponse<String> pr = http.send(HttpRequest.newBuilder(URI.create(host + "/agnesapi?video_id=" + vid + "&model_name=" + modelParam)).header("Authorization", "Bearer " + ep[1]).GET().timeout(Duration.ofSeconds(30L)).build(), HttpResponse.BodyHandlers.ofString());
                        if (pr.statusCode() == 200) {
                            JsonNode pj = JSON.readTree(pr.body());
                            JsonNode meta = pj.path("metadata");
                            lastStatus = pj.path("status").asText(meta.path("status").asText("")).toLowerCase(Locale.ROOT);
                            url = ModelGateway.extractVideoUrl(pj);
                            if (terminal.contains(lastStatus)) break;
                        }
                        Thread.sleep(1500L);
                    }
                    if (!List.of("completed", "succeeded", "success").contains(lastStatus)) {
                        lastErr = "模型任务结束 status='" + lastStatus + "' (id=" + vid + ")";
                        continue;
                    }
                    if (url == null) {
                        lastErr = "模型任务 " + vid + " 完成但未找到视频地址";
                        continue;
                    }
                    HttpResponse<byte[]> dr = http.send(HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(300L)).build(), HttpResponse.BodyHandlers.ofByteArray());
                    if (dr.statusCode() >= 400) {
                        lastErr = "视频下载失败（HTTP " + dr.statusCode() + ")";
                        continue;
                    }
                    byte[] bytes = dr.body();
                    // 秒级时间戳会同秒生成互覆盖 → 毫秒 + 进程内序号保证唯一
                    Path dest = baseDir.resolve("agnes_" + System.currentTimeMillis() + "_" + IMG_SEQ.incrementAndGet() + ".mp4");
                    Files.write(dest, bytes, new OpenOption[0]);
                    LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                    out.put("ok", true);
                    out.put("video", dest.toString());
                    out.put("model", model);
                    out.put("mode", refs.isEmpty() && auds.isEmpty() ? "text" : "reference");
                    out.put("aspect_ratio", ratio);
                    out.put("seconds", seconds);
                    out.put("video_id", vid);
                    out.put("size_bytes", bytes.length);
                    out.put("provider", "agnes");
                    return out;
                }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    lastErr = "interrupted";
                }
                catch (Exception exc) {
                    lastErr = "模型服务异常: " + exc;
                }
            }
            if (attempt >= queueRetries) continue;
            if (cancel.getAsBoolean()) {
                return ModelGateway.cancelledResult();
            }
            try {
                Thread.sleep(20000L);
                continue;
            }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        LinkedHashMap<String, Object> fin = new LinkedHashMap<String, Object>();
        fin.put("ok", false);
        fin.put("error", ((String)lastErr).isEmpty() ? "模型生成失败" : lastErr);
        fin.put("stage", "agnesa");
        return fin;
    }

    private static Map<String, Object> cancelledResult() {
        LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("ok", false);
        r.put("cancelled", true);
        r.put("error", "\u7528\u6237\u53d6\u6d88");
        r.put("stage", "agnesa");
        return r;
    }

    private static String extractVideoUrl(JsonNode node) {
        ArrayList<String> found = new ArrayList<String>();
        ModelGateway.walk(node, found);
        return found.isEmpty() ? null : (String)found.get(0);
    }

    private static void walk(JsonNode o, List<String> found) {
        if (o.isObject()) {
            Iterator it = o.fields();
            while (it.hasNext()) {
                ModelGateway.walk((JsonNode)((Map.Entry)it.next()).getValue(), found);
            }
        } else if (o.isArray()) {
            for (JsonNode v : o) {
                ModelGateway.walk(v, found);
            }
        } else if (o.isTextual()) {
            String bare;
            String s = o.asText();
            int q = s.indexOf(63);
            String string = bare = q >= 0 ? s.substring(0, q) : s;
            if (s.startsWith("http") && bare.endsWith(".mp4")) {
                found.add(s);
            }
        }
    }

    private static String firstNonBlank(String ... xs) {
        for (String x : xs) {
            if (x == null || x.isEmpty()) continue;
            return x;
        }
        return "";
    }
}
