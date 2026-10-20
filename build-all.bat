rem YUCL one-click build (this machine's paths; requires YUCL not running when deploying)
setlocal
set JAVA_HOME=C:\java\java25
set MVN=D:\Maven\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd
set JP=C:\java\java25\bin\jpackage.exe
cd /d D:\yucl-src

echo [1/5] fat jar
call %MVN% -q -B package -DskipTests || goto :err

echo [2/5] tools.jar
if exist build-tools\classes\com\yuc\tools\Installer.class goto :have_tools
mkdir build-tools\classes 2>nul
"C:\java\java25\bin\javac.exe" -d build-tools\classes src\main\java\com\yuc\tools\Installer.java src\main\java\com\yuc\tools\Uninstaller.java || goto :err
"C:\java\java25\bin\jar.exe" cfe build-tools\tools.jar com.yuc.tools.Installer -C build-tools\classes . || goto :err
:have_tools

echo [3/5] app image
if exist dist\YUCL rmdir /s /q dist\YUCL
mkdir staging-app 2>nul
copy /y target\ai-video-java.jar staging-app\ >nul
%JP% --type app-image --name YUCL --input "D:\yucl-src\staging-app" --main-jar ai-video-java.jar --main-class org.springframework.boot.loader.launch.JarLauncher --icon "D:\yucl-src\app.ico" --dest "D:\yucl-src\dist" --java-options "-Djpackage.app-version=1.0.0" || goto :err

echo [4/5] uninstaller + setup
if exist dist\YUCL-Uninstall rmdir /s /q dist\YUCL-Uninstall
if exist dist\YUCL-Setup rmdir /s /q dist\YUCL-Setup
if exist dist-staging-setup rmdir /s /q dist-staging-setup
mkdir dist-staging-setup\uninstall-input
copy /y build-tools\tools.jar dist-staging-setup\uninstall-input\ >nul
%JP% --type app-image --name YUCL-Uninstall --input "D:\yucl-src\dist-staging-setup\uninstall-input" --main-jar tools.jar --main-class com.yuc.tools.Uninstaller --icon "D:\yucl-src\app.ico" --dest "D:\yucl-src\dist" --java-options "-Djpackage.app-version=1.0.0" || goto :err
copy /y build-tools\tools.jar dist-staging-setup\ >nul
xcopy /e /i /q dist\YUCL dist-staging-setup\yuc-app >nul
xcopy /e /i /q dist\YUCL-Uninstall dist-staging-setup\yuc-uninstall >nul
%JP% --type app-image --name YUCL-Setup --input "D:\yucl-src\dist-staging-setup" --main-jar tools.jar --main-class com.yuc.tools.Installer --icon "D:\yucl-src\app.ico" --dest "D:\yucl-src\dist" --java-options "-Djpackage.app-version=1.0.0" || goto :err

echo [5/5] zip
powershell -NoProfile -Command "Compress-Archive -Path 'D:\yucl-src\dist\YUCL-Setup' -DestinationPath 'D:\yucl-src\dist\YUCL-Setup-windows.zip' -Force" || goto :err
echo DONE: D:\yucl-src\dist\YUCL-Setup-windows.zip
exit /b 0

:err
echo BUILD FAILED
exit /b 1
