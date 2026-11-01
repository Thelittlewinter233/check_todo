package test;

import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.TaskLogic;
import com.checktodo.app.domain.TaskStatus;
import com.checktodo.app.domain.Todo;
import com.checktodo.app.domain.TodoLogic;
import com.checktodo.app.domain.WidgetRefreshLogic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 2026-10-27 两项真机反馈的回归测试：
 *
 * <ol>
 *   <li><b>紧急度排序</b>：打卡与待办（含小卡片同路径）组内按「最晚开始时刻 =
 *       截止时间 − 完成所需时间」升序——越紧急越靠上，而不是单纯的截止时间升序。</li>
 *   <li><b>卡片按时间实时刷新</b>：状态跃迁点（任务开始/结束、待办截止、翻日）
 *       显式化为刷新边界（WidgetRefreshLogic），供 AlarmManager 调度。</li>
 * </ol>
 */
public final class UrgencyOrderRefreshTest {
    private static final boolean[] EVERY_DAY =
            new boolean[]{true, true, true, true, true, true, true};
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testTaskUrgencyOrdering();
        testTaskUrgencyTieBreak();
        testDoneGroupKeepsEndTimeOrder();
        testTodoUrgencyOrdering();
        testTodoUniformDurationKeepsEndTimeOrder();
        testNextRefreshAtTaskBoundaries();
        testNextRefreshAtTodoAndMidnight();
        testNextRefreshAtExcludesIrrelevant();
        testSchedulerWiringBySource();
        testSortReturnCapturedInWidgetFactory();

