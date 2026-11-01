package test;

import com.checktodo.app.domain.FrameDump;
import com.checktodo.app.domain.HtmlTimetableParser;
import com.checktodo.app.domain.OnlineExtraction;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>C 方案：JVM 里真实执行「注入 WebView 的脚本」——模拟真机测试 harness。</b>
 *
 * <p><b>为什么：</b>本机长期无 adb，注入脚本此前只能靠字符串断言（不含某子串之类），
 * <b>脚本从未被真正运行过</b>。字符串断言抓不住语法错误，也抓不住脚本与 Java 协议的
 * 对接错位。本测试用 standalone Nashorn（仅测试依赖，{@code tools/test-lib/}，不进 APK，
 * 不违反「零第三方库」红线——那条红线约束的是 APK 依赖）+ mock DOM（{@code test/fixtures/mockdom.js}），
 * 把生产脚本<b>原样</b>跑起来，并与真实 {@link OnlineExtraction.ChunkAssembler} 对接。</p>
 *
 * <p><b>本测试先红后绿钉死的两个真 bug（历史教训，防回归）：</b></p>
 * <ol>
 *   <li>{@code dumpScript} 是<b>不带括号的匿名 IIFE</b>（{@code function(){...}()}）——
 *       处于语句位置是语法错误，WebView 上必报 SyntaxError，脚本根本不会执行；</li>
 *   <li>{@code dumpScript} 给 {@code sink.chunk} 传 {@code total=0}，而
 *       {@code ChunkAssembler.chunk} 对 {@code total<=0} 直接拒绝——即使语法修好，
 *       done 时也必然「没有任何数据块回传」；且旧 64KB 步长会在大 frame 上撞
 *       {@code parts[]} 40 块上限（40×64KB=2.5MB &lt; 单帧 8MB 预算）。</li>
 * </ol>
 * 修复：IIFE 加括号；total 实算；步长对齐 {@link OnlineExtraction#CHUNK_BYTES}（256KB，
 * 与 fetch 代理同口径，8MB 上限 ≤ 32 块）。
 */
public final class ScriptEngineSimulationTest {
    private static int passed;
    private static int failed;

    private static final String KB = "test/fixtures/kingosoft_kb.htm";
    private static final String MOCKDOM = "test/fixtures/mockdom.js";

    public static void main(String[] args) throws Exception {
        ScriptEngine engine = newEngine();
        check("Nashorn 引擎可用（缺失时先跑 native-android\\prepare-test-libs.bat）", engine != null);
        if (engine == null) {
            report();
            return;
        }
        engine.eval(readFile(MOCKDOM, "UTF-8"));

        testDiscoveryFromRealisticShell(engine);
        testDiscoveryFrameCap(engine);
        testDiscoveryDepthCap(engine);
        testFrameInfoMainAndNested(engine);
        testFrameInfoFailureIsolation(engine);
        testDumpEndToEnd(engine);
        testDumpLargeMultiChunk(engine);
        testFetchMultiChunk(engine);
        testFetchHttpError(engine);
        testFetchAborted(engine);
        testFetchRejected(engine);
        testFetchWithoutSink(engine);
        testTwoRequestsShareAssemblerIndependently(engine);
        report();
    }

    // ------------------------------------------------------------------
    // 场景 1：真实外壳形态（参考 kingosoft_shell.htm 的 iframe 结构）
    // ------------------------------------------------------------------

    private static void testDiscoveryFromRealisticShell(ScriptEngine engine) throws Exception {
        installPage(engine,
                "document = makeDoc({"
                + "url:'https://jwxt.bnuzh.edu.cn/homes.html', title:'学生空间',"
                + "tables:0, bodyText:'北师大珠海分校   学生空间\\n首页',"
                + "frames:["
                + "{name:'leftFrame', url:'https://jwxt.bnuzh.edu.cn/menu.jsp', title:'菜单', tables:1, bodyText:'菜单 导航'},"
                + "{name:'frmReport', url:'https://jwxt.bnuzh.edu.cn/wsxk.xskcb10319.jsp?gnmkdm=N2151',"
                + " title:'学生课表', tables:3, bodyText:'学生课表   2025-2026学年第一学期'},"
                + "{name:'ad', cross:true},"
                + "{name:'top', url:'https://jwxt.bnuzh.edu.cn/top.jsp', title:'顶栏', tables:0, bodyText:'顶栏 通知',"
                + " frames:[{name:'inner', url:'https://jwxt.bnuzh.edu.cn/notice.jsp', title:'通知', tables:2, bodyText:'通知 内容'}]}"
                + "]})");
        String raw = evalString(engine, OnlineExtraction.listFramesScript());
        OnlineExtraction.Discovery discovery = OnlineExtraction.parseDiscovery(raw);

        check("真实外壳场景：脚本执行无诊断错误", discovery.error.isEmpty());
        check("发现 主文档+4 帧+1 嵌套 = 6 个文档", discovery.frames.size() == 6);
        check("pageUrl = 主文档 url", discovery.pageUrl.equals("https://jwxt.bnuzh.edu.cn/homes.html"));
        check("pageTitle = 主文档标题", discovery.pageTitle.equals("学生空间"));
        OnlineExtraction.FrameNode kb = findFrame(discovery, "xskcb10319");
        check("课表帧可访问且 url 命中 xskcb10319", kb != null && kb.accessible);
        OnlineExtraction.FrameNode nested = findFrame(discovery, "notice.jsp");
        check("嵌套帧 depth == 2", nested != null && nested.depth == 2);
        OnlineExtraction.FrameNode cross = findCrossFrame(discovery);
        check("跨域帧被保留且不可访问（不会被误读成没有 frame）",
                cross != null && !cross.accessible && cross.url.isEmpty());

        List<FrameDump.Candidate> candidates = FrameDump.candidates(discovery);
        FrameDump.Candidate kbCandidate = findCandidate(candidates, "xskcb10319");
        check("课表特征帧默认勾选", kbCandidate != null && kbCandidate.selected);
        FrameDump.Candidate crossCandidate = findCrossCandidate(candidates);
        check("跨域帧在列表中展示但不可勾选",
                crossCandidate != null && !crossCandidate.accessible() && !crossCandidate.selected);
    }

    private static void testDiscoveryFrameCap(ScriptEngine engine) throws Exception {
        StringBuilder spec = new StringBuilder();
        spec.append("document = makeDoc({url:'https://x.cn/a', title:'A', frames:[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) spec.append(',');
            spec.append("{name:'f").append(i).append("', url:'https://x.cn/f").append(i)
                    .append("', title:'帧").append(i).append("', tables:0, bodyText:''}");
        }
        spec.append("]})");
        installPage(engine, spec.toString());
        String raw = evalString(engine, OnlineExtraction.listFramesScript());
        OnlineExtraction.Discovery discovery = OnlineExtraction.parseDiscovery(raw);
        check("20 个 iframe 被截断到 16 上限（含主文档）", discovery.frames.size() == 16);
    }

    private static void testDiscoveryDepthCap(ScriptEngine engine) throws Exception {
        installPage(engine,
                "document = makeDoc({url:'https://x.cn/l0', title:'L0',"
                + "frames:[{url:'https://x.cn/l1', title:'L1',"
                + "frames:[{url:'https://x.cn/l2', title:'L2',"
                + "frames:[{url:'https://x.cn/l3', title:'L3',"
                + "frames:[{url:'https://x.cn/l4', title:'L4',"
                + "frames:[{url:'https://x.cn/l5', title:'L5', tables:9, bodyText:'最深层'}]"
                + "}]}]}]}]})");
        String raw = evalString(engine, OnlineExtraction.listFramesScript());
        OnlineExtraction.Discovery discovery = OnlineExtraction.parseDiscovery(raw);
        int maxDepth = 0;
        for (OnlineExtraction.FrameNode frame : discovery.frames) {
            maxDepth = Math.max(maxDepth, frame.depth);
        }
        check("6 层嵌套只下钻到 depth 4", maxDepth == 4);
        check("depth 4 的帧有 url（本层内容可读），第 5 层未展开",
                findFrame(discovery, "/l4") != null && findFrame(discovery, "/l5") == null);
    }

    // ------------------------------------------------------------------
    // 场景 2：单帧轻量信息
    // ------------------------------------------------------------------

    private static void testFrameInfoMainAndNested(ScriptEngine engine) throws Exception {
        installPage(engine,
                "document = makeDoc({url:'https://x.cn/main', title:'主',"
                + "tables:0, bodyText:'外壳   正文\\n\\n  换行折叠测试',"
                + "frames:["
                + "{url:'https://x.cn/first', title:'F1', tables:4, bodyText:'第一帧',"
                + " frames:[{url:'https://x.cn/second', title:'F2', tables:5, bodyText:'第二帧 嵌套'}]},"
                + "{url:'https://x.cn/other', title:'F3', tables:1, bodyText:'旁帧'}"
                + "]})");

        String rawMain = evalString(engine, FrameDump.frameInfoScript(""));
        check("主文档 tables 命中 spec", FrameDump.parseProbe(rawMain)[0] == 0);
        check("主文档 children == 2", FrameDump.parseProbe(rawMain)[1] == 2);
        check("主文档摘要折叠空白后取前 60 字",
                FrameDump.parseProbeSummary(rawMain).equals("外壳 正文 换行折叠测试"));

        String rawNested = evalString(engine, FrameDump.frameInfoScript("[0,0]"));
        check("path [0,0] 下钻到嵌套帧 tables==5", FrameDump.parseProbe(rawNested)[0] == 5);
        check("嵌套帧 children==0", FrameDump.parseProbe(rawNested)[1] == 0);
        check("嵌套帧摘要命中", FrameDump.parseProbeSummary(rawNested).equals("第二帧 嵌套"));

        String rawFirst = evalString(engine, FrameDump.frameInfoScript("[0]"));
        check("path [0] tables==4", FrameDump.parseProbe(rawFirst)[0] == 4);
    }

    private static void testFrameInfoFailureIsolation(ScriptEngine engine) throws Exception {
        installPage(engine,
                "document = makeDoc({url:'https://x.cn/main', title:'主', tables:2, bodyText:'主文档',"
                + "frames:[{name:'ad', cross:true}]})");
        String raw = evalString(engine, FrameDump.frameInfoScript("[9]"));
        check("越界 path → 单帧失败语义 tables==-1", FrameDump.parseProbe(raw)[0] == -1);
        check("失败帧不产生摘要", FrameDump.parseProbeSummary(raw).isEmpty());

        String crossRaw = evalString(engine, FrameDump.frameInfoScript("[0]"));
        check("跨域帧探测返回错误分支（SecurityError 被 catch 进 error 字段）",
                FrameDump.parseProbe(crossRaw)[0] == -1 && crossRaw.contains("error"));
    }

    // ------------------------------------------------------------------
    // 场景 3：dump 端到端（脚本执行 → ChunkAssembler → 解析链）
    // ------------------------------------------------------------------

    private static void testDumpEndToEnd(ScriptEngine engine) throws Exception {
        String kbHtml = readKbFixture();
        engine.put("KB_HTML", kbHtml);
        OnlineExtraction.ChunkAssembler assembler = new OnlineExtraction.ChunkAssembler();
        installPage(engine, "document = makeDoc({url:'https://x.cn/outer', title:'外',"
                + "tables:3, bodyText:'外壳',"
                + "frames:[{name:'frmReport', url:'https://jwxt.bnuzh.edu.cn/wsxk.xskcb10319.jsp',"
                + " title:'学生课表', tables:3, bodyText:'课表', outerHTML: KB_HTML}]})");
        installSink(engine, assembler);

        // 脚本语法必须是合法表达式：匿名 IIFE 缺括号在 WebView 上 SyntaxError（历史真 bug）。
        boolean executed = true;
        try {
            engine.eval(FrameDump.dumpScript("r1", "https://jwxt.bnuzh.edu.cn/wsxk.xskcb10319.jsp", "[0]"));
        } catch (ScriptException exception) {
            executed = false;
            System.out.println("      dumpScript 执行异常：" + exception.getMessage());
        }
        check("★dumpScript 是合法可执行的脚本（语法错误历史 bug 防回归）", executed);

        OnlineExtraction.Assembled result = assembler.result("r1");
        check("★dump 经真实 ChunkAssembler 拼装后 complete（total=0 历史 bug 防回归）", result.complete());
        check("dump 内容与夹具逐字符一致（round-trip）", result.bodyText().equals(kbHtml));
        check("declaredLength == 夹具长度", result.declaredLength == kbHtml.length());

        // 与 HTML 导入完全同一条解析链：dump → parseAll（8 节以上口径同 FrameDumpTest：11 门 / 19 条）。
        List<String[]> dumps = new ArrayList<>();
        dumps.add(new String[]{"frmReport", "https://jwxt.bnuzh.edu.cn/wsxk.xskcb10319.jsp", result.bodyText()});
        FrameDump.Report report = FrameDump.parseAll(dumps, 20, 12);
        check("dump 夹具解析出 11 门课（与 FrameDumpTest 同口径）", report.courseCount() == 11);
        check("dump 夹具解析出 19 条排课（12 节制含晚间两节）", report.detailCount() == 19);
    }

    private static void testDumpLargeMultiChunk(ScriptEngine engine) throws Exception {
        // 约 419KB outerHTML → 256KB 步长 = 2 块；同时验证远未触及 40 块上限。
        engine.put("BIG_HTML", repeat("xskcb10319 课表内容 ", 26214) + "tail");
        OnlineExtraction.ChunkAssembler assembler = new OnlineExtraction.ChunkAssembler();
        installPage(engine, "document = makeDoc({url:'https://x.cn/big', title:'大页',"
                + "frames:[{url:'https://x.cn/bigframe', title:'大帧', outerHTML: BIG_HTML}]})");
        installSink(engine, assembler);
        engine.eval(FrameDump.dumpScript("rBig", "https://x.cn/bigframe", "[0]"));
        OnlineExtraction.Assembled result = assembler.result("rBig");
        check("419KB frame 分 2 块回传且 complete", result.complete() && result.receivedChunks == 2);
        check("419KB frame round-trip 逐字符一致", result.bodyText().equals(engine.get("BIG_HTML")));
    }

    // ------------------------------------------------------------------
    // 场景 4：fetch 代理
    // ------------------------------------------------------------------

    private static void testFetchMultiChunk(ScriptEngine engine) throws Exception {
        OnlineExtraction.ChunkAssembler assembler = new OnlineExtraction.ChunkAssembler();
        engine.put("FETCH_TEXT", repeat("0123456789abcdef", 18750));   // 300,000 字符
        installPage(engine,
                "document = makeDoc({url:'https://x.cn/main', title:'主'});"
                + "fetch = mockFetchOk('https://x.cn/data', FETCH_TEXT);");
        installSink(engine, assembler);
        engine.eval(OnlineExtraction.fetchProxyScript("rF1", "https://x.cn/data"));

        OnlineExtraction.Assembled result = assembler.result("rF1");
        check("fetch 代理 300KB 分 2 块回传", result.complete() && result.receivedChunks == 2);
        check("fetch 代理 round-trip 逐字符一致", result.bodyText().equals(engine.get("FETCH_TEXT")));
        check("fetch 代理 HTTP 200 记入 status", result.status == 200);
        check("fetch 代理 declaredLength == 300000", result.declaredLength == 300000);
    }

    private static void testFetchHttpError(ScriptEngine engine) throws Exception {
        OnlineExtraction.ChunkAssembler assembler = new OnlineExtraction.ChunkAssembler();
        installPage(engine,
                "document = makeDoc({url:'https://x.cn/main', title:'主'});"
                + "fetch = mockFetchOk('https://x.cn/err', 'ignored', 500);");
        installSink(engine, assembler);
        engine.eval(OnlineExtraction.fetchProxyScript("rF2", "https://x.cn/err"));
        check("HTTP 500 → fail(HTTP 500)",
                assembler.hasFailed("rF2") && assembler.errorOf("rF2").equals("HTTP 500"));
    }

    private static void testFetchAborted(ScriptEngine engine) throws Exception {
        OnlineExtraction.ChunkAssembler assembler = new OnlineExtraction.ChunkAssembler();
        installPage(engine,
                "document = makeDoc({url:'https://x.cn/main', title:'主'});"
                + "fetch = mockFetchOk('https://x.cn/data', 'should-not-arrive');");
        installSink(engine, assembler);
        // 注入 abort 标志必须在 window 就绪之后（与真机一致：abortScript 在页面上执行）。
        engine.eval("window.__checkTodoAbort['rF3'] = true;");
        engine.eval(OnlineExtraction.fetchProxyScript("rF3", "https://x.cn/data"));
        check("已 abort 的请求 → fail(aborted before start)",
                assembler.hasFailed("rF3") && assembler.errorOf("rF3").equals("aborted before start"));
    }

    private static void testFetchRejected(ScriptEngine engine) throws Exception {
        OnlineExtraction.ChunkAssembler assembler = new OnlineExtraction.ChunkAssembler();
        installPage(engine,
                "document = makeDoc({url:'https://x.cn/main', title:'主'});"
                + "fetch = mockFetchReject('网络不可达');");
        installSink(engine, assembler);
        engine.eval(OnlineExtraction.fetchProxyScript("rF4", "https://x.cn/data"));
        check("fetch 拒绝 → fail(fetch rejected: ...) 带 message",
                assembler.hasFailed("rF4") && assembler.errorOf("rF4").contains("fetch rejected")
                        && assembler.errorOf("rF4").contains("网络不可达"));
    }

    private static void testFetchWithoutSink(ScriptEngine engine) throws Exception {
        OnlineExtraction.ChunkAssembler assembler = new OnlineExtraction.ChunkAssembler();
        // 注意：不安装 sink —— 真机对应「接口未注入/已被移除」。
        installPage(engine,
                "document = makeDoc({url:'https://x.cn/main', title:'主'});"
                + "fetch = mockFetchOk('https://x.cn/data', 'orphan');");
        engine.eval(OnlineExtraction.fetchProxyScript("rF5", "https://x.cn/data"));
        check("sink 未注入 → 无回调、不抛异常", assembler.pending().isEmpty()
                && !assembler.isDone("rF5"));
    }

    // ------------------------------------------------------------------
    // 场景 5：同一 assembler 上两路请求互不串
    // ------------------------------------------------------------------

    private static void testTwoRequestsShareAssemblerIndependently(ScriptEngine engine) throws Exception {
        String kbHtml = readKbFixture();
        engine.put("KB_HTML", kbHtml);
        engine.put("FETCH_TEXT", repeat("abcdef0123456789", 6250));   // 100,000 字符
        OnlineExtraction.ChunkAssembler assembler = new OnlineExtraction.ChunkAssembler();
        installPage(engine,
                "document = makeDoc({url:'https://x.cn/outer', title:'外',"
                + "frames:[{name:'frmReport', url:'https://jwxt.bnuzh.edu.cn/wsxk.xskcb10319.jsp',"
                + " title:'学生课表', outerHTML: KB_HTML}]});"
                + "fetch = mockFetchOk('https://x.cn/data', FETCH_TEXT);");
        installSink(engine, assembler);
        engine.eval(FrameDump.dumpScript("rA", "https://jwxt.bnuzh.edu.cn/wsxk.xskcb10319.jsp", "[0]"));
        engine.eval(OnlineExtraction.fetchProxyScript("rB", "https://x.cn/data"));

        check("同 assembler 双请求：dump 路完整", assembler.result("rA").complete()
                && assembler.result("rA").bodyText().equals(kbHtml));
        check("同 assembler 双请求：fetch 路完整", assembler.result("rB").complete()
                && assembler.result("rB").bodyText().equals(engine.get("FETCH_TEXT")));
    }

    // ------------------------------------------------------------------

    /** 先建 page（document/window），再注入 sink —— 顺序与真机一致（WebView 里 window 永远在）。 */
    private static void installPage(ScriptEngine engine, String setupJs) throws ScriptException {
        engine.eval(setupJs);
        engine.eval("window = document.defaultView;");
    }

    /** 把 ChunkAssembler 以生产同款形态注入 mock window（与 addJavascriptInterface 等价）。 */
    private static void installSink(ScriptEngine engine, OnlineExtraction.ChunkAssembler assembler)
            throws ScriptException {
        engine.put("javaSink", assembler);
        engine.eval("window.__checkTodoSink = makeSink(javaSink);");
        engine.eval("window.__checkTodoAbort = window.__checkTodoAbort || {};");
    }

    private static ScriptEngine newEngine() {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("nashorn");
        if (engine == null) engine = manager.getEngineByName("JavaScript");
        return engine;
    }

    private static String evalString(ScriptEngine engine, String script) throws ScriptException {
        Object value = engine.eval(script);
        return value == null ? null : String.valueOf(value);
    }

    private static OnlineExtraction.FrameNode findFrame(OnlineExtraction.Discovery discovery, String needle) {
        for (OnlineExtraction.FrameNode frame : discovery.frames) {
            if (frame.url.contains(needle)) return frame;
        }
        return null;
    }

    /** 跨域帧在发现结果里的形态：url/title 均空、accessible=false、depth>0。 */
    private static OnlineExtraction.FrameNode findCrossFrame(OnlineExtraction.Discovery discovery) {
        for (OnlineExtraction.FrameNode frame : discovery.frames) {
            if (frame.depth > 0 && !frame.accessible && frame.url.isEmpty()) return frame;
        }
        return null;
    }

    private static FrameDump.Candidate findCandidate(List<FrameDump.Candidate> candidates, String needle) {
        for (FrameDump.Candidate candidate : candidates) {
            if (candidate.frame.url.contains(needle)) return candidate;
        }
        return null;
    }

    private static FrameDump.Candidate findCrossCandidate(List<FrameDump.Candidate> candidates) {
        for (FrameDump.Candidate candidate : candidates) {
            if (!candidate.frame.accessible && candidate.frame.url.isEmpty()) return candidate;
        }
        return null;
    }

    /** 与 FrameDumpTest.readFixture 同口径：走生产 decode 链（剥 BOM → 严格 UTF-8 → 回落 GBK）。 */
    private static String readKbFixture() {
        try {
            InputStream stream = new FileInputStream(new java.io.File(KB));
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) >= 0) buffer.write(chunk, 0, count);
            stream.close();
            return HtmlTimetableParser.decode(buffer.toByteArray());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("读取夹具失败：" + KB, exception);
        }
    }

    private static String readFile(String path, String charset) {
        try {
            InputStream stream = new FileInputStream(new java.io.File(path));
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) >= 0) buffer.write(chunk, 0, count);
            stream.close();
            return new String(buffer.toByteArray(), Charset.forName(charset));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("读取文件失败：" + path, exception);
        }
    }

    private static String repeat(String unit, int times) {
        StringBuilder out = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) out.append(unit);
        return out.toString();
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

    private static void report() {
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
