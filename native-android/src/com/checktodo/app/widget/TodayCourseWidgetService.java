package com.checktodo.app.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.checktodo.app.R;
import com.checktodo.app.data.TimetableRepository;
import com.checktodo.app.domain.TimetableLogic;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 独立「今日课程」卡片的集合视图。
 *
 * <p>取数、排序与文案统一走 {@link CourseWidgetItems}，与三态卡片
 * （{@link CheckTodoWidgetService}）的课表视图保持同一口径。</p>
 */
public final class TodayCourseWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext(), intent);
    }

    private static final class Factory implements RemoteViewsFactory {
        private final Context context;
        private final int appWidgetId;
        private final List<CourseWidgetItems.Item> courses = new ArrayList<>();

        private Factory(Context context, Intent intent) {
            this.context = context;
            this.appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        @Override
        public void onDataSetChanged() {
            courses.clear();
            TimetableRepository repository = new TimetableRepository(context);
            Date now = new Date();
            // 已过滤当前教学周与星期，按开始节次 → level → baseId 排序。
            courses.addAll(CourseWidgetItems.build(
                    TimetableLogic.todayCourses(repository.getDetails(), repository.getConfig(), now),
                    repository.getBases(), repository.getTimeNodes(), now));
        }

        @Override
        public int getCount() {
            return courses.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= courses.size()) return null;
            CourseWidgetItems.Item item = courses.get(position);
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_item);
            views.setTextViewText(R.id.widget_item_title, item.title);
            views.setTextViewText(R.id.widget_item_subtitle, item.subtitle);
            views.setViewVisibility(R.id.widget_item_action, View.GONE);
            views.setTextViewText(R.id.widget_item_action, "");
            Intent fillIn = new Intent();
            fillIn.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            views.setOnClickFillInIntent(R.id.widget_item_root, fillIn);
            return views;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }

        @Override
        public void onCreate() {
        }

        @Override
        public void onDestroy() {
        }
    }
}
