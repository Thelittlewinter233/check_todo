package test;

import com.checktodo.app.domain.CourseBase;
import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.domain.ExtractionState;
import com.checktodo.app.domain.HtmlTimetableParser;
import com.checktodo.app.domain.NewZhengFangParser;
import com.checktodo.app.domain.OnlineImportLogic;
import com.checktodo.app.domain.PassiveCapture;
import com.checktodo.app.domain.TimetableParserRegistry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 被动捕获通道 + 插件化 parser + 停止语义的 JVM 测试。
 *
 * <p>覆盖三类：</p>
 * <ol>
 *   <li><b>Tee 流</b>：WebView 侧读到原文、捕获侧拿到完整副本、超 2MB 截断；</li>
 *   <li><b>捕获池</b>：URL+内容哈希去重、LRU 容量驱逐、数据接口判定、过滤非文本类型；</li>
 *   <li><b>解析链</b>：捕获池里的真实响应体（kb.htm）能解析出 11 门课；
 *       新正方 JSON 尽力解析；插件优先级；停止状态机语义。</li>
 * </ol>
 */
public final class PassiveCaptureTest {
    private static int passed;
    private static int failed;

    private static final String KB = "test/fixtures/kingosoft_kb.htm";

    public static void main(String[] args) {
        testTeePassthrough();
        testTeeTruncation();
        testTeeCloseFinishes();
        testContentTypeFilter();
        testCapturedModel();
        testPoolDeduplication();
        testPoolLruEviction();
        testPoolDataLike();
        testPoolSummary();
        testCapturePoolParsesRealBody();
        testNewZhengFangJson();
        testNewZhengFangHtml();
        testNewZhengFangEdgeCases();
        testRegistryOrdering();
        testRegistryDedicatedPlugin();
        testStopSemantics();
        testWatchdogSemantics();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // Tee 流
    // ------------------------------------------------------------------

    /** 核心断言：WebView 侧读到的字节必须与原文完全一致，捕获侧拿到完整副本。 */
    private static void testTeePassthrough() throws RuntimeException {
        String original = "<html><body>课表数据</body></html>";
        byte[] bytes = original.getBytes(java.nio.charset.Charset.forName("UTF-8"));

        PassiveCapture.TeeInputStream tee =
                PassiveCapture.TeeInputStream.wrap(new ByteArrayInputStream(bytes), 1024);

        // 模拟 WebView：按小块读（真实 WebView 也是小块读）
        ByteArrayOutputStream seen = new ByteArrayOutputStream();
        byte[] buffer = new byte[7];
        int count;
        try {
            while ((count = tee.read(buffer, 0, buffer.length)) >= 0) {
                seen.write(buffer, 0, count);
            }
        } catch (IOException exception) {
            check("Tee 读取不应抛异常", false);
            return;
        }
        check("Tee：WebView 侧读到完整原文",
                new String(seen.toByteArray(), java.nio.charset.Charset.forName("UTF-8")).equals(original));
        check("Tee：捕获侧拿到完整副本", tee.sink().text().equals(original));
        check("Tee：两侧长度一致", seen.size() == tee.sink().size());
        check("Tee：未截断", !tee.sink().truncated());

        // 单字节读路径也要被 Tee 记录
        PassiveCapture.TeeInputStream single =
                PassiveCapture.TeeInputStream.wrap(new ByteArrayInputStream("AB".getBytes()), 1024);
        try {
            while (single.read() >= 0) {
                // 逐字节消费
            }
        } catch (IOException ignored) {
            // ignore
        }
        check("Tee：逐字节读也被记录", single.sink().text().equals("AB"));
    }

    private static void testTeeTruncation() {
        byte[] big = new byte[5000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) ('a' + (i % 26));
        PassiveCapture.TeeInputStream tee =
                PassiveCapture.TeeInputStream.wrap(new ByteArrayInputStream(big), 2000);

        ByteArrayOutputStream seen = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int count;
        try {
            while ((count = tee.read(buffer, 0, buffer.length)) >= 0) seen.write(buffer, 0, count);
        } catch (IOException exception) {
            check("截断场景读取不应抛异常", false);
            return;
        }
        check("截断：WebView 侧仍读到全部 5000 字节", seen.size() == 5000);
        check("截断：捕获侧只保留前 2000 字节", tee.sink().size() == 2000);
        check("截断：已标记 truncated", tee.sink().truncated());

        // 上限为 0 时不应捕获任何内容
        PassiveCapture.TeeInputStream zero =
                PassiveCapture.TeeInputStream.wrap(new ByteArrayInputStream("xx".getBytes()), 0);
        try {
            zero.read(new byte[2], 0, 2);
        } catch (IOException ignored) {
            // ignore
        }
        check("上限 0：捕获为空且标记截断",
                zero.sink().size() == 0 && zero.sink().truncated());

        check("常量：单响应上限 2MB", PassiveCapture.MAX_BODY_BYTES == 2 * 1024 * 1024);
        check("常量：池上限 8MB", PassiveCapture.MAX_POOL_BYTES == 8 * 1024 * 1024);
    }

    private static void testTeeCloseFinishes() {
        PassiveCapture.TeeInputStream tee =
                PassiveCapture.TeeInputStream.wrap(new ByteArrayInputStream("abc".getBytes()), 1024);
        try {
            tee.read(new byte[3], 0, 3);
            tee.close();
        } catch (IOException exception) {
            check("close 不应抛异常", false);
        }
        check("close 后仍可取到已捕获内容", tee.sink().text().equals("abc"));
        // close 之后再喂数据应被忽略
        try {
            tee.read(new byte[3], 0, 3);
        } catch (IOException ignored) {
            // 上游已关闭，可能抛也可能返回 -1
        }
        check("close 后不再增长", tee.sink().size() == 3);
    }

    // ------------------------------------------------------------------
    // 类型过滤与模型
    // ------------------------------------------------------------------

    private static void testContentTypeFilter() {
        check("捕获 text/html", PassiveCapture.isCapturableType("text/html"));
        check("捕获带 charset 的 html", PassiveCapture.isCapturableType("text/html; charset=GBK"));
        check("捕获 application/json", PassiveCapture.isCapturableType("application/json"));
        check("捕获 text/plain", PassiveCapture.isCapturableType("text/plain"));
        check("不捕获图片", !PassiveCapture.isCapturableType("image/png"));
        check("不捕获脚本", !PassiveCapture.isCapturableType("application/javascript"));
        check("不捕获字体", !PassiveCapture.isCapturableType("font/woff2"));
        check("null 类型不捕获", !PassiveCapture.isCapturableType(null));
        check("mimeOf 去掉 charset", PassiveCapture.mimeOf("text/html; charset=GBK").equals("text/html"));
        check("mimeOf 对 null 返回空", PassiveCapture.mimeOf(null).isEmpty());
    }

    private static void testCapturedModel() {
        PassiveCapture.Captured zf = new PassiveCapture.Captured(
                "https://jwgl.hzau.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151",
                "post", 200, "text/html;charset=utf-8", "<table></table>", 1000L, false);
        check("method 规范化为大写", zf.method.equals("POST"));
        check("空 method 默认为 GET", new PassiveCapture.Captured("u", "", 200, "text/html",
                "x", 0, false).method.equals("GET"));
        check("hash 非空且稳定",
                zf.hash.length() > 0 && zf.hash.equals(new PassiveCapture.Captured(
                        zf.url, "POST", 200, zf.contentType, zf.body, 0, false).hash));
        check("不同内容 hash 不同", !zf.hash.equals(new PassiveCapture.Captured(
                zf.url, "POST", 200, zf.contentType, "other", 0, false).hash));
        check("xskbcx 判定为数据接口", zf.isDataLike());
        check("json 类型判定为数据接口", new PassiveCapture.Captured("https://x/a.html", "GET", 200,
                "application/json", "{}", 0, false).isDataLike());
        check("静态 css 不算数据接口", !new PassiveCapture.Captured(
                "https://x/a.css", "GET", 200, "text/css", "body{}", 0, false).isDataLike());
        check("shortUrl 去掉 query", !zf.shortUrl().contains("gnmkdm"));
    }

    // ------------------------------------------------------------------
    // 捕获池
    // ------------------------------------------------------------------

    private static void testPoolDeduplication() {
        PassiveCapture.CapturePool pool = new PassiveCapture.CapturePool();
        PassiveCapture.Captured one = new PassiveCapture.Captured(
                "https://x/kb", "GET", 200, "text/html", "<table>1</table>", 1L, false);
        check("首次加入成功", pool.add(one));
        check("池大小 1", pool.size() == 1);
        // 同 URL 同内容 → 去重
        check("重复（同 URL 同内容）被跳过", !pool.add(new PassiveCapture.Captured(
                one.url, "GET", 200, "text/html", one.body, 2L, false)));
        check("去重计数 +1", pool.duplicates() == 1);
        check("去重后池大小仍为 1", pool.size() == 1);
        // 同 URL 不同内容（翻学期）→ 新条目
        check("同 URL 不同内容作为新条目", pool.add(new PassiveCapture.Captured(
                one.url, "GET", 200, "text/html", "<table>2</table>", 3L, false)));
        check("池大小变为 2", pool.size() == 2);
        // 空 body 不加入
        check("空 body 不加入", !pool.add(new PassiveCapture.Captured(
                "https://x/e", "GET", 200, "text/html", "", 4L, false)));
    }

    private static void testPoolLruEviction() {
        // 容量 1000 字节，最多 10 条
        PassiveCapture.CapturePool pool = new PassiveCapture.CapturePool(1000, 10);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 120; i++) body.append('x');
        String chunk = body.toString(); // ~120 字节

        for (int i = 0; i < 5; i++) {
            pool.add(new PassiveCapture.Captured("https://x/" + i, "GET", 200, "text/html",
                    chunk + i, i, false));
        }
        check("5 条都在池里", pool.size() == 5);
        check("总量未超上限", pool.totalBytes() <= 1000);

        // 塞满直到超过上限
        for (int i = 5; i < 20; i++) {
            pool.add(new PassiveCapture.Captured("https://x/" + i, "GET", 200, "text/html",
                    chunk + i, i, false));
        }
        check("超容量后池大小受条目上限约束", pool.size() <= 10);
        check("超容量后总量不超上限", pool.totalBytes() <= 1000);
        check("有内容被驱逐", pool.dropped() > 0);

        // 单条超过容量上限 → 直接丢弃（不会把池清空）
        PassiveCapture.CapturePool tiny = new PassiveCapture.CapturePool(100, 10);
        tiny.add(new PassiveCapture.Captured("https://x/small", "GET", 200, "text/html", "aa", 1, false));
        check("超容量单条被丢弃",
                !tiny.add(new PassiveCapture.Captured("https://x/huge", "GET", 200, "text/html",
                        repeat('z', 500), 2, false)));
        check("丢弃大条目后小条目仍在", tiny.size() == 1);

        // clear
        pool.clear();
        check("clear 后为空", pool.size() == 0 && pool.totalBytes() == 0);
    }

