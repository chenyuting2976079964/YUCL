/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.springframework.boot.SpringApplication
 *  org.springframework.boot.autoconfigure.SpringBootApplication
 *  org.springframework.context.ConfigurableApplicationContext
 */
package com.aivideo;

import com.aivideo.AppShell;
import com.aivideo.DataDirs;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class YuclApp {
    public static void main(String[] args) {
        Path logFile = DataDirs.dataDir().resolve("app.log");
        System.setProperty("LOG_FILE", logFile.toString());
        System.setProperty("LOG_FILE_PATTERN", logFile.toString().replace("app.log", "app.%d{yyyyMMdd}.%i.log"));
        String port = YuclApp.effectivePort();
        boolean noBrowser = "1".equals(System.getenv("AI_VIDEO_NO_BROWSER"));
        boolean gui = !noBrowser && !"0".equals(System.getenv("YUCL_GUI"));
        String url = "http://127.0.0.1:" + port + "/";
        if (!noBrowser && YuclApp.alreadyRunning(port)) {
            System.err.println("YUCL is already running on port " + port + " \u2014 opening a window (single-instance).");
            if (gui) {
                AppShell.launch(url, null);
                return;
            }
            YuclApp.openBrowser(url);
            System.exit(0);
        }
        if (YuclApp.portBusy(port)) {
            System.err.println("\u65e0\u6cd5\u542f\u52a8 YUCL\uff1a\u7aef\u53e3 " + port + " \u5df2\u7ecf\u88ab\u5176\u4ed6\u7a0b\u5e8f\u5360\u7528\uff08\u4e0d\u662f YUCL\uff09\u3002");
            System.err.println("\u8bf7\u5173\u95ed\u5360\u7528\u8be5\u7aef\u53e3\u7684\u7a0b\u5e8f\u540e\u91cd\u8bd5\uff1b\u6216\u8bbe\u7f6e\u73af\u5883\u53d8\u91cf SERVER_PORT=8792 \u7b49\u5176\u5b83\u7aef\u53e3\u518d\u542f\u52a8\u3002");
            System.exit(1);
        }
        // \u5feb\u901f\u542f\u52a8\uff1a\u5148\u5f39\u51fa\u5e94\u7528\u7a97\u53e3\uff08\u95ea\u5c4f\uff09\uff0c\u670d\u52a1\u5728\u540e\u53f0\u542f\u52a8\uff0chealthz \u5c31\u7eea\u540e\u7a97\u53e3\u81ea\u52a8\u5207\u5165\u754c\u9762
        AtomicReference<ConfigurableApplicationContext> ctxRef = new AtomicReference<>();
        Runnable shutdown = () -> {
            ConfigurableApplicationContext c = ctxRef.get();
            if (c != null) {
                try {
                    c.close();
                }
                catch (Exception e) {
                    System.err.println("shutdown hook failed: " + e);
                }
            }
        };
        if (gui) {
            AppShell.launch(url, shutdown);
        }
        try {
            ConfigurableApplicationContext ctx = SpringApplication.run(YuclApp.class, (String[])args);
            ctxRef.set(ctx);
            if (!gui && !noBrowser) {
                YuclApp.openBrowser(url);
            }
        }
        catch (Throwable t) {
            System.err.println("\u670d\u52a1\u542f\u52a8\u5931\u8d25\uff1a" + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static String effectivePort() {
        String p = System.getenv("SERVER_PORT");
        return p == null || p.isBlank() ? "8791" : p.trim();
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static boolean alreadyRunning(String port) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection)new URL("http://127.0.0.1:" + port + "/api/plugins/ai-video/healthz").openConnection();
            conn.setConnectTimeout(400);
            conn.setReadTimeout(600);
            boolean bl = conn.getResponseCode() == 200;
            return bl;
        }
        catch (Exception e) {
            boolean bl = false;
            return bl;
        }
        finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static boolean portBusy(String port) {
        boolean bl;
        int p;
        try {
            p = Integer.parseInt(port);
        }
        catch (Exception e) {
            return false;
        }
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", p), 300);
            bl = true;
        }
        catch (Throwable throwable) {
            try {
                try {
                    s.close();
                }
                catch (Throwable throwable2) {
                    throwable.addSuppressed(throwable2);
                }
                throw throwable;
            }
            catch (Exception e) {
                return false;
            }
        }
        try {
            s.close();
        }
        catch (Exception ignored) {
        }
        return bl;
    }

    public static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb = os.contains("win") ? new ProcessBuilder("cmd", "/c", "start", "", url) : (os.contains("mac") ? new ProcessBuilder("open", url) : new ProcessBuilder("xdg-open", url));
            pb.inheritIO().start();
        }
        catch (Exception e) {
            System.err.println("Could not open the browser (" + url + "): " + e.getMessage());
        }
    }
}
