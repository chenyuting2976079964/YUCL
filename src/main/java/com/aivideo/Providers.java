/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  com.fasterxml.jackson.databind.ObjectMapper
 */
package com.aivideo;

import com.aivideo.ModelGateway;
import com.aivideo.DataDirs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Providers {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object LOCK = new Object();

    private Providers() {
    }

    public static Path file() {
        return DataDirs.dataDir().resolve("provider-config.json");
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static Map<String, Object> state() {
        Object object = LOCK;
        synchronized (object) {
            ArrayList<Map<String, Object>> providers = new ArrayList<Map<String, Object>>();
            String active = "";
            Path p = Providers.file();
            if (Files.exists(p, new LinkOption[0])) {
                try {
                    JsonNode root = JSON.readTree(Files.readAllBytes(p));
                    active = root.path("active").asText("");
                    for (JsonNode jsonNode : root.path("providers")) {
                        providers.add(Providers.asMap(jsonNode));
                    }
                }
                catch (Exception root) {
                    // empty catch block
                }
            }
            if (providers.stream().noneMatch(x -> Boolean.TRUE.equals(x.get("builtin")))) {
                providers.add(0, Providers.builtin());
            }
            if (providers.isEmpty()) {
                providers.add(Providers.builtin());
            }
            Map<String, Object> seed = Providers.builtin();
            for (Map map : providers) {
                if (!Boolean.TRUE.equals(map.get("builtin"))) continue;
                if (!(map.get("text_models") instanceof List) || ((List)map.get("text_models")).isEmpty()) {
                    map.put("text_models", seed.get("text_models"));
                }
                if (!Providers.str(map, "default_text_model").isEmpty()) continue;
                map.put("default_text_model", seed.get("default_text_model"));
            }
            String active0 = active;
            if (active0.isEmpty() || providers.stream().noneMatch(x -> active0.equals(x.get("id")))) {
                active = String.valueOf(((Map)providers.get(0)).get("id"));
            }
            LinkedHashMap<String, Object> linkedHashMap = new LinkedHashMap<String, Object>();
            linkedHashMap.put("active", active);
            linkedHashMap.put("providers", providers);
            return linkedHashMap;
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void save(Map<String, Object> newState) {
        Object object = LOCK;
        synchronized (object) {
            try {
                Path target = Providers.file();
                Files.createDirectories(target.getParent(), new FileAttribute[0]);
                // 原子替换：写一半崩溃时旧配置完好无损
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.writeString(tmp, (CharSequence)JSON.writerWithDefaultPrettyPrinter().writeValueAsString(newState), StandardCharsets.UTF_8, new OpenOption[0]);
                try {
                    Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            catch (Exception e) {
                System.err.println("[ai-video] provider config save failed: " + e);
            }
        }
    }

    public static Map<String, Object> byid(String id) {
        Map<String, Object> st = Providers.state();
        String want = id == null ? "" : id.trim();
        if (want.isEmpty()) {
            return Providers.active();
        }
        for (Map<String, Object> p : (List<Map<String, Object>>)st.get("providers")) {
            if (want.equals(String.valueOf(p.get("id")))) {
                return p;
            }
        }
        return Providers.active();
    }
    public static Map<String, Object> active() {
        Map<String, Object> st = Providers.state();
        String want = String.valueOf(st.get("active"));
        for (Map p : (List<Map>)st.get("providers")) {
            if (!want.equals(String.valueOf(p.get("id")))) continue;
            return p;
        }
        return (Map)((List)st.get("providers")).get(0);
    }

    /** 是否"用户已配置过模型供应商"——决定 UI 是否显示具体模型信息：
     *  全新安装（配置文件不存在或只有无 key 的内置条目）= 未配置，UI 全程通用话术、
     *  不出现任何厂商标识与模型名；一旦填过 key 或加过自定义供应商 = 已配置，
     *  表单里正常显示模型下拉。 */
    public static boolean isUserConfigured() {
        Path p = Providers.file();
        if (!Files.exists(p, new LinkOption[0])) {
            return false;
        }
        try {
            JsonNode root = JSON.readTree(Files.readAllBytes(p));
            for (JsonNode jsonNode : root.path("providers")) {
                String id = jsonNode.path("id").asText("");
                String key = jsonNode.path("api_key").asText("");
                if (!key.isBlank() || !"agnes".equals(id)) {
                    return true;
                }
            }
            return false;
        }
        catch (Exception e) {
            return false;
        }
    }

    /** UI 显示名：内置供应商永远显示通用名（不落厂商字样），自定义供应商显示其自命名。 */
    public static String displayName(Map<String, Object> p) {
        if (p != null && Boolean.TRUE.equals(p.get("builtin"))) {
            return "系统内置服务";
        }
        String n = p == null ? "" : String.valueOf(p.get("name"));
        return n == null || n.isBlank() ? "自定义服务" : n;
    }

    public static Map<String, Object> builtin() {
        LinkedHashMap<String, Object> b = new LinkedHashMap<String, Object>();
        b.put("id", "agnes");
        b.put("name", "系统内置服务");
        b.put("builtin", true);
        String base = "https://apihub.agnes-ai.com/v1";
        String key = "";
        List<String[]> eps = ModelGateway.endpoints();
        if (!eps.isEmpty()) {
            base = eps.get(0)[0];
            key = eps.get(0)[1];
        }
        b.put("base_url", base);
        b.put("api_key", key);
        b.put("image_models", ModelGateway.IMAGE_MODELS);
        b.put("default_image_model", "agnes-image-2.5-flash");
        b.put("video_models", ModelGateway.VIDEO_MODELS);
        b.put("default_video_model", "agnes-video-2.5-flash");
        b.put("text_models", ModelGateway.TEXT_MODELS);
        b.put("default_text_model", "agnes-3.0-flash");
        return b;
    }

    public static String str(Map<String, Object> p, String k) {
        Object v = p == null ? null : p.get(k);
        return v == null ? "" : String.valueOf(v).trim();
    }

    public static List<String> modelList(Map<String, Object> p, String k) {
        String s;
        if (p == null) {
            return List.of();
        }
        Object v = p.get(k);
        if (v instanceof List) {
            List l = (List)v;
            ArrayList<String> out = new ArrayList<String>();
            for (Object o : l) {
                out.add(String.valueOf(o));
            }
            return out;
        }
        if (v instanceof String && !(s = (String)v).isBlank()) {
            ArrayList<String> out = new ArrayList<String>();
            for (String part : s.split("[,\uff0c]")) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                out.add(t);
            }
            return out;
        }
        return List.of();
    }

    public static List<String> imageModels(Map<String, Object> p) {
        List<String> v = Providers.modelList(p, "image_models");
        return v.isEmpty() ? ModelGateway.IMAGE_MODELS : v;
    }

    public static List<String> videoModels(Map<String, Object> p) {
        List<String> v = Providers.modelList(p, "video_models");
        return v.isEmpty() ? ModelGateway.VIDEO_MODELS : v;
    }

    public static List<String> textModels(Map<String, Object> p) {
        List<String> v = Providers.modelList(p, "text_models");
        return v.isEmpty() ? ModelGateway.TEXT_MODELS : v;
    }

    public static String defaultImageModel(Map<String, Object> p) {
        String v = Providers.str(p, "default_image_model");
        return v.isEmpty() ? "agnes-image-2.5-flash" : v;
    }

    public static String defaultVideoModel(Map<String, Object> p) {
        String v = Providers.str(p, "default_video_model");
        return v.isEmpty() ? "agnes-video-2.5-flash" : v;
    }

    public static String defaultTextModel(Map<String, Object> p) {
        String v = Providers.str(p, "default_text_model");
        if (!v.isEmpty()) {
            return v;
        }
        List<String> list = Providers.modelList(p, "text_models");
        return list.isEmpty() ? "" : list.get(0);
    }

    private static Map<String, Object> asMap(JsonNode j) {
        return (Map)JSON.convertValue((Object)j, Map.class);
    }
}
