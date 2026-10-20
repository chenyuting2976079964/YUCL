@echo off
rem YUCL: add D:\YUCL to Windows Defender exclusions (self-elevates to admin)
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting administrator...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
echo Adding D:\YUCL to Windows Defender exclusions...
powershell -NoProfile -Command "Add-MpPreference -ExclusionPath 'D:\YUCL'; Add-MpPreference -ExclusionProcess 'YUCL.exe'; Write-Host 'OK'; (Get-MpPreference).ExclusionPath | Select-String YUCL"
echo Done. You can close this window.
pause
