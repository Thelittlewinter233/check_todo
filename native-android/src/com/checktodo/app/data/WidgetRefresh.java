package com.checktodo.app.data;

import android.content.Context;
import android.content.Intent;

import com.checktodo.app.MainActivity;
import com.checktodo.app.widget.CheckTodoWidget;
import com.checktodo.app.widget.TodayCourseWidget;

/**
 * App 内数据变更 → 卡片刷新的统一出口。
 *
 * <p>广播以显式组件（CheckTodoWidget）发出，保证在 Android 8+ 隐式广播限制下仍可送达；
 * 同一广播也会被 {@link MainActivity} 注册的动态 receiver 收到，用于刷新界面。</p>
 */
public final class WidgetRefresh {
    public static final String ACTION_REFRESH = "com.checktodo.app.action.WIDGET_REFRESH";

    private WidgetRefresh() {
    }

    public static Intent refreshIntent(Context context, Class<?> receiver) {
        Intent intent = new Intent();
        intent.setClass(context, receiver);
        intent.setAction(ACTION_REFRESH);
        return intent;
    }

    public static void notifyDataChanged(Context context) {
        if (context == null) return;
        try {
            Intent refresh = new Intent(ACTION_REFRESH);
            context.sendBroadcast(refresh);
            context.sendBroadcast(refreshIntent(context, CheckTodoWidget.class));
            context.sendBroadcast(refreshIntent(context, TodayCourseWidget.class));
        } catch (Exception ignored) {
            // 广播失败不影响数据已写入的状态。
        }
    }
}
