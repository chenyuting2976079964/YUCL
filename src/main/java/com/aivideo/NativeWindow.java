package com.aivideo;

import com.sun.jna.CallbackReference;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.W32APIOptions;

/**
 * Windows 原生框架级缩放/移动 —— JavaFX UNDECORATED 窗口的 WndProc 子类化（JNA 标准接口版）。
 *
 * <p>架构与成熟方案（fxstage / Microsoft "Custom Window Frame Using DWM"）一致：
 * <ul>
 *   <li>{@code WS_THICKFRAME} 保证存在 —— 框架级 resize 循环的前提；</li>
 *   <li>{@code WM_NCCALCSIZE}（wParam=TRUE）→ 客户端区四周内缩 {@code border} 像素，
 *       形成原生 NCA 边框环（Windows 11 圆角/阴影/DWM 边框都建立在此），返回 WVR_VALIDRECTS(1024)；</li>
 *   <li>{@code WM_NCHITTEST} → 先转发给原始（Glass）wndproc；结果为 HTCLIENT 时做自定义：
 *       边环 8 区（HTTOP/HTLEFT/HTRIGHT/HTBOTTOM + 四角）、标题栏带（HTCAPTION=移动，
 *       右侧按钮区让位给 HTCLIENT 保证按钮可点）；</li>
 *   <li>其余全部消息（含 WM_SIZE/WM_DESTROY/WM_GETMINMAXINFO）转发给原始 wndproc，
 *       Glass 引擎的窗口-场景同步不丢失；WM_DESTROY 时恢复原始 proc。</li>
 * </ul>
 *
 * <p>缩放/移动由 Windows 框架的 modal 循环驱动（与 IntelliJ/Electron 一致），
 * 全程跟手、无鼠标 grab 丢失、支持超长拖拽。失败时静默降级为纯 JavaFX 行为。
 */
public final class NativeWindow implements AutoCloseable {

    /** jna-platform 标准 User32 + 个别未封装方法 */
    interface User32Ex extends User32 {
        User32Ex INSTANCE = Native.load("user32", User32Ex.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean IsZoomed(WinDef.HWND hWnd);

        /** 64 位参数版（GCLP_HBRBACKGROUND 的画刷句柄是 pointer-sized） */
        WinDef.LRESULT SetClassLongPtr(WinDef.HWND hWnd, int nIndex, long dwNewLong);
    }

    /** Gdi32：创建窗口类背景画刷（句柄返回原始 long，避免 jna WinDef.HANDLE 差异） */
    interface Gdi32Ex extends com.sun.jna.Library {
        Gdi32Ex INSTANCE = Native.load("gdi32", Gdi32Ex.class, W32APIOptions.DEFAULT_OPTIONS);

        long CreateSolidBrush(int color);
    }

    /** GCLP_HBRBACKGROUND：替换窗口类的背景画刷 */
    private static final int GCLP_HBRBACKGROUND = -104;
    /** 0x00BBGGRR：#0a0b0f → R=0x0a G=0x0b B=0x0f */
    private static final int BRUSH_DARK = 0x000F0B0A;

    /**
     * DWM 边框色（dwmapi.h）：Win10 2004+/Win11 下，DWM 在窗口四周画 1px 边框，
     * 窗口失焦（inactive）时边框默认取浅色主题的白色 → 这就是"选中其他窗口时
     * YUCL 四周出现白框"的根因（聚焦时边框为 active 色/不可见，所以切回来就消失）。
     */
    interface DwmApi extends com.sun.jna.Library {
        DwmApi INSTANCE = Native.load("dwmapi", DwmApi.class);

        /** HRESULT DwmSetWindowAttribute(HWND, DWORD, const void*, DWORD) */
        int DwmSetWindowAttribute(WinDef.HWND hwnd, int dwAttribute, Pointer pvAttribute, int cbAttribute);
    }
    private static final int DWMWA_BORDER_COLOR = 33;
    private static final int DWMWA_CAPTION_COLOR = 34;

