package com.checktodo.app.notification;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import com.checktodo.app.MainActivity;
import com.checktodo.app.R;
import com.checktodo.app.data.TaskRepository;
import com.checktodo.app.data.WeeklyReportStore;
import com.checktodo.app.domain.ReminderLogic;
import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.Todo;
import com.checktodo.app.domain.WeeklyReport;

import java.util.Calendar;
import java.util.Date;

/**
 * 提醒广播接收器：闹钟到期 → 发通知；BOOT_COMPLETED / 每日翻日 → 全量重算调度。
 * receiver 设为 exported=false：系统广播（BOOT_COMPLETED）仍可送达，
 * 第三方 App 无法伪造 REMINDER 广播。
 */
public final class ReminderReceiver extends BroadcastReceiver {
    private static final String TAG = "CheckTodoReminder";
    /** 与任务数据同一 prefs 文件，但用独立键（周报幂等标记）。 */
    private static final String PREFS_NAME = "check-todo";
    /** 已生成周报的最近一周（周一键）：防止翻日闹钟重复触发时重复打模型。 */
    private static final String KEY_LAST_WEEK = "weekly-report.last-week";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null || intent.getAction() == null ? "" : intent.getAction();
        if (ReminderScheduler.ACTION_DAILY.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            ReminderScheduler.rescheduleAll(context);
            // AI 周报：每日翻日时判断「今天是周一」→ 后台生成上一周的周报。
            // 失败静默（只 Log.w），绝不影响提醒重算与打卡流程。
            maybeGenerateWeeklyReport(context);
            return;
        }
        if (!ReminderScheduler.ACTION_REMINDER.equals(action)) return;

        String type = intent.getStringExtra(ReminderScheduler.EXTRA_ITEM_TYPE);
        String id = intent.getStringExtra(ReminderScheduler.EXTRA_ITEM_ID);
        if (id == null || id.isEmpty()) return;
        if (ReminderScheduler.TYPE_TASK.equals(type)) {
            notifyTask(context, id);
        } else if (ReminderScheduler.TYPE_TODO.equals(type)) {
            notifyTodo(context, id);
        } else if (ReminderScheduler.TYPE_TEST.equals(type)) {
            notifyTest(context);
        }
    }

    /**
     * 周一生成上一周的 AI 周报（AI 周报功能）。
     *
     * <p><b>线程纪律</b>：BroadcastReceiver 的 onReceive 在主线程且有 10s 上限，
     * 网络请求最多 20s → 必须 {@code goAsync()} 拿 PendingResult 并切到后台线程；
     * 生成完（成功或失败）都要 finish()，否则系统认为 receiver 泄漏。</p>
     *
     * <p><b>幂等</b>：翻日闹钟可能因重启/时区变化多次触发 → 用 prefs 记「已生成到哪一周」，
     * 同一周只生成一次。</p>
     */
    private void maybeGenerateWeeklyReport(final Context context) {
        final Date now = new Date();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(now);
        if (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) return;

        final WeeklyReport.Week week = WeeklyReport.previousWeekOf(now);
        final Context appContext = context.getApplicationContext();
        if (week.startKey.equals(appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_WEEK, ""))) {
            Log.i(TAG, "weekly report already generated for " + week.label());
            return;
        }
        final PendingResult pendingResult = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    WeeklyReportStore.Entry entry =
                            WeeklyReportStore.generateAndStore(appContext, week, week.endOfDay());
                    if (entry == null) {
                        // 静默失败：未配 key / 网络异常 / 内容为空都不打扰用户。
                        Log.w(TAG, "weekly report not generated for " + week.label());
                        return;
                    }
                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString(KEY_LAST_WEEK, entry.weekStart).apply();
                    notifyWeeklyReport(appContext, entry);
                } catch (Exception exception) {
                    Log.w(TAG, "weekly report failed: " + exception.getClass().getSimpleName());
                } finally {
                    pendingResult.finish();
                }
            }
        }, "weekly-report").start();
    }

    /** 周报通知：复用提醒渠道，点击打开 MainActivity。 */
    private void notifyWeeklyReport(Context context, WeeklyReportStore.Entry entry) {
        postNotification(context, WeeklyReportStore.NOTIFICATION_ID,
                context.getString(R.string.notification_weekly_title),
                entry.weekLabel() + "\n" + entry.excerpt());
    }

    private void notifyTask(Context context, String taskId) {
        TaskRepository repository = new TaskRepository(context);
        Task task = null;
        for (Task candidate : repository.getTasks()) {
            if (candidate.id.equals(taskId)) {
                task = candidate;
                break;
            }
        }
        // 触发时二次校验：打卡/请假/删除发生在调度之后则静默丢弃，不重复打扰。
        if (task == null) return;
        if (!ReminderLogic.isTaskReminderValidAtTrigger(task, repository.getCompletedDates(task.id), new Date())) return;
        postNotification(context, ReminderScheduler.requestCode(ReminderScheduler.TYPE_TASK, task.id),
                context.getString(R.string.notification_task_title), ReminderLogic.taskBodyText(task));
    }

    private void notifyTodo(Context context, String todoId) {
        TaskRepository repository = new TaskRepository(context);
        Todo todo = null;
        for (Todo candidate : repository.getTodos()) {
            if (candidate.id.equals(todoId)) {
                todo = candidate;
                break;
            }
        }
        if (todo == null || !ReminderLogic.isTodoReminderValidAtTrigger(todo, System.currentTimeMillis())) return;
        postNotification(context, ReminderScheduler.requestCode(ReminderScheduler.TYPE_TODO, todo.id),
                context.getString(R.string.notification_todo_title), ReminderLogic.todoBodyText(todo));
    }

    private void postNotification(Context context, int notificationId, String title, String body) {
        ReminderScheduler.ensureChannel(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "notification blocked: POST_NOTIFICATIONS not granted, id=" + notificationId);
            return;
        }

        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context, notificationId, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, ReminderScheduler.CHANNEL_ID)
                : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(content)
                .setAutoCancel(true)
                .build();
        manager.notify(notificationId, notification);
        Log.i(TAG, "notification posted, id=" + notificationId + ", title=" + title);
    }

    private void notifyTest(Context context) {
        postNotification(context,
                ReminderScheduler.requestCode(ReminderScheduler.TYPE_TEST,
                        ReminderScheduler.TEST_NOTIFICATION_ID),
                context.getString(R.string.notification_test_title),
                context.getString(R.string.notification_test_body));
    }
}
