package com.checktodo.app.widget;

/**
 * 三态小卡片的模式纯函数：不依赖 Android，供 Provider / Factory / 单测共用，
 * 保证模式常量、循环顺序、文案与 URI 解析只有一份实现。
 */
public final class WidgetModes {
    /** 切换顺序：打卡 → 待办 → 课表 → 打卡。 */
    public static final int MODE_TASKS = 0;
    public static final int MODE_TODOS = 1;
    public static final int MODE_COURSES = 2;
    public static final int MODE_COUNT = 3;

    private WidgetModes() {
    }

    /** 老数据或异常值一律回落打卡视图，避免越界模式导致渲染串视图。 */
    public static int normalize(int mode) {
        return mode >= MODE_TASKS && mode < MODE_COUNT ? mode : MODE_TASKS;
    }

    /** 打卡 → 待办 → 课表 → 打卡。 */
    public static int next(int mode) {
        return normalize((mode + 1) % MODE_COUNT);
    }

    public static String title(int mode) {
        int safe = normalize(mode);
        if (safe == MODE_TODOS) return "待办";
        if (safe == MODE_COURSES) return "今日课程";
        return "今日打卡";
    }

    public static String emptyText(int mode) {
        int safe = normalize(mode);
        if (safe == MODE_TODOS) return "没有待办";
        if (safe == MODE_COURSES) return "今天没有课程";
        return "今天没有任务";
    }

    /** SharedPreferences 键：mode.<widgetId>。 */
    public static String modeKey(int appWidgetId) {
        return "mode." + appWidgetId;
    }

    /**
     * 从集合视图服务 URI（checktodo://widget/<id>/mode/<mode>）解析初始模式。
     * URI 非法时返回 -1，调用方应回落存储读取。
     */
    public static int modeFromUri(String uri) {
        if (uri == null) return -1;
        int marker = uri.lastIndexOf("/mode/");
        if (marker < 0) return -1;
        try {
            return Integer.parseInt(uri.substring(marker + "/mode/".length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
