import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.TaskLogic;
import com.checktodo.app.domain.TaskStats;
import com.checktodo.app.domain.TaskStatus;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/** 取消今日打卡的纯 Java 行为测试：状态恢复、streak 重算、统计口径自动跟随。 */
public final class CancelTest {
    private static final boolean[] MWF = new boolean[]{true, false, true, false, true, false, false};
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testCancelRestoresStatus();
        testCancelRecalculatesStreak();
        testCancelUpdatesStats();
        testOnlyTodayEntryCanBeCancelled();

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void testCancelRestoresStatus() {
        Task task = new Task("t1", "Exercise", MWF, "08:00", "22:00",
                TaskLogic.date(2026, 10, 5, 9, 0).getTime());

        Set<String> completed = set("2026-10-23");
        check("done status before cancel in window", TaskLogic.taskStatus(task, completed,
                TaskLogic.date(2026, 10, 23, 9, 0)) == TaskStatus.DONE);
        completed.remove("2026-10-23");
        check("cancel restores ACTIVE in window", TaskLogic.taskStatus(task, completed,
                TaskLogic.date(2026, 10, 23, 9, 0)) == TaskStatus.ACTIVE);
        check("cancelled today is checkable again", TaskLogic.canCheckIn(task, completed,
                TaskLogic.date(2026, 10, 23, 9, 0)));

        Set<String> eveningCompleted = set("2026-10-23");
        eveningCompleted.remove("2026-10-23");
        check("cancel after end becomes MISSED", TaskLogic.taskStatus(task, eveningCompleted,
                TaskLogic.date(2026, 10, 23, 23, 0)) == TaskStatus.MISSED);
        check("cancelled after end cannot check in", !TaskLogic.canCheckIn(task, eveningCompleted,
                TaskLogic.date(2026, 10, 23, 23, 0)));

        Set<String> morningCompleted = set("2026-10-23");
        morningCompleted.remove("2026-10-23");
        check("cancel before start becomes UPCOMING", TaskLogic.taskStatus(task, morningCompleted,
                TaskLogic.date(2026, 10, 23, 7, 0)) == TaskStatus.UPCOMING);
    }

    private static void testCancelRecalculatesStreak() {
        Task task = new Task("t1", "Exercise", MWF, "08:00", "22:00",
                TaskLogic.date(2026, 10, 5, 9, 0).getTime());
        Set<String> completed = set("2026-10-21", "2026-10-23");
        check("streak before cancel", TaskLogic.calculateStreak(task, completed,
                TaskLogic.date(2026, 10, 23, 9, 0)) == 2);

        completed.remove("2026-10-23");
        check("streak drops after cancel", TaskLogic.calculateStreak(task, completed,
                TaskLogic.date(2026, 10, 23, 9, 0)) == 1);

        completed.add("2026-10-23");
        completed.remove("2026-10-21");
        check("cancel an earlier day does not remove today's check-in", completed.contains("2026-10-23"));
        check("streak counts only consecutive records", TaskLogic.calculateStreak(task, completed,
                TaskLogic.date(2026, 10, 23, 9, 0)) == 1);
    }

    private static void testCancelUpdatesStats() {
        Task task = new Task("t1", "Exercise", MWF, "08:00", "22:00",
                TaskLogic.date(2026, 10, 5, 9, 0).getTime());
        Set<String> completed = set("2026-10-05", "2026-10-07", "2026-10-09");
        TaskStats before = TaskStats.compute(task, completed, TaskLogic.date(2026, 10, 9, 20, 0));
        check("stats before cancel", before.completedDays == 3 && before.totalDays == 3
                && before.ratePercent == 100);

        completed.remove("2026-10-09");
        TaskStats after = TaskStats.compute(task, completed, TaskLogic.date(2026, 10, 9, 20, 0));
        check("stats after cancel", after.completedDays == 2 && after.totalDays == 3
                && after.ratePercent == 67);
        check("stats rate recovers after re-check-in", TaskStats.compute(task,
                set("2026-10-05", "2026-10-07", "2026-10-09"),
                TaskLogic.date(2026, 10, 9, 20, 0)).ratePercent == 100);
    }

    private static void testOnlyTodayEntryCanBeCancelled() {
        Task task = new Task("t1", "Exercise", MWF, "08:00", "22:00",
                TaskLogic.date(2026, 10, 5, 9, 0).getTime());
        Set<String> completed = set("2026-10-21", "2026-10-23");
        Date now = TaskLogic.date(2026, 10, 23, 21, 0);
        String todayKey = TaskLogic.dateKey(now);

        check("cancel only removes today's key", completed.remove(todayKey)
                && completed.equals(set("2026-10-21")));
        check("historical date remains present after cancel", completed.contains("2026-10-21"));
        check("calendar history stays DONE", TaskLogic.calendarDayState(task,
                set("2026-10-21"), TaskLogic.date(2026, 10, 21, 12, 0), now)
                == TaskLogic.CalendarDayState.DONE);
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
