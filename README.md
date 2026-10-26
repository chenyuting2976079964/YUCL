# YUCL — AI 短视频工作室（源码工程）

本地免费 AI 短视频生成工具：人物/背景成片 · 旁白 TTS · BGM 混音 · 字幕烧录。
独立窗口（JavaFX WebView）运行，也可用 `YUCL_GUI=0` 回落到浏览器模式。

## 环境要求
- **JDK 25**（本机：`C:\java\java25`）
- Maven 3.9+（本机：`D:\Maven\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd`）
- 发行包内自带 ffmpeg；开发机需 ffmpeg 在 `PATH` 或 `D:\YUCL\ffmpeg`

## 目录结构
```
src/main/java/com/aivideo/
  YuclApp.java           入口（Spring Boot + AppShell 窗口 + 端口占用检查）
  AppShell.java          JavaFX 原生窗口（WebView 载入 http://127.0.0.1:8791）
  NativeWindow.java      Windows 原生框架级缩放/移动（JNA WndProc 子类化，跟手不丢）
  NativePlayerOverlay.java  原生视频播放浮层（ffmpeg 软解 + Canvas 逐帧绘制）
  FfmpegFramePlayer.java 窗口内视频渲染器（ffmpeg 软解 H.264/HEVC → Canvas，双图池+vsync）
  ApiController.java     全部 HTTP API（/api/plugins/ai-video/*）
  CharBgComposer.java    人物·背景成片 pipeline（镜头合成/TTS/BGM/字幕）
  FfmpegCore.java        ffmpeg 底层（compose/normalize/concat/mixBgm/burnSrt）
  Providers.java         模型供应商配置（provider-config.json）
  ModelGateway.java      Agnes 内置模型端点发现（config.yaml/env）
  Jobs.java              任务持久化（jobs.json）
  Projects.java          工程管理（projects/<slug>/project.json + assets/）
  DataDirs.java          数据目录解析（YUCL_DATA_DIR/YUCL_CACHE_DIR 环境变量优先）
  NoCacheFilter.java
src/main/java/com/yuc/tools/
  Installer.java         安装器（YUCL-Setup.exe 的主类）
  Uninstaller.java       卸载器（YUCL-Uninstall.exe 的主类）
ui/                      Vue 3 + Vite UI 源码（界面真相源）
src/main/resources/
  application.properties  端口 8791、媒体/日志目录
  static/                UI 构建产物（build-all.bat 把 ui/dist 拷到 static/，再打进 jar）
    index.html + assets/ Vue 构建输出（改界面要改 ui/，别改这里）
    manual.html          使用说明
    playtest.html        窗口内播放探测（手动诊断页；正常走 ui 的自动探测）
```

## 构建
```
cd D:\yucl-src
set JAVA_HOME=C:\java\java25
mvn -B package -DskipTests        # 产出 target/ai-video-java.jar（fat jar，含 OpenJFX 25 win 原生）
```

### 出应用 image（放到 D:\YUCL）
```
copy target\ai-video-java.jar staging-app\
jpackage --type app-image --name YUCL --input staging-app --main-jar ai-video-java.jar ^
  --main-class org.springframework.boot.loader.launch.JarLauncher --icon app.ico ^
  --dest dist --java-options "-Djpackage.app-version=1.0.0"
```
（jpackage 用 `C:\java\java25\bin\jpackage.exe`；dist\YUCL 必须不存在或先删）

### 出安装/卸载器与分发 zip
```
javac -d build-tools\classes src\main\java\com\yuc\tools\Installer.java src\main\java\com\yuc\tools\Uninstaller.java
jar cfe build-tools\tools.jar com.yuc.tools.Installer -C build-tools\classes .
jpackage --type app-image --name YUCL-Uninstall --input <只含 tools.jar 的目录> --main-jar tools.jar --main-class com.yuc.tools.Uninstaller --icon app.ico --dest dist
:: 组合 staging：tools.jar + yuc-app(=dist\YUCL) + yuc-uninstall(=dist\YUCL-Uninstall)
jpackage --type app-image --name YUCL-Setup --input <staging> --main-jar tools.jar --main-class com.yuc.tools.Installer --icon app.ico --dest dist
:: 压缩 dist\YUCL-Setup 即分发 zip
```
详见 `build-all.bat`（本机路径已写死，可整体运行）。

