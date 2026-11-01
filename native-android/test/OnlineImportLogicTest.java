package test;

import com.checktodo.app.domain.CourseBase;
import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.domain.HtmlTimetableParser;
import com.checktodo.app.domain.OnlineDivParser;
import com.checktodo.app.domain.OnlineImportLogic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 教务系统 WebView 导入的纯 Java 用例：URL 规范化、JSON 解析、历史记录与合并去重。 */
public final class OnlineImportLogicTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testNormalizeUrl();
        testHistory();
        testExtractionScript();
        testParseCapture();
        testResourceUrls();
        testResourceRefreshState();
        testFetchBudget();
        testFrameUrlCapture();
        testEvidencePack();
        testThreeChannelCapture();
        testOnlineDivParser();
        testChannelMerge();
        testMergeDeduplicates();
        testMergeDistinctCourses();
        testFamilyGuide();

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void testNormalizeUrl() {
        check("域名补 http", OnlineImportLogic.normalizeUrl("jw.school.edu.cn/x")
                .equals("http://jw.school.edu.cn/x"));
        check("https 原样保留", OnlineImportLogic.normalizeUrl(" https://jw.school.edu.cn ")
                .equals("https://jw.school.edu.cn"));
        check("协议相对地址补 http", OnlineImportLogic.normalizeUrl("//jw.school.edu.cn")
                .equals("http://jw.school.edu.cn"));
        check("javascript 拒绝", OnlineImportLogic.normalizeUrl("javascript:alert(1)").isEmpty());
        check("ftp 拒绝", OnlineImportLogic.normalizeUrl("ftp://jw.school.edu.cn").isEmpty());
        check("file 拒绝", OnlineImportLogic.normalizeUrl("file:///sdcard/a.html").isEmpty());
        check("空地址拒绝", OnlineImportLogic.normalizeUrl("   ").isEmpty());
    }

    private static void testHistory() {
        List<String> old = Arrays.asList("https://a.edu", "http://b.edu",
                "https://c.edu", "http://d.edu", "https://e.edu");
        List<String> next = OnlineImportLogic.history(old, "http://b.edu");
        check("重复历史置顶", next.size() == 5 && next.get(0).equals("http://b.edu"));
        check("原重复项保留", next.contains("https://e.edu"));

        List<String> capped = OnlineImportLogic.history(old, "https://f.edu");
        check("历史最多 5 条", capped.size() == 5);
        check("最新历史在最前", capped.get(0).equals("https://f.edu"));

        List<String> rejected = OnlineImportLogic.history(old, "javascript:x");
        check("非法历史不影响列表", rejected.equals(old));
    }

    private static void testExtractionScript() {
        String script = OnlineImportLogic.extractionScript();
        check("脚本查找 table", script.contains("querySelectorAll('table')"));
        check("脚本序列化为 JSON", script.contains("JSON.stringify"));
        check("脚本提取 outerHTML", script.contains("outerHTML"));
        check("脚本过滤行列", script.contains("rows.length<5") && script.contains("cols<5"));
        check("脚本过滤隐藏表格", script.contains("visibility==='hidden'"));
        check("脚本遍历 iframe 与 frame", script.contains("querySelectorAll('iframe,frame')"));
        check("脚本读取跨文档 frame", script.contains("contentDocument"));
        check("脚本递归深层 iframe", script.contains("scan(inner[f].contentDocument,d+1)"));
        check("脚本限制 frame 递归深度", script.contains("frames<12"));
        check("脚本统计扫描文档数", script.contains("frames++"));
        check("脚本统计表格数", script.contains("tables+=nodes.length"));
        check("脚本统计命中数", script.contains("matched++"));
        check("脚本返回诊断统计", script.contains("stats:{docs:frames"));
        check("脚本收集 XHR 与 fetch", script.contains("getEntriesByType('resource')")
                && script.contains("xmlhttprequest") && script.contains("fetch"));
        check("脚本收集 div 格子", script.contains("cells:cells"));
        check("脚本回传候选资源", script.contains("resources:Object.keys(resources)"));
        check("脚本统计 div 与资源", script.contains("divs:cells.length")
                && script.contains("resources:Object.keys(resources).length"));
        // 2026-10 真机反馈「提取课表」卡死：四项减负护栏必须存在且数值不被回退。
        check("脚本限制几何遍历元素上限 3000", script.contains("g<els.length&&g<3000"));
        check("脚本预筛零尺寸隐藏元素", script.contains("clientWidth<1||el.clientHeight<1"));
        check("脚本限制几何课程块上限 120", script.contains("geoCourses.length>=120"));
        check("脚本限制回传总字节上限 1.5MB", script.contains("payload+html.length<1500000"));
    }

    private static void testParseCapture() {
        OnlineImportLogic.Capture direct = OnlineImportLogic.parseCapture(
                "{\"url\":\"https://jw.edu/schedule\",\"title\":\"课表\",\"tables\":[\"<table>A</table>\",\"<table>B</table>\"]}");
        check("对象 JSON 解析 URL", direct != null && direct.url.equals("https://jw.edu/schedule"));
        check("对象 JSON 解析表格", direct != null && direct.tables.size() == 2);

        OnlineImportLogic.Capture quoted = OnlineImportLogic.parseCapture(
                "\"{\\\"url\\\":\\\"http://jw.edu\\\",\\\"title\\\":\\\"\\\\u8bfe\\\\u8868\\\",\\\"tables\\\":[\\\"<table>C</table>\\\"]}\"");
        check("WebView 字符串 JSON 解析", quoted != null && quoted.url.equals("http://jw.edu"));
        check("JSON unicode 转义解码", quoted != null && quoted.title.equals("课表"));
        check("WebView 字符串 JSON 表格", quoted != null && quoted.tables.get(0).equals("<table>C</table>"));

        check("null 输入返回空捕获", OnlineImportLogic.parseCapture(null) == null);
        check("空输入返回空捕获", OnlineImportLogic.parseCapture(" ") == null);
        check("JSON null 返回空捕获", OnlineImportLogic.parseCapture("null") == null);
        boolean threw = false;
        try {
            OnlineImportLogic.parseCapture("not-json");
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check("非法 JSON 抛出可读异常", threw);

        OnlineImportLogic.Capture stats = OnlineImportLogic.parseCapture(
                "{\"url\":\"http://j\",\"title\":\"t\",\"tables\":[\"<table>A</table>\"],"
                        + "\"stats\":{\"docs\":3,\"tables\":7,\"matched\":1,\"depth\":2}}");
        check("诊断统计装配文档数", stats != null && stats.scannedDocs == 3);
        check("诊断统计装配表格数", stats != null && stats.tablesSeen == 7);
        check("诊断统计装配命中数", stats != null && stats.matchedTables == 1);
        check("诊断统计装配 iframe 深度", stats != null && stats.frameDepth == 2);

        OnlineImportLogic.Capture legacy = OnlineImportLogic.parseCapture(
                "{\"url\":\"http://j\",\"title\":\"t\",\"tables\":[\"<table>A</table>\"]}");
        check("无统计字段时回填基础值", legacy != null && legacy.scannedDocs == 1
                && legacy.tablesSeen == 1 && legacy.matchedTables == 1);
    }

    private static void testResourceUrls() {
        List<String> raw = Arrays.asList(
                "https://xskb.school.edu.cn/api/list",
                "http://jw.school.edu.cn/kbcx",
                "https://static.other.edu.cn/app.js",
                "javascript:alert(1)",
                "ftp://files.school.edu.cn/data",
                "http://jw.school.edu.cn/kbcx",
                "https://portal.school.edu.cn/index");
        List<String> urls = OnlineImportLogic.resourceUrls(raw,
                "http://jw.school.edu.cn/schedule");
        check("同高校学术域被接受", urls.contains("https://xskb.school.edu.cn/api/list"));
        check("非 http 资源被拒绝", !urls.toString().contains("javascript:"));
        check("不同学术域被拒绝", !urls.contains("https://static.other.edu.cn/app.js"));
        check("资源 URL 去重", count(urls, "http://jw.school.edu.cn/kbcx") == 1);
        check("课表关键词 URL 排最前", !urls.isEmpty() && urls.get(0).contains("kbcx"));

        List<String> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add("http://jw.school.edu.cn/resource" + i);
        }
        check("资源 URL 最多 30 条", OnlineImportLogic.resourceUrls(many,
                "http://jw.school.edu.cn/schedule").size() == 30);
        check("空页面不保留资源", OnlineImportLogic.resourceUrls(
                Arrays.asList("http://jw.school.edu.cn/a"), "").isEmpty());
    }

    private static void testResourceRefreshState() {
        OnlineImportLogic.ResourceRefreshState state = new OnlineImportLogic.ResourceRefreshState();
        List<String> first = state.refresh(Arrays.asList("http://a.edu/x", "http://a.edu/x"), false, 15);
        check("refresh only returns first new candidate", first.equals(Arrays.asList("http://a.edu/x")));
        check("refresh state records candidate", state.attempted().contains("http://a.edu/x"));

        List<String> second = state.refresh(Arrays.asList("http://a.edu/x", "http://a.edu/y"), false, 15);
        check("refresh skips previously fetched URL", second.equals(Arrays.asList("http://a.edu/y")));
        check("refresh preserves attempted history", state.attempted().size() == 2);

        List<String> manual = state.refresh(Arrays.asList("http://a.edu/x", "http://a.edu/y"), true, 15);
        check("manual refresh refetches known URLs", manual.equals(Arrays.asList("http://a.edu/x", "http://a.edu/y")));

        List<String> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) many.add("http://a.edu/resource" + i);
        List<String> limited = new OnlineImportLogic.ResourceRefreshState().refresh(many, false, 15);
        check("refresh limits candidates to 15", limited.size() == 15);

        OnlineImportLogic.ResourceRefreshState resetState = new OnlineImportLogic.ResourceRefreshState();
        resetState.refresh(Arrays.asList("http://a.edu/x"), false, 15);
        resetState.reset();
        check("reset clears attempted URLs",
                resetState.refresh(Arrays.asList("http://a.edu/x"), false, 15).equals(Arrays.asList("http://a.edu/x")));
    }

    /**
     * 单轮抓取的耗时上限：15 个不可达候选 × 20s 超时 ≈ 300s 是「提取中…」不退的真实来源。
     * FetchBudget 用截止时间把最坏耗时钉死，并让单次超时夹到剩余额度内。
     */
    private static void testFetchBudget() {
        long start = 1_000_000L;
        OnlineImportLogic.FetchBudget budget = new OnlineImportLogic.FetchBudget(start, 60_000L);

        check("budget allows the first request", budget.canStart(start));
        check("budget remaining equals total at start", budget.remaining(start) == 60_000L);
        check("budget spent starts at zero", budget.spent() == 0);

        budget.record();
        check("budget records spent requests", budget.spent() == 1);

        long midway = start + 30_000L;
        check("budget still open at half time", budget.canStart(midway));
        check("budget remaining shrinks with time", budget.remaining(midway) == 30_000L);

        long exactDeadline = start + 60_000L;
        check("budget is exhausted exactly at deadline", !budget.canStart(exactDeadline));
        check("budget remaining is zero past deadline", budget.remaining(exactDeadline + 5_000L) == 0L);
        check("budget remaining never negative", budget.remaining(exactDeadline + 900_000L) == 0L);

        OnlineImportLogic.FetchBudget zero = new OnlineImportLogic.FetchBudget(start, 0L);
        check("zero budget blocks every request", !zero.canStart(start));

        OnlineImportLogic.FetchBudget negative = new OnlineImportLogic.FetchBudget(start, -5_000L);
        check("negative budget is treated as zero", !negative.canStart(start));

        // 15 个候选全部不可达：预算耗尽后不再发起，最坏耗时被钉死在预算内。
        OnlineImportLogic.FetchBudget loop = new OnlineImportLogic.FetchBudget(start, 60_000L);
        int started = 0;
        for (int i = 0; i < 15; i++) {
            long now = start + i * 20_000L; // 每个候选都吃满 20s 超时
            if (!loop.canStart(now)) break;
            loop.record();
            started++;
        }
        check("budget caps worst-case fetches below the 15 candidate limit", started == 3);
        check("budget spent matches started fetches", loop.spent() == started);
        check("worst-case total time stays inside the budget", started * 20_000L <= 60_000L);
    }

    private static void testFrameUrlCapture() {
        OnlineImportLogic.Capture capture = OnlineImportLogic.parseCapture(
                "{\"url\":\"http://jw.school.edu.cn/homes.html\",\"frameUrls\":[\"http://jw.school.edu.cn/a\",\"http://jw.school.edu.cn/b\"]}");
        check("frame URL list is parsed", capture != null && capture.frameUrls.equals(
                Arrays.asList("http://jw.school.edu.cn/a", "http://jw.school.edu.cn/b")));
    }

    private static void testEvidencePack() {
        check("html evidence escapes tags", OnlineImportLogic.htmlEvidence("<table>", 300)
                .equals("&lt;table&gt;"));
        check("html evidence collapses whitespace", OnlineImportLogic.htmlEvidence("<a>  \n <b>  ", 300)
                .equals("&lt;a&gt; &lt;b&gt;"));
        check("html evidence truncates source", OnlineImportLogic.htmlEvidence("abcdef", 3)
                .equals("abc"));
        check("response preview escapes JSON", OnlineImportLogic.responsePreview("{\"a\":\"<b>\"}", 300)
                .contains("&lt;b&gt;"));

        String resource = OnlineImportLogic.resourceEvidence("http://a.edu/kbcx", 200, "application/json",
                "{\"list\":[]}", "KingosoftParser=2; HtmlTimetableParser=0");
        check("resource evidence includes URL", resource.contains("URL: http://a.edu/kbcx"));
        check("resource evidence includes status", resource.contains("HTTP: 200"));
        check("resource evidence includes content type", resource.contains("Content-Type: application/json"));
        check("resource evidence includes preview", resource.contains("Preview: {\"list\":[]}"));
        check("resource evidence includes parser summary", resource.contains("Parsers: KingosoftParser=2"));

        String table = OnlineImportLogic.tableEvidence(2, "0 details after heuristic parse", "<table></table>");
        check("table evidence includes index", table.contains("Table #2"));
        check("table evidence includes reason", table.contains("Reason: 0 details after heuristic parse"));
        check("table evidence includes escaped HTML", table.contains("HTML: &lt;table&gt;&lt;/table&gt;"));
    }

    private static void testThreeChannelCapture() {
        OnlineImportLogic.Capture capture = OnlineImportLogic.parseCapture(
                "{\"url\":\"http://jw.school.edu.cn/kb\",\"title\":\"我的课表\","
                        + "\"tables\":[\"<table><tr><td>高等数学</td></tr></table>\"],"
                        + "\"cells\":[{\"t\":\"高等数学 张三 A101 1-16周(单)\",\"i\":\"3_2\","
                        + "\"c\":\"kbcontent\",\"r\":3,\"n\":2}],"
                        + "\"resources\":[\"http://xskb.school.edu.cn/list\"],"
                        + "\"stats\":{\"docs\":4,\"tables\":3,\"matched\":1,\"divs\":2,"
                        + "\"resources\":5,\"depth\":2}}");
        check("三通道 URL 解析", capture != null && capture.url.equals("http://jw.school.edu.cn/kb"));
        check("三通道表格保留", capture != null && capture.tables.size() == 1);
        check("三通道 div 格子解析", capture != null && capture.cells.size() == 1
                && capture.cells.get(0).id.equals("3_2"));
        check("三通道资源解析", capture != null && capture.resources.size() == 1
                && capture.resources.get(0).equals("http://xskb.school.edu.cn/list"));
        check("三通道统计装配", capture != null && capture.scannedDocs == 4
                && capture.tablesSeen == 3 && capture.matchedTables == 1
                && capture.divCells == 2 && capture.resourceCount == 5
                && capture.frameDepth == 2);
    }

    private static void testOnlineDivParser() {
        OnlineImportLogic.GridCell explicit = new OnlineImportLogic.GridCell(
                "高等数学\n张三\nA101\n1-16周(单)\n[1-3]节", "3_2", "kbcontent", 3, 2);
        HtmlTimetableParser.Result explicitResult =
                OnlineDivParser.parse(Arrays.asList(explicit), 20, 12);
        check("div 显式格子解析课程", explicitResult.bases.size() == 1
                && explicitResult.details.size() == 1);
        CourseDetail detail = explicitResult.details.get(0);
        check("div id 映射星期", detail.day == 2);
        check("div 方括号覆盖节次", detail.startNode == 1 && detail.step == 3);
        check("div 单周类型", detail.type == CourseDetail.TYPE_ODD);
        check("div 映射教师与教室", detail.teacher.equals("张三") && detail.room.equals("A101"));
        check("div 映射周次", detail.startWeek == 1 && detail.endWeek == 16);

        OnlineImportLogic.GridCell sibling = new OnlineImportLogic.GridCell(
                "大学英语 李四 B202 1-16周", "", "course", 0, 1);
        HtmlTimetableParser.Result siblingResult =
                OnlineDivParser.parse(Arrays.asList(sibling), 20, 12);
        check("div 兄弟索引映射星期", siblingResult.details.size() == 1
                && siblingResult.details.get(0).day == 2);
        check("div 兄弟索引起始节次", siblingResult.details.get(0).startNode == 1);

        OnlineImportLogic.GridCell invalid = new OnlineImportLogic.GridCell(
                "无效课程 张三 A101 17-20周", "1_1", "kbcontent", 1, 1);
        check("div 超出总周数被拒绝",
                OnlineDivParser.parse(Arrays.asList(invalid), 16, 12).details.isEmpty());
        OnlineImportLogic.GridCell noWeek = new OnlineImportLogic.GridCell(
                "无周次课程 张三 A101", "1_1", "kbcontent", 1, 1);
        HtmlTimetableParser.Result noWeekResult = OnlineDivParser.parse(Arrays.asList(noWeek), 16, 12);
        check("div 无周次有提醒", noWeekResult.details.isEmpty()
                && !noWeekResult.warnings.isEmpty());
        check("div 空输入返回空结果", OnlineDivParser.parse(null, 20, 12).details.isEmpty());
    }

    private static void testChannelMerge() {
        HtmlTimetableParser.Result table = new HtmlTimetableParser.Result();
        table.bases.add(new CourseBase("t", "数据结构", 0, "", 0));
        table.details.add(new CourseDetail("td", "t", 3, 1, 2, 1, 16,
                CourseDetail.TYPE_ALL, "A101", "张三", 0));
        HtmlTimetableParser.Result div = new HtmlTimetableParser.Result();
        div.bases.add(new CourseBase("d", "数据结构", 0, "", 0));
        div.details.add(new CourseDetail("dd", "d", 3, 1, 2, 1, 16,
                CourseDetail.TYPE_ALL, "A101", "张三", 0));
        OnlineImportLogic.Merged merged = OnlineImportLogic.merge(null, null,
                table.bases, table.details);
        merged = OnlineImportLogic.merge(merged.bases, merged.details, div.bases, div.details);
        check("表格与 div 通道去重", merged.courseCount() == 1 && merged.detailCount() == 1
                && merged.duplicates == 1);

        HtmlTimetableParser.Result xhr = new HtmlTimetableParser.Result();
        xhr.bases.add(new CourseBase("x", "数据结构", 0, "", 0));
        xhr.details.add(new CourseDetail("xd", "x", 5, 1, 2, 1, 16,
                CourseDetail.TYPE_ALL, "B202", "李四", 0));
        merged = OnlineImportLogic.merge(merged.bases, merged.details, xhr.bases, xhr.details);
        check("不同星期不合并", merged.detailCount() == 2);
    }

    private static void testFamilyGuide() {
        check("kingo_new 指引含主控与检索",
                OnlineImportLogic.guideFor("", "kingo_new").contains("主控")
                        && OnlineImportLogic.guideFor("", "kingo_new").contains("检索"));
        check("kingosoft 指引同族", OnlineImportLogic.guideFor("kingosoft", "")
                .equals(OnlineImportLogic.guideFor("", "kingo_new")));
        check("zf 指引提示首页课表不可导入",
                OnlineImportLogic.guideFor("zf", "").contains("首页课表不可导入"));
        check("jz 指引进入学生个人课表",
                OnlineImportLogic.guideFor("jz", "").contains("学生个人课表"));
        check("urp_new 指引本学期课表",
                OnlineImportLogic.guideFor("", "urp_new").contains("本学期课表"));
        check("urp 指引同族", OnlineImportLogic.guideFor("urp", "")
                .equals(OnlineImportLogic.guideFor("", "urp_new")));
        check("element_ui 用通用提示",
                OnlineImportLogic.guideFor("element_ui", "").equals("打开课表页面后点导入"));
        check("未匹配家族用通用提示",
                OnlineImportLogic.guideFor("qz", "").equals("打开课表页面后点导入"));
        check("空学校用通用提示",
                OnlineImportLogic.guideFor(null, null).equals("打开课表页面后点导入"));
    }

    private static void testMergeDeduplicates() {
        CourseBase math = new CourseBase("1", "高等数学", 0, "", 0);
        CourseBase mathAgain = new CourseBase("2", "高等数学", 0, "note", 4);
        CourseDetail first = new CourseDetail("d1", "1", 1, 1, 2, 1, 16,
                CourseDetail.TYPE_ALL, "", "张三", 0);
        CourseDetail duplicate = new CourseDetail("d2", "2", 1, 1, 2, 1, 16,
                CourseDetail.TYPE_ALL, "A101", "张三", 0);
        OnlineImportLogic.Merged merged = OnlineImportLogic.merge(null, null,
                Arrays.asList(math, mathAgain), Arrays.asList(first, duplicate));

        check("同名课程只保留一门", merged.courseCount() == 1);
        check("同排课只保留一条", merged.detailCount() == 1);
        check("重复计数", merged.duplicates == 1);
        check("重复项补齐教室", merged.details.get(0).room.equals("A101"));
        check("重复项保留教师", merged.details.get(0).teacher.equals("张三"));

        CourseDetail differentWeek = new CourseDetail("d3", "2", 1, 1, 2, 17, 20,
                CourseDetail.TYPE_ALL, "B202", "李四", 0);
        OnlineImportLogic.Merged mergedAgain = OnlineImportLogic.merge(merged.bases, merged.details,
                Arrays.asList(mathAgain), Arrays.asList(differentWeek));
        check("不同周次不视为重复", mergedAgain.detailCount() == 2);
        check("已有课程继续累积", mergedAgain.courseCount() == 1);
    }

    private static void testMergeDistinctCourses() {
        HtmlTimetableParser.Result result = new HtmlTimetableParser.Result();
        result.bases.add(new CourseBase("a", "大学英语", 0, "", 0));
        result.bases.add(new CourseBase("b", "数据结构", 0, "", 0));
        result.details.add(new CourseDetail("a1", "a", 2, 3, 2, 1, 16,
                CourseDetail.TYPE_EVEN, "B202", "李四", 0));
        result.details.add(new CourseDetail("b1", "b", 4, 1, 1, 1, 8,
                CourseDetail.TYPE_ODD, "C303", "王五", 0));
        OnlineImportLogic.Merged merged = OnlineImportLogic.merge(result);

        check("多课程合并", merged.courseCount() == 2);
        check("多排课合并", merged.detailCount() == 2);
        List<String> names = new ArrayList<>();
        for (CourseBase base : merged.bases) names.add(base.name);
        check("课程顺序保持", names.equals(Arrays.asList("大学英语", "数据结构")));
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

    private static int count(List<String> values, String target) {
        int total = 0;
        for (String value : values) {
            if (value.equals(target)) total++;
        }
        return total;
    }
}
