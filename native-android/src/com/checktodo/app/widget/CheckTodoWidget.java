package com.checktodo.app.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

import com.checktodo.app.R;
import com.checktodo.app.data.TaskRepository;
import com.checktodo.app.data.WidgetRefresh;
import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.Todo;

import java.util.Date;
import java.util.Set;

/**
 * 桌面小卡片：打卡 / 待办 / 今日课程三视图。
 *
 * <p>「切换」按钮按 打卡 → 待办 → 课表 → 打卡 循环，当前视图按 widgetId 存在
 * {@code check-todo-widget} 的 {@code mode.<id>}（默认 {@link #MODE_TASKS}），
 * 刷新（含开机、重建、数据变更广播）后仍保持。</p>
 *
 * <p>交互链（targetSdk 34 下必须完整）：</p>
 * <ol>
 *   <li>{@link #updateWidget} 对集合视图设置 template PendingIntent（FLAG_MUTABLE）；</li>
 *   <li>{@link CheckTodoWidgetService.Factory} 对每个条目 setOnClickFillInIntent(fillInIntent)；</li>
 *   <li>用户在条目上点击时，系统把 fillInIntent 合入 template 并发出广播；</li>
 *   <li>{@link #onReceive} 处理 {@link #ACTION_ITEM}，写数据并立刻刷新所有卡片实例。</li>
 * </ol>
 *
 * <p>课表视图条目只做跳转（打开 App 课表页），不带条目 ID，因此复用同一 template；
 * 点击时携带 {@link #TYPE_COURSE} 由 {@link #onReceive} 分发。</p>
 */
public class CheckTodoWidget extends AppWidgetProvider {
    public static final int MODE_TASKS = WidgetModes.MODE_TASKS;
    public static final int MODE_TODOS = WidgetModes.MODE_TODOS;
    public static final int MODE_COURSES = WidgetModes.MODE_COURSES;

    static final String ACTION_SWITCH = "com.checktodo.app.action.WIDGET_SWITCH";
    static final String ACTION_ITEM = "com.checktodo.app.action.WIDGET_ITEM";
    static final String ACTION_CLICK_ITEM = "com.checktodo.app.action.WIDGET_CLICK_ITEM";
    static final String ACTION_OPEN_APP = "com.checktodo.app.action.WIDGET_OPEN_APP";
    static final String EXTRA_APP_WIDGET_ID = "appWidgetId";
    static final String EXTRA_ITEM_TYPE = "itemType";
    static final String EXTRA_ITEM_ID = "itemId";
    /** 条目所属的视图模式：Factory 写入快照模式，Provider 校验后才处理（丢弃旧模式残留点击）。 */
    static final String EXTRA_MODE = "itemMode";
    /** 未携带 EXTRA_MODE 的老卡片（升级前生成的 RemoteViews）按 -1 处理，走兼容分支。 */
    static final int MODE_UNKNOWN = -1;
    static final String TYPE_TODO = "todo";
    static final String TYPE_TASK = "task";
    static final String TYPE_COURSE = "course";
    /** 已完成条目点击后，交给 MainActivity 弹出「取消今日打卡？」确认框。 */
    public static final String EXTRA_CANCEL_TASK_ID = "cancel_task_id";
    /**
     * AI 拍照判定任务的可打卡条目点击后，交给 MainActivity 启动拍照判定流程
     * （相机 → L1 预检 → L2 判定）；小卡片不能直接写库。
     */
    public static final String EXTRA_AI_CHECKIN_TASK_ID = "ai_checkin_task_id";

