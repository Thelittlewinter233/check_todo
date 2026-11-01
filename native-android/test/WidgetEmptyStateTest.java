import com.checktodo.app.widget.WidgetFactoryState;
import com.checktodo.app.widget.WidgetModes;

/**
 * 空课表 / 空待办 / 空打卡 × 三视图切换的状态矩阵单测。
 *
 * <p>复现真机问题：当前课表没有课程时，切换三视图会「串状态」——课表视图仍显示上一个模式的
 * 打卡/待办条目。根因是 Factory 在 onDataSetChanged 之前就被启动器取视图，行缓存沿用旧模式。
 * 纯 Java 的 {@link WidgetFactoryState} 把「模式来源 + 是否已加载」显式建模，可测且不依赖框架。</p>
 */
public final class WidgetEmptyStateTest {
    private static int passed;
    private static int failed;

    private static final int TASKS = 5;
    private static final int TODOS = 3;
    private static final int NO_COURSES = 0;

    public static void main(String[] args) {
        testEmptyCoursesView();
        testEmptyMatrix();
        testNoStaleRowsBeforeLoad();
        testModeCycleFromCourses();
        testStoredModeWinsOverUri();
        testTitlesFollowLoadedMode();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    /** 空课表：切到课表视图必须 0 条，且标题/空态文案对得上，不显示上一个模式的打卡条目。 */
    private static void testEmptyCoursesView() {
        WidgetFactoryState state = new WidgetFactoryState(uri(WidgetModes.MODE_TASKS));
        state.load(TASKS, TODOS, NO_COURSES, WidgetModes.MODE_COURSES);
        check("empty timetable renders zero rows", state.count() == 0);
        check("empty timetable is renderable (no stale rows)", state.canRender());
        check("empty timetable mode is courses", state.mode() == WidgetModes.MODE_COURSES);
        check("empty timetable title is course title",
                WidgetModes.title(state.mode()).equals("今日课程"));
        check("empty timetable empty text is course empty text",
                WidgetModes.emptyText(state.mode()).equals("今天没有课程"));
        check("empty timetable does not expose task rows",
                state.count() != TASKS && state.count() != TODOS);
    }

    /** 三种空态 × 三视图全矩阵：每个空态只影响自己那一列。 */
    private static void testEmptyMatrix() {
        int[][] matrix = {
                // tasks, todos, courses
                {0, TODOS, NO_COURSES},
                {TASKS, 0, NO_COURSES},
                {TASKS, TODOS, NO_COURSES},
        };
        for (int row = 0; row < matrix.length; row++) {
            int tasks = matrix[row][0];
            int todos = matrix[row][1];
            int courses = matrix[row][2];
            for (int mode = 0; mode < WidgetModes.MODE_COUNT; mode++) {
                WidgetFactoryState state = new WidgetFactoryState(uri(mode));
                state.load(tasks, todos, courses, mode);
                int expected = mode == WidgetModes.MODE_TODOS ? todos
                        : mode == WidgetModes.MODE_COURSES ? courses : tasks;
                check("matrix row " + row + " mode " + mode + " count matches source",
                        state.count() == expected);
                check("matrix row " + row + " mode " + mode + " can render", state.canRender());
            }
        }
    }

    /** Factory 被启动器复用、onDataSetChanged 还没跑时，绝不允许吐旧行。 */
    private static void testNoStaleRowsBeforeLoad() {
        WidgetFactoryState state = new WidgetFactoryState(uri(WidgetModes.MODE_COURSES));
        check("unloaded factory cannot render", !state.canRender());
        check("unloaded factory reports zero rows", state.count() == 0);
        state.load(TASKS, TODOS, 2, WidgetModes.MODE_TASKS);
        check("loaded factory renders tasks", state.count() == TASKS);
        check("reload with empty courses drops rows",
                reloadWith(state, TASKS, TODOS, 0, WidgetModes.MODE_COURSES) == 0);
    }

    /** 空课表 → 打卡 → 待办 → 课表 循环：每步条目数都跟着当前模式的数据源。 */
    private static void testModeCycleFromCourses() {
        WidgetFactoryState state = new WidgetFactoryState(uri(WidgetModes.MODE_COURSES));
        state.load(TASKS, TODOS, NO_COURSES, WidgetModes.MODE_COURSES);
        check("cycle starts on empty courses", state.count() == 0);
        state.load(TASKS, TODOS, NO_COURSES, WidgetModes.next(state.mode()));
        check("cycle to tasks shows task rows", state.count() == TASKS);
        state.load(TASKS, TODOS, NO_COURSES, WidgetModes.next(state.mode()));
        check("cycle to todos shows todo rows", state.count() == TODOS);
        state.load(TASKS, TODOS, NO_COURSES, WidgetModes.next(state.mode()));
        check("cycle back to empty courses shows no rows", state.count() == 0);
    }

    /** 存储是唯一事实源：URI 只决定 Factory 重建瞬间的初始模式。 */
    private static void testStoredModeWinsOverUri() {
        WidgetFactoryState state = new WidgetFactoryState(uri(WidgetModes.MODE_COURSES));
        check("uri seeds initial mode before load", state.mode() == WidgetModes.MODE_COURSES);
        check("uri-seeded state cannot render yet", !state.canRender());
        state.load(TASKS, TODOS, NO_COURSES, WidgetModes.MODE_TODOS);
        check("stored mode overrides uri", state.mode() == WidgetModes.MODE_TODOS);
        check("stored mode drives row source", state.count() == TODOS);

        WidgetFactoryState noUri = new WidgetFactoryState("checktodo://widget/12");
        check("uri without mode falls back to tasks",
                noUri.mode() == WidgetModes.MODE_TASKS);
        noUri.load(TASKS, TODOS, NO_COURSES, WidgetModes.normalize(99));
        check("out of range stored mode is normalized",
                noUri.mode() == WidgetModes.MODE_TASKS && noUri.count() == TASKS);
    }

    /** 标题与内容同源：都读已加载的模式，避免标题课表内容打卡的错位。 */
    private static void testTitlesFollowLoadedMode() {
        for (int mode = 0; mode < WidgetModes.MODE_COUNT; mode++) {
            WidgetFactoryState state = new WidgetFactoryState(uri(mode));
            state.load(TASKS, TODOS, NO_COURSES, mode);
            String title = WidgetModes.title(state.mode());
            check("title matches loaded mode " + mode,
                    title.equals(mode == WidgetModes.MODE_TODOS ? "待办"
                            : mode == WidgetModes.MODE_COURSES ? "今日课程" : "今日打卡"));
        }
    }

    private static int reloadWith(WidgetFactoryState state, int tasks, int todos, int courses, int mode) {
        state.load(tasks, todos, courses, mode);
        return state.count();
    }

    private static String uri(int mode) {
        return "checktodo://widget/12/mode/" + mode;
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
