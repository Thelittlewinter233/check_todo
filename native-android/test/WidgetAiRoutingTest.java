package test;

import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.VerifyMode;
import com.checktodo.app.widget.WidgetCheckInRouting;
import com.checktodo.app.widget.WidgetCheckInRouting.Action;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * AI 拍照判定打卡的<b>壳层接线</b>回归测试。
 *
 * <p>域层判定逻辑已由 {@code AiVerifyCoreTest} / {@code AiVerifyProtocolTest} 覆盖；
 * 本文件覆盖两件事：</p>
 * <ol>
 *   <li><b>小卡片路由决策</b>（{@link WidgetCheckInRouting}，纯函数）：AI 任务必须
 *       「打开 App 走拍照」而不是卡片内直接写库，同时普通任务打卡 / 取消确认两条
 *       既有路径一字不改（零回归）。</li>
 *   <li><b>接线源码断言</b>：相机 Intent 形态、后台线程与回 UI 线程纪律、
 *       BYOK 持久化键、降级路径调用点、Provider 注册——这些无法在 JVM 上执行，
 *       按既有 {@code UrgencyOrderRefreshTest} 的做法钉死源码形态，防回退。</li>
 * </ol>
 */
public final class WidgetAiRoutingTest {
    private static final boolean[] EVERY_DAY =
            new boolean[]{true, true, true, true, true, true, true};
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testPlainTaskActiveChecksInPlace();
        testAiTaskActiveOpensAppForPhoto();
        testDoneOpensCancelConfirmForBothModes();
        testNotCheckableStatesIgnored();
        testNullTaskAndNullDatesIgnored();
        testAiFlagDetection();
        testActiveActionLabel();
        testReferencePhotoHelpers();
        testCameraFlowWiringBySource();
        testDegradationWiringBySource();
        testSettingsPersistenceWiringBySource();
        testWidgetRoutingWiringBySource();
        testManifestProviderWiringBySource();
        report();
    }

    // ------------------------------------------------------------------
    // 1. 路由决策（纯函数）
    // ------------------------------------------------------------------

    /** 普通任务 + 可打卡 → 卡片内直接写库（既有行为，零回归）。 */
    private static void testPlainTaskActiveChecksInPlace() {
        Task task = task("plain", VerifyMode.NONE, "08:00", "22:00");
        Date now = date(2026, 10, 30, 10, 0);
        check("普通任务可打卡 → CHECK_IN（卡片内直接写库）",
                WidgetCheckInRouting.decide(task, empty(), now) == Action.CHECK_IN);
    }

    /** AI 拍照任务 + 可打卡 → 必须打开 App 走拍照流程，绝不卡片内写库。 */
    private static void testAiTaskActiveOpensAppForPhoto() {
        Task task = task("ai", VerifyMode.AI_PHOTO, "08:00", "22:00");
        Date now = date(2026, 10, 30, 10, 0);
        check("AI 任务可打卡 → OPEN_APP_AI_CHECKIN（不在卡片内写库）",
                WidgetCheckInRouting.decide(task, empty(), now) == Action.OPEN_APP_AI_CHECKIN);
        check("AI 任务可打卡 ≠ CHECK_IN（防回退到直接打卡）",
                WidgetCheckInRouting.decide(task, empty(), now) != Action.CHECK_IN);
    }

    /** 已完成 → 两种模式都走「取消今日打卡」确认链路（既有行为）。 */
    private static void testDoneOpensCancelConfirmForBothModes() {
        Date now = date(2026, 10, 30, 10, 0);
        String today = com.checktodo.app.domain.TaskLogic.dateKey(now);
        Set<String> done = new HashSet<>(Collections.singletonList(today));
        check("普通任务已完成 → OPEN_APP_CANCEL",
                WidgetCheckInRouting.decide(task("plain", VerifyMode.NONE, "08:00", "22:00"), done, now)
                        == Action.OPEN_APP_CANCEL);
        check("AI 任务已完成 → OPEN_APP_CANCEL（取消确认与判定模式无关）",
                WidgetCheckInRouting.decide(task("ai", VerifyMode.AI_PHOTO, "08:00", "22:00"), done, now)
                        == Action.OPEN_APP_CANCEL);
    }

    /** 未开始 / 已错过 / 非重复日 → IGNORE（卡片不动作）。 */
    private static void testNotCheckableStatesIgnored() {
        Date now = date(2026, 10, 30, 10, 0);
        check("未开始 → IGNORE",
                WidgetCheckInRouting.decide(task("t", VerifyMode.NONE, "12:00", "13:00"), empty(), now)
                        == Action.IGNORE);
        check("已错过 → IGNORE",
                WidgetCheckInRouting.decide(task("t", VerifyMode.NONE, "06:00", "08:00"), empty(), now)
                        == Action.IGNORE);
        check("AI 任务已错过 → IGNORE（不给 AI 任务开后门）",
                WidgetCheckInRouting.decide(task("t", VerifyMode.AI_PHOTO, "06:00", "08:00"), empty(), now)
                        == Action.IGNORE);
        boolean[] mondayOnly = new boolean[]{true, false, false, false, false, false, false};
        // 2026-10-30 是周五，非周一重复日。
        check("非重复日 → IGNORE",
                WidgetCheckInRouting.decide(new Task("t", "T", mondayOnly, "08:00", "22:00",
                        empty(), 0L, 10), empty(), now) == Action.IGNORE);
    }

    /** 脏入参不崩：null 任务、null 集合、null 时刻。 */
    private static void testNullTaskAndNullDatesIgnored() {
        Date now = date(2026, 10, 30, 10, 0);
        check("null 任务 → IGNORE",
                WidgetCheckInRouting.decide(null, empty(), now) == Action.IGNORE);
        check("null 时刻 → IGNORE",
                WidgetCheckInRouting.decide(task("t", VerifyMode.AI_PHOTO, "08:00", "22:00"), empty(), null)
                        == Action.IGNORE);
        check("null 已完成集合 → 按空集处理（AI 可打卡）",
                WidgetCheckInRouting.decide(task("t", VerifyMode.AI_PHOTO, "08:00", "22:00"), null, now)
                        == Action.OPEN_APP_AI_CHECKIN);
        check("isAiPhoto(null) 为 false", !WidgetCheckInRouting.isAiPhoto((com.checktodo.app.domain.Task) null));
    }

    private static void testAiFlagDetection() {
        check("AI_PHOTO → isAiPhoto true",
                WidgetCheckInRouting.isAiPhoto(task("t", VerifyMode.AI_PHOTO, "08:00", "22:00")));
        check("NONE → isAiPhoto false",
                !WidgetCheckInRouting.isAiPhoto(task("t", VerifyMode.NONE, "08:00", "22:00")));
        // 老数据：无 verifyMode 字段的构造路径默认 NONE。
        check("老构造（无 verifyMode）→ isAiPhoto false",
                !WidgetCheckInRouting.isAiPhoto(new Task("t", "T", EVERY_DAY, "08:00", "22:00", 0L)));
    }

    private static void testActiveActionLabel() {
        check("普通任务动作按钮文案保持「打卡」",
                "打卡".equals(WidgetCheckInRouting.activeActionLabel(task("t", VerifyMode.NONE, "08:00", "22:00"))));
        check("AI 任务动作按钮文案为「拍照打卡」",
                "拍照打卡".equals(WidgetCheckInRouting.activeActionLabel(
                        task("t", VerifyMode.AI_PHOTO, "08:00", "22:00"))));
        check("null 任务回落「打卡」（不崩）",
                "打卡".equals(WidgetCheckInRouting.activeActionLabel(null)));
    }

    /** 标准照存在性判定：编辑器保存与打卡入口都依赖它决定走判定还是降级。 */
    private static void testReferencePhotoHelpers() {
        Task bare = new Task("t", "T", EVERY_DAY, "08:00", "22:00", empty(), 0L, 10,
                VerifyMode.AI_PHOTO, "书桌整洁", "");
        check("未拍标准照 → hasReferencePhoto false", !bare.hasReferencePhoto());
        Task withPhoto = new Task("t", "T", EVERY_DAY, "08:00", "22:00", empty(), 0L, 10,
                VerifyMode.AI_PHOTO, "书桌整洁", "/data/user/0/com.checktodo.app/files/ai_photos/ref.jpg");
        check("已拍标准照 → hasReferencePhoto true", withPhoto.hasReferencePhoto());
        check("空白路径归一为空串 → hasReferencePhoto false",
                !new Task("t", "T", EVERY_DAY, "08:00", "22:00", empty(), 0L, 10,
                        VerifyMode.AI_PHOTO, "x", "   ").hasReferencePhoto());
        check("withVerify 可补标准照（编辑器保存路径）",
                bare.withVerify(VerifyMode.AI_PHOTO, "书桌整洁", "p.jpg").hasReferencePhoto());
        check("withVerify 可关闭 AI 模式",
                !withPhoto.withVerify(VerifyMode.NONE, "", "").isAiPhoto());
    }

    // ------------------------------------------------------------------
    // 2. 接线源码断言（壳层无法 JVM 执行，钉死形态防回退）
    // ------------------------------------------------------------------

    /** 相机链路：系统相机 Intent + EXTRA_OUTPUT 私有目录 + 结果回 UI 线程。 */
    private static void testCameraFlowWiringBySource() {
        String main = readSource("src/com/checktodo/app/MainActivity.java");
        check("★打卡入口用系统相机（ACTION_IMAGE_CAPTURE，无 CameraX）",
                main.contains("MediaStore.ACTION_IMAGE_CAPTURE"));
        check("★照片落 app 私有目录（EXTRA_OUTPUT）",
                main.contains("MediaStore.EXTRA_OUTPUT"));
        check("★相机结果在 onActivityResult 处理",
                main.contains("REQUEST_AI_CHECKIN_PHOTO"));
        check("★AI 任务打卡入口不直接 checkIn（走拍照流程）",
                main.contains("startAiCheckIn("));
        check("★后台线程结果回 UI 线程（runOnUiThread）",
                main.contains("runOnUiThread"));
        check("★L1 预检调用域层纯函数",
                main.contains("VerificationPolicy.precheck("));
        check("★Bitmap→灰度像素矩阵在壳层完成（域层保持纯 Java）",
                main.contains("AiImageShell."));
        String camera = readSource("src/com/checktodo/app/ai/AiPhotoStore.java");
        check("★照片目录在 getFilesDir 下（私有，不需存储权限）",
                camera.contains("getFilesDir"));
        String provider = readSource("src/com/checktodo/app/ai/AiPhotoProvider.java");
        check("★自建 Provider 只服务私有照片目录（规范路径校验，防越权访问）",
                provider.contains("getCanonicalPath") && provider.contains("ai_photos"));
        check("★Provider 支持相机写回（openFile 透传写模式）",
                provider.contains("MODE_WRITE_ONLY") || provider.contains("MODE_READ_WRITE"));
        check("★Provider 授予相机写权限的 flag 由调用方设置",
                main.contains("FLAG_GRANT_WRITE_URI_PERMISSION"));
    }

    /** 降级铁律：任何异常都完成打卡 + 待复核，绝不阻塞、绝不误判 fail。 */
    private static void testDegradationWiringBySource() {
        String main = readSource("src/com/checktodo/app/MainActivity.java");
        check("★未配 key / 网络失败走域层降级路径（不自行发明结论）",
                main.contains("VerificationPolicy.degraded("));
        check("★判定结果映射走 decideOrNull（null → 降级）",
                main.contains("VerificationPolicy.decideOrNull("));
        check("★判定历史经 AiVerifyRecord 持久化",
                main.contains("AiVerifyRecord"));
        check("★网络请求不在 UI 线程（异步执行器）",
                main.contains("GlmHttp.requestAsync("));
        String http = readSource("src/com/checktodo/app/ai/GlmHttp.java");
        check("★HTTP 连接与读取超时 20s",
                http.contains("TIMEOUT_MS = 20_000")
                        && http.contains("int total) throws IOException {\n        return postStatus(config, model, body, index, total, TIMEOUT_MS);")
                        && http.contains("setConnectTimeout(timeoutMs)")
                        && http.contains("setReadTimeout(timeoutMs)"));
        check("★api key 只进 Authorization 头，不进请求体",
                http.contains("\"Authorization\"") && http.contains("Bearer "));
        check("★HTTP 异常不外抛（回 null → 调用方降级）",
                http.contains("catch (Exception") || http.contains("catch (IOException"));
        check("★单线程执行器（不并发打模型，参考 OnlineExtractionSession 纪律）",
                http.contains("newSingleThreadExecutor"));
        String repo = readSource("src/com/checktodo/app/data/TaskRepository.java");
        check("★仓库持久化判定历史（aiRecords 节点）",
                repo.contains("aiRecords") && repo.contains("AiVerifyRecord"));
        check("★仓库写入 verifyMode（新键在 check-todo.v1 内部，向后兼容）",
                repo.contains("\"verifyMode\""));
    }

    /** BYOK 设置：独立持久化键 + 隐私明示。 */
    private static void testSettingsPersistenceWiringBySource() {
        String settings = readSource("src/com/checktodo/app/ai/AiVerifySettings.java");
        check("★BYOK 存独立键 ai-verify.v1", settings.contains("ai-verify.v1"));
        check("★key 只存本机 SharedPreferences（写 prefs.edit()）",
                settings.contains("SharedPreferences") && settings.contains("putString"));
        check("★配置类不触碰任务仓库（key 不随任务数据被备份/导出）",
                !settings.contains("TaskRepository") && !settings.contains("Task task"));
        String main = readSource("src/com/checktodo/app/MainActivity.java");
        check("★设置对话框含隐私明示（照片会上传）",
                main.contains("上传") && main.contains("showAiVerifySettings("));
        check("★设置项入口挂在设置区菜单",
                main.contains("MENU_AI_SETTINGS"));
        check("★配置校验走域层 validate", main.contains(".validate()"));
    }

    /** 小卡片：路由决策接管分支，且世代权威机制未被触碰。 */
    private static void testWidgetRoutingWiringBySource() {
        String widget = readSource("src/com/checktodo/app/widget/CheckTodoWidget.java");
        check("★Provider 用纯函数路由决策（不在卡片内判定模式）",
                widget.contains("WidgetCheckInRouting.decide("));
        check("★AI 任务点击携带专用 extra 打开 App",
                widget.contains("EXTRA_AI_CHECKIN_TASK_ID"));
        check("★普通任务打卡分支保留（零回归）",
                widget.contains("case CHECK_IN") && widget.contains("repository.checkIn(id, now)"));
        check("★已完成取消确认分支保留（零回归）",
                widget.contains("case OPEN_APP_CANCEL") && widget.contains("openAppForCancel(context, id)"));
        check("★世代校验仍在路由之前（accepts）",
                widget.contains("current.accepts(clickedMode)"));
        String service = readSource("src/com/checktodo/app/widget/CheckTodoWidgetService.java");
        check("★卡片条目文案区分拍照打卡",
                service.contains("WidgetCheckInRouting.activeActionLabel("));
        check("★AI 任务行照常显示（itemId 策略未排除 AI 任务）",
                service.contains("status == TaskStatus.ACTIVE || status == TaskStatus.DONE"));
        String main = readSource("src/com/checktodo/app/MainActivity.java");
        check("★App 接收小卡片转来的拍照请求",
                main.contains("EXTRA_AI_CHECKIN_TASK_ID"));
    }

    /** 相机需要 content:// URI（API 24+ 禁 file://），Provider 必须注册。 */
    private static void testManifestProviderWiringBySource() {
        String manifest = readSource("AndroidManifest.xml");
        check("★注册自建照片 Provider（content:// 授予相机写权限）",
                manifest.contains("AiPhotoProvider") && manifest.contains("provider"));
        check("★Provider 不导出（仅本机 + 显式授权）",
                manifest.contains("android:exported=\"false\""));
        check("★INTERNET 权限在位（调模型需要）",
                manifest.contains("android.permission.INTERNET"));
        check("★未声明 CAMERA 权限（走系统相机 App，无需自持相机权限）",
                !manifest.contains("android.permission.CAMERA"));
        check("★grantUriPermissions 打开（相机进程可写回）",
                manifest.contains("grantUriPermissions=\"true\""));
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static Task task(String id, VerifyMode mode, String start, String end) {
        return new Task(id, "任务", EVERY_DAY, start, end, empty(), 0L, 10,
                mode, "特征描述", "ref.jpg");
    }

    private static Set<String> empty() {
        return Collections.emptySet();
    }

    @SuppressWarnings("unused")
    private static Set<String> dates(String... keys) {
        return new HashSet<>(Arrays.asList(keys));
    }

    private static Date date(int year, int month, int day, int hour, int minute) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.set(year, month - 1, day, hour, minute, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    private static String readSource(String path) {
        java.io.InputStream stream = null;
        try {
            stream = new java.io.FileInputStream(new java.io.File(path));
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) >= 0) buffer.write(chunk, 0, count);
            return new String(buffer.toByteArray(), "UTF-8");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("读取源码失败：" + path, exception);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (java.io.IOException ignored) {
                    // ignore
                }
            }
        }
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static void report() {
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
