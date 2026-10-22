/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  com.fasterxml.jackson.databind.ObjectMapper
 *  com.fasterxml.jackson.databind.node.ObjectNode
 */
package com.aivideo;

import com.aivideo.DataDirs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class Jobs {
    public static final int MAX_JOBS = 20;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RAND = new SecureRandom();
    private static final Object LOCK = new Object();

    private Jobs() {
    }

    public static Path file() {
        Path p = DataDirs.dataDir().resolve("jobs.json");
        try {
            Files.createDirectories(p.getParent(), new FileAttribute[0]);
        }
        catch (Exception exception) {
            // empty catch block
        }
        return p;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static List<Map<String, Object>> load() {
        Object object = LOCK;
        synchronized (object) {
            Path p = Jobs.file();
            if (!Files.exists(p, new LinkOption[0])) {
                return new ArrayList<Map<String, Object>>();
            }
            try {
                String text;
                byte[] raw = Files.readAllBytes(p);
                if (raw.length >= 3 && (raw[0] & 0xFF) == 239 && (raw[1] & 0xFF) == 187 && (raw[2] & 0xFF) == 191) {
                    raw = Arrays.copyOfRange(raw, 3, raw.length);
                }
                if ((text = new String(raw, StandardCharsets.UTF_8).trim()).isEmpty()) {
                    return new ArrayList<Map<String, Object>>();
                }
                JsonNode node = JSON.readTree(text);
                ArrayList<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
                if (node.isArray()) {
                    for (JsonNode j : node) {
                        out.add(Jobs.toMap(j));
                    }
                }
                return out;
            }
            catch (Exception e) {
                return new ArrayList<Map<String, Object>>();
            }
        }
    }

    private static Map<String, Object> toMap(JsonNode j) {
        if (j.isObject()) {
            return (Map)JSON.convertValue((Object)j, Map.class);
        }
        if (j.isTextual()) {
            return Map.of("s", j.asText());
        }
        if (j.isNumber()) {
            return Map.of("n", j.doubleValue());
        }
        if (j.isBoolean()) {
            return Map.of("b", j.booleanValue());
        }
        return Map.of("v", j.toString());
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void save(List<Map<String, Object>> jobs) {
        Object object = LOCK;
        synchronized (object) {
            try {
                Path target = Jobs.file();
                Files.createDirectories(target.getParent(), new FileAttribute[0]);
                // 原子替换：进程在写一半时崩溃不会留下半截 JSON（旧文件原样保留）
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.writeString(tmp, (CharSequence)JSON.writerWithDefaultPrettyPrinter().writeValueAsString(jobs), StandardCharsets.UTF_8, new OpenOption[0]);
                try {
                    Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            catch (Exception e) {
                System.err.println("[ai-video] jobs save failed: " + e);
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static String register(Map<String, Object> meta) {
        Object object = LOCK;
        synchronized (object) {
            byte[] b = new byte[2];
            RAND.nextBytes(b);
            String id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + String.format("%02x%02x", b[0], b[1]);
            ObjectNode record = JSON.createObjectNode();
            record.put("id", id);
            record.put("created_at", System.currentTimeMillis() / 1000L);
            meta.forEach((k, v) -> {
                if (v != null) {
                    record.set(k, JSON.valueToTree(v));
                }
            });
            List<Map<String, Object>> jobs = Jobs.load();
            jobs.add(0, (Map)JSON.convertValue((Object)record, Map.class));
            Jobs.save(jobs.subList(0, Math.min(20, jobs.size())));
            return id;
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static boolean update(String jobId, Map<String, Object> fields) {
        Object object = LOCK;
        synchronized (object) {
            List<Map<String, Object>> jobs = Jobs.load();
            Map<String, Object> target = null;
            for (Map<String, Object> map : jobs) {
                if (!jobId.equals(map.get("id"))) continue;
                target = map;
            }
            if (target == null) {
                return false;
            }
            for (Map.Entry entry : fields.entrySet()) {
                if (entry.getValue() == null) {
                    target.remove(entry.getKey());
                    continue;
                }
                target.put((String)entry.getKey(), entry.getValue());
            }
            Jobs.save(jobs);
            return true;
        }
    }

    public static Map<String, Object> find(String jobId) {
        for (Map<String, Object> j : Jobs.load()) {
            if (!jobId.equals(j.get("id"))) continue;
            return j;
        }
        return null;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static boolean remove(String jobId, boolean deleteFiles) {
        Object object = LOCK;
        synchronized (object) {
            Path runDir;
            Object v;
            List<Map<String, Object>> jobs = Jobs.load();
            Map<String, Object> target = null;
            ArrayList<Map<String, Object>> kept = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> j : jobs) {
                if (jobId.equals(j.get("id"))) {
                    target = j;
                    continue;
                }
                kept.add(j);
            }
            if (target == null) {
                return false;
            }
            if (deleteFiles && (v = target.get("video")) != null && (runDir = Paths.get(String.valueOf(v), new String[0]).getParent()) != null && Files.isDirectory(runDir, new LinkOption[0])) {
                try {
                    Path cacheRoot = DataDirs.cacheRoot().toRealPath(new LinkOption[0]);
                    if (runDir.toRealPath(new LinkOption[0]).startsWith(cacheRoot)) {
                        Jobs.deleteTree(runDir);
                    }
                }
                catch (Exception e) {
                    System.err.println("[ai-video] delete files failed: " + e);
                }
            }
            Jobs.save(kept);
            return true;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root, new FileVisitOption[0]);){
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                }
                catch (IOException iOException) {
                    // empty catch block
                }
            });
        }
    }
}
