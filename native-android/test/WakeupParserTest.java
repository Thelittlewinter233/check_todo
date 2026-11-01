package test;

import com.checktodo.app.domain.CourseBase;
import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.wakeup_parser.SchoolInfo;
import com.checktodo.app.wakeup_parser.SchoolInfoDecoder;
import com.checktodo.app.wakeup_parser.WakeupCourse;
import com.checktodo.app.wakeup_parser.WakeupCourseMapper;
import com.checktodo.app.wakeup_parser.WakeupParser;
import com.checktodo.app.wakeup_parser.WakeupParserFactory;
import com.checktodo.app.wakeup_parser.WakeupParserResult;
import com.checktodo.app.wakeup_parser.WeekText;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Smoke tests for the hand-written KSoup shim and the five parser families
 * selected from WakeUp's encrypted school catalog.
 */
public final class WakeupParserTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testWeekText();
        testFactory();
        testZhengFang();
        testJinZhi();
        testUrpNew();
        testElementUi();
        testKingosoft();
        testModernKingosoft();
        testMapper();
        testSchoolCatalog();

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void testWeekText() {
        check("week range", Arrays.equals(WeekText.range("第1-16周"), new int[]{1, 16}));
        check("single week", Arrays.equals(WeekText.range("第3周"), new int[]{3, 3}));
        check("odd week type", WeekText.type("1-16周(单)") == 1);
        check("even week type", WeekText.type("1-16周(双)") == 2);
        check("continuous week type", WeekText.type("1-16周") == 0);
        check("English odd week type", WeekText.type("odd") == 1);
        check("English even week type", WeekText.type("even") == 2);
        check("invalid week falls back", Arrays.equals(WeekText.range("第周"), new int[]{1, 20}));
    }

    private static void testFactory() {
        check("factory selects ZhengFang", WakeupParserFactory.create("zf", "") instanceof com.checktodo.app.wakeup_parser.ZhengFangParser);
        check("factory selects JinZhi", WakeupParserFactory.create("", "jz") instanceof com.checktodo.app.wakeup_parser.JinZhiParser);
        check("factory rejects unsupported family", WakeupParserFactory.create("cf", "") == null);
        check("factory supports five families", WakeupParserFactory.supportedTypes().size() == 5);
    }

    private static void testZhengFang() {
        String html = "<table id='table_tb'><tr>"
                + "<td id='td_2-3' rowspan='2'><table>"
                + "<tr><td>高等数学</td><td>A</td><td>A101</td><td>张三</td><td>1-16周,3-16周(单)</td></tr>"
                + "</table></td></tr></table>";
        WakeupParserResult result = parse(WakeupParserFactory.create("zf", ""), html);

        check("ZhengFang parses course count", result.courses.size() == 2);
        WakeupCourse course = result.courses.get(0);
        check("ZhengFang maps name", course.name.equals("高等数学"));
        check("ZhengFang maps day", course.day == 2);
        check("ZhengFang maps nodes", course.startNode == 3 && course.endNode == 4);
        check("ZhengFang maps room", course.room.equals("A101"));
        check("ZhengFang maps teacher", course.teacher.equals("张三"));
        check("ZhengFang splits week ranges", result.courses.get(1).startWeek == 3
                && result.courses.get(1).endWeek == 16);
        check("ZhengFang maps odd weeks", result.courses.get(1).type == CourseDetail.TYPE_ODD);
    }

    private static void testJinZhi() {
        String html = "<table id='jsTbl_1'><tr><td xq='3' jc='5' rowspan='3'>"
                + "<arrage><div>1-16周</div><div>大学英语(二)</div><div>B202</div><div>李四</div></arrage>"
                + "</td></tr></table>";
        WakeupParserResult result = parse(WakeupParserFactory.create("jz", ""), html);

        check("JinZhi parses one course", result.courses.size() == 1);
        WakeupCourse course = result.courses.get(0);
        check("JinZhi cleans course name", course.name.equals("大学英语"));
        check("JinZhi maps day", course.day == 3);
        check("JinZhi maps nodes", course.startNode == 5 && course.endNode == 7);
        check("JinZhi maps room", course.room.equals("B202"));
        check("JinZhi maps teacher", course.teacher.equals("李四"));
        check("JinZhi maps week range", course.startWeek == 1 && course.endWeek == 16);
    }

    private static void testUrpNew() {
        String html = "<table class='pages-sec-student-scheme-pages-student-timetable-query-timetable-index-tableContent'>"
                + "<tr><td _nk='x' day='4'><div>数据结构</div><div>王五</div><div>C303</div>"
                + "<div>[1-16周]</div><div>第2节到第4节</div></td></tr></table>";
        WakeupParserResult result = parse(WakeupParserFactory.create("urp_new", ""), html);

        check("URP New parses one course", result.courses.size() == 1);
        WakeupCourse course = result.courses.get(0);
        check("URP New maps name", course.name.equals("数据结构"));
        check("URP New maps teacher", course.teacher.equals("王五"));
        check("URP New maps room", course.room.equals("C303"));
        check("URP New maps day", course.day == 4);
        check("URP New maps node range", course.startNode == 2 && course.endNode == 4);
        check("URP New maps week range", course.startWeek == 1 && course.endWeek == 16);
    }

    private static void testElementUi() {
        String html = "<div class='arranged-content'><div class='el-card arranged-course-card'>"
                + "<div class='card-item'><div class='label'>课程名:</div><div class='value'>大学物理</div></div>"
                + "<div class='card-item'><div class='label'>上课教师:</div><div class='value'>赵六</div></div>"
                + "<div class='card-item'><div class='label'>上课地点:</div><div class='value'>D404</div></div>"
                + "<div class='card-item'><div class='label'>上课时间:</div><div class='value'>一3-5 (1-16周)</div></div>"
                + "</div></div>";
        WakeupParserResult result = parse(WakeupParserFactory.create("element_ui", ""), html);

        check("Element UI parses one course", result.courses.size() == 1);
        WakeupCourse course = result.courses.get(0);
        check("Element UI maps name", course.name.equals("大学物理"));
        check("Element UI maps teacher", course.teacher.equals("赵六"));
        check("Element UI maps room", course.room.equals("D404"));
        check("Element UI maps day", course.day == 1);
        check("Element UI maps nodes", course.startNode == 3 && course.endNode == 5);
        check("Element UI maps weeks", course.startWeek == 1 && course.endWeek == 16);
    }

    private static void testKingosoft() {
        String json = "[{\"week2\":[{\"kcmc\":\"操作系统\",\"skdd\":\"E505\",\"rkjs\":\"孙七\","
                + "\"jcxx\":\"1-2\",\"xf\":\"3.5\"}]}]";
        WakeupParserResult result = parse(WakeupParserFactory.create("kingo_new", ""), json);

        check("Kingosoft parses one course", result.courses.size() == 1);
        WakeupCourse course = result.courses.get(0);
        check("Kingosoft maps name", course.name.equals("操作系统"));
        check("Kingosoft maps day", course.day == 2);
        check("Kingosoft maps week", course.startWeek == 1 && course.endWeek == 1);
        check("Kingosoft maps nodes", course.startNode == 1 && course.endNode == 2);
        check("Kingosoft maps room", course.room.equals("E505"));
        check("Kingosoft maps teacher", course.teacher.equals("孙七"));
        check("Kingosoft maps credit", course.credit == 3.5);
    }

    private static void testModernKingosoft() {
        String json = "{\"list\":[{\"kcmc\":\"高等数学\",\"jsxm\":\"张三\",\"xkd\":\"A101\","
                + "\"zc\":\"1-16周(单)\",\"xq\":\"2\",\"jc\":\"1-2\",\"xf\":\"4.5\"}]}";
        WakeupParserResult result = parse(WakeupParserFactory.create("kingo_new", ""), json);

        check("modern Kingosoft parses list", result.courses.size() == 1);
        WakeupCourse course = result.courses.get(0);
        check("modern Kingosoft maps name", course.name.equals("高等数学"));
        check("modern Kingosoft maps teacher", course.teacher.equals("张三"));
        check("modern Kingosoft maps room", course.room.equals("A101"));
        check("modern Kingosoft maps day", course.day == 2);
        check("modern Kingosoft maps nodes", course.startNode == 1 && course.endNode == 2);
        check("modern Kingosoft maps weeks", course.startWeek == 1 && course.endWeek == 16);
        check("modern Kingosoft maps odd weeks", course.type == CourseDetail.TYPE_ODD);
        check("modern Kingosoft maps credit", course.credit == 4.5);
    }

    private static void testMapper() {
        WakeupCourseMapper.Result mapped = WakeupCourseMapper.map(Arrays.asList(
                new WakeupCourse("高等数学", 1, "A101", "张三", 1, 2, 1, 16, 0, 4),
                new WakeupCourse("高等数学", 3, "A102", "张三", 1, 2, 1, 16, 1, 4)));

        check("mapper creates one course base", mapped.bases.size() == 1);
        check("mapper creates two details", mapped.details.size() == 2);
        check("mapper maps base fields", mapped.bases.get(0).name.equals("高等数学")
                && mapped.bases.get(0).credit == 4);
        check("mapper maps detail fields", mapped.details.get(1).day == 3
                && mapped.details.get(1).step == 2
                && mapped.details.get(1).type == CourseDetail.TYPE_ODD
                && mapped.details.get(1).teacher.equals("张三"));
        check("mapper rejects invalid day", WakeupCourseMapper.invalid(
                new WakeupCourse("bad", 8, "", "", 1, 1, 1, 1, 0, 0)));
    }

    private static void testSchoolCatalog() throws Exception {
        String raw = Files.readString(Path.of("res/raw/school_info_android_new.txt"), StandardCharsets.UTF_8);
        String json = SchoolInfoDecoder.decode(raw);
        List<SchoolInfo> schools = SchoolInfoDecoder.parse(json);

        check("school catalog decodes", schools.size() == 3570);
        int zf = 0;
        int jz = 0;
        int urpNew = 0;
        int kingoNew = 0;
        for (SchoolInfo school : schools) {
            String key = school.type.trim().toLowerCase();
            if (key.equals("zf")) zf++;
            if (key.equals("jz")) jz++;
            if (key.equals("urp_new")) urpNew++;
            if (key.equals("kingo_new")) kingoNew++;
        }
        check("ZhengFang catalog count", zf == 970);
        check("JinZhi catalog count", jz == 128);
        check("URP New catalog count", urpNew == 47);
        check("Kingosoft catalog count", kingoNew == 173);
        check("catalog search finds school by name", !SchoolInfoDecoder.search(schools, "北京电影学院").isEmpty());
    }

    private static WakeupParserResult parse(WakeupParser parser, String source) {
        WakeupParserResult result = parser.parse(source);
        for (String warning : result.warnings) System.out.println("warning: " + warning);
        return result;
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
