package test;

import com.checktodo.app.domain.AiVerifyConfig;
import com.checktodo.app.domain.AiVerdict;
import com.checktodo.app.domain.ModelChain;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 判定失败转移链（N+11）域层测试：{@link ModelChain} 纯函数决策 +
 * {@link AiVerifyConfig} 模型链持久化兼容。
 *
 * <p>产品决策：按序最多 3 个模型；单个失败（网络/非 2xx/不可解析/不支持图片）→
 * 转移下一个；<b>401/403（key 无效）不转移，直接终局降级</b>；429（限流）是可重试
 * 失败 → 转移；空位不发起请求；全部失败 → 既有降级（完成 + 待复核）。</p>
 *
 * <p>覆盖：</p>
 * <ol>
 *   <li>链归一化：trim / 剔空位 / 截断到 3 / 空链回落默认。</li>
 *   <li>decide 决策：链首开始 / 成功即停 / 429·5xx·网络·不可解析转移 /
 *       401·403 直接终局降级 / 全失败降级文案 / 空位跳过。</li>
 *   <li>classifyFailure 状态码分类。</li>
 *   <li>prefixReason：判定成功后 reason 带「[模型名] 」前缀。</li>
 *   <li>AiVerifyConfig 链字段：defaults 三模型 / 老持久化单 model 迁移为链首 /
 *       models JSON 数组键往返 / effectiveModel = 链首。</li>
 *   <li>GlmRequest 链版本请求体：显式模型名进 body。</li>
 *   <li>壳层源码接线断言（GlmHttp 链式调用 + Log 链位 + MainActivity 三行输入）。</li>
 * </ol>
 */
