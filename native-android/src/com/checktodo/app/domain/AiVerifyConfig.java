package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 判定打卡的 BYOK 配置（base_url / 模型链 / api key），不可变值对象。
 *
 * <p>默认预置智谱模板：{@code https://open.bigmodel.cn/api/paas/v4/} +
 * 默认链 {@value #DEFAULT_MODEL_1} → {@value #DEFAULT_MODEL_2} → {@value #DEFAULT_MODEL_3}
 * （三个都是免费视觉模型。api key 由用户自备，只存本机 SharedPreferences
 * （key = {@code ai-verify.v1}），绝不写入任务数据。</p>
 *
 * <p><b>模型链（N+11）</b>：判定按链序最多尝试 {@value ModelChain#MAX_MODELS} 个模型，
 * 单个失败转移下一个（决策见 {@link ModelChain}）。{@link #model} 是兼容字段——
 * 老持久化只有单 model，读时迁移为链首（见 {@link #fromMap}）。</p>
 *
 * <p><b>未配 key 不是错误</b>：{@link #isConfigured()} 为 false 时打卡流程走降级路径
 * （仅 L1 预检 + 「待复核」标记），永不阻塞用户。</p>
 */
public final class AiVerifyConfig {
    /** 智谱默认网关（OpenAI 兼容 v4）。 */
    public static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4/";
    /** 链首默认模型：免费视觉模型。 */
    public static final String DEFAULT_MODEL_1 = "glm-4.1v-thinking-flash";
    /** 链位 2 默认模型：免费视觉模型。 */
    public static final String DEFAULT_MODEL_2 = "glm-4v-flash";
    /** 链位 3 默认模型：免费视觉模型（2026-10-31 调整：原 glm-4.5-flash 是文本模型
     *  不能识图，判定链全链改用视觉模型，glm-4.5-flash 转任周报专用文本模型）。 */
    public static final String DEFAULT_MODEL_3 = "glm-4.6v-flash";
    /** 兼容别名：默认模型 = 链首。 */
    public static final String DEFAULT_MODEL = DEFAULT_MODEL_1;
    /** chat completions 相对路径。 */
    public static final String CHAT_PATH = "chat/completions";
    /** 周报专用文本模型（2026-10-31 用户指定）：GLM-4-Flash-250414 纯文本、最大输出 16K
     *  （免费档稳定，非 thinking 无思维链开销），不参与图片判定链。
     *  周报请求 {@code max_tokens} 见 {@code GlmRequest.TEXT_MAX_TOKENS}。 */
    public static final String WEEKLY_MODEL = "glm-4-flash-250414";

    /** 归一化后的 base_url；空串表示「未自定义，用默认」。 */
    public final String baseUrl;
    /** 归一化后的链首 model（兼容老持久化字段）；空串表示「未自定义，用默认」。 */
    public final String model;
    /** 判定链（保序，空位已剔除，≤{@value ModelChain#MAX_MODELS}；可为空=用默认）。 */
    public final List<String> models;
    /** 归一化后的 api key；空串表示未配置。 */
    public final String apiKey;

    /** 兼容构造：单模型（保留老调用点）。null/空白 model → 全空配置（回落默认链）。 */
    public AiVerifyConfig(String baseUrl, String model, String apiKey) {
        this(baseUrl, model == null ? Collections.<String>emptyList()
                : Collections.singletonList(model), apiKey, CHAIN_MARKER);
    }

    /**
     * 链构造入口（公开）：models 为判定链（保序）。
     *
     * <p>用静态工厂而非公开重载构造，是为了消除
     * {@code new AiVerifyConfig(base, null, null)} 这类调用的<b>重载歧义</b>——
     * String 与 List 两个第二参数在传字面 null 时无法区分（编译期报「引用不明确」）。</p>
     */
    public static AiVerifyConfig ofChain(String baseUrl, List<String> models, String apiKey) {
        return new AiVerifyConfig(baseUrl, models, apiKey, CHAIN_MARKER);
    }

    /**
     * 链构造（私有）：models 归一化（trim、剔空白位、截到 3）后为空 → 全空配置
     * （{@code model=""}，行为等同老版「未自定义，用默认」——
     * {@link #effectiveModel()} 回落默认链首）。
     */
    private AiVerifyConfig(String baseUrl, List<String> models, String apiKey, ChainMarker marker) {
        this.baseUrl = normalize(baseUrl);
        this.models = ModelChain.sanitize(models);
        this.model = this.models.isEmpty() ? "" : this.models.get(0);
        this.apiKey = normalize(apiKey);
    }

    /** 私有构造的重载消歧标记（永不为 null，不参与语义）。 */
    private enum ChainMarker { CHAIN }
    private static final ChainMarker CHAIN_MARKER = ChainMarker.CHAIN;

    /** 默认配置（智谱模板，无 key）。 */
    public static AiVerifyConfig defaults() {
        return ofChain(DEFAULT_BASE_URL, defaultModels(), "");
    }

    /** 默认判定链（三个免费模型；可修改返回新列表）。 */
    public static List<String> defaultModels() {
        return new ArrayList<>(Arrays.asList(DEFAULT_MODEL_1, DEFAULT_MODEL_2, DEFAULT_MODEL_3));
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** 实际生效的 base_url（未配置回落默认）。 */
    public String effectiveBaseUrl() {
        return baseUrl.isEmpty() ? DEFAULT_BASE_URL : baseUrl;
    }

    /** 实际生效的判定链（链首为空时整体回落默认链）。 */
    public List<String> effectiveModels() {
        return ModelChain.effective(models);
    }

    /**
     * 周报的模型尝试序列（纯函数）：周报专用文本模型 WEEKLY_MODEL 优先，
     * 失败后落到判定链模型（视觉模型处理纯文本同样可行，2026-10-31 实测
     * glm-4.5-flash 免费档排队 >90 秒×2 而判定链链首 <20 秒出全文）。
     * 去重、剔空、上限 4 个。
     */
    public List<String> textModelSequence() {
        List<String> sequence = new ArrayList<>();
        if (WEEKLY_MODEL != null && !WEEKLY_MODEL.trim().isEmpty()) {
            sequence.add(WEEKLY_MODEL.trim());
        }
        for (String model : effectiveModels()) {
            if (model == null || model.trim().isEmpty()) continue;
            if (sequence.contains(model.trim())) continue;
            sequence.add(model.trim());
            if (sequence.size() >= 4) break;
        }
        return sequence;
    }

    /** 实际生效的链首 model（未配置回落默认链首）。 */
    public String effectiveModel() {
        return effectiveModels().get(0);
    }

    /**
     * chat completions 完整 endpoint，<b>兼容两种 base_url 填法</b>。
     *
     * <p>用户把服务商文档里的完整地址粘进 base_url 是最自然的操作（真机 404 的根因）：
     * 旧实现无条件追加 {@value #CHAT_PATH}，会打到
     * {@code …/v4/chat/completions/chat/completions}。这里先归一化再拼：</p>
     * <ol>
     *   <li>去首尾空格与所有尾斜杠；</li>
     *   <li>若已以 {@value #CHAT_PATH} 结尾（忽略大小写）则剥掉一次；</li>
     *   <li>统一拼一次 {@code /}{@value #CHAT_PATH}。</li>
     * </ol>
     * <p>因此 {@code https://x/v4}、{@code https://x/v4/chat/completions}、
     * {@code https://x/v4/chat/completions/} 三者结果相同，默认模板行为不变。</p>
     */
    public String endpoint() {
        String base = effectiveBaseUrl().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.toLowerCase(Locale.US).endsWith("/" + CHAT_PATH)) {
            base = base.substring(0, base.length() - CHAT_PATH.length() - 1);
        }
        return base + "/" + CHAT_PATH;
    }

    /** 是否已配置 api key（未配置 → 走降级路径）。 */
    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    /**
     * 校验配置。空 baseUrl 合法（回落默认）；自定义 baseUrl 必须是 http(s)。
     * 允许 http 是为自建代理场景；返回空列表表示合法。
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (baseUrl.isEmpty()) return errors;
        String lowered = baseUrl.toLowerCase(Locale.US);
        if (!lowered.startsWith("http://") && !lowered.startsWith("https://")) {
            errors.add("base_url 必须以 http:// 或 https:// 开头");
        }
        return errors;
    }

    /** 持久化用 Map（写入 ai-verify.v1 的 JSON 对象；models 存 JSON 数组键）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("baseUrl", baseUrl);
        map.put("model", model);
        map.put("apiKey", apiKey);
        map.put("models", new ArrayList<>(models));
        return map;
    }

    /**
     * 从持久化 Map 恢复；null → 全空配置（等价「未自定义，用默认」）。
     *
     * <p><b>向后兼容</b>：老持久化只有单 {@code model} 键——读时迁移为链首；
     * 新键 {@code models} 为 JSON 数组（空白位剔除）。</p>
     */
    public static AiVerifyConfig fromMap(Map<String, ?> map) {
        if (map == null) return ofChain("", Collections.<String>emptyList(), "");
        List<String> chain = null;
        Object raw = map.get("models");
        if (raw instanceof List) {
            chain = new ArrayList<>();
            for (Object item : (List<?>) raw) {
                if (item == null) continue;
                String model = String.valueOf(item).trim();
                if (!model.isEmpty()) chain.add(model);
            }
        }
        if (chain == null) {
            // 老持久化迁移：单 model → 链首；model 也空 → 全空配置（回落默认链）。
            String single = stringValue(map.get("model"));
            chain = single.isEmpty() ? Collections.<String>emptyList()
                    : Collections.singletonList(single);
        }
        return ofChain(stringValue(map.get("baseUrl")), chain, stringValue(map.get("apiKey")));
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 复制并替换单项（设置对话框保存用）。 */
    public AiVerifyConfig withBaseUrl(String next) {
        return ofChain(next, models, apiKey);
    }

    /** 复制并替换链首（兼容老「单模型输入」路径）。 */
    public AiVerifyConfig withModel(String next) {
        List<String> chain = new ArrayList<>();
        chain.add(next == null ? "" : next);
        for (int index = 1; index < models.size() && chain.size() < ModelChain.MAX_MODELS; index++) {
            chain.add(models.get(index));
        }
        return ofChain(baseUrl, chain, apiKey);
    }

    /** 复制并整体替换判定链（设置对话框保存三行输入用）。 */
    public AiVerifyConfig withModels(List<String> next) {
        return ofChain(baseUrl, next, apiKey);
    }

    public AiVerifyConfig withApiKey(String next) {
        return ofChain(baseUrl, models, next);
    }

    /** 遮蔽 key 用于界面展示（只显示尾 4 位）。 */
    public String maskedKey() {
        if (apiKey.isEmpty()) return "";
        if (apiKey.length() <= 4) return "****";
        return "****" + apiKey.substring(apiKey.length() - 4);
    }

    /** Map 拷贝，避免持久化层拿到内部引用。 */
    public Map<String, Object> copyMap() {
        return new HashMap<>(toMap());
    }
}
