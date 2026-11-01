package test;

import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.domain.HtmlTimetableParser;
import com.checktodo.app.domain.SimpleHtmlParser;
import com.checktodo.app.domain.WeekExpression;

import java.util.ArrayList;
import java.util.List;

/** Real-shape KINGOSOFT xskcb report parser and shared week-expression tests. */
public final class KingoReportParserTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testRealReportFixture();
        testWeekGrammar();
        testNonKingoTableFallsBack();
        testSiblingDivBlocksAndMetadata();

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void testRealReportFixture() {
        HtmlTimetableParser.Result result = HtmlTimetableParser.parse(fixture(), 20, 8);
        check("KINGOSOFT structure is recognized", result.gridInfo.contains("KINGOSOFT"));
        check("parses 17 details for an 8-node sheet: 16 source schedules plus comma split",
                result.details.size() == 17);
        check("parses 11 course bases", result.bases.size() == 11);
        check("two evening 9-10 schedules are skipped", countWarnings(result, "9-10") == 2);

        CourseDetail math = find(result, "\u79bb\u6563\u6570\u5b66");
        check("discrete math day", math != null && math.day == 1);
        check("discrete math nodes", math != null && math.startNode == 1 && math.endNode() == 3);
        check("discrete math weeks", math != null && math.startWeek == 1 && math.endWeek == 16);
        check("discrete math teacher", math != null && math.teacher.equals("\u5468\u745c"));
        check("discrete math room", math != null && math.room.equals("\u4e3d\u6cfd\u697cC103"));

        List<CourseDetail> calc = findAll(result, "\u8ba1\u7b97\u65b9\u6cd5");
        check("calculation method has three details after comma split", calc.size() == 3);
        CourseDetail weekSeven = findByWeek(calc, 7, 7, 2, 3, 4);
        check("calculation week 7 Tuesday 3-4", weekSeven != null);
        CourseDetail comma = findByWeek(calc, 1, 5, 5, 7, 8);
        check("calculation comma segment 1-5 Friday 7-8", comma != null);
        check("calculation comma segment 7-16 Friday 7-8", findByWeek(calc, 7, 16, 5, 7, 8) != null);

        List<CourseDetail> situation = findAll(result, "\u5f62\u52bf\u4e0e\u653f\u7b563");
        check("situation has four schedules", situation.size() == 4);
        check("situation teacher week 14", teacherWeek(situation, "\u6797\u5b50\u5efa", 14));
        check("situation teacher week 10", teacherWeek(situation, "\u738b\u8bd7\u97f5", 10));
        check("situation teacher week 16", teacherWeek(situation, "\u738b\u827a\u7487", 16));
        check("situation teacher week 12", teacherWeek(situation, "\u738b\u4e3d\u838e", 12));
        check("all situation schedules are Friday 5-6", friday(situation));
    }

    private static void testWeekGrammar() {
        check("plain range", segments("1-16", 20) == 1
                && start("1-16", 20) == 1 && end("1-16", 20) == 16);
        check("single week", segments("7[3-4]", 20) == 1
                && start("7[3-4]", 20) == 7 && end("7[3-4]", 20) == 7);
        check("collapsed range", segments("14-14[5-6]", 20) == 1
                && start("14-14[5-6]", 20) == 14 && end("14-14[5-6]", 20) == 14);
        check("comma segments", segments("1-5,7-16[7-8]", 20) == 2);
        check("comma first segment", start("1-5,7-16[7-8]", 20) == 1
                && end("1-5,7-16[7-8]", 20) == 5);
        check("odd marker", type("1-16\u5468(\u5355)", 20) == CourseDetail.TYPE_ODD);
        check("even marker", type("1-16\u5468(\u53cc)", 20) == CourseDetail.TYPE_EVEN);
        check("week marker is optional", WeekExpression.contains("1-16") && WeekExpression.contains("1-16周"));
        check("node numbers are not week numbers", segments("7[3-4]", 20) == 1);
        check("out of range clamps", end("1-25", 20) == 20);
    }

    private static void testNonKingoTableFallsBack() {
        String html = "<html><body><table><tr><td>id</td><td>name</td></tr>"
                + "<tr><td>1</td><td>abc</td></tr></table></body></html>";
        HtmlTimetableParser.Result result = HtmlTimetableParser.parse(html, 20, 8);
        check("non-report table does not enter Kingo parser", !result.gridInfo.contains("KINGOSOFT"));
        check("generic parser reports no timetable", !result.errors.isEmpty());
    }

    private static void testSiblingDivBlocksAndMetadata() {
        List<SimpleHtmlParser.Table> tables = SimpleHtmlParser.tables(fixture());
        check("table id is retained", tables.size() == 1 && tables.get(0).id.equals("mytable"));
        SimpleHtmlParser.Row header = tables.get(0).structuredRows.get(0);
        check("header class is retained", header.className.equals("H"));
        check("header first cell colspan is retained", header.cells.get(0).colspan == 2);
        SimpleHtmlParser.Row first = tables.get(0).structuredRows.get(1);
        check("section rowspan is retained", first.cells.get(0).rowspan == 2);
        check("node class is retained", first.cells.get(1).className.equals("td1"));
        String cell = first.cells.get(2).text;
        check("sibling div text blocks are separated", cell.contains("\u79bb\u6563\u6570\u5b66\n"));
    }

    private static String fixture() {
        return "<html><body><table id='mytable'>"
                + "<tr class='H'><td class='td0' colspan='2'></td>"
                + "<td class='td0'>星期一</td><td class='td0'>星期二</td>"
                + "<td class='td0'>星期三</td><td class='td0'>星期四</td>"
                + "<td class='td0'>星期五</td><td class='td0'>星期六</td>"
                + "<td class='td0'>星期日</td></tr>"

                + "<tr><td class='td1' rowspan='2'>上午</td><td class='td1'>一</td>"
                + cell("离散数学|周瑜|1-16[1-3]|丽泽楼C103")
                + empty()
                + cell("面向对象方法与技术|肖红玉|1-16[1-2]|木铎楼C302")
                + empty() + empty() + empty() + empty() + "</tr>"

                + "<tr><td class='td1'>二</td>"
                + empty()
                + cell("计算方法|林定移|7[3-4]|丽泽楼C106")
                + cell("面向对象方法与技术|肖红玉|1-16[3-4]|木铎楼C302")
                + cell("线性代数|刘凯|1-16[3-4]|丽泽楼D201")
                + cell("AI赋能遥感与应用|余先川|1-16[3-4]|丽泽楼A202")
                + empty() + empty() + "</tr>"

                + "<tr><td class='td1' rowspan='2'>下午</td><td class='td1'>三</td>"
                + cell("线性代数|刘凯|1-16[5-6]|丽泽楼D201")
                + cell("计算机导论|张程|1-16[5-6]|丽泽楼A203")
                + empty()
                + cell("数据结构|杨静|1-16[5-7]|丽泽楼C101")
                + multiCell()
                + empty() + empty() + "</tr>"

                + "<tr><td class='td1'>四</td>"
                + cell("人工智能导论|李彦增|1-16[7-8]|乐育楼C204")
                + empty()
                + cell("马克思主义基本原理|林航|1-16[7-8]|丽泽楼C202")
                + empty()
                + cell("计算方法|林定移|1-5,7-16[7-8]|丽泽楼C106")
                + empty() + empty() + "</tr>"

                + "<tr><td class='td1' rowspan='2'>晚上</td><td class='td1'>五</td>"
                + empty()
                + cell("博弈论|王晨豪|1-16[9-10]|丽泽楼C201")
                + empty()
                + cell("数据结构|杨静|1-16[9-10]|木铎楼C302")
                + empty() + empty() + empty() + "</tr>"

                + "<tr><td class='td1'>六</td>"
                + empty() + empty() + empty() + empty() + empty() + empty() + empty() + "</tr>"
                + "</table></body></html>";
    }

    private static String cell(String value) {
        String[] parts = value.split("\\|", -1);
        return "<td class='td'><div>" + parts[0] + "<br>" + parts[1] + "<br>"
                + parts[2] + "<br>" + parts[3] + "</div></td>";
    }

    private static String multiCell() {
        return "<td class='td'>"
                + cellText("形势与政策3", "林子建", "14-14[5-6]", "丽泽楼C103")
                + cellText("形势与政策3", "王诗韵", "10-10[5-6]", "丽泽楼C103")
                + cellText("形势与政策3", "王艺璇", "16-16[5-6]", "丽泽楼C103")
                + cellText("形势与政策3", "王丽莎", "12-12[5-6]", "丽泽楼C103")
                + "</td>";
    }

    private static String cellText(String name, String teacher, String weeks, String room) {
        return "<div>" + name + "<br>" + teacher + "<br>" + weeks + "<br>" + room + "</div>";
    }

    private static String empty() {
        return "<td class='td'><div class='div_nokb' id='k'></div></td>";
    }

    private static int countWarnings(HtmlTimetableParser.Result result, String text) {
        int count = 0;
        for (String warning : result.warnings) {
            if (warning.contains(text)) count++;
        }
        return count;
    }

    private static List<CourseDetail> findAll(HtmlTimetableParser.Result result, String name) {
        List<CourseDetail> found = new ArrayList<>();
        for (CourseDetail detail : result.details) {
            if (name.equals(baseName(result, detail))) found.add(detail);
        }
        return found;
    }

    private static CourseDetail find(HtmlTimetableParser.Result result, String name) {
        List<CourseDetail> all = findAll(result, name);
        return all.isEmpty() ? null : all.get(0);
    }

    private static CourseDetail findByWeek(List<CourseDetail> details, int startWeek, int endWeek,
                                           int day, int startNode, int endNode) {
        for (CourseDetail detail : details) {
            if (detail.startWeek == startWeek && detail.endWeek == endWeek && detail.day == day
                    && detail.startNode == startNode && detail.endNode() == endNode) {
                return detail;
            }
        }
        return null;
    }

    private static boolean teacherWeek(List<CourseDetail> details, String teacher, int week) {
        for (CourseDetail detail : details) {
            if (detail.teacher.equals(teacher) && detail.startWeek == week && detail.endWeek == week) {
                return true;
            }
        }
        return false;
    }

    private static boolean friday(List<CourseDetail> details) {
        for (CourseDetail detail : details) {
            if (detail.day != 5 || detail.startNode != 5 || detail.endNode() != 6) return false;
        }
        return true;
    }

    private static String baseName(HtmlTimetableParser.Result result, CourseDetail detail) {
        for (com.checktodo.app.domain.CourseBase base : result.bases) {
            if (base.id.equals(detail.baseId)) return base.name;
        }
        return "";
    }

    private static int segments(String text, int maxWeek) {
        return WeekExpression.parse(text, maxWeek).segments.size();
    }

    private static int start(String text, int maxWeek) {
        return WeekExpression.parse(text, maxWeek).segments.get(0).start;
    }

    private static int end(String text, int maxWeek) {
        return WeekExpression.parse(text, maxWeek).segments.get(0).end;
    }

    private static int type(String text, int maxWeek) {
        return WeekExpression.parse(text, maxWeek).segments.get(0).type;
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
