package test;

import com.checktodo.app.domain.CourseBase;
import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.domain.HtmlTimetableParser;
import com.checktodo.app.domain.ScauGridParser;
import com.checktodo.app.domain.SimpleHtmlParser;
import com.checktodo.app.domain.TimeNode;
import com.checktodo.app.domain.TimetableParserRegistry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 华农（新版正方 jwglxt）课表网格的定点解析测试。
 *
 * <p>夹具 {@code test/fixtures/scau_grid.htm} 是真实页面 {@code D:\Desktop\scau.htm}
 * 的原样拷贝（罗欣悦，2026-2027 学年第 1 学期，学号 202613310215）。</p>
 *
 * <p><b>实测事实（先跑 harness 数清，再写死断言）：</b></p>
 * <ul>
 *   <li>网格表 1 张：{@code id="kbgrid_table_0" class="... timetable1"}；</li>
 *   <li>{@code td_wrap} 课程格 <b>16</b> 个，其中 3 个格子里各有 2 个
 *       {@code div.timetable_con}（5-1、5-8、4-10），合计 <b>19 个课程块</b>；</li>
 *   <li>去重后 <b>14 门课</b>、<b>22 条排课</b>（大学数学Ⅰ 的 4-7/9-14 拆成两条、
 *       国家安全教育与军事理论 的 4-4 与 5-14 各一条）；</li>
 *   <li>解析 <b>0 警告</b>；</li>
 *   <li>节次时间 <b>15</b> 节（1: 08:00-08:40 … 15: 21:00-21:40）。</li>
 * </ul>
 *
 * <p>任务书中的「12 个课程块 / 大学数学Ⅰ×8 / 国家安全教育与军事理论×4」与实测不符：
 * 12 应是「含<u>该课</u>的格子数」而非课程块数。本测试以实测为准，并在报告里说明差异。</p>
 */
public final class ScauGridParserTest {
    private static int passed;
    private static int failed;

    private static final String FIXTURE = "test/fixtures/scau_grid.htm";

    private static String cachedHtml;

