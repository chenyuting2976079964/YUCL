# 反编译恢复记录（2026-10-20）

本工程源码由 CFR 0.152 从线上 fat jar（当时 JDK17 + Boot 3.2.7 构建）反编译恢复，
再升级到 Java 25 / Boot 3.5.16 / OpenJFX 25。已知痕迹与修复清单：

## 修复的 CFR 伪影
- `ArrayList<CallSite>` / `(CallSite)((Object)(...))`：lambda 捕获被 CFR 错误地展开为
  方法引用形式，`fc`（filtergraph 片段列表）与 `voiceWarnings` 实际都是 `List<String>`。
  **这类伪影编译能过但运行时 `String→CallSite` 直接 CCE**，已全部清掉
  （VideoCore.composeLocal / CharBg.run / Agnesa.endpoints）。
- 泛型擦除痕迹：`Object p` → `Map<?,?>`、`LinkedHashMap<String,Serializable>` →
  `<String,Object>`、`ResponseEntity.ok()` 显式类型参数化（byte[]/InputStreamResource）。
- `new String[0]`/`new LinkOption[0]` 是 CFR 对 `Paths.get(...)` 可变参数的写法，合法、保留。
- 个别 checked 异常丢失（AiVideoApp.portBusy / AppShell.loadIcon 的 `close()`），
  补了 try-catch。

## 运行时踩过的坑（别重踩）
1. **Spring Boot 必须 ≥ 3.5**：3.2.7 内置 ASM 不认 Java 25 字节码（major 69），
   组件扫描直接 `Unsupported class file major version 69` 崩。
2. **JavaFX 只能走 classpath**：非模块化 app-image 的 jlink 运行时只 root java.se，
   `--java-options -add-modules` 会被启动器拒识；FX 原生库由 glass 运行时解压到
   %TEMP%\jfx。启动日志里 "classes were loaded from 'unnamed module'" 警告无害。
3. **视频编码必须 h264 main + faststart**（VideoCore 三处），WebView 媒体管线拒 HIGH。
4. jpackage 的 `--dest` 目标目录必须不存在（先删）；`ffmpeg 8` 已移除
   ffmpeg.conf 配置支持（`-x264opts` 写配置无效），所以参数在代码里写死。

## 与旧版本的类对照
- `com.aivideo.tools.{Installer,Uninstaller}` 是改名前的遗留副本，仍打进 fat jar，
  安装/卸载实际用 `com.yuc.tools.*`（tools.jar 只含这四个类里的 yuc 两个 + 无依赖）。


## Code audit 2026-10-20 (bugs found & fixed, source = authoritative java25 tree)

### P0 — would break / was broken
1. **`Api.restore()` was a CFR decompilation failure** (`throw IllegalStateException("Decompilation failed")`):
   rebuilding from source would have shipped a dead /restore endpoint. **Rewritten** with
   `restoreTarget()` mapping + `entryExtAllowed()` extension whitelist, so a backup zip can
   only write media files into the three media dirs (whitelisted extensions) or the three
   known config/log files — no arbitrary-file planting (`.exe`), no zip-slip.
2. **`/backup` OOM on large media libraries**: the endpoint returned `ResponseEntity<byte[]>`
   (entire zip in heap). **Now streams** via `FileSystemResource`; leftover temp zips are
   swept by a daemon (10s cadence, >10 min old; a file still being streamed is locked on
   Windows so the sweep is a no-op until the stream closes).
3. **`Uninstaller` command-injection-prone**: program-dir delete was
   `"cmd /c ... rmdir /s /q \"" + installDir + "\""`. **Now deleted in-process**
   (sleep + Files.walk). Also: it only looked at `%USERPROFILE%\YUCL` for user data —
   **now discovers** `YUCL_MEDIA_DIR`/`YUCL_DATA_DIR`/`YUCL_CACHE_DIR` env dirs +
   `%LOCALAPPDATA%\YUCL` + legacy home dir.
4. **`Installer` `~` expansion bug**: `installDir.substring(indexOf('~'))` kept the `~`
   itself → `C:\Users\x~/...` (invalid). **Fixed** to `substring(t + 1)`.
5. **`serveFile` Range crash on malformed input** (HTTP header, attacker-controllable):
   `Long.parseLong` without try/catch → 500 on `bytes=abc-`; overflow on huge offsets.
   **Now**: NumberFormatException → 416, negative start clamped to 0.

### P1 — robustness
6. **`safeChild` / `guardedChild` symlink escape**: lexical `normalize()` does not follow
   symlinks — a link inside the media dir pointing out of it passed the check. **Both
   now re-verify with `toRealPath()` containment** (guardedChild also rejects leading `/` `\`).
7. **`VideoCore.runFfmpeg`**: stdout was read once (8 KB) and the stream closed — a
   broken pipe if ffmpeg ever printed more. **Now drains the whole stream.**
8. **`CharBg` runaway duration**: a long local audio file stretched a shot to its full
   length (ffmpeg 900 s timeout, multi-minute clips). **Now capped at 60 s** with a
   voice_warning.
9. **`Agnesa.generate`**: `model` was spliced into the `/agnesapi` query unencoded →
   `URI.create` IllegalArgumentException on any model name with special chars. **Now
   URLEncoder-encoded.** `credentialStatus` also mis-handled 401/403 (a 403 was not
   reported as "key rejected"); **now tracks lastRejected**.
10. **`/logs` OOM on big rolling logs**: `readAllLines` of the whole file. **Now a
    one-pass bounded-deque tail** (last N lines only).
11. **`Installer` resource leak**: `Files.list(target).count()` never closed. **Now
    try-with-resources + findAny().**

### Known accepted risks (local 127.0.0.1 service — noted, not fixed)
- `provider-test` / image-URL download accept user-supplied URLs → SSRF against LAN is
  possible, but the whole API is loopback-only (server.address=127.0.0.1).
- `out_dir` / media absolute paths in job params are user-controllable (same-user local
  tool; retry reuses stored params).
- `/provider-config` GET returns a **masked** key (`sk-…jOMi`) — verified in the API;
  the front-end already edits against the mask, no leak.