        // 2026-10-28 真机反馈：卡片分组顺序 / 卡片已错过不刷新 / 完成待办即消失
        testWidgetTaskGroupOrder();
        testWidgetTaskUrgencyWithinGroup();
        testWidgetTaskLeaveLast();
        testTodayViewOrderUnchangedByWidgetSort();
        testRefreshBoundsCollectsAll();
        testRefreshBoundsDedupAndSorted();
        testRefreshBoundsCap();
        testRefreshBoundsAlwaysHasMidnight();
        testRefreshBoundsLegacySingleValue();
        testIndependentAlarmWiringBySource();
        testWidgetTodosHideCompleted();
        report();
    }

    // ------------------------------------------------------------------
    // 1. 紧急度排序
    // ------------------------------------------------------------------

    private static void testTaskUrgencyOrdering() {
        // A：22:00 截止、需 120 分钟 → 最晚 20:00 开始
        // B：21:00 截止、需  5 分钟 → 最晚 20:55 开始
        // B 的截止更早，但 A 更紧急（再不做就来不及了）→ A 在前。
        Task a = task("a", "Alpha", "22:00", 120);
        Task b = task("b", "Beta", "21:00", 5);
        List<Task> sorted = TaskLogic.sortForToday(Arrays.asList(b, a), new HashMap<>(),
                date(2026, 10, 27, 10, 0));
        check("★进行中按 截止−所需 排序（紧急在前）", ids(sorted).equals(Arrays.asList("a", "b")));
    }

    private static void testTaskUrgencyTieBreak() {
        // A：22:00 截止、需 120 分钟 → 1200；B：21:00 截止、需 60 分钟 → 1200。同刻 → 按名。
        Task a = task("a", "Alpha", "22:00", 120);
        Task b = task("b", "Beta", "21:00", 60);
        List<Task> sorted = TaskLogic.sortForToday(Arrays.asList(b, a), new HashMap<>(),
                date(2026, 10, 27, 10, 0));
        check("同一切换刻按名字排序保持确定性", ids(sorted).equals(Arrays.asList("a", "b")));
    }

    private static void testDoneGroupKeepsEndTimeOrder() {
        // 已完成没有「紧急度」概念：组内仍按截止时间升序。
        Task evening = task("evening", "Evening", "20:00", 60);
        Task morning = task("morning", "Morning", "09:00", 60);
        Map<String, Set<String>> completed = new HashMap<>();
        completed.put("evening", set("2026-10-27"));
        completed.put("morning", set("2026-10-27"));
        List<Task> sorted = TaskLogic.sortForToday(Arrays.asList(evening, morning), completed,
                date(2026, 10, 27, 21, 0));
        check("已完成组仍按截止时间升序", ids(sorted).equals(Arrays.asList("morning", "evening")));
    }

    private static void testTodoUrgencyOrdering() {
        // X：22:00 截止、需 120 分钟 → 最晚 20:00；Y：21:00 截止、需 5 分钟 → 20:55。
        Todo x = todo("x", "X", date(2026, 10, 27, 22, 0).getTime(), 120);
        Todo y = todo("y", "Y", date(2026, 10, 27, 21, 0).getTime(), 5);
        List<Todo> pending = TodoLogic.pending(Arrays.asList(y, x));
        check("★待办 pending 按 截止−时长 排序", todoIds(pending).equals(Arrays.asList("x", "y")));
        List<Todo> widget = TodoLogic.filterWidgetTodos(Arrays.asList(y, x),
                date(2026, 10, 27, 10, 0).getTime());
        check("★小卡片待办过滤同口径（filterWidgetTodos）",
                todoIds(widget).equals(Arrays.asList("x", "y")));
        List<Todo> completed = TodoLogic.completed(Arrays.asList(
                y.withCompletion(true), x.withCompletion(true)));
        check("已完成待办同口径", todoIds(completed).equals(Arrays.asList("x", "y")));
    }

    private static void testTodoUniformDurationKeepsEndTimeOrder() {
        // 花费时长相同时退化为截止时间升序（旧口径兼容，防回归）。
        Todo p = todo("p", "P", date(2026, 10, 27, 10, 0).getTime(), 0);
        Todo q = todo("q", "Q", date(2026, 10, 27, 12, 0).getTime(), 0);
        List<Todo> pending = TodoLogic.pending(Arrays.asList(q, p));
        check("花费时长相同 → 退化为截止时间升序", todoIds(pending).equals(Arrays.asList("p", "q")));
    }

    // ------------------------------------------------------------------
    // 2. 卡片实时刷新边界
    // ------------------------------------------------------------------

    private static void testNextRefreshAtTaskBoundaries() {
        Task t = new Task("t", "T", EVERY_DAY, "09:00", "18:00", 0L);
        List<Task> tasks = Arrays.asList(t);

        long at0830 = WidgetRefreshLogic.nextRefreshAt(tasks, null, date(2026, 10, 27, 8, 30));
        check("★开始前 → 下一边界 = 今日 startTime 09:00",
                at0830 == date(2026, 10, 27, 9, 0).getTime());

        long at10 = WidgetRefreshLogic.nextRefreshAt(tasks, null, date(2026, 10, 27, 10, 0));
        check("★进行中 → 下一边界 = 今日 endTime 18:00",
                at10 == date(2026, 10, 27, 18, 0).getTime());

        long at19 = WidgetRefreshLogic.nextRefreshAt(tasks, null, date(2026, 10, 27, 19, 0));
        check("★已错过 → 下一边界 = 次日 00:00（翻日）",
                at19 == midnight(2026, 10, 28));
    }

    private static void testNextRefreshAtTodoAndMidnight() {
        // 待办截止在今晚 21:30（早于翻日点）→ 它就是下一边界。
        long todoEnd = date(2026, 10, 27, 21, 30).getTime();
        Todo todo = todo("t1", "Todo", todoEnd, 30);
        List<Todo> todos = Arrays.asList(todo);

        long onlyTodo = WidgetRefreshLogic.nextRefreshAt(null, todos, date(2026, 10, 27, 19, 0));
        check("无任务时 → 下一边界 = 待办截止（48h 内且早于翻日）", onlyTodo == todoEnd);

        Task t = new Task("t", "T", EVERY_DAY, "09:00", "18:00", 0L);
        long mixed = WidgetRefreshLogic.nextRefreshAt(Arrays.asList(t), todos,
                date(2026, 10, 27, 19, 0));
        check("任务与待办并存 → 取最早边界（此例为待办截止）", mixed == todoEnd);

        // 待办截止越过翻日点 → 翻日 00:00 更早，成为下一边界。
        Todo nextDay = todo("t3", "NextDay", date(2026, 10, 28, 10, 0).getTime(), 30);
        long overnight = WidgetRefreshLogic.nextRefreshAt(null, Arrays.asList(nextDay),
                date(2026, 10, 27, 19, 0));
        check("待办截止在翻日之后 → 下一边界 = 翻日 00:00", overnight == midnight(2026, 10, 28));

        Todo completedTodo = todo("t2", "Done", todoEnd, 30).withCompletion(true);
        long done = WidgetRefreshLogic.nextRefreshAt(null,
                Arrays.asList(completedTodo), date(2026, 10, 27, 19, 0));
        check("已完成待办不产生边界", done == midnight(2026, 10, 28));
    }

    private static void testNextRefreshAtExcludesIrrelevant() {
        // 今日非重复日的任务：其开始/结束时刻不产生边界（翻日点接管）。
        Task sundayOnly = new Task("sun", "Sun",
                new boolean[]{false, false, false, false, false, false, true},
                "09:00", "18:00", 0L);
        long at = WidgetRefreshLogic.nextRefreshAt(Arrays.asList(sundayOnly), null,
                date(2026, 10, 27, 10, 0));   // 2026-10-27 周二
        check("非重复日任务的边界被忽略 → 翻日点", at == midnight(2026, 10, 28));

        // 48h 窗口外的待办截止不产生边界。
        Todo far = todo("far", "Far", date(2026, 10, 31, 10, 0).getTime(), 30);
        long farAt = WidgetRefreshLogic.nextRefreshAt(null, Arrays.asList(far),
                date(2026, 10, 27, 10, 0));
        check("48h 窗口外的待办截止被忽略", farAt == midnight(2026, 10, 28));
    }

    // ------------------------------------------------------------------
    // 3. 调度接线（读源码断言）
    // ------------------------------------------------------------------

    private static void testSchedulerWiringBySource() {
        String widget = readSource("src/com/checktodo/app/widget/CheckTodoWidget.java");
        check("★ACTION_REFRESH 分支触发后继续调度下一边界",
                widget.contains("WidgetRefreshScheduler.scheduleNext"));
        String scheduler = readSource("src/com/checktodo/app/notification/ReminderScheduler.java");
        check("★rescheduleAll（数据变更统一入口）末尾挂接卡片刷新调度",
                scheduler.contains("WidgetRefreshScheduler.scheduleNext"));
        String alarm = readSource("src/com/checktodo/app/widget/WidgetRefreshScheduler.java");
        check("★刷新闹钟走 RTC_WAKEUP（Doze 下也要准点）", alarm.contains("RTC_WAKEUP"));
        check("★刷新闹钟发往 CheckTodoWidget 的 ACTION_REFRESH",
                alarm.contains("ACTION_REFRESH") && alarm.contains("CheckTodoWidget.class"));
    }

    /**
     * 2026-10-29 真机回归「已错过混在中间」：排序函数（sortForWidgetToday /
     * sortForToday / sortByUrgency）都是<b>纯函数</b>——返回新列表、不改参数。
     * buildRows 曾把返回值丢弃，卡片因此一直按仓库原始顺序渲染，分组与紧急度完全不生效。
     * 本断言钉死：调用点必须接收返回值。
     */
    private static void testSortReturnCapturedInWidgetFactory() {
        String service = readSource("src/com/checktodo/app/widget/CheckTodoWidgetService.java");
        check("★buildRows 接收 sortForWidgetToday 的返回值（纯函数不改参）",
                service.contains("= TaskLogic.sortForWidgetToday("));
        // 裸调用 = 行首直接以方法调用开头（语句位置丢弃返回值）；赋值行尾部含同文本是合法的。
        boolean bareCall = false;
        for (String line : service.split("\n")) {
            if (line.trim().startsWith("TaskLogic.sortForWidgetToday(")) bareCall = true;
        }
        check("★不存在丢弃返回值的裸调用（历史 bug 防回归）", !bareCall);
        String main = readSource("src/com/checktodo/app/MainActivity.java");
        check("App 今日视图调用点接收 sortForToday 返回值（既有正确用法防回退）",
                main.contains("return TaskLogic.sortForToday("));
    }

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 4. 卡片专用分组顺序（2026-10-28 真机反馈）
    // ------------------------------------------------------------------

    /** 四种状态并存：卡片序 ACTIVE → UPCOMING → DONE → MISSED。 */
    private static void testWidgetTaskGroupOrder() {
        Date now = date(2026, 10, 27, 10, 30);
        Task done = new Task("done", "Done", EVERY_DAY, "08:00", "09:00", new HashSet<String>(), 0L, 10);
        Task missed = new Task("missed", "Missed", EVERY_DAY, "08:00", "10:00", new HashSet<String>(), 0L, 10);
        Task upcoming = new Task("upcoming", "Upcoming", EVERY_DAY, "12:00", "13:00", new HashSet<String>(), 0L, 10);
        Task active = new Task("active", "Active", EVERY_DAY, "10:00", "11:00", new HashSet<String>(), 0L, 10);
        Map<String, Set<String>> completed = new HashMap<>();
        completed.put("done", set("2026-10-27"));

        List<Task> tasks = Arrays.asList(missed, done, active, upcoming);
        List<Task> widget = TaskLogic.sortForWidgetToday(tasks, completed, now);
        check("★卡片序：ACTIVE → UPCOMING → DONE → MISSED",
                ids(widget).equals(Arrays.asList("active", "upcoming", "done", "missed")));
        // 打乱输入顺序也必须同结果（确定性）。
        List<Task> shuffled = TaskLogic.sortForWidgetToday(
                Arrays.asList(upcoming, missed, active, done), completed, now);
        check("卡片序与输入顺序无关", ids(shuffled).equals(
                Arrays.asList("active", "upcoming", "done", "missed")));
    }

    /** 四组组内均按「截止 − 完成所需时间」升序。 */
    private static void testWidgetTaskUrgencyWithinGroup() {
        Date now = date(2026, 10, 27, 10, 30);
        // ACTIVE：a1 截止 11:00 需 10 → 10:50；a2 截止 12:00 需 120 → 10:00（更紧急）
        Task a1 = new Task("a1", "A1", EVERY_DAY, "10:00", "11:00", new HashSet<String>(), 0L, 10);
        Task a2 = new Task("a2", "A2", EVERY_DAY, "09:00", "12:00", new HashSet<String>(), 0L, 120);
        // UPCOMING：u1 截止 20:00 需 10 → 19:50；u2 截止 18:00 需 120 → 16:00
        Task u1 = new Task("u1", "U1", EVERY_DAY, "19:00", "20:00", new HashSet<String>(), 0L, 10);
        Task u2 = new Task("u2", "U2", EVERY_DAY, "15:00", "18:00", new HashSet<String>(), 0L, 120);
        // DONE：d1 截止 09:30 需 10 → 09:20；d2 截止 09:00 需 120 被钳到 1440 → 负值
        Task d1 = new Task("d1", "D1", EVERY_DAY, "08:00", "09:30", new HashSet<String>(), 0L, 10);
        Task d2 = new Task("d2", "D2", EVERY_DAY, "08:00", "09:00", new HashSet<String>(), 0L, 120);
        // MISSED：m1 截止 10:00 需 10 → 09:50；m2 截止 10:20 需 60 → 09:20（更紧急）
        Task m1 = new Task("m1", "M1", EVERY_DAY, "08:00", "10:00", new HashSet<String>(), 0L, 10);
        Task m2 = new Task("m2", "M2", EVERY_DAY, "08:00", "10:20", new HashSet<String>(), 0L, 60);

        Map<String, Set<String>> completed = new HashMap<>();
        completed.put("d1", set("2026-10-27"));
        completed.put("d2", set("2026-10-27"));

        List<Task> widget = TaskLogic.sortForWidgetToday(
                Arrays.asList(m2, u1, d1, a1, m1, d2, u2, a2), completed, now);
        check("★卡片组内按 截止−所需 升序（ACTIVE 组）",
                ids(widget).subList(0, 2).equals(Arrays.asList("a2", "a1")));
        check("★卡片组内按 截止−所需 升序（UPCOMING 组）",
                ids(widget).subList(2, 4).equals(Arrays.asList("u2", "u1")));
        check("★卡片组内按 截止−所需 升序（DONE 组）",
                ids(widget).subList(4, 6).equals(Arrays.asList("d2", "d1")));
        check("★卡片组内按 截止−所需 升序（MISSED 组）",
                ids(widget).subList(6, 8).equals(Arrays.asList("m2", "m1")));
    }

    /** 请假排在最末（且 OFF 不进卡片业务，由调用方过滤）。 */
    private static void testWidgetTaskLeaveLast() {
        Date now = date(2026, 10, 27, 10, 30);
        Task leave = new Task("leave", "Leave", EVERY_DAY, "09:00", "10:00",
                set("2026-10-27"), 0L, 10);
        Task missed = new Task("missed", "Missed", EVERY_DAY, "08:00", "10:00", new HashSet<String>(), 0L, 10);
        Task upcoming = new Task("upcoming", "Upcoming", EVERY_DAY, "12:00", "13:00", new HashSet<String>(), 0L, 10);
        List<Task> widget = TaskLogic.sortForWidgetToday(
                Arrays.asList(leave, missed, upcoming), new HashMap<>(), now);
        check("★卡片请假分组在最末（UPCOMING 之后）",
                ids(widget).equals(Arrays.asList("upcoming", "missed", "leave")));
        check("卡片序编号 widgetRank：ACTIVE<UPCOMING<DONE<MISSED<LEAVE<OFF",
                TaskLogic.widgetRank(TaskStatus.ACTIVE) < TaskLogic.widgetRank(TaskStatus.UPCOMING)
                        && TaskLogic.widgetRank(TaskStatus.UPCOMING) < TaskLogic.widgetRank(TaskStatus.DONE)
                        && TaskLogic.widgetRank(TaskStatus.DONE) < TaskLogic.widgetRank(TaskStatus.MISSED)
                        && TaskLogic.widgetRank(TaskStatus.MISSED) < TaskLogic.widgetRank(TaskStatus.LEAVE)
                        && TaskLogic.widgetRank(TaskStatus.LEAVE) < TaskLogic.widgetRank(TaskStatus.OFF));
    }

    /** App 内今日视图排序不受卡片序影响（TaskOrderTest 口径原样保留）。 */
    private static void testTodayViewOrderUnchangedByWidgetSort() {
        Date now = date(2026, 10, 27, 10, 30);
        Task done = new Task("done", "Done", EVERY_DAY, "08:00", "09:00", new HashSet<String>(), 0L, 10);
        Task missed = new Task("missed", "Missed", EVERY_DAY, "08:00", "10:00", new HashSet<String>(), 0L, 10);
        Task upcoming = new Task("upcoming", "Upcoming", EVERY_DAY, "12:00", "13:00", new HashSet<String>(), 0L, 10);
        Task active = new Task("active", "Active", EVERY_DAY, "10:00", "11:00", new HashSet<String>(), 0L, 10);
        Map<String, Set<String>> completed = new HashMap<>();
        completed.put("done", set("2026-10-27"));
        List<Task> app = TaskLogic.sortForToday(
                Arrays.asList(upcoming, missed, active, done), completed, now);
        check("★App 今日视图排序保持 ACTIVE→DONE→MISSED→UPCOMING",
                ids(app).equals(Arrays.asList("active", "done", "missed", "upcoming")));
    }

    // ------------------------------------------------------------------
    // 5. 刷新边界列表 + 独立闹钟（防单链断裂）
    // ------------------------------------------------------------------

    private static void testRefreshBoundsCollectsAll() {
        Date now = date(2026, 10, 27, 8, 0);
        Task t1 = new Task("t1", "T1", EVERY_DAY, "09:00", "18:00", 0L);
        Task t2 = new Task("t2", "T2", EVERY_DAY, "12:00", "20:00", 0L);
        Todo todo = todo("td", "Todo", date(2026, 10, 27, 21, 30).getTime(), 30);
        List<Long> bounds = WidgetRefreshLogic.nextRefreshBounds(
                Arrays.asList(t1, t2), Arrays.asList(todo), now, 32);
        check("★边界列表包含全部任务 start/end",
                bounds.contains(date(2026, 10, 27, 9, 0).getTime())
                        && bounds.contains(date(2026, 10, 27, 12, 0).getTime())
                        && bounds.contains(date(2026, 10, 27, 18, 0).getTime())
                        && bounds.contains(date(2026, 10, 27, 20, 0).getTime()));
        check("★边界列表包含待办截止与翻日点",
                bounds.contains(date(2026, 10, 27, 21, 30).getTime())
                        && bounds.contains(midnight(2026, 10, 28)));
        check("★边界列表不含已过去的时刻",
                WidgetRefreshLogic.nextRefreshBounds(null, null,
                        date(2026, 10, 27, 10, 0), 32)
                        .contains(date(2026, 10, 27, 9, 0).getTime()) == false);
    }

    private static void testRefreshBoundsDedupAndSorted() {
        Date now = date(2026, 10, 27, 8, 0);
        // 两个任务的结束时刻相同 → 只保留一个。
        Task t1 = new Task("t1", "T1", EVERY_DAY, "09:00", "18:00", 0L);
        Task t2 = new Task("t2", "T2", EVERY_DAY, "10:00", "18:00", 0L);
        List<Long> bounds = WidgetRefreshLogic.nextRefreshBounds(
                Arrays.asList(t1, t2), null, now, 32);
        int hits = 0;
        for (long b : bounds) if (b == date(2026, 10, 27, 18, 0).getTime()) hits++;
        check("★重复边界去重（同一时刻只挂一个闹钟）", hits == 1);
        boolean ascending = true;
        for (int i = 1; i < bounds.size(); i++) {
            if (bounds.get(i) <= bounds.get(i - 1)) ascending = false;
        }
        check("★边界列表严格升序", ascending);
    }

    private static void testRefreshBoundsCap() {
        Date now = date(2026, 10, 27, 0, 1);
        List<Task> many = new ArrayList<>();
        for (int minute = 5; minute < 58 * 60 && many.size() < 80; minute += 37) {
            String end = String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60);
            many.add(new Task("t" + minute, "T" + minute, EVERY_DAY, "00:02", end, 0L));
        }
        List<Long> bounds = WidgetRefreshLogic.nextRefreshBounds(many, null, now, 32);
        check("★边界数量受 cap 限制（≤32）", bounds.size() == 32);
        List<Long> tiny = WidgetRefreshLogic.nextRefreshBounds(many, null, now, 5);
        check("cap=5 时只保留最早的 5 个且仍含未来时刻", tiny.size() == 5
                && tiny.get(0) < tiny.get(4));
    }

    private static void testRefreshBoundsAlwaysHasMidnight() {
        Date now = date(2026, 10, 27, 23, 30);
        List<Long> none = WidgetRefreshLogic.nextRefreshBounds(null, null, now, 32);
        check("★无任何数据时仍有翻日点", none.size() == 1 && none.get(0) == midnight(2026, 10, 28));

        // 翻日点不会因 cap 被挤掉：先塞满更早的待办边界。
        List<Todo> manyTodos = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            manyTodos.add(todo("p" + i, "P" + i, now.getTime() + (i + 1) * 60_000L, 0));
        }
        List<Long> bounds = WidgetRefreshLogic.nextRefreshBounds(null, manyTodos, now, 32);
        check("★翻日点恒在边界列表内（cap 挤占也不丢）",
                bounds.contains(midnight(2026, 10, 28)) && bounds.size() == 32);
    }

    private static void testRefreshBoundsLegacySingleValue() {
        // 兼容：单值函数 = 边界列表首个。
        Task t = new Task("t", "T", EVERY_DAY, "09:00", "18:00", 0L);
        Date now = date(2026, 10, 27, 8, 30);
        List<Long> bounds = WidgetRefreshLogic.nextRefreshBounds(Arrays.asList(t), null, now,
                WidgetRefreshLogic.MAX_BOUNDS);
        check("单值函数与边界列表首个一致",
                WidgetRefreshLogic.nextRefreshAt(Arrays.asList(t), null, now) == bounds.get(0));
        check("MAX_BOUNDS 为 32", WidgetRefreshLogic.MAX_BOUNDS == 32);
    }

    /** 源码断言：每个边界独立闹钟 + 确定性 requestCode + 过期清理 + 诊断日志。 */
    private static void testIndependentAlarmWiringBySource() {
        String alarm = readSource("src/com/checktodo/app/widget/WidgetRefreshScheduler.java");
        check("★调度器改用边界列表接口", alarm.contains("nextRefreshBounds"));
        check("★每个边界独立 requestCode（由边界时刻派生）",
                alarm.contains("requestCodeFor") && alarm.contains("alarms.setExactAndAllowWhileIdle"));
        check("★每轮取消已过期/已变化的边界（prefs 登记集合）",
                alarm.contains("KEY_CODES") && alarm.contains("alarms.cancel"));
        check("★诊断日志：每个边界时刻 + exact/inexact 模式",
                alarm.contains("Log.i(TAG") && alarm.contains("alarmMode("));
        String widget = readSource("src/com/checktodo/app/widget/CheckTodoWidgetService.java");
        check("★卡片打卡分支改走卡片专用排序",
                widget.contains("TaskLogic.sortForWidgetToday")
                        && widget.contains("TaskLogic.sortForToday") == false);
        String todoLogic = readSource("src/com/checktodo/app/domain/TodoLogic.java");
        check("★filterWidgetTodos 过滤已完成（完成即消失）",
                todoLogic.contains("if (todo.completed) continue;"));
    }

    private static void testWidgetTodosHideCompleted() {
        long now = date(2026, 10, 27, 10, 0).getTime();
        Todo pending = todo("p", "Pending", now + 3600_000L, 30);
        Todo done = todo("d", "Done", now + 7200_000L, 30).withCompletion(true);
        List<Todo> widget = TodoLogic.filterWidgetTodos(Arrays.asList(pending, done), now);
        check("★已完成待办立即从卡片消失", todoIds(widget).equals(Arrays.asList("p")));
    }

    // ------------------------------------------------------------------

    private static Task task(String id, String name, String endTime, int requiredMinutes) {
        return new Task(id, name, EVERY_DAY, "08:00", endTime,
                new HashSet<String>(), 0L, requiredMinutes);
    }

    private static Todo todo(String id, String title, long endTime, int durationMinutes) {
        return new Todo(id, title, endTime - 2L * 60 * 60 * 1000, endTime, durationMinutes, false, 0L);
    }

    private static Date date(int year, int month, int day, int hour, int minute) {
        return TaskLogic.date(year, month, day, hour, minute);
    }

    private static long midnight(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month - 1, day, 0, 0, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static Set<String> set(String... keys) {
        return new HashSet<>(Arrays.asList(keys));
    }

    private static List<String> ids(List<Task> tasks) {
        List<String> result = new ArrayList<>();
        for (Task task : tasks) result.add(task.id);
        return result;
    }

    private static List<String> todoIds(List<Todo> todos) {
        List<String> result = new ArrayList<>();
        for (Todo todo : todos) result.add(todo.id);
        return result;
    }

    private static String readSource(String path) {
        java.io.InputStream stream = null;
        try {
            stream = new java.io.FileInputStream(new java.io.File(path));
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) >= 0) buffer.write(chunk, 0, count);
            return new String(buffer.toByteArray(), "UTF-8");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("读取源码失败：" + path, exception);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (java.io.IOException ignored) {
                    // ignore
                }
            }
        }
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

    private static void report() {
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