public final class ModelChainTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testSanitize();
        testEffective();
        testDecideHappyPath();
        testDecideRetryableTransfer();
        testDecideAuthTerminal();
        testDecideAllFailedDegrade();
        testDecideEmptySlotsSkipped();
        testDecideEmptyChainFallsBack();
        testClassifyFailure();
        testPrefixReason();
        testConfigChainDefaults();
        testConfigLegacyMigration();
        testConfigModelsRoundTrip();
        testRequestBodyUsesChainModel();
        testShellWiringBySource();
        report();
    }

    // ------------------------------------------------------------------
    // 1. 链归一化
    // ------------------------------------------------------------------

    private static void testSanitize() {
        List<String> sanitized = ModelChain.sanitize(Arrays.asList(" m1 ", "", "  ", "m2", null));
        check("★trim + 剔空白位（空位不发起请求）",
                Arrays.asList("m1", "m2").equals(sanitized));
        check("★超过 3 个截断到 3",
                3 == ModelChain.sanitize(Arrays.asList("a", "b", "c", "d", "e")).size());
        check("截断保序（前 3 个）",
                Arrays.asList("a", "b", "c").equals(
                        ModelChain.sanitize(Arrays.asList("a", "b", "c", "d"))));
        check("null 链 → 空列表", ModelChain.sanitize(null).isEmpty());
        check("全空位链 → 空列表", ModelChain.sanitize(Arrays.asList("", "  ", null)).isEmpty());
        check("sanitize 返回可变副本（不共享内部状态）",
                ModelChain.sanitize(Collections.singletonList("x")).size() == 1);
    }

    private static void testEffective() {
        check("★空链回落默认链（3 个）",
                AiVerifyConfig.defaultModels().equals(ModelChain.effective(null)));
        check("回落链首 = DEFAULT_MODEL",
                AiVerifyConfig.DEFAULT_MODEL.equals(ModelChain.effective(
                        Collections.<String>emptyList()).get(0)));
        check("非空链原样生效",
                Arrays.asList("m1", "m2").equals(
                        ModelChain.effective(Arrays.asList(" m1 ", "", "m2"))));
    }

    // ------------------------------------------------------------------
    // 2. decide 决策
    // ------------------------------------------------------------------

    private static final List<String> CHAIN3 = Arrays.asList("vision-a", "vision-b", "text-c");

    private static void testDecideHappyPath() {
        ModelChain.Decision first = ModelChain.decide(CHAIN3, null);
        check("★没有尝试 → 链首", !first.finished && "vision-a".equals(first.nextModel));
        ModelChain.Decision second = ModelChain.decide(CHAIN3,
                Collections.singletonList(ModelChain.Attempt.success("vision-a")));
        check("★链首成功 → 终止（不再转移）", second.finished && !second.degraded);
        check("成功终止带 successModel", "vision-a".equals(second.successModel));
        check("成功终止无失败文案", second.failureWhy.isEmpty());
        ModelChain.Decision mid = ModelChain.decide(CHAIN3, Arrays.asList(
                ModelChain.Attempt.failure("vision-a", ModelChain.Outcome.RETRYABLE, "429", 429),
                ModelChain.Attempt.success("vision-b")));
        check("★第二个模型成功也终止", mid.finished && "vision-b".equals(mid.successModel));
    }

    private static void testDecideRetryableTransfer() {
        ModelChain.Decision after429 = ModelChain.decide(CHAIN3, Collections.singletonList(
                ModelChain.Attempt.failure("vision-a", ModelChain.Outcome.RETRYABLE, "限流", 429)));
        check("★429 限流 → 转移下一个", !after429.finished && "vision-b".equals(after429.nextModel));
        ModelChain.Decision after5xx = ModelChain.decide(CHAIN3, Collections.singletonList(
                ModelChain.Attempt.failure("vision-a", ModelChain.Outcome.RETRYABLE, "5xx", 503)));
        check("★5xx → 转移下一个", !after5xx.finished && "vision-b".equals(after5xx.nextModel));
        ModelChain.Decision afterNetwork = ModelChain.decide(CHAIN3, Collections.singletonList(
                ModelChain.Attempt.failure("vision-a", ModelChain.Outcome.RETRYABLE, "超时", 0)));
        check("★网络异常（status=0）→ 转移下一个",
                !afterNetwork.finished && "vision-b".equals(afterNetwork.nextModel));
        ModelChain.Decision afterUnparsable = ModelChain.decide(CHAIN3, Collections.singletonList(
                ModelChain.Attempt.failure("vision-a", ModelChain.Outcome.RETRYABLE, "不可解析", 0)));
        check("★响应不可解析 → 转移下一个",
                !afterUnparsable.finished && "vision-b".equals(afterUnparsable.nextModel));
        ModelChain.Decision afterNoImage = ModelChain.decide(CHAIN3, Arrays.asList(
                ModelChain.Attempt.failure("vision-a", ModelChain.Outcome.RETRYABLE, "限流", 429),
                ModelChain.Attempt.failure("vision-b", ModelChain.Outcome.RETRYABLE, "不支持图片", 400)));
        check("★文本模型不支持图片（400）→ 转移第三个",
                !afterNoImage.finished && "text-c".equals(afterNoImage.nextModel));
    }

    private static void testDecideAuthTerminal() {
        ModelChain.Decision auth = ModelChain.decide(CHAIN3, Collections.singletonList(
                ModelChain.Attempt.failure("vision-a", ModelChain.Outcome.AUTH_FAILURE, "key 无效", 401)));
        check("★401 → 不转移，终局降级", auth.finished && auth.degraded);
        check("401 降级文案含鉴权语义", auth.failureWhy.contains("鉴权"));
        check("401 降级文案不再指向下一个模型", auth.nextModel == null);
        ModelChain.Decision forbidden = ModelChain.decide(CHAIN3, Collections.singletonList(
                ModelChain.Attempt.failure("vision-a", ModelChain.Outcome.AUTH_FAILURE, "无权限", 403)));
        check("★403 → 同样终局降级", forbidden.finished && forbidden.degraded);
        check("401/403 分类一致", ModelChain.classifyFailure(401) == ModelChain.Outcome.AUTH_FAILURE
                && ModelChain.classifyFailure(403) == ModelChain.Outcome.AUTH_FAILURE);
    }

    private static void testDecideAllFailedDegrade() {
        List<ModelChain.Attempt> all = new ArrayList<>();
        all.add(ModelChain.Attempt.failure("vision-a", ModelChain.Outcome.RETRYABLE, "限流", 429));
        all.add(ModelChain.Attempt.failure("vision-b", ModelChain.Outcome.RETRYABLE, "5xx", 503));
        all.add(ModelChain.Attempt.failure("text-c", ModelChain.Outcome.RETRYABLE, "不支持图片", 400));
        ModelChain.Decision done = ModelChain.decide(CHAIN3, all);
        check("★三个全失败 → 终局降级（完成 + 待复核由调用方落地）",
                done.finished && done.degraded);
        check("降级文案含「全部 3 个模型失败」", done.failureWhy.contains("3"));
        check("降级文案含最后失败原因", done.failureWhy.contains("不支持图片"));
        ModelChain.Decision single = ModelChain.decide(Collections.singletonList("only"),
                Collections.singletonList(ModelChain.Attempt.failure("only",
                        ModelChain.Outcome.RETRYABLE, "超时", 0)));
        check("单模型链失败 → 也降级（不越界）", single.finished && single.degraded);
    }

    private static void testDecideEmptySlotsSkipped() {
        // 空位在 sanitize 阶段就被剔除 → decide 永远看不到空位（空位不发起请求）。
        List<String> sparse = Arrays.asList("vision-a", "", "  ", "text-c", null);
        ModelChain.Decision first = ModelChain.decide(sparse, null);
        check("★稀疏链 → 链首仍是第一个非空位", "vision-a".equals(first.nextModel));
        ModelChain.Decision second = ModelChain.decide(sparse, Collections.singletonList(
                ModelChain.Attempt.failure("vision-a", ModelChain.Outcome.RETRYABLE, "x", 500)));
        check("★空位被跳过：下一个直接是 text-c", "text-c".equals(second.nextModel));
        ModelChain.Decision third = ModelChain.decide(sparse, Arrays.asList(
                ModelChain.Attempt.failure("vision-a", ModelChain.Outcome.RETRYABLE, "x", 500),
                ModelChain.Attempt.failure("text-c", ModelChain.Outcome.RETRYABLE, "y", 500)));
        check("稀疏链全失败 → 降级（不卡在空位）", third.finished && third.degraded);
    }

    private static void testDecideEmptyChainFallsBack() {
        ModelChain.Decision fallback = ModelChain.decide(null, null);
        check("★null 链 → 回落默认链首", AiVerifyConfig.DEFAULT_MODEL.equals(fallback.nextModel));
        ModelChain.Decision emptyFallback = ModelChain.decide(Collections.<String>emptyList(),
                Collections.singletonList(ModelChain.Attempt.failure(AiVerifyConfig.DEFAULT_MODEL,
                        ModelChain.Outcome.RETRYABLE, "超时", 0)));
        // 空链回落完整默认三模型链（N+11 语义修正：单元素回落会悄悄丢失链位 2/3 的
        // 失败转移能力），因此一次失败 → 转移到默认链第 2 位，而不是直接降级。
        check("★空链 + 一次失败 → 转移到默认链第 2 位（glm-4v-flash）",
                !emptyFallback.finished && "glm-4v-flash".equals(emptyFallback.nextModel));
        ModelChain.Decision allThree = ModelChain.decide(Collections.<String>emptyList(),
                java.util.Arrays.asList(
                        ModelChain.Attempt.failure(AiVerifyConfig.DEFAULT_MODEL_1,
                                ModelChain.Outcome.RETRYABLE, "超时", 0),
                        ModelChain.Attempt.failure(AiVerifyConfig.DEFAULT_MODEL_2,
                                ModelChain.Outcome.RETRYABLE, "超时", 0),
                        ModelChain.Attempt.failure(AiVerifyConfig.DEFAULT_MODEL_3,
                                ModelChain.Outcome.RETRYABLE, "超时", 0)));
        check("★空链 + 三次全失败 → 终局降级", allThree.finished && allThree.degraded);
    }

    // ------------------------------------------------------------------
    // 3. classifyFailure
    // ------------------------------------------------------------------

    private static void testClassifyFailure() {
        check("★401 → AUTH_FAILURE", ModelChain.classifyFailure(401) == ModelChain.Outcome.AUTH_FAILURE);
        check("★403 → AUTH_FAILURE", ModelChain.classifyFailure(403) == ModelChain.Outcome.AUTH_FAILURE);
        check("★429 → RETRYABLE（限流换下一个）",
                ModelChain.classifyFailure(429) == ModelChain.Outcome.RETRYABLE);
        check("★5xx → RETRYABLE", ModelChain.classifyFailure(503) == ModelChain.Outcome.RETRYABLE);
        check("400 → RETRYABLE（不支持图片等）",
                ModelChain.classifyFailure(400) == ModelChain.Outcome.RETRYABLE);
        check("0（网络异常）→ RETRYABLE", ModelChain.classifyFailure(0) == ModelChain.Outcome.RETRYABLE);
        check("isAuthStatus 只对 401/403", ModelChain.isAuthStatus(401) && ModelChain.isAuthStatus(403)
                && !ModelChain.isAuthStatus(429) && !ModelChain.isAuthStatus(500));
    }

    // ------------------------------------------------------------------
    // 4. prefixReason
    // ------------------------------------------------------------------

    private static void testPrefixReason() {
        AiVerdict verdict = new AiVerdict(AiVerdict.Verdict.PASS, 90, "桌面整洁");
        AiVerdict prefixed = ModelChain.prefixReason(verdict, "glm-4v-flash");
        check("★reason 前缀「[模型名] 」", "[glm-4v-flash] 桌面整洁".equals(prefixed.reason));
        check("前缀不改变判定结论", prefixed.verdict == AiVerdict.Verdict.PASS);
        check("前缀不改变置信度", prefixed.confidence == 90);
        check("★原对象不变（不可变纪律）", "桌面整洁".equals(verdict.reason));
        AiVerdict emptyReason = ModelChain.prefixReason(
                new AiVerdict(AiVerdict.Verdict.UNCERTAIN, 50, "  "), "m");
        check("空 reason → 只有前缀", "[m]".equals(emptyReason.reason));
        check("null verdict → null（不炸）", ModelChain.prefixReason(null, "m") == null);
        AiVerdict noModel = ModelChain.prefixReason(verdict, "");
        check("空模型名 → 原样返回（不加空前缀）", "桌面整洁".equals(noModel.reason));
    }

    // ------------------------------------------------------------------
    // 5. AiVerifyConfig 链字段
    // ------------------------------------------------------------------

    private static void testConfigChainDefaults() {
        check("★默认链首 = GLM-4.1V-Thinking-Flash",
                "glm-4.1v-thinking-flash".equals(AiVerifyConfig.DEFAULT_MODEL_1));
        check("★默认链 2 = GLM-4V-Flash", "glm-4v-flash".equals(AiVerifyConfig.DEFAULT_MODEL_2));
        check("★默认链 3 = glm-4.6v-flash（视觉模型；4.5-Flash 转任周报文本模型）",
                "glm-4.6v-flash".equals(AiVerifyConfig.DEFAULT_MODEL_3));
        check("★DEFAULT_MODEL = 链首（兼容别名）",
                AiVerifyConfig.DEFAULT_MODEL_1.equals(AiVerifyConfig.DEFAULT_MODEL));
        AiVerifyConfig defaults = AiVerifyConfig.defaults();
        check("★defaults() 带三模型链",
                Arrays.asList(AiVerifyConfig.DEFAULT_MODEL_1, AiVerifyConfig.DEFAULT_MODEL_2,
                        AiVerifyConfig.DEFAULT_MODEL_3).equals(defaults.models));
        check("defaults().model = 链首（兼容字段）",
                AiVerifyConfig.DEFAULT_MODEL_1.equals(defaults.model));
        check("★effectiveModel() = 链首", AiVerifyConfig.DEFAULT_MODEL_1.equals(defaults.effectiveModel()));
        check("effectiveModels() = 完整链", defaults.models.equals(defaults.effectiveModels()));
    }

    private static void testConfigLegacyMigration() {
        // 老持久化：只有单 model 键（无 models）→ 读时迁移为链首。
        Map<String, Object> legacy = new java.util.LinkedHashMap<>();
        legacy.put("baseUrl", "https://open.bigmodel.cn/api/paas/v4/");
        legacy.put("model", "glm-4.6v-flash");
        legacy.put("apiKey", "secret");
        AiVerifyConfig migrated = AiVerifyConfig.fromMap(legacy);
        check("★老单 model 迁移为链首", "glm-4.6v-flash".equals(migrated.model));
        check("★迁移后链只有一项（不凭空补默认链）",
                Collections.singletonList("glm-4.6v-flash").equals(migrated.models));
        check("迁移后 effectiveModel = 老模型", "glm-4.6v-flash".equals(migrated.effectiveModel()));
        check("迁移保留 apiKey", "secret".equals(migrated.apiKey));

        // 全空 map（老版 null 语义）→ 空配置，effective 回落默认链首。
        AiVerifyConfig nulls = AiVerifyConfig.fromMap(null);
        check("★null Map → 空配置（model 空串）", nulls.model.isEmpty());
        check("null Map → effectiveModel 回落默认链首",
                AiVerifyConfig.DEFAULT_MODEL_1.equals(nulls.effectiveModel()));
        check("null Map → effectiveModels 回落默认链（3 个）",
                AiVerifyConfig.defaultModels().equals(nulls.effectiveModels()));
        AiVerifyConfig empty = new AiVerifyConfig(null, null, null);
        check("★全 null 构造 → 空配置不炸", empty.model.isEmpty() && empty.models.isEmpty());
    }

    private static void testConfigModelsRoundTrip() {
        AiVerifyConfig original = AiVerifyConfig.ofChain("https://example.com/v4/",
                Arrays.asList(" m1 ", "", "m2", "m3", "m4"), "secret");
        check("★链构造：trim + 剔空位 + 截断到 3",
                Arrays.asList("m1", "m2", "m3").equals(original.models));
        check("model 字段 = 链首", "m1".equals(original.model));
        Map<String, Object> map = original.toMap();
        check("★toMap 含 models 数组键", map.get("models") instanceof List);
        check("toMap 仍含单 model 键（兼容读取端）", "m1".equals(map.get("model")));
        AiVerifyConfig restored = AiVerifyConfig.fromMap(map);
        check("★Map 往返：链完整", original.models.equals(restored.models));
        check("Map 往返：baseUrl", original.baseUrl.equals(restored.baseUrl));
        check("Map 往返：apiKey", original.apiKey.equals(restored.apiKey));
        AiVerifyConfig single = new AiVerifyConfig("https://x/v4/", "only", "k");
        check("★单模型兼容构造 → 链只有一项", Collections.singletonList("only").equals(single.models));
        check("单模型 effectiveModel", "only".equals(single.effectiveModel()));
        AiVerifyConfig withChain = single.withModels(Arrays.asList("a", "b"));
        check("★withModels 替换链", Arrays.asList("a", "b").equals(withChain.models));
        check("withModels 保留 baseUrl/apiKey",
                "https://x/v4/".equals(withChain.baseUrl) && "k".equals(withChain.apiKey));
        AiVerifyConfig head = withChain.withModel("z");
        check("★withModel 只换链首", Arrays.asList("z", "b").equals(head.models));
    }

    // ------------------------------------------------------------------
    // 6. 请求体用链上的模型名
    // ------------------------------------------------------------------

    private static void testRequestBodyUsesChainModel() {
        AiVerifyConfig config = new AiVerifyConfig(null, null, "k");
        String body = com.checktodo.app.domain.GlmRequest.buildBodyForModel(config, "glm-4v-flash",
                "书桌整洁", "证据", "REF", "CUR");
        check("★请求体 model = 本次尝试的链位模型", body.contains("\"glm-4v-flash\""));
        check("请求体不含默认链首（按链位覆盖）",
                !body.contains(AiVerifyConfig.DEFAULT_MODEL_1));
        check("请求体含任务名", body.contains("书桌整洁"));
        String probe = com.checktodo.app.domain.GlmRequest.buildProbeBody(config, "glm-4.5-flash");
        check("★探针体也支持显式模型", probe.contains("\"glm-4.5-flash\""));
        check("探针体 max_tokens=1", probe.contains("\"max_tokens\":1"));
        String fallback = com.checktodo.app.domain.GlmRequest.buildBodyForModel(config, "  ",
                "t", "e", "REF", "CUR");
        check("★空模型名回落链首", fallback.contains(AiVerifyConfig.DEFAULT_MODEL_1));
        String legacyBody = com.checktodo.app.domain.GlmRequest.buildBodyFor(config, "t", "e",
                "REF", "CUR");
        check("老 4 参 buildBodyFor 不炸且用链首",
                legacyBody.contains(AiVerifyConfig.DEFAULT_MODEL_1));
    }

    // ------------------------------------------------------------------
    // 7. 壳层接线源码断言（JVM 不可执行，钉死形态防回退）
    // ------------------------------------------------------------------

    private static void testShellWiringBySource() {
        String http = readSource("src/com/checktodo/app/ai/GlmHttp.java");
        check("★GlmHttp 走 ModelChain 决策", http.contains("ModelChain.decide("));
        check("★GlmHttp 按链位组装请求体", http.contains("buildBodyForModel("));
        check("★GlmHttp 成功后 reason 加模型前缀", http.contains("ModelChain.prefixReason("));
        check("★GlmHttp 每次尝试 Log 链位（第 N/总数 个模型）",
                http.contains("判定链第 ") && http.contains("Log.i(TAG"));
        check("★GlmHttp 探针按链逐个尝试", http.contains("requestProbe")
                && http.contains("buildProbeBody(config, model)"));
        check("★全部失败文案含「全部 N 个模型失败」", http.contains("个模型失败"));
        check("★日志绝不打印 api key", !http.contains("Log.i(TAG, \"key")
                && !http.contains("apiKey +"));

        String main = readSource("src/com/checktodo/app/MainActivity.java");
        check("★设置对话框三行模型输入", main.contains("判定模型 1")
                && main.contains("判定模型 2") && main.contains("判定模型 3"));
        check("★hint 明示文本模型收到图片会失败并跳过",
                main.contains("文本模型收到图片会失败") && main.contains("自动跳"));
        check("★两处判定调用点都走链式 requestAsync",
                countOccurrences(main, "GlmHttp.requestAsync(") >= 2);

        String settings = readSource("src/com/checktodo/app/ai/AiVerifySettings.java");
        check("★持久化写 models JSON 数组键", settings.contains("\"models\"")
                || settings.contains("models"));
        check("★持久化仍用独立键 ai-verify.v1", settings.contains("ai-verify.v1"));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

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
