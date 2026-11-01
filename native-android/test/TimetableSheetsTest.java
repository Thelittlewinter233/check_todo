package test;

import com.checktodo.app.domain.CourseBase;
import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.domain.TimeNode;
import com.checktodo.app.domain.TimetableConfig;
import com.checktodo.app.domain.TimetableSheet;
import com.checktodo.app.domain.TimetableSheets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Multi-sheet timetable behavior: creation, switching, isolation, copy and migration. */
public final class TimetableSheetsTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testCreateSwitchRenameDelete();
        testConfigAndCourseIsolation();
        testCreateWithoutCourseCopy();
        testLegacyMigrationPreservesData();

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void testCreateSwitchRenameDelete() {
        TimetableSheets sheets = new TimetableSheets(sheet("id-1", "默认课表",
                config("2026-09-07", 20, 12), 1));
        TimetableSheet created = sheets.create("大二上", true);

        check("create selects new sheet", sheets.currentId().equals(created.id));
        check("create appends sheet", sheets.all().size() == 2);
        check("create generates unique id", !created.id.equals("id-1"));

        check("switch back to first sheet", sheets.switchTo("id-1"));
        check("switch selects requested sheet", sheets.current().name.equals("默认课表"));
        check("switch unknown id fails", !sheets.switchTo("missing"));

        check("rename updates sheet", sheets.rename("id-1", "大一"));
        check("rename rejects unknown id", !sheets.rename("missing", "x"));
        check("rename cannot make empty name", sheets.current().name.equals("大一"));

        check("delete non-current sheet", sheets.delete(created.id));
        check("deleted sheet is gone", sheets.find(created.id) == null);
        check("current cannot be deleted", !sheets.delete("id-1"));
        check("last sheet cannot be deleted", !sheets.delete("id-1"));
    }

    private static void testConfigAndCourseIsolation() {
        TimetableSheets sheets = new TimetableSheets(sheet("id-1", "A",
                config("2026-09-07", 20, 12), 1));
        TimetableSheet second = sheets.create("B", true);
        TimetableSheet changed = second.withConfig(config("2026-09-14", 18, 10))
                .withTimeNodes(second.timeNodes.subList(0, 10));
        check("replace updates selected sheet", sheets.replace(changed));

        check("configs are isolated", sheets.find("id-1").config.maxWeek == 20
                && sheets.current().config.maxWeek == 18);
        check("time tables are isolated", sheets.find("id-1").timeNodes.size() == 12
                && sheets.current().timeNodes.size() == 10);
        check("courses are isolated when replaced",
                sheets.find("id-1").details.size() == 1);
    }

    private static void testCreateWithoutCourseCopy() {
        TimetableSheets sheets = new TimetableSheets(sheet("id-1", "A",
                config("2026-09-07", 20, 12), 1));
        TimetableSheet created = sheets.create("空表", false);

        check("copy=false creates empty course set", created.details.isEmpty());
        check("copy=false keeps source courses", sheets.switchTo("id-1")
                && sheets.current().details.size() == 1);
    }

    private static void testLegacyMigrationPreservesData() {
        TimetableSheet legacy = sheet("legacy-timetable", "默认课表",
                config("2026-09-07", 20, 12), 1);
        TimetableSheets migrated = TimetableSheets.fromLegacy(legacy);

        check("legacy migration creates one sheet", migrated.all().size() == 1);
        check("legacy migration selects sheet", migrated.currentId().equals("legacy-timetable"));
        check("legacy config preserved", migrated.current().config.startDate.equals("2026-09-07"));
        check("legacy time node preserved",
                migrated.current().timeNodes.get(0).startTime.equals("08:00"));
        check("legacy course base preserved",
                migrated.current().bases.get(0).name.equals("高等数学"));
        check("legacy course detail preserved",
                migrated.current().details.get(0).room.equals("A101"));
    }

    private static TimetableSheet sheet(String id, String name, TimetableConfig config,
                                        int courseCount) {
        List<TimeNode> nodes = new ArrayList<>();
        for (int i = 1; i <= config.nodes; i++) nodes.add(new TimeNode(i, "08:00", "09:40"));
        List<CourseBase> bases = new ArrayList<>();
        List<CourseDetail> details = new ArrayList<>();
        if (courseCount > 0) {
            bases.add(new CourseBase("base-1", "高等数学", 1, "基础课", 4));
            details.add(new CourseDetail("detail-1", "base-1", 1, 1, 2,
                    1, 16, CourseDetail.TYPE_ALL, "A101", "张三", 0));
        }
        return new TimetableSheet(id, name, config, nodes, bases, details);
    }

    private static TimetableConfig config(String startDate, int maxWeek, int nodes) {
        return new TimetableConfig(startDate, maxWeek, nodes, true, false, true);
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