    public static void main(String[] args) {
        testDetection();
        testStructureFacts();
        testCourseTotals();
        testUniversityMath();
        testMilitaryTheory();
        testMarkerStripping();
        testWeekGrammar();
        testTimeNodes();
        testFieldClassification();
        testBothMarkerVariants();
        testSingleLineForm();
        testBothLayoutsEndToEnd();
        testIntegration();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static String html() {
        if (cachedHtml == null) cachedHtml = readFixture();
        return cachedHtml;
    }

    private static ScauGridParser.Outcome parse() {
        return ScauGridParser.parse(html(), 20, 15);
    }

    // ------------------------------------------------------------------

    private static void testDetection() {
        check("looksLikeScau 识别夹具", ScauGridParser.looksLikeScau(html()));
        check("非课表页面不识别", !ScauGridParser.looksLikeScau("<html><body>hi</body></html>"));
        check("null 不识别", !ScauGridParser.looksLikeScau(null));

        int scauTables = 0;
        for (SimpleHtmlParser.Table table : SimpleHtmlParser.tables(html())) {
            if (ScauGridParser.isScauTable(table, html())) scauTables++;
        }
        check("只识别 1 张华农网格表", scauTables == 1);

        // 同页的 kblist_table（课程列表）不能被误认成课表网格
        boolean kblistMisdetected = false;
        for (SimpleHtmlParser.Table table : SimpleHtmlParser.tables(html())) {
            if ("kblist_table".equals(table.id) && ScauGridParser.isScauTable(table, html())) {
                kblistMisdetected = true;
            }
        }
        check("kblist_table 不被误判", !kblistMisdetected);
    }

    private static void testStructureFacts() {
        ScauGridParser.Outcome outcome = parse();
        check("识别到 1 张网格", outcome.tablesMatched == 1);
        check("★课程块 19 个（实测）", outcome.blocksSeen == 19);
        check("解析 0 警告", outcome.result.warnings.isEmpty());
        check("解析 0 错误", outcome.result.errors.isEmpty());
        check("gridInfo 含「华农」", outcome.result.gridInfo.contains("华农"));
        check("gridInfo 含课程块数", outcome.result.gridInfo.contains("19 个课程块"));
        check("表格标题含学期", outcome.tableTitle.contains("2026-2027"));
        check("表格标题含姓名", outcome.tableTitle.contains("罗欣悦"));
    }

    private static void testCourseTotals() {
        ScauGridParser.Outcome outcome = parse();
        check("★14 门课", outcome.result.bases.size() == 14);
        check("★22 条排课", outcome.result.details.size() == 22);

        List<String> names = new ArrayList<>();
        for (CourseBase base : outcome.result.bases) names.add(base.name);
        String[] expected = {
                "大学数学Ⅰ", "基础化学实验Ⅰ", "体育Ⅰ[无等级][跆拳道品势01][跆拳道品势]",
                "大学英语Ⅰ", "植物学实验", "无机及分析化学", "种子科学与工程专业概论",
                "种业强国建设概论", "习近平新时代中国特色社会主义思想概论",
                "国家安全教育与军事理论", "人工智能通识A（公共课）", "植物学",
                "形势与政策Ⅰ", "走在前列的广东实践"
        };
        for (String name : expected) {
            check("课程列表含「" + name + "」", names.contains(name));
        }
        // baseId 不重复
        java.util.Set<String> ids = new java.util.HashSet<>();
        boolean unique = true;
        for (CourseBase base : outcome.result.bases) {
            if (!ids.add(base.id)) unique = false;
        }
        check("baseId 唯一", unique);
        // 每条排课都有有效坐标
        boolean coordsOk = true;
        for (CourseDetail detail : outcome.result.details) {
            if (detail.day < 1 || detail.day > 7 || detail.startNode < 1
                    || detail.step < 1 || detail.startWeek < 1
                    || detail.endWeek < detail.startWeek) {
                coordsOk = false;
            }
        }
        check("所有排课坐标合法", coordsOk);
    }

    /** 大学数学Ⅰ：周三 3-1 (1-2节) 4-14周；周五 5-1 拆 4-7 / 9-14；周四 4-8 (8-9节) 4-14周。 */
    private static void testUniversityMath() {
        ScauGridParser.Outcome outcome = parse();
        List<CourseDetail> math = detailsOf(outcome, "大学数学Ⅰ");
        // 5 条：周三 4-14、周五 4-7 / 9-14 / 8-8、周四 4-14
        check("大学数学Ⅰ 共 5 条排课", math.size() == 5);

        CourseDetail wed = find(math, 3, 1, 4, 14);
        check("★大学数学Ⅰ 周三(3-1) 4-14周", wed != null);
        if (wed != null) {
            check("周三 (1-2节) 起始 1 跨 2 节", wed.startNode == 1 && wed.step == 2);
            check("周三教室 4203", "4203".equals(wed.room));
            check("周三教师 卢建平", "卢建平".equals(wed.teacher));
        }

        CourseDetail friA = find(math, 5, 1, 4, 7);
        CourseDetail friB = find(math, 5, 1, 9, 14);
        check("★大学数学Ⅰ 周五(5-1) 拆出 4-7周", friA != null);
        check("★大学数学Ⅰ 周五(5-1) 拆出 9-14周", friB != null);
        if (friA != null) check("周五 4-7 段教室 4308", "4308".equals(friA.room));

        CourseDetail friSunday = find(math, 5, 1, 8, 8);
        check("周五第二个课程块 第8-8周", friSunday != null);
        if (friSunday != null) {
            check("第8周单周教室 5B602", "5B602".equals(friSunday.room));
        }

        CourseDetail thu = find(math, 4, 8, 4, 14);
        check("★大学数学Ⅰ 周四(4-8) 4-14周", thu != null);
        if (thu != null) {
            check("周四 (8-9节) 起始 8 跨 2 节", thu.startNode == 8 && thu.step == 2);
            check("周四教室 4104", "4104".equals(thu.room));
        }
    }

    /** 国家安全教育与军事理论：周四 4-10 两个块，(10-12节) 4-4周 与 5-14周。 */
    private static void testMilitaryTheory() {
        ScauGridParser.Outcome outcome = parse();
        List<CourseDetail> military = detailsOf(outcome, "国家安全教育与军事理论");
        check("国家安全教育与军事理论 共 2 条排课", military.size() == 2);
        CourseDetail single = find(military, 4, 10, 4, 4);
        CourseDetail ranged = find(military, 4, 10, 5, 14);
        check("★4-4周（单周）存在", single != null);
        check("★5-14周存在", ranged != null);
        if (single != null) {
            check("4-4周 (10-12节) 跨 3 节", single.startNode == 10 && single.step == 3);
            check("4-4周教室 5C403", "5C403".equals(single.room));
        }
        if (ranged != null) check("5-14周教室 5C603", "5C603".equals(ranged.room));
        check("教师 曹汉林", single != null && "曹汉林".equals(single.teacher));
    }

    private static void testMarkerStripping() {
        check("★剥离【调】", "大学数学Ⅰ".equals(ScauGridParser.cleanTitle("【调】大学数学Ⅰ")));
        check("剥离【补】", "高等数学".equals(ScauGridParser.cleanTitle("【补】高等数学")));
        check("剥离【停】", "线性代数".equals(ScauGridParser.cleanTitle("【停】线性代数")));
        check("剥离（调）中文括号", "离散数学".equals(ScauGridParser.cleanTitle("（调）离散数学")));
        check("无标记原样保留", "大学英语Ⅰ".equals(ScauGridParser.cleanTitle("大学英语Ⅰ")));
        check("null 返回空", ScauGridParser.cleanTitle(null).isEmpty());

        // 夹具里所有【调】都被剥干净
        ScauGridParser.Outcome outcome = parse();
        boolean anyMarked = false;
        for (CourseBase base : outcome.result.bases) {
            if (base.name.contains("【") || base.name.contains("】")) anyMarked = true;
        }
        check("★解析结果中无【】残留", !anyMarked);
    }

    private static void testWeekGrammar() {
        // 「4-7周,9-14周」必须解成两段
        List<int[]> segments = weekSegments("(1-2节)4-7周,9-14周");
        check("★逗号分段解成 2 段", segments.size() == 2);
        if (segments.size() == 2) {
            check("第一段 4-7", segments.get(0)[0] == 4 && segments.get(0)[1] == 7);
            check("第二段 9-14", segments.get(1)[0] == 9 && segments.get(1)[1] == 14);
        }
        check("每段带周字也支持", weekSegments("4-14周").size() == 1);
        check("第8-8周支持", weekSegments("(1-2节)第8-8周").size() == 1);
        check("(单)标记支持", weekSegments("1-16周(单)").get(0)[2] == CourseDetail.TYPE_ODD);
        check("(双)标记支持", weekSegments("1-16周(双)").get(0)[2] == CourseDetail.TYPE_EVEN);

        // 节次范围必须被剥离，不能混进周次
        String stripped = ScauGridParser.stripNodeRange("(1-2节)4-14周");
        check("节次括号被剥离", stripped.equals("4-14周"));
        check("剥离后只有一段周次", weekSegments("(1-2节)4-14周").size() == 1);

        // 班级名单不能被当成周次
        check("★班级名单不算周次行",
                !ScauGridParser.isWeekLine("26种子科学1-2,26林学(林学+食工双学位)1"));
        check("★班级名单不算周次行（含逗号）",
                !ScauGridParser.isWeekLine("26农学1-3,26种子科学1-2"));
        check("真实周次行被识别", ScauGridParser.isWeekLine("(1-2节)4-14周"));
        check("第8-8周被识别", ScauGridParser.isWeekLine("第8-8周"));
    }

    private static void testTimeNodes() {
        List<TimeNode> nodes = ScauGridParser.parseTimeNodes(html());
        check("★节次时间 15 节", nodes.size() == 15);
        TimeNode first = findNode(nodes, 1);
        check("第1节 08:00-08:40",
                first != null && first.startTime.equals("08:00") && first.endTime.equals("08:40"));
        TimeNode eighth = findNode(nodes, 8);
        check("第8节 14:30-15:10",
                eighth != null && eighth.startTime.equals("14:30") && eighth.endTime.equals("15:10"));
        TimeNode last = findNode(nodes, 15);
        check("第15节 21:00-21:40",
                last != null && last.startTime.equals("21:00") && last.endTime.equals("21:40"));

        // 时间必须升序且都在合法范围
        boolean ordered = true;
        for (int i = 1; i < nodes.size(); i++) {
            if (nodes.get(i).node <= nodes.get(i - 1).node) ordered = false;
        }
        check("节次号升序", ordered);
        boolean validTimes = true;
        for (TimeNode node : nodes) {
            if (!node.startTime.matches("\\d{2}:\\d{2}") || !node.endTime.matches("\\d{2}:\\d{2}")) {
                validTimes = false;
            }
        }
        check("时间格式统一 HH:mm", validTimes);
        check("页面无时间时返回空", ScauGridParser.parseTimeNodes("<html>").isEmpty());
        check("null 返回空", ScauGridParser.parseTimeNodes(null).isEmpty());
    }

    private static void testFieldClassification() {
        check("纯人名 卢建平", ScauGridParser.looksLikePersonName("卢建平"));
        check("多教师 徐振江,冯发强,王州飞", ScauGridParser.looksLikePersonName("徐振江,冯发强,王州飞"));
        check("★班级名单不是人名",
                !ScauGridParser.looksLikePersonName("26种子科学1-2,26林学(林学+食工双学位)1"));
        check("含数字不是人名", !ScauGridParser.looksLikePersonName("课程A1"));
        check("英文不是人名", !ScauGridParser.looksLikePersonName("John"));
        check("单字不是人名", !ScauGridParser.looksLikePersonName("张"));

        // 教室必须带前缀才认，避免把「5B802」这种裸串误当教室
        ScauGridParser.Outcome outcome = parse();
        boolean roomsOk = true;
        for (CourseDetail detail : outcome.result.details) {
            if (detail.room.contains("上课地点") || detail.room.contains(":")) roomsOk = false;
        }
        check("★教室不含「上课地点:」前缀", roomsOk);
        check("选课人数未进教室/教师", !outcome.result.gridInfo.contains("选课人数"));
    }

    /** 入口优先级：Kingo → 华农 → 通用启发；以及注册表插件。 */
    private static void testIntegration() {
        HtmlTimetableParser.Result viaEntry = HtmlTimetableParser.parse(html(), 20, 15);
        check("★HtmlTimetableParser 入口直接解析出 22 条",
                viaEntry.details.size() == 22);
        check("入口命中华农（gridInfo 含华农）",
                viaEntry.gridInfo.contains("华农"));

        TimetableParserRegistry.Outcome viaRegistry = TimetableParserRegistry.parse(
                new TimetableParserRegistry.Input(html(),
                        "https://jwgl.scau.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html",
                        "text/html", 20, 15));
        check("注册表命中 ScauGrid", "ScauGrid".equals(viaRegistry.winner));
        check("注册表解析出 14 门课", viaRegistry.result.bases.size() == 14);
        check("注册表解析出 22 条", viaRegistry.result.details.size() == 22);
        check("ScauGrid 优先级在 HtmlGrid 之前", priorityOf("ScauGrid") < priorityOf("HtmlGrid"));

        // 北师大 Kingo 夹具仍走 Kingo 路径，不被华农抢先
        String kingo = readFixtureFile("test/fixtures/kingosoft_kb.htm");
        HtmlTimetableParser.Result kingoResult = HtmlTimetableParser.parse(kingo, 20, 12);
        check("★Kingo 夹具仍解析出 11 门课（未被华农干扰）",
                kingoResult.bases.size() == 11);
        check("Kingo 夹具 19 条排课", kingoResult.details.size() == 19);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 两种课程块标记变体都必须被解析：{@code <u class="title1" data-jxb_id=...>}（调课记录）
     * 与 {@code <span class="title1">}（正常课程）。
     *
     * <p>夹具 {@code kbgrid_table_0} 内实测：{@code <u>} 6 个（大学数学Ⅰ×4、
     * 国家安全教育与军事理论×2）、{@code <span>} 13 个，合计 19 个课程块。</p>
     */
    private static void testBothMarkerVariants() {
        String html = html();
        int start = html.indexOf("kbgrid_table_0");
        // 必须截到该表格结束：此后还有 11 张表，里面有同一批课程块的重复副本
        // （页面为了兼容旧版渲染把课表重复输出了多次），不截断会把数量数成 12/26。
        int end = start < 0 ? html.length() : html.indexOf("</table>", start);
        if (end < 0) end = html.length();
        String grid = start < 0 ? html : html.substring(start, end);

        java.util.regex.Matcher u = java.util.regex.Pattern.compile(
                "<u[^>]*class=\"title1[^\"]*\"[^>]*>(.*?)</u>",
                java.util.regex.Pattern.DOTALL).matcher(grid);
        java.util.regex.Matcher span = java.util.regex.Pattern.compile(
                "<span[^>]*class=\"title1[^\"]*\"[^>]*>(.*?)</span>",
                java.util.regex.Pattern.DOTALL).matcher(grid);
        int uCount = 0;
        int spanCount = 0;
        while (u.find()) uCount++;
        while (span.find()) spanCount++;
        check("网格内 <u class=title1> 共 6 块", uCount == 6);
        check("网格内 <span class=title1> 共 13 块", spanCount == 13);
        check("两种变体合计 19 块（= 解析出的课程块数）", uCount + spanCount == 19);

        // 两种变体对应的课名都在结果里
        ScauGridParser.Outcome outcome = parse();
        List<String> names = new ArrayList<>();
        for (CourseBase base : outcome.result.bases) names.add(base.name);
        check("★u 变体（调课）课名存在：大学数学Ⅰ", names.contains("大学数学Ⅰ"));
        check("★span 变体（正常课）课名存在：大学英语Ⅰ", names.contains("大学英语Ⅰ"));
        check("★span 变体课名存在：植物学实验", names.contains("植物学实验"));
        check("★span 变体课名存在：习近平新时代中国特色社会主义思想概论",
                names.contains("习近平新时代中国特色社会主义思想概论"));
        check("★span 变体课名存在：人工智能通识A（公共课）",
                names.contains("人工智能通识A（公共课）"));

        // 抽查处方断言
        List<CourseDetail> english = detailsOf(outcome, "大学英语Ⅰ");
        check("★大学英语Ⅰ = (3-4节)4-15周 一条", english.size() == 1);
        if (!english.isEmpty()) {
            CourseDetail detail = english.get(0);
            check("大学英语Ⅰ 星期 3", detail.day == 3);
            check("大学英语Ⅰ 起始节 3、跨度 2", detail.startNode == 3 && detail.step == 2);
            check("大学英语Ⅰ 4-15 周", detail.startWeek == 4 && detail.endWeek == 15);
            check("大学英语Ⅰ 教室 5B502", "5B502".equals(detail.room));
        }

        List<CourseDetail> botanyLab = detailsOf(outcome, "植物学实验");
        check("★植物学实验 = (8-11节) 7-12/14-17 拆两条", botanyLab.size() == 2);
        check("植物学实验 7-12 周存在", find(botanyLab, 1, 8, 7, 12) != null);
        check("植物学实验 14-17 周存在", find(botanyLab, 1, 8, 14, 17) != null);
        if (find(botanyLab, 1, 8, 7, 12) != null) {
            CourseDetail detail = find(botanyLab, 1, 8, 7, 12);
            check("植物学实验 (8-11节) 跨度 4 节", detail.step == 4);
        }

        List<CourseDetail> xi = detailsOf(outcome, "习近平新时代中国特色社会主义思想概论");
        check("★习概 = (10-12节)4-14周 一条", xi.size() == 1);
        if (!xi.isEmpty()) {
            check("习概 星期 2", xi.get(0).day == 2);
            check("习概 起始节 10、跨度 3", xi.get(0).startNode == 10 && xi.get(0).step == 3);
            check("习概 4-14 周", xi.get(0).startWeek == 4 && xi.get(0).endWeek == 14);
            check("习概 教室 5C304", "5C304".equals(xi.get(0).room));
            check("习概 教师 陈洁", "陈洁".equals(xi.get(0).teacher));
        }

        // (13-15节)4-14周 的连 3 节跨度
        List<CourseDetail> botany = detailsOf(outcome, "植物学");
        check("★植物学 (13-15节)4-14周 跨度 3", botany.size() == 1
                && botany.get(0).startNode == 13 && botany.get(0).step == 3
                && botany.get(0).startWeek == 4 && botany.get(0).endWeek == 14);
    }

    /** 单行式：周次/地点/教师/班级/人数全在一个 <p> 里，用双空格分隔。 */
    private static void testSingleLineForm() {
        String cy = "4-17周  上课地点:东区田径看台2  赵燕  26种子科学2  选课人数:40";
        String multiWeek = "7-12周,14-17周  上课地点:DS526  钟伟良  26种子科学2  选课人数:32";
        String withClass = "4-16周  上课地点:5B304  唐小兰  26种子科学1-2,26生物育种(植物育种)1  选课人数:122";

        check("★单行式被识别", ScauGridParser.isSingleLineForm(cy));
        check("★单行式被识别（逗号分段周次）", ScauGridParser.isSingleLineForm(multiWeek));
        check("多行式不被误判为单行式",
                !ScauGridParser.isSingleLineForm("上课地点:4203"));
        check("纯周次行不被误判为单行式",
                !ScauGridParser.isSingleLineForm("(1-2节)4-14周"));

        check("单行式周次段 = 4-17周", "4-17周".equals(ScauGridParser.findWeekSpan(cy)));
        check("★单行式周次段 = 7-12周,14-17周（逗号分段完整取到）",
                "7-12周,14-17周".equals(ScauGridParser.findWeekSpan(multiWeek)));
        check("★单行式周次段不含班级数字（4-16周）",
                "4-16周".equals(ScauGridParser.findWeekSpan(withClass)));

        // 关键：只取周次段解析，不能把「5B304」的 5、「26种子科学1-2」的 1-2 当周次
        List<int[]> segments = weekSegments(ScauGridParser.findWeekSpan(withClass));
        check("★单行式只解析出 1 段周次（无假周次）", segments.size() == 1);
        if (segments.size() == 1) {
            check("单行式周次 4-16", segments.get(0)[0] == 4 && segments.get(0)[1] == 16);
        }
        List<int[]> multi = weekSegments(ScauGridParser.findWeekSpan(multiWeek));
        check("★单行式逗号周次解出 2 段", multi.size() == 2);

        // 用单行式拼一个最小网格，端到端验证教室/教师/周次
        String html = "<table id=\"kbgrid_table_0\" class=\"timetable1\"><tr>"
                + "<td rowspan=\"2\" id=\"1-3\" class=\"td_wrap\">"
                + "<div class=\"timetable_con\"><span class=\"title1\">体育Ⅰ</span>"
                + "<p>" + cy + "</p></div></td></tr></table>";
        ScauGridParser.Outcome outcome = ScauGridParser.parse(html, 20, 15);
        check("★单行式网格解析出 1 门课", outcome.result.bases.size() == 1);
        check("★单行式网格解析出 1 条排课", outcome.result.details.size() == 1);
        if (!outcome.result.details.isEmpty()) {
            CourseDetail detail = outcome.result.details.get(0);
            check("单行式 星期 1、起始节 3", detail.day == 1 && detail.startNode == 3);
            check("单行式 4-17 周", detail.startWeek == 4 && detail.endWeek == 17);
            check("★单行式教室 = 东区田径看台2", "东区田径看台2".equals(detail.room));
            check("★单行式教师 = 赵燕", "赵燕".equals(detail.teacher));
        }
        check("单行式解析无警告", outcome.result.warnings.isEmpty());
    }

    /** 同一页面里多行式与单行式混排，两种都要吃下。 */
    private static void testBothLayoutsEndToEnd() {
        String html = "<table id=\"kbgrid_table_0\" class=\"timetable1\"><tr>"
                // 多行式
                + "<td rowspan=\"2\" id=\"3-1\" class=\"td_wrap\">"
                + "<div class=\"timetable_con\"><u class=\"title1\">【调】大学数学Ⅰ</u>"
                + "<p>(1-2节)4-14周</p><p>上课地点:4203</p><p>卢建平</p>"
                + "<p>26种子科学1-2</p><p>选课人数:100</p></div></td>"
                // 单行式
                + "<td rowspan=\"4\" id=\"4-2\" class=\"td_wrap\">"
                + "<div class=\"timetable_con\"><span class=\"title1\">基础化学实验Ⅰ</span>"
                + "<p>7-12周,14-15周  上课地点:DS806  陈明洁  26种子科学2  选课人数:32</p></div></td>"
                + "</tr></table>";
        ScauGridParser.Outcome outcome = ScauGridParser.parse(html, 20, 15);
        check("混排：解析出 2 门课", outcome.result.bases.size() == 2);
        check("混排：解析出 3 条排课（1 + 2 段）", outcome.result.details.size() == 3);
        check("混排：0 警告", outcome.result.warnings.isEmpty());

        List<CourseDetail> math = detailsOf(outcome, "大学数学Ⅰ");
        check("混排：多行式 4-14 周", find(math, 3, 1, 4, 14) != null);
        List<CourseDetail> chem = detailsOf(outcome, "基础化学实验Ⅰ");
        check("混排：单行式 7-12 周", find(chem, 4, 2, 7, 12) != null);
        check("混排：单行式 14-15 周", find(chem, 4, 2, 14, 15) != null);
        if (!chem.isEmpty()) {
            check("混排：单行式教室 DS806", "DS806".equals(chem.get(0).room));
            check("混排：单行式教师 陈明洁", "陈明洁".equals(chem.get(0).teacher));
            check("混排：单行式跨度 4 节", chem.get(0).step == 4);
        }
    }

    private static int priorityOf(String name) {
        for (TimetableParserRegistry.Parser plugin : TimetableParserRegistry.plugins()) {
            if (plugin.name().equals(name)) return plugin.priority();
        }
        return 999;
    }

    private static List<int[]> weekSegments(String text) {
        List<int[]> out = new ArrayList<>();
        // 与解析器同口径：先剥离 (N-M节)，否则 (1-2节) 里的 1-2 会被误当周次。
        String stripped = ScauGridParser.stripNodeRange(text);
        com.checktodo.app.domain.WeekExpression.Result result =
                com.checktodo.app.domain.WeekExpression.parse(stripped, 20);
        for (com.checktodo.app.domain.WeekExpression.Segment segment : result.segments) {
            out.add(new int[]{segment.start, segment.end, segment.type});
        }
        return out;
    }

    private static List<CourseDetail> detailsOf(ScauGridParser.Outcome outcome, String courseName) {
        String baseId = null;
        for (CourseBase base : outcome.result.bases) {
            if (base.name.equals(courseName)) baseId = base.id;
        }
        List<CourseDetail> out = new ArrayList<>();
        if (baseId == null) return out;
        for (CourseDetail detail : outcome.result.details) {
            if (detail.baseId.equals(baseId)) out.add(detail);
        }
        return out;
    }

    private static CourseDetail find(List<CourseDetail> details, int day, int startNode,
                                     int startWeek, int endWeek) {
        for (CourseDetail detail : details) {
            if (detail.day == day && detail.startNode == startNode
                    && detail.startWeek == startWeek && detail.endWeek == endWeek) {
                return detail;
            }
        }
        return null;
    }

    private static TimeNode findNode(List<TimeNode> nodes, int node) {
        for (TimeNode item : nodes) {
            if (item.node == node) return item;
        }
        return null;
    }

    private static String readFixture() {
        return readFixtureFile(FIXTURE);
    }

    private static String readFixtureFile(String path) {
        InputStream stream = null;
        try {
            stream = new FileInputStream(new File(path));
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) >= 0) buffer.write(chunk, 0, count);
            return HtmlTimetableParser.decode(buffer.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("读取夹具失败：" + path, exception);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
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
}
