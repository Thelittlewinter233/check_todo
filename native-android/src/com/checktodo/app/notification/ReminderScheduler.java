package com.checktodo.app.notification;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import com.checktodo.app.R;
import com.checktodo.app.data.TaskRepository;
import com.checktodo.app.domain.ReminderLogic;
import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.Todo;

import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 提醒调度器：纯框架 AlarmManager + NotificationChannel，零第三方依赖。
 *
 * <p>调度规则：只对「今天需要、未完成、未请假、提醒时刻在未来」的条目注册一次性闹钟。
 * 每次数据变更（TaskRepository.persist）后全量重算，并取消已不再需要的 requestCode，
 * 因此打卡、完成待办、请假、删除、编辑都会自动同步。</p>
 *
 * <p>降级取舍：API 31+ 先查 canScheduleExactAlarms()，允许则用 setExactAndAllowWhileIdle
 * （精确到分钟，Doze 下也能唤醒）；无「闹钟和提醒」权限时退回 setAndAllowWhileIdle，
 * 系统会把触发时刻并入维护窗口，可能延后若干分钟，但不会丢失提醒。</p>
 */
public final class ReminderScheduler {
    public static final String CHANNEL_ID = "reminders";
    public static final String ACTION_REMINDER = "com.checktodo.app.action.REMINDER";
    public static final String ACTION_DAILY = "com.checktodo.app.action.REMINDER_DAILY";
    public static final String EXTRA_ITEM_TYPE = "itemType";
    public static final String EXTRA_ITEM_ID = "itemId";
    public static final String TYPE_TASK = "task";
    public static final String TYPE_TODO = "todo";
    public static final String TYPE_TEST = "test";
    public static final String TEST_NOTIFICATION_ID = "test-notification";

    private static final String PREFS_NAME = "check-todo-reminders";
    private static final String KEY_CODES = "scheduled_codes";
    /** 每日翻日兜底闹钟的固定 requestCode（与条目派生的 requestCode 相互独立）。 */
    private static final int DAILY_REQUEST_CODE = 0x51D2B0A9;
    private static final String TAG = "CheckTodoReminder";
    private static final long TEST_NOTIFICATION_DELAY_MS = 5_000L;

    private ReminderScheduler() {
    }

