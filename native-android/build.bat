@echo off
setlocal enabledelayedexpansion
set ROOT=%~dp0
set REPO_ROOT=%ROOT%..
set TOOLS_DIR=%REPO_ROOT%\tools\sdk
set BUILD_TOOLS=%TOOLS_DIR%\build-tools\35.0.1\android-15
set PLATFORM_JAR=%TOOLS_DIR%\platforms\android-34\android-34\android.jar
set TEST_LIBS=%REPO_ROOT%\tools\test-lib
set NASHORN_JAR=%TEST_LIBS%\nashorn-core-15.4.jar
set AAPT2=%BUILD_TOOLS%\aapt2.exe
set D8=%BUILD_TOOLS%\d8.bat
set ZIPALIGN=%BUILD_TOOLS%\zipalign.exe
set APKSIGNER=%BUILD_TOOLS%\apksigner.bat

if not exist "%AAPT2%" (
  echo Missing aapt2. Run native-android\prepare.bat first.
  exit /b 1
)
if not exist "%PLATFORM_JAR%" (
  echo Missing android.jar. Run native-android\prepare.bat first.
  exit /b 1
)
if not exist "%NASHORN_JAR%" (
  echo Missing test-only JS engine libs. Run native-android\prepare-test-libs.bat first.
  exit /b 1
)

set BUILD_DIR=%ROOT%build
set OUT_DIR=%ROOT%out
set GEN_DIR=%BUILD_DIR%\gen
set CLASSES_DIR=%BUILD_DIR%\classes
set DEX_DIR=%BUILD_DIR%\dex
set RES_ZIP=%BUILD_DIR%\res.zip
set UNSIGNED_APK=%BUILD_DIR%\unsigned.apk
set ALIGNED_APK=%BUILD_DIR%\aligned.apk
set FINAL_APK=%OUT_DIR%\checktodo-debug.apk
set KEYSTORE=%ROOT%debug.keystore

if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
if exist "%OUT_DIR%" rmdir /s /q "%OUT_DIR%"
mkdir "%BUILD_DIR%" || goto :error
mkdir "%GEN_DIR%" || goto :error
mkdir "%CLASSES_DIR%" || goto :error
mkdir "%DEX_DIR%" || goto :error
mkdir "%OUT_DIR%" || goto :error

echo [1/7] aapt2 compile
"%AAPT2%" compile --dir "%ROOT%res" -o "%RES_ZIP%"
if errorlevel 1 goto :error

echo [2/7] aapt2 link
"%AAPT2%" link -o "%UNSIGNED_APK%" -I "%PLATFORM_JAR%" --manifest "%ROOT%AndroidManifest.xml" -R "%RES_ZIP%" --java "%GEN_DIR%" --auto-add-overlay
if errorlevel 1 goto :error

echo [3/7] javac --release 11 -Xlint:all
rem Source list goes into a javac @argfile: one-line %SOURCES% expansion exceeds the cmd 8191-char limit.
set SOURCES_FILE=%BUILD_DIR%\sources.txt
if exist "%SOURCES_FILE%" del "%SOURCES_FILE%"
for /r "%ROOT%src" %%F in (*.java) do echo %%F>>"%SOURCES_FILE%"
javac --release 11 -Xlint:all -encoding UTF-8 -classpath "%PLATFORM_JAR%;%GEN_DIR%" -d "%CLASSES_DIR%" @"%SOURCES_FILE%"
if errorlevel 1 goto :error

echo [4/7] prepare shim test classes
set JSHIM_SOURCES=
for /r "%ROOT%src\com\checktodo\app\jshim" %%F in (*.java) do set JSHIM_SOURCES=!JSHIM_SOURCES! "%%F"
set WAKEUP_SOURCES=
for /r "%ROOT%src\com\checktodo\app\wakeup_parser" %%F in (*.java) do set WAKEUP_SOURCES=!WAKEUP_SOURCES! "%%F"

