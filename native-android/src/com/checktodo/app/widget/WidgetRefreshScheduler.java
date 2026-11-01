package com.checktodo.app.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.checktodo.app.data.TaskRepository;
import com.checktodo.app.data.WidgetRefresh;
import com.checktodo.app.domain.WidgetRefreshLogic;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 卡片「按时间实时刷新」的闹钟调度（Android 侧接线，纯计算在
 * {@link WidgetRefreshLogic}）。
 *
 * <p><b>为什么需要（2026-10-27 真机反馈）：</b>卡片状态随时间跃迁（未开始→可打卡→已错过），
 * 但旧实现只有数据变更 / 用户操作 / 30 分钟系统轮询（{@code updatePeriodMillis}，粗粒度
 * 且 Doze 下延迟）才会刷新——到点后卡片仍显示旧状态。</p>
 *
 * <p><b>为什么改成「每边界独立闹钟」（2026-10-28 真机：开始时间能刷、截止时间永不刷）：</b>
 * 单链实现只挂最早的一个边界，下一边界由本次触发续约。只要有一次触发被系统吞掉
 * （Doze 合并、MagicOS 省电、重启后 BOOT_COMPLETED 广播丢失），链条就永久断裂，
 * 越靠后的边界越容易丢。现在 {@link WidgetRefreshLogic#nextRefreshBounds} 返回<b>全部</b>
 * 未来边界，每个边界各挂一个 RTC_WAKEUP 闹钟（requestCode 由边界时刻确定性派生），
 * 一个丢了不影响其余；每轮调度后用 prefs 登记集合取消不再需要的边界——照搬
 * {@code ReminderScheduler} 的 KEY_CODES 模式。</p>
 *
 * <p><b>挂链位置（保留）：</b>{@code ReminderScheduler.rescheduleAll}（所有数据变更的统一
 * 入口）末尾调用本类；{@code CheckTodoWidget} 的 ACTION_REFRESH 分支刷新后再调用一次，
 * 使数据变更导致的边界集合变化能被重建。链条断裂不再致命（独立闹钟已覆盖全部边界）。</p>
 *
 * <p>精确策略与提醒一致：API 31+ 有「闹钟和提醒」权限用 setExactAndAllowWhileIdle，
 * 否则退回 setAndAllowWhileIdle（可能延迟数分钟，不丢失）。</p>
 */
public final class WidgetRefreshScheduler {
    private static final String TAG = "CheckTodoWidgetRefresh";
    private static final String PREFS_NAME = "check-todo-widget-refresh";
    private static final String KEY_CODES = "scheduled_codes";
    /** 独立基址，与提醒条目的 hashCode 派生码互不冲突。 */
    private static final int REQUEST_BASE = 0x5C81D600;

    private WidgetRefreshScheduler() {
    }

    /** 全量重算卡片刷新边界：每个边界一个闹钟，并取消上一轮已失效的边界。 */
    public static void scheduleNext(Context context) {
        if (context == null) return;
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        try {
            TaskRepository repository = new TaskRepository(context);
            Date now = new Date();
            List<Long> bounds = WidgetRefreshLogic.nextRefreshBounds(
                    repository.getTasks(), repository.getTodos(), now, WidgetRefreshLogic.MAX_BOUNDS);
            boolean exact = alarmExact(alarms);
            String mode = alarmMode(alarms);

            Set<String> nextCodes = new HashSet<>();
            List<String> scheduled = new ArrayList<>();
            for (Long at : bounds) {
                int code = requestCodeFor(at);
                nextCodes.add(String.valueOf(code));
                PendingIntent pending = pendingIntent(context, code);
                if (exact) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
                }
                String entry = formatTime(at) + " (" + at + ")";
                scheduled.add(entry);
                Log.i(TAG, "scheduled refresh @" + entry + ", mode=" + mode);
            }
            if (scheduled.isEmpty()) Log.i(TAG, "no refresh boundary to schedule");

            // 取消上一轮登记、本轮已不再需要的边界（时刻已过 / 任务已改 / 待办已完成）。
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Set<String> previous = prefs.getStringSet(KEY_CODES, Collections.<String>emptySet());
            for (String code : previous) {
                if (nextCodes.contains(code)) continue;
                try {
                    alarms.cancel(pendingIntent(context, Integer.parseInt(code)));
                    Log.i(TAG, "cancelled stale refresh requestCode=" + code);
                } catch (NumberFormatException ignored) {
                    // 脏数据跳过即可，不影响本轮调度结果。
                }
            }
            prefs.edit().putStringSet(KEY_CODES, nextCodes).apply();
        } catch (RuntimeException ignored) {
            // 仓库读取失败等异常不阻断调用方（rescheduleAll / 刷新链）。
        }
    }

    /**
     * 边界时刻 → 确定性 requestCode：同一时刻重复调度只覆盖同一闹钟，不会叠加；
     * 不同边界各自独立（这正是「丢一个不拖垮其余」的前提）。
     */
    public static int requestCodeFor(long at) {
        long mixed = at ^ (at >>> 32);
        return REQUEST_BASE + (int) (mixed & 0x00FFFFFF);
    }

    private static boolean alarmExact(AlarmManager alarms) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms();
    }

    /** 与 ReminderScheduler 同口径的诊断文案：exact / inexact。 */
    private static String alarmMode(AlarmManager alarms) {
        return alarmExact(alarms) ? "exact" : "inexact";
    }

    private static PendingIntent pendingIntent(Context context, int requestCode) {
        // Intent 用固定 action + package 保证同一 requestCode 的 PendingIntent 始终等价，
        // 否则 cancel 会因 filterEquals 不等而失效。
        android.content.Intent intent = WidgetRefresh.refreshIntent(context, CheckTodoWidget.class);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static String formatTime(long millis) {
        return new SimpleDateFormat("M月d日 HH:mm:ss", Locale.CHINA).format(new Date(millis));
    }
}
