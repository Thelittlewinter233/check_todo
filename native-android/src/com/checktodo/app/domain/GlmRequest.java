package com.checktodo.app.domain;

import java.util.List;

/**
 * GLM 请求体组装（OpenAI 兼容 chat/completions），纯 Java。
 *
 * <p>HTTP 执行本身在 Android 壳层（HttpURLConnection + 20s 超时 + 后台线程）；
 * 本类只负责可 JVM 测试的部分：data URI 拼装、请求体 JSON 组装与转义。</p>
 *
 * <p><b>api key 不进请求体</b>——壳层把它放进 {@code Authorization: Bearer} 头，
 * 避免 key 随日志/诊断泄漏。</p>
 */
public final class GlmRequest {

    private GlmRequest() {
    }

    /** base64 图片 → OpenAI 兼容的 data URI。mime 缺省 image/jpeg。 */
    public static String dataUri(String base64, String mime) {
        String normalized = mime == null || mime.trim().isEmpty() ? "image/jpeg" : mime.trim();
        return "data:" + normalized + ";base64," + (base64 == null ? "" : base64);
    }

    /**
     * 组装 chat/completions 请求体。
     *
     * <p>结构：{@code {"model":..., "messages":[{system 纯文本},{user 两图+文本}],
     * "temperature":0}}。temperature=0 让判定尽量稳定可复现。</p>
     */
    public static String buildBody(AiVerifyConfig config, String referenceDataUri,
                                   String currentDataUri, String userText) {
        return buildBodyWithModel(config, config == null ? null : config.effectiveModel(),
                referenceDataUri, currentDataUri, userText);
    }

