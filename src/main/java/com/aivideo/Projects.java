/*
 * Project management (AE-style): <dataDir>/projects/<slug>/project.json + assets/.
 *
 * A project file is a self-contained JSON the UI form can save/load:
 *   { name, created, modified, state: { form: {characters, backgrounds, shots,
 *      orientation, providerId, videoModel, imgModel, charScale, charPos,
 *      subtitles, narration}, voice: {mode, voice, libName},
 *      bgm: {bgm, bgmVol, quality, subSize} } }
 *
 * Assets: image files (character/background art) are copied into the project's
 * assets/ dir on save so the project is portable; on open they are materialized
 * back into the global media library (imagesDir) so the generator resolves bare
 * names exactly as with freshly uploaded art.
 */
package com.aivideo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Projects {
  private Projects() {}

  private static final Set<String> IMG_EXTS =
      Set.of(".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif");
  private static final ObjectMapper M = new ObjectMapper();

  public static Path root() {
    Path p = DataDirs.dataDir().resolve("projects");
    try {
      Files.createDirectories(p);
    } catch (Exception ignored) {
    }
    return p;
  }

  /** Slug the project id/name so it is a safe directory name (CJK allowed but trimmed). */
  public static String slug(String name) {
    String s = name == null ? "" : name.trim();
    if (s.isEmpty()) return "project";
    s = s.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
    if (s.length() > 40) s = s.substring(0, 40);
    return s.isEmpty() ? "project" : s;
  }

  /** Existing project directory, or null if it does not exist yet. */
  public static Path dir(String id) {
    Path d = root().resolve(slug(id));
    return Files.isDirectory(d) ? d : null;
  }

  /** Raw (not-yet-existing) project dir — always non-null; create where needed. */
  private static Path rawDir(String id) {
    return root().resolve(slug(id));
  }

  private static Path jsonOf(String id) {
    return rawDir(id).resolve("project.json");
  }

  public static Path assetsDir(String id) {
    Path a = rawDir(id).resolve("assets");
    try {
      Files.createDirectories(a);
    } catch (Exception ignored) {
    }
    return a;
  }

  /** List projects (most recently modified first) with display metadata. */
  public static List<Map<String, Object>> list() {
    List<Map<String, Object>> out = new ArrayList<>();
    Path root = root();
    if (!Files.isDirectory(root)) return out;
    try (var s = Files.list(root)) {
      s.filter(Files::isDirectory).forEach(d -> {
        Path j = d.resolve("project.json");
        if (!Files.isRegularFile(j)) return;
        try {
          JsonNode n = M.readTree(Files.readAllBytes(j));
          Map<String, Object> e = new LinkedHashMap<>();
          e.put("id", d.getFileName().toString());
          e.put("name", n.path("name").asText(d.getFileName().toString()));
          JsonNode st = n.path("state");
          e.put("chars", st.path("form").path("characters").size());
          e.put("bgs", st.path("form").path("backgrounds").size());
          e.put("shots", st.path("form").path("shots").size());
          e.put("modified", n.path("modified").asText(""));
          e.put("created", n.path("created").asText(""));
          out.add(e);
        } catch (Exception ignored) {
        }
      });
    } catch (Exception ignored) {
    }
    out.sort(Comparator.comparing((Map<String, Object> m) ->
        String.valueOf(m.get("modified"))).reversed());
    return out;
  }

  /** Create an empty project; returns the new id. */
  public static String create(String name) throws IOException {
    String base = slug(name == null || name.trim().isEmpty() ? "未命名项目" : name);
    String id = base;
    int m = 2;
    while (Files.exists(rawDir(id))) {
      id = base + "-" + m;
      m++;
    }
    Files.createDirectories(assetsDir(id));
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("name", name.trim());
    doc.put("created", now());
    doc.put("modified", now());
    doc.put("state", emptyState());
    writeJson(id, doc);
    return id;
  }

  public static Map<String, Object> emptyState() {
    Map<String, Object> m = new LinkedHashMap<>();
    Map<String, Object> form = new LinkedHashMap<>();
    form.put("characters", new ArrayList<>());
    form.put("backgrounds", new ArrayList<>());
    form.put("shots", new ArrayList<>());
    m.put("form", form);
    m.put("voice", new LinkedHashMap<String, Object>() {{ put("mode", "tts"); put("voice", ""); put("libName", ""); }});
    m.put("bgm", new LinkedHashMap<String, Object>() {{ put("bgm", ""); put("bgmVol", 0.18); put("quality", "1080p"); put("subSize", "medium"); }});
    return m;
  }

  private static String now() {
    return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(java.time.ZoneId.of("Asia/Shanghai"))
        .format(java.time.Instant.now());
  }

  private static void writeJson(String id, Map<String, Object> doc) throws IOException {
    Path j = jsonOf(id);
    if (j == null) throw new IOException("project not found: " + id);
    Files.write(j, (M.writerWithDefaultPrettyPrinter()
        .writeValueAsString(doc)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * Save the UI's project state. Also copies any local image art referenced by the
   * form into assets/ so the project stays portable, and records "asset" names.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> save(String id, Map<String, Object> body) throws IOException {
    Path j = jsonOf(id);
    if (j == null) throw new IOException("project not found: " + id);
    JsonNode docN = Files.exists(j) ? M.readTree(Files.readAllBytes(j)) : null;
    Map<String, Object> doc = docN != null
        ? M.convertValue(docN, Map.class)
        : new LinkedHashMap<>();

    Map<String, Object> state = body.get("state") instanceof Map
        ? (Map<String, Object>) body.get("state")
        : emptyState();
    String name = body.get("name") instanceof String s && !s.isBlank()
        ? s.trim() : String.valueOf(doc.getOrDefault("name", id));

    // Copy referenced local image art into assets/
    int copied = 0;
    Path ad = assetsDir(id);
    Map<String, Object> form = (Map<String, Object>) state.getOrDefault("form", new LinkedHashMap<>());
    for (String key : List.of("characters", "backgrounds")) {
      List<Map<String, Object>> ents = (List<Map<String, Object>>) form.getOrDefault(key, new ArrayList<>());
      for (Map<String, Object> e : ents) {
        String p = String.valueOf(e.getOrDefault("path", ""));
        String bare = p == null ? "" : Paths.get(p).getFileName().toString();
        if (bare.isEmpty() || !IMG_EXTS.contains(
            "." + bare.substring(bare.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT))) {
          continue;
        }
        Path src = Paths.get(p);
        if (!Files.isRegularFile(src)) continue;
        Path dst = ad.resolve(bare);
        if (!Files.exists(dst)) {
          try {
            Files.copy(src, dst);
            copied++;
          } catch (Exception ignored) {
          }
        }
        e.put("asset", bare);
        e.remove("path");
      }
    }

    doc.put("name", name);
    doc.put("state", state);
    doc.put("modified", now());
    doc.putIfAbsent("created", now());
    writeJson(id, doc);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ok", true);
    out.put("id", id);
    out.put("modified", doc.get("modified"));
    out.put("assets_copied", copied);
    return out;
  }

  /**
   * Open a project: return the stored state with image entities normalized so the
   * generator can resolve them — assets are materialized into the global media
   * imagesDir (bare-name resolution) and the entity's "image" field set to that
   * bare name; the "asset" URL hint (for the UI preview) is included.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> open(String id) throws IOException {
    Path j = jsonOf(id);
    if (j == null) throw new IOException("project not found: " + id);
    Map<String, Object> doc = M.convertValue(M.readTree(Files.readAllBytes(j)), Map.class);
    Map<String, Object> state = doc.get("state") instanceof Map
        ? (Map<String, Object>) doc.get("state") : emptyState();
    Map<String, Object> form = (Map<String, Object>) state.getOrDefault("form", new LinkedHashMap<>());
    Path mediaImg = DataDirs.mediaDir("images");
    Path ad = assetsDir(id);
    for (String key : List.of("characters", "backgrounds")) {
      List<Map<String, Object>> ents = (List<Map<String, Object>>) form.getOrDefault(key, new ArrayList<>());
      for (Map<String, Object> e : ents) {
        String asset = String.valueOf(e.getOrDefault("asset", ""));
        String bare = "";
        if (!asset.isBlank() && ad != null && Files.isRegularFile(ad.resolve(asset))) {
          Path dst = mediaImg.resolve(asset);
          if (!Files.exists(dst)) {
            try {
              Files.copy(ad.resolve(asset), dst);
            } catch (Exception ignored) {
            }
          }
          bare = asset;
        } else if (e.get("image") != null) {
          bare = String.valueOf(e.get("image"));
        }
        e.put("image", bare);
        e.put("asset_url", bare.isBlank() ? "" : "/projects/" + id + "/asset?name=" + java.net.URLEncoder.encode(bare, java.nio.charset.StandardCharsets.UTF_8));
        e.remove("path");
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ok", true);
    out.put("id", id);
    out.put("name", doc.getOrDefault("name", id));
    out.put("state", state);
    out.put("modified", doc.get("modified"));
    return out;
  }

  public static boolean delete(String id) {
    Path d = dir(id);
    if (d == null) return false;
    try (var s = Files.walk(d)) {
      List<Path> all = new ArrayList<>();
      s.forEach(all::add);
      for (int i = all.size() - 1; i >= 0; i--) {
        Files.deleteIfExists(all.get(i));
      }
    } catch (Exception ignored) {
    }
    return !Files.exists(d);
  }

  /** Serve one asset file under a project's assets/ (preview). */
  public static Path asset(String id, String name) {
    Path ad = assetsDir(id);
    if (ad == null) return null;
    Path p = ad.resolve(name == null ? "" : name);
    if (!p.startsWith(ad) || !Files.isRegularFile(p)) return null;
    String ext = name == null || name.lastIndexOf('.') < 0 ? ""
        : name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
    if (!IMG_EXTS.contains(ext)) return null;
    return p;
  }
}
