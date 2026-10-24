/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.annotation.JsonProperty
 *  com.fasterxml.jackson.databind.JsonNode
 *  com.fasterxml.jackson.databind.ObjectMapper
 *  com.fasterxml.jackson.databind.node.ArrayNode
 *  com.fasterxml.jackson.databind.node.ObjectNode
 *  jakarta.annotation.PostConstruct
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.core.io.InputStreamResource
 *  org.springframework.http.ContentDisposition
 *  org.springframework.http.HttpHeaders
 *  org.springframework.http.MediaType
 *  org.springframework.http.ResponseEntity
 *  org.springframework.http.ResponseEntity$BodyBuilder
 *  org.springframework.web.bind.annotation.DeleteMapping
 *  org.springframework.web.bind.annotation.ExceptionHandler
 *  org.springframework.web.bind.annotation.GetMapping
 *  org.springframework.web.bind.annotation.PathVariable
 *  org.springframework.web.bind.annotation.PostMapping
 *  org.springframework.web.bind.annotation.RequestBody
 *  org.springframework.web.bind.annotation.RequestHeader
 *  org.springframework.web.bind.annotation.RequestMapping
 *  org.springframework.web.bind.annotation.RequestParam
 *  org.springframework.web.bind.annotation.RestController
 *  org.springframework.web.multipart.MultipartException
 *  org.springframework.web.multipart.MultipartFile
 */
package com.aivideo;