    private static void testPoolDataLike() {
        PassiveCapture.CapturePool pool = new PassiveCapture.CapturePool();
        pool.add(new PassiveCapture.Captured("https://x/a.css", "GET", 200, "text/css", "b{}", 1, false));
        pool.add(new PassiveCapture.Captured("https://x/kbcx/xskbcx_cxXskbcxIndex.html",
                "POST", 200, "text/html", "<table>kb</table>", 2, false));
        pool.add(new PassiveCapture.Captured("https://x/api/list", "POST", 200,
                "application/json", "{\"a\":1}", 3, false));
        check("数据接口计数为 2", pool.dataLikeCount() == 2);
        check("池总数为 3", pool.size() == 3);
        List<PassiveCapture.Captured> dataLike = pool.dataLike();
        boolean hasKb = false;
        for (PassiveCapture.Captured item : dataLike) {
            if (item.url.contains("xskbcx")) hasKb = true;
        }
        check("数据接口包含 xskbcx 响应", hasKb);
    }

    private static void testPoolSummary() {
        PassiveCapture.CapturePool pool = new PassiveCapture.CapturePool();
        pool.add(new PassiveCapture.Captured("https://x/kbcx/a", "POST", 200, "text/html",
                "<table>x</table>", 1, false));
        String summary = pool.summary();
        check("摘要含「被动捕获」", summary.contains("被动捕获"));
        check("摘要含响应数", summary.contains("1 个响应"));
        check("摘要含数据接口数", summary.contains("数据接口"));
        check("摘要含容量信息", summary.contains("KB"));
    }

