@echo off
REM 下载「C 方案模拟真机测试」所需的仅测试 JS 引擎（Nashorn standalone + ASM）。
REM 这些 jar 只在 JVM 测试里执行注入脚本用，绝不进 APK（零第三方库红线约束的是 APK 依赖）。
REM 可随时删除本目录重下；SHA1 全部校验。
setlocal
set HERE=%~dp0
set LIB=%HERE%..\tools\test-lib
set BASE=https://repo1.maven.org/maven2

if not exist "%LIB%" mkdir "%LIB%"

set JOBS=%TEMP%\prepare-test-libs-jobs.txt
> "%JOBS%" (
  echo org/openjdk/nashorn/nashorn-core/15.4/nashorn-core-15.4.jar
  echo org/ow2/asm/asm/9.6/asm-9.6.jar
  echo org/ow2/asm/asm-tree/9.6/asm-tree-9.6.jar
  echo org/ow2/asm/asm-commons/9.6/asm-commons-9.6.jar
  echo org/ow2/asm/asm-util/9.6/asm-util-9.6.jar
)

for /f "usebackq delims=" %%S in ("%JOBS%") do call :fetch "%%S"
if errorlevel 1 goto :error

echo Verify SHA1...
for /f "usebackq delims=" %%S in ("%JOBS%") do call :verify "%%S"
if errorlevel 1 goto :error

del "%JOBS%"
echo Test libs ready: %LIB%
exit /b 0

:fetch
set SPEC=%~1
for %%F in ("%SPEC%") do set NAME=%%~nxF
if exist "%LIB%\%NAME%.ok" (
  echo skip %NAME% ^(already verified^)
  exit /b 0
)
echo Downloading %SPEC%
curl -sL --max-time 300 -o "%LIB%\%NAME%" "%BASE%/%SPEC%" || exit /b 1
curl -sL --max-time 60 -o "%LIB%\%NAME%.sha1" "%BASE%/%SPEC%.sha1" || exit /b 1
exit /b 0

:verify
set SPEC=%~1
for %%F in ("%SPEC%") do set NAME=%%~nxF
certutil -hashfile "%LIB%\%NAME%" SHA1 | findstr /r "^[0-9a-fA-F]*$" > "%LIB%\%NAME%.actual" 2>nul
REM certutil 输出格式不定，改用 PowerShell 校验（Windows 自带）
for /f %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA1 '%LIB%\%NAME%').Hash.ToLower()"') do set ACTUAL=%%H
set /p EXPECTED=<"%LIB%\%NAME%.sha1"
if "%ACTUAL%" NEQ "%EXPECTED%" (
  echo SHA1 MISMATCH: %NAME%
  echo   expected %EXPECTED%
  echo   actual   %ACTUAL%
  exit /b 1
)
echo ok %NAME%
type nul > "%LIB%\%NAME%.ok"
exit /b 0

:error
echo prepare-test-libs failed.
exit /b 1
