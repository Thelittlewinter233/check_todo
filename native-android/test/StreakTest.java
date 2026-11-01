import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.TaskLogic;
import com.checktodo.app.domain.TaskStatus;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

public final class StreakTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testCheckInWindow();
        testSameDayCannotRepeat();
        testStreakCountsOnlyRepeatDays();
        testMissedRepeatDayResetsStreak();
        testYesterdayStreakWhileTodayUpcoming();
        testOffDayAndNoRepeatDay();
        testCurrentDayCompletionAndBoundaries();
        testValidation();

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void testCheckInWindow() {
        Task task = task(new boolean[]{true, false, true, false, true, false, false});
        Set<String> completed = new HashSet<>();

        check("active on scheduled day", TaskLogic.canCheckIn(task, completed,
                TaskLogic.date(2026, 10, 21, 9, 0)));
        check("inactive on non-repeat day", !TaskLogic.canCheckIn(task, completed,
                TaskLogic.date(2026, 10, 20, 9, 0)));
        check("upcoming before start", TaskLogic.taskStatus(task, completed,
                TaskLogic.date(2026, 10, 21, 7, 59)) == TaskStatus.UPCOMING);
        check("missed after end", TaskLogic.taskStatus(task, completed,
                TaskLogic.date(2026, 10, 21, 22, 0)) == TaskStatus.MISSED);
    }

    private static void testSameDayCannotRepeat() {
        Task task = task(new boolean[]{true, false, true, false, true, false, false});
        Set<String> completed = new HashSet<>(Arrays.asList("2026-10-21"));

        check("done after check-in", TaskLogic.taskStatus(task, completed,
                TaskLogic.date(2026, 10, 21, 9, 0)) == TaskStatus.DONE);
        check("cannot check in again", !TaskLogic.canCheckIn(task, completed,
                TaskLogic.date(2026, 10, 21, 9, 0)));
    }

    private static void testStreakCountsOnlyRepeatDays() {
        Task task = task(new boolean[]{true, false, true, false, true, false, false});
        Set<String> completed = new HashSet<>(Arrays.asList("2026-10-23", "2026-10-21", "2026-10-19"));

        check("streak counts repeat days", TaskLogic.calculateStreak(task, completed,
                TaskLogic.date(2026, 10, 23, 8, 0)) == 3);
    }

    private static void testMissedRepeatDayResetsStreak() {
        Task task = task(new boolean[]{true, false, true, false, true, false, false});
        Set<String> completed = new HashSet<>(Arrays.asList("2026-10-19"));

        check("streak resets on missed day", TaskLogic.calculateStreak(task, completed,
                TaskLogic.date(2026, 10, 23, 9, 0)) == 0);
        check("streak stays zero after end", TaskLogic.calculateStreak(task, completed,
                TaskLogic.date(2026, 10, 23, 22, 1)) == 0);
    }

    private static void testYesterdayStreakWhileTodayUpcoming() {
        Task task = task(new boolean[]{true, false, true, false, true, false, false});
        Set<String> completed = new HashSet<>(Arrays.asList("2026-10-21"));

        check("yesterday streak preserved", TaskLogic.calculateStreak(task, completed,
                TaskLogic.date(2026, 10, 23, 7, 0)) == 1);
    }

    private static void testOffDayAndNoRepeatDay() {
        Task task = task(new boolean[]{true, false, true, false, true, false, false});
        check("off on non-repeat day", TaskLogic.taskStatus(task, new HashSet<>(),
                TaskLogic.date(2026, 10, 22, 12, 0)) == TaskStatus.OFF);

        Task noDays = task(new boolean[7]);
        check("no repeat days means zero streak", TaskLogic.calculateStreak(noDays, new HashSet<>(),
                TaskLogic.date(2026, 10, 23, 12, 0)) == 0);
    }

    private static void testCurrentDayCompletionAndBoundaries() {
        Task task = task(new boolean[]{true, false, true, false, true, false, false});
        Set<String> completed = new HashSet<>(Arrays.asList("2026-10-23", "2026-10-21"));
        Date now = TaskLogic.date(2026, 10, 23, 22, 1);

        check("completed current day counts once", TaskLogic.calculateStreak(task, completed, now) == 2);
        check("start boundary is active", TaskLogic.taskStatus(task, new HashSet<>(),
                TaskLogic.date(2026, 10, 21, 8, 0)) == TaskStatus.ACTIVE);
        check("end boundary is missed", TaskLogic.taskStatus(task, new HashSet<>(),
                TaskLogic.date(2026, 10, 21, 22, 0)) == TaskStatus.MISSED);
    }

    private static void testValidation() {
        check("valid task has no errors", TaskLogic.validateTask("Exercise",
                new boolean[]{true, false, true, false, true, false, false}, "08:00", "22:00").isEmpty());
        check("blank name is rejected", !TaskLogic.validateTask("  ",
                new boolean[]{true}, "08:00", "22:00").isEmpty());
        check("no weekday is rejected", !TaskLogic.validateTask("Exercise",
                new boolean[7], "08:00", "22:00").isEmpty());
        check("equal times are rejected", !TaskLogic.validateTask("Exercise",
                new boolean[]{true}, "08:00", "08:00").isEmpty());
    }

    private static Task task(boolean[] weekdays) {
        return new Task("task-1", "Exercise", weekdays, "08:00", "22:00", 0L);
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
