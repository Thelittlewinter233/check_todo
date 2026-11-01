package test;

import com.checktodo.app.domain.AiHttpDiagnostics;
import com.checktodo.app.domain.AiVerifyConfig;
import com.checktodo.app.domain.GlmRequest;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * AI 判定打卡「HTTP 404」根因回归测试。
 *
 * <p><b>真机实证的根因</b>：设置对话框里 base_url 实际存的是完整端点
 * {@code https://open.bigmodel.cn/api/paas/v4/chat/completions}（把文档里的完整地址
 * 粘进 base_url 是最自然的操作），而旧 {@code endpoint()} 无条件追加
 * {@code /chat/completions}，请求打到 {@code …/v4/chat/completions/chat/completions}
 * → 服务端 404。用户没填错——兼容两种填法是 App 的责任。</p>
 *
 * <p>覆盖：</p>
 * <ol>
 *   <li>{@link AiVerifyConfig#endpoint()} 归一化（完整端点 / 尾斜杠 / 大小写 / 默认模板 / 幂等）。</li>
 *   <li>{@link AiHttpDiagnostics#statusWhy} 错误文案带 endpoint + 响应体前 120 字（换行折叠、key 脱敏）。</li>
 *   <li>{@link GlmRequest#buildProbeBody} 最小探针请求体 + {@link AiHttpDiagnostics#modelName}。</li>
 *   <li>壳层源码接线断言（Log 诊断 / 测试连接按钮 / hint / debuggable）。</li>
 * </ol>
 */
public final class AiEndpointNormalizeTest {
    private static final String FULL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final String BASE = "https://open.bigmodel.cn/api/paas/v4";
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testPastedFullEndpoint();
        testTrailingSlashVariants();
        testCaseInsensitiveSuffix();
        testDefaultTemplateUnchanged();
        testIdempotent();
        testStatusWhyDiagnosable();
        testStatusWhyBodySanitized();
        testStatusWhyKeyRedacted();
        testProbeBody();
        testModelName();
        testShellWiringBySource();
        report();
    }

    // ------------------------------------------------------------------
    // 1. endpoint() 归一化
    // ------------------------------------------------------------------

    /** ★根因用例：base_url 直接是完整端点 → 不能重复追加。 */
    private static void testPastedFullEndpoint() {
        AiVerifyConfig config = new AiVerifyConfig(FULL, "glm-4.6v-flash", "k");
        check("★粘贴完整端点 → endpoint 不再重复追加（404 根因）",
                FULL.equals(config.endpoint()));
        check("★粘贴完整端点 ≠ 旧错误 …/chat/completions/chat/completions",
                !config.endpoint().endsWith("/chat/completions/chat/completions"));
        check("endpoint 中 chat/completions 恰好出现一次",
                countOccurrences(config.endpoint(), "chat/completions") == 1);
    }

    /** 尾斜杠：base 结尾 / 完整端点结尾 / 多重结尾 都收敛。 */
    private static void testTrailingSlashVariants() {
        check("base 无尾斜杠 → base/chat/completions",
                FULL.equals(new AiVerifyConfig(BASE, null, null).endpoint()));
        check("★base 带尾斜杠 → 同样结果",
                FULL.equals(new AiVerifyConfig(BASE + "/", null, null).endpoint()));
        check("★完整端点带尾斜杠 → 同样结果",
                FULL.equals(new AiVerifyConfig(FULL + "/", null, null).endpoint()));
        check("★base 多重尾斜杠 → 同样结果",
                FULL.equals(new AiVerifyConfig(BASE + "///", null, null).endpoint()));
        check("★完整端点 + 尾斜杠 + 首尾空格 → 同样结果",
                FULL.equals(new AiVerifyConfig("  " + FULL + "/  ", null, null).endpoint()));
    }

    /** 大小写：Chat/Completions 之类写法同样剥掉一次。 */
    private static void testCaseInsensitiveSuffix() {
        check("★/CHAT/COMPLETIONS 大写结尾也剥掉",
                "https://x/v4/chat/completions"
                        .equals(new AiVerifyConfig("https://x/v4/CHAT/COMPLETIONS", null, null).endpoint()));
        check("大小写混合 + 尾斜杠",
                "https://x/v4/chat/completions"
                        .equals(new AiVerifyConfig("https://x/v4/Chat/Completions/", null, null).endpoint()));
    }

    /** 默认模板行为不变（零回归）。 */
    private static void testDefaultTemplateUnchanged() {
        check("★默认 endpoint 不变", FULL.equals(AiVerifyConfig.defaults().endpoint()));
        check("空 baseUrl → 默认再拼", FULL.equals(new AiVerifyConfig("", "", "k").endpoint()));
        check("自定义网关 /v1 → /v1/chat/completions",
                "https://example.com/v1/chat/completions"
                        .equals(new AiVerifyConfig("https://example.com/v1/", null, null).endpoint()));
        check("自定义网关完整端点 → 同样 /v1/chat/completions",
                "https://example.com/v1/chat/completions"
                        .equals(new AiVerifyConfig("https://example.com/v1/chat/completions", null, null)
                                .endpoint()));
        check("http 自建代理照样拼",
                "http://127.0.0.1:8080/v1/chat/completions"
                        .equals(new AiVerifyConfig("http://127.0.0.1:8080/v1/chat/completions", null, null)
                                .endpoint()));
    }

    /** 幂等：把 endpoint() 结果再存回 base_url，结果不变（反复编辑不漂移）。 */
    private static void testIdempotent() {
        AiVerifyConfig first = new AiVerifyConfig(FULL, null, null);
        AiVerifyConfig second = first.withBaseUrl(first.endpoint());
        check("★endpoint() 幂等（结果再当 base_url 不变）", first.endpoint().equals(second.endpoint()));
        check("有效 baseUrl 原样保留（不偷偷改写持久化值）", FULL.equals(first.baseUrl));
        AiVerifyConfig fromBase = new AiVerifyConfig(BASE + "/", null, null);
        check("★base 形态与完整端点形态收敛到同一 endpoint",
                fromBase.endpoint().equals(new AiVerifyConfig(FULL, null, null).endpoint()));
    }

    // ------------------------------------------------------------------
    // 2. statusWhy 可诊断
    // ------------------------------------------------------------------

    private static void testStatusWhyDiagnosable() {
        String why = AiHttpDiagnostics.statusWhy(404,
                "{\"error\":{\"code\":\"4xx\",\"message\":\"No route\"}}", FULL, "secret-key-123");
        check("★404 文案含 HTTP 404", why.contains("404"));
        check("★404 文案含最终 endpoint", why.contains(FULL));
        check("★404 文案含响应体片段", why.contains("No route"));
        check("文案带 endpoint 标注（便于一眼看出路径错）", why.contains("endpoint:"));
        check("endpoint 为 null 也不炸", AiHttpDiagnostics.statusWhy(404, "x", null, "k").contains("404"));

        check("401 语义保留（鉴权失败）",
                AiHttpDiagnostics.statusWhy(401, "", FULL, "k").contains("鉴权失败"));
        check("401 仍带 endpoint", AiHttpDiagnostics.statusWhy(401, "", FULL, "k").contains(FULL));
        check("403 语义保留（鉴权失败）",
                AiHttpDiagnostics.statusWhy(403, "", FULL, "k").contains("鉴权失败"));
        check("429 语义保留（额度/频率）",
                AiHttpDiagnostics.statusWhy(429, "", FULL, "k").contains("额度"));
        check("402 语义保留（余额不足）",
                AiHttpDiagnostics.statusWhy(402, "", FULL, "k").contains("余额"));
        check("5xx 语义保留（服务不可用）",
                AiHttpDiagnostics.statusWhy(503, "", FULL, "k").contains("不可用"));
        check("成功码不会走到这里但语义有兜底",
                AiHttpDiagnostics.statusWhy(418, "", FULL, "k").contains("418"));
    }

    private static void testStatusWhyBodySanitized() {
        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 300; i++) longBody.append('x');
        String why = AiHttpDiagnostics.statusWhy(400, longBody.toString(), FULL, "k");
        int xCount = countOccurrences(why, "x");
        check("★超长响应体截断到 120 字内", xCount > 0 && xCount <= 120);
        check("截断有省略号提示", why.contains("…"));

        String folded = AiHttpDiagnostics.statusWhy(400, "第一行\n第二行\r\n第三行", FULL, "k");
        check("★响应体换行折叠（单行展示）", !folded.contains("\n") && !folded.contains("\r"));
        check("折叠后内容仍在", folded.contains("第一行") && folded.contains("第三行"));

        String blank = AiHttpDiagnostics.statusWhy(400, "   ", FULL, "k");
        check("空白响应体不加空片段", !blank.contains("响应体"));
        check("null 响应体不炸", AiHttpDiagnostics.statusWhy(400, null, FULL, "k").contains("400"));
    }

    /** 响应体可能回显 Authorization / key 片段——文案里必须抹掉。 */
    private static void testStatusWhyKeyRedacted() {
        String echo = "{\"error\":\"invalid bearer secret-key-123\"}";
        String why = AiHttpDiagnostics.statusWhy(401, echo, FULL, "secret-key-123");
        check("★响应体回显的 key 被抹掉", !why.contains("secret-key-123"));
        check("抹掉后文案仍可读（留占位）", why.contains("***"));
        check("空 key 不影响脱敏逻辑",
                AiHttpDiagnostics.statusWhy(500, echo, FULL, "").contains("500"));
        check("空 key 时不误伤（响应体原样摘录）",
                AiHttpDiagnostics.statusWhy(500, echo, FULL, "").contains("secret-key-123"));
        check("null key 不炸", AiHttpDiagnostics.statusWhy(500, echo, FULL, null).contains("500"));
    }

    // ------------------------------------------------------------------
    // 3. 「测试连接」探针
    // ------------------------------------------------------------------

    private static void testProbeBody() {
        AiVerifyConfig config = new AiVerifyConfig(FULL, "glm-custom", "k");
        String body = GlmRequest.buildProbeBody(config);
        check("★探针请求体含当前 model", body.contains("\"glm-custom\""));
        check("★探针限制 max_tokens=1（最省额度）", body.contains("\"max_tokens\":1"));
        check("探针含 messages", body.contains("\"messages\""));
        check("★探针是合法 JSON（可回读）", isJsonObject(body));
        check("★探针不含 api key", !body.contains("\"k\""));
        check("探针不含图片（不是判定请求）", !body.contains("image_url"));
        check("空 model → 回落默认", GlmRequest.buildProbeBody(new AiVerifyConfig(FULL, "", "k"))
                .contains(AiVerifyConfig.DEFAULT_MODEL));
        check("null config 不炸", GlmRequest.buildProbeBody(null).contains("max_tokens"));
    }

    private static void testModelName() {
        String response = "{\"id\":\"x\",\"model\":\"glm-4.6v-flash\",\"choices\":[{\"message\":"
                + "{\"role\":\"assistant\",\"content\":\"pong\"},\"finish_reason\":\"stop\"}]}";
        check("★从响应体取 model 名", "glm-4.6v-flash".equals(AiHttpDiagnostics.modelName(response)));
        check("缺 model 字段 → null", AiHttpDiagnostics.modelName("{\"choices\":[]}") == null);
        check("非 JSON → null", AiHttpDiagnostics.modelName("<html>404</html>") == null);
        check("null → null", AiHttpDiagnostics.modelName(null) == null);
        check("空串 → null", AiHttpDiagnostics.modelName("  ") == null);
    }

    // ------------------------------------------------------------------
    // 4. 壳层接线源码断言（JVM 不可执行，钉死形态防回退）
    // ------------------------------------------------------------------

    private static void testShellWiringBySource() {
        String http = readSource("src/com/checktodo/app/ai/GlmHttp.java");
        check("★GlmHttp 诊断 TAG = CheckTodoAi", http.contains("TAG = \"CheckTodoAi\""));
        check("★GlmHttp 记录 endpoint 与状态码（Log.i）", http.contains("Log.i(TAG"));
        check("★statusWhy 带 endpoint 参数（调用链改为可诊断版本）",
                http.contains("AiHttpDiagnostics.statusWhy(status, text, endpoint, config.apiKey)"));
        check("★日志不打印 Authorization / key",
                !http.contains("Log.i(TAG, " + quote("Authorization"))
                        && !http.contains("Log.i(TAG, " + quote("Bearer")));
        check("诊断文案用域层实现（单一实现源）",
                http.contains("AiHttpDiagnostics.statusWhy(status, text, endpoint, config.apiKey)"));
        check("★探针提交入口在位", http.contains("buildProbeBody"));

        String main = readSource("src/com/checktodo/app/MainActivity.java");
        check("★AI 设置 hint 说明可粘贴完整地址",
                main.contains("填到 /v4") || main.contains("完整 chat/completions"));
        check("★设置对话框有「测试连接」按钮", main.contains("测试连接"));
        check("★测试连接期间按钮置灰防重复点击",
                main.contains("setEnabled(false)") && main.contains("setEnabled(true)"));
        check("★测试连接独立方法（后台线程 + 回 UI 线程）",
                main.contains("testAiConnection(") && main.contains("GlmHttp.requestProbe("));

        String manifest = readSource("AndroidManifest.xml");
        check("★debug 包开启 debuggable（run-as 排查）",
                manifest.contains("android:debuggable=\"true\""));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static boolean isJsonObject(String body) {
        return com.checktodo.app.domain.AiVerdictParser.extractJsonObject(body) != null;
    }

    /** 双引号包裹（避免在本文件里写出难读的引号转义）。 */
    private static String quote(String text) {
        return new String(new char[]{'"'}) + text + new String(new char[]{'"'});
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String readSource(String path) {
        try {
            InputStream stream = new FileInputStream(new File(path));
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int count;
                while ((count = stream.read(chunk)) >= 0) buffer.write(chunk, 0, count);
                return new String(buffer.toByteArray(), "UTF-8");
            } finally {
                stream.close();
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("读取源码失败：" + path, exception);
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
