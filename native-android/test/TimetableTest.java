package test;

import com.checktodo.app.domain.CourseBase;
import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.domain.TimetableConfig;
import com.checktodo.app.domain.TimetableCsvParser;
import com.checktodo.app.domain.TimetableLogic;
import com.checktodo.app.domain.TaskLogic;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

/** 课表纯 Java 领域测试：当前周、单双周、冲突、多节占位、今日课程与 CSV 解析。 */
public final class TimetableTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testCurrentWeekClamps();
        testCurrentWeekNormal();
        testOddEvenAndContinuousWeeks();
        testConflictDetection();
        testStepSpanAndTodayCourses();
        testCsvSuccess();
        testCsvSeparators();
        testCsvOddEvenAndWeekList();
        testCsvErrors();
        testCsvHeaderAlias();

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void testCurrentWeekClamps() {
        TimetableConfig config = config("2026-09-07", 20, 12);
        check("开学前钳制到第 1 周",
                TimetableLogic.currentWeek(config, TimetableLogic.date(2026, 8, 31)) == 1);
        check("第 20 周最后一天仍是第 20 周",
                TimetableLogic.currentWeek(config, TimetableLogic.date(2027, 1, 24)) == 20);
        check("超出学期后钳制到 maxWeek",
                TimetableLogic.currentWeek(config, TimetableLogic.date(2027, 1, 25)) == 20);
        check("开学前返回 true", TimetableLogic.isBeforeTermStart(config, TimetableLogic.date(2026, 8, 31)));
        check("开学后返回 false", !TimetableLogic.isBeforeTermStart(config, TimetableLogic.date(2026, 9, 7)));
    }

    private static void testCurrentWeekNormal() {
        TimetableConfig config = config("2026-09-07", 20, 12);
        check("开学第一天是第 1 周", TimetableLogic.currentWeek(config, TimetableLogic.date(2026, 9, 7)) == 1);
        check("开学第 7 天仍是第 1 周", TimetableLogic.currentWeek(config, TimetableLogic.date(2026, 9, 13)) == 1);
        check("开学第 8 天进入第 2 周", TimetableLogic.currentWeek(config, TimetableLogic.date(2026, 9, 14)) == 2);
        check("2026-10-24 是第 7 周", TimetableLogic.currentWeek(config, TimetableLogic.date(2026, 10, 24)) == 7);
        check("日期 key 使用 ISO 格式",
                TimetableLogic.dateKey(TimetableLogic.date(2026, 10, 24)).equals("2026-10-24"));
    }

    private static void testOddEvenAndContinuousWeeks() {
        CourseDetail odd = detail("c1", 1, 1, 2, 1, 20, CourseDetail.TYPE_ODD);
        CourseDetail even = detail("c2", 1, 1, 2, 1, 20, CourseDetail.TYPE_EVEN);
        CourseDetail all = detail("c3", 1, 1, 2, 1, 20, CourseDetail.TYPE_ALL);

        check("奇数周包含单周课", TimetableLogic.isCourseInWeek(odd, 7));
        check("偶数周不包含单周课", !TimetableLogic.isCourseInWeek(odd, 8));
        check("偶数周包含双周课", TimetableLogic.isCourseInWeek(even, 8));
        check("奇数周不包含双周课", !TimetableLogic.isCourseInWeek(even, 7));
        check("连续周全部包含",
                TimetableLogic.isCourseInWeek(all, 1) && TimetableLogic.isCourseInWeek(all, 20));
        check("范围外不包含", !TimetableLogic.isCourseInWeek(detail("c4", 1, 1, 2, 3, 5, CourseDetail.TYPE_ALL), 6));
    }

    private static void testConflictDetection() {
        CourseDetail first = detail("c1", 1, 1, 2, 1, 10, CourseDetail.TYPE_ALL);
        CourseDetail second = detail("c2", 1, 2, 2, 1, 10, CourseDetail.TYPE_ALL);
        CourseDetail thirdDay = detail("c3", 2, 1, 2, 1, 10, CourseDetail.TYPE_ALL);
        CourseDetail thirdWeek = detail("c4", 1, 1, 2, 11, 20, CourseDetail.TYPE_ALL);
        CourseDetail odd = detail("c5", 1, 1, 2, 1, 20, CourseDetail.TYPE_ODD);
        CourseDetail even = detail("c6", 1, 1, 2, 1, 20, CourseDetail.TYPE_EVEN);

        check("同日同节次重叠算冲突",
                TimetableLogic.hasConflict(Arrays.asList(second), first, 1));
        check("不同星期不算冲突",
                !TimetableLogic.hasConflict(Arrays.asList(thirdDay), first, 1));
        check("不同周次不算冲突",
                !TimetableLogic.hasConflict(Arrays.asList(thirdWeek), first, 1));
        check("单双周互斥不算冲突",
                !TimetableLogic.hasConflict(Arrays.asList(even), odd, 1));
        check("同一周重叠单双周算冲突",
                TimetableLogic.hasConflict(Arrays.asList(odd), first, 1));
        check("自身不算冲突",
                !TimetableLogic.hasConflict(Arrays.asList(first), first, 1));
    }

    private static void testStepSpanAndTodayCourses() {
        CourseDetail twoNodes = detail("c1", 6, 1, 2, 1, 20, CourseDetail.TYPE_ALL);
        check("step=2 的结束节次为 2", twoNodes.endNode() == 2);

        CourseDetail threeNodes = detail("c2", 6, 3, 3, 1, 20, CourseDetail.TYPE_ALL);
        check("step=3 的结束节次为 5", threeNodes.endNode() == 5);

        CourseDetail otherDay = detail("c3", 1, 1, 2, 1, 20, CourseDetail.TYPE_ALL);
        CourseDetail nextNode = detail("c4", 6, 3, 2, 1, 20, CourseDetail.TYPE_ALL);
        List<CourseDetail> today = TimetableLogic.todayCourses(
                Arrays.asList(nextNode, otherDay, twoNodes),
                config("2026-09-07", 20, 12),
                TaskLogic.date(2026, 10, 24, 8, 0));
        check("周六只保留今天课程", today.size() == 2);
        check("今日课程按开始节次升序",
                today.get(0).id.equals("c1") && today.get(1).id.equals("c4"));

        CourseDetail oddToday = detail("c5", 6, 1, 2, 1, 20, CourseDetail.TYPE_ODD);
        List<CourseDetail> filtered = TimetableLogic.todayCourses(
                Arrays.asList(oddToday),
                config("2026-09-07", 20, 12),
                TaskLogic.date(2026, 10, 31, 8, 0));
        check("第 8 周不显示单周课", filtered.isEmpty());
    }

    private static void testCsvSuccess() {
        TimetableCsvParser.Result result = TimetableCsvParser.parse(
                "高等数学,一,1,2,张三,教一201,1-16\n线性代数,三,3,4,李四,A301,2-18\n", 20);
        check("CSV 成功解析 2 门课程", result.bases.size() == 2);
        check("CSV 成功解析 2 条排课", result.details.size() == 2);
        check("CSV 无错误", result.errors.isEmpty());

        CourseDetail first = result.details.get(0);
        check("CSV 映射课程名与基础 ID",
                result.bases.get(0).name.equals("高等数学") && first.baseId.equals(result.bases.get(0).id));
        check("CSV 映射星期/节次/step",
                first.day == 1 && first.startNode == 1 && first.step == 2 && first.endNode() == 2);
        check("CSV 映射教师与教室", first.teacher.equals("张三") && first.room.equals("教一201"));
        check("CSV 映射周次范围", first.startWeek == 1 && first.endWeek == 16);
        check("CSV 默认连续周", first.type == CourseDetail.TYPE_ALL);

        CourseDetail second = result.details.get(1);
        check("CSV 第二行周次范围", second.startWeek == 2 && second.endWeek == 18);
    }

    private static void testCsvSeparators() {
        String comma = "课程,1,1,2,教师,教室,1-16";
        String semicolon = comma.replace(',', ';');
        String tab = comma.replace(',', '\t');
        check("逗号分隔成功", TimetableCsvParser.parse(comma, 20).details.size() == 1);
        check("分号分隔成功", TimetableCsvParser.parse(semicolon, 20).details.size() == 1);
        check("Tab 分隔成功", TimetableCsvParser.parse(tab, 20).details.size() == 1);
    }

    private static void testCsvOddEvenAndWeekList() {
        TimetableCsvParser.Result odd = TimetableCsvParser.parse("英语,2,1,2,王五,B101,1-15单周", 20);
        check("单周解析", odd.details.get(0).type == CourseDetail.TYPE_ODD
                && odd.details.get(0).startWeek == 1 && odd.details.get(0).endWeek == 15);

        TimetableCsvParser.Result even = TimetableCsvParser.parse("体育,3,1,2,赵六,操场,2-16双周", 20);
        check("双周解析", even.details.get(0).type == CourseDetail.TYPE_EVEN);

        TimetableCsvParser.Result list = TimetableCsvParser.parse("研讨,4,5,6,孙七,C202,1,3,5,7", 20);
        check("周次列表拆成 4 条", list.details.size() == 4);
        check("周次列表每个都是单周",
                list.details.get(0).startWeek == 1 && list.details.get(0).endWeek == 1
                        && list.details.get(3).startWeek == 7 && list.details.get(3).endWeek == 7);
    }

    private static void testCsvErrors() {
        TimetableCsvParser.Result shortRow = TimetableCsvParser.parse("课程,1,1,2,教师,教室", 20);
        check("少于 7 列报错", shortRow.details.isEmpty() && shortRow.errors.size() == 1);
        check("少于 7 列错误包含行号", shortRow.errors.get(0).startsWith("第 1 行"));

        TimetableCsvParser.Result badDay = TimetableCsvParser.parse("课程,8,1,2,教师,教室,1-16", 20);
        check("星期无效报错", badDay.details.isEmpty() && badDay.errors.size() == 1);

        TimetableCsvParser.Result badNode = TimetableCsvParser.parse("课程,1,0,2,教师,教室,1-16", 20);
        check("开始节次非法报错", badNode.details.isEmpty() && badNode.errors.size() == 1);

        TimetableCsvParser.Result reversedNode = TimetableCsvParser.parse("课程,1,3,2,教师,教室,1-16", 20);
        check("结束节次早于开始报错", reversedNode.details.isEmpty() && reversedNode.errors.size() == 1);

        TimetableCsvParser.Result badWeek = TimetableCsvParser.parse("课程,1,1,2,教师,教室,16-1", 20);
        check("周次倒序报错", badWeek.details.isEmpty() && badWeek.errors.size() == 1);

        TimetableCsvParser.Result empty = TimetableCsvParser.parse("", 20);
        check("空文件报错", empty.details.isEmpty() && empty.errors.get(0).equals("文件为空"));
    }

    private static void testCsvHeaderAlias() {
        TimetableCsvParser.Result result = TimetableCsvParser.parse(
                "课程名称,星期,开始节,结束节,教师,教室,周次\n数据结构,周五,7,8,钱八,D404,3-18\n", 20);
        check("表头别名解析成功", result.details.size() == 1);
        check("表头别名映射课程", result.bases.get(0).name.equals("数据结构"));
        CourseDetail detail = result.details.get(0);
        check("表头别名映射星期/节次/周次",
                detail.day == 5 && detail.startNode == 7 && detail.step == 2
                        && detail.startWeek == 3 && detail.endWeek == 18);
    }

    private static TimetableConfig config(String startDate, int maxWeek, int nodes) {
        return new TimetableConfig(startDate, maxWeek, nodes, false, false, true);
    }

    private static CourseDetail detail(String id, int day, int startNode, int step,
                                       int startWeek, int endWeek, int type) {
        return new CourseDetail(id, "base-" + id, day, startNode, step, startWeek, endWeek, type, "", "", 0);
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
