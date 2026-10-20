/*
 * Decompiled with CFR 0.152.
 */
package com.yuc.tools;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.stream.Stream;

public final class Uninstaller {
    private static PrintStream out;
    private static final String REG_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\YUCL";

    public static void main(String[] args) throws Exception {
        boolean clean;
        Uninstaller.run("cmd", "/c", "chcp", "65001");
        out = new PrintStream((OutputStream)new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        out.println("==============================================");
        out.println(" YUCL \u00b7 AI \u77ed\u89c6\u9891\u5de5\u4f5c\u5ba4 \u2014 \u5378\u8f7d\u7a0b\u5e8f  v1.0.0");
        out.println("==============================================");
        Path installDir = Uninstaller.registryInstallDir();
        if (installDir == null) {
            installDir = Paths.get(System.getenv("LOCALAPPDATA"), "YUCL");
        }
        out.println("\u5b89\u88c5\u76ee\u5f55: " + installDir);
        boolean autoYes = false;
        boolean delData = false;
        for (String a : args) {
            if ("--yes".equals(a)) {
                autoYes = true;
            }
            if (!"--del-data".equals(a)) continue;
            delData = true;
        }
        Uninstaller.run("taskkill", "/F", "/IM", "YUCL.exe");
        Uninstaller.run("taskkill", "/F", "/IM", "YUCL.exe");
        String delAll = autoYes ? "Y" : Uninstaller.ask("\u5220\u9664 YUCL \u7a0b\u5e8f\u6587\u4ef6\uff1f[Y/n]:", "Y");
        boolean removeData = delData;
        // Data lives wherever the install put it: env-configured dirs first
        // (YUCL_DATA_DIR / YUCL_MEDIA_DIR, set by the standard installer),
        // then the default user dir, then the legacy spot.
        ArrayList<Path> dataDirs = new ArrayList<Path>();
        String[] candEnv = {"YUCL_MEDIA_DIR", "YUCL_DATA_DIR", "YUCL_CACHE_DIR"};
        for (String ev : candEnv) {
            String v = System.getenv(ev);
            if (v != null && !v.isBlank() && !dataDirs.contains(Paths.get(v, new String[0]))) {
                dataDirs.add(Paths.get(v, new String[0]));
            }
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !dataDirs.contains(Paths.get(local, "YUCL"))) {
            dataDirs.add(Paths.get(local, "YUCL"));
        }
        dataDirs.add(Paths.get(System.getProperty("user.home"), "YUCL"));
        ArrayList<Path> dataDirsExisting = new ArrayList<Path>();
        for (Path dd : dataDirs) {
            if (Files.isDirectory(dd, new LinkOption[0])) {
                dataDirsExisting.add(dd);
            }
        }
        if (!autoYes && !dataDirsExisting.isEmpty()) {
            out.println("\u53d1\u73b0\u7528\u6237\u6570\u636e\u76ee\u5f55: " + dataDirsExisting + "\uff08\u56fe\u7247/\u8bed\u97f3/BGM \u5e93\u3001\u4efb\u52a1\u5386\u53f2\u3001\u6a21\u578b\u914d\u7f6e\uff09");
            String d = Uninstaller.ask("\u540c\u65f6\u5220\u9664\u7528\u6237\u6570\u636e\uff1f[y/N]:", "N");
            removeData = d.equalsIgnoreCase("Y");
        }
        boolean bl = clean = delAll.equalsIgnoreCase("Y") || delAll.isEmpty();
        if (clean) {
            String cmd = "timeout /t 3 /nobreak >nul & rmdir /s /q \"" + installDir + "\"";
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            try {
                pb.start();
            }
            catch (Exception exception) {
                // empty catch block
            }
            out.println("\u6b63\u5728\u5220\u9664\u7a0b\u5e8f\u6587\u4ef6\uff08\u7ea6 3 \u79d2\u540e\u5f00\u59cb\uff0c\u4e0d\u5f71\u54cd\u5378\u8f7d\u6d41\u7a0b\uff09\u2026");
        }
        if (removeData) {
            out.println("\u6b63\u5728\u5220\u9664\u7528\u6237\u6570\u636e\u2026");
            for (Path dd : dataDirsExisting) {
                Uninstaller.deleteTree(dd);
            }
        }
        Uninstaller.run("reg", "delete", REG_KEY, "/f");
        Path lnk = Paths.get(System.getProperty("user.home"), "Desktop", "YUCL.lnk");
        if (Files.exists(lnk, new LinkOption[0])) {
            try {
                Files.delete(lnk);
                out.println("\u5df2\u5220\u9664\u684c\u9762\u5feb\u6377\u65b9\u5f0f\u3002");
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        out.println();
        out.println(clean ? "\u2714 YUCL \u5df2\u5378\u8f7d\uff08\u7a0b\u5e8f\u6587\u4ef6\u5c06\u5728\u51e0\u79d2\u540e\u5f7b\u5e95\u79fb\u9664\uff09\u3002" : "\u2714 \u5df2\u79fb\u9664\u6ce8\u518c\u8868\u6761\u76ee\u4e0e\u684c\u9762\u5feb\u6377\u65b9\u5f0f\uff08\u7a0b\u5e8f\u6587\u4ef6\u4fdd\u7559\uff09\u3002");
        if (removeData) {
            out.println("\u2714 \u7528\u6237\u6570\u636e\u5df2\u5220\u9664\u3002");
        } else if (!dataDirsExisting.isEmpty()) {
            out.println("\u7528\u6237\u6570\u636e\u5df2\u4fdd\u7559\u5728 " + dataDirsExisting + "\uff08\u91cd\u65b0\u5b89\u88c5\u540e\u53ef\u7ee7\u7eed\u7528\u300c\u6062\u590d\u5907\u4efd\u300d\u627e\u56de\u5386\u53f2\uff09\u3002");
        }
        if (!autoYes) {
            out.println("\u6309\u56de\u8f66\u9000\u51fa\u2026");
            new Scanner(System.in).close();
        }
        System.exit(0);
    }

    static Path registryInstallDir() {
        try {
            Process p = new ProcessBuilder("reg", "query", REG_KEY, "/v", "InstallLocation").redirectErrorStream(true).start();
            byte[] raw = p.getInputStream().readAllBytes();
            p.waitFor();
            String text = new String(raw, StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (!t.toLowerCase().startsWith("installlocation") || !t.contains("REG_SZ")) continue;
                int i = t.lastIndexOf("REG_SZ");
                return Paths.get(t.substring(i + 6).trim(), new String[0]);
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return null;
    }

    static String ask(String prompt, String def) {
        String line;
        out.print(prompt + " ");
        try {
            line = new Scanner(System.in).nextLine().trim();
        }
        catch (Exception e) {
            line = "";
        }
        return line.isEmpty() ? def : line;
    }

    static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root, new FileVisitOption[0]);){
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                }
                catch (Exception exception) {
                    // empty catch block
                }
            });
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    static Process run(String ... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor();
            return p;
        }
        catch (Exception e) {
            return null;
        }
    }
}
