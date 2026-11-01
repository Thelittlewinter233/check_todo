package com.checktodo.app.domain;

import java.util.Calendar;
import java.util.Date;
import java.util.Set;

/**
 * 打卡统计（纯 Java，可单测）。
 * 口径：总天数 = 从任务创建日（含当天）到今天之间属于「当前重复日」的天数，
 * 非重复日与未来日不计；打卡天数 = 该区间内已完成打卡（日期 ≤ 今天）的天数。
 * 打卡率 = 打卡天数 / 总天数，四舍五入取整；总天数为 0 时 ratePercent = -1（UI 显示 --）。
 */
public final class TaskStats {
    public final int totalDays;
    public final int completedDays;
    /** 0..100 的百分比；-1 表示总天数为 0，无法计算。 */
    public final int ratePercent;

    private TaskStats(int totalDays, int completedDays, int ratePercent) {
        this.totalDays = totalDays;
        this.completedDays = completedDays;
        this.ratePercent = ratePercent;
    }

    public static TaskStats compute(Task task, Set<String> completedDates, Date now) {
        String todayKey = TaskLogic.dateKey(now);
        String createdKey = resolveStatsStartKey(task, completedDates, todayKey);
        int total = 0;
        int completed = 0;
        Date day = parseKey(createdKey);
        Date end = parseKey(todayKey);
        while (!day.after(end)) {
            if (TaskLogic.isScheduled(task, day)) {
                total++;
                if (completedDates.contains(TaskLogic.dateKey(day))) completed++;
            }
            day = addDays(day);
        }
        int rate = total == 0 ? -1 : Math.round(completed * 100f / total);
        return new TaskStats(total, completed, rate);
    }

    /**
     * 创建日兜底：createdAt 缺失（≤0，老数据）时取最早的 ≤今天 打卡日期；
     * 没有任何打卡记录则回落到今天（此时总天数为 0 或 1）。
     * createdAt 晚于今天（时钟异常）同样钳制到今天。
     */
    /** 统计起算日：创建日（含）；若创建当天已过截止时间（now > endTime）则为第二天。 */
    static String resolveStatsStartKey(Task task, Set<String> completedDates, String todayKey) {
        String createdKey = resolveCreatedKey(task, completedDates, todayKey);
        if (task.createdAt <= 0) return createdKey;
        // 创建时刻晚于当日截止时间：起算日推到第二天，创建日不计入总天数。
        // 与 TaskLogic.isDeferredCreationDay 共用同一创建日规则。
        if (TaskLogic.createdAfterDailyEndTime(task)) {
            return TaskLogic.dateKey(addDays(parseKey(createdKey)));
        }
        return createdKey;
    }

    static String resolveCreatedKey(Task task, Set<String> completedDates, String todayKey) {
        if (task.createdAt > 0) {
            String key = TaskLogic.dateKey(new Date(task.createdAt));
            return key.compareTo(todayKey) <= 0 ? key : todayKey;
        }
        String earliest = null;
        for (String key : completedDates) {
            if (key == null || key.compareTo(todayKey) > 0) continue;
            if (earliest == null || key.compareTo(earliest) < 0) earliest = key;
        }
        return earliest != null ? earliest : todayKey;
    }

    /** 解析 yyyy-MM-dd 为当天正午，避免时区/夏令时边界影响按天迭代。 */
    private static Date parseKey(String key) {
        return TaskLogic.date(Integer.parseInt(key.substring(0, 4)),
                Integer.parseInt(key.substring(5, 7)),
                Integer.parseInt(key.substring(8, 10)), 12, 0);
    }

    private static Date addDays(Date day) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(day);
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        return calendar.getTime();
    }
}
