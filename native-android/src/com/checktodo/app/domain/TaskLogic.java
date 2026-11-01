package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class TaskLogic {
    /**
     * 日历中单个日期的展示状态，用于打卡日历着色。
     */
    public enum CalendarDayState {
        /** 重复日且已打卡（绿色） */
        DONE,
        /** 重复日但当天已过且未打卡（灰色，视为错过） */
        MISSED,
        /** 请假（黑底/夜间为浅底） */
        LEAVE,
        /** 今天且尚未完成的全新一天（描边） */
        TODAY,
        /** 今天且仍在时间窗内（描边 + 可打卡提示） */
        TODAY_ACTIVE,
        /** 非重复日（淡显） */
        OFF,
        /** 未来的重复日（淡显，尚未发生） */
        FUTURE,
        /** 任务创建之前的日期（淡显） */
        BEFORE_START
    }

    private TaskLogic() {
    }

    public static boolean isScheduled(Task task, Date date) {
        return task.weekdays[weekIndex(date)];
    }

    /**
     * 请假按任务存在 task.leaveDates 中，日期键为 yyyy-MM-dd。
     */
    public static boolean isOnLeave(Task task, Date date) {
        return task.leaveDates.contains(dateKey(date));
    }

    /**
     * 创建时刻是否已过当日截止时间。TaskStats 与状态机共用该规则，
     * 保证「创建日不计入统计」和「当天显示未开始」始终同口径。
     */
    public static boolean createdAfterDailyEndTime(Task task) {
        if (task == null || task.createdAt <= 0) return false;
        Date created = new Date(task.createdAt);
        return minutesOfDay(created) > parseTime(task.endTime);
    }

    /** 今天是否为「截止后创建」的特殊日。老数据没有 createdAt 时不适用。 */
    public static boolean isDeferredCreationDay(Task task, Date now) {
        if (!createdAfterDailyEndTime(task)) return false;
        return dateKey(new Date(task.createdAt)).equals(dateKey(now));
    }

    public static TaskStatus taskStatus(Task task, Set<String> completedDates, Date now) {
        return statusOf(task, completedDates, now);
    }

    /**
     * 统一状态入口：截止时间之后创建的任务，创建当天不显示已错过，
     * 而是显示未开始；从下一个重复日起恢复正常状态机。
     */
    public static TaskStatus statusOf(Task task, Set<String> completedDates, Date now) {
        if (!isScheduled(task, now)) return TaskStatus.OFF;
        if (isOnLeave(task, now)) return TaskStatus.LEAVE;
        if (completedDates.contains(dateKey(now))) return TaskStatus.DONE;
        if (isDeferredCreationDay(task, now)) return TaskStatus.UPCOMING;

        int minute = minutesOfDay(now);
        if (minute < parseTime(task.startTime)) return TaskStatus.UPCOMING;
        if (minute >= parseTime(task.endTime)) return TaskStatus.MISSED;
        return TaskStatus.ACTIVE;
    }

    public static boolean canCheckIn(Task task, Set<String> completedDates, Date now) {
        return taskStatus(task, completedDates, now) == TaskStatus.ACTIVE;
    }

    /**
     * 今日视图与小卡片（今日打卡模式）的统一排序。
     * 顺序：可打卡（ACTIVE，endTime 升序，越紧急越靠上）→ 已完成 → 已错过（endTime 升序）
     * → 未开始（endTime 升序）→ 请假 → 今日非重复日（endTime 升序）。
     * 同 endTime 时按任务名排序，保证结果确定。
     */
    public static List<Task> sortForToday(List<Task> tasks, Map<String, Set<String>> completedDates, Date now) {
        List<Task> result = new ArrayList<>(tasks);
        result.sort((left, right) -> compareTodayOrder(
                left, completedFor(left, completedDates),
                right, completedFor(right, completedDates), now));
        return result;
    }

    private static int compareTodayOrder(Task left, Set<String> leftCompleted,
                                         Task right, Set<String> rightCompleted, Date now) {
        int rankLeft = todayRank(left, leftCompleted, now);
        int rankRight = todayRank(right, rightCompleted, now);
        if (rankLeft != rankRight) return Integer.compare(rankLeft, rankRight);
        int keyLeft = orderKey(left, rankLeft);
        int keyRight = orderKey(right, rankRight);
        if (keyLeft != keyRight) return Integer.compare(keyLeft, keyRight);
        return left.name.compareTo(right.name);
    }

    /**
     * 组内排序键（2026-10-27 需求变更）：进行中 / 已错过 / 未开始按
     * 「最晚开始时刻 = endTime − 完成所需时间」升序——<b>越紧急越靠前</b>
     * （同样截止 22:00，需要 2 小时的任务比需要 5 分钟的更紧迫）；
     * 已完成 / 请假 / 今日非重复日没有紧急度概念，保持 endTime 升序。
     */
    private static int orderKey(Task task, int rank) {
        boolean urgencyApplies = rank == 0 || rank == 2 || rank == 3; // ACTIVE / MISSED / UPCOMING
        int endTimeMinute = parseTime(task.endTime);
        return urgencyApplies
                ? endTimeMinute - ReminderLogic.requiredMinutes(task)
                : endTimeMinute;
    }

    private static int todayRank(Task task, Set<String> completedDates, Date now) {
        TaskStatus status = taskStatus(task, completedDates, now);
        switch (status) {
            case ACTIVE: return 0;
            case DONE: return 1;
            case MISSED: return 2;
            case UPCOMING: return 3;
            case LEAVE: return 4;
            default: return 5;
        }
    }

    private static Set<String> completedFor(Task task, Map<String, Set<String>> completedDates) {
        Set<String> dates = completedDates.get(task.id);
        return dates == null ? Collections.emptySet() : dates;
    }

    /**
     * 打卡小卡片专用分组顺序（2026-10-28 真机反馈）。
     *
     * <p>真机要求卡片从上到下：可打卡（ACTIVE）→ 未开始（UPCOMING）→ 已完成（DONE，撤销）
     * → 已错过（MISSED）。App 内今日视图的状态序（ACTIVE→DONE→MISSED→UPCOMING，见
     * {@link #sortForToday}）保持不变，两者只在「分组序」不同、组内口径完全相同。</p>
     */
    public enum WidgetGroup {
        /** 可打卡：卡片最上面。 */
        ACTIVE(0),
        /** 未开始：中间。 */
        UPCOMING(1),
        /** 已完成（可撤销）：底部。 */
        DONE(2),
        /** 已错过：底部，排在已完成之后。 */
        MISSED(3),
        /** 请假：最末（卡片兜底分组，正常数据里今日请假会显示为 LEAVE）。 */
        LEAVE(4),
        /** 今日非重复日：卡片 buildRows 已按 isScheduled 过滤，这里仅作兜底。 */
        OFF(5);

        private final int rank;

        WidgetGroup(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }

    public static WidgetGroup widgetGroup(TaskStatus status) {
        if (status == null) return WidgetGroup.OFF;
        switch (status) {
            case ACTIVE: return WidgetGroup.ACTIVE;
            case UPCOMING: return WidgetGroup.UPCOMING;
            case DONE: return WidgetGroup.DONE;
            case MISSED: return WidgetGroup.MISSED;
            case LEAVE: return WidgetGroup.LEAVE;
            default: return WidgetGroup.OFF;
        }
    }

    /** 分组序号（0..5），供测试与排查直接断言。 */
    public static int widgetRank(TaskStatus status) {
        return widgetGroup(status).rank();
    }

    /**
     * 小卡片今日打卡排序：分组 = {@link WidgetGroup} 序号，组内全部按
     * 「截止时间 − 完成所需时间」升序（同刻按任务名）。
     *
     * <p>与 {@link #sortForToday} 的唯一差别是分组序；组内紧急度口径故意复用
     * {@link #orderKey}，保证两处「谁更紧急」的结论一致。</p>
     */
    public static List<Task> sortForWidgetToday(List<Task> tasks,
                                                Map<String, Set<String>> completedDates, Date now) {
        List<Task> result = new ArrayList<>(tasks);
        result.sort((left, right) -> {
            WidgetGroup leftGroup = widgetGroup(taskStatus(left, completedFor(left, completedDates), now));
            WidgetGroup rightGroup = widgetGroup(taskStatus(right, completedFor(right, completedDates), now));
            if (leftGroup != rightGroup) return Integer.compare(leftGroup.rank(), rightGroup.rank());
            int keyLeft = orderKey(left, urgencyRank(leftGroup));
            int keyRight = orderKey(right, urgencyRank(rightGroup));
            if (keyLeft != keyRight) return Integer.compare(keyLeft, keyRight);
            return left.name.compareTo(right.name);
        });
        return result;
    }

    /** 卡片分组 → {@link #orderKey} 的紧急度适用判定（沿用今日视图的三组口径）。 */
    private static int urgencyRank(WidgetGroup group) {
        if (group == WidgetGroup.ACTIVE || group == WidgetGroup.UPCOMING || group == WidgetGroup.MISSED) {
            return 0;
        }
        return group == WidgetGroup.DONE ? 1 : 2;
    }

    /**
     * 需求变更（第二轮）：今天与未来的重复日都可以请假（含未来预请假）；
     * 过去日期仍不可补请假。取消请假不受该限制，任何时候都可以。
     */
    public static boolean canLeave(Task task, Date target, Date now) {
        if (target == null || now == null) return false;
        if (!isScheduled(task, target)) return false;
        return dateKey(target).compareTo(dateKey(now)) >= 0;
    }

    public static int calculateStreak(Task task, Set<String> completedDates, Date now) {
        boolean hasRepeatDay = false;
        for (boolean day : task.weekdays) {
            if (day) {
                hasRepeatDay = true;
                break;
            }
        }
        if (!hasRepeatDay) return 0;

        String todayKey = dateKey(now);
        int streak = 0;
        if (isScheduled(task, now)) {
            // 需求变更：请假当天视为未完成，连续记录直接归零。
            if (isOnLeave(task, now)) return 0;
            if (completedDates.contains(todayKey)) {
                streak = 1;
            } else if (minutesOfDay(now) >= parseTime(task.endTime)) {
                return 0;
            }
        }

        Date date = addDays(now, -1);
        while (true) {
            if (isScheduled(task, date)) {
                // 需求变更：历史请假日视为未完成，遇到即打断连续记录。
                if (isOnLeave(task, date)) break;
                if (!completedDates.contains(dateKey(date))) break;
                streak++;
            }
            date = addDays(date, -1);
        }
        return streak;
    }

    /**
     * 计算日历中某一天的展示状态。纯函数，不依赖 Android 类，可被纯 Java 测试覆盖。
     */
    public static CalendarDayState calendarDayState(Task task, Set<String> completedDates, Date day, Date now) {
        // 未来预请假的日期也按 LEAVE 展示：未到日期时无打卡入口，到达后按请假处理。
        if (isScheduled(task, day) && isOnLeave(task, day)) return CalendarDayState.LEAVE;
        if (dateKey(day).compareTo(dateKey(now)) > 0) {
            return isScheduled(task, day) ? CalendarDayState.FUTURE : CalendarDayState.OFF;
        }
        if (isScheduled(task, day) && completedDates.contains(dateKey(day))) return CalendarDayState.DONE;
        if (!isScheduled(task, day)) return CalendarDayState.OFF;
        if (dateKey(day).equals(dateKey(now))) {
            return taskStatus(task, completedDates, now) == TaskStatus.ACTIVE
                    ? CalendarDayState.TODAY_ACTIVE
                    : CalendarDayState.TODAY;
        }
        return CalendarDayState.MISSED;
    }

    public static List<String> validateTask(String name, boolean[] weekdays, String startTime, String endTime) {
        return validateTask(name, weekdays, startTime, endTime, Task.DEFAULT_REQUIRED_MINUTES);
    }

    public static List<String> validateTask(String name, boolean[] weekdays, String startTime,
                                            String endTime, int requiredMinutes) {
        List<String> errors = new ArrayList<>();
        if (name == null || name.trim().isEmpty()) errors.add("请填写任务名称");
        boolean hasDay = false;
        for (boolean day : weekdays) {
            if (day) {
                hasDay = true;
                break;
            }
        }
        if (!hasDay) errors.add("请至少选择一个重复日");
        if (!isValidTime(startTime) || !isValidTime(endTime)) {
            errors.add("时间格式必须为 HH:mm");
        } else if (parseTime(startTime) >= parseTime(endTime)) {
            errors.add("结束时间必须晚于开始时间");
        }
        if (!ReminderLogic.isValidRequiredMinutes(requiredMinutes)) {
            errors.add("完成所需时间必须在 " + ReminderLogic.MIN_REQUIRED_MINUTES + " 到 "
                    + ReminderLogic.MAX_REQUIRED_MINUTES + " 分钟之间");
        }
        return errors;
    }

    public static String dateKey(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return String.format(Locale.US, "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    public static Date date(int year, int month, int day, int hour, int minute) {
        Calendar calendar = new GregorianCalendar(year, month - 1, day, hour, minute, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    private static Date addDays(Date date, int amount) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.add(Calendar.DAY_OF_MONTH, amount);
        return calendar.getTime();
    }

    private static int weekIndex(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    public static int minutesOfDay(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
    }

    public static int parseTime(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private static boolean isValidTime(String time) {
        if (time == null || !time.matches("\\d{2}:\\d{2}")) return false;
        int hour = Integer.parseInt(time.substring(0, 2));
        int minute = Integer.parseInt(time.substring(3));
        return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
    }
}
