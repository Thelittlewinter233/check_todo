package com.checktodo.app.domain;

/**
 * 任务的打卡验证策略。
 *
 * <p>{@link #NONE} 是现状（点一下即完成）；{@link #AI_PHOTO} 要求拍照并由 GLM 视觉模型判定。
 * 老数据（check-todo.v1 中无 verifyMode 键）与任何无法识别的值都回落 {@link #NONE}，
 * 保证向后兼容、向前也不炸。</p>
 */
public enum VerifyMode {
    /** 无需拍照：现状行为。 */
    NONE("none"),
    /** AI 判定打卡：拍标准照 + 特征描述，之后每次打卡拍照交给视觉模型判定。 */
    AI_PHOTO("ai_photo");

    private final String value;

    VerifyMode(String value) {
        this.value = value;
    }

    /** 持久化用的字符串值（写入 check-todo.v1 的 verifyMode 键）。 */
    public String value() {
        return value;
    }

    /**
     * 解析持久化字符串。null / 空串 / 未知值一律 {@link #NONE}——
     * 宁可退回「点一下完成」，也不要把普通任务变成需要拍照的任务而阻塞用户。
     */
    public static VerifyMode fromString(String raw) {
        if (raw == null) return NONE;
        String normalized = raw.trim().toLowerCase(java.util.Locale.US);
        for (VerifyMode mode : values()) {
            if (mode.value.equals(normalized)) return mode;
        }
        return NONE;
    }

    /** 是否为「拍照 AI 判定」任务。 */
    public boolean isAiPhoto() {
        return this == AI_PHOTO;
    }
}
