package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TreeSet;

/**
 * 桌面卡片「按时间实时刷新」的纯函数核心。
 *
 * <p><b>问题（2026-10-27 真机反馈）：</b>卡片状态会随时间自动跃迁（未开始→可打卡→已错过），
 * 但只有数据变更或用户点「切换」才触发刷新——到点后卡片仍显示旧状态。</p>
 *
 * <p><b>方案：</b>把「状态跃迁点」显式化为刷新边界，用 AlarmManager 在边界时刻唤醒刷新：</p>
 * <ul>
 *   <li>每个<b>今日重复日</b>任务的 startTime（未开始→可打卡）与 endTime（可打卡→已错过）；</li>
 *   <li>每条<b>未完成待办</b>的 endTime（进行中→已过期，仅 48h 卡片展示窗口内的有意义）；</li>
 *   <li>次日 00:00（翻日：任务集、日期键、今日视图整体轮换）。</li>
 * </ul>
 *
 * <p>调度方在每次触发后重算下一个边界，因此本函数只需覆盖「今天」的边界 + 翻日点；
 * 无任何边界时返回 -1，由 {@code updatePeriodMillis}（30 分钟）兜底。</p>
 *
 * <p>纯 Java、无 Android 依赖，可在 JVM 直跑单测。</p>
 */
public final class WidgetRefreshLogic {
    /** 待办边界扫描窗口：与 TodoLogic.WIDGET_UPCOMING_WINDOW_MS 同口径（48 小时）。 */
    public static final long TODO_HORIZON_MS = 48L * 60 * 60 * 1000;
    /** 单轮挂钟上限：防止异常数据（成百上千个边界）把系统闹钟表撑爆。 */
    public static final int MAX_BOUNDS = 32;

    private WidgetRefreshLogic() {
    }

    /**
     * 下一个应刷新卡片的时刻（epoch 毫秒）；没有未来边界时返回 -1。
     * 严格晚于 {@code now} 的边界才参与（恰好等于 now 的边界由本次触发自身覆盖）。
     *
     * <p>兼容入口：等价于 {@link #nextRefreshBounds} 的首个元素。新调度方应使用列表版本。</p>
     */
    public static long nextRefreshAt(List<Task> tasks, List<Todo> todos, Date now) {
        long earliest = -1L;
        if (tasks != null) {
            for (Task task : tasks) {
                if (task == null || !TaskLogic.isScheduled(task, now)) continue;
                earliest = min(earliest, todayBoundary(now, TaskLogic.parseTime(task.startTime)));
                earliest = min(earliest, todayBoundary(now, TaskLogic.parseTime(task.endTime)));
            }
        }
        if (todos != null) {
            long horizon = now.getTime() + TODO_HORIZON_MS;
            for (Todo todo : todos) {
                if (todo == null || todo.completed) continue;
                if (todo.endTime > now.getTime() && todo.endTime <= horizon) {
                    earliest = min(earliest, todo.endTime);
                }
            }
        }
        return min(earliest, nextMidnight(now));
    }

    /**
     * 未来的<b>全部</b>刷新边界（2026-10-28 结构性加固）：今日各重复日任务的
     * startTime / endTime、48h 内未完成待办的 endTime、次日 00:00，按键值升序去重，
     * 最多 {@code cap} 个。
     *
     * <p><b>为什么要有列表版本：</b>旧的单链闹钟只挂最早的一个边界，下一次闹钟由本次
     * 触发续约。只要有一次触发被系统吞掉（Doze 合并、ROM 省电、重启后广播丢失），
     * 链条就永久断裂，越靠后的边界（如任务截止）越容易丢——表现为「开始时间能刷、
     * 截止时间永远不刷」。改为每个边界各挂一个独立闹钟后，单个边界丢失不再拖垮其余。</p>
     *
     * <p>翻日点恒定包含在内：它同时是「次日任务集轮换」的兜底，不能被 {@code cap} 挤掉——
     * 超出上限时丢弃最靠后的普通边界，保留翻日点。</p>
     */
    public static List<Long> nextRefreshBounds(List<Task> tasks, List<Todo> todos, Date now, int cap) {
        int limit = cap <= 0 ? 1 : cap;
        TreeSet<Long> bounds = new TreeSet<>();
        if (tasks != null) {
            for (Task task : tasks) {
                if (task == null || !TaskLogic.isScheduled(task, now)) continue;
                addIfFuture(bounds, todayBoundary(now, TaskLogic.parseTime(task.startTime)));
                addIfFuture(bounds, todayBoundary(now, TaskLogic.parseTime(task.endTime)));
            }
        }
        if (todos != null) {
            long horizon = now.getTime() + TODO_HORIZON_MS;
            for (Todo todo : todos) {
                if (todo == null || todo.completed) continue;
                if (todo.endTime > now.getTime() && todo.endTime <= horizon) {
                    addIfFuture(bounds, todo.endTime);
                }
            }
        }
        long midnight = nextMidnight(now);
        // 普通边界最多 limit-1 个，翻日点恒占最后一格。
        int plainLimit = Math.max(0, limit - 1);
        TreeSet<Long> plain = new TreeSet<>(bounds);
        plain.remove(midnight);
        List<Long> result = new ArrayList<>(limit);
        while (result.size() < plainLimit && !plain.isEmpty()) {
            result.add(plain.pollFirst());
        }
        if (result.size() < limit) result.add(midnight);
        return result;
    }

    private static void addIfFuture(TreeSet<Long> bounds, long at) {
        if (at > 0) bounds.add(at);
    }

    private static long min(long a, long b) {
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    /** 今天 minuteOfDay 对应的时刻；已过（≤ now）返回 -1。 */
    private static long todayBoundary(Date now, int minuteOfDay) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(now);
        calendar.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        calendar.set(Calendar.MINUTE, minuteOfDay % 60);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long at = calendar.getTimeInMillis();
        return at > now.getTime() ? at : -1L;
    }

    /** 次日 00:00:00.000。 */
    private static long nextMidnight(Date now) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(now);
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