    private static final int GWL_WNDPROC = -4;
    private static final int GWL_STYLE = -16;
    private static final int WS_THICKFRAME = 0x00040000;
    private static final int WM_DESTROY = 0x0002;
    private static final int WM_NCCALCSIZE = 0x0083;
    private static final int WM_NCHITTEST = 0x0084;

    private static final int HTCLIENT = 1;
    private static final int HTCAPTION = 2;
    private static final int HTLEFT = 10;
    private static final int HTRIGHT = 11;
    private static final int HTTOP = 12;
    private static final int HTTOPLEFT = 13;
    private static final int HTTOPRIGHT = 14;
    private static final int HTBOTTOM = 15;
    private static final int HTBOTTOMLEFT = 16;
    private static final int HTBOTTOMRIGHT = 17;

    /** 子类化回调（必须强引用：JNA callback map 为弱引用） */
    private static final class WndProc implements WinUser.WindowProc {
        final BaseTSD.LONG_PTR oldProc;
        final Pointer oldProcPtr;
        final WinDef.HWND hwnd;
        final int border;         // 边环命中区厚度（物理像素）
        final int titleBarH;      // 顶部标题栏带高度（物理像素）
        final int rightButtonsW;  // 标题栏右侧按钮区宽度（物理像素，让位 HTCLIENT）
        final java.util.concurrent.atomic.AtomicBoolean glassHtLogged = new java.util.concurrent.atomic.AtomicBoolean(false);

        WndProc(WinDef.HWND hwnd, BaseTSD.LONG_PTR oldProc, int border, int titleBarH, int rightButtonsW) {
            this.hwnd = hwnd;
            this.oldProc = oldProc;
            this.oldProcPtr = new Pointer(oldProc.longValue());
            this.border = border;
            this.titleBarH = titleBarH;
            this.rightButtonsW = rightButtonsW;
        }

        @Override
        public WinDef.LRESULT callback(WinDef.HWND h, int msg, WinDef.WPARAM wp, WinDef.LPARAM lp) {
            User32Ex u32 = User32Ex.INSTANCE;
            switch (msg) {
                case WM_DESTROY:
                    // 恢复原始 wndproc
                    u32.SetWindowLongPtr(h, GWL_WNDPROC, oldProcPtr);
                    return new WinDef.LRESULT(0);

                case WM_NCCALCSIZE:
                    // client = 整个窗口（无 NCA 边框环）→ 四周不会出现空白边条；
                    // 缩放仍由 NCHITTEST 的 HT* 边区 + WS_THICKFRAME 驱动框架 modal 循环。
                    return new WinDef.LRESULT(0);

                case WM_NCHITTEST: {
                    WinDef.LRESULT r = u32.CallWindowProc(oldProcPtr, h, msg, wp, lp);
                    if (r.intValue() != HTCLIENT) {
                        if (glassHtLogged.compareAndSet(false, true)) {
                            logDebug("hittest: glass returned HT=" + r.intValue() + " once — honoring Glass, skipping custom zones");
                        }
                        return r; // Glass 已有结论（如原生命中区）则尊重
                    }
                    return hitTest(u32, h, lp);
                }

                default:
                    return u32.CallWindowProc(oldProcPtr, h, msg, wp, lp);
            }
        }

