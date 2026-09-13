@echo off
set YUNIAN_STORE_PASSWORD=3498762309
set YUNIAN_KEY_ALIAS=your_alias
set YUNIAN_KEY_PASSWORD=3498762309
call gradlew.bat assembleRelease -x lintVitalAnalyzeRelease
echo.
echo APK: app\build\outputs\apk\release\app-release.apk
