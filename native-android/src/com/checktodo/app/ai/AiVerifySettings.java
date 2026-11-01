package com.checktodo.app.ai;

import android.content.Context;
import android.content.SharedPreferences;

import com.checktodo.app.domain.AiVerifyConfig;
import com.checktodo.app.domain.AiVerdictParser;

import java.util.List;
import java.util.Map;

/**
 * BYOK 配置的持久化薄壳（独立键 {@code ai-verify.v1}）。
 *
 * <p><b>与任务数据分离</b>：api key 是账户凭据，不是任务属性——存进
 * {@code check-todo.v1} 会随任务数据一起被备份/导入导出链路带出去。这里单独一个
 * SharedPreferences 键，值就是 {@link AiVerifyConfig#toMap()} 的 JSON。</p>
 *
 * <p><b>模型链（N+11）</b>：新持久化含 {@code models} JSON 数组键（判定链，≤3）；
 * 老持久化只有单 {@code model} 键——{@link AiVerifyConfig#fromMap} 读时迁移为链首，
 * 无需数据迁移脚本。</p>
 *
 * <p><b>未配置不是错误</b>：读不到或解析失败都回落 {@link AiVerifyConfig#defaults()}
 * （智谱模板 + 空 key），打卡流程据此走降级路径，永不阻塞。</p>
 */
public final class AiVerifySettings {

    /** 独立持久化键（与 check-todo.v1 / timetable.v2 / check-todo-widget 并列）。 */
    public static final String STORAGE_KEY = "ai-verify.v1";
    private static final String PREFS_NAME = "check-todo";

    private AiVerifySettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 读取配置；未配置或脏数据 → 默认模板（空 key）。 */
    public static AiVerifyConfig load(Context context) {
        String raw = prefs(context).getString(STORAGE_KEY, null);
        if (raw == null || raw.trim().isEmpty()) return AiVerifyConfig.defaults();
        Map<String, Object> map = AiVerdictParser.readObjectOrNull(raw);
        if (map == null) return AiVerifyConfig.defaults();
        return AiVerifyConfig.fromMap(map);
    }

    /** 保存配置（全部键一起写，避免半更新）。 */
    public static void save(Context context, AiVerifyConfig config) {
        AiVerifyConfig safe = config == null ? AiVerifyConfig.defaults() : config;
        prefs(context).edit().putString(STORAGE_KEY, toJson(safe.toMap())).apply();
    }

    /** 是否已配置 api key（未配置 → 打卡走降级）。 */
    public static boolean isConfigured(Context context) {
        return load(context).isConfigured();
    }

    /**
     * Map → JSON（域层零依赖，这里自己拼）。字符串值做转义；{@code models} 是
     * List 值 → JSON 数组（老版本只有字符串值，此扩展向后兼容读取端）。
     */
    static String toJson(Map<String, Object> map) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) builder.append(',');
            first = false;
            builder.append(com.checktodo.app.domain.GlmRequest.escapeJson(entry.getKey()))
                    .append(':')
                    .append(valueJson(entry.getValue()));
        }
        return builder.append('}').toString();
    }

    private static String valueJson(Object value) {
        if (value instanceof List) {
            StringBuilder array = new StringBuilder("[");
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) array.append(',');
                first = false;
                array.append(com.checktodo.app.domain.GlmRequest.escapeJson(
                        item == null ? "" : String.valueOf(item)));
            }
            return array.append(']').toString();
        }
        return com.checktodo.app.domain.GlmRequest.escapeJson(
                value == null ? "" : String.valueOf(value));
    }
}
