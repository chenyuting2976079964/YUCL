/*
 * Decompiled with CFR 0.152.
 */
package com.aivideo;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;

public final class DataDirs {
    private DataDirs() {
    }

    public static Path mediaBase() {
        String env = System.getenv("YUCL_MEDIA_DIR");
        if (env != null && !env.isBlank()) {
            return Paths.get(env, new String[0]);
        }
        Path legacy = Paths.get("D:/YUCL/media", new String[0]);
        if (Files.isDirectory(legacy, new LinkOption[0])) {
            return legacy;
        }
        return Paths.get(System.getProperty("user.home"), "YUCL", "media");
    }

    public static Path mediaDir(String name) {
        Path p = DataDirs.mediaBase().resolve(name);
        try {
            Files.createDirectories(p, new FileAttribute[0]);
        }
        catch (Exception exception) {
            // empty catch block
        }
        return p;
    }

    public static Path dataDir() {
        String env = System.getenv("YUCL_DATA_DIR");
        if (env != null && !env.isBlank()) {
            return Paths.get(env, new String[0]);
        }
        Path legacy = Paths.get("D:/hermes/plugin-data/ai-video", new String[0]);
        if (Files.isDirectory(legacy, new LinkOption[0])) {
            return legacy;
        }
        Path def = Paths.get(System.getProperty("user.home"), "YUCL", "data");
        try {
            Files.createDirectories(def, new FileAttribute[0]);
        }
        catch (Exception exception) {
            // empty catch block
        }
        return def;
    }

    public static Path cacheRoot() {
        String env = System.getenv("YUCL_CACHE_DIR");
        if (env != null && !env.isBlank()) {
            return Paths.get(env, new String[0]);
        }
        Path legacy = Paths.get("D:/hermes/cache/ai-video", new String[0]);
        if (Files.isDirectory(legacy, new LinkOption[0])) {
            return legacy;
        }
        Path def = Paths.get(System.getProperty("user.home"), "YUCL", "cache");
        try {
            Files.createDirectories(def, new FileAttribute[0]);
        }
        catch (Exception exception) {
            // empty catch block
        }
        return def;
    }

    public static String usableDir(String configured, String mediaName) {
        if (System.getenv("YUCL_MEDIA_DIR") != null) {
            return DataDirs.mediaDir(mediaName).toString();
        }
        try {
            Path p = Paths.get(configured, new String[0]);
            if (Files.exists(p, new LinkOption[0])) {
                return configured;
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return DataDirs.mediaDir(mediaName).toString();
    }
}