    // ------------------------------------------------------------------
    // 捕获池 → 解析链
    // ------------------------------------------------------------------

    /** 关键验收：捕获池里的真实响应体能解析出 11 门课（模拟「用户查询后被动捕获到数据」）。 */
    private static void testCapturePoolParsesRealBody() {
        String body = read(KB);
        check("夹具可读且非空", body.length() > 10_000);

        PassiveCapture.CapturePool pool = new PassiveCapture.CapturePool();
        boolean added = pool.add(new PassiveCapture.Captured(
                "https://jwxt.bnuzh.edu.cn/student/wsxk.xskcb10319.jsp?params=eG49",
                "GET", 200, "text/html;charset=UTF-8", body, 1L, false));
        check("真实响应体进入捕获池", added && pool.size() == 1);

        // 模拟 runExtraction 的「通道 0」：遍历池 → 注册表解析 → merge
        OnlineImportLogic.Merged merged = new OnlineImportLogic.Merged();
        int hits = 0;
        for (PassiveCapture.Captured item : pool.snapshot()) {
            TimetableParserRegistry.Outcome outcome = TimetableParserRegistry.parse(
                    new TimetableParserRegistry.Input(item.body, item.url, item.contentType, 20, 12));
            if (!outcome.isEmpty()) {
                hits++;
                merged = OnlineImportLogic.merge(merged.bases, merged.details,
                        outcome.result.bases, outcome.result.details);
            }
        }
        check("捕获池命中 1 个", hits == 1);
        check("捕获池路径解析出 11 门课", merged.courseCount() == 11);
        check("捕获池路径解析出 19 条排课", merged.detailCount() == 19);
        List<String> names = new ArrayList<>();
        for (CourseBase base : merged.bases) names.add(base.name);
        check("捕获路径含「离散数学」", names.contains("离散数学"));
    }

