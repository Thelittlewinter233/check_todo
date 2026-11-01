import com.checktodo.app.domain.ReminderLogic;
import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.TaskLogic;
import com.checktodo.app.domain.TaskStatus;
import com.checktodo.app.domain.Todo;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/** 提醒时刻与调度条件的纯 Java 测试：公式、兜底、状态过滤、时间边界。 */
public final class ReminderTest {
    private static final boolean[] MWF = {true, false, true, false, true, false, false};
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testTaskReminderDefault();
        testTaskReminderCustom();
        testOneMinuteShortWindowScenario();
        testLegacyRequiredMinutesFallback();
        testTaskSchedulingRules();
        testReminderBeforeTodayNotScheduled();
        testTodoReminder();
        testTodoSchedulingRules();
        testBodyText();
        testRequiredMinutesBounds();
        testRequiredMinutesClampingConsistency();
        testCreatedAfterDeadlineStatus();
        testNextDayNormalStateMachine();
        testCreatedBeforeDeadlineUnaffected();

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    /** 默认 60 分钟：22:00 截止 → 20:00 提醒。 */
    private static void testTaskReminderDefault() {
        Task task = task("22:00", 60);
        long reminder = ReminderLogic.computeTaskReminderTime(task,
                TaskLogic.date(2026, 10, 23, 10, 0));
        check("默认 60 分钟提醒时刻 20:00",
                reminder == TaskLogic.date(2026, 10, 23, 20, 0).getTime());
        check("默认所需分钟兜底 60", ReminderLogic.requiredMinutes(task) == 60);
    }

    /** 自定义 15 分钟：22:00 截止 → 20:45 提醒。 */
    private static void testTaskReminderCustom() {
        Task task = task("22:00", 15);
        long reminder = ReminderLogic.computeTaskReminderTime(task,
                TaskLogic.date(2026, 10, 23, 10, 0));
        check("自定义 15 分钟提醒时刻 20:45",
                reminder == TaskLogic.date(2026, 10, 23, 20, 45).getTime());
    }

    /** 老数据 requiredMinutes 缺失或非正：按 60 兜底。 */
    private static void testLegacyRequiredMinutesFallback() {
        Task legacy = task("22:00", 0);
        check("legacy 0 按 60 兜底", ReminderLogic.requiredMinutes(legacy) == 60);
        long reminder = ReminderLogic.computeTaskReminderTime(legacy,
                TaskLogic.date(2026, 10, 23, 10, 0));
        check("legacy 0 提醒仍为 20:00",
                reminder == TaskLogic.date(2026, 10, 23, 20, 0).getTime());
    }

    private static void testTaskSchedulingRules() {
        Date now = TaskLogic.date(2026, 10, 23, 20, 30);
        Task active = task("22:00", 15);
        check("今天需要且未完成可调度",
                ReminderLogic.shouldScheduleTask(active, set(), now));

        check("今天已完成不调度", !ReminderLogic.shouldScheduleTask(active,
                set("2026-10-23"), now));
        Task leave = new Task("leave", "Leave", MWF, "08:00", "22:00",
                set("2026-10-23"), TaskLogic.date(2026, 10, 5, 9, 0).getTime(), 15);
        check("今天请假不调度", !ReminderLogic.shouldScheduleTask(leave, set(), now));

        boolean[] tuesdayOnly = {false, true, false, false, false, false, false};
        Task offDay = new Task("off", "Off", tuesdayOnly, "08:00", "22:00",
                null, 0L, 15);
        check("今天非重复日不调度", !ReminderLogic.shouldScheduleTask(offDay, set(), now));

        Date afterEnd = TaskLogic.date(2026, 10, 23, 22, 1);
        check("已过截止不调度", !ReminderLogic.shouldScheduleTask(active, set(), afterEnd));

        Date reminderPassed = TaskLogic.date(2026, 10, 23, 20, 46);
        check("提醒时刻已过但截止未到时仍可调度",
                ReminderLogic.shouldScheduleTask(active, set(), reminderPassed));
        check("已过提醒改为 5 秒后立即提醒",
                ReminderLogic.resolveTaskScheduleTime(active, set(), reminderPassed)
                        == reminderPassed.getTime() + 5_000L);
    }

