import com.checktodo.app.domain.CourseBase;
import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.domain.TimeNode;
import com.checktodo.app.widget.CourseWidgetItems;
import com.checktodo.app.widget.WidgetModeStore;
import com.checktodo.app.widget.WidgetModes;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class WidgetModeTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testModeCycle();
        testModeStorePerWidgetId();
        testModeFromUri();
        testTitles();
        testCourseItems();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void testModeCycle() {
        check("tasks is the first mode", WidgetModes.MODE_TASKS == 0);
        check("mode cycle tasks -> todos",
                WidgetModes.next(WidgetModes.MODE_TASKS) == WidgetModes.MODE_TODOS);
        check("mode cycle todos -> courses",
                WidgetModes.next(WidgetModes.MODE_TODOS) == WidgetModes.MODE_COURSES);
        check("mode cycle courses -> tasks",
                WidgetModes.next(WidgetModes.MODE_COURSES) == WidgetModes.MODE_TASKS);
        check("invalid mode normalizes to tasks",
                WidgetModes.normalize(99) == WidgetModes.MODE_TASKS
                        && WidgetModes.normalize(-3) == WidgetModes.MODE_TASKS);
    }

    private static void testModeStorePerWidgetId() {
        WidgetModeStore store = new WidgetModeStore.Memory();
        check("store defaults to tasks",
                store.read(11) == WidgetModes.MODE_TASKS);
        store.write(11, WidgetModes.MODE_TODOS);
        store.write(42, WidgetModes.MODE_COURSES);
        check("widget 11 keeps todos mode", store.read(11) == WidgetModes.MODE_TODOS);
        check("widget 42 keeps courses mode", store.read(42) == WidgetModes.MODE_COURSES);
        check("modes are isolated per widget id",
                store.read(11) != store.read(42));
        store.remove(11);
        check("removed widget falls back to default mode",
                store.read(11) == WidgetModes.MODE_TASKS);
        check("other widget is unaffected by removal",
                store.read(42) == WidgetModes.MODE_COURSES);
        store.write(77, -1);
        check("invalid stored mode is normalized on write",
                store.read(77) == WidgetModes.MODE_TASKS);
    }

    private static void testModeFromUri() {
        check("uri parses mode",
                WidgetModes.modeFromUri("checktodo://widget/12/mode/2")
                        == WidgetModes.MODE_COURSES);
        check("uri without mode returns -1",
                WidgetModes.modeFromUri("checktodo://widget/12") == -1);
        check("null uri returns -1", WidgetModes.modeFromUri(null) == -1);
        check("malformed mode returns -1",
                WidgetModes.modeFromUri("checktodo://widget/12/mode/x") == -1);
    }

    private static void testTitles() {
        check("tasks title", WidgetModes.title(WidgetModes.MODE_TASKS).equals("今日打卡"));
        check("todos title", WidgetModes.title(WidgetModes.MODE_TODOS).equals("待办"));
        check("courses title", WidgetModes.title(WidgetModes.MODE_COURSES).equals("今日课程"));
        check("tasks empty text", WidgetModes.emptyText(WidgetModes.MODE_TASKS).equals("今天没有任务"));
        check("todos empty text", WidgetModes.emptyText(WidgetModes.MODE_TODOS).equals("没有待办"));
        check("courses empty text",
                WidgetModes.emptyText(WidgetModes.MODE_COURSES).equals("今天没有课程"));
    }

    private static void testCourseItems() {
        List<CourseBase> bases = new ArrayList<>();
        bases.add(new CourseBase("b1", "高等数学", 0, "", 4.0));
        bases.add(new CourseBase("b2", "大学英语", 1, "", 2.0));
        List<CourseDetail> details = new ArrayList<>();
        details.add(new CourseDetail("d2", "b2", 2, 3, 2, 1, 16, 0, "外语楼201", "李老师", 1));
        details.add(new CourseDetail("d1", "b1", 1, 1, 2, 1, 16, 0, "教一101", "张老师", 0));
        List<TimeNode> nodes = new ArrayList<>();
        nodes.add(new TimeNode(1, "08:00", "08:45"));
        nodes.add(new TimeNode(2, "08:55", "09:40"));
        nodes.add(new TimeNode(3, "10:00", "10:45"));
        nodes.add(new TimeNode(4, "10:55", "11:40"));

        List<CourseWidgetItems.Item> items =
                CourseWidgetItems.build(details, bases, nodes, new Date(0));
        check("course items sort by start node",
                items.size() == 2 && items.get(0).title.equals("高等数学")
                        && items.get(1).title.equals("大学英语"));
        check("course subtitle contains node span and room",
                items.get(0).subtitle.contains("1-2节")
                        && items.get(0).subtitle.contains("08:00-09:40")
                        && items.get(0).subtitle.contains("教一101"));
        check("missing base falls back to unnamed course",
                CourseWidgetItems.title(
                        new CourseDetail("d3", "missing", 1, 1, 1, 1, 1, 0, "", "", 0),
                        bases).equals("未命名课程"));
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