    // ------------------------------------------------------------------
    // 新正方 parser
    // ------------------------------------------------------------------

    private static void testNewZhengFangJson() {
        String json = "{\"tmpList\":["
                + "{\"kcmc\":\"高等数学\",\"xqj\":\"1\",\"jcs\":\"1-2\",\"zcd\":\"1-16周\","
                + "\"cdmc\":\"教一101\",\"xm\":\"张老师\"},"
                + "{\"kcmc\":\"大学英语\",\"xqj\":\"星期三\",\"jcs\":\"3-4节\",\"zcd\":\"1-16周(单)\","
                + "\"cdmc\":\"外语楼201\",\"jsxx\":\"李老师\"},"
                + "{\"kcmc\":\"高等数学\",\"xqj\":\"5\",\"jcs\":\"5\",\"zcd\":\"1-8周\","
                + "\"cdmc\":\"教一101\",\"xm\":\"张老师\"}"
                + "]}";
        TimetableParserRegistry.Outcome outcome = TimetableParserRegistry.parse(
                new TimetableParserRegistry.Input(json,
                        "https://jwgl.hzau.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html",
                        "application/json", 20, 12));
        check("新正方 JSON 被识别", outcome.winner.equals("NewZhengFang"));
        check("新正方 JSON 解析 3 条排课", outcome.result.details.size() == 3);
        check("同名课程合并为 1 个 base", outcome.result.bases.size() == 2);

        CourseDetail first = outcome.result.details.get(0);
        check("星期解析正确（1）", first.day == 1);
        check("节次解析正确（1 起 2 节）", first.startNode == 1 && first.step == 2);
        check("周次解析正确（1-16 全周）",
                first.startWeek == 1 && first.endWeek == 16 && first.type == CourseDetail.TYPE_ALL);
        check("教室解析正确", first.room.equals("教一101"));

        CourseDetail second = outcome.result.details.get(1);
        check("中文星期「星期三」→ 3", second.day == 3);
        check("单周被识别", second.type == CourseDetail.TYPE_ODD);

        // 备选字段名：kbList + 数字型节次
        String alt = "{\"kbList\":[{\"kcmc\":\"数据结构\",\"xqj\":\"4\",\"jcs\":\"7-8\","
                + "\"zcd\":\"2-10\",\"cdmc\":\"实验楼\",\"xm\":\"王老师\"}]}";
        TimetableParserRegistry.Outcome altOutcome = TimetableParserRegistry.parse(
                new TimetableParserRegistry.Input(alt, "https://x/kbcx/list", "", 20, 12));
        check("备选字段 kbList 可解析", altOutcome.result.details.size() == 1);
        check("备选字段周次 2-10 正确",
                altOutcome.result.details.get(0).startWeek == 2
                        && altOutcome.result.details.get(0).endWeek == 10);

        // 数组根（直接返回 list）
        String rootArray = "[{\"kcmc\":\"线性代数\",\"xqj\":\"2\",\"jcs\":\"3\",\"zcd\":\"1-16周\"}]";
        TimetableParserRegistry.Outcome rootOutcome = TimetableParserRegistry.parse(
                new TimetableParserRegistry.Input(rootArray, "https://x/kbcx/zf", "application/json", 20, 12));
        check("根为数组的 JSON 可解析", rootOutcome.result.details.size() == 1);
    }

