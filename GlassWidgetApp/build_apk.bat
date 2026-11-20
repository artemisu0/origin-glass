@echo off
setlocal
cd /d "%~dp0"
set "JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "GRADLE_BAT=C:\Users\Art\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat"
if exist "%GRADLE_BAT%" (call "%GRADLE_BAT%" assembleDebug) else (call gradle assembleDebug)
if %ERRORLEVEL% equ 0 (
    echo.
    echo BUILD SUCCESSFUL: app\build\outputs\apk\debug\app-debug.apk
) else (
    echo.
    echo [!] Build failed: %ERRORLEVEL%
)
pause