echo [4/7] pure Java domain tests
if exist "%BUILD_DIR%\test" rmdir /s /q "%BUILD_DIR%\test"
mkdir "%BUILD_DIR%\test"
javac --release 11 -Xlint:all -encoding UTF-8 -d "%BUILD_DIR%\test" %JSHIM_SOURCES% %WAKEUP_SOURCES% "%ROOT%src\com\checktodo\app\domain\Task.java" "%ROOT%src\com\checktodo\app\domain\TaskStatus.java" "%ROOT%src\com\checktodo\app\domain\TaskLogic.java" "%ROOT%src\com\checktodo\app\domain\TaskStats.java" "%ROOT%src\com\checktodo\app\domain\ThemeMode.java" "%ROOT%src\com\checktodo\app\domain\Todo.java" "%ROOT%src\com\checktodo\app\domain\TodoLogic.java" "%ROOT%src\com\checktodo\app\domain\TodoStatus.java" "%ROOT%src\com\checktodo\app\domain\ReminderLogic.java" "%ROOT%src\com\checktodo\app\domain\CourseBase.java" "%ROOT%src\com\checktodo\app\domain\CourseDetail.java" "%ROOT%src\com\checktodo\app\domain\TimeNode.java" "%ROOT%src\com\checktodo\app\domain\TimetableConfig.java" "%ROOT%src\com\checktodo\app\domain\TimetableLogic.java" "%ROOT%src\com\checktodo\app\domain\TimetableCsvParser.java" "%ROOT%src\com\checktodo\app\domain\SimpleHtmlParser.java" "%ROOT%src\com\checktodo\app\domain\HtmlTimetableParser.java" "%ROOT%src\com\checktodo\app\domain\OnlineImportLogic.java" "%ROOT%src\com\checktodo\app\domain\OnlineExtraction.java" "%ROOT%src\com\checktodo\app\domain\OneShotGate.java" "%ROOT%src\com\checktodo\app\domain\WidgetRefreshLogic.java" "%ROOT%src\com\checktodo\app\domain\OnlineDivParser.java" "%ROOT%src\com\checktodo\app\domain\GeometryGridSolver.java" "%ROOT%src\com\checktodo\app\domain\WeekExpression.java" "%ROOT%src\com\checktodo\app\domain\KingoReportParser.java" "%ROOT%src\com\checktodo\app\domain\TimeTableSettings.java" "%ROOT%src\com\checktodo\app\domain\TimetableSheet.java" "%ROOT%src\com\checktodo\app\domain\TimetableSheets.java" "%ROOT%src\com\checktodo\app\widget\CourseWidgetItems.java" "%ROOT%src\com\checktodo\app\widget\WidgetModes.java" "%ROOT%src\com\checktodo\app\widget\WidgetSwitchLogic.java" "%ROOT%src\com\checktodo\app\domain\TimetableEditor.java" "%ROOT%src\com\checktodo\app\widget\WidgetModeStore.java" "%ROOT%src\com\checktodo\app\domain\PassiveCapture.java" "%ROOT%src\com\checktodo\app\domain\ExtractionState.java" "%ROOT%src\com\checktodo\app\domain\TimetableParserRegistry.java" "%ROOT%src\com\checktodo\app\domain\NewZhengFangParser.java" "%ROOT%src\com\checktodo\app\domain\HtmlGridParser.java" "%ROOT%src\com\checktodo\app\domain\KingoGridParser.java" "%ROOT%src\com\checktodo\app\domain\DedicatedParserPlugin.java" "%ROOT%src\com\checktodo\app\domain\PassiveCapturePolicy.java" "%ROOT%src\com\checktodo\app\domain\ScauGridParser.java" "%ROOT%src\com\checktodo\app\domain\ScauGridPlugin.java" "%ROOT%src\com\checktodo\app\domain\TimeTableDraft.java" "%ROOT%src\com\checktodo\app\domain\FrameDump.java" "%ROOT%src\com\checktodo\app\domain\VerifyMode.java" "%ROOT%src\com\checktodo\app\domain\ImageHash.java" "%ROOT%src\com\checktodo\app\domain\ExifFreshness.java" "%ROOT%src\com\checktodo\app\domain\AiVerdict.java" "%ROOT%src\com\checktodo\app\domain\AiVerifyRecord.java" "%ROOT%src\com\checktodo\app\domain\PhotoChannel.java" "%ROOT%src\com\checktodo\app\domain\VerificationPolicy.java" "%ROOT%src\com\checktodo\app\domain\AiVerdictParser.java" "%ROOT%src\com\checktodo\app\domain\AiVerifyConfig.java" "%ROOT%src\com\checktodo\app\domain\ModelChain.java" "%ROOT%src\com\checktodo\app\domain\AiHttpDiagnostics.java" "%ROOT%src\com\checktodo\app\domain\AiPromptBuilder.java" "%ROOT%src\com\checktodo\app\domain\GlmRequest.java" "%ROOT%src\com\checktodo\app\domain\WeeklyReport.java" "%ROOT%src\com\checktodo\app\widget\WidgetCheckInRouting.java" "%ROOT%src\com\checktodo\app\widget\WidgetFactorySnapshot.java"
if errorlevel 1 goto :error
javac --release 11 -Xlint:all -encoding UTF-8 -classpath "%BUILD_DIR%\test" -d "%BUILD_DIR%\test" "%ROOT%test\WidgetSwitchStaleTest.java" "%ROOT%test\TimetableEditRegressionTest.java" "%ROOT%test\StreakTest.java" "%ROOT%test\TodoTest.java" "%ROOT%test\LeaveTest.java" "%ROOT%test\ThemeModeTest.java" "%ROOT%test\TaskStatsTest.java" "%ROOT%test\TaskOrderTest.java" "%ROOT%test\CancelTest.java" "%ROOT%test\ReminderTest.java" "%ROOT%test\TimetableTest.java" "%ROOT%test\TimeTableSettingsTest.java" "%ROOT%test\HtmlTimetableParserTest.java" "%ROOT%test\KingoReportParserTest.java" "%ROOT%test\GeometryGridSolverTest.java" "%ROOT%test\OnlineImportLogicTest.java" "%ROOT%test\JShimTest.java" "%ROOT%test\WakeupParserTest.java" "%ROOT%test\TimetableSheetsTest.java" "%ROOT%test\WidgetModeTest.java" "%ROOT%test\RealCaseImportTest.java" "%ROOT%test\PassiveCaptureTest.java" "%ROOT%test\PassiveCapturePolicyTest.java" "%ROOT%test\ScauGridParserTest.java" "%ROOT%test\TimeTableDraftTest.java" "%ROOT%test\TimeTableSaveRegressionTest.java" "%ROOT%test\ScauScheduleDefaultsTest.java" "%ROOT%test\FrameDumpTest.java" "%ROOT%test\WidgetFactorySnapshotTest.java" "%ROOT%test\FrameDiscoveryRegressionTest.java" "%ROOT%test\FramePickerThreadingRegressionTest.java" "%ROOT%test\ScriptEngineSimulationTest.java" "%ROOT%test\UrgencyOrderRefreshTest.java" "%ROOT%test\AiEndpointNormalizeTest.java" "%ROOT%test\TodoAiMirrorTest.java" "%ROOT%test\AiVerifyCoreTest.java" "%ROOT%test\AiVerifyProtocolTest.java" "%ROOT%test\ModelChainTest.java" "%ROOT%test\WeeklyReportTest.java" "%ROOT%test\PhotoChannelTest.java" "%ROOT%test\WidgetAiRoutingTest.java"
if errorlevel 1 goto :error
javac --release 11 -Xlint:all -encoding UTF-8 -classpath "%BUILD_DIR%\test" -d "%BUILD_DIR%\test" "%ROOT%src\com\checktodo\app\widget\WidgetFactoryState.java" "%ROOT%test\WidgetEmptyStateTest.java"
if errorlevel 1 goto :error
java -cp "%BUILD_DIR%\test" StreakTest
if errorlevel 1 goto :error
java -cp "%BUILD_DIR%\test" TodoTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" WidgetModeTest
if errorlevel 1 goto :error
java -cp "%BUILD_DIR%\test" LeaveTest
if errorlevel 1 goto :error
java -cp "%BUILD_DIR%\test" ThemeModeTest
if errorlevel 1 goto :error
java -cp "%BUILD_DIR%\test" TaskStatsTest
if errorlevel 1 goto :error
java -cp "%BUILD_DIR%\test" TaskOrderTest
if errorlevel 1 goto :error
java -cp "%BUILD_DIR%\test" CancelTest
if errorlevel 1 goto :error
java -cp "%BUILD_DIR%\test" ReminderTest
if errorlevel 1 goto :error
java -cp "%BUILD_DIR%\test" test.TimetableTest
if errorlevel 1 goto :error
java -cp "%BUILD_DIR%\test" test.TimeTableSettingsTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.HtmlTimetableParserTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.KingoReportParserTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.GeometryGridSolverTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.OnlineImportLogicTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.JShimTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.WakeupParserTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.TimetableSheetsTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" WidgetEmptyStateTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.RealCaseImportTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.PassiveCaptureTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.PassiveCapturePolicyTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.ScauGridParserTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.TimeTableDraftTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.TimeTableSaveRegressionTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.ScauScheduleDefaultsTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.WidgetFactorySnapshotTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.FrameDumpTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.FrameDiscoveryRegressionTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.FramePickerThreadingRegressionTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test;%TEST_LIBS%\*" test.ScriptEngineSimulationTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.UrgencyOrderRefreshTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.AiEndpointNormalizeTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.TodoAiMirrorTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" WidgetSwitchStaleTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.TimetableEditRegressionTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.AiVerifyCoreTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.AiVerifyProtocolTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.ModelChainTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.WeeklyReportTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.PhotoChannelTest
if errorlevel 1 goto :error
java -Dfile.encoding=UTF-8 -cp "%BUILD_DIR%\test" test.WidgetAiRoutingTest
if errorlevel 1 goto :error

