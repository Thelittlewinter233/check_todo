package com.checktodo.app.domain;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * 提醒时刻计算（纯 Java，无 Android 依赖，可 JVM 直跑单测）。
 *
 * <p>统一公式：<b>提醒时刻 = 截止时间 − 完成所需时间 − 1 小时缓冲</b>，
 * 即在「最晚需要开始做的时刻」再提前 1 小时提醒。</p>
 * <ul>
 *   <li>打卡任务：截止时间取当天的 {@code endTime}（HH:mm），完成所需时间取 {@code requiredMinutes}；</li>
 *   <li>待办：截止时间取绝对时间戳 {@code endTime}，完成所需时间取 {@code durationMinutes}；</li>
 *   <li>已完成、已请假、今天不需要、已过截止 → 不发；提醒时刻已过但截止未到 → 5 秒后立即提醒。</li>
 * </ul>
 */
public final class ReminderLogic {
    /** 所需时间之外附加的缓冲（分钟）。 */
    public static final int BUFFER_MINUTES = 60;
    /** 完成所需时间的可输入区间。 */
    public static final int MIN_REQUIRED_MINUTES = 1;
    public static final int MAX_REQUIRED_MINUTES = 24 * 60;
    /** 不可调度时的哨兵值。 */
    public static final long NOT_SCHEDULED = Long.MIN_VALUE;
    /** 提醒时刻已过但截止未到时的立即补发延迟。 */
    public static final long IMMEDIATE_REMINDER_DELAY_MS = 5_000L;

    private ReminderLogic() {
    }