        /** 自定义命中测试。client = 整个窗口（无 NCA 内缩），scene 左上 = 窗口左上。
         *  边环 8 区（窗口最外 border 像素）→ 框架 resize；标题栏带 → 移动窗口；坐标均为物理像素。 */
        private WinDef.LRESULT hitTest(User32Ex u32, WinDef.HWND h, WinDef.LPARAM lp) {
            long v = lp.longValue();
            int x = (int) (v & 0xFFFF);
            if (x > 0x7FFF) x -= 0x10000;
            int y = (int) ((v >>> 16) & 0xFFFF);
            if (y > 0x7FFF) y -= 0x10000;

            WinDef.RECT rc = new WinDef.RECT();
            if (!u32.GetWindowRect(h, rc)) {
                return new WinDef.LRESULT(HTCLIENT);
            }

            boolean zoomed = u32.IsZoomed(h);

            // 边环（NCA 环，窗口最外 border 像素）：8 区 resize
            boolean topEdge = y >= rc.top && y < rc.top + border;
            boolean bottomEdge = y > rc.bottom - border && y <= rc.bottom;
            boolean leftEdge = x >= rc.left && x < rc.left + border;
            boolean rightEdge = x > rc.right - border && x <= rc.right;

            if (!zoomed) {
                if (topEdge && leftEdge) return new WinDef.LRESULT(HTTOPLEFT);
                if (topEdge && rightEdge) return new WinDef.LRESULT(HTTOPRIGHT);
                if (bottomEdge && leftEdge) return new WinDef.LRESULT(HTBOTTOMLEFT);
                if (bottomEdge && rightEdge) return new WinDef.LRESULT(HTBOTTOMRIGHT);
                if (topEdge) return new WinDef.LRESULT(HTTOP);
                if (bottomEdge) return new WinDef.LRESULT(HTBOTTOM);
                if (leftEdge) return new WinDef.LRESULT(HTLEFT);
                if (rightEdge) return new WinDef.LRESULT(HTRIGHT);
            }

            // 标题栏带（scene 顶部 titleBarH 像素，最外 border 像素让给顶部边 resize）：
            // 左侧拖动 = 移动窗口（最大化时 = 还原）；右侧按钮区让位给 HTCLIENT
            int sceneTop = rc.top;
            int sceneBottom = sceneTop + titleBarH;
            if (y >= sceneTop && y < sceneBottom) {
                int btnStart = rc.right - rightButtonsW; // 宽松取 2x 区，保证按钮可点
                if (x < btnStart) {
                    return new WinDef.LRESULT(HTCAPTION);
                }
            }
            return new WinDef.LRESULT(HTCLIENT);
        }
    }

    private final WinDef.HWND hwnd;
    private final BaseTSD.LONG_PTR oldProc;
    private final Pointer oldProcPtr;
    private final WndProc wndProc;   // 强引用（JNA 回调 map 为弱引用）
    private final Pointer trampoline;

    private NativeWindow(WinDef.HWND hwnd, BaseTSD.LONG_PTR oldProc, WndProc wndProc, Pointer trampoline) {
        this.hwnd = hwnd;
        this.oldProc = oldProc;
        this.oldProcPtr = new Pointer(oldProc.longValue());
        this.wndProc = wndProc;
        this.trampoline = trampoline;
    }

