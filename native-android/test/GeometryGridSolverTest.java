package test;

import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.domain.GeometryGridSolver;
import com.checktodo.app.domain.HtmlTimetableParser;
import com.checktodo.app.domain.OnlineImportLogic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Geometry-grid extraction tests using Kingosoft and generic div-grid shapes. */
public final class GeometryGridSolverTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testKingoGridShape();
        testGenericDivGridShape();
        testTimeAxisRows();
        testClusterFallback();
        testMissingDayHeaders();
        testParentBlocksAreDeduplicated();
        testCaptureJsonRoundTrip();
        testTextVariants();

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void testKingoGridShape() {
        HtmlTimetableParser.Result result = GeometryGridSolver.solve(
                Arrays.asList(
                        block("离散数学\n周瑜\n1-16[1-3]\n丽泽楼C103", 100, 100),
                        block("计算方法\n杨定坤\n7[3-4]\n丽泽楼C106", 200, 100),
                        block("计算方法\n杨定坤\n1-5,7-16[7-8]\n丽泽楼C106", 500, 300),
                        block("形势与政策3\n林子建\n14-14[5-6]\n丽泽楼C103", 500, 200)),
                days(), periods(new String[]{"一", "二", "三", "四"}, 100), 20, 8);

        check("Kingo shape parses 5 details", result.details.size() == 5);
        check("Kingo shape parses 3 courses", result.bases.size() == 3);
        check("geometry result is marked", result.gridInfo.contains("几何网格"));
        CourseDetail math = detail(result, "离散数学");
        check("nearest day column maps Monday", math != null && math.day == 1);
        check("bracketed nodes supply a three-node span",
                math != null && math.startNode == 1 && math.endNode() == 3);
        check("week expression is parsed", math != null && math.startWeek == 1 && math.endWeek == 16);
        check("teacher and room are split",
                math != null && math.teacher.equals("周瑜") && math.room.equals("丽泽楼C103"));

        List<CourseDetail> calculation = details(result, "计算方法");
        check("all calculation rows are retained", calculation.size() == 3);
        check("single-week expression uses row and bracket nodes",
                find(calculation, 2, 3, 4, 7, 7, 0) != null);
        check("comma first segment maps to Friday",
                find(calculation, 5, 7, 8, 1, 5, 0) != null);
        check("comma second segment maps to Friday",
                find(calculation, 5, 7, 8, 7, 16, 0) != null);

        CourseDetail situation = detail(result, "形势与政策3");
        check("row overlap maps a two-node period",
                situation != null && situation.day == 5 && situation.startNode == 5
                        && situation.endNode() == 6);
    }

    private static void testGenericDivGridShape() {
        HtmlTimetableParser.Result result = GeometryGridSolver.solve(
                Arrays.asList(
                        block("高等数学\n张三\n1-16周(单)\nA101", 120, 110),
                        block("大学英语\n李四\n1-16周(双)\nB202", 320, 210),
                        block("数据结构\n王五\n8-16周\nC303", 220, 310)),
                days(),
                periods(new String[]{"第1节", "第2节", "第3节"}, 100), 20, 12);

        check("generic div grid parses three courses", result.bases.size() == 3);
        CourseDetail math = detail(result, "高等数学");
        CourseDetail english = detail(result, "大学英语");
        CourseDetail data = detail(result, "数据结构");
        check("Arabic period mark maps row 1", math != null && math.startNode == 1);
        check("odd-week marker is retained", math != null && math.type == CourseDetail.TYPE_ODD);
        check("row 2 and nearest column are mapped",
                english != null && english.day == 3 && english.startNode == 2);
        check("even-week marker is retained", english != null && english.type == CourseDetail.TYPE_EVEN);
        check("later row maps to node 3", data != null && data.startNode == 3);
    }

    private static void testTimeAxisRows() {
        HtmlTimetableParser.Result result = GeometryGridSolver.solve(
                Arrays.asList(
                        block("体育\n赵六\n1-16周\n田径场", 105, 100),
                        block("人工智能\n钱七\n1-16周\n丽泽楼C202", 305, 300)),
                days(),
                Arrays.asList(
                        new GeometryGridSolver.Mark("08:00-08:45", 5, 100),
                        new GeometryGridSolver.Mark("08:55-09:40", 5, 200),
                        new GeometryGridSolver.Mark("10:00-10:45", 5, 300)),
                20, 12);
        check("time axis assigns first node", detail(result, "体育") != null
                && detail(result, "体育").startNode == 1);
        check("time axis assigns third node", detail(result, "人工智能") != null
                && detail(result, "人工智能").startNode == 3);
    }

    private static void testClusterFallback() {
        HtmlTimetableParser.Result result = GeometryGridSolver.solve(
                Arrays.asList(
                        block("课程A\n张三\n1-16周\nA101", 100, 100),
                        block("课程B\n李四\n1-16周\nB202", 300, 250),
                        block("课程C\n王五\n1-16周\nC303", 500, 500)),
                days(), new ArrayList<>(), 20, 12);
        check("cluster fallback keeps details", result.details.size() == 3);
        check("cluster fallback marks low confidence", result.gridInfo.contains("行聚类"));
        check("first cluster maps node 1", detail(result, "课程A").startNode == 1);
        check("separate cluster maps node 2", detail(result, "课程B").startNode == 2);
        check("last cluster maps node 3", detail(result, "课程C").startNode == 3);
    }

    private static void testMissingDayHeaders() {
        HtmlTimetableParser.Result result = GeometryGridSolver.solve(
                Arrays.asList(block("课程A\n张三\n1-16周\nA101", 100, 100)),
                new ArrayList<>(), new ArrayList<>(), 20, 12);
        check("missing day headers produce no details", result.details.isEmpty());
        check("missing day headers produce warning", !result.warnings.isEmpty());
    }

    private static void testParentBlocksAreDeduplicated() {
        GeometryGridSolver.Block child = block("课程A\n张三\n1-16周\nA101", 100, 100);
        GeometryGridSolver.Block parent = new GeometryGridSolver.Block(child.text, 100, 100, 400, 200);
        HtmlTimetableParser.Result result = GeometryGridSolver.solve(
                Arrays.asList(parent, child), days(),
                periods(new String[]{"一", "二"}, 100), 20, 12);
        check("parent container is removed", result.details.size() == 1);
    }

    private static void testCaptureJsonRoundTrip() {
        OnlineImportLogic.Capture capture = OnlineImportLogic.parseCapture(
                "{\"url\":\"http://jw.edu.cn/kb\",\"title\":\"我的课表\",\"tables\":[],"
                        + "\"geometry\":{\"courses\":[{\"t\":\"离散数学\\n周瑜\\n1-16[1-3]\\n丽泽楼C103\","
                        + "\"x\":100,\"y\":100,\"w\":80,\"h\":50}],"
                        + "\"days\":[{\"t\":\"星期一\",\"x\":100,\"y\":10},"
                        + "{\"t\":\"星期二\",\"x\":200,\"y\":10},"
                        + "{\"t\":\"星期三\",\"x\":300,\"y\":10},"
                        + "{\"t\":\"星期四\",\"x\":400,\"y\":10},"
                        + "{\"t\":\"星期五\",\"x\":500,\"y\":10}],"
                        + "\"periods\":[{\"t\":\"一\",\"x\":10,\"y\":100},"
                        + "{\"t\":\"二\",\"x\":10,\"y\":200}]},"
                        + "\"stats\":{\"docs\":2,\"tables\":0,\"geo\":1}}");
        check("capture parses geometry blocks", capture != null && capture.geometryCourses.size() == 1);
        check("capture parses geometry day headers", capture != null && capture.geometryDays.size() == 5);
        check("capture parses geometry period marks", capture != null && capture.geometryPeriods.size() == 2);
        HtmlTimetableParser.Result result = GeometryGridSolver.solve(
                capture == null ? null : capture.geometryCourses,
                capture == null ? null : capture.geometryDays,
                capture == null ? null : capture.geometryPeriods, 20, 8);
        check("capture geometry solves to one detail", result.details.size() == 1);
        check("capture geometry maps Monday", result.details.size() == 1 && result.details.get(0).day == 1);
    }

    private static void testTextVariants() {
        HtmlTimetableParser.Result result = GeometryGridSolver.solve(
                Arrays.asList(
                        block("两行课程 1-16周", 100, 100),
                        block("三行课程\nA101\n1-16周(单)", 200, 100),
                        block("第1-2节\n教室A\n1-16周", 300, 100)),
                days(), periods(new String[]{"一", "二", "三", "四"}, 100), 20, 12);
        check("two-line text has no course name", detail(result, "两行课程") == null);
        check("three-line text extracts room and odd weeks",
                detail(result, "三行课程") != null
                        && detail(result, "三行课程").room.equals("A101")
                        && detail(result, "三行课程").type == CourseDetail.TYPE_ODD);
        check("bare node-like line does not become a course", detail(result, "第1-2节") == null);
    }

    private static List<GeometryGridSolver.Mark> days() {
        List<GeometryGridSolver.Mark> marks = new ArrayList<>();
        String[] names = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        for (int i = 0; i < names.length; i++) {
            marks.add(new GeometryGridSolver.Mark(names[i], 100 + i * 100, 10));
        }
        return marks;
    }

    private static List<GeometryGridSolver.Mark> periods(String[] names, int y) {
        List<GeometryGridSolver.Mark> marks = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            marks.add(new GeometryGridSolver.Mark(names[i], 5, y + i * 100));
        }
        return marks;
    }

    private static GeometryGridSolver.Block block(String text, double x, double y) {
        return new GeometryGridSolver.Block(text, x, y, 80, 50);
    }

    private static CourseDetail detail(HtmlTimetableParser.Result result, String name) {
        List<CourseDetail> found = details(result, name);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<CourseDetail> details(HtmlTimetableParser.Result result, String name) {
        List<CourseDetail> found = new ArrayList<>();
        for (CourseDetail detail : result.details) {
            if (name.equals(baseName(result, detail))) found.add(detail);
        }
        return found;
    }

    private static CourseDetail find(List<CourseDetail> details, int day, int startNode, int endNode,
                                     int startWeek, int endWeek, int type) {
        for (CourseDetail detail : details) {
            if (detail.day == day && detail.startNode == startNode && detail.endNode() == endNode
                    && detail.startWeek == startWeek && detail.endWeek == endWeek && detail.type == type) {
                return detail;
            }
        }
        return null;
    }

    private static String baseName(HtmlTimetableParser.Result result, CourseDetail detail) {
        for (com.checktodo.app.domain.CourseBase base : result.bases) {
            if (base.id.equals(detail.baseId)) return base.name;
        }
        return "";
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS " + label);
        } else {
            failed++;
            System.out.println("FAIL " + label);
        }
    }
}