    /**
     * 打卡任务今天的提醒时刻（epoch 毫秒）。
     * 计算基于当天零点 + endTime 分钟数 − requiredMinutes − BUFFER_MINUTES；
     * 结果可能落在前一天（如截止 01:00），此时会转为立即提醒。
     */
    public static long computeTaskReminderTime(Task task, Date now) {
        if (task == null || now == null || !isValidTime(task.endTime)) return NOT_SCHEDULED;
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(now);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.MINUTE,
                TaskLogic.parseTime(task.endTime) - requiredMinutes(task) - BUFFER_MINUTES);
        return calendar.getTimeInMillis();
    }

    /** 待办的提醒时刻（epoch 毫秒）：截止时刻 − 花费时长 − 缓冲。 */
    public static long computeTodoReminderTime(Todo todo) {
        if (todo == null) return NOT_SCHEDULED;
        return todo.endTime - (long) requiredTodoMinutes(todo) * 60_000L - (long) BUFFER_MINUTES * 60_000L;
    }

    /**
     * 打卡任务触发前的资格校验：今天需要、未完成、未请假，且截止时间还没到。
     * 这里不再要求计算出的提醒时刻在未来；时刻已过但截止未到时会走立即提醒。
     */
    public static boolean isTaskReminderEligible(Task task, Set<String> completedDates, Date now) {
        if (task == null || now == null || completedDates == null) return false;
        TaskStatus status = TaskLogic.taskStatus(task, completedDates, now);
        if (status != TaskStatus.ACTIVE && status != TaskStatus.UPCOMING) return false;
        return taskDeadline(task, now) > now.getTime();
    }

    /**
     * 解析打卡任务的实际调度时刻：提醒在未来则按公式；提醒已过但截止未到，
     * 则改为 5 秒后的立即提醒；截止已过则不再调度。
     */
    public static long resolveTaskScheduleTime(Task task, Set<String> completedDates, Date now) {
        if (!isTaskReminderEligible(task, completedDates, now)) return NOT_SCHEDULED;
        long reminder = computeTaskReminderTime(task, now);
        if (reminder != NOT_SCHEDULED && reminder > now.getTime()) return reminder;
        return now.getTime() + IMMEDIATE_REMINDER_DELAY_MS;
    }

    /** 闹钟触发时的二次校验：完成、请假、删除、截止已过都会被拦截。 */
    public static boolean isTaskReminderValidAtTrigger(Task task, Set<String> completedDates, Date now) {
        return isTaskReminderEligible(task, completedDates, now);
    }

    /**
     * 打卡任务是否需要调度提醒：今天需要打卡、未完成、未请假、状态仍可打卡或未开始，
     * 且截止时间在未来；提醒时刻已过时会转为立即提醒。
     */
    public static boolean shouldScheduleTask(Task task, Set<String> completedDates, Date now) {
        return resolveTaskScheduleTime(task, completedDates, now) != NOT_SCHEDULED;
    }

    /**
     * 待办是否需要调度提醒：未完成、截止时间在今天且未过；提醒时刻已过时转为立即提醒。
     * 截止日在明天及以后的待办由每日翻日重算接管。
     */
    public static boolean shouldScheduleTodo(Todo todo, long nowMillis) {
        return resolveTodoScheduleTime(todo, nowMillis) != NOT_SCHEDULED;
    }

    /** 待办触发前资格：未完成、截止在今天且还没过。 */
    public static boolean isTodoReminderEligible(Todo todo, long nowMillis) {
        if (todo == null || todo.completed) return false;
        Date now = new Date(nowMillis);
        return TaskLogic.dateKey(new Date(todo.endTime)).equals(TaskLogic.dateKey(now))
                && todo.endTime > nowMillis;
    }

    /** 待办调度时刻：公式时刻未来按公式；已过则 5 秒后立即提醒。 */
    public static long resolveTodoScheduleTime(Todo todo, long nowMillis) {
        if (!isTodoReminderEligible(todo, nowMillis)) return NOT_SCHEDULED;
        long reminder = computeTodoReminderTime(todo);
        if (reminder > nowMillis) return reminder;
        return nowMillis + IMMEDIATE_REMINDER_DELAY_MS;
    }

    /** 待办闹钟触发时的二次校验。 */
    public static boolean isTodoReminderValidAtTrigger(Todo todo, long nowMillis) {
        return isTodoReminderEligible(todo, nowMillis);
    }

    /** 任务完成所需分钟数，非正值按默认 60 兜底（老数据同样走这里）。 */
    public static int requiredMinutes(Task task) {
        if (task == null || task.requiredMinutes < MIN_REQUIRED_MINUTES) {
            return Task.DEFAULT_REQUIRED_MINUTES;
        }
        return Math.min(task.requiredMinutes, MAX_REQUIRED_MINUTES);
    }

    /** 待办花费时长，负值按默认 60 兜底。 */
    public static int requiredTodoMinutes(Todo todo) {
        if (todo == null || todo.durationMinutes < MIN_REQUIRED_MINUTES) {
            return Task.DEFAULT_REQUIRED_MINUTES;
        }
        return Math.min(todo.durationMinutes, MAX_REQUIRED_MINUTES);
    }

    /** 完成所需时间是否可保存。 */
    public static boolean isValidRequiredMinutes(int minutes) {
        return minutes >= MIN_REQUIRED_MINUTES && minutes <= MAX_REQUIRED_MINUTES;
    }

    /** 通知正文：任务名 + 截止时间 + 剩余完成时间。 */
    public static String taskBodyText(Task task) {
        return task.name + "｜截止 " + task.endTime + "｜需 " + requiredMinutes(task) + " 分钟完成";
    }

    /** 通知正文：待办名 + 截止时间 + 剩余完成时间。 */
    public static String todoBodyText(Todo todo) {
        return todo.title + "｜截止 " + formatDateTime(todo.endTime) + "｜需 "
                + requiredTodoMinutes(todo) + " 分钟完成";
    }

    public static String formatTimeOfDay(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(millis));
    }

    public static String formatDateTime(long millis) {
        return new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(new Date(millis));
    }

    private static boolean isValidTime(String time) {
        return time != null && time.matches("\\d{2}:\\d{2}");
    }

    private static long taskDeadline(Task task, Date now) {
        if (!isValidTime(task.endTime)) return Long.MIN_VALUE;
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(now);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.MINUTE, TaskLogic.parseTime(task.endTime));
        return calendar.getTimeInMillis();
    }
}