echo [5/7] d8 dex
jar cf "%BUILD_DIR%\classes.jar" -C "%CLASSES_DIR%" .
if errorlevel 1 goto :error
call "%D8%" --release --min-api 24 --lib "%PLATFORM_JAR%" --output "%DEX_DIR%" "%BUILD_DIR%\classes.jar"
if errorlevel 1 goto :error

echo [6/7] package and align
jar uf "%UNSIGNED_APK%" -C "%DEX_DIR%" classes.dex
if errorlevel 1 goto :error
"%ZIPALIGN%" -f 4 "%UNSIGNED_APK%" "%ALIGNED_APK%"
if errorlevel 1 goto :error

if not exist "%KEYSTORE%" (
  echo [7/7] generate debug keystore
  keytool -genkeypair -keystore "%KEYSTORE%" -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
) else (
  echo [7/7] reuse debug keystore
)

echo Sign APK
call "%APKSIGNER%" sign --ks "%KEYSTORE%" --ks-pass pass:android --key-pass pass:android --out "%FINAL_APK%" "%ALIGNED_APK%"
if errorlevel 1 goto :error

echo Verify APK
call "%APKSIGNER%" verify --verbose "%FINAL_APK%"
if errorlevel 1 goto :error

echo Build complete: %FINAL_APK%
exit /b 0

:error
echo Build failed.
exit /b 1