    /**
     * 判定链版本：显式指定本次尝试的模型名（{@link ModelChain} 按链逐个转移时调用）。
     * model 空白 → 回落 {@code config.effectiveModel()}。
     */
    public static String buildBodyWithModel(AiVerifyConfig config, String model,
                                            String referenceDataUri, String currentDataUri,
                                            String userText) {
        List<AiPromptBuilder.Message> messages = AiPromptBuilder.buildMessages(
                "", "", referenceDataUri, currentDataUri);
        // 文本提示由调用方传入（含任务名与证据块）；这里重新组一遍消息结构，
        // 用调用方文本覆盖 user 消息的 text 部件。
        String effective = model == null || model.trim().isEmpty()
                ? (config == null ? AiVerifyConfig.DEFAULT_MODEL : config.effectiveModel())
                : model.trim();
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":").append(escapeJson(effective));
        body.append(",\"temperature\":0");
        body.append(",\"messages\":[");
        appendMessage(body, messages.get(0).role, messages.get(0).parts, null);
        body.append(',');
        appendMessage(body, messages.get(1).role, messages.get(1).parts, userText);
        body.append("]}");
        return body.toString();
    }

    /**
     * 「测试连接」用的最小请求体：纯文本 ping + {@code max_tokens=1}（几乎不耗额度）。
     *
     * <p>只验证「endpoint 打得通 + key 有效 + model 存在」，不带图片，也不解析判定。
     * config 为 null 时按默认配置组装（不崩）。</p>
     */
    public static String buildProbeBody(AiVerifyConfig config) {
        return buildProbeBody(config, null);
    }

    /** 判定链版本：显式指定探针模型名（空 → 链首）。 */
    public static String buildProbeBody(AiVerifyConfig config, String model) {
        AiVerifyConfig safe = config == null ? AiVerifyConfig.defaults() : config;
        String effective = model == null || model.trim().isEmpty()
                ? safe.effectiveModel() : model.trim();
        return "{\"model\":" + escapeJson(effective)
                + ",\"max_tokens\":1,\"temperature\":0"
                + ",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";
    }

    /** 周报文本请求的输出上限（产品决策：800 token 够 150 字总结 + 3 条警醒）。 */
    /** 周报专用文本模型 GLM-4-Flash-250414 最大输出 16K（2026-10-31 用户指定）：token 上限
 *  16384 顶满模型上限（1210 自适应降额仍兜底）。周报模型固定 WEEKLY_MODEL，不吃判定链。 */
    public static final int TEXT_MAX_TOKENS = 16384;

    /**
     * 纯文本请求体（AI 周报用）：无 image_url，单条 user 文本，
     * {@code max_tokens=} {@value #TEXT_MAX_TOKENS}，temperature=0。
     *
     * <p>模型口径：<b>显式 model 优先</b>；未显式指定时用
     * {@link AiVerifyConfig#WEEKLY_MODEL}（周报专用文本模型，不吃图片判定链）；
     * config 为 null 时回落 {@link AiVerifyConfig#DEFAULT_MODEL}（不崩）。
     * api key 照旧不进请求体。</p>
     */
    public static String buildTextBody(AiVerifyConfig config, String userText) {
        return buildTextBody(config, null, userText);
    }

    /** 纯文本请求体 + 显式模型名（空白 → 周报专用文本模型）。 */
    public static String buildTextBody(AiVerifyConfig config, String model, String userText) {
        return buildTextBody(config, model, userText, TEXT_MAX_TOKENS);
    }

    /**
     * 按服务端 1210 错误（「max_tokens参数非法：限制数值范围[1,16384]」）解析各模型
     * 输出上限并对请求降额——不同模型上限不同（实测 4.1v-thinking=16384、4v-flash=1024），
     * 请求值超上限会被整体拒绝。解析不到上限返回 -1（调用方放弃该模型）。
     */
    public static int clampFromError(String errorBody, int requested) {
        if (errorBody == null || requested <= 0) return -1;
        if (!errorBody.contains("max_tokens")) return -1;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\[\\s*1\\s*,\\s*(\\d+)\\s*\\]").matcher(errorBody);
        if (!matcher.find()) return -1;
        try {
            int bound = Integer.parseInt(matcher.group(1));
            return Math.min(requested, bound);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /** 纯文本请求体 + 显式模型名与 max_tokens（1210 降额重试用）。 */
    public static String buildTextBody(AiVerifyConfig config, String model, String userText,
                                       int maxTokens) {
        String effective = model == null || model.trim().isEmpty()
                ? (config == null ? AiVerifyConfig.DEFAULT_MODEL : AiVerifyConfig.WEEKLY_MODEL)
                : model.trim();
        return "{\"model\":" + escapeJson(effective)
                + ",\"max_tokens\":" + Math.max(1, maxTokens)
                + ",\"temperature\":0"
                + ",\"messages\":[{\"role\":\"user\",\"content\":"
                + escapeJson(userText == null ? "" : userText) + "}]}";
    }

    /**
     * 完整版组装：直接给任务名与证据（内部走 AiPromptBuilder.userText）。
     */
    public static String buildBodyFor(AiVerifyConfig config, String taskName, String evidence,
                                      String referenceDataUri, String currentDataUri) {
        return buildBodyWithModel(config, config == null ? null : config.effectiveModel(),
                referenceDataUri, currentDataUri,
                AiPromptBuilder.userText(taskName, evidence));
    }

    /** 判定链版本：完整版组装 + 显式模型名。 */
    public static String buildBodyForModel(AiVerifyConfig config, String model, String taskName,
                                           String evidence, String referenceDataUri,
                                           String currentDataUri) {
        return buildBodyWithModel(config, model, referenceDataUri, currentDataUri,
                AiPromptBuilder.userText(taskName, evidence));
    }

    private static void appendMessage(StringBuilder body, String role,
                                      List<AiPromptBuilder.Part> parts, String textOverride) {
        body.append("{\"role\":").append(escapeJson(role)).append(",\"content\":");
        if ("system".equals(role)) {
            String text = textOverride != null ? textOverride : firstText(parts);
            body.append(escapeJson(text)).append('}');
            return;
        }
        body.append('[');
        boolean first = true;
        for (AiPromptBuilder.Part part : parts) {
            if (!first) body.append(',');
            first = false;
            if ("image_url".equals(part.type)) {
                body.append("{\"type\":\"image_url\",\"image_url\":{\"url\":")
                        .append(escapeJson(part.url)).append("}}");
            } else {
                String text = textOverride != null ? textOverride : part.text;
                body.append("{\"type\":\"text\",\"text\":").append(escapeJson(text)).append('}');
            }
        }
        body.append("]}");
    }

    private static String firstText(List<AiPromptBuilder.Part> parts) {
        for (AiPromptBuilder.Part part : parts) {
            if ("text".equals(part.type)) return part.text;
        }
        return "";
    }

    /**
     * JSON 字符串转义（含首尾引号）。控制字符走 \\u00XX，其余原样——
     * 中文直接保留（UTF-8 请求体），减小体积也便于诊断。
     */
    public static String escapeJson(String raw) {
        if (raw == null) return "\"\"";
        StringBuilder builder = new StringBuilder(raw.length() + 16);
        builder.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char current = raw.charAt(i);
            switch (current) {
                case '"': builder.append("\\\""); break;
                case '\\': builder.append("\\\\"); break;
                case '\n': builder.append("\\n"); break;
                case '\r': builder.append("\\r"); break;
                case '\t': builder.append("\\t"); break;
                case '\b': builder.append("\\b"); break;
                case '\f': builder.append("\\f"); break;
                default:
                    if (current < 0x20) {
                        builder.append(String.format(java.util.Locale.US, "\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
            }
        }
        builder.append('"');
        return builder.toString();
    }
}