    /** 截止 01:00、默认 60 分钟 → 提醒落到昨天 23:00，今天不再补发。 */
    private static void testReminderBeforeTodayNotScheduled() {
        Task task = task("01:00", 60);
        Date now = TaskLogic.date(2026, 10, 23, 0, 30);
        long reminder = ReminderLogic.computeTaskReminderTime(task, now);
        check("越界到昨天的提醒改为立即提醒",
                reminder == TaskLogic.date(2026, 10, 22, 23, 0).getTime()
                        && ReminderLogic.shouldScheduleTask(task, set(), now)
                        && ReminderLogic.resolveTaskScheduleTime(task, set(), now)
                                == now.getTime() + 5_000L);
    }

    private static void testTodoReminder() {
        Todo todo = new Todo("todo1", "Write report",
                TaskLogic.date(2026, 10, 23, 12, 0).getTime(),
                TaskLogic.date(2026, 10, 23, 18, 0).getTime(),
                30, false, 0L);
        long reminder = ReminderLogic.computeTodoReminderTime(todo);
        check("待办 30 分钟提醒 16:30",
                reminder == TaskLogic.date(2026, 10, 23, 16, 30).getTime());
    }

    private static void testTodoSchedulingRules() {
        Todo pending = new Todo("todo1", "Pending",
                TaskLogic.date(2026, 10, 23, 12, 0).getTime(),
                TaskLogic.date(2026, 10, 23, 18, 0).getTime(),
                30, false, 0L);
        long now = TaskLogic.date(2026, 10, 23, 15, 0).getTime();
        check("今天未完成待办可调度", ReminderLogic.shouldScheduleTodo(pending, now));

        Todo completed = pending.withCompletion(true);
        check("已完成待办不调度", !ReminderLogic.shouldScheduleTodo(completed, now));

        Todo yesterday = new Todo("todo2", "Yesterday",
                TaskLogic.date(2026, 10, 22, 12, 0).getTime(),
                TaskLogic.date(2026, 10, 22, 18, 0).getTime(),
                30, false, 0L);
        check("截止日在昨天不调度", !ReminderLogic.shouldScheduleTodo(yesterday, now));

        long afterReminder = TaskLogic.date(2026, 10, 23, 16, 31).getTime();
        check("待办提醒时刻已过但截止未到时改为立即提醒",
                ReminderLogic.shouldScheduleTodo(pending, afterReminder)
                        && ReminderLogic.resolveTodoScheduleTime(pending, afterReminder)
                                == afterReminder + 5_000L);
    }

    private static void testBodyText() {
        Task task = task("22:00", 45);
        check("任务通知正文", ReminderLogic.taskBodyText(task)
                .equals("Task｜截止 22:00｜需 45 分钟完成"));
        Todo todo = new Todo("todo1", "Report",
                TaskLogic.date(2026, 10, 23, 12, 0).getTime(),
                TaskLogic.date(2026, 10, 23, 18, 0).getTime(),
                30, false, 0L);
        check("待办通知正文", ReminderLogic.todoBodyText(todo)
                .equals("Report｜截止 10月23日 18:00｜需 30 分钟完成"));
    }

    private static void testRequiredMinutesBounds() {
        check("0 分钟非法", !ReminderLogic.isValidRequiredMinutes(0));
        check("1 分钟合法", ReminderLogic.isValidRequiredMinutes(1));
        check("1440 分钟合法", ReminderLogic.isValidRequiredMinutes(1440));
        check("1441 分钟非法", !ReminderLogic.isValidRequiredMinutes(1441));
    }

