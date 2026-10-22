// YUCL window edge/corner pixel probe (DPI-unaware: GetWindowRect + CopyFromScreen both virtualized — consistent)
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using System.Runtime.InteropServices;
using System.Threading;

class Probe
{
    [StructLayout(LayoutKind.Sequential)]
    struct RECT { public int left, top, right, bottom; }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern IntPtr FindWindowW(string cls, string title);
    [DllImport("user32.dll")] static extern int GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr h, int idx);
    [DllImport("user32.dll")] static extern IntPtr EnumWindows(EnumWindowsProc cb, IntPtr p);
    delegate bool EnumWindowsProc(IntPtr h, IntPtr p);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassNameW(IntPtr h, System.Text.StringBuilder s, int n);
    [DllImport("user32.dll")] static extern int GetClassLongPtr(IntPtr h, int idx);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern int GetWindowTextW(IntPtr h, System.Text.StringBuilder s, int n);
    [DllImport("user32.dll")] static extern int SetClassLongPtr(IntPtr h, int idx, int dv);
    [DllImport("user32.dll", EntryPoint = "SetClassLongPtrA")] static extern int SetClassLongPtrA_Explicit(IntPtr h, int idx, int dv);
    [DllImport("user32.dll", EntryPoint = "SetClassLongPtrW")] static extern int SetClassLongPtrW_Explicit(IntPtr h, int idx, int dv);
    [DllImport("gdi32.dll")] static extern int CreateSolidBrush(int rgb);
    [DllImport("kernel32.dll")] static extern uint GetLastError();
    [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int w, int hp, uint flags);
    [DllImport("user32.dll")] static extern int ShowWindow(IntPtr h, int cmd);
    [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int attr, out int val, int cb);
    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr h, int attr, ref int val, int cb);
    [StructLayout(LayoutKind.Sequential)]
    struct MARGINS { public int cxLeft, cxRight, cxTop, cxBottom; }
    [DllImport("dwmapi.dll")] static extern int DwmExtendFrameIntoClientArea(IntPtr h, ref MARGINS m);
    [DllImport("gdi32.dll")] static extern IntPtr CreateRoundRectRgn(int l, int t, int r, int b, int wr, int hr);
    [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr o);
    [DllImport("user32.dll")] static extern int SetWindowRgn(IntPtr h, IntPtr rgn, bool repaint);
    [DllImport("user32.dll")] static extern int SetWindowLong(IntPtr h, int idx, int dw);

    static string hex(int r, int g, int b) { return string.Format("#{0:x2}{1:x2}{2:x2}", r, g, b); }

    static int Main(string[] args)
    {
        try { return Run(); }
        catch (Exception e)
        {
            try { System.IO.File.WriteAllText(System.IO.Directory.GetCurrentDirectory() + "\\probe-error.txt", e.ToString()); } catch { }
            Console.WriteLine("PROBE EXC: " + e.GetType().Name + " " + e.Message);
            return 2;
        }
    }

    static IntPtr foundH;
    static int yuclCount;
    static bool EnumCb(IntPtr h, IntPtr p)
    {
        System.Text.StringBuilder sb = new System.Text.StringBuilder(256);
        int len = GetWindowTextW(h, sb, 256);
        string title = sb.ToString(0, Math.Max(0, len));
        if (!title.Contains("YUCL")) return true;
        yuclCount++;
        int style = GetWindowLong(h, -16);
        bool vis = (style & 0x10000000) != 0;
        RECT r; GetWindowRect(h, out r);
        int w = r.right - r.left, hh = r.bottom - r.top;
        Console.WriteLine("  YUCL-window #" + yuclCount + " hwnd=" + h + " vis=" + vis + " " + w + "x" + hh + " at " + r.left + "," + r.top + " title='" + title + "'");
        // pick the on-screen, reasonably-sized one
        if (vis && w >= 500 && hh >= 400 && r.left > -100 && r.top > -100)
        { foundH = h; }
        return true; // keep enumerating; last suitable one wins
    }

    static bool EnumAll(IntPtr h, IntPtr p)
    {
        System.Text.StringBuilder cls = new System.Text.StringBuilder(64);
        GetClassNameW(h, cls, 64);
        string cname = cls.ToString();
        if (cname.IndexOf("Glass", System.StringComparison.OrdinalIgnoreCase) < 0
            && cname.IndexOf("Sun", System.StringComparison.OrdinalIgnoreCase) < 0
            && cname.IndexOf("Java", System.StringComparison.OrdinalIgnoreCase) < 0) return true;
        int style = GetWindowLong(h, -16);
        bool vis = (style & 0x10000000) != 0;
        RECT r; GetWindowRect(h, out r);
        System.Text.StringBuilder sb = new System.Text.StringBuilder(256);
        int len = GetWindowTextW(h, sb, 256);
        string title = sb.ToString(0, Math.Max(0, len));
        Console.WriteLine("  GLASS " + (r.right - r.left) + "x" + (r.bottom - r.top) + " at " + r.left + "," + r.top
            + " vis=" + vis + " class='" + cname + "' title='" + title + "'");
        if (vis && (r.right - r.left) >= 600 && (r.bottom - r.top) >= 400 && r.left > -100 && r.top > -100) foundH = h;
        return true;
    }

    static int Run()
    {
        foundH = IntPtr.Zero; yuclCount = 0;
        Console.WriteLine("--- enumerating ALL visible top-level windows >=600x400 ---");
        try { EnumWindows(EnumAll, IntPtr.Zero); } catch (Exception e) { Console.WriteLine("enum err " + e.Message); }
        Console.WriteLine("suitable = " + foundH);
        IntPtr h = foundH != IntPtr.Zero ? foundH : FindWindowW(null, "YUCL · AI 短视频工作室");
        if (h == IntPtr.Zero) { Console.WriteLine("YUCL HWND not found"); return 1; }
        int style = GetWindowLong(h, -16);
        Console.WriteLine("hwnd=" + h + " style=0x" + style.ToString("X8")
            + " wsThinFrame=" + ((style & 0x40000) != 0) + " wsCaption=" + ((style & 0xC0000) != 0));
        int corner = -1;
        try { corner = -1; DwmGetWindowAttribute(h, 33, out corner, 4); } catch { }
        Console.WriteLine("DWM corner pref attr33 readback = " + corner + " (0=default 2=round 3=roundsmall; -1=read failed)");
        RECT rc; GetWindowRect(h, out rc);
        Console.WriteLine("rect virtual px: " + rc.left + "," + rc.top + " -> " + rc.right + "," + rc.bottom + " (" + (rc.right - rc.left) + "x" + (rc.bottom - rc.top) + ")");

        // restore collapsed/off-screen window to normal visible state before probing
        if (rc.left < -100 || rc.top < -100 || (rc.right - rc.left) < 400 || (rc.bottom - rc.top) < 300)
        {
            Console.WriteLine(">>> window collapsed/off-screen — restoring to 920x705 at (60,60)");
            uint SWP_NOZORDER = 0x0004;
            SetWindowPos(h, IntPtr.Zero, 60, 60, 920, 705, SWP_NOZORDER);
            ShowWindow(h, 1); // SW_SHOWNORMAL
            Thread.Sleep(2500); // let Glass sync + webview paint
            GetWindowRect(h, out rc);
            Console.WriteLine("restored rect: " + rc.left + "," + rc.top + " -> " + rc.right + "," + rc.bottom + " (" + (rc.right - rc.left) + "x" + (rc.bottom - rc.top) + ")");
        }

        Console.WriteLine("--- class background brush experiment (GCLP_HBRBACKGROUND=-10, applies LIVE) ---");
        int prev = GetClassLongPtr(h, -10);
        Console.WriteLine("GetClassLongPtr(-10) before = 0x" + prev.ToString("X") + (prev != 0 ? " (has current brush)" : " (none)"));
        int brush = CreateSolidBrush(0x000F0B0A);
        uint berr = GetLastError();
        Console.WriteLine("CreateSolidBrush(#0a0b0f) = 0x" + brush.ToString("X") + " err=" + berr);
        if (brush != 0)
        {
            int res = SetClassLongPtr(h, -10, brush);
            uint serr = GetLastError();
            Console.WriteLine("SetClassLongPtr(-10, brush) = 0x" + res.ToString("X") + " err=" + serr + (res == 0 ? "  ==> FAILED" : "  ==> APPLIED (prev=0x" + res.ToString("X") + ")"));
            int now = GetClassLongPtr(h, -10);
            Console.WriteLine("GetClassLongPtr(-10) after = 0x" + now.ToString("X"));
        }

        Rectangle vs = SystemInformation.VirtualScreen;
        Console.WriteLine("virtual screen " + vs.Width + "x" + vs.Height + " origin " + vs.X + "," + vs.Y);
        Bitmap bmp = new Bitmap(vs.Width, vs.Height);
        using (Graphics g = Graphics.FromImage(bmp))
            g.CopyFromScreen(vs.X, vs.Y, 0, 0, bmp.Size, CopyPixelOperation.SourceCopy);

        int L = rc.left, T = rc.top, R = rc.right - 1, B = rc.bottom - 1;
        int my = (T + B) / 2, mx = (L + R) / 2;
        Console.WriteLine("--- inside edge ring (window content edges) ---");
        Sample(bmp, vs, "TL-in1", L + 1, T + 1); Sample(bmp, vs, "TL-in3", L + 3, T + 3);
        Sample(bmp, vs, "TR-in1", R - 1, T + 1); Sample(bmp, vs, "TR-in3", R - 3, T + 3);
        Sample(bmp, vs, "BL-in1", L + 1, B - 1); Sample(bmp, vs, "BL-in3", L + 3, B - 3);
        Sample(bmp, vs, "BR-in1", R - 1, B - 1); Sample(bmp, vs, "BR-in3", R - 3, B - 3);
        Sample(bmp, vs, "Lmid-in1", L + 1, my); Sample(bmp, vs, "Lmid-in3", L + 3, my);
        Sample(bmp, vs, "Rmid-in1", R - 1, my); Sample(bmp, vs, "Rmid-in3", R - 3, my);
        Sample(bmp, vs, "Tmid-in1", mx, T + 1); Sample(bmp, vs, "Tmid-in3", mx, T + 3);
        Sample(bmp, vs, "Bmid-in1", mx, B - 1); Sample(bmp, vs, "Bmid-in3", mx, B - 3);
        Console.WriteLine("--- rect corners & just outside ---");
        Sample(bmp, vs, "TL-exact", L, T); Sample(bmp, vs, "TR-exact", R, T);
        Sample(bmp, vs, "BL-exact", L, B); Sample(bmp, vs, "BR-exact", R, B);
        Sample(bmp, vs, "TL-out", L - 2, T - 2); Sample(bmp, vs, "TR-out", R + 2, T - 2);
        Sample(bmp, vs, "BL-out", L - 2, B + 2); Sample(bmp, vs, "BR-out", R + 2, B + 2);
        Console.WriteLine("INTERPRET: in1/in3 light #F0+ = white border still visible; " +
            "corner-exact showing desktop colors (not #0a0b0f/#0d1120 chrome) = DWM rounded corners active; " +
            "corner-exact == app chrome dark = square corners.");
        // save the full desktop capture for visual inspection (window rect printed above)
        try {
            string outp = System.IO.Directory.GetCurrentDirectory() + "\\yucl-desktop-full.png";
            bmp.Save(outp, System.Drawing.Imaging.ImageFormat.Png);
            Console.WriteLine("saved desktop capture -> " + outp + " (window rect: " + L + "," + T + " -> " + R + "," + B + ")");
        } catch (Exception e) { Console.WriteLine("capture save failed: " + e.Message); }

        Console.WriteLine("--- EXPERIMENTS (live, on window " + h + ") ---");
        int L2 = rc.left, T2 = rc.top, R2 = rc.right - 1, B2 = rc.bottom - 1;
        Ring(bmp, vs, L2, T2, R2, B2, "BASE");
        // A: DwmExtendFrameIntoClientArea(0,0,0,0)
        MARGINS mg = new MARGINS();
        int hrA = DwmExtendFrameIntoClientArea(h, ref mg);
        Console.WriteLine("A: DwmExtendFrameIntoClientArea(0,0,0,0) hr=0x" + hrA.ToString("X"));
        Thread.Sleep(400);
        bmp = CapScreen(vs);
        Ring(bmp, vs, L2, T2, R2, B2, "A");
        // B: strip WS_CAPTION/WS_BORDER/WS_DLGFRAME (keep WS_THICKFRAME etc.)
        int st = GetWindowLong(h, -16);
        int st2 = st & ~0x00C00000;
        if (st2 != st) { SetWindowLong(h, -16, st2); Console.WriteLine("B: style 0x" + st.ToString("X8") + " -> 0x" + st2.ToString("X8")); }
        else Console.WriteLine("B: caption bits already absent");
        Thread.Sleep(400);
        bmp = CapScreen(vs);
        Ring(bmp, vs, L2, T2, R2, B2, "B");
        // D: DWMWA_BORDER_COLOR = DWMWA_COLOR_NONE (0xFFFFFFEE) — official "rounded window with no border"
        int none = unchecked((int)0xFFFFFFEE);
        int hrD = DwmSetWindowAttribute(h, 34, ref none, 4);
        Console.WriteLine("D: BORDER_COLOR=COLOR_NONE hr=0x" + hrD.ToString("X"));
        Thread.Sleep(800);
        bmp = CapScreen(vs);
        Ring(bmp, vs, L2, T2, R2, B2, "D");
        // save final state for vision
        try {
            string outp = System.IO.Directory.GetCurrentDirectory() + "\\yucl-experiment-final.png";
            bmp.Save(outp, System.Drawing.Imaging.ImageFormat.Png);
            Console.WriteLine("saved final -> " + outp);
        } catch { }

        Console.WriteLine("--- strip from TOP edge inward (x=895) ---");
        for (int d = 0; d <= 12; d++) Sample(bmp, vs, "T+" + d, mx, T + d);
        Console.WriteLine("--- strip from LEFT edge inward (y=593) ---");
        for (int d = 0; d <= 12; d++) Sample(bmp, vs, "L+" + d, L + d, 593);
        Console.WriteLine("--- strip from RIGHT edge inward (y=593) ---");
        for (int d = 0; d <= 12; d++) Sample(bmp, vs, "R-" + d, R - d, 593);
        Console.WriteLine("--- strip from BOTTOM edge inward (x=895) ---");
        for (int d = 0; d <= 12; d++) Sample(bmp, vs, "B-" + d, mx, B - d);
        Console.WriteLine("--- deep reference (should be app content) ---");
        Sample(bmp, vs, "deep-center", mx, (T + B) / 2);
        Sample(bmp, vs, "deep-titlebar", mx, T + 16);
        Console.WriteLine("INTERPRET: if T+0..T+2 are light and T+3..T+12 dark #0d1120 => thin white border at very edge (GDI/DWM). " +
            "If T+0..T+12 all light => the top area itself is light (content). Compare L vs R to see desktop correlation.");
        return 0;
    }

    static Bitmap CapScreen(Rectangle vs)
    {
        Bitmap b = new Bitmap(vs.Width, vs.Height);
        using (Graphics g = Graphics.FromImage(b))
            g.CopyFromScreen(vs.X, vs.Y, 0, 0, b.Size, CopyPixelOperation.SourceCopy);
        return b;
    }

    static void Ring(Bitmap bmp, Rectangle vs, int L, int T, int R, int B, string tag)
    {
        int my = (T + B) / 2, mx = (L + R) / 2;
        Sample(bmp, vs, tag + "_T2", mx, T + 2);
        Sample(bmp, vs, tag + "_B2", mx, B - 2);
        Sample(bmp, vs, tag + "_L2", L + 2, my);
        Sample(bmp, vs, tag + "_R2", R - 2, my);
    }

    static void Sample(Bitmap bmp, Rectangle vs, string name, int sx, int sy)
    {
        int x = sx - vs.X, y = sy - vs.Y;
        if (x < 0 || y < 0 || x >= vs.Width || y >= vs.Height) { Console.WriteLine(name + " (" + sx + "," + sy + ") oob"); return; }
        Color c = bmp.GetPixel(x, y);
        Console.WriteLine(name + " (" + sx + "," + sy + ") = " + string.Format("#{0:x2}{1:x2}{2:x2}", c.R, c.G, c.B));
    }
}
