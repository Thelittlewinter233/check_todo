@echo off
setlocal
set REPO_ROOT=%~dp0..
set TOOLS_DIR=%REPO_ROOT%\tools\sdk
set DOWNLOAD_DIR=%TOOLS_DIR%\downloads
set BUILD_TOOLS_DIR=%TOOLS_DIR%\build-tools\35.0.1
set PLATFORM_DIR=%TOOLS_DIR%\platforms\android-34

if not exist "%DOWNLOAD_DIR%" mkdir "%DOWNLOAD_DIR%"
if not exist "%BUILD_TOOLS_DIR%" mkdir "%BUILD_TOOLS_DIR%"
if not exist "%PLATFORM_DIR%" mkdir "%PLATFORM_DIR%"

set BUILD_TOOLS_ZIP=%DOWNLOAD_DIR%\build-tools_r35.0.1_windows.zip
set PLATFORM_ZIP=%DOWNLOAD_DIR%\platform-34-ext7_r03.zip

if not exist "%BUILD_TOOLS_ZIP%" powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/build-tools_r35.0.1_windows.zip' -OutFile '%BUILD_TOOLS_ZIP%' -UseBasicParsing"
if errorlevel 1 goto :error

if not exist "%PLATFORM_ZIP%" powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-34-ext7_r03.zip' -OutFile '%PLATFORM_ZIP%' -UseBasicParsing"
if errorlevel 1 goto :error

set EXPECTED_BUILD_TOOLS_SHA1=1cbfa5564b62504a1111352edda35914cf723a20
set EXPECTED_PLATFORM_SHA1=1f2e9478d6a7601425ceaa553311dc43191f103d
for /f "delims=" %%A in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA1 '%BUILD_TOOLS_ZIP%').Hash.ToLower()"') do set ACTUAL_BUILD_TOOLS_SHA1=%%A
for /f "delims=" %%A in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA1 '%PLATFORM_ZIP%').Hash.ToLower()"') do set ACTUAL_PLATFORM_SHA1=%%A
if /i not "%ACTUAL_BUILD_TOOLS_SHA1%"=="%EXPECTED_BUILD_TOOLS_SHA1%" (
  echo Build-tools SHA1 mismatch. Expected %EXPECTED_BUILD_TOOLS_SHA1%, got %ACTUAL_BUILD_TOOLS_SHA1%.
  exit /b 1
)
if /i not "%ACTUAL_PLATFORM_SHA1%"=="%EXPECTED_PLATFORM_SHA1%" (
  echo Platform SHA1 mismatch. Expected %EXPECTED_PLATFORM_SHA1%, got %ACTUAL_PLATFORM_SHA1%.
  exit /b 1
)

if not exist "%BUILD_TOOLS_DIR%\android-15\aapt2.exe" powershell -NoProfile -Command "Expand-Archive -Path '%BUILD_TOOLS_ZIP%' -DestinationPath '%BUILD_TOOLS_DIR%' -Force"
if errorlevel 1 goto :error
if not exist "%PLATFORM_DIR%\android-34\android.jar" powershell -NoProfile -Command "Expand-Archive -Path '%PLATFORM_ZIP%' -DestinationPath '%PLATFORM_DIR%' -Force"
if errorlevel 1 goto :error

echo Android build tools and platform 34 ready.
exit /b 0

:error
echo Failed to prepare Android build tools.
exit /b 1
