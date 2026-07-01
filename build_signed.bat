@echo off
set LIANYU_STORE_PASSWORD=3498762309
set LIANYU_KEY_ALIAS=your_alias
set LIANYU_KEY_PASSWORD=3498762309
call gradlew.bat assembleRelease -x lintVitalAnalyzeRelease
echo.
echo APK: app\build\outputs\apk\release\app-release.apk
