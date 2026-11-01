import com.checktodo.app.domain.Todo;
import com.checktodo.app.domain.TodoLogic;
import com.checktodo.app.domain.TodoStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TodoTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testSorting();
        testValidation();
        testCompletionRoundTrip();
        testGroupingDoesNotMutate();
        testStatusLifecycle();
        testFilterWidgetTodos();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void testSorting() {
        Todo first = todo("1", 100L, 300L, false, 3L);
        Todo second = todo("2", 50L, 400L, true, 2L);
        Todo third = todo("3", 200L, 500L, false, 1L);

        List<Todo> sorted = TodoLogic.sortByEndTime(Arrays.asList(third, first, second));
        check("todos sort by end time ascending", sorted.get(0).id.equals("1")
                && sorted.get(1).id.equals("2") && sorted.get(2).id.equals("3"));
    }

    private static void testValidation() {
        check("valid todo has no errors",
                TodoLogic.validateTodo("Write report", 100L, 200L, 30).isEmpty());
        check("blank title is rejected",
                !TodoLogic.validateTodo("  ", 100L, 200L, 30).isEmpty());
        check("end before start is rejected",
                !TodoLogic.validateTodo("Write report", 200L, 100L, 30).isEmpty());
        check("end equal to start is rejected",
                !TodoLogic.validateTodo("Write report", 100L, 100L, 30).isEmpty());
        check("negative duration is rejected",
                !TodoLogic.validateTodo("Write report", 100L, 200L, -1).isEmpty());
    }

    private static void testCompletionRoundTrip() {
        Todo original = todo("1", 100L, 300L, false, 10L);
        Todo done = original.withCompletion(true);
        Todo undone = done.withCompletion(false);

        check("todo can be marked complete", done.completed);
        check("undo restores pending state", !undone.completed);
        check("undo preserves all original fields", undone.title.equals(original.title)
                && undone.startTime == original.startTime && undone.endTime == original.endTime
                && undone.durationMinutes == original.durationMinutes
                && undone.createdAt == original.createdAt);
    }

    private static void testGroupingDoesNotMutate() {
        List<Todo> todos = new ArrayList<>();
        todos.add(todo("1", 100L, 300L, false, 10L));
        todos.add(todo("2", 100L, 200L, true, 20L));

        List<Todo> pending = TodoLogic.pending(todos);
        List<Todo> completed = TodoLogic.completed(todos);

        check("pending group only contains unfinished todos",
                pending.size() == 1 && pending.get(0).id.equals("1"));
        check("completed group only contains finished todos",
                completed.size() == 1 && completed.get(0).id.equals("2"));
        check("grouping does not mutate source", todos.size() == 2
                && !todos.get(0).completed && todos.get(1).completed);
    }

    private static void testStatusLifecycle() {
        Todo todo = todo("1", 100L, 300L, false, 10L);

        check("before start is not started",
                TodoLogic.status(todo, 99L) == TodoStatus.NOT_STARTED);
        check("start boundary is in progress",
                TodoLogic.status(todo, 100L) == TodoStatus.IN_PROGRESS);
        check("before end is in progress",
                TodoLogic.status(todo, 299L) == TodoStatus.IN_PROGRESS);
        check("end boundary is expired",
                TodoLogic.status(todo, 300L) == TodoStatus.EXPIRED);
        check("completed wins over time window",
                TodoLogic.status(todo.withCompletion(true), 150L) == TodoStatus.COMPLETED);
        check("completed wins over expiry",
                TodoLogic.status(todo.withCompletion(true), 500L) == TodoStatus.COMPLETED);
    }

    private static void testFilterWidgetTodos() {
        long now = 1_000_000L;
        List<Todo> todos = new ArrayList<>();
        // 已过期的截止时间最早，排序后自然位于列表顶部。
        todos.add(todo("expired", now - 10_000L, now - 1_000L, false, 1L));
        todos.add(todo("running", now - 5_000L, now + 5_000L, false, 2L));
        // 恰好 48 小时后开始 → 显示（边界含等号）。
        todos.add(todo("edge", now + TodoLogic.WIDGET_UPCOMING_WINDOW_MS,
                now + TodoLogic.WIDGET_UPCOMING_WINDOW_MS + 60_000L, false, 3L));
        // 超过 48 小时后开始 → 不显示。
        todos.add(todo("far", now + TodoLogic.WIDGET_UPCOMING_WINDOW_MS + 1,
                now + TodoLogic.WIDGET_UPCOMING_WINDOW_MS + 60_000L, false, 4L));
        // 已完成 → 2026-10-28 需求变更：完成即从卡片消失（撤销回 App 里做）。
        todos.add(todo("done", now - 5_000L, now + 5_000L, true, 5L));
        // 进行中且未完成，但紧急度排在同窗条目之后，用于验证整体顺序。
        todos.add(todo("later", now + 10_000L, now + 200_000L, false, 6L));

        List<Todo> filtered = TodoLogic.filterWidgetTodos(todos, now);
        check("expired todos are all shown", contains(filtered, "expired"));
        check("in-progress todos are all shown", contains(filtered, "running"));
        check("not-started within 48h boundary is shown", contains(filtered, "edge"));
        check("not-started beyond 48h is hidden", !contains(filtered, "far"));
        check("completed todos disappear from widget", !contains(filtered, "done"));
        check("expired todos sort to the top", filtered.get(0).id.equals("expired"));
        check("filter keeps urgency order without completed entries",
                filtered.size() == 4 && filtered.get(0).id.equals("expired")
                        && filtered.get(1).id.equals("running")
                        && filtered.get(2).id.equals("later")
                        && filtered.get(3).id.equals("edge"));
        // App 内已完成分组与撤销不受卡片过滤影响（回归）。
        List<Todo> doneGroup = TodoLogic.completed(todos);
        check("completed group still exposes finished todos for undo",
                doneGroup.size() == 1 && doneGroup.get(0).id.equals("done"));
    }

    private static boolean contains(List<Todo> todos, String id) {
        for (Todo todo : todos) if (todo.id.equals(id)) return true;
        return false;
    }

    private static Todo todo(String id, long start, long end, boolean completed, long createdAt) {
        return new Todo(id, "Todo " + id, start, end, 30, completed, createdAt);
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
