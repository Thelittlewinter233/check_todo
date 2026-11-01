import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.TaskLogic;
import com.checktodo.app.domain.TaskStatus;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 请假（leave）功能域测试（第二轮需求变更后语义）：请假视为未完成并打断 streak、
 * 今天与未来重复日均可请假（未来为预请假）、过去仍不可补请、取消请假恢复、
 * 日历状态判定。纯 Java，无 Android 依赖。
 */
public final class LeaveTest {
    private static int passed;
    private static int failed;

    // 周一/三/五重复
    private static final boolean[] MWF = new boolean[]{true, false, true, false, true, false, false};

    public static void main(String[] args) {
        testLeaveStatus();
        testLeaveBreaksStreak();
        testLeaveDayMissedResets();
        testCancelLeaveRestoresMissed();
        testCanLeaveTodayOrFuture();
        testFuturePreLeave();
        testCalendarFutureLeave();
        testCalendarDayState();
        testLeaveDataCompatibility();

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    /** 请假日状态为 LEAVE，且不可打卡。 */
    private static void testLeaveStatus() {
        Task task = task(MWF, "2026-10-21");
        Set<String> completed = new HashSet<>();

        check("leave day status is LEAVE", TaskLogic.taskStatus(task, completed,
                TaskLogic.date(2026, 10, 21, 9, 0)) == TaskStatus.LEAVE);
        check("leave day cannot check in", !TaskLogic.canCheckIn(task, completed,
                TaskLogic.date(2026, 10, 21, 9, 0)));
        check("leave day not treated as missed", TaskLogic.taskStatus(task, completed,
                TaskLogic.date(2026, 10, 21, 22, 30)) == TaskStatus.LEAVE);
        check("non-repeat day is not leave", !TaskLogic.isOnLeave(task,
                TaskLogic.date(2026, 10, 20, 9, 0)));
    }

    /** 需求变更：请假打断 streak——中间重复日请假，前后打卡不再连续。 */
    private static void testLeaveBreaksStreak() {
        // 2026-10-19 周一、10-21 周三（请假）、10-23 周五，今天 10-23 已打卡
        Task task = task(MWF, "2026-10-21");
        Set<String> completed = new HashSet<>(Arrays.asList("2026-10-19", "2026-10-23"));

        check("leave day breaks streak", TaskLogic.calculateStreak(task, completed,
                TaskLogic.date(2026, 10, 23, 9, 0)) == 1);

        // 请假之后继续打卡：连续天数从最近的完成日开始重新累计。
        check("streak restarts after leave day", TaskLogic.calculateStreak(task, completed,
                TaskLogic.date(2026, 10, 26, 9, 0)) == 1);

        // 对照：无请假且三个重复日全部打卡时，streak 正常连续。
        check("no leave in between keeps streak", TaskLogic.calculateStreak(taskNoLeave(),
                new HashSet<>(Arrays.asList("2026-10-19", "2026-10-21", "2026-10-23")),
                TaskLogic.date(2026, 10, 23, 9, 0)) == 3);
    }

    /** 需求变更：请假日视为未完成 → streak 归零；今天请假同样归零。 */
    private static void testLeaveDayMissedResets() {
        Task task = task(MWF, "2026-10-21");
        Set<String> completed = new HashSet<>(Arrays.asList("2026-10-19"));

        // 今天 10-23 未打卡（时间窗前），回溯遇 10-21 请假 → 打断，streak 归零。
        check("missed leave day zeroes streak before window", TaskLogic.calculateStreak(task, completed,
                TaskLogic.date(2026, 10, 23, 7, 0)) == 0);
        // 时间窗内同样归零。
        check("missed leave day zeroes streak in window", TaskLogic.calculateStreak(task, completed,
                TaskLogic.date(2026, 10, 23, 9, 0)) == 0);

        // 今天本身请假（10-23 周五重复日）：当天即未完成 → streak 归零。
        Task todayLeave = task(MWF, "2026-10-23");
        check("today leave zeroes streak", TaskLogic.calculateStreak(todayLeave,
                completed, TaskLogic.date(2026, 10, 23, 9, 0)) == 0);
        // 今天请假但今天不重复（10-24 周六）→ 仍是 0（最近重复日 10-23 请假打断）。
        Task leaveNonRepeatToday = task(MWF, "2026-10-23");
        check("leave on repeat day before non-repeat today is zero", TaskLogic.calculateStreak(
                leaveNonRepeatToday, completed, TaskLogic.date(2026, 10, 24, 10, 0)) == 0);
        check("today leave is not missed", TaskLogic.taskStatus(todayLeave,
                new HashSet<String>(), TaskLogic.date(2026, 10, 23, 23, 0)) == TaskStatus.LEAVE);
    }

    /** 取消请假后，该日恢复为 missed / 可打卡语义。 */
    private static void testCancelLeaveRestoresMissed() {
        Task withLeave = task(MWF, "2026-10-21");
        Task cancelled = withLeave.withLeaveDates(new HashSet<String>());

        check("cancel leave clears leaveDates", cancelled.leaveDates.isEmpty());
        check("cancelled day becomes missed after end", TaskLogic.taskStatus(cancelled,
                new HashSet<String>(), TaskLogic.date(2026, 10, 21, 23, 0)) == TaskStatus.MISSED);
        check("cancelled day is checkable in window", TaskLogic.canCheckIn(cancelled,
                new HashSet<String>(), TaskLogic.date(2026, 10, 21, 9, 0)));
        check("cancelled missed day breaks streak", TaskLogic.calculateStreak(cancelled,
                new HashSet<>(Arrays.asList("2026-10-19")), TaskLogic.date(2026, 10, 23, 9, 0)) == 0);
        check("withLeaveDates keeps other fields", cancelled.name.equals(withLeave.name)
                && cancelled.startTime.equals(withLeave.startTime)
                && cancelled.endTime.equals(withLeave.endTime)
                && cancelled.createdAt == withLeave.createdAt
                && Arrays.equals(cancelled.weekdays, withLeave.weekdays));
    }

    /** 需求变更：canLeave 对「今天及未来的重复日」为 true，过去日期仍拒绝。 */
    private static void testCanLeaveTodayOrFuture() {
        Task task = new Task("t", "T", MWF, "08:00", "22:00", 0L);

        check("can leave today", TaskLogic.canLeave(task,
                TaskLogic.date(2026, 10, 23, 0, 1), TaskLogic.date(2026, 10, 23, 9, 0)));
        check("cannot leave yesterday repeat day", !TaskLogic.canLeave(task,
                TaskLogic.date(2026, 10, 21, 12, 0), TaskLogic.date(2026, 10, 23, 9, 0)));
        check("cannot leave past repeat day", !TaskLogic.canLeave(task,
                TaskLogic.date(2026, 10, 19, 12, 0), TaskLogic.date(2026, 10, 23, 9, 0)));
        // 明天（2026-10-24 周六）不是重复日，仍不可请假。
        check("cannot leave tomorrow non-repeat day", !TaskLogic.canLeave(task,
                TaskLogic.date(2026, 10, 24, 12, 0), TaskLogic.date(2026, 10, 23, 9, 0)));
        // 未来重复日（2026-10-26 周一）可预请假。
        check("can leave future repeat day", TaskLogic.canLeave(task,
                TaskLogic.date(2026, 10, 26, 12, 0), TaskLogic.date(2026, 10, 23, 9, 0)));
        check("can leave repeat day in next week", TaskLogic.canLeave(task,
                TaskLogic.date(2026, 10, 28, 8, 0), TaskLogic.date(2026, 10, 23, 9, 0)));
        check("can leave today after window end", TaskLogic.canLeave(task,
                TaskLogic.date(2026, 10, 23, 23, 0), TaskLogic.date(2026, 10, 23, 23, 30)));
        check("cannot leave today before target", !TaskLogic.canLeave(task,
                TaskLogic.date(2026, 10, 23, 9, 0), TaskLogic.date(2026, 10, 24, 9, 0)));
        check("cannot leave non-repeat day", !TaskLogic.canLeave(task,
                TaskLogic.date(2026, 10, 22, 12, 0), TaskLogic.date(2026, 10, 23, 9, 0)));
        check("cannot leave with null date", !TaskLogic.canLeave(task, null,
                TaskLogic.date(2026, 10, 23, 9, 0)));
        check("cannot leave with null now", !TaskLogic.canLeave(task,
                TaskLogic.date(2026, 10, 23, 9, 0), null));
        // 已请假的今天仍返回 true，用于 UI 的「取消请假」切换。
        Task todayLeave = task(MWF, "2026-10-23");
        check("can leave today even if already on leave", TaskLogic.canLeave(todayLeave,
                TaskLogic.date(2026, 10, 23, 9, 0), TaskLogic.date(2026, 10, 23, 9, 0)));
    }

    /** 预请假语义：日期到来时按请假处理（不算完成、不提供打卡入口），取消后恢复可打卡。 */
    private static void testFuturePreLeave() {
        java.util.Date now = TaskLogic.date(2026, 10, 23, 9, 0);
        Task preLeave = task(MWF, "2026-10-26");

        // 预请假日未到时：日历标为 LEAVE，且不参与今天可打卡判定。
        check("pre-leave day is not today check-in blocker", TaskLogic.taskStatus(taskNoLeave(),
                new HashSet<String>(), now) != TaskStatus.LEAVE);

        // 日期到来（now 走到 2026-10-26 时间窗内）→ 按请假处理。
        java.util.Date arrival = TaskLogic.date(2026, 10, 26, 9, 0);
        check("pre-leave day becomes LEAVE when it arrives", TaskLogic.taskStatus(preLeave,
                new HashSet<String>(), arrival) == TaskStatus.LEAVE);
        check("pre-leave day has no check-in entry", !TaskLogic.canCheckIn(preLeave,
                new HashSet<String>(), arrival));
        check("pre-leave day is not counted as done", !TaskLogic.taskStatus(preLeave,
                new HashSet<>(Arrays.asList("2026-10-26")), arrival).equals(TaskStatus.DONE));

        // 取消预请假后，该日恢复为可打卡（时间窗内）。
        Task cancelled = preLeave.withLeaveDates(new HashSet<String>());
        check("cancel pre-leave restores check-in", TaskLogic.canCheckIn(cancelled,
                new HashSet<String>(), arrival));
        check("cancel pre-leave clears leaveDates", cancelled.leaveDates.isEmpty());

        // 预请假日到达并结束后按请假语义：不算完成、不算错过、打断 streak。
        check("pre-leave day after window still LEAVE", TaskLogic.taskStatus(preLeave,
                new HashSet<String>(), TaskLogic.date(2026, 10, 26, 23, 0)) == TaskStatus.LEAVE);
        check("pre-leave day breaks streak", TaskLogic.calculateStreak(preLeave,
                new HashSet<>(Arrays.asList("2026-10-23")),
                TaskLogic.date(2026, 10, 28, 9, 0)) == 0);
    }

    /** 日历展示：未来预请假日标为 LEAVE（不是 FUTURE），取消后回到 FUTURE。 */
    private static void testCalendarFutureLeave() {
        java.util.Date now = TaskLogic.date(2026, 10, 23, 9, 0);
        java.util.Date future = TaskLogic.date(2026, 10, 26, 12, 0);
        Task preLeave = task(MWF, "2026-10-26");

        check("calendar future pre-leave shows LEAVE", TaskLogic.calendarDayState(preLeave,
                new HashSet<String>(), future, now) == TaskLogic.CalendarDayState.LEAVE);
        check("calendar future without leave shows FUTURE", TaskLogic.calendarDayState(taskNoLeave(),
                new HashSet<String>(), future, now) == TaskLogic.CalendarDayState.FUTURE);
        check("calendar future off day stays OFF", TaskLogic.calendarDayState(preLeave,
                new HashSet<String>(), TaskLogic.date(2026, 10, 27, 12, 0), now)
                == TaskLogic.CalendarDayState.OFF);
        check("calendar future leave restores FUTURE after cancel", TaskLogic.calendarDayState(
                preLeave.withLeaveDates(new HashSet<String>()), new HashSet<String>(), future, now)
                == TaskLogic.CalendarDayState.FUTURE);
        check("calendar today leave still wins over today state", TaskLogic.calendarDayState(
                task(MWF, "2026-10-23"), new HashSet<String>(), now, now)
                == TaskLogic.CalendarDayState.LEAVE);
    }

    /** 日历着色判定覆盖全部状态。 */
    private static void testCalendarDayState() {
        Task task = new Task("t", "T", MWF, "08:00", "22:00",
                new HashSet<>(Arrays.asList("2026-10-21")), 0L);
        Set<String> completed = new HashSet<>(Arrays.asList("2026-10-19"));
        java.util.Date now = TaskLogic.date(2026, 10, 23, 9, 0);

        check("calendar done", TaskLogic.calendarDayState(task, completed,
                TaskLogic.date(2026, 10, 19, 12, 0), now) == TaskLogic.CalendarDayState.DONE);
        check("calendar leave", TaskLogic.calendarDayState(task, completed,
                TaskLogic.date(2026, 10, 21, 12, 0), now) == TaskLogic.CalendarDayState.LEAVE);
        check("calendar missed", TaskLogic.calendarDayState(task, completed,
                TaskLogic.date(2026, 10, 20, 12, 0), now) == TaskLogic.CalendarDayState.OFF
                // 无请假的同一任务：10-21 是周三（重复日），次日 10-22 看它已是错过
                && TaskLogic.calendarDayState(taskNoLeave(), new HashSet<String>(),
                TaskLogic.date(2026, 10, 21, 12, 0), TaskLogic.date(2026, 10, 22, 12, 0))
                == TaskLogic.CalendarDayState.MISSED);
        check("calendar today active", TaskLogic.calendarDayState(task, completed,
                now, now) == TaskLogic.CalendarDayState.TODAY_ACTIVE);
        check("calendar today leave wins", TaskLogic.calendarDayState(task, completed,
                TaskLogic.date(2026, 10, 23, 9, 0), now) == TaskLogic.CalendarDayState.TODAY_ACTIVE);
        check("calendar off day", TaskLogic.calendarDayState(task, completed,
                TaskLogic.date(2026, 10, 22, 12, 0), now) == TaskLogic.CalendarDayState.OFF);
        check("calendar future repeat day", TaskLogic.calendarDayState(task, completed,
                TaskLogic.date(2026, 10, 26, 12, 0), now) == TaskLogic.CalendarDayState.FUTURE);
        check("calendar future off day", TaskLogic.calendarDayState(task, completed,
                TaskLogic.date(2026, 10, 27, 12, 0), now) == TaskLogic.CalendarDayState.OFF);
    }

    /** leaveDates 不可变、不可被外部修改，且 dateKey 格式固定。 */
    private static void testLeaveDataCompatibility() {
        Set<String> source = new HashSet<>(Arrays.asList("2026-10-21"));
        Task task = new Task("t", "T", MWF, "08:00", "22:00", source, 0L);
        source.add("2026-10-23");
        check("leaveDates is defensively copied", task.leaveDates.size() == 1);

        boolean immutable;
        try {
            task.leaveDates.add("2026-10-25");
            immutable = false;
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        check("leaveDates is unmodifiable", immutable);
        check("empty leave dates default to empty set", new Task("x", "X", MWF, "08:00", "22:00",
                (Set<String>) null, 0L).leaveDates.isEmpty());
        check("dateKey format", TaskLogic.dateKey(TaskLogic.date(2026, 1, 5, 0, 0)).equals("2026-01-05"));
    }

    private static Task task(boolean[] weekdays, String... leaveKeys) {
        return new Task("task-1", "Exercise", weekdays, "08:00", "22:00",
                new HashSet<>(Arrays.asList(leaveKeys)), 0L);
    }

    /** 同样的周一/三/五任务，但不带任何请假记录，用于对比「请假」与「错过」。 */
    private static Task taskNoLeave() {
        return new Task("task-1", "Exercise", MWF, "08:00", "22:00", 0L);
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
