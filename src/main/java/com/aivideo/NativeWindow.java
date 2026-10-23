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

    /** WM_GETMINMAXINFO 结构体（JNA 5.14 未封装） */
    public static class MINMAXINFO extends com.sun.jna.Structure {
        public WinDef.POINT ptReserved = new WinDef.POINT();
        public WinDef.POINT ptMaxSize = new WinDef.POINT();
        public WinDef.POINT ptMaxPosition = new WinDef.POINT();
        public WinDef.POINT ptMinTrackSize = new WinDef.POINT();
        public WinDef.POINT ptMaxTrackSize = new WinDef.POINT();

        public MINMAXINFO(Pointer p) {
            super(p);
            read();
        }

        @Override
        protected java.util.List<String> getFieldOrder() {
            return java.util.Arrays.asList("ptReserved", "ptMaxSize", "ptMaxPosition",
                    "ptMinTrackSize", "ptMaxTrackSize");
        }
    }

    /** Gdi32：创建窗口类背景画刷（句柄返回原始 long，避免 jna WinDef.HANDLE 差异） */
    interface Gdi32Ex extends com.sun.jna.Library {
        Gdi32Ex INSTANCE = Native.load("gdi32", Gdi32Ex.class, W32APIOptions.DEFAULT_OPTIONS);

        long CreateSolidBrush(int color);
    }

    /** GCLP_HBRBACKGROUND：替换窗口类的背景画刷（winuser.h 标准值 = -10） */
    private static final int GCLP_HBRBACKGROUND = -10;
    /** 0x00BBGGRR：#0a0b0f → R=0x0a G=0x0b B=0x0f */
    private static final int BRUSH_DARK = 0x000F0B0A;

    /**
     * DWM 窗口属性（dwmapi.h，Win11 22000+）。失焦白框的机制：
     * DWM 给每个顶层窗口画 1px 边框，颜色随激活状态联动——官方文档原话
     * "The app is responsible for changing the border color according to state
     * changes, such as a change in window activation"。启动时设一次暗色只管
     * active 状态；窗口失焦（用户点了别的窗口）时 DWM 回退系统默认（浅色主题
     * = 白）→ 四周白框只在失焦时可见。正解：BORDER_COLOR 设 DWMWA_COLOR_NONE
     * 彻底禁画边框（与激活状态无关），再顺手染 caption/text 深色 + 暗色模式。
     */
    interface DwmApi extends com.sun.jna.Library {
        DwmApi INSTANCE = Native.load("dwmapi", DwmApi.class);

        /** HRESULT DwmSetWindowAttribute(HWND, DWORD, const void*, DWORD) */
        int DwmSetWindowAttribute(WinDef.HWND hwnd, int dwAttribute, Pointer pvAttribute, int cbAttribute);
    }
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    private static final int DWMWA_BORDER_COLOR = 34;
    private static final int DWMWA_CAPTION_COLOR = 35;
    private static final int DWMWA_TEXT_COLOR = 36;

    /** 抑制边框绘制：官方 DWMWINDOWATTRIBUTE 文档（Win11 22000+）——
     *  "Specifying DWMWA_COLOR_NONE (value 4294967294, see dwmapi.h) for the color will suppress
     *   the drawing of the window border. This makes it possible to have a rounded
     *   window with no border."  即"圆角 + 无边框"的官方做法（PowerToys/Tauri 同款修复）。 */
    private static final int DWMWA_COLOR_NONE = 0xFFFFFFFE;
    /** DWMWCP_ROUND=2：Win11 圆角半径（与系统一致 ~8px）；Win10 接受该属性但视觉无效（优雅降级为方角） */
    private static final int DWMWCP_ROUND = 2;
    /** 0x00AARRGGBB 不相关——COLORREF 小端 0x00BBGGRR：#0a0b0f */
    private static final int DWM_COLOR_DARK = 0x000F0B0A;

    private static final int GWL_WNDPROC = -4;
    private static final int GWL_STYLE = -16;
    private static final int WS_THICKFRAME = 0x00040000;
    private static final int WM_DESTROY = 0x0002;
    private static final int WM_NCCALCSIZE = 0x0083;
    private static final int WM_NCHITTEST = 0x0084;
    /** 激活/失焦状态消息 —— 用于在每次激活变化时重新钉住深色 DWM 边框 */
    private static final int WM_ACTIVATE = 0x0006;
    private static final int WM_SETFOCUS = 0x0001;
    private static final int WM_KILLFOCUS = 0x0008;
    /** 非客户区激活：DWM 失焦时会重置边框颜色（= 失焦白框的触发点） */
    private static final int WM_NCACTIVATE = 0x0086;
    /** 非客户区绘制：客户区铺满窗口后 NC 区为空，系统仍会按 WS_THICKFRAME
     *  重画 3D 斜面边框（激活/失焦切换时最明显）→ 必须吞掉，不能转发 */
    private static final int WM_NCPAINT = 0x0085;
    /** 最大化尺寸/位置协商：客户区=整个窗口时，系统仍按"带边框"给最大化区域，
     *  窗口会比屏幕大一圈（实测 -10,-10→2570,1610），必须自己钳到显示器工作区 */
    private static final int WM_GETMINMAXINFO = 0x0024;

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

    /** 激活切换后的延迟重钉执行器（DWM 异步重绘会把边框色回退成系统默认） */
    private static final java.util.concurrent.ScheduledExecutorService REAPPLY =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dwm-border-reapply");
                t.setDaemon(true);
                return t;
            });

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

                // 吞掉非客户区绘制：客户区=整个窗口后这里本不该有任何像素，
                // 但系统在激活/失焦切换时会重画 3D 斜面边框（浅灰 8px + 白/灰 1px），
                // 转发给 Glass/DefWindowProc 就等于让它画 → 这就是"点别的窗口就出白边"。
                case WM_NCPAINT:
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

                // 激活状态变化：DWM 会在切换时把边框颜色重置为系统默认（失焦白框），
                // 必须在消息处理【之后】再钉一次（顺序颠倒等于钉在旧状态上），
                // 并补一次 ~80ms 的延迟重钉，防止 DWM 异步重绘时又回退。
                case WM_ACTIVATE:
                case WM_SETFOCUS:
                case WM_KILLFOCUS: {
                    WinDef.LRESULT rr = u32.CallWindowProc(oldProcPtr, h, msg, wp, lp);
                    applyDwmColors(h);
                    REAPPLY.schedule(() -> {
                        try {
                            applyDwmColors(h);
                        } catch (Throwable ignored) {
                        }
                    }, 80, java.util.concurrent.TimeUnit.MILLISECONDS);
                    return rr;
                }

                // WM_NCACTIVATE 不能转发：DefWindowProc 收到后会直接调用内部 NC 绘制，
                // 按 WS_THICKFRAME 画一圈 Win32 3D 斜面边框（高光 #e3e3e3/#ffffff +
                // 面 #b4b4b4 + 阴影 #a0a0a0/#696969，共 10px），盖在客户区最外圈 ——
                // 这就是"点别的窗口/切回来自动出现白边"的真正来源。
                // 直接返回 TRUE 阻止重画；Glass 的激活/焦点状态走 WM_ACTIVATE，不受影响。
                case WM_NCACTIVATE: {
                    applyDwmColors(h);
                    REAPPLY.schedule(() -> {
                        try {
                            applyDwmColors(h);
                        } catch (Throwable ignored) {
                        }
                    }, 80, java.util.concurrent.TimeUnit.MILLISECONDS);
                    return new WinDef.LRESULT(1);
                }

                // 最大化区域钳到显示器工作区（客户区=整个窗口带来的必然副作用）
                case WM_GETMINMAXINFO: {
                    WinDef.LRESULT r = u32.CallWindowProc(oldProcPtr, h, msg, wp, lp);
                    try {
                        MINMAXINFO mm = new MINMAXINFO(new Pointer(lp.longValue()));
                        WinUser.HMONITOR mon = u32.MonitorFromWindow(h, WinUser.MONITOR_DEFAULTTONEAREST);
                        WinUser.MONITORINFO mi = new WinUser.MONITORINFO();
                        if (u32.GetMonitorInfo(mon, mi).booleanValue()) {
                            mm.ptMaxPosition.x = mi.rcWork.left - mi.rcMonitor.left;
                            mm.ptMaxPosition.y = mi.rcWork.top - mi.rcMonitor.top;
                            mm.ptMaxSize.x = mi.rcWork.right - mi.rcWork.left;
                            mm.ptMaxSize.y = mi.rcWork.bottom - mi.rcWork.top;
                            mm.write();
                        }
                    } catch (Throwable ignored) {
                    }
                    return r;
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
                    BaseTSD.LONG_PTR prev = User32Ex.INSTANCE.SetClassLongPtr(h, GCLP_HBRBACKGROUND, darkBrush);
                    boolean ok = prev != null && prev.longValue() != 0L;
                    logDebug("class HBRBACKGROUND->dark 0x" + Long.toHexString(darkBrush)
                            + " GCLP=" + GCLP_HBRBACKGROUND
                            + " SetClassLongPtr prev=0x" + (prev == null ? "null" : Long.toHexString(prev.longValue()))
                            + " ok=" + ok);
                }
            } catch (Throwable t) {
                logDebug("HBRBACKGROUND swap failed (fallback: white gaps may show): " + t);
            }

            // DWM 边框治理（Win11 22000+；老系统返回非 0 时记日志降级，不中断安装）：
            // 边框/标题染应用深色 #0a0b0f（与 UI 同色→视觉不可见）+ 文本染白 + 暗色模式。
            // 注意：这只是"启动时（active 状态）"的一次应用；失焦时 DWM 会回退系统浅色边框，
            // 真正消除失焦白框靠 WndProc 在 WM_ACTIVATE/SETFOCUS/KILLFOCUS 时反复调用本方法。
            applyDwmColors(h);

            WndProc cb = new WndProc(h, oldProc, border, titleBarH, rightButtonsW);
            Pointer tramp = CallbackReference.getFunctionPointer(cb);
            u32.SetWindowLongPtr(h, GWL_WNDPROC, tramp);
            // 关键一步：强制框架重算非客户区。子类化发生在窗口创建之后，
            // 创建时 Glass 已按带 WS_THICKFRAME 的窗口算好了 NC 边框区（2px），
            // 不做这一步，WM_NCCALCSIZE 永远轮不到我们的实现（client 仍比窗口小 2px），
            // DWM 就会在那 2px 里画浅色边框 —— 就是用户看到的"白边"。
            // SWP_FRAMECHANGED 触发 WM_NCCALCSIZE，本类返回 0 → 客户区 = 整个窗口。
            u32.SetWindowPos(h, null, 0, 0, 0, 0,
                    WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOZORDER
                            | WinUser.SWP_NOACTIVATE | WinUser.SWP_FRAMECHANGED);
            logDebug("install: frame recalculated (SWP_FRAMECHANGED) after subclass");
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

    /**
     * 重新应用 DWM 边框/标题/文本/圆角属性。
     *
     * <p><b>白边根因与官方解法</b>：Win11 的 DWM 给每个顶层窗口（含 undecorated）画一圈
     * 1-2px 边框，颜色随激活状态联动（active/inactive 各一套），浅色主题下失焦时
     * 回退成白——这就是界面四周的白边。按官方 DWMWINDOWATTRIBUTE 文档（Win11 22000+）：
     * 指定 <b>DWMWA_COLOR_NONE（值 4294967294，见 dwmapi.h）</b> 可<b>彻底抑制边框绘制</b>——
     * "rounded window with no border"，与激活状态无关（PowerToys PR#48091 同款修复）。
     * 圆角（WINDOW_CORNER_PREFERENCE=ROUND）与投影不受影响，仍由 DWM 正常渲染。
     *
     * <p>caption/text 染深色 + immersive dark mode 作为配套（边框抑制后基本不可见，
     * 保留以防未来样式变化）。每次激活状态变化时重新应用（幂等）。
     */
    private static void applyDwmColors(WinDef.HWND h) {
        try {
            com.sun.jna.Memory border = new com.sun.jna.Memory(4);
            border.setInt(0, DWMWA_COLOR_NONE); // 4294967294：抑制 DWM 边框线（dwmapi.h DWMWA_COLOR_NONE）
            int hrBorder = DwmApi.INSTANCE.DwmSetWindowAttribute(h, DWMWA_BORDER_COLOR, border, 4);
            logDebug("DWM applyDwmColors: BORDER=COLOR_NONE(4294967294) hr=0x" + Integer.toHexString(hrBorder));
            // caption（undecorated 窗口不可见，仅防御）染深色；COLOR_NONE 语义未定义于 caption，勿混用
            com.sun.jna.Memory caption = new com.sun.jna.Memory(4);
            caption.setInt(0, DWM_COLOR_DARK);
            DwmApi.INSTANCE.DwmSetWindowAttribute(h, DWMWA_CAPTION_COLOR, caption, 4);
            com.sun.jna.Memory white = new com.sun.jna.Memory(4);
            white.setInt(0, 0x00FFFFFF);
            DwmApi.INSTANCE.DwmSetWindowAttribute(h, DWMWA_TEXT_COLOR, white, 4);
            com.sun.jna.Memory dm = new com.sun.jna.Memory(4);
            dm.setInt(0, 1);
            DwmApi.INSTANCE.DwmSetWindowAttribute(h, DWMWA_USE_IMMERSIVE_DARK_MODE, dm, 4);
            // 四周圆角：让 DWM 对整个窗口（含 undecorated 客户区）套 Win11 圆角遮罩，
            // 标题栏/播放器四角随之圆弧化；Win10 上调用成功但无视觉变化
            com.sun.jna.Memory corner = new com.sun.jna.Memory(4);
            corner.setInt(0, DWMWCP_ROUND);
            int hrCorner = DwmApi.INSTANCE.DwmSetWindowAttribute(h, DWMWA_WINDOW_CORNER_PREFERENCE, corner, 4);
            // 已尝试禁用 DWM 非客户区渲染（属性 2）根治白边——实测副作用：窗口边缘变成
            // 全透明，桌面直接透出来（比白边更糟），故回退；白边改由类背景画刷（深色）
            // + DWM 边框染色覆盖。
            logDebug("DWM applyDwmColors: CORNER hr=0x" + Integer.toHexString(hrCorner)
                    + " (NC rendering kept enabled)");
        } catch (Throwable t) {
            logDebug("applyDwmColors failed: " + t);
        }
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