    private static final String PREFS_NAME = "check-todo-widget";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) updateWidget(context, appWidgetManager, appWidgetId);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        WidgetModeStore store = modeStore(context);
        for (int appWidgetId : appWidgetIds) store.remove(appWidgetId);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_SWITCH.equals(action)) {
            int appWidgetId = intent.getIntExtra(EXTRA_APP_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                // 以「该代 RemoteViews 渲染所见的模式」为基准 +1，而不是存储值 +1。
                // 启动器保留旧一代渲染时，用户看到的模式才是他要继续轮转的那一格；
                // 用存储值 +1 会导致每张卡整体错一位且永远自愈不了（见 WidgetSwitchLogic）。
                int seenMode = intent.getIntExtra(EXTRA_MODE, MODE_UNKNOWN);
                WidgetSwitchLogic.apply(modeStore(context), appWidgetId, seenMode);
            }
            // 切换后集合视图的 fillInIntent 语义也变了，必须整卡重建再刷新数据。
            updateAll(context);
            return;
        }
        if (ACTION_CLICK_ITEM.equals(action)) {
            // 单独点击条目：课表条目直接进课表页，其余维持原有打开 App 行为。
            if (TYPE_COURSE.equals(intent.getStringExtra(EXTRA_ITEM_TYPE))) {
                openTimetable(context);
            } else {
                handleItemAction(context, intent);
            }
            updateAll(context);
            return;
        }
        if (ACTION_ITEM.equals(action)) {
            int appWidgetId = intent.getIntExtra(EXTRA_APP_WIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            // 纪律 2：校验点击携带的模式与当前模式一致，否则丢弃。
            // 场景：用户刚点到「课表」视图，而启动器还渲染着上一代 RemoteViews，
            // 此时点到的行属于旧模式的 itemId，直接执行会「用待办 id 去打卡」。
            int clickedMode = intent.getIntExtra(EXTRA_MODE, MODE_UNKNOWN);
            if (clickedMode != MODE_UNKNOWN && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                int currentMode = getMode(context, appWidgetId);
                WidgetFactorySnapshot current = WidgetFactorySnapshot.of(appWidgetId, currentMode);
                if (!current.accepts(clickedMode)) {
                    // 旧代点击 -> 立即以存储模式整卡重建，把启动器的渲染拉回权威状态。
                    updateAll(context);
                    return;
                }
            }
            handleItemAction(context, intent);
            updateAll(context);
            return;
        }
        if (ACTION_OPEN_APP.equals(action)) {
            openApp(context);
            return;
        }
        if (WidgetRefresh.ACTION_REFRESH.equals(action)) {
            updateAll(context);
            // 实时刷新链（2026-10-27）：刷新完立刻续约下一个状态跃迁边界，
            // 使「未开始→可打卡→已错过」等时间跃迁能自动反映到卡片上。
            WidgetRefreshScheduler.scheduleNext(context);
            return;
        }
        super.onReceive(context, intent);
    }

    private static Class<?> getMainActivityClass(Context context) {
        try {
            return Class.forName(context.getPackageName() + ".MainActivity");
        } catch (ClassNotFoundException ignored) {
            throw new IllegalStateException("MainActivity is missing from the manifest");
        }
    }

    static int getMode(Context context, int appWidgetId) {
        return modeStore(context).read(appWidgetId);
    }

    private static void handleItemAction(Context context, Intent intent) {
        String id = intent.getStringExtra(EXTRA_ITEM_ID);
        if (id == null || id.isEmpty()) {
            // 无操作行同样整行可点：没有条目数据时直接打开 App。
            openApp(context);
            return;
        }
        String type = intent.getStringExtra(EXTRA_ITEM_TYPE);
        if (TYPE_COURSE.equals(type)) {
            openTimetable(context);
            return;
        }
        TaskRepository repository = new TaskRepository(context);
        if (TYPE_TODO.equals(type)) {
            for (Todo todo : repository.getTodos()) {
                if (todo.id.equals(id)) {
                    // AI 拍照待办：卡片不能直接写库，打开 App 走「拍照 → L1 → GLM 判定」链路。
                    WidgetCheckInRouting.Action decision = WidgetCheckInRouting.decideTodo(todo);
                    if (decision == WidgetCheckInRouting.Action.TOGGLE_TODO) {
                        repository.setTodoCompleted(id, !todo.completed);
                    } else if (decision == WidgetCheckInRouting.Action.OPEN_APP_AI_TODO) {
                        openApp(context);
                    }
                    break;
                }
            }
            return;
        }
        if (!TYPE_TASK.equals(type)) return;

        Date now = new Date();
        Task target = null;
        for (Task task : repository.getTasks()) {
            if (task.id.equals(id)) {
                target = task;
                break;
            }
        }
        if (target == null) return;

        Set<String> completed = repository.getCompletedDates(id);
        // 路由决策是纯函数（WidgetCheckInRouting，JVM 可测）：AI 拍照任务必须回 App
        // 走「相机 → L1 预检 → L2 判定」链路，卡片不能直接写库；普通任务打卡与
        // 「已完成 → 取消确认」两条既有路径保持不变。
        WidgetCheckInRouting.Action action = WidgetCheckInRouting.decide(target, completed, now);
        switch (action) {
            case CHECK_IN:
                repository.checkIn(id, now);
                return;
            case OPEN_APP_AI_CHECKIN:
                openAppForAiCheckIn(context, id);
                return;
            case OPEN_APP_CANCEL:
                // 小卡片不能弹 Dialog：把确认动作转交主界面，保持与 App 内一致的防误点链路。
                openAppForCancel(context, id);
                return;
            case IGNORE:
            default:
                return;
        }
    }

    private static void openApp(Context context) {
        Intent open = new Intent(context, getMainActivityClass(context));
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(open);
        } catch (Exception ignored) {
            // 无界面上下文时兼容失败，不影响卡片本身。
        }
    }

    /** 课表条目点击：复用「今日课程」卡片的 EXTRA_OPEN_TIMETABLE 跳转思路，直接进入课表页。 */
    private static void openTimetable(Context context) {
        Intent open = new Intent(context, getMainActivityClass(context));
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra(TodayCourseWidget.EXTRA_OPEN_TIMETABLE, true);
        try {
            context.startActivity(open);
        } catch (Exception ignored) {
            // 无界面上下文时兼容失败，不影响卡片本身。
        }
    }

    private static void openAppForCancel(Context context, String taskId) {
        Intent open = new Intent(context, getMainActivityClass(context));
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra(EXTRA_CANCEL_TASK_ID, taskId);
        try {
            context.startActivity(open);
        } catch (Exception ignored) {
            // 无界面上下文时兼容失败，不影响卡片本身。
        }
    }

    /**
     * AI 拍照判定任务的打卡入口：打开 App 并携带任务 id，由 MainActivity 启动
     * 相机 → L1 预检 → L2 判定链路。卡片侧不写库、不弹框。
     */
    private static void openAppForAiCheckIn(Context context, String taskId) {
        Intent open = new Intent(context, getMainActivityClass(context));
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra(EXTRA_AI_CHECKIN_TASK_ID, taskId);
        try {
            context.startActivity(open);
        } catch (Exception ignored) {
            // 无界面上下文时兼容失败，不影响卡片本身。
        }
    }

    private static void updateWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        if (appWidgetManager == null) return;
        int mode = getMode(context, appWidgetId);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_checktodo);
        views.setTextViewText(R.id.widget_title, WidgetModes.title(mode));
        views.setTextViewText(R.id.widget_empty, WidgetModes.emptyText(mode));

        // 切换按钮使用独立的 setOnClickPendingIntent（不经过集合视图模板）。
        // 必须带上这一代渲染所见的 mode：启动器可能保留旧一代 RemoteViews，
        // Provider 只有知道「用户看的是哪一格」才能正确 +1（见 WidgetSwitchLogic）。
        views.setOnClickPendingIntent(R.id.widget_switch, switchIntent(context, appWidgetId, mode));
        views.setOnClickPendingIntent(R.id.widget_title, openAppIntent(context, appWidgetId));

        Intent serviceIntent = new Intent(context, CheckTodoWidgetService.class);
        serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        // data 必须唯一且随模式变化：切换视图后强制重建 Factory，避免沿用旧模式的条目缓存。
        serviceIntent.setData(android.net.Uri.parse("checktodo://widget/" + appWidgetId + "/mode/" + mode));
        views.setRemoteAdapter(R.id.widget_list, serviceIntent);
        views.setEmptyView(R.id.widget_list, R.id.widget_empty);
        // 关键修复：template 必须可写，否则系统无法把 fillInIntent 合入模板，点击无反应。
        views.setPendingIntentTemplate(R.id.widget_list, templateIntent(context, appWidgetId));

        appWidgetManager.updateAppWidget(appWidgetId, views);
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list);
    }

    /** 集合视图模板：广播型，必须 FLAG_MUTABLE（API 31+）。 */
    static PendingIntent templateIntent(Context context, int appWidgetId) {
        Intent intent = new Intent(context, CheckTodoWidget.class);
        intent.setAction(ACTION_ITEM);
        intent.putExtra(EXTRA_APP_WIDGET_ID, appWidgetId);
        // requestCode 每个卡片唯一，data 唯一，避免 PendingIntent 互相覆盖。
        return PendingIntent.getBroadcast(context, 1000 + appWidgetId, intent,
                mutableFlags() | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * 切换按钮：独立广播 PendingIntent，显式组件保证 manifest 注册的 receiver 能收到。
     *
     * <p>{@code seenMode} 是这一代 RemoteViews 正在渲染的模式。requestCode 与 data 都按
     * {@code (id, mode)} 唯一：不同代拿到的是不同的 PendingIntent 身份，某些启动器会把
     * 同身份但 extra 不同的旧 PendingIntent 缓存复用，按模式区分 identity 可以避免
     * 「点了却按上一代的模式推进」。</p>
     */
    static PendingIntent switchIntent(Context context, int appWidgetId, int seenMode) {
        int mode = WidgetModes.normalize(seenMode);
        Intent intent = new Intent(context, CheckTodoWidget.class);
        intent.setAction(ACTION_SWITCH);
        intent.putExtra(EXTRA_APP_WIDGET_ID, appWidgetId);
        intent.putExtra(EXTRA_MODE, mode);
        intent.setData(android.net.Uri.parse("checktodo://widget/" + appWidgetId + "/switch/" + mode));
        return PendingIntent.getBroadcast(context, 2000 + appWidgetId * 10 + mode, intent,
                immutableFlags() | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** 点击标题打开 App：广播形态，兼容 setOnClickPendingIntent 在部分定制 ROM 上的限制。 */
    static PendingIntent openAppIntent(Context context, int appWidgetId) {
        Intent intent = new Intent(context, CheckTodoWidget.class);
        intent.setAction(ACTION_OPEN_APP);
        intent.putExtra(EXTRA_APP_WIDGET_ID, appWidgetId);
        return PendingIntent.getBroadcast(context, 3000 + appWidgetId, intent,
                immutableFlags() | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * API 31+ 需要显式声明可变性。集合视图 template 必须 MUTABLE，
     * 其余（切换/打开）使用 IMMUTABLE 更安全，且它们不需要填充额外数据。
     */
    static int mutableFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
    }

    static int immutableFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static WidgetModeStore modeStore(Context context) {
        return new PrefsModeStore(prefs(context));
    }

    /** SharedPreferences 侧的按实例持久化，键规则与内存实现共用。 */
    private static final class PrefsModeStore implements WidgetModeStore {
        private final SharedPreferences prefs;

        PrefsModeStore(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        @Override
        public int read(int appWidgetId) {
            return WidgetModes.normalize(
                    prefs.getInt(WidgetModes.modeKey(appWidgetId), WidgetModes.MODE_TASKS));
        }

        @Override
        public void write(int appWidgetId, int mode) {
            prefs.edit().putInt(WidgetModes.modeKey(appWidgetId), WidgetModes.normalize(mode))
                    .apply();
        }

        @Override
        public void remove(int appWidgetId) {
            prefs.edit().remove(WidgetModes.modeKey(appWidgetId)).apply();
        }
    }

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) return;
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, CheckTodoWidget.class));
        for (int id : ids) updateWidget(context, manager, id);
    }
}
