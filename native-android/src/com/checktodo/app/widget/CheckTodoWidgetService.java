package com.checktodo.app.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.checktodo.app.R;
import com.checktodo.app.data.TaskRepository;
import com.checktodo.app.data.TimetableRepository;
import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.TaskLogic;
import com.checktodo.app.domain.TaskStatus;
import com.checktodo.app.domain.TimetableLogic;
import com.checktodo.app.domain.Todo;
import com.checktodo.app.domain.TodoLogic;
import com.checktodo.app.domain.TodoStatus;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CheckTodoWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext(), intent);
    }

    static final class Factory implements RemoteViewsService.RemoteViewsFactory {
        private final Context context;
        private final int appWidgetId;
        /**
         * 构造时冻结的快照；之后只读。
         *
         * <p>旧实现（{@link WidgetFactoryState}）允许 onDataSetChanged 用实时存储模式覆盖它，
         * 造成「行数已切换到新模式、列表内容还是旧模式」的中间态——这就是"偶发串状态"的根因。</p>
         */
        private volatile WidgetFactorySnapshot snapshot;
        /** 内容快照：只在 onDataSetChanged 里整体替换，getViewAt 只读。 */
        private volatile WidgetFactorySnapshot.Rows<Row> rows =
                WidgetFactorySnapshot.Rows.of(WidgetModes.MODE_TASKS, new ArrayList<Row>());

        private Factory(Context context, Intent intent) {
            this.context = context;
            this.appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            // 构造即冻结：模式从 URI + 存储一次性确定，此后不再读实时状态。
            int stored = appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID
                    ? WidgetModes.MODE_TASKS : CheckTodoWidget.getMode(context, appWidgetId);
            this.snapshot = WidgetFactorySnapshot.freeze(appWidgetId, intent.getDataString(), stored);
        }

        /** 一行渲染所需的全部数据：在 onDataSetChanged 时一并算好，getViewAt 不再查仓库。 */
        static final class Row {
            final String type;
            final String title;
            final String subtitle;
            final String action;
            final String itemId;

            Row(String type, String title, String subtitle, String action, String itemId) {
                this.type = type;
                this.title = title;
                this.subtitle = subtitle;
                this.action = action;
                this.itemId = itemId;
            }
        }

        @Override
        public void onCreate() {
        }

        @Override
        public void onDataSetChanged() {
            // 世代一致性（2026-10-26 真机「标题课表对上打卡正文」的根因修复）：
            // 正文永远按构造时冻结的模式重建——该模式来自服务 URI，与启动器正在显示的
            // 标题、切换按钮是同一代 updateWidget 的产物。绝不在这里实时读存储：
            // 存储在「标题上屏」与「正文重建」之间可能已被下一次切换推进，读它会把
            // 正文拽到标题之外的世代。数据新鲜度由 buildRows 重新查仓库保证；
            // 世代推进只发生在启动器按新 data URI 重绑（新 Factory 冻结新模式）。
            List<Row> built = buildRows(snapshot.mode());
            // 先建内容、后换快照：两步都在本方法内，宿主读到的永远是「行数 = 内容长度」。
            rows = WidgetFactorySnapshot.Rows.of(snapshot.mode(), built);
            snapshot = snapshot.bind(built.size());
        }

        /** 按快照模式一次性重建内容；此方法只在 onDataSetChanged 里被调用。 */
        private List<Row> buildRows(int mode) {
            List<Row> out = new ArrayList<>();
            Date now = new Date();
            if (mode == CheckTodoWidget.MODE_COURSES) {
                TimetableRepository timetable = new TimetableRepository(context);
                List<CourseWidgetItems.Item> courses = CourseWidgetItems.build(
                        TimetableLogic.todayCourses(timetable.getDetails(), timetable.getConfig(), now),
                        timetable.getBases(), timetable.getTimeNodes(), now);
                for (CourseWidgetItems.Item item : courses) {
                    out.add(new Row(CheckTodoWidget.TYPE_COURSE, item.title, item.subtitle,
                            "课表", ""));
                }
                return out;
            }
            TaskRepository repository = new TaskRepository(context);
            if (mode == CheckTodoWidget.MODE_TODOS) {
                // 小卡片过滤：已过期与进行中全部显示；未开始只显示 48 小时内开始的；
                // 已完成不再显示（2026-10-28 需求变更：完成即消失，撤销回 App 内做）。
                List<Todo> todos = TodoLogic.filterWidgetTodos(repository.getTodos(), now.getTime());
                for (Todo todo : todos) {
                    out.add(new Row(CheckTodoWidget.TYPE_TODO, todo.title,
                            todoStatusLabel(TodoLogic.status(todo, System.currentTimeMillis()))
                                    + " · 截止 "
                                    + new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
                                    .format(new Date(todo.endTime)),
                            todo.completed ? "撤销" : WidgetCheckInRouting.todoActionLabel(todo), todo.id));
                }
                return out;
            }
            List<Task> tasks = new ArrayList<>();
            Map<String, Set<String>> completedDates = new HashMap<>();
            for (Task task : repository.getTasks()) {
                completedDates.put(task.id, repository.getCompletedDates(task.id));
                if (TaskLogic.isScheduled(task, now)) tasks.add(task);
            }
            // 卡片专用分组序（2026-10-28 真机反馈）：ACTIVE → UPCOMING → DONE → MISSED →
            // LEAVE → OFF，组内按「截止 − 完成所需」升序。App 内今日视图仍走 sortForToday。
            // 排序函数是纯函数（返回新列表、不改参数）——必须接收返回值！
            // 2026-10-29 真机回归「已错过混在中间」的根因正是这里丢弃了返回值，
            // 卡片一直按仓库原始顺序渲染，分组与紧急度完全不生效。
            List<Task> ordered = TaskLogic.sortForWidgetToday(tasks, completedDates, now);
            for (Task task : ordered) {
                Set<String> completed = completedDates.get(task.id);
                if (completed == null) completed = Collections.emptySet();
                TaskStatus status = TaskLogic.taskStatus(task, completed, now);
                // 只有 ACTIVE/DONE 可操作；其余行不带 itemId，点击回落打开 App。
                String itemId = (status == TaskStatus.ACTIVE || status == TaskStatus.DONE)
                        ? task.id : "";
                out.add(new Row(CheckTodoWidget.TYPE_TASK, task.name,
                        task.startTime + " - " + task.endTime, actionLabel(task, status), itemId));
            }
            return out;
        }

        @Override
        public int getCount() {
            // 纪律 1：只读快照。未绑定内容前为 0，宁可空态也不吐旧行。
            return snapshot.loaded() ? snapshot.count() : 0;
        }

        @Override
        public RemoteViews getViewAt(int position) {
            // 纪律 1 + 4：只读快照与已建好的行，绝不在这里查仓库或读实时模式。
            WidgetFactorySnapshot current = snapshot;
            WidgetFactorySnapshot.Rows<Row> currentRows = rows;
            if (!current.loaded() || !currentRows.has(position)) return null;
            if (currentRows.mode() != current.mode()) return null;   // 内容与快照不同代 -> 拒绝渲染
            Row row = currentRows.get(position);
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_item);
            views.setTextViewText(R.id.widget_item_title, row.title);
            views.setTextViewText(R.id.widget_item_subtitle, row.subtitle);
            views.setTextViewText(R.id.widget_item_action, row.action);
            views.setViewVisibility(R.id.widget_item_action, View.VISIBLE);

            // 纪律 2：fillInIntent 必须带快照模式，Provider 据此丢弃旧模式的残留点击。
            Intent fillInIntent = new Intent();
            fillInIntent.putExtra(CheckTodoWidget.EXTRA_APP_WIDGET_ID, appWidgetId);
            fillInIntent.putExtra(CheckTodoWidget.EXTRA_MODE, current.mode());
            fillInIntent.putExtra(CheckTodoWidget.EXTRA_ITEM_TYPE, row.type);
            if (!row.itemId.isEmpty()) {
                fillInIntent.putExtra(CheckTodoWidget.EXTRA_ITEM_ID, row.itemId);
            }
            views.setOnClickFillInIntent(R.id.widget_item_root, fillInIntent);
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
        public void onDestroy() {
        }

        static String statusLabel(TaskStatus status) {
            switch (status) {
                case DONE: return "已完成";
                case MISSED: return "已错过";
                case UPCOMING: return "未开始";
                case LEAVE: return "请假";
                default: return "今日休息";
            }
        }

        static String todoStatusLabel(TodoStatus status) {
            switch (status) {
                case NOT_STARTED: return "未开始";
                case IN_PROGRESS: return "进行中";
                case EXPIRED: return "已过期";
                default: return "已完成";
            }
        }

        /**
         * 可打卡时显示「打卡」，AI 拍照任务显示「拍照打卡」，其余状态显示状态文案。
         * 行本身照常渲染（AI 任务不隐藏），点击由 Provider 的纯函数路由分发。
         */
        private static String actionLabel(Task task, TaskStatus status) {
            if (status == TaskStatus.ACTIVE) return WidgetCheckInRouting.activeActionLabel(task);
            return status == TaskStatus.DONE ? "取消" : statusLabel(status);
        }
    }
}