import com.aivideo.ModelGateway;
import com.aivideo.CharBgComposer;
import com.aivideo.DataDirs;
import com.aivideo.Jobs;
import com.aivideo.Providers;
import com.aivideo.FfmpegCore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipInputStream;
import java.nio.file.StandardOpenOption;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
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
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(value={"/api/plugins/ai-video"})
public class ApiController {
    private static final long MAX_MEDIA_BYTES = 0x2800000L;
    private static final long MAX_UPLOAD_BYTES = 0x3200000L;
    private static final Set<String> VOICE_EXTS = Set.of(".mp3", ".wav", ".m4a", ".aac", ".flac", ".ogg", ".wma", ".opus");
    private static final Set<String> IMG_EXTS = Set.of(".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif");
    private static final Set<String> MUSIC_EXTS = Set.of(".mp3", ".wav", ".m4a", ".aac", ".flac", ".ogg");
    private static final Semaphore RENDER_SEM = new Semaphore(1);
    // 正在持有渲染信号量的 jobId（区分「渲染中」与「排队中」，取消时避免误杀前一个任务的 ffmpeg）
    private static final java.util.concurrent.atomic.AtomicReference<String> RENDER_OWNER = new java.util.concurrent.atomic.AtomicReference<>();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "aivideo-render");
        t.setDaemon(true);
        return t;
    });
    private static final ConcurrentHashMap<String, AtomicBoolean> CANCELS = new ConcurrentHashMap();
    private static final ObjectMapper JSON2 = new ObjectMapper();
    private static volatile boolean SWEEPER_STARTED;
    @Value(value="${aivideo.voices.dir:C:/YUCL/media/voices}")
    private String voicesDir;
    @Value(value="${aivideo.images.dir:C:/YUCL/media/images}")
    private String imagesDir;
    @Value(value="${aivideo.music.dir:C:/YUCL/media/music}")
    private String musicDir;
    @Value(value="${aivideo.log.file:C:/YUCL/data/app.log}")
    private String logFile;

    @PostConstruct
    void resolveDataDirs() {
        ApiController.startBackupTempSweeper();
        this.voicesDir = DataDirs.usableDir(this.voicesDir, "voices");
        this.imagesDir = DataDirs.usableDir(this.imagesDir, "images");
        this.musicDir = DataDirs.usableDir(this.musicDir, "music");
        Path logRoot = Path.of(this.logFile, new String[0]).getRoot();
        if (logRoot != null && !Files.exists(logRoot, new LinkOption[0])) {
            this.logFile = DataDirs.dataDir().resolve("app.log").toString();
        }
        // 目录缺失时自动创建：safeChild/guardedChild 的 toRealPath() 容器校验、
        // /restore 与预览都依赖真实路径存在；缺目录会让校验/恢复静默失败
        for (String d : new String[] { this.voicesDir, this.imagesDir, this.musicDir, Paths.get(this.logFile).getParent() == null ? "" : Paths.get(this.logFile).getParent().toString() }) {
            if (d == null || d.isEmpty()) {
                continue;
            }
            try {
                Files.createDirectories(Paths.get(d, new String[0]));
            }
            catch (Exception ignored) {}
        }
    }

    @GetMapping(value={"/options"})
    public Map<String, Object> options() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("orientations", FfmpegCore.GEOM.entrySet().stream().map(e -> {
            LinkedHashMap<String, Object> o = new LinkedHashMap<String, Object>();
            o.put("key", e.getKey());
            o.put("label", e.getKey());
            o.put("size", ((int[])e.getValue())[0] + "x" + ((int[])e.getValue())[1]);
            return o;
        }).toList());
        out.put("voices", FfmpegCore.voiceCatalog());
        out.put("default_voice", FfmpegCore.VOCES.get("zh"));
        out.put("default_style", "cinematic");
        out.put("image_models", Providers.imageModels(Providers.active()));
        out.put("video_models", Providers.videoModels(Providers.active()));
        out.put("default_image_model", Providers.defaultImageModel(Providers.active()));
        out.put("default_video_model", Providers.defaultVideoModel(Providers.active()));
        out.put("text_models", Providers.textModels(Providers.active()));
        out.put("default_text_model", Providers.defaultTextModel(Providers.active()));
        out.put("active_provider", Providers.displayName(Providers.active()));
        // 开放 harness：全部供应商（含内置），UI 生成表单可自选供应商与模型
        Map<String, Object> st = Providers.state();
        java.util.ArrayList<Map<String, Object>> pout = new java.util.ArrayList<>();
        for (Map<String, Object> pp : (java.util.List<Map<String, Object>>)st.get("providers")) {
            Map<String, Object> o2 = new java.util.LinkedHashMap<>();
            o2.put("id", String.valueOf(pp.get("id")));
            o2.put("name", Boolean.TRUE.equals(pp.get("builtin")) ? Providers.displayName(pp) : Providers.str(pp, "name"));
            o2.put("builtin", Boolean.TRUE.equals(pp.get("builtin")));
            o2.put("has_key", !Providers.str(pp, "api_key").isEmpty());
            o2.put("image_models", Providers.imageModels(pp));
            o2.put("default_image_model", Providers.defaultImageModel(pp));
            o2.put("video_models", Providers.videoModels(pp));
            o2.put("default_video_model", Providers.defaultVideoModel(pp));
            o2.put("text_models", Providers.textModels(pp));
            o2.put("default_text_model", Providers.defaultTextModel(pp));
            pout.add(o2);
        }
        out.put("providers", pout);
        out.put("providers_configured", Providers.isUserConfigured());
        out.put("active_provider_id", String.valueOf(st.get("active")));
        // 本地判断（不发网络）：内置凭据是否可用。实时在线状态走 /agnesa-status，
        // 避免 /options 被 provider 探测阻塞（UI 启动重试循环会放大网络延迟）
        out.put("agnesa_ok", !ModelGateway.providerEndpoints("agnesa").isEmpty());
        return out;
    }

    @GetMapping(value={"/agnesa-status"})
    public Map<String, Object> agnesaStatus() {
        return ModelGateway.providerStatus();
    }

    @GetMapping(value={"/provider-config"})
    public Map<String, Object> providerConfig() {
        Map<String, Object> st = Providers.state();
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.putAll(st);
        // 内置条目显示名固定为通用名（厂商字样不落 UI，即使配置文件里存了旧名）
        for (Map<String, Object> pp : (java.util.List<Map<String, Object>>)st.get("providers")) {
            if (Boolean.TRUE.equals(pp.get("builtin"))) {
                pp.put("name", Providers.displayName(pp));
            }
        }
        return out;
    }

    @PostMapping(value={"/provider-config"})
    public Map<String, Object> saveProviderConfig(@RequestBody Map<String, Object> body) {
        List l;
        Object provs = body.get("providers");
        if (!(provs instanceof List) || (l = (List)provs).isEmpty()) {
            return Map.of("ok", false, "error", "providers \u4e0d\u80fd\u4e3a\u7a7a");
        }
        ArrayList saved = new ArrayList();
        for (Object o : l) {
            if (!(o instanceof Map)) continue;
            Map m = (Map)o;
            LinkedHashMap<String, Object> p2 = new LinkedHashMap<String, Object>();
            for (String k : new String[]{"id", "name", "base_url", "api_key", "default_image_model", "default_video_model", "default_text_model"}) {
                p2.put(k, m.get(k) == null ? "" : String.valueOf(m.get(k)).trim());
            }
            if (String.valueOf(p2.get("id")).isEmpty()) {
                p2.put("id", "p" + System.nanoTime() % 100000000L);
            }
            p2.put("builtin", Boolean.TRUE.equals(m.get("builtin")));
            for (String k : new String[]{"image_models", "video_models", "text_models"}) {
                Object v = m.get(k);
                if (v instanceof List) {
                    List ll = (List)v;
                    p2.put(k, new ArrayList(ll));
                    continue;
                }
                p2.put(k, new ArrayList());
            }
            saved.add(p2);
        }
        if (saved.isEmpty()) {
            return Map.of("ok", false, "error", "providers \u683c\u5f0f\u4e0d\u6b63\u786e");
        }
        String active0 = String.valueOf(body.getOrDefault("active", ""));
        String active = saved.stream().anyMatch(p -> active0.equals(String.valueOf(((Map<?, ?>)p).get("id")))) ? active0 : String.valueOf(((Map<?, ?>)saved.get(0)).get("id"));
        LinkedHashMap<String, Object> clean = new LinkedHashMap<String, Object>();
        clean.put("active", active);
        clean.put("providers", saved);
        Providers.save(clean);
        return Map.of("ok", true, "active", active, "count", saved.size());
    }

    @PostMapping(value={"/provider-test"})
    public Map<String, Object> providerTest(@RequestBody Map<String, Object> prov) {
        String base = String.valueOf(prov.getOrDefault("base_url", "")).trim();
        String key = String.valueOf(prov.getOrDefault("api_key", "")).trim();
        String builtinNote = "";
        // 内置供应商的 key 不在 provider-config.json 里（在 config.yaml/env），
        // 卡片上 key 为空时回落到 ModelGateway 内置凭据，否则永远提示"需要 API Key"。
        if (base.isEmpty() || key.isEmpty()) {
            List<String[]> eps = ModelGateway.providerEndpoints();
            if (!eps.isEmpty()) {
                if (base.isEmpty()) {
                    base = eps.get(0)[0];
                }
                if (key.isEmpty()) {
                    key = eps.get(0)[1];
                    builtinNote = "\uff08\u5df2\u4f7f\u7528\u5185\u7f6e\u51ed\u8bc1\uff09";
                }
            }
        }
        if (((String)base).isEmpty() || key.isEmpty()) {
            return Map.of("ok", false, "error", "\u9700\u8981 Base URL \u548c API Key");
        }
        if (!((String)(base = ((String)base).replaceAll("/+$", ""))).endsWith("/v1")) {
            base = (String)base + "/v1";
        }
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15L)).build();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create((String)base + "/models")).header("Authorization", "Bearer " + key).GET().timeout(Duration.ofSeconds(25L)).build(), HttpResponse.BodyHandlers.ofString());
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("status", r.statusCode());
            if (r.statusCode() == 200) {
                ArrayList<String> names = new ArrayList<String>();
                try {
                    JsonNode data;
                    JsonNode j = new ObjectMapper().readTree(r.body());
                    JsonNode jsonNode = data = j.has("data") ? j.get("data") : j;
                    if (data.isArray()) {
                        for (JsonNode n : data) {
                            String id = n.isTextual() ? n.asText() : n.path("id").asText("");
                            if (id.isEmpty()) continue;
                            names.add(id);
                        }
                    }
                }
                catch (Exception exception) {
                    // empty catch block
                }
                out.put("ok", true);
                out.put("models", names);
                out.put("hint", (names.isEmpty() ? "\u8fde\u63a5\u6b63\u5e38" : "\u8fde\u63a5\u6b63\u5e38 \u00b7 \u53d1\u73b0 " + names.size() + "\u4e2a\u6a21\u578b\uff0c\u53ef\u590d\u5236\u586b\u5165\u4e0a\u65b9\u6a21\u578b\u5217\u8868") + builtinNote);
                return out;
            }
            out.put("ok", false);
            out.put("error", "HTTP " + r.statusCode() + (r.statusCode() == 401 || r.statusCode() == 403 ? "\uff08API Key \u88ab\u62d2\u7edd\uff09" : "") + "\uff1a" + r.body().substring(0, Math.min(200, r.body().length())) + (r.statusCode() == 404 || r.statusCode() == 405 ? "\uff08\u8be5\u670d\u52a1\u53ef\u80fd\u4e0d\u652f\u6301 /models \u5217\u8868\uff0c\u751f\u56fe\u63a5\u53e3\u4ecd\u53ef\u76f4\u63a5\u4f7f\u7528\uff09" : ""));
            return out;
        }
        catch (Exception e) {
            return Map.of("ok", false, "error", "\u8fde\u4e0d\u4e0a\uff1a" + e);
        }
    }

    @PostMapping(value={"/script"})
    public Map<String, Object> script(@RequestBody Map<String, Object> body) {
        String model;
        String topic = String.valueOf(body.getOrDefault("topic", "")).trim();
        String style = String.valueOf(body.getOrDefault("style", "")).trim();
        String extra = String.valueOf(body.getOrDefault("extra", "")).trim();
        int n = 6;
        try {
            n = Integer.parseInt(String.valueOf(body.getOrDefault("shots", "6")));
        }
        catch (Exception exception) {
            // empty catch block
        }
        n = Math.max(3, Math.min(15, n));
        String modelOverride = String.valueOf(body.getOrDefault("model", "")).trim();
        String providerId = String.valueOf(body.getOrDefault("provider_id", "")).trim();
        if (topic.isEmpty()) {
            return Map.of("ok", false, "error", "\u8bf7\u8f93\u5165\u6545\u4e8b\u4e3b\u9898");
        }
        Map<String, Object> prov = Providers.byid(providerId);
        boolean builtin = Boolean.TRUE.equals(prov.get("builtin"));
        String string = model = !modelOverride.isEmpty() ? modelOverride : Providers.defaultTextModel(prov);
        if (model.isEmpty() || !builtin && !Providers.modelList(prov, "text_models").stream().anyMatch(x -> !x.isEmpty()) && Providers.str(prov, "default_text_model").isEmpty()) {
            return Map.of("ok", false, "error", "\u5f53\u524d\u4f9b\u5e94\u5546\u6ca1\u6709\u53ef\u7528\u7684\u6587\u672c\u6a21\u578b\u2014\u2014\u8bf7\u5728\u300c\u6a21\u578b\u914d\u7f6e\u300d\u91cc\u586b\u5199\u6587\u672c\u6a21\u578b\uff08\u70b9\u300c\u6d4b\u8bd5\u8fde\u63a5\u300d\u53ef\u62c9\u53d6\u6a21\u578b\u5217\u8868\uff09");
        }
        List<String[]> eps = ModelGateway.providerEndpoints(providerId);
        if (eps.isEmpty()) {
            return Map.of("ok", false, "error", "\u5f53\u524d\u4f9b\u5e94\u5546\u6ca1\u6709\u53ef\u7528\u7684 API Key");
        }
        String host = ModelGateway.splitHost(eps.get(0)[0]);
        String sys = "\u4f60\u662f\u64c5\u957f\u5199\u6296\u97f3/\u89c6\u9891\u53f7\u7206\u6b3e\u77ed\u89c6\u9891\u7684\u7f16\u5267\u3002\u6839\u636e\u7528\u6237\u4e3b\u9898\u5199\u4e00\u4e2a\u77ed\u89c6\u9891\u5267\u672c\uff0c\u53ea\u8f93\u51fa\u4e00\u4e2a JSON \u5bf9\u8c61\uff08\u4e0d\u8981\u89e3\u91ca\u3001\u4e0d\u8981 markdown \u4ee3\u7801\u5757\uff09\uff0c\u7ed3\u6784\u56fa\u5b9a\u4e3a\uff1a\n{\"title\":\"\u6807\u9898\",\"logline\":\"\u4e00\u53e5\u8bdd\u5356\u70b9\",\"characters\":[{\"name\":\"\u89d2\u8272\u540d\",\"appearance\":\"\u4f9b\u751f\u56fe\u7684\u5916\u89c2\u63cf\u8ff0\uff08\u4e2d\u6587\u3001\u5177\u4f53\u3001\u753b\u9762\u611f\uff0c\u4e0d\u542b\u5c4f\u5e55\u6587\u5b57\uff09\",\"voice\":\"\u97f3\u8272\u63cf\u8ff0\uff08\u53ef\u9009\uff09\"}],\"scenes\":[{\"character\":\"\u672c\u955c\u51fa\u955c\u89d2\u8272\u540d\uff08\u65e0\u89d2\u8272\u5219\u7a7a\u5b57\u7b26\u4e32\uff09\",\"background\":\"\u4f9b\u751f\u56fe\u7684\u573a\u666f/\u80cc\u666f\u63cf\u8ff0\",\"shot\":\"\u4f9b\u751f\u56fe\u7684\u753b\u9762\u63cf\u8ff0\",\"dialogue\":\"\u672c\u955c\u53f0\u8bcd\u6216\u65c1\u767d\uff08\u53e3\u8bed\u5316\u3001\u6709\u94a9\u5b50\uff09\",\"seconds\":5}]}\n\u8981\u6c42\uff1ascenes \u957f\u5ea6\u5fc5\u987b\u7b49\u4e8e\u7528\u6237\u6307\u5b9a\u955c\u5934\u6570\uff1bcharacters \u6700\u591a 3 \u4e2a\uff1b\u6bcf\u4e2a\u955c\u5934\u7684 background/shot/dialogue \u975e\u7a7a\uff1bseconds \u53d6 4-12 \u7684\u6574\u6570\uff1bscenes \u91cc\u5f15\u7528\u7684 character \u5fc5\u987b\u6765\u81ea characters\u3002";
        String user = "\u4e3b\u9898\uff1a" + topic + "\n\u98ce\u683c\uff1a" + (style.isEmpty() ? "\u9ed8\u8ba4" : style) + "\n\u955c\u5934\u6570\uff1a" + n + (String)(extra.isEmpty() ? "" : "\n\u989d\u5916\u8981\u6c42\uff1a" + extra);
        try {
            ObjectNode payload = JSON2.createObjectNode();
            payload.put("model", model);
            payload.put("temperature", 0.9);
            payload.put("max_tokens", 3000);
            payload.put("stream", false);
            ArrayNode msgs = payload.putArray("messages");
            ObjectNode s1 = msgs.addObject();
            s1.put("role", "system");
            s1.put("content", sys);
            ObjectNode s2 = msgs.addObject();
            s2.put("role", "user");
            s2.put("content", user);
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15L)).build();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(host + "/v1/chat/completions")).header("Authorization", "Bearer " + eps.get(0)[1]).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)).timeout(Duration.ofSeconds(120L)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 400) {
                return Map.of("ok", false, "error", "HTTP " + r.statusCode() + (r.statusCode() == 401 || r.statusCode() == 403 ? "\uff08API Key \u88ab\u62d2\u7edd\uff09" : "") + "\uff1a" + r.body().substring(0, Math.min(200, r.body().length())) + (r.statusCode() == 404 ? "\uff08\u8be5\u670d\u52a1\u4e0d\u652f\u6301 /chat/completions \u6216\u6a21\u578b\u540d\u4e0d\u5bf9\uff09" : ""));
            }
            JsonNode root = JSON2.readTree(r.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("").trim();
            if (content.isEmpty()) {
                return Map.of("ok", false, "error", "\u6a21\u578b\u8fd4\u56de\u4e3a\u7a7a");
            }
            JsonNode parsed = ApiController.extractJson(content);
            if (parsed == null) {
                return Map.of("ok", true, "script", null, "raw", content, "model", model, "provider", Providers.str(prov, "name"), "note", "\u6a21\u578b\u672a\u8fd4\u56de\u53ef\u89e3\u6790\u7684 JSON\uff0c\u5df2\u7ed9\u51fa\u539f\u6587\uff0c\u53ef\u590d\u5236\u540e\u624b\u52a8\u586b\u5165\u8868\u5355");
            }
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", true);
            out.put("script", ApiController.normalizeScript(parsed, n));
            out.put("model", model);
            out.put("provider", Providers.str(prov, "name"));
            return out;
        }
        catch (Exception e) {
            return Map.of("ok", false, "error", "\u5267\u672c\u751f\u6210\u5931\u8d25\uff1a" + e);
        }
    }

    private static JsonNode extractJson(String text) {
        String t = text.replace("```json", "```").trim();
        int start = t.indexOf(123);
        if (start < 0) {
            return null;
        }
        for (int end = t.length(); end > start; --end) {
            if (t.charAt(end - 1) != '}') continue;
            String cand = t.substring(start, end);
            try {
                JsonNode j = JSON2.readTree(cand);
                if (j.isObject()) {
                    return j;
                }
            }
            catch (Exception exception) {}
            break;
        }
        try {
            JsonNode j = JSON2.readTree(t.substring(start));
            return j.isObject() ? j : null;
        }
        catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> normalizeScript(JsonNode j, int wantedShots) {
        LinkedHashMap<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("title", j.path("title").asText("").trim());
        s.put("logline", j.path("logline").asText("").trim());
        ArrayList chars = new ArrayList();
        for (JsonNode c : j.path("characters")) {
            LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
            m.put("name", c.path("name").asText(""));
            m.put("appearance", c.path("appearance").asText(""));
            m.put("voice", c.path("voice").asText(""));
            if (String.valueOf(m.get("name")).isEmpty()) continue;
            chars.add(m);
        }
        s.put("characters", chars);
        ArrayList scenes = new ArrayList();
        for (JsonNode c : j.path("scenes")) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("character", c.path("character").asText(""));
            m.put("background", c.path("background").asText(""));
            m.put("shot", c.path("shot").asText(""));
            m.put("dialogue", c.path("dialogue").asText(""));
            int sec = 5;
            try {
                sec = Integer.parseInt(c.path("seconds").asText("5"));
            }
            catch (Exception exception) {
                // empty catch block
            }
            m.put("seconds", Math.max(4, Math.min(12, sec)));
            scenes.add(m);
        }
        s.put("scenes", scenes);
        return s;
    }

    @PostMapping(value={"/agnes-image"})
    public Map<String, Object> agnesImage(@RequestBody AgnesImageRequest req) {
        Map<String, Object> prov = Providers.byid(req.providerId);
        String model = req.model == null || req.model.trim().isEmpty() ? Providers.defaultImageModel(prov) : req.model.trim();
        return ModelGateway.generateImage(req.prompt, req.size, model, null, req.providerId);
    }

    @PostMapping(value={"/images/upload"})
    public ResponseEntity<Map<String, Object>> imagesUpload(@RequestParam(value="file") MultipartFile file) {
        byte[] data;
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = ApiController.extOf(original).toLowerCase(Locale.ROOT);
        if (!IMG_EXTS.contains(ext)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "detail", "unsupported image type '" + ext + "'; allowed: " + String.join((CharSequence)", ", IMG_EXTS)));
        }
        try {
            data = file.getBytes();
        }
        catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "detail", String.valueOf(e)));
        }
        if (data.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "detail", "empty file"));
        }
        if ((long)data.length > 0x3200000L) {
            return ResponseEntity.status((int)413).body(Map.of("ok", false, "detail", "image file too large (> 50MB)"));
        }
        Path dir = Paths.get(this.imagesDir, new String[0]);
        try {
            Files.createDirectories(dir, new FileAttribute[0]);
            String stem = FfmpegCore.safeStem(original, "image");
            Path dest = dir.resolve(stem + ext);
            int n = 1;
            while (Files.exists(dest, new LinkOption[0])) {
                dest = dir.resolve(stem + "_" + n++ + ext);
            }
            Files.write(dest, data, new OpenOption[0]);
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", true);
            out.put("name", dest.getFileName().toString());
            out.put("path", dest.toString());
            out.put("size", data.length);
            return ResponseEntity.ok(out);
        }
        catch (Exception e) {
            return ResponseEntity.status((int)500).body(Map.of("ok", false, "detail", "save failed: " + e));
        }
    }

    @GetMapping(value={"/images/preview"}, produces={"image/png"})
    public ResponseEntity<InputStreamResource> imagesPreview(@RequestParam(name="name", required=false) String name, @RequestHeader(value="Range", required=false) String range) {
        Path p = this.safeChild(Paths.get(this.imagesDir, new String[0]), name, IMG_EXTS);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        return this.serveFile(p, "image", range);
    }

    /* ============ AE-style project management (home: new / open / recent) ============ */

    @GetMapping(value={"/projects"})
    public Map<String, Object> projectsList() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            out.put("ok", true);
            out.put("projects", Projects.list());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("detail", "list projects failed: " + e);
        }
        return out;
    }

    @PostMapping(value={"/projects"})
    public Map<String, Object> projectsCreate(@RequestBody Map<String, Object> body) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            String name = String.valueOf(body.getOrDefault("name", "未命名项目"));
            String id = Projects.create(name);
            out.put("ok", true);
            out.put("id", id);
            out.put("name", name);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("detail", "create project failed: " + e);
        }
        return out;
    }

    @GetMapping(value={"/projects/{id}"})
    public Map<String, Object> projectsOpen(@PathVariable("id") String id) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            out.putAll(Projects.open(id));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("detail", "open project failed: " + e);
        }
        return out;
    }

    @PostMapping(value={"/projects/{id}/save"})
    public Map<String, Object> projectsSave(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            out.putAll(Projects.save(id, body));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("detail", "save project failed: " + e);
        }
        return out;
    }

    @DeleteMapping(value={"/projects/{id}"})
    public Map<String, Object> projectsDelete(@PathVariable("id") String id) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        boolean ok = Projects.delete(id);
        out.put("ok", ok);
        if (!ok) out.put("detail", "project not found: " + id);
        return out;
    }

    @GetMapping(value={"/projects/{id}/asset"}, produces={"image/png"})
    public ResponseEntity<InputStreamResource> projectsAsset(@PathVariable("id") String id, @RequestParam(name="name", required=false) String name, @RequestHeader(value="Range", required=false) String range) {
        Path p = Projects.asset(id, name);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        return this.serveFile(p, "image", range);
    }

    @GetMapping(value={"/voices"})
    public Map<String, Object> voicesList() {
        ArrayList out = new ArrayList();
        Path dir = Paths.get(this.voicesDir, new String[0]);
        if (Files.isDirectory(dir, new LinkOption[0])) {
            try (Stream<Path> stream2 = Files.list(dir);){
                List<Path> files = stream2.sorted().toList();
                for (Path p : files) {
                    if (!Files.isRegularFile(p, new LinkOption[0]) || !VOICE_EXTS.contains(ApiController.extOf(p.toString()).toLowerCase(Locale.ROOT))) continue;
                    LinkedHashMap<String, Object> e = new LinkedHashMap<String, Object>();
                    e.put("name", p.getFileName().toString());
                    e.put("path", p.toString());
                    e.put("size", Files.size(p));
                    Double d = FfmpegCore.ffprobeDuration(p.toString());
                    e.put("duration_s", d == null ? 0.0 : (double)Math.round(d * 100.0) / 100.0);
                    out.add(e);
                }
            }
            catch (Exception stream2) {
                // empty catch block
            }
        }
        LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("ok", true);
        r.put("dir", this.voicesDir);
        r.put("voices", out);
        return r;
    }

    @PostMapping(value={"/voices/upload"})
    public ResponseEntity<Map<String, Object>> voicesUpload(@RequestParam(value="file") MultipartFile file) {
        byte[] data;
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = ApiController.extOf(original).toLowerCase(Locale.ROOT);
        if (!VOICE_EXTS.contains(ext)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "detail", "unsupported audio type '" + ext + "'; allowed: " + String.join((CharSequence)", ", VOICE_EXTS)));
        }
        try {
            data = file.getBytes();
        }
        catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "detail", String.valueOf(e)));
        }
        if (data.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "detail", "empty file"));
        }
        if ((long)data.length > 0x3200000L) {
            return ResponseEntity.status((int)413).body(Map.of("ok", false, "detail", "voice file too large (> 50MB)"));
        }
        try {
            Path dir = Paths.get(this.voicesDir, new String[0]);
            Files.createDirectories(dir, new FileAttribute[0]);
            String stem = FfmpegCore.safeStem(original, "voice");
            Path dest = dir.resolve(stem + ext);
            int n = 1;
            while (Files.exists(dest, new LinkOption[0])) {
                dest = dir.resolve(stem + "_" + n++ + ext);
            }
            Files.write(dest, data, new OpenOption[0]);
            Double d = FfmpegCore.ffprobeDuration(dest.toString());
            LinkedHashMap<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("name", dest.getFileName().toString());
            entry.put("path", dest.toString());
            entry.put("size", data.length);
            entry.put("duration_s", d == null ? 0.0 : (double)Math.round(d * 100.0) / 100.0);
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.valueOf(true));
            out.put("voice", entry);
            return ResponseEntity.ok(out);
        }
        catch (Exception e) {
            return ResponseEntity.status((int)500).body(Map.of("ok", false, "detail", "save failed: " + e));
        }
    }

    @GetMapping(value={"/voices/preview"})
    public ResponseEntity<InputStreamResource> voicesPreview(@RequestParam(name="name", required=false) String name, @RequestHeader(value="Range", required=false) String range) {
        Path p = this.safeChild(Paths.get(this.voicesDir, new String[0]), name, VOICE_EXTS);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        return this.serveFile(p, "audio", range);
    }

    @GetMapping(value={"/music"})
    public Map<String, Object> musicList() {
        ArrayList out = new ArrayList();
        Path dir = Paths.get(this.musicDir, new String[0]);
        if (Files.isDirectory(dir, new LinkOption[0])) {
            try (Stream<Path> stream2 = Files.list(dir);){
                List<Path> files = stream2.sorted().toList();
                for (Path p : files) {
                    if (!Files.isRegularFile(p, new LinkOption[0]) || !MUSIC_EXTS.contains(ApiController.extOf(p.toString()).toLowerCase(Locale.ROOT))) continue;
                    LinkedHashMap<String, Object> e = new LinkedHashMap<String, Object>();
                    e.put("name", p.getFileName().toString());
                    e.put("path", p.toString());
                    e.put("size", Files.size(p));
                    Double d = FfmpegCore.ffprobeDuration(p.toString());
                    e.put("duration_s", d == null ? 0.0 : (double)Math.round(d * 100.0) / 100.0);
                    out.add(e);
                }
            }
            catch (Exception stream2) {
                // empty catch block
            }
        }
        LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("ok", true);
        r.put("dir", this.musicDir);
        r.put("music", out);
        return r;
    }

    @PostMapping(value={"/music/upload"})
    public ResponseEntity<Map<String, Object>> musicUpload(@RequestParam(value="file") MultipartFile file) {
        byte[] data;
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = ApiController.extOf(original).toLowerCase(Locale.ROOT);
        if (!MUSIC_EXTS.contains(ext)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "detail", "unsupported music type '" + ext + "'; allowed: " + String.join((CharSequence)", ", MUSIC_EXTS)));
        }
        try {
            data = file.getBytes();
        }
        catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "detail", String.valueOf(e)));
        }
        if (data.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "detail", "empty file"));
        }
        if ((long)data.length > 0x3200000L) {
            return ResponseEntity.status((int)413).body(Map.of("ok", false, "detail", "music file too large (> 50MB)"));
        }
        try {
            Path dir = Paths.get(this.musicDir, new String[0]);
            Files.createDirectories(dir, new FileAttribute[0]);
            String stem = FfmpegCore.safeStem(original, "bgm");
            Path dest = dir.resolve(stem + ext);
            int n = 1;
            while (Files.exists(dest, new LinkOption[0])) {
                dest = dir.resolve(stem + "_" + n++ + ext);
            }
            Files.write(dest, data, new OpenOption[0]);
            LinkedHashMap<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("name", dest.getFileName().toString());
            entry.put("path", dest.toString());
            entry.put("size", data.length);
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.valueOf(true));
            out.put("music", entry);
            return ResponseEntity.ok(out);
        }
        catch (Exception e) {
            return ResponseEntity.status((int)500).body(Map.of("ok", false, "detail", "save failed: " + e));
        }
    }

    @GetMapping(value={"/music/preview"})
    public ResponseEntity<InputStreamResource> musicPreview(@RequestParam(name="name", required=false) String name, @RequestHeader(value="Range", required=false) String range) {
        Path p = this.safeChild(Paths.get(this.musicDir, new String[0]), name, MUSIC_EXTS);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        return this.serveFile(p, "audio", range);
    }

    @DeleteMapping(value={"/music/{name}"})
    public Map<String, Object> musicDelete(@PathVariable(value="name") String name) {
        Path p = this.safeChild(Paths.get(this.musicDir, new String[0]), name, MUSIC_EXTS);
        if (p == null) {
            return Map.of("ok", false, "detail", "not found");
        }
        try {
            Files.deleteIfExists(p);
            return Map.of("ok", true, "name", name);
        }
        catch (Exception e) {
            return Map.of("ok", false, "detail", "delete failed: " + e);
        }
    }

    @PostMapping(value={"/make-char-bg"})
    public Map<String, Object> makeCharBg(@RequestBody MakeCharBgRequest req) {
        String bgmRaw;
        if (req.shots == null || req.shots.isEmpty()) {
            return Map.of("ok", false, "error", "\u9700\u8981\u81f3\u5c11\u4e00\u4e2a\u955c\u5934 shots");
        }
        if (req.characters == null || req.characters.isEmpty()) {
            return Map.of("ok", false, "error", "\u9700\u8981\u81f3\u5c11\u4e00\u4e2a\u4eba\u7269\u56fe\u7247\uff08\u4e0a\u4f20\u6216\u63d0\u793a\u8bcd\u751f\u6210\uff09");
        }
        if (req.backgrounds == null || req.backgrounds.isEmpty()) {
            return Map.of("ok", false, "error", "\u9700\u8981\u81f3\u5c11\u4e00\u4e2a\u80cc\u666f\u56fe\u7247\uff08\u4e0a\u4f20\u6216\u63d0\u793a\u8bcd\u751f\u6210\uff09");
        }
        LinkedHashMap<String, String> charAudio = null;
        if (req.charVoices != null && !req.charVoices.isEmpty()) {
            charAudio = new LinkedHashMap<String, String>();
            for (Map.Entry<String, String> e : req.charVoices.entrySet()) {
                String v = e.getValue() == null ? "" : e.getValue().trim();
                if (v.isEmpty()) continue;
                if (v.startsWith("file:")) {
                    Path p = this.safeChild(Paths.get(this.voicesDir, new String[0]), v.substring(5), VOICE_EXTS);
                    if (p == null) continue;
                    charAudio.put(e.getKey(), p.toString());
                    continue;
                }
                charAudio.put(e.getKey(), v);
            }
        }
        Map<String, Object> params = req.toParams();
        params.put("char_audio", charAudio);
        String string = bgmRaw = req.bgm == null ? "" : req.bgm.trim();
        params.put("bgm_requested", bgmRaw); // 记录原始 BGM 名：解析失败/重试时可给出明确告警
        if (!bgmRaw.isEmpty() && !Files.exists(Paths.get(bgmRaw, new String[0]), new LinkOption[0])) {
            Path mp = this.safeChild(Paths.get(this.musicDir, new String[0]), bgmRaw, MUSIC_EXTS);
            params.put("bgm", mp != null ? mp.toString() : "");
        }
        for (Object list : new Object[]{params.get("characters"), params.get("backgrounds")}) {
            if (!(list instanceof List)) continue;
            List l = (List)list;
            for (Object o : l) {
                Map em;
                String img;
                if (!(o instanceof Map) || (img = String.valueOf((em = (Map)o).get("image")).trim()).isEmpty() || Files.exists(Paths.get(img, new String[0]), new LinkOption[0])) continue;
                String bare = img.replace('\\', '/');
                bare = bare.substring(bare.lastIndexOf(47) + 1);
                Path resolved = this.safeChild(Paths.get(this.imagesDir, new String[0]), bare, IMG_EXTS);
                if (resolved == null) continue;
                em.put("image", resolved.toString());
            }
        }
        String jobId = this.startJob(params);
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("job_id", jobId);
        out.put("status", "running");
        out.put("note", "\u4efb\u52a1\u5df2\u5f00\u59cb\uff0c\u5728\u4e0b\u65b9\u300c\u8fd1\u671f\u4f5c\u54c1\u300d\u91cc\u67e5\u770b\u8fdb\u5ea6 / \u53d6\u6d88 / \u91cd\u8bd5");
        return out;
    }

    private String startJob(Map<String, Object> params) {
        List<Map<String, Object>> shots = ApiController.asMapList(params.get("shots"));
        String firstText = shots.isEmpty() ? "" : FfmpegCore.str(shots.get(0).get("text"));
        String snippet = firstText.substring(0, Math.min(24, firstText.length()));
        LinkedHashMap<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("topic", "charbg:" + (snippet.isEmpty() ? "\u4eba\u7269\u80cc\u666f" : snippet));
        meta.put("style", "char-bg");
        meta.put("orientation", FfmpegCore.str(params.get("orientation")));
        meta.put("status", "running");
        meta.put("progress", ApiController.progressMap(0, shots.size() + 3, "\u6392\u961f\u4e2d\u2026", 0));
        meta.put("params", params);
        meta.put("started_at", System.currentTimeMillis() / 1000L);
        String jobId = Jobs.register(meta);
        AtomicBoolean cancel = new AtomicBoolean(false);
        CANCELS.put(jobId, cancel);
        EXECUTOR.submit(() -> this.runJob(jobId, params, cancel));
        return jobId;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void runJob(final String jobId, Map<String, Object> params, final AtomicBoolean cancel) {
        int total = ApiController.asMapList(params.get("shots")).size() + 3;
        boolean holdSem = false;
        try {
            Map<String, Object> result;
            holdSem = RENDER_SEM.tryAcquire();
            if (!holdSem) {
                Jobs.update(jobId, Map.of("progress", ApiController.progressMap(0, total, "\u6392\u961f\u4e2d\uff08\u524d\u9762\u8fd8\u6709\u4efb\u52a1\u5728\u6e32\u67d3\uff09", 0)));
                try {
                    RENDER_SEM.acquire();
                    holdSem = true;
                }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    Jobs.update(jobId, Map.of("status", "failed", "error", "\u7b49\u5f85\u6e32\u67d3\u961f\u5217\u65f6\u88ab\u4e2d\u65ad"));
                    CANCELS.remove(jobId);
                    if (holdSem) {
                        RENDER_SEM.release();
                    }
                    return;
                }
            }
            RENDER_OWNER.set(jobId);
            List<Map<String, Object>> shots = ApiController.asMapList(params.get("shots"));
            List<Map<String, Object>> chars = ApiController.asMapList(params.get("characters"));
            List<Map<String, Object>> bgs = ApiController.asMapList(params.get("backgrounds"));
            // 镜头引用兜底：名称为空或在实体里查不到（UI 数据错位/旧项目）时，人物/背景指回第一个实体，避免"全部图片路径缺失"
            if (!chars.isEmpty() || !bgs.isEmpty()) {
                List<String> charNames = new ArrayList<>();
                for (Map<String, Object> m : chars) {
                    charNames.add(FfmpegCore.str(m.get("name")));
                }
                List<String> bgNames = new ArrayList<>();
                for (Map<String, Object> m : bgs) {
                    bgNames.add(FfmpegCore.str(m.get("name")));
                }
                for (Map<String, Object> s : shots) {
                    String cn = FfmpegCore.str(s.get("character"));
                    if ((!cn.isEmpty() && !charNames.contains(cn)) || (cn.isEmpty() && !chars.isEmpty())) {
                        s.put("character", FfmpegCore.str(((Map<String, Object>) chars.get(0)).get("name")));
                    }
                    String c2 = FfmpegCore.str(s.get("character2"));
                    if (!c2.isEmpty() && !charNames.contains(c2)) {
                        s.put("character2", ""); // 查不到的第二人物降级为单人镜头
                    }
                    String bn = FfmpegCore.str(s.get("background"));
                    if ((!bn.isEmpty() && !bgNames.contains(bn)) || (bn.isEmpty() && !bgs.isEmpty())) {
                        s.put("background", FfmpegCore.str(((Map<String, Object>) bgs.get(0)).get("name")));
                    }
                }
            }
            String orientation = FfmpegCore.str(params.get("orientation")).isEmpty() ? "vertical" : FfmpegCore.str(params.get("orientation"));
            boolean subtitles = Boolean.TRUE.equals(params.get("subtitles"));
            String bgm = FfmpegCore.str(params.get("bgm"));
            if (!bgm.isEmpty() && !Files.exists(Paths.get(bgm, new String[0]))) {
                bgm = ""; // 入队后 BGM 被删/移动 → 降级为无 BGM（下方补告警），不让整个任务失败
            }
            double bgmVol = ApiController.num(params.get("bgm_vol"), 0.18);
            boolean narration = !Boolean.FALSE.equals(params.get("narration"));
            Map<String, String> voices = ApiController.strMap(params.get("voices"));
            Map<String, String> localAudio = ApiController.strMap(params.get("local_audio"));
            Map<String, String> charAudio = ApiController.strMap(params.get("char_audio"));
            String defaultVoice = FfmpegCore.str(params.get("default_voice"));
            String model = FfmpegCore.str(params.get("model"));
            String providerId = FfmpegCore.str(params.get("provider_id"));
            int fps = ApiController.num(params.get("fps"), 30);
            double charScale = ApiController.num(params.get("char_scale"), 0.68);
            String charPos = FfmpegCore.str(params.get("char_pos")).isEmpty() ? "bottom-left" : FfmpegCore.str(params.get("char_pos"));
            String styleHint = FfmpegCore.str(params.get("style_hint")).isEmpty() ? "cinematic, natural motion, no on-screen text" : FfmpegCore.str(params.get("style_hint"));
            String outDir = FfmpegCore.str(params.get("out_dir")).isEmpty() ? FfmpegCore.assetRoot().resolve("ui").toString() : FfmpegCore.str(params.get("out_dir"));
            double pollDeadlineS = ApiController.num(params.get("poll_deadline_s"), 900.0);
            int queueRetries = ApiController.num(params.get("queue_retries"), 4);
            String quality = FfmpegCore.str(params.get("quality")).isEmpty() ? "1080p" : FfmpegCore.str(params.get("quality"));
            int subFontSize = switch (FfmpegCore.str(params.get("sub_size")).toLowerCase(Locale.ROOT)) {
                case "small" -> 12;
                case "large" -> 22;
                default -> 16;
            };
            CharBgComposer.Progress prog = new CharBgComposer.Progress(){

                @Override
                public void report(int step, int t, String message) {
                    int pct = t > 1 ? (int)(100.0 * (double)step / (double)(t - 1)) : 100;
                    Jobs.update(jobId, Map.of("progress", ApiController.progressMap(step, t, message, pct)));
                }

                @Override
                public boolean cancelled() {
                    if (cancel.get()) {
                        // 只杀本任务自己的 ffmpeg：排队任务此时尚未启动 ffmpeg，
                        // 误杀会波及前一个正在渲染的任务（历史 bug）
                        if (jobId.equals(RENDER_OWNER.get())) {
                            FfmpegCore.killCurrentFfmpeg();
                        }
                    }
                    return cancel.get();
                }
            };
            try {
                result = CharBgComposer.makeCharBgVideo(shots, chars, bgs, orientation, subtitles, bgm.isEmpty() ? null : bgm, bgmVol, narration, voices, localAudio, charAudio, defaultVoice, model, providerId, fps, charScale, charPos, styleHint, outDir, pollDeadlineS, queueRetries, quality, subFontSize, prog);
            }
            catch (Exception e) {
                result = Map.of("ok", false, "error", "pipeline failed: " + e, "stage", "pipeline");
            }
            if (cancel.get() || Boolean.TRUE.equals(result.get("cancelled"))) {
                Jobs.update(jobId, Map.of("status", "cancelled", "error", "\u5df2\u53d6\u6d88\uff08\u7528\u6237\u8bf7\u6c42\u505c\u6b62\uff09", "progress", ApiController.progressMap(total, total, "\u5df2\u53d6\u6d88", 100)));
            } else if (Boolean.TRUE.equals(result.get("ok"))) {
                List l2;
                Object ef;
                List l;
                LinkedHashMap<String, Object> fin = new LinkedHashMap<String, Object>();
                fin.put("status", "done");
                fin.put("video", result.get("video"));
                fin.put("duration_s", result.get("duration_s"));
                fin.put("size", result.get("size"));
                fin.put("subtitles", result.get("subtitles"));
                fin.put("elapsed_s", result.get("elapsed_s"));
                fin.put("progress", ApiController.progressMap(total, total, "完成", 100));
                // 窗口内播放器：每个完成的任务都附带一份"窗口版"（默认 mpeg4，或诊断选定的编码），
                // 窗口内直接播放用；失败不影响任务本身（原 h264 仍可浏览器/下载）
                try {
                    String pc = this.effPlayCodec();
                    if (!"h264".equals(pc)) {
                        String winV = this.transcodeWin(Paths.get(FfmpegCore.str(result.get("video")), new String[0]), pc);
                        if (!winV.isEmpty()) {
                            fin.put("video_win", winV);
                            fin.put("play_codec", pc);
                        }
                    }
                }
                catch (Exception ignored) {
                }
                // 声源 / 模型 随结果落盘，播放器点历史作品时可显示完整 meta
                Object as = result.get("audio_sources");
                if (as instanceof Map && !((Map<?, ?>)as).isEmpty()) {
                    fin.put("audio_sources", as);
                }
                Object md = result.get("model");
                if (md instanceof String ms && !ms.isBlank()) {
                    fin.put("model", ms);
                }
                Object vw = result.get("voice_warnings");
                if (vw instanceof List && !(l = (List)vw).isEmpty()) {
                    fin.put("voice_warnings", vw);
                }
                if ((ef = result.get("engine_fallbacks")) instanceof List && !(l2 = (List)ef).isEmpty()) {
                    fin.put("engine_fallbacks", ef);
                }
                // 用户选了 BGM 但文件在库里找不到 → 静默降级会困惑，补一条可见告警
                String bgmReq = FfmpegCore.str(params.get("bgm_requested"));
                if (!bgmReq.isEmpty() && bgm.isEmpty()) {
                    List<String> warns = fin.get("voice_warnings") instanceof List ll ? new ArrayList<>(ll) : new ArrayList<>();
                    warns.add("BGM\u300c" + bgmReq + "\u300d\u672a\u5728\u97f3\u4e50\u5e93\u627e\u5230\uff0c\u5df2\u7701\u7565\u80cc\u666f\u97f3\u4e50");
                    fin.put("voice_warnings", warns);
                }
                Jobs.update(jobId, fin);
            } else {
                Jobs.update(jobId, Map.of("status", "failed", "error", String.valueOf(result.get("error")), "stage", String.valueOf(result.get("stage")), "progress", ApiController.progressMap(total, total, "\u5931\u8d25", 100)));
            }
        }
        finally {
            CANCELS.remove(jobId);
            if (holdSem) {
                RENDER_SEM.release();
                if (jobId.equals(RENDER_OWNER.get())) {
                    RENDER_OWNER.set(null);
                }
            }
        }
    }

    /**
     * A single daemon sweep (10s cadence) that removes leftover backup temp
     * zips older than 10 minutes. Started once per app launch.
     */
    static void startBackupTempSweeper() {
        if (SWEEPER_STARTED) {
            return;
        }
        synchronized (ApiController.class) {
            if (SWEEPER_STARTED) {
                return;
            }
            SWEEPER_STARTED = true;
            Thread sw = new Thread(() -> {
                java.nio.file.Path tmpRoot = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
                while (!Thread.interrupted()) {
                    try (java.util.stream.Stream<java.nio.file.Path> ls = java.nio.file.Files.list(tmpRoot)) {
                        ls.filter(f -> {
                            String n = f.getFileName() == null ? "" : f.getFileName().toString();
                            if (!n.startsWith("aivideo-backup") || !n.endsWith(".zip")) {
                                return false;
                            }
                            try {
                                return java.nio.file.Files.getLastModifiedTime(f).toMillis() < System.currentTimeMillis() - 600000L;
                            }
                            catch (Exception e) {
                                return false;
                            }
                        }).forEach(f -> {
                            try {
                                java.nio.file.Files.deleteIfExists(f);
                            }
                            catch (Exception ignored) {
                            }
                        });
                    }
                    catch (Exception ignored2) {
                    }
                    try {
                        Thread.sleep(10000L);
                    }
                    catch (InterruptedException ie) {
                        break;
                    }
                }
            }, "aivideo-backup-sweep");
            sw.setDaemon(true);
            sw.start();
        }
    }

    private static Map<String, Object> progressMap(int step, int total, String message, int percent) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("step", step);
        m.put("total", total);
        m.put("message", message);
        m.put("percent", percent);
        return m;
    }

    private static List<Map<String, Object>> asMapList(Object v) {
        ArrayList<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (v instanceof List) {
            List l = (List)v;
            for (Object o : l) {
                if (!(o instanceof Map)) continue;
                Map m = (Map)o;
                out.add(m);
            }
        }
        return out;
    }

    private static Map<String, String> strMap(Object v) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>)v;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    private static int num(Object v, int dflt) {
        try {
            int n;
            if (v instanceof Number) {
                Number n2 = (Number)v;
                n = n2.intValue();
            } else {
                n = Integer.parseInt(String.valueOf(v));
            }
            return n;
        }
        catch (Exception e) {
            return dflt;
        }
    }

    private static double num(Object v, double dflt) {
        try {
            double d;
            if (v instanceof Number) {
                Number n = (Number)v;
                d = n.doubleValue();
            } else {
                d = Double.parseDouble(String.valueOf(v));
            }
            return d;
        }
        catch (Exception e) {
            return dflt;
        }
    }

    @GetMapping(value={"/recent"})
    public Map<String, Object> recent() {
        ArrayList live = new ArrayList();
        for (Map<String, Object> j : Jobs.load()) {
            boolean inFlight;
            String video = FfmpegCore.str(j.get("video"));
            String status = FfmpegCore.str(j.get("status"));
            boolean hasVideo = !video.isEmpty() && Files.exists(Paths.get(video, new String[0]), new LinkOption[0]);
            boolean bl = inFlight = "running".equals(status) || "failed".equals(status) || "cancelled".equals(status);
            if (!hasVideo && !inFlight) continue;
            LinkedHashMap<String, Object> o = new LinkedHashMap<String, Object>();
            o.put("id", j.get("id"));
            o.put("topic", j.get("topic"));
            o.put("style", j.get("style"));
            o.put("duration_s", j.get("duration_s"));
            o.put("created_at", j.get("created_at"));
            o.put("status", hasVideo ? "done" : (status.isEmpty() ? "done" : status));
            o.put("has_video", hasVideo);
            if (j.get("progress") != null) {
                o.put("progress", j.get("progress"));
            }
            if (j.get("error") != null) {
                o.put("error", j.get("error"));
            }
            if (j.get("voice_warnings") != null) {
                o.put("voice_warnings", j.get("voice_warnings"));
            }
            live.add(o);
        }
        LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("ok", true);
        r.put("jobs", live);
        return r;
    }

    @GetMapping(value={"/jobs/{jobId}"})
    public ResponseEntity<Map<String, Object>> jobDetail(@PathVariable(value="jobId") String jobId) {
        Map<String, Object> j = Jobs.find(jobId);
        if (j == null) {
            return ResponseEntity.status((int)404).body(Map.of("ok", false, "error", "\u672a\u627e\u5230\u8be5\u4f5c\u54c1"));
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>(j);
        String status = FfmpegCore.str(j.get("status"));
        out.put("cancelable", "running".equals(status));
        out.put("retryable", "failed".equals(status) || "cancelled".equals(status));
        out.put("video_exists", j.get("video") != null && Files.exists(Paths.get(FfmpegCore.str(j.get("video")), new String[0]), new LinkOption[0]));
        out.remove("params");
        return ResponseEntity.ok(out);
    }

    @PostMapping(value={"/jobs/{jobId}/cancel"})
    public Map<String, Object> cancelJob(@PathVariable(value="jobId") String jobId) {
        Map<String, Object> j = Jobs.find(jobId);
        if (j == null) {
            return Map.of("ok", false, "error", "\u672a\u627e\u5230\u8be5\u4f5c\u54c1");
        }
        AtomicBoolean c = CANCELS.get(jobId);
        if (c == null || !"running".equals(FfmpegCore.str(j.get("status")))) {
            return Map.of("ok", false, "error", "\u4efb\u52a1\u4e0d\u5728\u8fd0\u884c\u4e2d");
        }
        c.set(true);
        // 只有真正持有渲染信号量（正在跑 ffmpeg）的任务才需要杀 ffmpeg；
        // 排队中的任务杀掉的是前一个任务的 ffmpeg —— 历史 bug，会误伤
        if (jobId.equals(RENDER_OWNER.get())) {
            FfmpegCore.killCurrentFfmpeg();
        }
        // 保留当前进度数值（step/total/percent），只换文案；整 map 替换会让 UI 进度条掉回 0%
        Map<String, Object> newProg = new LinkedHashMap<>();
        if (j.get("progress") instanceof Map<?, ?> cp) {
            for (Map.Entry<?, ?> en : cp.entrySet()) {
                if (en.getKey() instanceof String k) {
                    newProg.put(k, en.getValue());
                }
            }
        }
        newProg.put("message", "\u6b63\u5728\u53d6\u6d88\u2026");
        Jobs.update(jobId, Map.of("progress", newProg));
        return Map.of("ok", true, "job_id", jobId, "note", "\u5df2\u8bf7\u6c42\u53d6\u6d88\uff0c\u5c06\u5728\u4e0b\u4e00\u4e2a\u68c0\u67e5\u70b9\u505c\u6b62");
    }

    @PostMapping(value={"/jobs/{jobId}/retry"})
    public Map<String, Object> retryJob(@PathVariable(value="jobId") String jobId) {
        Map<String, Object> j = Jobs.find(jobId);
        if (j == null) {
            return Map.of("ok", false, "error", "\u672a\u627e\u5230\u8be5\u4f5c\u54c1");
        }
        String status = FfmpegCore.str(j.get("status"));
        if ("running".equals(status)) {
            return Map.of("ok", false, "error", "\u4efb\u52a1\u6b63\u5728\u8fd0\u884c\uff0c\u65e0\u9700\u91cd\u8bd5");
        }
        if (!(j.get("params") instanceof Map)) {
            return Map.of("ok", false, "error", "\u6b64\u4f5c\u54c1\u7f3a\u5c11\u53ef\u91cd\u8bd5\u7684\u53c2\u6570\uff08\u65e7\u4efb\u52a1\uff09");
        }
        Map params = (Map)j.get("params");
        String newId = this.startJob(params);
        Jobs.update(jobId, Map.of("status", "retried", "retried_as", newId));
        return Map.of("ok", true, "job_id", newId, "note", "\u5df2\u65b0\u5efa\u4efb\u52a1\u91cd\u8dd1");
    }

    @DeleteMapping(value={"/jobs/{jobId}"})
    public Map<String, Object> deleteJob(@PathVariable(value="jobId") String jobId) {
        if (jobId == null || jobId.isEmpty()) {
            return Map.of("ok", false, "error", "\u9700\u8981 job_id");
        }
        boolean found = Jobs.remove(jobId, true);
        return found ? Map.of("ok", true, "removed", jobId) : Map.of("ok", false, "error", "\u672a\u627e\u5230\u8be5\u4f5c\u54c1\uff08\u53ef\u80fd\u5df2\u88ab\u5220\u9664\uff09");
    }

    @GetMapping(value={"/media"})
    public ResponseEntity<Map<String, Object>> media(@RequestParam(name="job_id", required=false) String jobId) {
        if (jobId == null || jobId.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "missing job_id"));
        }
        Map<String, Object> job = Jobs.find(jobId);
        if (job == null) {
            return ResponseEntity.status((int)404).body(Map.of("detail", "unknown job"));
        }
        String videoPath = FfmpegCore.str(job.get("video"));
        if (videoPath.isEmpty()) {
            // 还在渲染（video 字段完成时才写入）：空路径会解析到工作目录导致 500
            return ResponseEntity.status((int)409).body(Map.of("detail", "视频尚未生成完成"));
        }
        Path path = Paths.get(videoPath, new String[0]);
        if (!Files.exists(path, new LinkOption[0])) {
            return ResponseEntity.status((int)410).body(Map.of("detail", "media no longer on disk"));
        }
        try {
            long size = Files.size(path);
            if (size > 0x2800000L) {
                return ResponseEntity.status((int)413).body(Map.of("detail", "video too large to stream to the pane"));
            }
            byte[] bytes = Files.readAllBytes(path);
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", true);
            out.put("job_id", jobId);
            out.put("mime", "video/mp4");
            out.put("dataUri", "data:video/mp4;base64," + Base64.getEncoder().encodeToString(bytes));
            return ResponseEntity.ok(out);
        }
        catch (Exception e) {
            return ResponseEntity.status((int)500).body(Map.of("detail", "read failed: " + e));
        }
    }

    @GetMapping(value={"/stream"})
    public ResponseEntity<InputStreamResource> stream(@RequestParam(name="job_id", required=false) String jobId, @RequestParam(name="win", required=false) String win, @RequestHeader(value="Range", required=false) String range) {
        if (jobId == null || jobId.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        Map<String, Object> job = Jobs.find(jobId);
        if (job == null) {
            return ResponseEntity.status((int)404).build();
        }
        String videoPath = FfmpegCore.str(job.get("video"));
        if (videoPath.isEmpty()) {
            // 渲染中：空路径会让 serveFile 对工作目录统计出 total=0 → 误导性的 416
            return ResponseEntity.status((int)409).build();
        }
        // 窗口内播放（win=1）：给"窗口版"（默认 mpeg4，或用户探测选定的编码）。
        // 旧作品没有窗口版 → 现场按需转码（幂等，之后秒开），不需要重新生成作品。
        if ("1".equals(win)) {
            String want = this.effPlayCodec();
            if (!"h264".equals(want)) {
                Path srcP = Paths.get(videoPath, new String[0]);
                Path cand = this.winVariantFile(srcP, want);
                if (!Files.isRegularFile(cand)) {
                    synchronized (WIN_LOCK) {
                        if (!Files.isRegularFile(cand)) {
                            try {
                                String made = this.transcodeWin(srcP, want);
                                if (made.isEmpty()) cand = null;
                            }
                            catch (Exception e) {
                                cand = null;
                            }
                        }
                    }
                }
                if (cand != null && Files.isRegularFile(cand)) {
                    videoPath = cand.toString();
                }
                // 转码失败 → 回退原始视频（窗口里可能还是黑屏，但下载/浏览器可用）
            }
        }
        Path path = Paths.get(videoPath, new String[0]);
        if (!Files.exists(path, new LinkOption[0])) {
            return ResponseEntity.status((int)410).build();
        }
        return this.serveFile(path, "video", range);
    }

    /* ---- 窗口内播放器（WebEngine）编码探测：JFX WebEngine 的媒体管线可能解不了 h264，
       探测页实测出可播放的编码后，任务完成时自动多转一版"窗口版"，窗口内直接播放 ---- */

    private static final List<String> WIN_CODEC_ORDER = List.of("h264", "mpeg4", "vp8", "baseline");
    private static final Map<String, String[]> WIN_CODECS = Map.of(
        "h264", new String[]{"H.264（原始）", "mp4"},
        "mpeg4", new String[]{"MPEG-4（窗口友好）", "mp4"},
        "vp8", new String[]{"VP8/OPUS（WebM）", "webm"},
        "baseline", new String[]{"H.264 Baseline", "mp4"});
    private static final Object WIN_LOCK = new Object();
    /** 默认窗口版编码：用户没跑过诊断时就用它（MP4 封装 + MPEG-4 视频，兼容面最广） */
    private static final String WIN_DEFAULT_CODEC = "mpeg4";

    private Path playCodecFile() {
        return DataDirs.dataDir().resolve("play-codec.json");
    }

    /** 用户探测选定的窗口编码（""=未设置；"h264"=无需转码）。 */
    private String playCodec() {
        try {
            Path f = this.playCodecFile();
            if (Files.isRegularFile(f)) {
                JsonNode n = new ObjectMapper().readTree(Files.readAllBytes(f));
                String c = n.path("codec").asText("");
                return c;
            }
        }
        catch (Exception ignored) {
        }
        return "";
    }

    /** 实际生效的窗口版编码：用户探测结果优先，未探测时默认 mpeg4。 */
    private String effPlayCodec() {
        String pc = this.playCodec();
        return pc.isEmpty() ? WIN_DEFAULT_CODEC : pc;
    }

    /** 某编码的窗口版文件路径（编码进文件名，避免不同编码互相覆盖）。 */
    private Path winVariantFile(Path src, String codec) {
        if ("h264".equals(codec)) {
            return src;
        }
        String[] cc = WIN_CODECS.get(codec);
        if (cc == null) {
            return src;
        }
        return src.getParent().resolve("final_subs_win_" + codec + "." + cc[1]);
    }

    /** 把视频转成窗口友好编码（幂等：已存在直接复用）。h264 原样返回自身。 */
    private String transcodeWin(Path video, String codec) {
        if ("h264".equals(codec)) {
            return video.toString();
        }
        String[] cc = WIN_CODECS.get(codec);
        if (cc == null) {
            return "";
        }
        Path out = this.winVariantFile(video, codec);
        if (Files.isRegularFile(out)) {
            return out.toString();
        }
        List<String> args = new ArrayList<>();
        args.add("-i");
        args.add(video.toString());
        switch (codec) {
            case "mpeg4" -> args.addAll(List.of("-c:v", "mpeg4", "-q:v", "7", "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart"));
            case "vp8" -> args.addAll(List.of("-c:v", "libvpx", "-deadline", "realtime", "-cpu-used", "16", "-b:v", "1200k", "-c:a", "libopus", "-b:a", "96k"));
            case "baseline" -> args.addAll(List.of("-c:v", "libx264", "-profile:v", "baseline", "-level", "3.0", "-pix_fmt", "yuv420p", "-crf", "23", "-preset", "fast", "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart"));
            default -> {
                return "";
            }
        }
        args.add(out.toString());
        Map<String, Object> r = FfmpegCore.runFfmpeg(args, video.getParent().toString());
        if (Boolean.TRUE.equals(r.get("ok")) && Files.isRegularFile(out)) {
            return out.toString();
        }
        return "";
    }

    /** 某任务某编码的窗口版文件路径（不存在 = 未转码）。 */
    private Path winVariantPath(String jobId, String codec) {
        Map<String, Object> job = Jobs.find(jobId);
        if (job == null) {
            return null;
        }
        String v = FfmpegCore.str(job.get("video"));
        if (v.isEmpty()) {
            return null;
        }
        Path src = Paths.get(v, new String[0]);
        if (!Files.isRegularFile(src)) {
            return null;
        }
        if ("h264".equals(codec)) {
            return src;
        }
        Path out = this.winVariantFile(src, codec);
        return Files.isRegularFile(out) ? out : null;
    }

    @PostMapping(value={"/playtest/variants"})
    public Map<String, Object> playtestVariants(@RequestBody Map<String, Object> body) {
        String jobId = FfmpegCore.str(body.get("job_id"));
        Map<String, Object> job = Jobs.find(jobId);
        if (job == null || FfmpegCore.str(job.get("video")).isEmpty()) {
            return Map.of("ok", false, "error", "该作品暂无可用视频");
        }
        Path video = Paths.get(FfmpegCore.str(job.get("video")), new String[0]);
        if (!Files.isRegularFile(video)) {
            return Map.of("ok", false, "error", "该作品视频文件已不存在");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String codec : WIN_CODEC_ORDER) {
            String p;
            try {
                p = this.transcodeWin(video, codec);
            }
            catch (Exception e) {
                p = "";
            }
            LinkedHashMap<String, Object> e = new LinkedHashMap<>();
            e.put("codec", codec);
            e.put("label", WIN_CODECS.get(codec)[0]);
            e.put("ready", !p.isEmpty());
            e.put("url", p.isEmpty() ? "" : "/playtest/variant?job_id=" + java.net.URLEncoder.encode(jobId, java.nio.charset.StandardCharsets.UTF_8) + "&codec=" + codec);
            out.add(e);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("job", jobId);
        r.put("variants", out);
        return r;
    }

    @GetMapping(value={"/playtest/variant"}, produces={"video/mp4"})
    public ResponseEntity<InputStreamResource> playtestVariant(@RequestParam(name="job_id") String jobId, @RequestParam(name="codec") String codec, @RequestHeader(value="Range", required=false) String range) {
        Path p = this.winVariantPath(jobId, codec);
        if (p == null) {
            return ResponseEntity.status((int)404).build();
        }
        return this.serveFile(p, "playtest", range);
    }

    @PostMapping(value={"/playtest/result"})
    public Map<String, Object> playtestResultSet(@RequestBody Map<String, Object> body) {
        String codec = FfmpegCore.str(body.get("codec"));
        if (!WIN_CODECS.containsKey(codec)) {
            return Map.of("ok", false, "error", "未知编码");
        }
        try {
            LinkedHashMap<String, Object> doc = new LinkedHashMap<>();
            doc.put("codec", codec);
            doc.put("at", String.valueOf(System.currentTimeMillis()));
            Files.createDirectories(DataDirs.dataDir());
            Files.write(this.playCodecFile(), new ObjectMapper().writeValueAsBytes(doc));
        }
        catch (IOException e) {
            return Map.of("ok", false, "error", "保存失败：" + e.getMessage());
        }
        LinkedHashMap<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("codec", codec);
        return r;
    }

    @GetMapping(value={"/playtest/result"})
    public Map<String, Object> playtestResultGet() {
        String c = this.playCodec();
        LinkedHashMap<String, Object> r = new LinkedHashMap<>();
        r.put("codec", c.isEmpty() ? WIN_DEFAULT_CODEC : c); // 未探测时显示实际默认值
        r.put("probe_set", !c.isEmpty());
        r.put("enabled", !"h264".equals(this.effPlayCodec())); // true = 窗口版需要/会转码
        return r;
    }

    @GetMapping(value={"/healthz"})
    public Map<String, Object> healthz() {
        LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("ok", true);
        r.put("backend", "java");
        r.put("port", "8791");
        r.put("ffmpeg", FfmpegCore.ffmpeg());
        return r;
    }

    @PostMapping(value={"/window/reset"})
    public Map<String, Object> windowReset() {
        LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
        Runnable h = AppShell.windowResetHook;
        if (h != null) {
            h.run();
            r.put("ok", true);
            return r;
        }
        r.put("ok", false);
        r.put("error", "window hook not ready");
        return r;
    }

    @GetMapping(value={"/logs"})
    public Map<String, Object> logs(@RequestParam(name="lines", required=false, defaultValue="200") int lines) {
        ArrayList<String> out = new ArrayList<String>();
        Path p = Paths.get(this.logFile, new String[0]);
        if (Files.isRegularFile(p, new LinkOption[0])) {
            try {
                // One-pass tail: keep a bounded deque of the last `lines`
                // lines without ever loading the whole (possibly 100s of
                // MB) log into heap.
                int cap = Math.max(1, lines);
                java.util.Deque<String> win = new java.util.ArrayDeque<String>(cap);
                try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        win.addLast(line);
                        if (win.size() > cap) {
                            win.removeFirst();
                        }
                    }
                }
                out.addAll(win);
            }
            catch (Exception e) {
                return Map.of("ok", false, "detail", "\u8bfb\u53d6\u65e5\u5fd7\u5931\u8d25: " + e, "file", this.logFile);
            }
        }
        LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("ok", true);
        r.put("file", this.logFile);
        r.put("lines", out);
        return r;
    }

    @GetMapping(value={"/backup"})
    public ResponseEntity<org.springframework.core.io.Resource> backup() {
        Path tmp = null;
        try {
            tmp = this.buildBackupZip();
            String fname = "ai-video-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".zip";
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            h.setContentDisposition(ContentDisposition.builder((String)"attachment").filename(fname, StandardCharsets.UTF_8).build());
            // Stream the temp zip via FileSystemResource (Spring's
            // proven path: correct content-length + range handling).
            // Best-effort cleanup: deleteOnExit so a JVM exit never leaks
            // it, plus a delayed daemon delete for the normal case.
            final Path tmpRef = tmp;
            final org.springframework.core.io.FileSystemResource res = new org.springframework.core.io.FileSystemResource(tmpRef.toFile());
            h.setContentLength(Files.size(tmp));
            h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            // The sweep (started once at boot) removes any aivideo-backup
            // temp zip older than 10 minutes; a file still being streamed
            // is locked on Windows so the delete is a harmless no-op and
            // the file goes on the next sweep after the stream closes.
            return ResponseEntity.ok().headers(h).body(res);
        }
        catch (Exception e) {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                }
                catch (Exception ignored) {
                }
            }
            return ResponseEntity.<org.springframework.core.io.Resource>status((int)500).header("Content-Type", "text/plain").build();
        }
    }

    /** Builds the backup zip in a temp file; returns the path (caller streams it and deletes it). */
    private Path buildBackupZip() throws Exception {
        Path tmp = Files.createTempFile("aivideo-backup", ".zip", new FileAttribute[0]);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp, new OpenOption[0]));){
            this.addDirToZip(zos, "images", Paths.get(this.imagesDir, new String[0]));
            this.addDirToZip(zos, "voices", Paths.get(this.voicesDir, new String[0]));
            this.addDirToZip(zos, "music", Paths.get(this.musicDir, new String[0]));
            this.addFileToZip(zos, "jobs.json", Jobs.file());
            this.addFileToZip(zos, "provider-config.json", Providers.file());
            this.addFileToZip(zos, "app.log", Paths.get(this.logFile, new String[0]));
        }
        return tmp;
    }

    private void addDirToZip(ZipOutputStream zos, String prefix, Path dir) throws IOException {
        if (!Files.isDirectory(dir, new LinkOption[0])) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir, new FileVisitOption[0]);){
            List<Path> files = stream.filter(x$0 -> Files.isRegularFile(x$0, new LinkOption[0])).sorted().toList();
            for (Path f : files) {
                String name = prefix + "/" + dir.relativize(f).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(name));
                zos.write(Files.readAllBytes(f));
                zos.closeEntry();
            }
        }
    }

    private void addFileToZip(ZipOutputStream zos, String name, Path p) throws IOException {
        if (!Files.isRegularFile(p, new LinkOption[0])) {
            return;
        }
        zos.putNextEntry(new ZipEntry(name));
        zos.write(Files.readAllBytes(p));
        zos.closeEntry();
    }

    /*
     * Rewritten (was a CFR decompilation failure in the original jar; the
     * running jar had the real implementation). Restores a backup produced by
     * /backup: media files (images/ voices/ music/) plus jobs.json,
     * provider-config.json and app.log. Entry names are mapped through
     * restoreTarget() so only files under the three media dirs or the three
     * known config files are ever written; entryExtAllowed() adds an
     * extension whitelist so a zip cannot plant arbitrary files (e.g. .exe)
     * into the media dirs.
     */
    @PostMapping(value={"/restore"})
    public Map<String, Object> restore(@RequestParam(value="file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Map.of("ok", false, "error", "\u9700\u8981\u5907\u4efd zip \u6587\u4ef6");
        }
        int restored = 0;
        ArrayList<String> skipped = new ArrayList<String>();
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry ze;
            byte[] buf = new byte[8192];
            while ((ze = zis.getNextEntry()) != null) {
                if (ze.isDirectory()) {
                    continue;
                }
                String entry = ze.getName();
                Path target = this.restoreTarget(entry);
                if (target == null || !this.entryExtAllowed(entry)) {
                    skipped.add(entry);
                    continue;
                }
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent(), new FileAttribute[0]);
                }
                try (OutputStream os = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    int read;
                    while ((read = zis.read(buf)) > 0) {
                        os.write(buf, 0, read);
                    }
                }
                ++restored;
            }
        }
        catch (Exception e) {
            return Map.of("ok", false, "error", "\u6062\u590d\u5931\u8d25: " + e);
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.valueOf(true));
        out.put("restored", Integer.valueOf(restored));
        if (!skipped.isEmpty()) {
            out.put("skipped", skipped);
        }
        return out;
    }

    private boolean entryExtAllowed(String entry) {
        if (entry.startsWith("images/") || entry.startsWith("voices/") || entry.startsWith("music/")) {
            String ext = ApiController.extOf(entry).toLowerCase(Locale.ROOT);
            if (entry.startsWith("images/")) {
                return IMG_EXTS.contains(ext);
            }
            if (entry.startsWith("voices/")) {
                return VOICE_EXTS.contains(ext);
            }
            return MUSIC_EXTS.contains(ext);
        }
        return entry.equals("jobs.json") || entry.equals("provider-config.json") || entry.equals("app.log");
    }

    @ExceptionHandler(value={MultipartException.class})
    public ResponseEntity<Map<String, Object>> multipartError(MultipartException e) {
        return ResponseEntity.badRequest().body(Map.of("ok", false, "detail", "文件上传/恢复需要 multipart 表单（请在界面里选择文件）"));
    }

    /**
     * 通用兜底：任何未被更具体 @ExceptionHandler 捕获的运行时异常，统一返回 JSON
     * {ok:false,error,detail}，避免 Spring 默认吐出 HTML 错误页让前端解析失败。
     * MultipartException 等更具体的处理器会优先命中，不会被此吞并。
     */
    @ExceptionHandler(value={Exception.class})
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        int status = 500;
        if (e instanceof org.springframework.web.server.ResponseStatusException rse) {
            status = rse.getStatusCode().value();
        }
        // 记入 app.log（维护面板「日志」可查）
        try {
            Path p = Paths.get(this.logFile, new String[0]);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8, new java.nio.file.OpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.APPEND });
            try (w) {
                w.write(new Date().toString() + " UNHANDLED " + e.getClass().getName() + " " + msg + System.lineSeparator());
            }
        }
        catch (Exception ignored) {
        }
        return ResponseEntity.status(status).body(Map.of(
            "ok", false,
            "error", "服务器内部异常: " + msg,
            "detail", e.getClass().getSimpleName()));
    }

    private Path restoreTarget(String entry) {
        if (entry.startsWith("images/") && entry.length() > 7) {
            return this.guardedChild(Paths.get(this.imagesDir, new String[0]), entry.substring(7));
        }
        if (entry.startsWith("voices/") && entry.length() > 7) {
            return this.guardedChild(Paths.get(this.voicesDir, new String[0]), entry.substring(7));
        }
        if (entry.startsWith("music/") && entry.length() > 6) {
            return this.guardedChild(Paths.get(this.musicDir, new String[0]), entry.substring(6));
        }
        switch (entry) {
            case "jobs.json": {
                return Jobs.file();
            }
            case "provider-config.json": {
                return Providers.file();
            }
            case "app.log": {
                return Paths.get(this.logFile, new String[0]);
            }
        }
        return null;
    }

    private Path guardedChild(Path root, String rel) {
        if (rel.isEmpty() || rel.contains("..") || rel.startsWith("\\") || rel.startsWith("/")) {
            return null;
        }
        Path p = root.resolve(rel).toAbsolutePath().normalize();
        if (!p.startsWith(root.toAbsolutePath().normalize())) {
            return null;
        }
        try {
            // A restored entry must never be a symlink escape either.
            Files.createDirectories(root); // 根目录缺失时 toRealPath 无法校验（新机器首次 /restore）
            if (!p.toRealPath(new LinkOption[0]).startsWith(root.toRealPath(new LinkOption[0]))) {
                return null;
            }
        }
        catch (Exception e) {
            return null;
        }
        return p;
    }

    private static String extOf(String name) {
        if (name == null) {
            return "";
        }
        int slash = Math.max(name.lastIndexOf(47), name.lastIndexOf(92));
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = base.lastIndexOf(46);
        return dot >= 0 ? base.substring(dot) : "";
    }

    private Path safeChild(Path dir, String name, Set<String> exts) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Path p = dir.resolve(name).toAbsolutePath().normalize();
        if (!p.startsWith(dir.toAbsolutePath().normalize())) {
            return null;
        }
        // Symlink containment: a link inside the media dir may point OUT of
        // it (e.g. to C:\Windows\...). Re-resolve the real path and require
        // it still lives under the real root. (Lexical normalize() alone
        // does not follow symlinks.)
        try {
            Path realRoot = dir.toRealPath(new LinkOption[0]);
            Path realP = p.toRealPath(new LinkOption[0]);
            if (!realP.startsWith(realRoot)) {
                return null;
            }
        }
        catch (Exception e) {
            return null; // does not exist / unreadable -> not servable
        }
        if (!Files.isRegularFile(p, new LinkOption[0]) || !exts.contains(ApiController.extOf(p.toString()).toLowerCase(Locale.ROOT))) {
            return null;
        }
        return p;
    }

    /** Opens a YUCL URL in the system's default browser (JavaFX WebView can't decode h264 here). */
    @GetMapping(value={"/open-in-browser"})
    public Map<String, Object> openInBrowser(@RequestParam(name="url", required=false) String url, @RequestParam(name="job_id", required=false) String jobId, jakarta.servlet.http.HttpServletRequest req) {
        // 用请求自身的 host/port 构造放行前缀（SERVER_PORT 改端口后不再被写死的 8791 拒收）
        String selfBase = "http://" + req.getServerName() + ":" + req.getLocalPort();
        String target = "";
        if (url != null && !url.isEmpty()) {
            String u = url;
            if (u.startsWith(selfBase) || u.startsWith("http://127.0.0.1:8791") || u.startsWith("http://localhost:8791")) {
                target = u;
            }
        }
        if (target.isEmpty() && jobId != null && !jobId.isEmpty()) {
            String safe = jobId.replaceAll("[^A-Za-z0-9-]", "");
            if (!jobId.equals(safe)) {
                return Map.of("ok", false, "error", "bad job_id");
            }
            target = selfBase + "/api/plugins/ai-video/stream?job_id=" + safe;
        }
        if (target.isEmpty()) {
            return Map.of("ok", false, "error", "no url");
        }
        final String t = target;
        Thread th = new Thread(() -> com.aivideo.YuclApp.openBrowser(t), "yucl-open-browser");
        th.setDaemon(true);
        th.start();
        return Map.of("ok", true, "url", t);
    }

    private ResponseEntity<InputStreamResource> serveFile(Path path, String kind, String range) {
        BufferedInputStream in;
        String spec;
        int dash;
        long total;
        try {
            total = Files.size(path);
        }
        catch (Exception e) {
            return ResponseEntity.status((int)410).build();
        }
        String mime = "video".equals(kind) ? "video/mp4" : ("audio".equals(kind) ? "audio/mpeg" : "application/octet-stream");
        long start = 0L;
        long end = total - 1L;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=") && (dash = (spec = range.substring(6).split(",")[0].trim()).indexOf(45)) > 0) {
            try {
                String a = spec.substring(0, dash).trim();
                String b = spec.substring(dash + 1).trim();
                partial = true;
                start = a.isEmpty() ? 0L : Long.parseLong(a);
                end = b.isEmpty() ? total - 1L : Math.min(Long.parseLong(b), total - 1L);
            }
            catch (NumberFormatException nfe) {
                // Malformed Range header (attacker-controllable) -> 416, not 500.
                return ((ResponseEntity.BodyBuilder)ResponseEntity.status((int)416).header("Content-Range", new String[]{"bytes */" + total})).build();
            }
        }
        if (start < 0L) {
            start = 0L;
        }
        if (start > end || start >= total) {
            return ((ResponseEntity.BodyBuilder)ResponseEntity.status((int)416).header("Content-Range", new String[]{"bytes */" + total})).build();
        }
        long len = end - start + 1L;
        try {
            long s;
            in = new BufferedInputStream(Files.newInputStream(path, new OpenOption[0]));
            ((InputStream)in).mark(Integer.MAX_VALUE);
            for (long skip = start; skip > 0L && (s = ((InputStream)in).skip(Math.min(skip, Integer.MAX_VALUE))) > 0L; skip -= s) {
            }
        }
        catch (Exception e) {
            return ResponseEntity.status((int)410).build();
        }
        InputStreamResource res = new InputStreamResource((InputStream)new LimitedInputStream(in, len));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType((String)mime));
        h.set("Accept-Ranges", "bytes");
        h.set("Cache-Control", "no-cache");
        if (partial) {
            h.set("Content-Range", "bytes " + start + "-" + end + "/" + total);
            h.setContentLength(len);
            return ResponseEntity.<InputStreamResource>status(206).headers(h).body(res);
        }
        h.setContentLength(total);
        return ResponseEntity.<InputStreamResource>ok().headers(h).body(res);
    }

    public static class AgnesImageRequest {
        @JsonProperty(value="prompt")
        public String prompt = "";
        @JsonProperty(value="size")
        public String size = "1024x1024";
        @JsonProperty(value="model")
        public String model = "";
        @JsonProperty(value="provider_id")
        public String providerId = "";
    }

    public static class MakeCharBgRequest {
        @JsonProperty(value="provider_id")
        public String providerId = "";
        @JsonProperty(value="characters")
        public List<Entity> characters;
        @JsonProperty(value="backgrounds")
        public List<Entity> backgrounds;
        @JsonProperty(value="shots")
        public List<Shot> shots;
        @JsonProperty(value="orientation")
        public String orientation = "vertical";
        @JsonProperty(value="subtitles")
        public boolean subtitles = true;
        @JsonProperty(value="bgm")
        public String bgm = "";
        @JsonProperty(value="bgm_vol")
        public double bgmVol = 0.18;
        @JsonProperty(value="narration")
        public boolean narration = true;
        @JsonProperty(value="voices")
        public Map<String, String> voices;
        @JsonProperty(value="local_audio")
        public Map<String, String> localAudio;
        @JsonProperty(value="char_voices")
        public Map<String, String> charVoices;
        @JsonProperty(value="default_voice")
        public String defaultVoice = "";
        @JsonProperty(value="model")
        public String model = "";
        @JsonProperty(value="fps")
        public int fps = 30;
        @JsonProperty(value="char_scale")
        public double charScale = 0.68;
        @JsonProperty(value="char_pos")
        public String charPos = "bottom-left";
        @JsonProperty(value="style_hint")
        public String styleHint = "cinematic, natural motion, no on-screen text";
        @JsonProperty(value="out_dir")
        public String outDir = "";
        @JsonProperty(value="poll_deadline_s")
        public double pollDeadlineS = 900.0;
        @JsonProperty(value="queue_retries")
        public int queueRetries = 4;
        @JsonProperty(value="quality")
        public String quality = "1080p";
        @JsonProperty(value="sub_size")
        public String subSize = "medium";

        public Map<String, Object> toParams() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("characters", this.characters == null ? List.of() : this.characters.stream().map(Entity::toMap).toList());
            m.put("backgrounds", this.backgrounds == null ? List.of() : this.backgrounds.stream().map(Entity::toMap).toList());
            m.put("shots", this.shots == null ? List.of() : this.shots.stream().map(Shot::toMap).toList());
            m.put("orientation", this.orientation);
            m.put("subtitles", this.subtitles);
            m.put("bgm", this.bgm);
            m.put("bgm_vol", this.bgmVol);
            m.put("narration", this.narration);
            m.put("voices", this.voices);
            m.put("local_audio", this.localAudio);
            m.put("char_voices", this.charVoices);
            m.put("default_voice", this.defaultVoice);
            m.put("model", this.model);
            m.put("provider_id", this.providerId == null ? "" : this.providerId);
            m.put("fps", this.fps);
            m.put("char_scale", this.charScale);
            m.put("char_pos", this.charPos);
            m.put("style_hint", this.styleHint);
            m.put("out_dir", this.outDir);
            m.put("poll_deadline_s", this.pollDeadlineS);
            m.put("queue_retries", this.queueRetries);
            m.put("quality", this.quality);
            m.put("sub_size", this.subSize);
            return m;
        }
    }

    private static final class LimitedInputStream
    extends InputStream {
        private final InputStream in;
        private long left;

        LimitedInputStream(InputStream in, long limit) {
            this.in = in;
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            if (this.left <= 0L) {
                return -1;
            }
            int v = this.in.read();
            if (v >= 0) {
                --this.left;
            }
            return v;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (this.left <= 0L) {
                return -1;
            }
            int want = (int)Math.min((long)len, this.left);
            int got = this.in.read(buf, off, want);
            if (got > 0) {
                this.left -= (long)got;
            }
            return got;
        }
    }

    public static class Shot {
        @JsonProperty(value="character")
        public String character = "";
        @JsonProperty(value="character2")
        public String character2 = "";
        @JsonProperty(value="background")
        public String background = "";
        @JsonProperty(value="text")
        public String text = "";
        @JsonProperty(value="seconds")
        public int seconds = 5;
        @JsonProperty(value="engine")
        public String engine = "local";

        public Map<String, Object> toMap() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("character", this.character);
            m.put("character2", this.character2);
            m.put("background", this.background);
            m.put("text", this.text);
            m.put("seconds", this.seconds);
            m.put("engine", this.engine);
            return m;
        }
    }

    public static class Entity {
        @JsonProperty(value="name")
        public String name = "";
        @JsonProperty(value="image")
        public String image = "";
        @JsonProperty(value="url")
        public String url = "";
        @JsonProperty(value="source")
        public String source = "";

        public Map<String, Object> toMap() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", this.name);
            m.put("image", this.image);
            m.put("url", this.url);
            m.put("source", this.source);
            return m;
        }
    }
}
