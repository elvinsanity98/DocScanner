@echo off
rem Builds the signed release APK of BongScanner.
rem Output: BongScanner.apk next to this script.

setlocal
set "JAVA_HOME=%USERPROFILE%\.jdks\jdk-17.0.20.1+1"
rem JDK 17 on Windows cannot open its internal socket when the temp folder path contains spaces.
if not exist "C:\Users\Public\bongscanner-tmp" mkdir "C:\Users\Public\bongscanner-tmp"
set "JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Users\Public\bongscanner-tmp"

cd /d "%~dp0"
call "%~dp0gradlew.bat" :app:assembleRelease --console=plain
if errorlevel 1 exit /b 1
copy /y "%~dp0app\build\outputs\apk\release\app-release.apk" "%~dp0BongScanner.apk" >nul
echo.
echo Done: %~dp0BongScanner.apk
endlocal