    /**
     * 按标题查找窗口并安装原生缩放。
     *
     * @param title         窗口标题
     * @param border        NCA 边框环厚度（物理像素，建议 6-12）
     * @param titleBarH     标题栏带高度（物理像素）
     * @param rightButtonsW 标题栏右侧按钮区宽度（物理像素）
     * @return 成功返回实例；JNA/窗口不可用时返回 null（降级为纯 JavaFX）
     */
    public static NativeWindow install(String title, int border, int titleBarH, int rightButtonsW) {
        try {
            User32 u32 = User32.INSTANCE;
            WinDef.HWND h = u32.FindWindow(null, title);
            if (h == null || h.getPointer() == null || h.getPointer().equals(Pointer.NULL)) {
                logDebug("FindWindow returned null for title=" + title);
                return null;
            }
            BaseTSD.LONG_PTR oldProc = u32.GetWindowLongPtr(h, GWL_WNDPROC);
            if (oldProc == null || oldProc.longValue() == 0L) {
                logDebug("GetWindowLongPtr returned 0/null");
                return null;
            }
            // 保险：确保 WS_THICKFRAME 存在（框架 resize 循环的前提；幂等）
            int style = u32.GetWindowLong(h, GWL_STYLE);
            u32.SetWindowLong(h, GWL_STYLE, style | WS_THICKFRAME);

            // 把窗口类的背景画刷从默认白色（GetSysColorBrush(COL_WINDOW)）染成深色。
            // undecorated 窗口在 DPI 取整/最大化时，Scene 与原生客户区之间的亚像素缝隙
            // 会透出这个画刷 → 就是用户看到的四周 1-2px 白框。深色后缝隙不可见。
            try {
                long darkBrush = Gdi32Ex.INSTANCE.CreateSolidBrush(BRUSH_DARK);
                if (darkBrush != 0L) {
                    User32Ex.INSTANCE.SetClassLongPtr(h, GCLP_HBRBACKGROUND, darkBrush);
                    logDebug("class HBRBACKGROUND set to dark brush 0x" + Long.toHexString(darkBrush));
                }
            } catch (Throwable t) {
                logDebug("HBRBACKGROUND swap failed (fallback: white gaps may show): " + t);
            }

            // 染 DWM 窗口边框色（失焦时 DWM 在窗口四周画的 1px 边框，默认浅色主题白色）。
            // 老系统（Win10 2004 前）不支持该属性，返回非 0 HRESULT——记日志后继续，不中断安装。
            try {
                com.sun.jna.Memory m = new com.sun.jna.Memory(4);
                m.setInt(0, BRUSH_DARK); // COLORREF 小端
                int hr = DwmApi.INSTANCE.DwmSetWindowAttribute(h, DWMWA_BORDER_COLOR, m, 4);
                hr = Math.max(hr, DwmApi.INSTANCE.DwmSetWindowAttribute(h, DWMWA_CAPTION_COLOR, m, 4));
                if (hr == 0) {
                    logDebug("DWM border/caption color set to dark");
                } else {
                    logDebug("DwmSetWindowAttribute hr=0x" + Integer.toHexString(hr) + " (border color unsupported on this build of Windows; white inactive frame may persist)");
                }
            } catch (Throwable t) {
                logDebug("DWM border color set failed: " + t);
            }

            WndProc cb = new WndProc(h, oldProc, border, titleBarH, rightButtonsW);
            Pointer tramp = CallbackReference.getFunctionPointer(cb);
            u32.SetWindowLongPtr(h, GWL_WNDPROC, tramp);
            return new NativeWindow(h, oldProc, cb, tramp);
        } catch (Throwable t) {
            logDebug("install error: " + t + "\n" + stackTrace(t));
            return null;
        }
    }

    @Override
    public void close() {
        try {
            if (hwnd != null && oldProcPtr != null && !hwnd.getPointer().equals(Pointer.NULL)) {
                User32.INSTANCE.SetWindowLongPtr(hwnd, GWL_WNDPROC, oldProcPtr);
            }
        } catch (Throwable ignored) {
        }
    }

    /** OS 标准最大化切换：最大化 = 显示器工作区（和其它 Windows 应用一致），再点还原到之前位置/大小。
     *  走 ShowWindow 让 Windows 框架处理，Glass 经 WM_SIZE 同步场景；IsZoomed 状态与 NCHITTEST 边区跳过逻辑一致。 */
    public synchronized void toggleMaximize() {
        if (hwnd == null || hwnd.getPointer().equals(Pointer.NULL)) {
            return;
        }
        // WinUser.h: SW_RESTORE=1, SW_MAXIMIZE=3
        boolean zoomed = User32Ex.INSTANCE.IsZoomed(hwnd);
        User32Ex.INSTANCE.ShowWindow(hwnd, zoomed ? 1 : 3);
    }

    /** 若当前最大化则还原（幂等；windowResetHook 用） */
    public synchronized void restore() {
        if (hwnd == null || hwnd.getPointer().equals(Pointer.NULL)) {
            return;
        }
        if (User32Ex.INSTANCE.IsZoomed(hwnd)) {
            User32Ex.INSTANCE.ShowWindow(hwnd, 1); // SW_RESTORE
        }
    }

    /** 是否处于 OS 最大化状态 */
    public boolean isMaximized() {
        if (hwnd == null || hwnd.getPointer().equals(Pointer.NULL)) {
            return false;
        }
        return User32Ex.INSTANCE.IsZoomed(hwnd);
    }

    private static void logDebug(String msg) {
        try {
            java.nio.file.Path d = com.aivideo.DataDirs.dataDir();
            java.nio.file.Files.createDirectories(d);
            java.nio.file.Files.write(d.resolve("native-debug.log"),
                    (msg + System.lineSeparator()).getBytes(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private static String stackTrace(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
