@echo off
set ADB=%USERPROFILE%\platform-tools\adb.exe
if not exist "%ADB%" set ADB=adb
cd /d "%USERPROFILE%\Desktop\origin_dump"
"%ADB%" pull /data/preload/com.xtc.originwidget/xtc.apk xtc.apk
"%ADB%" pull /system/custom/app/WidgetCalendar/WidgetCalendar.apk WidgetCalendar.apk
"%ADB%" pull /system/app/BBKLauncher2/BBKLauncher2.apk launcher.apk
"%ADB%" pull /system/app/SuperCard/SuperCard.apk SuperCard.apk
"%ADB%" shell dumpsys package com.xtc.originwidget > pkg_xtc.txt
"%ADB%" shell dumpsys package com.vivo.widget.calendar > pkg_calendar.txt
"%ADB%" shell dumpsys package com.bbk.launcher2 > pkg_launcher.txt
dir /b > files.txt
echo.
echo Gotovo
pause