## 版本管理与发布流程（GitHub 式）

约定（用户规则）：**每次更新只改代码并提交 git；只有确认的稳定版才封装分发包。**

```
# 日常：改完代码、编译+自测通过后
git add -A
git commit -m "改动简述"            # 每个更新一个 commit

# 联调/测试：直接把新 jar 丢进运行中的安装目录（不出分发包）
copy target\ai-video-java.jar D:\YUCL\app\
# 重启 YUCL.exe 即可生效

# 稳定版：打 tag + 出分发包
git tag v1.x.y -m "stable"
# 跑 build-all.bat（或 README 上面 jpackage 三步）生成 dist\YUCL-Setup
# 压缩成 D:\YUCL-Setup-windows.zip（带版本号），这是唯一要发给别人的东西
```

- 分支：`main` 为唯一主干；实验性改动开分支，稳了再并回 `main`。
- `target/`、`dist/`、`build-tools/classes/` 已 gitignore，不进库。
- 远端（GitHub）：`https://github.com/chenyuting2976079964/YUCL`（公开）。
  直连偶尔 TLS 超时，超时就走本机 Clash 代理（127.0.0.1:7897）。
  认证走 `gh auth`（git-credential helper 方式，`gh auth setup-git` 已注册）——
  **别**把 token 直接拼进 push URL（GitHub 对 `git` 协议会 401），**别**用 GCM
  (manager) 凭据（会弹 GUI 卡死）：
  `git -c credential.https://github.com/.helper="!gh auth git-credential" -c credential.interactive=false push origin main`
  （需要代理时前面加 `HTTPS_PROXY=http://127.0.0.1:7897`；推完用 `gh api repos/...` 验证远端 head）。
  **远端历史保持干净**：staging-app/ 等大产物已 gitignore，不要 commit 进库。

## 本地开发运行
```
set JAVA_HOME=C:\java\java25
mvn spring-boot:run            # 浏览器模式（自动开浏览器）
```
- `YUCL_GUI=0` 强制浏览器模式；默认启动 JavaFX 窗口
- `AI_VIDEO_NO_BROWSER=1` 连浏览器也不开
- 环境变量 `YUCL_DATA_DIR` / `YUCL_CACHE_DIR` 覆盖数据/缓存位置（本机安装约定：`D:\YUCL\data`、`D:\YUCL\cache`）

## 关键约定
- 视频输出 **必须** 是 h264 **main** profile（`FfmpegCore` 里三处重编码点已加
  `-profile:v main` 和 `-movflags +faststart`）：浏览器/WebView 媒体管线拒 HIGH。
  **但本 OpenJFX 构建的窗口内网页播放解不了 H.264**（WebKit 媒体栈无解码器，
  JavaFX MediaPlayer 的 GStreamer-lite 管线也不带任何视频解码器——所以 pom 不引
  `javafx-media`，引了也没用），窗口内成片播放走 **`FfmpegFramePlayer`：随包
  ffmpeg 软解 + Canvas 逐帧渲染**（暂停/续播=管线背压原地冻结，seek=闪帧+后台
  切管线，详见该文件类注释）。别删 h264 编码参数；也别指望改编码让网页 `<video>`
  直接播 H.264。
- 播放兜底层级（`PlayerPanel`）：网页 `<video>` → 黑屏/播不了自动切「⚡ 原生播放器」
  （`/playtest/native-request` → 进程内钩子在 AppShell 开浮层）→ 仍不行用
  「⇕ 在系统浏览器打开」（`/open-in-browser`，完整字幕轨道等功能也靠它）。
- OpenJFX 走 **classpath**（打进 fat jar，原生提取到 %TEMP%\jfx），不能给
  jpackage 加 `--java-options -add-modules`（非模块化 image 拒识），历史坑见
  `NOTES-decompile.md`。
- 源文件由 CFR 0.152 从线上 jar 反编译恢复，个别处有 `(Object)`/`@SuppressWarnings`
  痕迹，不影响行为；改代码时顺手清理。
