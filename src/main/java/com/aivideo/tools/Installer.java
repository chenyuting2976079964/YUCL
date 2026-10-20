/*
 * Decompiled with CFR 0.152.
 */
package com.aivideo.tools;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public final class Installer {
    private static PrintStream out;
    private static final AtomicLong copiedBytes;
    private static Path appDir;

    public static void main(String[] args) {
        try {
            Installer.run("cmd", "/c", "chcp", "65001");
            out = new PrintStream((OutputStream)new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
            Installer.runInstall(args);
        }
        catch (Exception e) {
            Installer.fail("\u5b89\u88c5\u5931\u8d25: " + e.getMessage(), e);
        }
    }

    private static void runInstall(String[] args) throws Exception {
        String ans;
        boolean existingInstall;
        Object installDir;
        appDir = Installer.appDir();
        String payload = appDir.resolve("yuc-app").toString();
        String uninstaller = appDir.resolve("yuc-uninstall").toString();
        if (!Files.isDirectory(Paths.get(payload, new String[0]), new LinkOption[0])) {
            out.println("YUCL-Setup: payload folder missing (app\\yuc-app) \u2014 incomplete installer build.");
            System.exit(1);
        }
        out.println("==============================================");
        out.println(" YUCL \u00b7 AI \u77ed\u89c6\u9891\u5de5\u4f5c\u5ba4 \u2014 \u5b89\u88c5\u7a0b\u5e8f  v1.0.0");
        out.println(" \u514d\u5b89\u88c5\u4f9d\u8d56\uff1a\u81ea\u5e26 Java \u8fd0\u884c\u65f6 + ffmpeg");
        out.println(" \u6570\u636e\u5c06\u4fdd\u5b58\u5728 C:\\Users\\" + System.getProperty("user.name") + "\\YUCL\uff08\u7528\u6237\u76ee\u5f55\uff09");
        out.println("==============================================");
        String def = Paths.get(System.getenv("LOCALAPPDATA"), "YUCL").toString();
        boolean autoYes = false;
        ArrayList<String> rest = new ArrayList<String>();
        for (String string : args) {
            if ("--yes".equals(string)) {
                autoYes = true;
                continue;
            }
            rest.add(string);
        }
        Object object = installDir = !rest.isEmpty() ? ((String)rest.get(0)).trim().replace("\"", "") : Installer.ask("\u8bf7\u9009\u62e9\u5b89\u88c5\u76ee\u5f55 [\u9ed8\u8ba4: " + def + "]:", def);
        if (((String)installDir).isEmpty()) {
            installDir = def;
        }
        if (((String)installDir).contains("~")) {
            installDir = System.getProperty("user.home") + ((String)installDir).substring(((String)installDir).indexOf(126));
        }
        installDir = ((String)installDir).replace('/', '\\');
        Path target = Paths.get((String)installDir, new String[0]);
        out.println("\u5b89\u88c5\u5230: " + target.toAbsolutePath().normalize());
        boolean bl = existingInstall = Files.isRegularFile(target.resolve("YUCL.exe"), new LinkOption[0]) || Files.isRegularFile(target.resolve("app").resolve("ai-video-java.jar"), new LinkOption[0]);
        if (existingInstall) {
            out.println("\u68c0\u6d4b\u5230\u5df2\u6709\u5b89\u88c5 \u2192 \u5347\u7ea7\u5b89\u88c5\u3002");
            out.println("\u6b63\u5728\u505c\u6b62\u8fd0\u884c\u4e2d\u7684 YUCL\u2026");
            Installer.run("taskkill", "/F", "/IM", "YUCL.exe");
            Installer.run("taskkill", "/F", "/IM", "YUCL.exe");
            Thread.sleep(2000L);
            for (String d : new String[]{"app", "runtime", "ffmpeg", "YUCL-Uninstall"}) {
                Installer.deleteTree(target.resolve(d));
            }
            Files.deleteIfExists(target.resolve("YUCL.exe"));
            Files.deleteIfExists(target.resolve("YUCL.ico"));
        } else if (target.toFile().exists() && Files.list(target).count() > 0L) {
            out.println("\u8be5\u76ee\u5f55\u5df2\u6709\u5176\u4ed6\u6587\u4ef6\uff0c\u540c\u540d\u5185\u5bb9\u5c06\u88ab\u8986\u76d6\u3002");
        }
        Files.createDirectories(target, new FileAttribute[0]);
        if (!Files.isWritable(target)) {
            out.println("\u9519\u8bef\uff1a\u76ee\u5f55\u4e0d\u53ef\u5199\uff08\u53ef\u80fd\u9700\u8981\u7ba1\u7406\u5458\u6743\u9650\uff09\u3002\u8bf7\u6362\u4e00\u4e2a\u666e\u901a\u7528\u6237\u76ee\u5f55\uff08\u5982 " + def + "\uff09\u3002");
            System.exit(2);
        }
        out.println("\u6b63\u5728\u590d\u5236 YUCL \u7a0b\u5e8f\u6587\u4ef6\uff08\u7ea6 700MB\uff0c\u8bf7\u7a0d\u5019\uff09\u2026");
        Installer.copyTree(Paths.get(payload, new String[0]), target);
        out.println(String.format("\u590d\u5236\u5b8c\u6210\uff08%.1f MB\uff09\u3002", (double)copiedBytes.get() / 1048576.0));
        Path path = target.resolve("YUCL-Uninstall");
        Installer.copyTree(Paths.get(uninstaller, new String[0]), path);
        String desktopDir = Paths.get(System.getProperty("user.home"), "Desktop").toString();
        Installer.createShortcut(desktopDir + "\\YUCL.lnk", target.resolve("YUCL.exe").toString(), target.toString(), "YUCL \u00b7 AI \u77ed\u89c6\u9891\u5de5\u4f5c\u5ba4");
        Installer.regAddUninstall(target, path);
        out.println();
        out.println("\u2714 \u5b89\u88c5\u5b8c\u6210");
        out.println("  \u00b7 \u684c\u9762\u5feb\u6377\u65b9\u5f0f: YUCL");
        out.println("  \u00b7 \u5378\u8f7d: " + path.resolve("YUCL-Uninstall.exe"));
        out.println("    \uff08\u6216 \u8bbe\u7f6e \u2192 \u5e94\u7528 \u2192 YUCL \u2192 \u5378\u8f7d\uff09");
        out.println();
        String string = ans = autoYes ? "Y" : Installer.ask("\u73b0\u5728\u542f\u52a8 YUCL\uff1f[Y/n]:", "Y");
        if (ans.equalsIgnoreCase("Y") || ans.isEmpty()) {
            ProcessBuilder pb = new ProcessBuilder(target.resolve("YUCL.exe").toString());
            pb.environment().put("AI_VIDEO_NO_BROWSER", "0");
            try {
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            }
            catch (Exception exception) {
                // empty catch block
            }
            pb.start();
            out.println("YUCL \u5df2\u542f\u52a8\uff08\u6d4f\u89c8\u5668\u5c06\u81ea\u52a8\u6253\u5f00\uff09\u3002");
        } else {
            out.println("\u53ef\u4ee5\u7a0d\u540e\u53cc\u51fb\u684c\u9762\u300cYUCL\u300d\u542f\u52a8\u3002");
        }
        if (!autoYes) {
            out.println("\u6309\u56de\u8f66\u9000\u51fa\u2026");
            new Scanner(System.in).close();
        }
        System.exit(0);
    }

    private static void fail(String msg, Exception e) {
        if (out == null) {
            System.err.println(msg);
            e.printStackTrace();
            System.exit(1);
        }
        out.println();
        out.println("\u2718 " + msg);
        out.println("\u8be6\u7ec6\u9519\u8bef: " + e);
        out.println("\u5b89\u88c5\u4e2d\u65ad\u3002\u8bf7\u5173\u95ed\u8fd0\u884c\u4e2d\u7684 YUCL \u540e\u91cd\u8bd5\uff1b\u6216\u6362\u4e00\u4e2a\u5b89\u88c5\u76ee\u5f55\u3002");
        try {
            out.println("\u6309\u56de\u8f66\u9000\u51fa\u2026");
            new Scanner(System.in).close();
        }
        catch (Exception exception) {
            // empty catch block
        }
        System.exit(1);
    }

    static Path appDir() throws Exception {
        int bang;
        String s;
        try {
            URL loc = Installer.class.getProtectionDomain().getCodeSource().getLocation();
            s = loc.toString();
        }
        catch (Exception e) {
            return Paths.get("app", new String[0]);
        }
        String pathPart = s;
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
        Path jar = Paths.get(pathPart, new String[0]);
        return jar.toAbsolutePath().getParent();
    }

    static void copyTree(Path src, Path dst) throws IOException {
        copiedBytes.set(0L);
        try (Stream<Path> walk = Files.walk(src, new FileVisitOption[0]);){
            for (Path p : walk.filter(x -> Files.isRegularFile(x, new LinkOption[0])).toList()) {
                Path rel = src.relativize(p);
                Path d = dst.resolve(rel.toString().replace('/', File.separatorChar));
                Files.createDirectories(d.getParent(), new FileAttribute[0]);
                Files.copy(p, d, StandardCopyOption.REPLACE_EXISTING);
                copiedBytes.addAndGet(Files.size(p));
                if (Files.size(p) <= 0L || copiedBytes.get() % 0xA00000L >= Files.size(p)) continue;
                out.println("  \u2026 " + copiedBytes.get() / 0x100000L + " MB");
            }
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, new LinkOption[0])) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root, new FileVisitOption[0]);){
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (!Files.isRegularFile(p, new LinkOption[0])) continue;
                Files.deleteIfExists(p);
            }
        }
        Files.deleteIfExists(root);
    }

    static void createShortcut(String lnkPath, String targetExe, String workDir, String desc) {
        String ps = "$ws = New-Object -ComObject WScript.Shell; $l = $ws.CreateShortcut('" + lnkPath.replace("\\", "\\\\") + "'); $l.TargetPath = '" + targetExe.replace("\\", "\\\\") + "'; $l.WorkingDirectory = '" + workDir.replace("\\", "\\\\") + "'; $l.IconLocation = '" + targetExe.replace("\\", "\\\\") + "',0; $l.Description = '" + desc + "'; $l.Save()";
        Installer.run("powershell", "-NoProfile", "-Command", ps);
    }

    static void regAddUninstall(Path target, Path uninstallDir) {
        long kb = 0L;
        try (Stream<Path> w2 = Files.walk(target, new FileVisitOption[0]);){
            for (Path p : w2.filter(x$0 -> Files.isRegularFile(x$0, new LinkOption[0])).toList()) {
                kb += Files.size(p);
            }
        }
        catch (Exception w2) {
            // empty catch block
        }
        String key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\YUCL";
        Installer.run("reg", "add", key, "/v", "DisplayName", "/d", "YUCL - AI \u77ed\u89c6\u9891\u5de5\u4f5c\u5ba4", "/f");
        Installer.run("reg", "add", key, "/v", "DisplayVersion", "/d", "1.0.0", "/f");
        Installer.run("reg", "add", key, "/v", "Publisher", "/d", "YUCL", "/f");
        Installer.run("reg", "add", key, "/v", "InstallLocation", "/d", target.toString(), "/f");
        Installer.run("reg", "add", key, "/v", "UninstallString", "/d", uninstallDir.resolve("YUCL-Uninstall.exe").toString(), "/f");
        Installer.run("reg", "add", key, "/v", "DisplayIcon", "/d", target.resolve("YUCL.exe").toString(), "/f");
        Installer.run("reg", "add", key, "/v", "NoModify", "/d", "1", "/f");
        Installer.run("reg", "add", key, "/v", "NoRepair", "/d", "1", "/f");
        Installer.run("reg", "add", key, "/v", "EstimatedSize", "/d", String.valueOf(kb / 1024L), "/f");
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
        if (line.isEmpty()) {
            line = def;
        }
        return line;
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

    static {
        copiedBytes = new AtomicLong();
    }
}
