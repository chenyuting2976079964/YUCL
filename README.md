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
  AiVideoApp.java   入口（Spring Boot + AppShell 窗口 + 端口占用检查）
  AppShell.java     JavaFX 原生窗口（WebView 载入 http://127.0.0.1:8791）
  Api.java          全部 HTTP API（/api/plugins/ai-video/*）
  CharBg.java       人物·背景成片 pipeline（镜头合成/TTS/BGM/字幕）
  VideoCore.java    ffmpeg 底层（compose/normalize/concat/mixBgm/burnSrt）
  Providers.java    模型供应商配置（provider-config.json）
  Agnesa.java       Agnes 内置模型端点发现（config.yaml/env）
  Jobs.java         任务持久化（jobs.json）
  DataDirs.java     数据目录解析（YUCL_DATA_DIR/YUCL_CACHE_DIR 环境变量优先）
  NoCacheFilter.java
  tools/            安装/卸载器（jpackage 独立小 jar 使用）
src/main/java/com/yuc/tools/
  Installer.java    安装器（YUCL-Setup.exe 的主类）
  Uninstaller.java  卸载器（YUCL-Uninstall.exe 的主类）
src/main/resources/
  application.properties   端口 8791、媒体/日志目录
  static/index.html        UI（窗口内加载的就是它）
  static/manual.html       使用说明
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
  本机要走系统代理（127.0.0.1:7897），git 推的时候带上：
  `set HTTPS_PROXY=http://127.0.0.1:7897 & git push origin main`。
  认证走 `gh auth login`（设备码，已在 keyring，重跑 `gh auth status` 即可）。
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
- 视频输出 **必须** 是 h264 **main** profile（`VideoCore` 里三处重编码点已加
  `-profile:v main` 和 `-movflags +faststart`）：WebView/浏览器能正常播放。
  **但原生 JavaFX 窗口的媒体管线（webkit.mediaplayer）仍报 260
  ERROR_MEDIA_INVALID，解不了 h264**——这是 JavaFX 固有限制，改不了。原生窗口
  里的播放靠 UI 的"在系统浏览器打开"按钮（`/open-in-browser`）兜底。别删这两个
  编码参数；也别指望改编码能让原生窗口直接播。
- OpenJFX 走 **classpath**（打进 fat jar，原生提取到 %TEMP%\jfx），不能给
  jpackage 加 `--java-options -add-modules`（非模块化 image 拒识），历史坑见
  `NOTES-decompile.md`。
- 源文件由 CFR 0.152 从线上 jar 反编译恢复，个别处有 `(Object)`/`@SuppressWarnings`
  痕迹，不影响行为；改代码时顺手清理。