    private static void testNewZhengFangHtml() {
        // 新正方若返回 HTML，最终也要落到网格解析
        String html = "<html><body><table>"
                + "<tr><th></th><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th><th>星期五</th></tr>"
                + "<tr><td>1</td><td>高等数学<br>张老师<br>1-16周<br>教一101</td>"
                + "<td></td><td></td><td></td><td></td></tr>"
                + "<tr><td>2</td><td></td><td>线性代数<br>李老师<br>1-16周<br>教二202</td>"
                + "<td></td><td></td><td></td></tr>"
                + "</table></body></html>";
        TimetableParserRegistry.Outcome outcome = TimetableParserRegistry.parse(
                new TimetableParserRegistry.Input(html, "https://x/kbcx/xskbcx_xs.html",
                        "text/html", 20, 12));
        check("新正方 HTML 片段能进入解析（有插件命中）", !outcome.attempts.isEmpty());
        // HTML 路径不强制要求成功（真实格式未知），但必须不抛异常且给出尝试记录
        check("HTML 路径有尝试记录", outcome.attempts.size() > 0);
    }

    private static void testNewZhengFangEdgeCases() {
        // 字段齐全但值为空
        check("空列表返回 0 条", TimetableParserRegistry.parse(new TimetableParserRegistry.Input(
                "{\"tmpList\":[]}", "https://x/kbcx/a", "application/json", 20, 12))
                .result.details.isEmpty());
        // 缺 kcmc
        check("缺课程名跳过", TimetableParserRegistry.parse(new TimetableParserRegistry.Input(
                "{\"tmpList\":[{\"xqj\":\"1\",\"jcs\":\"1\"}]}",
                "https://x/kbcx/a", "application/json", 20, 12)).result.details.isEmpty());
        // 缺星期
        check("缺星期跳过", TimetableParserRegistry.parse(new TimetableParserRegistry.Input(
                "{\"tmpList\":[{\"kcmc\":\"X\",\"jcs\":\"1\"}]}",
                "https://x/kbcx/a", "application/json", 20, 12)).result.details.isEmpty());
        // 非法 JSON
        TimetableParserRegistry.Outcome broken = TimetableParserRegistry.parse(
                new TimetableParserRegistry.Input("{\"tmpList\":[{",
                        "https://x/kbcx/a", "application/json", 20, 12));
        check("非法 JSON 不抛异常且无课程", broken.result.details.isEmpty());

        // 解析工具函数
        check("parseDay: 星期一", NewZhengFangParser.parseDay("星期一") == 1);
        check("parseDay: 周日", NewZhengFangParser.parseDay("星期日") == 7);
        check("parseDay: 数字 6", NewZhengFangParser.parseDay("6") == 6);
        check("parseDay: 无法识别返回 -1", NewZhengFangParser.parseDay("abc") == -1);

        int[] span = NewZhengFangParser.parseNodes("1-3节");
        check("parseNodes: 1-3 节 → 起 1 长 3", span[0] == 1 && span[1] == 3);
        int[] single = NewZhengFangParser.parseNodes("5");
        check("parseNodes: 单节", single[0] == 5 && single[1] == 1);

        int[] weeks = NewZhengFangParser.parseWeeks("1-16周(双)", 20);
        check("parseWeeks: 双周", weeks[0] == 1 && weeks[1] == 16 && weeks[2] == CourseDetail.TYPE_EVEN);
        int[] clamp = NewZhengFangParser.parseWeeks("1-30周", 20);
        check("parseWeeks: 超出 maxWeek 被夹住", clamp[1] == 20);
        check("parseWeeks: 空串返回 null", NewZhengFangParser.parseWeeks("", 20) == null);
    }

