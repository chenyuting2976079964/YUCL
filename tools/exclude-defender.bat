@echo off
rem YUCL: add app dir + OpenJFX native cache to Windows Defender exclusions (self-elevates)
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting administrator...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
echo Adding Defender exclusions...
powershell -NoProfile -Command "Add-MpPreference -ExclusionPath 'D:\YUCL'; Add-MpPreference -ExclusionPath 'C:\Users\28737\.openjfx'; Add-MpPreference -ExclusionProcess 'YUCL.exe'; Write-Host 'OK'; (Get-MpPreference).ExclusionPath | Select-String -Pattern 'YUCL|openjfx'"
echo Done. You can close this window.
pause
