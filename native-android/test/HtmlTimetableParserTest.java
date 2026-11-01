package test;

import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.domain.HtmlTimetableParser;
import com.checktodo.app.domain.SimpleHtmlParser;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** HTML 课表导入纯 Java 测试：容错解析、rowspan/colspan、识别启发式、编码回落。 */
public final class HtmlTimetableParserTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testEntityDecodeAndFullWidthSpace();
        testRowspanExpansion();
        testColspanExpansion();
        testUnclosedTagsTolerance();
        testScriptStyleSkipped();
        testTimetableRecognition();
        testNonTimetableTableRejected();
        testNodeBoundarySkipped();
        testEncodingFallback();
        testKingosoftBracketNodeFormat();

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void testEntityDecodeAndFullWidthSpace() {
        List<SimpleHtmlParser.Table> tables = SimpleHtmlParser.tables(
                "<table><tr><td>A&nbsp;&amp;&nbsp;B&lt;C&gt;　D</td></tr></table>");
        String text = tables.get(0).rows.get(0).get(0);
        check("实体解码", text.equals("A & B<C> D"));
    }

    private static void testRowspanExpansion() {
        String html = "<table>"
                + "<tr><td>节次</td><td>周一</td><td>周二</td><td>周三</td><td>周四</td><td>周五</td></tr>"
                + "<tr><td>1</td><td rowspan='2'>连课</td><td></td><td></td><td></td><td></td></tr>"
                + "<tr><td>2</td><td></td><td></td><td></td><td></td></tr>"
                + "</table>";
        SimpleHtmlParser.Table table = SimpleHtmlParser.tables(html).get(0);
        check("rowspan 展开行数", table.rows.size() == 3);
        check("rowspan 第一行文本", table.rows.get(1).get(1).equals("连课"));
        check("rowspan 第二行复制文本", table.rows.get(2).get(1).equals("连课"));
        check("rowspan 后列位保持", table.rows.get(2).get(2).isEmpty());
    }

    private static void testColspanExpansion() {
        String html = "<TABLE><TR><TD COLSPAN=2>上午</TD><TD>三</TD><TD>四</TD><TD>五</TD></TR>"
                + "<TR><TD>1</TD><TD></TD><TD></TD><TD></TD><TD></TD></TR></TABLE>";
        SimpleHtmlParser.Table table = SimpleHtmlParser.tables(html).get(0);
        check("colspan 展开宽度", table.columns() == 5);
        check("colspan 两列同文本", table.rows.get(0).get(0).equals("上午")
                && table.rows.get(0).get(1).equals("上午"));
        check("标签大小写与无引号属性", table.rows.get(0).get(2).equals("三"));
    }

    private static void testUnclosedTagsTolerance() {
        String html = "<table><tr><td>1-16周<br>课程A";
        List<SimpleHtmlParser.Table> tables = SimpleHtmlParser.tables(html);
        check("未闭合标签仍出表格", tables.size() == 1);
        String text = tables.get(0).rows.get(0).get(0);
        check("br 转换行", text.equals("1-16周\n课程A"));
    }

    private static void testScriptStyleSkipped() {
        String html = "<table><tr><td>课程<script>document.write('<td>污染')</script></td></tr></table>";
        String text = SimpleHtmlParser.tables(html).get(0).rows.get(0).get(0);
        check("script 内容不污染单元格", text.equals("课程"));
    }

    private static void testTimetableRecognition() {
        HtmlTimetableParser.Result result = HtmlTimetableParser.parse(sampleTimetable(), 20, 12);
        check("识别无致命错误", result.errors.isEmpty());
        check("解析出 5 门课", result.bases.size() == 5);
        check("解析出排课数", result.details.size() >= 6);
        check("网格信息包含行列", result.gridInfo.contains("列") && result.gridInfo.contains("行"));

        CourseDetail math = find(result, "高等数学");
        check("连课占两节", math != null && math.startNode == 1 && math.step == 2);
        check("连课教师", math != null && math.teacher.equals("张三"));
        check("连课教室", math != null && math.room.equals("A101"));
        CourseDetail sport = find(result, "体育");
        check("单周课类型", sport != null && sport.type == CourseDetail.TYPE_ODD);
        CourseDetail english = find(result, "大学英语");
        check("双周课类型", english != null && english.type == CourseDetail.TYPE_EVEN);
        check("双周课星期", english != null && english.day == 3);
        List<CourseDetail> segments = findAll(result, "数据结构");
        check("多段周次拆分", segments.size() == 2);
        check("多段周次第一段", segments.get(0).startWeek == 1 && segments.get(0).endWeek == 8);
        check("多段周次第二段双周", segments.get(1).startWeek == 10 && segments.get(1).endWeek == 16
                && segments.get(1).type == CourseDetail.TYPE_EVEN);
        check("实体解码进入课程名", find(result, "软件 工程") != null);
    }

    private static void testNonTimetableTableRejected() {
        String html = "<html><body><table><tr><td>学号</td><td>姓名</td><td>班级</td></tr>"
                + "<tr><td>101</td><td>张三</td><td>1班</td></tr></table></body></html>";
        HtmlTimetableParser.Result result = HtmlTimetableParser.parse(html, 20, 12);
        check("非课表表格报错", !result.errors.isEmpty());
        check("错误提示建议 CSV", result.errors.get(0).contains("CSV"));
        check("无课程导入", result.bases.isEmpty());
    }

    private static void testNodeBoundarySkipped() {
        HtmlTimetableParser.Result result = HtmlTimetableParser.parse(sampleTimetable(), 20, 2);
        boolean skipped = false;
        for (String warning : result.warnings) {
            if (warning.contains("超出当前时间表")) skipped = true;
        }
        check("超节次排课跳过并告警", skipped);
        for (CourseDetail detail : result.details) {
            if (detail.endNode() > 2) {
                check("超节次排课未入库", false);
                return;
            }
        }
        check("范围内排课保留", !result.details.isEmpty());
    }

    private static void testEncodingFallback() throws Exception {
        check("UTF-8 解码", HtmlTimetableParser.decode(
                "课程A".getBytes(StandardCharsets.UTF_8)).equals("课程A"));
        check("UTF-8 BOM 剥离", HtmlTimetableParser.decode(
                new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a'}).equals("a"));
        check("GBK 回落", HtmlTimetableParser.decode(
                "高等数学".getBytes("GBK")).contains("高等数学"));
        check("空输入", HtmlTimetableParser.decode(new byte[0]).isEmpty());
    }

    /** 模拟教务系统导出页：表头 + rowspan 连课 + 单双周 + 多段周次 + 实体。 */
    private static String sampleTimetable() {
        return "<html><head><title>课表</title><style>td{border:1px}</style></head><body>"
                + "<table border='1' cellpadding='2'>"
                + "<tr><td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td><td>星期四</td><td>星期五</td></tr>"
                + "<tr><td>1</td><td rowspan='2'>高等数学<br>1-16周(单)<br>张三<br>A101</td>"
                + "<td></td><td>大学英语<br>1-16周(双)<br>李四<br>B202</td><td></td><td></td></tr>"
                + "<tr><td>2</td><td></td><td>体育<br>1-16周(单)<br>王五<br>操场</td><td></td><td></td></tr>"
                + "<tr><td>3</td><td></td><td>数据结构<br>1-8周、10-16周(双)<br>赵六<br>教三305</td>"
                + "<td>软件&nbsp;工程<br>1-16周<br>钱七<br>主楼201</td><td></td></tr>"
                + "</table></body></html>";
    }

    /** KINGOSOFT 常见格式：1-16周[1-3]节、1-16[5-6]节、1-16周（单）。 */
    private static void testKingosoftBracketNodeFormat() {
        String html = "<html><body><table border='1'>"
                + "<tr><td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td><td>星期四</td><td>星期五</td></tr>"
                + "<tr><td>1</td><td rowspan='3'>数据结构<br>1-16周[1-3]节<br>张三<br>教学楼301</td>"
                + "<td></td><td>大学英语<br>1-16[5-6]节<br>李四<br>B202</td><td></td><td></td></tr>"
                + "<tr><td>4</td><td></td><td>体育<br>1-16周（单）<br>王五<br>操场</td>"
                + "<td></td><td></td><td></td></tr>"
                + "</table></body></html>";
        HtmlTimetableParser.Result result = HtmlTimetableParser.parse(html, 20, 12);
        check("方括号节次格式识别", result.errors.isEmpty());
        CourseDetail data = find(result, "数据结构");
        check("方括号节次起止", data != null && data.startNode == 1 && data.step == 3);
        check("方括号节次周次", data != null && data.startWeek == 1 && data.endWeek == 16);
        CourseDetail english = find(result, "大学英语");
        check("无周字周次仍解析", english != null && english.startWeek == 1
                && english.endWeek == 16 && english.startNode == 5 && english.step == 2);
        CourseDetail sport = find(result, "体育");
        check("全角括号单周", sport != null && sport.type == CourseDetail.TYPE_ODD);
    }

    private static CourseDetail find(HtmlTimetableParser.Result result, String name) {
        List<CourseDetail> all = findAll(result, name);
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<CourseDetail> findAll(HtmlTimetableParser.Result result, String name) {
        List<CourseDetail> found = new java.util.ArrayList<>();
        for (CourseDetail detail : result.details) {
            if (name.equals(baseName(result, detail))) found.add(detail);
        }
        return found;
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
