@echo off
chcp 65001 >nul
set ADB=%USERPROFILE%\platform-tools\adb.exe
if not exist "%ADB%" set ADB=adb
set OUT=%USERPROFILE%\Desktop\origin_dump
mkdir "%OUT%" 2>nul
cd /d "%OUT%"
echo Otkroy na telefone ekran "Komplekty Origin" i nazhmi lyubuyu klavishu...
pause >nul
"%ADB%" devices > devices.txt
"%ADB%" shell dumpsys window | findstr /i "mCurrentFocus mFocusedApp" > top.txt
"%ADB%" shell dumpsys activity activities | findstr /i "ResumedActivity" >> top.txt
"%ADB%" shell dumpsys appwidget > widgets.txt
"%ADB%" shell pm list packages -f > packages.txt
"%ADB%" shell getprop ro.build.version.release > build.txt
"%ADB%" shell getprop ro.vivo.os.build.display.id >> build.txt
for /f "tokens=2 delims=:" %%a in ('"%ADB%" shell pm path com.bbk.launcher2 ^| findstr base') do "%ADB%" pull %%a launcher.apk
echo.
echo Gotovo. Papka: %OUT%
pause
