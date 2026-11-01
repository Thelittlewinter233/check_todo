package com.checktodo.app.domain;

import java.util.Map;

/**
 * AI 调用的<b>可诊断错误文案</b>（纯 Java，壳层与测试共用）。
 *
 * <p><b>为什么单独一层</b>：真机排查「HTTP 404」时，旧文案只有
 * {@code AI 服务返回异常（HTTP 404）}——既看不出实际请求的地址（base_url 被重复
 * 追加了 {@code /chat/completions}），也看不到服务端说了什么，只能靠 adb + curl 对照
 * 才定位到根因。这里把「最终 endpoint + 响应体前 120 字」带进文案，下次同类问题
 * 一眼可断。</p>
 *
 * <p><b>脱敏纪律</b>：响应体可能回显 Authorization 片段或含服务端内部信息——
 * 只取前 {@value #BODY_EXCERPT_CHARS} 字、换行折叠为空格、并把 api key 字面量抹成
 * {@value #REDACTED}。endpoint 本身是公开地址，不含 key（key 只走 Authorization 头）。</p>
 */
public final class AiHttpDiagnostics {

    /** 响应体最多带出的字符数（避免刷屏与泄漏服务端细节）。 */
    public static final int BODY_EXCERPT_CHARS = 120;
    /** 脱敏占位。 */
    public static final String REDACTED = "***";

    private AiHttpDiagnostics() {
    }

    /**
     * HTTP 状态码 → 用户可读且可诊断的原因。
     *
     * @param status   HTTP 状态码
     * @param body     响应体（可为 null / 空）
     * @param endpoint 最终请求地址（不含 key；可为 null）
     * @param apiKey   当前 api key，用于把响应体里可能回显的 key 抹掉（可为 null）
     * @return 形如「AI 服务返回 HTTP 404（endpoint: https://…/chat/completions）：响应体…」
     */
    public static String statusWhy(int status, String body, String endpoint, String apiKey) {
        StringBuilder why = new StringBuilder();
        why.append(headline(status));
        if (endpoint != null && !endpoint.trim().isEmpty()) {
            why.append("（endpoint: ").append(endpoint.trim()).append('）');
        }
        String excerpt = excerpt(body, apiKey);
        if (excerpt.isEmpty()) return why.toString();
        return why.append("：").append(excerpt).toString();
    }

    /** 状态码语义（额度类错误单独点明，产品决策要求可辨识）。 */
    private static String headline(int status) {
        if (status == 401 || status == 403) return "AI 服务鉴权失败（API key 无效或无权限）";
        if (status == 429) return "AI 服务额度耗尽或请求过于频繁";
        if (status == 402) return "AI 服务额度耗尽（账户余额不足）";
        if (status >= 500) return "AI 服务暂时不可用（HTTP " + status + "）";
        return "AI 服务返回 HTTP " + status;
    }

    /**
     * 响应体摘录：换行/制表折叠为空格、压缩连续空白、抹掉 api key、截到
     * {@value #BODY_EXCERPT_CHARS} 字（截断补省略号）。全空白 → 空串。
     */
    static String excerpt(String body, String apiKey) {
        if (body == null) return "";
        String text = body.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            text = text.replace(apiKey.trim(), REDACTED);
        }
        if (text.isEmpty()) return "";
        if (text.length() <= BODY_EXCERPT_CHARS) return text;
        return text.substring(0, BODY_EXCERPT_CHARS) + "…";
    }

    /**
     * 从成功响应体里取实际服务模型名（{@code model} 字段），供「测试连接」展示。
     * 取不到（非 JSON / 无字段）返回 null。
     */
    public static String modelName(String responseBody) {
        Map<String, Object> parsed = responseBody == null || responseBody.trim().isEmpty()
                ? null : AiVerdictParser.readObjectOrNull(responseBody);
        if (parsed == null) return null;
        Object model = parsed.get("model");
        if (!(model instanceof String)) return null;
        String name = ((String) model).trim();
        return name.isEmpty() ? null : name;
    }
}
