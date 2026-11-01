package com.checktodo.app.domain;

/**
 * 主题模式（纯 Java，可单测）：亮色 / 深色 / 跟随系统。
 * 持久化值为小写字符串，非法或缺省值一律回落为 FOLLOW_SYSTEM，保证旧数据兼容。
 */
public enum ThemeMode {
    LIGHT("light"),
    DARK("dark"),
    FOLLOW_SYSTEM("follow_system");

    private final String value;

    ThemeMode(String value) {
        this.value = value;
    }

    /** 持久化到 SharedPreferences 的字符串值。 */
    public String value() {
        return value;
    }

    /** 解析持久化值；null / 空串 / 未知值回落为 FOLLOW_SYSTEM。 */
    public static ThemeMode fromString(String raw) {
        if (raw == null) return FOLLOW_SYSTEM;
        for (ThemeMode mode : values()) {
            if (mode.value.equals(raw)) return mode;
        }
        return FOLLOW_SYSTEM;
    }

    /** 图标按钮点击时的循环顺序：亮色 → 深色 → 跟随系统 → 亮色。 */
    public ThemeMode next() {
        ThemeMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