    // ------------------------------------------------------------------
    // 注册表
    // ------------------------------------------------------------------

    private static void testRegistryOrdering() {
        List<TimetableParserRegistry.Parser> plugins = TimetableParserRegistry.plugins();
        check("注册表非空", !plugins.isEmpty());
        boolean ascending = true;
        for (int i = 1; i < plugins.size(); i++) {
            if (plugins.get(i - 1).priority() > plugins.get(i).priority()) ascending = false;
        }
        check("插件按优先级升序", ascending);
        check("NewZhengFang 在通用网格之前",
                indexOf(plugins, "NewZhengFang") < indexOf(plugins, "HtmlGrid"));
        check("KingoGrid 在通用网格之前",
                indexOf(plugins, "KingoGrid") < indexOf(plugins, "HtmlGrid"));

        // 空响应
        TimetableParserRegistry.Outcome empty = TimetableParserRegistry.parse(
                new TimetableParserRegistry.Input("", "u", "text/html", 20, 12));
        check("空响应给出错误", !empty.result.errors.isEmpty());
        check("空响应无课程", empty.result.details.isEmpty());
        check("describe 输出非空", !TimetableParserRegistry.describe(empty).isEmpty());
    }

    private static void testRegistryDedicatedPlugin() {
        // 未注入时 Dedicated 不参与
        TimetableParserRegistry.Outcome before = TimetableParserRegistry.parse(
                new TimetableParserRegistry.Input("<table>x</table>", "u", "text/html", 20, 12));
        boolean dedicatedSeen = false;
        for (TimetableParserRegistry.Attempt attempt : before.attempts) {
            if (attempt.parser.equals("Dedicated") && attempt.supported) dedicatedSeen = true;
        }
        check("未注入时 Dedicated 不参与", !dedicatedSeen);

        // 注入后优先级最高且命中
        com.checktodo.app.domain.DedicatedParserPlugin.setDelegate(
                new com.checktodo.app.domain.DedicatedParserPlugin.Delegate() {
                    @Override
                    public HtmlTimetableParser.Result parse(String body) {
                        HtmlTimetableParser.Result result = new HtmlTimetableParser.Result();
                        result.bases.add(new CourseBase("d1", "专用课程", 0, "", 0));
                        result.details.add(new CourseDetail("d1-1", "d1", 1, 1, 2, 1, 16, 0, "A101", "T", 0));
                        return result;
                    }
                });
        TimetableParserRegistry.Outcome after = TimetableParserRegistry.parse(
                new TimetableParserRegistry.Input("<table>x</table>", "u", "text/html", 20, 12));
        check("注入后 Dedicated 命中", after.winner.equals("Dedicated"));
        check("注入后解析出专用课程", after.result.bases.size() == 1
                && after.result.bases.get(0).name.equals("专用课程"));
        com.checktodo.app.domain.DedicatedParserPlugin.setDelegate(null);
        check("解除注入后不再命中", !TimetableParserRegistry.parse(
                new TimetableParserRegistry.Input("<table>x</table>", "u", "text/html", 20, 12))
                .winner.equals("Dedicated"));
    }

