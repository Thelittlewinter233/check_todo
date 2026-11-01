package test;

import com.checktodo.app.domain.CourseBase;
import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.domain.HtmlTimetableParser;
import com.checktodo.app.domain.OnlineExtraction;
import com.checktodo.app.domain.OnlineImportLogic;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 真实案例离线夹具：北京师范大学珠海校区（Kingosoft）课表页。
 *
 * <p>夹具来源（用户提供的真实页面另存）：</p>
 * <ul>
 *   <li>{@code test/fixtures/kingosoft_shell.htm} —— 外壳页，内含
 *       {@code <iframe name="frmReport" src="../student/wsxk.xskcb10319.jsp?params=...&t=...">}；
 *       注意该 src 属性在源码中被折行，含 {@code \r\n}。</li>
 *   <li>{@code test/fixtures/kingosoft_kb.htm} —— 数据页真实响应体（GBK），
 *       即 {@code xskcb10319.jsp} 返回的课表 HTML，共 11 门课 / 19 条排课。</li>
 * </ul>
 *
 * <p>本测试不联网、不依赖 Android：frame URL 从外壳 HTML 里静态抽取，再走
 * {@link OnlineExtraction#candidates} 排序 → 模拟 fetch 分块回传 → {@link OnlineExtraction.ChunkAssembler}
 * 拼装 → {@link HtmlTimetableParser} 解析，验证「新架构能真的拿到 11 门课」。</p>
 */
public final class RealCaseImportTest {
    private static int passed;
    private static int failed;

    private static final String SHELL = "test/fixtures/kingosoft_shell.htm";
    private static final String KB = "test/fixtures/kingosoft_kb.htm";

    /** 外壳页的真实线上地址（从页面里的 jwxt.bnuzh.edu.cn 资源链接推断）。 */
    private static final String PAGE_URL = "https://jwxt.bnuzh.edu.cn/homes.html";

    public static void main(String[] args) {
        testFixtureReadable();
        testFrameDiscoveryFromShell();
        testCandidateRanking();
        testChunkedTransport();
        testRealBodyParsesElevenCourses();
        testTimeoutMatrix();
        testNullCallbackReasons();
        testAssemblerEdgeCases();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------

    private static void testFixtureReadable() {
        check("外壳夹具存在且非空", size(SHELL) > 100_000);
        check("数据页夹具存在且非空", size(KB) > 10_000);
    }

    /**
     * 从外壳 HTML 抽取 iframe src —— 模拟「通道 1 轻发现」在真机上会拿到的东西。
     *
     * <p>真机上走的是 {@code discoveryScript()}（读 {@code contentDocument.location.href}），
     * 离线时没有浏览器，因此这里静态解析 HTML 属性，得到与浏览器折叠空白后相同的绝对 URL。</p>
     */
    private static void testFrameDiscoveryFromShell() {
        String shell = read(SHELL, "GBK");
        List<String> srcs = iframeSrcs(shell);
        check("外壳里至少有一个 iframe", srcs.size() >= 1);

        String reportSrc = null;
        for (String src : srcs) {
            if (src.contains("xskcb10319")) reportSrc = src;
        }
        check("发现 frmReport 指向 wsxk.xskcb10319.jsp", reportSrc != null);
        check("原始 src 含折行空白（真实页面特征）",
                reportSrc != null && (reportSrc.contains("\r") || reportSrc.contains("\n")));
        check("frmReport 的 name 属性存在", shell.contains("name=\"frmReport\""));

        String resolved = OnlineImportLogic.resolveUrl(PAGE_URL, reportSrc);
        check("相对 src 解析为绝对 URL",
                resolved.startsWith("https://jwxt.bnuzh.edu.cn/student/wsxk.xskcb10319.jsp?"));
        check("解析后不含任何空白字符",
                !resolved.contains(" ") && !resolved.contains("\n") && !resolved.contains("\r"));
        check("路径已折叠 ../（不含 /../）", !resolved.contains("/../"));
        check("params 参数完整保留", resolved.contains("params=eG49MjAyNiZ4cT0w"));

        // 模拟 discovery 脚本的返回（真机上这些字段来自 contentDocument）。
        String discoveryJson = discoveryJson(PAGE_URL, resolved);
        OnlineExtraction.Discovery discovery = OnlineExtraction.parseDiscovery(discoveryJson);
        check("discovery JSON 解析成功", discovery.error.isEmpty());
        check("frame 树含 2 个文档（主文档 + frmReport）", discovery.frames.size() == 2);
        check("主文档 depth=0", discovery.frames.get(0).depth == 0);
        check("frmReport depth=1 且 name 正确",
                discovery.frames.get(1).depth == 1 && discovery.frames.get(1).name.equals("frmReport"));
        check("frmReport 可访问", discovery.frames.get(1).accessible);

        List<String> candidates = OnlineExtraction.candidates(discovery, new ArrayList<String>(), 12);
        check("候选列表包含 xskcb10319.jsp", !candidates.isEmpty()
                && candidates.get(0).contains("xskcb10319"));

        // 通道 1 脚本现在只列 frame 路径。
        //
        // 旧实现会在"每个文档"上跑 performance.getEntriesByType('resource')，在
        // 华农 WebVPN + echarts 页面上把主线程占满数秒 → 真机稳定「框架发现超时」。
        // 资源候选改由被动捕获池与 fetch 代理承担（它们本来就能拿到具体响应体），
        // 因此这里断言"不再采集"，而不是"必须采集"。
        String script = OnlineExtraction.discoveryScript();
        check("★发现脚本不再采集 performance 资源（性能回归点）",
                !script.contains("getEntriesByType"));
        check("★发现脚本不含 initiatorType 过滤", !script.contains("initiatorType"));
        check("发现脚本回传空的 resources 列表", script.contains("resources:[]"));
        check("发现脚本不扫元素（无 outerHTML）", !script.contains("outerHTML"));
        check("发现脚本不遍历 body *", !script.contains("'body *'"));
        check("发现脚本限制 frame 数", script.contains("frames.length>=16"));
        check("发现脚本限制递归深度", script.contains("depth>=4"));

        // resources 经 Discovery 透传后应参与候选排序
        String withResources = "{\"pageUrl\":\"" + PAGE_URL + "\",\"pageTitle\":\"t\",\"frames\":[],"
                + "\"resources\":[\"https://jwxt.bnuzh.edu.cn/student/wsxk.xskcb10319_get.jsp?x=1\"],"
                + "\"blocked\":0}";
        OnlineExtraction.Discovery r = OnlineExtraction.parseDiscovery(withResources);
        check("discovery 解析出 resources", r.resources.size() == 1);
        List<String> rc = OnlineExtraction.candidates(r, r.resources, 12);
        check("resources 参与候选", !rc.isEmpty() && rc.get(0).contains("xskcb10319_get"));
    }

    /** 候选排序：课表 jsp 必须排在静态资源前面，否则会先去抓 js/css。 */
    private static void testCandidateRanking() {
        OnlineExtraction.Discovery discovery = OnlineExtraction.parseDiscovery(
                discoveryJson(PAGE_URL, "https://jwxt.bnuzh.edu.cn/student/wsxk.xskcb10319.jsp?params=eG49"));
        List<String> resources = Arrays.asList(
                "https://jwxt.bnuzh.edu.cn/custom/js/jquery.js",
                "https://jwxt.bnuzh.edu.cn/custom/icons/iconfont.css",
                "https://jwxt.bnuzh.edu.cn/themes/classical/css/blue/blue_DataTable.css");
        List<String> candidates = OnlineExtraction.candidates(discovery, resources, 12);
        check("候选非空", !candidates.isEmpty());
        check("第一个候选是课表 jsp", candidates.get(0).contains("xskcb10319"));
        // jquery.js 也在 jwxt 域名下，会进入候选；关键要求是它必须排在课表页之后。
        int jsIndex = indexOfContaining(candidates, "jquery.js");
        if (jsIndex < 0) {
            check("静态 js 若进入候选必排在课表页之后", true);
        } else {
            check("静态 js 排在课表页之后", jsIndex > 0);
        }
        check("同站过滤保留 jwxt 域名", candidates.get(0).startsWith("https://jwxt.bnuzh.edu.cn/"));
        // 跨域资源必须剔除
        List<String> withForeign = new ArrayList<>(resources);
        withForeign.add("https://cdn.example.com/evil.js");
        List<String> filtered = OnlineExtraction.candidates(discovery, withForeign, 12);
        check("跨域资源被剔除", indexOfContaining(filtered, "cdn.example.com") < 0);
    }

    /** 真实响应体按 256KB 分块回传再拼装，必须与原文逐字节一致。 */
    private static void testChunkedTransport() {
        String body = read(KB, "GBK");
        check("真实响应体长度 > 10KB", body.length() > 10_000);

        OnlineExtraction.ChunkAssembler assembler = new OnlineExtraction.ChunkAssembler();
        assembler.begin("r1", "https://jwxt.bnuzh.edu.cn/student/wsxk.xskcb10319.jsp");
        int total = (body.length() + OnlineExtraction.CHUNK_BYTES - 1) / OnlineExtraction.CHUNK_BYTES;
        for (int i = 0; i < total; i++) {
            int from = i * OnlineExtraction.CHUNK_BYTES;
            int to = Math.min(body.length(), from + OnlineExtraction.CHUNK_BYTES);
            boolean accepted = assembler.chunk("r1", i, total, body.substring(from, to));
            if (!accepted) {
                check("块 " + i + " 被接收", false);
                return;
            }
        }
        assembler.done("r1", "200", body.length());
        OnlineExtraction.Assembled assembled = assembler.result("r1");
        check("分块拼装完成", assembled.complete());
        check("拼装结果与原文完全一致", assembled.bodyText().equals(body));
        check("块数符合 256KB 协议", total == (body.length() + 262143) / 262144);
        check("HTTP 状态透传", assembled.status == 200);
        check("declaredLength 透传", assembled.declaredLength == body.length());
    }

    /**
     * 端到端（离线）：真实响应体 → 解析 → 合并去重 → 11 门课 / 19 条排课。
     * 这是本次重做最关键的验收点。
     */
    private static void testRealBodyParsesElevenCourses() {
        String body = read(KB, "GBK");
        HtmlTimetableParser.Result result = HtmlTimetableParser.parse(body, 20, 12);
        check("真实数据页解析出 11 门课", result.bases.size() == 11);
        check("真实数据页解析出 19 条排课", result.details.size() == 19);
        check("解析无错误", result.errors.isEmpty());

        OnlineImportLogic.Merged merged = OnlineImportLogic.merge(
                new ArrayList<CourseBase>(), new ArrayList<CourseDetail>(),
                result.bases, result.details);
        check("合并后仍是 11 门课", merged.courseCount() == 11);
        check("合并后仍是 19 条排课", merged.detailCount() == 19);

        List<String> names = new ArrayList<>();
        for (CourseBase base : merged.bases) names.add(base.name);
        check("解析出「离散数学」", names.contains("离散数学"));
        check("解析出「数据结构」", names.contains("数据结构"));
        check("解析出「马克思主义基本原理」", names.contains("马克思主义基本原理"));
        check("解析出「博弈论」", names.contains("博弈论"));

        boolean roomOk = false;
        for (CourseDetail detail : merged.details) {
            if (detail.room.contains("丽泽楼")) roomOk = true;
        }
        check("教室信息解析正确（丽泽楼）", roomOk);
    }

    /** 超时矩阵：每一条都可能让「提取中」卡住，逐个验证都有确定结果。 */
    private static void testTimeoutMatrix() {
        long t0 = 1_000_000L;
        OnlineExtraction.ExtractionDeadline deadline =
                new OnlineExtraction.ExtractionDeadline(t0, 30_000L);

        check("初始阶段为发现", deadline.phase().equals(OnlineExtraction.ExtractionDeadline.PHASE_DISCOVER));
        check("起始可继续", deadline.canContinue(t0));
        check("t0+29.9s 仍可继续", deadline.canContinue(t0 + 29_900L));
        check("t0+30s 恰好到期不可继续", !deadline.canContinue(t0 + 30_000L));
        check("t0+30s 判定为超时", deadline.expired(t0 + 30_000L));
        check("超期剩余为 0", deadline.remaining(t0 + 40_000L) == 0L);

        deadline.enterPhase(OnlineExtraction.ExtractionDeadline.PHASE_FETCH, t0 + 5_000L);
        check("阶段推进到取数据", deadline.phase().equals(OnlineExtraction.ExtractionDeadline.PHASE_FETCH));

        // 单步超时必须夹在剩余额度内
        check("单步超时不超过剩余额度", deadline.stepTimeout(t0 + 20_000L, 10_000, 500) <= 10_000);
        check("临近截止时单步超时收缩到剩余额度",
                deadline.stepTimeout(t0 + 28_000L, 10_000, 500) <= 2_000);
        check("已超时单步返回 0", deadline.stepTimeout(t0 + 31_000L, 10_000, 500) == 0);

        // 取消优先级高于一切
        OnlineExtraction.ExtractionDeadline cancelled =
                new OnlineExtraction.ExtractionDeadline(t0, 30_000L);
        cancelled.cancel();
        check("取消后不可继续", !cancelled.canContinue(t0));
        check("取消后阶段为已取消",
                cancelled.phase().equals(OnlineExtraction.ExtractionDeadline.PHASE_CANCELLED));

        // 统计口径
        OnlineExtraction.ExtractionDeadline stats =
                new OnlineExtraction.ExtractionDeadline(t0, 30_000L);
        stats.noteCandidate(true);
        stats.noteCandidate(false);
        stats.noteCandidate(true);
        check("候选统计：扫描 3 成功 2", stats.candidatesSeen() == 3 && stats.candidatesOk() == 2);
        String timeoutSummary = stats.summary(t0 + 30_000L);
        check("超时文案含扫描/成功数",
                timeoutSummary.contains("提取超时") && timeoutSummary.contains("已扫描 3")
                        && timeoutSummary.contains("成功 2"));
        String okSummary = stats.summary(t0 + 4_000L);
        check("完成文案含用时", okSummary.contains("提取完成") && okSummary.contains("用时 4s"));

        // 零预算
        OnlineExtraction.ExtractionDeadline zero =
                new OnlineExtraction.ExtractionDeadline(t0, 0L);
        check("零预算立即不可继续", !zero.canContinue(t0));
    }

    /** null / "null" / 空串 / 非法 JSON 都必须给出可读原因，而不是静默失败。 */
    private static void testNullCallbackReasons() {
        OnlineExtraction.Discovery a = OnlineExtraction.parseDiscovery(null);
        check("null 回调给出原因", a.error.contains("null") && !a.error.isEmpty());
        OnlineExtraction.Discovery b = OnlineExtraction.parseDiscovery("null");
        check("\"null\" 回调给出原因", b.error.contains("null"));
        OnlineExtraction.Discovery c = OnlineExtraction.parseDiscovery("   ");
        check("空白回调给出原因", !c.error.isEmpty());
        OnlineExtraction.Discovery d = OnlineExtraction.parseDiscovery("<<<not json>>>");
        check("非法 JSON 给出原因", d.error.contains("不是合法 JSON"));
        check("失败时 frames 为空而不是 null", a.frames != null && a.frames.isEmpty());

        // 双重 JSON 编码（WebView 常见）仍应解析成功
        String inner = discoveryJson(PAGE_URL, PAGE_URL);
        String encoded = "\"" + inner.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        OnlineExtraction.Discovery e = OnlineExtraction.parseDiscovery(encoded);
        check("双重编码的 JSON 也能解析", e.error.isEmpty() && e.frames.size() >= 1);
    }

    /** 分块协议的异常分支：乱序、重复、缺块、超额、失败、abort。 */
    private static void testAssemblerEdgeCases() {
        OnlineExtraction.ChunkAssembler assembler = new OnlineExtraction.ChunkAssembler();

        // 乱序到达
        assembler.begin("r1", "u1");
        assembler.chunk("r1", 2, 3, "C");
        assembler.chunk("r1", 0, 3, "A");
        assembler.chunk("r1", 1, 3, "B");
        assembler.done("r1", "200", 3);
        check("乱序块按 seq 拼回正确顺序", assembler.result("r1").bodyText().equals("ABC"));

        // 重复块不应重复拼接
        assembler.begin("r2", "u2");
        assembler.chunk("r2", 0, 2, "X");
        assembler.chunk("r2", 0, 2, "X");
        assembler.chunk("r2", 1, 2, "Y");
        assembler.done("r2", "200", 2);
        check("重复块被忽略", assembler.result("r2").bodyText().equals("XY"));

        // 缺块必须被识别为失败，而不是返回半截 HTML
        assembler.begin("r3", "u3");
        assembler.chunk("r3", 0, 3, "A");
        assembler.done("r3", "200", 3);
        OnlineExtraction.Assembled partial = assembler.result("r3");
        check("缺块判定为不完整", !partial.complete());
        check("缺块给出原因", partial.error.contains("不完整"));

        // 越界 seq 被拒绝
        assembler.begin("r4", "u4");
        check("seq 越界被拒绝", !assembler.chunk("r4", 5, 3, "Z"));
        check("负 seq 被拒绝", !assembler.chunk("r4", -1, 3, "Z"));

        // 未知 id 被拒绝
        check("未知 requestId 被拒绝", !assembler.chunk("nope", 0, 1, "Z"));

        // HTTP 失败
        assembler.begin("r5", "u5");
        assembler.fail("r5", "HTTP 403");
        check("失败记录原因", assembler.result("r5").error.equals("HTTP 403"));
        check("失败后 isDone 为真（不会无限等）", assembler.isDone("r5"));
        check("失败后 complete 为假", !assembler.result("r5").complete());

        // 没有 begin 直接 done
        assembler.begin("r6", "u6");
        assembler.done("r6", "200", 10);
        check("无数据块直接 done 判定失败", !assembler.result("r6").complete());

        // 多请求并发（不同 id 互不干扰）——用干净的拼装器，避免前面用例的残留干扰
        OnlineExtraction.ChunkAssembler concurrent = new OnlineExtraction.ChunkAssembler();
        concurrent.begin("a", "ua");
        concurrent.begin("b", "ub");
        concurrent.chunk("a", 0, 2, "A1");
        concurrent.chunk("b", 0, 2, "B1");
        check("两个请求都在等待时 pending 含 2 项", concurrent.pending().size() == 2);
        concurrent.chunk("a", 1, 2, "A2");
        concurrent.chunk("b", 1, 2, "B2");
        concurrent.done("a", "200", 4);
        concurrent.done("b", "200", 4);
        check("多请求隔离：a", concurrent.result("a").bodyText().equals("A1A2"));
        check("多请求隔离：b", concurrent.result("b").bodyText().equals("B1B2"));
        check("多请求都完成后 pending 为空", concurrent.pending().isEmpty());

        // 协议脚本侧的往返：abort 脚本可被构造且含 id
        String abort = OnlineExtraction.abortScript("r9");
        check("abort 脚本含 requestId", abort.contains("__checkTodoAbort") && abort.contains("r9"));
        check("全局 abort 脚本存在", OnlineExtraction.abortAllScript().contains("__all"));

        // fetch 代理脚本的注入安全
        String script = OnlineExtraction.fetchProxyScript("r1", "https://x/a.jsp?p='quote'");
        check("fetch 代理脚本含 credentials include", script.contains("credentials:'include'"));
        check("fetch 代理脚本转义单引号", script.contains("\\'quote\\'"));
        check("fetch 代理脚本分块", script.contains("substring(i*size"));
        check("fetch 代理脚本含 abort 检查点", script.contains("ABORT[id]"));
        check("分块大小常量为 256KB", OnlineExtraction.CHUNK_BYTES == 262144);

        // 注入的 URL 里若含 </script> 也不应截断（用 \x3c 转义）
        String risky = OnlineExtraction.fetchProxyScript("r2", "https://x/a?q=</script>");
        check("URL 中的 < 被转义", risky.contains("\\x3c/script>"));
    }

    // ------------------------------------------------------------------
    // 夹具解析辅助
    // ------------------------------------------------------------------

    /**
     * 从 HTML 里抽出所有 {@code <iframe|frame ... src="...">} 的原始 src。
     * 故意保留折行空白，用来验证后续 {@code resolveUrl} 的处理能力。
     */
    private static List<String> iframeSrcs(String html) {
        List<String> out = new ArrayList<>();
        String lower = html.toLowerCase();
        int pos = 0;
        while (true) {
            int iframe = lower.indexOf("<iframe", pos);
            int frame = lower.indexOf("<frame", pos);
            int start;
            if (iframe < 0) start = frame;
            else if (frame < 0) start = iframe;
            else start = Math.min(iframe, frame);
            if (start < 0) break;
            int end = lower.indexOf('>', start);
            if (end < 0) break;
            String tag = html.substring(start, end);
            String src = attribute(tag, "src");
            if (src != null) out.add(src);
            pos = end + 1;
        }
        return out;
    }

    private static String attribute(String tag, String name) {
        String lower = tag.toLowerCase();
        int at = lower.indexOf(name.toLowerCase() + "=");
        if (at < 0) return null;
        int quoteAt = at + name.length() + 1;
        if (quoteAt >= tag.length()) return null;
        char quote = tag.charAt(quoteAt);
        if (quote != '"' && quote != '\'') return null;
        int close = tag.indexOf(quote, quoteAt + 1);
        if (close < 0) return null;
        return tag.substring(quoteAt + 1, close);
    }

    private static String discoveryJson(String pageUrl, String frameUrl) {
        return "{\"pageUrl\":\"" + escape(pageUrl) + "\",\"pageTitle\":\"我的课表\","
                + "\"frames\":["
                + "{\"url\":\"" + escape(pageUrl) + "\",\"title\":\"我的课表\",\"depth\":0,"
                + "\"name\":\"\",\"accessible\":true,\"hasUrl\":true,\"cookie\":true},"
                + "{\"url\":\"" + escape(frameUrl) + "\",\"title\":\"\",\"depth\":1,"
                + "\"name\":\"frmReport\",\"accessible\":true,\"hasUrl\":true,\"cookie\":true}"
                + "],\"blocked\":0}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static int indexOfContaining(List<String> values, String needle) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).contains(needle)) return i;
        }
        return -1;
    }

    private static long size(String path) {
        File file = new File(path);
        return file.exists() ? file.length() : -1;
    }

    private static String read(String path, String charset) {
        byte[] raw = readBytes(path);
        // 与生产路径完全一致：走 HtmlTimetableParser.decode（剥 BOM → 严格 UTF-8 → 回落 GBK）。
        // 夹具实际是 UTF-8，但沿用生产解码器才能保证测的就是线上行为。
        return HtmlTimetableParser.decode(raw);
    }

    private static byte[] readBytes(String path) {
        InputStream stream = null;
        try {
            stream = new FileInputStream(path);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) >= 0) buffer.write(chunk, 0, count);
            return buffer.toByteArray();
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
