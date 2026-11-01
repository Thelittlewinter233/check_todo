package com.checktodo.app.widget;

import java.util.HashMap;
import java.util.Map;

/**
 * 按卡片实例持久化模式的存储抽象。
 *
 * <p>接口与内存实现均为纯 Java，可直接单测；SharedPreferences 实现留在
 * {@link CheckTodoWidget} 内，避免测试链路依赖 Android 框架。</p>
 */
public interface WidgetModeStore {
    int read(int appWidgetId);

    void write(int appWidgetId, int mode);

    void remove(int appWidgetId);

    /** 单测 / 调试用内存实现：默认打卡视图，多实例键互相隔离。 */
    final class Memory implements WidgetModeStore {
        private final Map<String, Integer> values = new HashMap<>();

        @Override
        public int read(int appWidgetId) {
            Integer value = values.get(WidgetModes.modeKey(appWidgetId));
            return WidgetModes.normalize(value == null ? WidgetModes.MODE_TASKS : value);
        }

        @Override
        public void write(int appWidgetId, int mode) {
            values.put(WidgetModes.modeKey(appWidgetId), WidgetModes.normalize(mode));
        }

        @Override
        public void remove(int appWidgetId) {
            values.remove(WidgetModes.modeKey(appWidgetId));
        }
    }
}