    /** API 26+ 创建高重要性提醒渠道：系统默认通知声音 + 震动 + 呼吸灯。 */
    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.channel_reminders),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.channel_reminders_description));
        channel.enableLights(true);
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 200, 100, 200});
        channel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        manager.createNotificationChannel(channel);
    }

    /** 全量重算：调度仍需要的条目，取消不再需要的条目，并注册次日翻日兜底闹钟。 */
    public static void rescheduleAll(Context context) {
        if (context == null) return;
        ensureChannel(context);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;

        Log.i(TAG, "rescheduleAll: notifications="
                + (canPostNotifications(context) ? "allowed" : "blocked")
                + ", alarms=" + alarmMode(alarms));

        TaskRepository repository = new TaskRepository(context);
        Date now = new Date();
        Set<String> nextCodes = new HashSet<>();
        List<String> scheduled = new ArrayList<>();

        for (Task task : repository.getTasks()) {
            if (!ReminderLogic.isTaskReminderEligible(task, repository.getCompletedDates(task.id), now)) continue;
            long at = ReminderLogic.resolveTaskScheduleTime(task, repository.getCompletedDates(task.id), now);
            if (at == ReminderLogic.NOT_SCHEDULED) continue;
            int code = schedule(context, alarms, TYPE_TASK, task.id, at);
            nextCodes.add(String.valueOf(code));
            String entry = "task " + task.name + " (" + task.id + ") @ " + formatTime(at);
            scheduled.add(entry);
            Log.i(TAG, "scheduled " + entry + ", alarm=" + alarmMode(alarms)
                    + (at <= now.getTime() + ReminderLogic.IMMEDIATE_REMINDER_DELAY_MS ? ", immediate" : ""));
        }
        for (Todo todo : repository.getTodos()) {
            if (!ReminderLogic.isTodoReminderEligible(todo, now.getTime())) continue;
            long at = ReminderLogic.resolveTodoScheduleTime(todo, now.getTime());
            if (at == ReminderLogic.NOT_SCHEDULED) continue;
            int code = schedule(context, alarms, TYPE_TODO, todo.id, at);
            nextCodes.add(String.valueOf(code));
            String entry = "todo " + todo.title + " (" + todo.id + ") @ " + formatTime(at);
            scheduled.add(entry);
            Log.i(TAG, "scheduled " + entry + ", alarm=" + alarmMode(alarms)
                    + (at <= now.getTime() + ReminderLogic.IMMEDIATE_REMINDER_DELAY_MS ? ", immediate" : ""));
        }
        if (scheduled.isEmpty()) {
            Log.i(TAG, "no future reminders to schedule");
        }

        // 取消上一轮登记但本轮不再需要的提醒（已完成、已请假、已删除、时刻已过）。
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> previous = prefs.getStringSet(KEY_CODES, Collections.<String>emptySet());
        for (String code : previous) {
            if (nextCodes.contains(code)) continue;
            try {
                alarms.cancel(pendingIntent(context, Integer.parseInt(code), null, null));
            } catch (NumberFormatException ignored) {
                // 脏数据跳过即可，不影响本轮调度结果。
            }
        }
        prefs.edit().putStringSet(KEY_CODES, nextCodes).apply();
        scheduleDaily(context, alarms, now);
        // 卡片实时刷新（2026-10-27）：rescheduleAll 是所有数据变更的统一入口，
        // 在这里顺带把「下一个状态跃迁边界」的刷新闹钟续上。
        com.checktodo.app.widget.WidgetRefreshScheduler.scheduleNext(context);
    }

    /** 发送测试通知：5 秒后走与真实提醒完全一致的 Alarm → Receiver → Channel 链路。 */
    public static void scheduleTestNotification(Context context) {
        if (context == null) return;
        ensureChannel(context);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        long at = System.currentTimeMillis() + TEST_NOTIFICATION_DELAY_MS;
        schedule(context, alarms, TYPE_TEST, TEST_NOTIFICATION_ID, at);
        Log.i(TAG, "scheduled test:test-notification @ " + formatTime(at));
    }

    /** 单条调度：requestCode 由 type + id 派生，PendingIntent 使用 FLAG_IMMUTABLE。 */
    private static int schedule(Context context, AlarmManager alarms, String type, String id, long at) {
        int requestCode = requestCode(type, id);
        // API 31 之前系统允许精确闹钟；API 31+ 无权限时退回 inexact。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at,
                    pendingIntent(context, requestCode, type, id));
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at,
                    pendingIntent(context, requestCode, type, id));
        }
        return requestCode;
    }

    private static String alarmMode(AlarmManager alarms) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
                ? "exact" : "inexact";
    }

    private static boolean canPostNotifications(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static String formatTime(long millis) {
        return new SimpleDateFormat("M月d日 HH:mm:ss", Locale.CHINA).format(new Date(millis));
    }

    /** 次日 00:00 翻日兜底：重算全部提醒，接管新一天的任务与截止日在明天的待办。 */
    private static void scheduleDaily(Context context, AlarmManager alarms, Date now) {
        Calendar nextMidnight = Calendar.getInstance();
        nextMidnight.setTime(now);
        nextMidnight.add(Calendar.DAY_OF_MONTH, 1);
        nextMidnight.set(Calendar.HOUR_OF_DAY, 0);
        nextMidnight.set(Calendar.MINUTE, 0);
        nextMidnight.set(Calendar.SECOND, 0);
        nextMidnight.set(Calendar.MILLISECOND, 0);

        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.setAction(ACTION_DAILY);
        PendingIntent pending = PendingIntent.getBroadcast(context, DAILY_REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    nextMidnight.getTimeInMillis(), pending);
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    nextMidnight.getTimeInMillis(), pending);
        }
    }

    /** 条目 requestCode：type:id 的 hashCode，同一 id 重复调度会覆盖旧闹钟而不是叠加。 */
    public static int requestCode(String type, String id) {
        return (type + ":" + id).hashCode();
    }

    private static PendingIntent pendingIntent(Context context, int requestCode, String type, String id) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.setAction(ACTION_REMINDER);
        if (type != null) intent.putExtra(EXTRA_ITEM_TYPE, type);
        if (id != null) intent.putExtra(EXTRA_ITEM_ID, id);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
