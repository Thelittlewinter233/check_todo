package com.checktodo.app.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

import com.checktodo.app.MainActivity;
import com.checktodo.app.R;
import com.checktodo.app.data.WidgetRefresh;

/** 今日课程小卡片：只展示当天课程，点击任意条目打开 App 课表页。 */
public final class TodayCourseWidget extends AppWidgetProvider {
    static final String ACTION_ITEM = "com.checktodo.app.action.TODAY_COURSE_ITEM";
    static final String ACTION_OPEN_APP = "com.checktodo.app.action.TODAY_COURSE_OPEN_APP";
    /**
     * 开启课表页的跳转思路，三态卡课表视图沿用同一 extra 与 flag；
     * 常量值直接取 {@link MainActivity#EXTRA_OPEN_TIMETABLE}，避免两套约定漂移。
     */
    public static final String EXTRA_OPEN_TIMETABLE = MainActivity.EXTRA_OPEN_TIMETABLE;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) updateWidget(context, manager, appWidgetId);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_ITEM.equals(action) || ACTION_OPEN_APP.equals(action)) {
            openTimetable(context);
            return;
        }
        if (WidgetRefresh.ACTION_REFRESH.equals(action)) {
            updateAll(context);
            return;
        }
        super.onReceive(context, intent);
    }

    private static void updateWidget(Context context, AppWidgetManager manager, int appWidgetId) {
        if (manager == null) return;
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_today_course);
        views.setTextViewText(R.id.today_course_widget_title, "今日课程");
        views.setTextViewText(R.id.today_course_widget_empty, "今天没有课程");
        views.setOnClickPendingIntent(R.id.today_course_widget_title,
                openAppIntent(context, appWidgetId));

        Intent service = new Intent(context, TodayCourseWidgetService.class);
        service.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        service.setData(android.net.Uri.parse("checktodo://today-course-widget/" + appWidgetId));
        views.setRemoteAdapter(R.id.today_course_widget_list, service);
        views.setEmptyView(R.id.today_course_widget_list, R.id.today_course_widget_empty);
        views.setPendingIntentTemplate(R.id.today_course_widget_list,
                templateIntent(context, appWidgetId));
        manager.updateAppWidget(appWidgetId, views);
        manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.today_course_widget_list);
    }

    private static PendingIntent templateIntent(Context context, int appWidgetId) {
        Intent intent = new Intent(context, TodayCourseWidget.class);
        intent.setAction(ACTION_ITEM);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        return PendingIntent.getBroadcast(context, 5000 + appWidgetId, intent,
                mutableFlags() | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent openAppIntent(Context context, int appWidgetId) {
        Intent intent = new Intent(context, TodayCourseWidget.class);
        intent.setAction(ACTION_OPEN_APP);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        return PendingIntent.getBroadcast(context, 6000 + appWidgetId, intent,
                immutableFlags() | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void openTimetable(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(EXTRA_OPEN_TIMETABLE, true);
        try {
            context.startActivity(intent);
        } catch (Exception ignored) {
            // 启动器上下文偶发无法启动时忽略，不影响卡片渲染。
        }
    }

    private static int mutableFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE : 0;
    }

    private static int immutableFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) return;
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, TodayCourseWidget.class));
        for (int id : ids) updateWidget(context, manager, id);
    }
}
