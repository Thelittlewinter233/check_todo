import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.TaskLogic;
import com.checktodo.app.domain.TaskStats;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * 打卡统计纯 Java 测试：跨周统计、创建当天（含/非重复日）、编辑重复日后重算、
 * 0 总天数边界、100%/0%、未来与创建前打卡排除、老数据 createdAt 兜底。
 * 基准日历：2026-10-05 为周一。
 */
public final class TaskStatsTest {
    private static int passed;
    private static int failed;

    private static final boolean[] MWF = {true, false, true, false, true, false, false};
    private static final boolean[] TUTH = {false, true, false, true, false, false, false};

    public static void main(String[] args) {
        testBasicWeek();
        testCrossWeekFull();
        testCrossWeekRounding();
        testCreatedTodayScheduled();
        testCreatedTodayNotScheduled();
        testEditedWeekdaysRecount();
        testFutureCompletedExcluded();
        testCompletedBeforeCreationExcluded();
        testLegacyFallbackEarliestCheckin();
        testLegacyFallbackToday();
        testFutureCreatedAtClamped();
        testCreatedAfterDeadline();
        testCreatedBeforeDeadline();

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    /** 周一创建、周日统计：MWF 三天，打卡两天 → 2/3 = 67%。 */
    private static void testBasicWeek() {
        Task task = task(MWF, 2026, 10, 5, 9, 0);
        TaskStats stats = TaskStats.compute(task, set("2026-10-05", "2026-10-07"), at(2026, 10, 11, 20, 0));
        check("基本周：总天数 3", stats.totalDays == 3);
        check("基本周：打卡天数 2", stats.completedDays == 2);
        check("基本周：打卡率 67%", stats.ratePercent == 67);
    }

    /** 跨周：10-05 至 10-23 共 9 个 MWF，全部打卡 → 100%。 */
    private static void testCrossWeekFull() {
        Task task = task(MWF, 2026, 10, 5, 9, 0);
        TaskStats stats = TaskStats.compute(task,
                set("2026-10-05", "2026-10-07", "2026-10-09", "2026-10-12", "2026-10-14",
                        "2026-10-16", "2026-10-19", "2026-10-21", "2026-10-23"),
                at(2026, 10, 23, 21, 0));
        check("跨周：总天数 9", stats.totalDays == 9);
        check("跨周：全打卡 100%", stats.completedDays == 9 && stats.ratePercent == 100);
    }

    /** 跨周部分打卡：5/9 = 55.6 → 56%（四舍五入）。 */
    private static void testCrossWeekRounding() {
        Task task = task(MWF, 2026, 10, 5, 9, 0);
        TaskStats stats = TaskStats.compute(task,
                set("2026-10-05", "2026-10-07", "2026-10-09", "2026-10-12", "2026-10-14"),
                at(2026, 10, 23, 21, 0));
        check("跨周四舍五入：5/9 = 56%", stats.completedDays == 5 && stats.ratePercent == 56);
    }

    /** 创建当天且是重复日：0/1 = 0%，打卡后 1/1 = 100%。 */
    private static void testCreatedTodayScheduled() {
        Task task = task(MWF, 2026, 10, 23, 8, 0);
        Date now = at(2026, 10, 23, 12, 0);
        TaskStats before = TaskStats.compute(task, set(), now);
        TaskStats after = TaskStats.compute(task, set("2026-10-23"), now);
        check("创建当天重复日：0/1 = 0%", before.totalDays == 1 && before.completedDays == 0
                && before.ratePercent == 0);
        check("创建当天打卡：1/1 = 100%", after.totalDays == 1 && after.completedDays == 1
                && after.ratePercent == 100);
    }

    /** 创建当天且非重复日：0/0，rate = -1。 */
    private static void testCreatedTodayNotScheduled() {
        Task task = task(MWF, 2026, 10, 24, 8, 0); // 周六创建
        TaskStats stats = TaskStats.compute(task, set(), at(2026, 10, 24, 12, 0));
        check("创建当天非重复日：0/0", stats.totalDays == 0 && stats.completedDays == 0);
        check("0 总天数：rate = -1", stats.ratePercent == -1);
    }

    /** 编辑重复日后按当前口径重算：周一打卡记录不再计入 TuTh 任务。 */
    private static void testEditedWeekdaysRecount() {
        Task task = task(TUTH, 2026, 10, 5, 9, 0);
        TaskStats stats = TaskStats.compute(task,
                set("2026-10-05", "2026-10-12", "2026-10-19"),
                at(2026, 10, 23, 21, 0));
        // 10-05..10-23 的周二/周四：6,8,13,15,20,22 共 6 天
        check("编辑重复日：总天数按新口径 6", stats.totalDays == 6);
        check("编辑重复日：旧周一打卡不计入", stats.completedDays == 0 && stats.ratePercent == 0);
    }

    /** 未来的打卡日期不计入打卡天数。 */
    private static void testFutureCompletedExcluded() {
        Task task = task(MWF, 2026, 10, 5, 9, 0);
        TaskStats stats = TaskStats.compute(task, set("2026-10-05", "2026-10-14"), at(2026, 10, 11, 20, 0));
        check("未来打卡不计入", stats.totalDays == 3 && stats.completedDays == 1 && stats.ratePercent == 33);
    }

    /** 创建日之前的打卡记录不计入。 */
    private static void testCompletedBeforeCreationExcluded() {
        Task task = task(MWF, 2026, 10, 9, 9, 0);
        TaskStats stats = TaskStats.compute(task, set("2026-10-05", "2026-10-09"), at(2026, 10, 11, 20, 0));
        check("创建前打卡不计入", stats.totalDays == 1 && stats.completedDays == 1 && stats.ratePercent == 100);
    }

    /** 老数据无 createdAt：以最早打卡日期兜底为创建日。 */
    private static void testLegacyFallbackEarliestCheckin() {
        Task task = legacyTask(MWF);
        TaskStats stats = TaskStats.compute(task, set("2026-10-07", "2026-10-09"), at(2026, 10, 11, 20, 0));
        check("兜底最早打卡日：总天数 2", stats.totalDays == 2);
        check("兜底最早打卡日：打卡率 100%", stats.completedDays == 2 && stats.ratePercent == 100);
    }

    /** 老数据无 createdAt 且无打卡记录：回落到今天（重复日 → 0/1；非重复日 → 0/0）。 */
    private static void testLegacyFallbackToday() {
        Task task = legacyTask(MWF);
        TaskStats onFriday = TaskStats.compute(task, set(), at(2026, 10, 9, 12, 0));
        TaskStats onSaturday = TaskStats.compute(task, set(), at(2026, 10, 10, 12, 0));
        check("兜底今天（重复日）：0/1", onFriday.totalDays == 1 && onFriday.ratePercent == 0);
        check("兜底今天（非重复日）：0/0", onSaturday.totalDays == 0 && onSaturday.ratePercent == -1);
    }

    /** createdAt 晚于今天（时钟异常）：钳制到今天，不产生负区间。 */
    private static void testFutureCreatedAtClamped() {
        Task task = task(MWF, 2026, 10, 30, 9, 0);
        TaskStats stats = TaskStats.compute(task, set(), at(2026, 10, 11, 20, 0));
        check("未来 createdAt 钳制到今天", stats.totalDays == 0 && stats.ratePercent == -1);
    }

    /** 截止后创建：创建当天不计入总天数，从下一个重复日起算。 */
    private static void testCreatedAfterDeadline() {
        // 10-23 周五 23:00 创建，截止 22:00 已过 → 10-23 不计入。
        Task task = task(MWF, 2026, 10, 23, 23, 0);
        TaskStats sameNight = TaskStats.compute(task, set(), at(2026, 10, 23, 23, 30));
        TaskStats nextMonday = TaskStats.compute(task, set(), at(2026, 10, 26, 12, 0));
        check("截止后创建当天 0/0", sameNight.totalDays == 0
                && sameNight.completedDays == 0 && sameNight.ratePercent == -1);
        check("截止后创建从 10-26 起算", nextMonday.totalDays == 1
                && nextMonday.completedDays == 0 && nextMonday.ratePercent == 0);
    }

    /** 截止前创建：维持从创建日（含）起算。 */
    private static void testCreatedBeforeDeadline() {
        Task task = task(MWF, 2026, 10, 23, 21, 0);
        TaskStats stats = TaskStats.compute(task, set(), at(2026, 10, 23, 21, 30));
        check("截止前创建当天计入", stats.totalDays == 1
                && stats.completedDays == 0 && stats.ratePercent == 0);
    }

    private static Task task(boolean[] weekdays, int year, int month, int day, int hour, int minute) {
        long createdAt = TaskLogic.date(year, month, day, hour, minute).getTime();
        return new Task("t", "Task", weekdays, "08:00", "22:00", createdAt);
    }

    private static Task legacyTask(boolean[] weekdays) {
        return new Task("t", "Legacy", weekdays, "08:00", "22:00", 0L);
    }

    private static Date at(int year, int month, int day, int hour, int minute) {
        return TaskLogic.date(year, month, day, hour, minute);
    }

    private static Set<String> set(String... keys) {
        return new HashSet<>(Arrays.asList(keys));
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }
}
