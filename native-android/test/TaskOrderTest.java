import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.TaskLogic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 今日视图 / 小卡片统一排序测试（ACTIVE→DONE→MISSED→UPCOMING→LEAVE→OFF）。 */
public final class TaskOrderTest {
    private static final boolean[] EVERY_DAY =
            new boolean[]{true, true, true, true, true, true, true};
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testActiveSortedByEndTime();
        testStatusGroupsOrdered();
        testOffAndLeaveGoLast();
        testDeterministicTieBreak();

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void testActiveSortedByEndTime() {
        List<Task> tasks = Arrays.asList(
                task("late", "Late task", "20:00"),
                task("early", "Early task", "09:00"),
                task("middle", "Middle task", "12:00"));
        List<Task> sorted = TaskLogic.sortForToday(tasks, new HashMap<>(),
                date(2026, 10, 23, 8, 0));

        check("two active tasks sort by endTime", ids(sorted).equals(
                Arrays.asList("early", "middle", "late")));
    }

    private static void testStatusGroupsOrdered() {
        List<Task> tasks = Arrays.asList(
                new Task("leave", "Leave", EVERY_DAY, "09:00", "10:00",
                        set("2026-10-23"), 0L),
                task("missed", "Missed", "10:00"),
                task("done", "Done", "20:00"),
                new Task("upcoming", "Upcoming", EVERY_DAY, "12:00", "18:00", 0L),
                task("active", "Active", "20:00"));
        Map<String, Set<String>> completed = new HashMap<>();
        completed.put("done", set("2026-10-23"));
        Date now = date(2026, 10, 23, 10, 30);

        check("done after active", TaskLogic.sortForToday(tasks, completed, now).get(0).id
                .equals("active") && TaskLogic.sortForToday(tasks, completed, now).get(1).id
                .equals("done"));
        List<Task> sorted = TaskLogic.sortForToday(tasks, completed, now);
        check("full status order is active done missed upcoming leave",
                ids(sorted).equals(Arrays.asList("active", "done", "missed", "upcoming", "leave")));
    }

    private static void testOffAndLeaveGoLast() {
        List<Task> tasks = new ArrayList<>(Arrays.asList(
                task("everyday", "Everyday", "09:00"),
                new Task("sunday", "Sunday only",
                        new boolean[]{false, false, false, false, false, false, true},
                        "10:00", "11:00", 0L)));
        // 2026-10-23 是周五：sunday 任务为 OFF。
        List<Task> sorted = TaskLogic.sortForToday(tasks, new HashMap<>(),
                date(2026, 10, 23, 10, 0));
        check("off task sorts after scheduled active", sorted.get(0).id.equals("everyday")
                && sorted.get(1).id.equals("sunday"));

        Task onLeave = new Task("onleave", "On leave", EVERY_DAY, "09:00", "10:00",
                set("2026-10-23"), 0L);
        Task off = new Task("off", "Off", new boolean[]{false, false, false, false,
                false, false, true}, "09:00", "10:00", 0L);
        List<Task> second = TaskLogic.sortForToday(Arrays.asList(off, onLeave),
                new HashMap<>(), date(2026, 10, 23, 10, 0));
        check("leave sorts before off", ids(second).equals(Arrays.asList("onleave", "off")));
    }

    private static void testDeterministicTieBreak() {
        List<Task> tasks = Arrays.asList(
                task("b", "Beta", "10:00"),
                task("a", "Alpha", "10:00"));
        List<Task> sorted = TaskLogic.sortForToday(tasks, new HashMap<>(),
                date(2026, 10, 23, 9, 0));
        check("same endTime falls back to name", ids(sorted).equals(Arrays.asList("a", "b")));

        List<Task> reversed = new ArrayList<>(Arrays.asList(
                task("a", "Alpha", "10:00"), task("b", "Beta", "10:00")));
        Collections.reverse(reversed);
        check("tie result is deterministic regardless of input order", ids(
                TaskLogic.sortForToday(reversed, new HashMap<>(), date(2026, 10, 23, 9, 0)))
                .equals(Arrays.asList("a", "b")));
    }

    private static Task task(String id, String name, String endTime) {
        return new Task(id, name, EVERY_DAY, "08:00", endTime, 0L);
    }

    private static Date date(int year, int month, int day, int hour, int minute) {
        return TaskLogic.date(year, month, day, hour, minute);
    }

    private static Set<String> set(String... keys) {
        return new HashSet<>(Arrays.asList(keys));
    }

    private static List<String> ids(List<Task> tasks) {
        List<String> result = new ArrayList<>();
        for (Task task : tasks) result.add(task.id);
        return result;
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