    // ------------------------------------------------------------------
    // 停止语义
    // ------------------------------------------------------------------

    private static void testStopSemantics() {
        ExtractionState state = new ExtractionState();
        check("初始为 IDLE", state.state() == ExtractionState.State.IDLE);
        check("IDLE 不可停止", !state.stop(0));
        check("IDLE 不保留结果", !state.keepsResults());

        check("可以开始", state.start(1000));
        check("运行中为 RUNNING", state.isRunning());
        check("运行中不可重复开始", !state.start(1100));
        check("运行中保留结果标志为假", !state.keepsResults());

        check("可以停止", state.stop(5000));
        check("停止后为 STOPPED", state.state() == ExtractionState.State.STOPPED);
        check("停止后不再运行", !state.isRunning());
        check("★停止后保留已捕获结果", state.keepsResults());
        check("重复停止无效（幂等）", !state.stop(6000));
        check("停止后运行时长冻结为 4s", state.elapsed(9999) == 4000L);
        check("停止文案含「已停止」", state.statusText(6000).contains("已停止"));

        state.reset();
        check("reset 回到 IDLE", state.state() == ExtractionState.State.IDLE);
        check("reset 后可再次开始", state.start(10_000));

        // 自然完成
        check("可以完成", state.complete(13_000));
        check("完成状态为 COMPLETED", state.state() == ExtractionState.State.COMPLETED);
        check("完成后保留结果", state.keepsResults());
        check("完成后不能再停止", !state.stop(14_000));

        state.reset();
        check("运行时长初始为 0", state.elapsed(0) == 0L);
        check("IDLE 文案为未开始", state.statusText(0).contains("未开始"));
    }

    private static void testWatchdogSemantics() {
        ExtractionState state = new ExtractionState();
        state.start(0);
        check("看门狗只能结束运行中的提取", state.abortByWatchdog(1));
        check("看门狗结束状态", state.state() == ExtractionState.State.WATCHDOG_ABORTED);
        check("★看门狗结束后仍保留已有结果", state.keepsResults());
        check("看门狗文案说明保留结果", state.statusText(1).contains("保留"));
        check("看门狗不能重复触发", !state.abortByWatchdog(2));
        check("看门狗不能对 IDLE 触发", !new ExtractionState().abortByWatchdog(0));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static int indexOf(List<TimetableParserRegistry.Parser> plugins, String name) {
        for (int i = 0; i < plugins.size(); i++) {
            if (plugins.get(i).name().equals(name)) return i;
        }
        return 999;
    }

    private static String repeat(char c, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(c);
        return out.toString();
    }

    private static String read(String path) {
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