    /** 真机反馈场景：15:01 创建、16:04 截止、所需 1 分钟 → 15:03 提醒。 */
    private static void testOneMinuteShortWindowScenario() {
        Task task = task("16:04", 1);
        Date now = TaskLogic.date(2026, 10, 23, 15, 1);
        long reminder = ReminderLogic.computeTaskReminderTime(task, now);
        check("1 分钟不被钳制成 60", ReminderLogic.requiredMinutes(task) == 1);
        check("真机场景提醒时刻为 15:03",
                reminder == TaskLogic.date(2026, 10, 23, 15, 3).getTime());
        check("真机场景闹钟触发时仍有效",
                ReminderLogic.isTaskReminderValidAtTrigger(task, set(),
                        TaskLogic.date(2026, 10, 23, 15, 3)));
        check("触发时提醒已过则改为立即提醒",
                ReminderLogic.resolveTaskScheduleTime(task, set(),
                        TaskLogic.date(2026, 10, 23, 15, 3))
                        == TaskLogic.date(2026, 10, 23, 15, 3).getTime() + 5_000L);
    }

    private static void testRequiredMinutesClampingConsistency() {
        check("1 分钟保持 1", ReminderLogic.requiredMinutes(task("22:00", 1)) == 1);
        check("legacy 0 钳制成 60", ReminderLogic.requiredMinutes(task("22:00", 0)) == 60);
        check("超过上限钳制成 1440", ReminderLogic.requiredMinutes(task("22:00", 1441)) == 1440);
    }

    /** 截止后创建：当天显示未开始，提醒时刻已过也不再调度。 */
    private static void testCreatedAfterDeadlineStatus() {
        Date created = TaskLogic.date(2026, 10, 23, 23, 0);
        Date now = TaskLogic.date(2026, 10, 23, 23, 30);
        Task task = new Task("late", "Late", MWF, "08:00", "22:00", null,
                created.getTime(), 15);
        check("截止后创建当天状态为未开始",
                TaskLogic.statusOf(task, set(), now) == TaskStatus.UPCOMING);
        check("截止后创建当天不调度提醒",
                !ReminderLogic.shouldScheduleTask(task, set(), now));
    }

    /** 第二天恢复正常状态机：开始前未开始，窗口内可打卡，截止后已错过。 */
    private static void testNextDayNormalStateMachine() {
        Task task = new Task("next", "Next", MWF, "08:00", "22:00", null,
                TaskLogic.date(2026, 10, 23, 23, 0).getTime(), 15);
        check("次日开始前为未开始",
                TaskLogic.statusOf(task, set(), TaskLogic.date(2026, 10, 26, 7, 0))
                        == TaskStatus.UPCOMING);
        check("次日窗口内为可打卡",
                TaskLogic.statusOf(task, set(), TaskLogic.date(2026, 10, 26, 8, 30))
                        == TaskStatus.ACTIVE);
        check("次日截止后为已错过",
                TaskLogic.statusOf(task, set(), TaskLogic.date(2026, 10, 26, 22, 0))
                        == TaskStatus.MISSED);
    }

    /** 截止前创建的任务不受新规则影响，截止后仍是已错过。 */
    private static void testCreatedBeforeDeadlineUnaffected() {
        Date created = TaskLogic.date(2026, 10, 23, 21, 0);
        Date now = TaskLogic.date(2026, 10, 23, 22, 0);
        Task task = new Task("before", "Before", MWF, "08:00", "22:00", null,
                created.getTime(), 15);
        check("截止前创建当天截止后仍为已错过",
                TaskLogic.statusOf(task, set(), now) == TaskStatus.MISSED);
        check("截止前创建提醒已过不调度",
                !ReminderLogic.shouldScheduleTask(task, set(), now));
    }

    private static Task task(String endTime, int requiredMinutes) {
        return new Task("task1", "Task", MWF, "08:00", endTime, null,
                TaskLogic.date(2026, 10, 5, 9, 0).getTime(), requiredMinutes);
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
