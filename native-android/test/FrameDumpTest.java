package test;

import com.checktodo.app.domain.FrameDump;
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
 * frame HTML 转储通道的测试：可勾选列表、脚本分离、逐 frame 解析合并。
 *
 * <p><b>离线性：</b>真正的 {@code documentElement.outerHTML} 只能在 WebView 里取，
 * 这里用真实页面夹具 {@code kingosoft_kb.htm} 扮演「某个 frame 的 dump 响应体」，
 * 验证它走同一条注册表链能解析出 11 门课——与 HTML 文件导入的结论完全一致。</p>
 */
public final class FrameDumpTest {
    private static int passed;
    private static int failed;

    private static final String KINGO = "test/fixtures/kingosoft_kb.htm";
    private static final String SCAU = "test/fixtures/scau_grid.htm";

    public static void main(String[] args) {
        testCandidateDefaults();
        testCourseLikeSelection();
        testAllUnrelatedDeselected();
        testScriptSeparation();
        testFrameResolverPaths();
        testProbeParsing();
        testUrlsTail();
        testNestedPaths();
        testPerFrameParseKingo();
        testPerFrameParseScau();
        testMergeDeduplicates();
        testEmptyAndBrokenFrames();
        testDiagnostics();
        report();
    }

    // ------------------------------------------------------------------

    private static void testCandidateDefaults() {
        List<OnlineExtraction.FrameNode> frames = new ArrayList<>();
        frames.add(node("https://jwgl.scau.edu.cn/main.jsp", "教务系统", 0, true));
        frames.add(node("https://jwgl.scau.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html", "课表查询", 1, true));
        frames.add(node("https://ads.example.com/banner", "广告", 1, true));
        frames.add(node("https://other.example.com/x", "", 1, false));

        OnlineExtraction.Discovery discovery = new OnlineExtraction.Discovery(
                "https://jwgl.scau.edu.cn/main.jsp", "教务系统", frames, "", 1);
        List<FrameDump.Candidate> candidates = FrameDump.candidates(discovery);
        check("候选数量与发现一致（含跨域）", candidates.size() == 4);

        FrameDump.Candidate course = find(candidates, "/kbcx/");
        check("★kbcx frame 默认勾选", course != null && course.selected);
        check("kbcx frame 可访问", course != null && course.accessible());

        FrameDump.Candidate ad = find(candidates, "banner");
        check("★广告 frame 默认不勾", ad != null && !ad.selected);

        FrameDump.Candidate cross = find(candidates, "other.example.com");
        check("★跨域 frame 默认不勾", cross != null && !cross.selected);
        check("跨域 frame 不可勾选（setSelected 会拒绝）", cross != null && !selectAndRead(cross));

        check("★selected() 只返回勾选且可访问的",
                FrameDump.selected(candidates).size() == 1);
    }

    private static void testCourseLikeSelection() {
        List<OnlineExtraction.FrameNode> frames = new ArrayList<>();
        frames.add(node("https://x.edu.cn/a/frmKbList.do", "", 1, true));
        OnlineExtraction.Discovery discovery = new OnlineExtraction.Discovery(
                "https://x.edu.cn/", "", frames, "", 0);
        FrameDump.Candidate candidate = FrameDump.candidates(discovery).get(0);
        check("★标题空但 URL 含 kb 特征 → 勾选", candidate.selected);
        check("courseLike 命中", candidate.courseLike());

        FrameDump.Candidate bySummary = new FrameDump.Candidate(0,
                node("https://x.edu.cn/a/xx", "", 1, true));
        bySummary.withInfo(2, "2026-2027学年第1学期 课表", 0);
        check("★摘要含「课表」也算课表特征", bySummary.courseLike());
    }

    private static void testAllUnrelatedDeselected() {
        List<OnlineExtraction.FrameNode> frames = new ArrayList<>();
        frames.add(node("https://x.edu.cn/home", "首页", 0, true));
        frames.add(node("https://x.edu.cn/notice", "公告", 1, true));
        OnlineExtraction.Discovery discovery = new OnlineExtraction.Discovery(
                "https://x.edu.cn/", "首页", frames, "", 0);
        List<FrameDump.Candidate> candidates = FrameDump.candidates(discovery);
        boolean noneSelected = true;
        for (FrameDump.Candidate candidate : candidates) {
            if (candidate.selected) noneSelected = false;
        }
        check("★都不像课表时全不勾（把选择权交给用户）", noneSelected);
    }

    private static void testScriptSeparation() {
        String probe = FrameDump.probeScript("[1]");
        String dump = FrameDump.dumpScript("r1", "https://x.edu.cn/kb", "[1]");

        check("★轻量脚本不含 outerHTML", !probe.contains("outerHTML"));
        check("★dump 脚本含 outerHTML", dump.contains("outerHTML"));
        check("轻量脚本读 table 数量", probe.contains("querySelectorAll('table')"));
        check("轻量脚本截取 60 字摘要", probe.contains(".slice(0,60)"));
        check("轻量脚本统计子 frame", probe.contains("iframe,frame"));
        check("★dump 脚本走 begin/chunk/done 协议",
                dump.contains(".begin(") && dump.contains(".chunk(") && dump.contains(".done("));
        check("dump 脚本带 requestId", dump.contains("'r1'"));
        check("dump 脚本失败回调到 fail", dump.contains(".fail("));
        check("dump 脚本长度可控（无内联 HTML）", dump.length() < 2000);
    }

    private static void testFrameResolverPaths() {
        check("空路径 → 主文档", FrameDump.frameResolver("").equals("document"));
        check("null → 主文档", FrameDump.frameResolver(null).equals("document"));
        check("单层 → frames[2]",
                FrameDump.frameResolver("[2]").equals("document.defaultView.frames[2].document"));
        check("★嵌套两层",
                FrameDump.frameResolver("[2,1]").equals(
                        "document.defaultView.frames[2].document.defaultView.frames[1].document"));
        check("三层", FrameDump.frameResolver("[0,0,3]").contains("frames[0]")
                && FrameDump.frameResolver("[0,0,3]").contains("frames[3]"));
        check("非法片段被忽略", FrameDump.frameResolver("[x]").equals("document"));
    }

    private static void testProbeParsing() {
        String raw = "\"{\\\"tables\\\":3,\\\"summary\\\":\\\"第一行 正文\\\",\\\"children\\\":2}\"";
        int[] values = FrameDump.parseProbe(raw);
        check("★解析 tables", values[0] == 3);
        check("★解析 children", values[1] == 2);
        check("解析摘要", FrameDump.parseProbeSummary(raw).contains("正文"));

        check("非 JSON 回落 -1", FrameDump.parseProbe("oops")[0] == -1);
        check("null 回落 -1", FrameDump.parseProbe(null)[0] == -1);
        check("空串摘要为空", FrameDump.parseProbeSummary("").isEmpty());
        check("负数 tables 解析", FrameDump.parseProbe("{\"tables\":-1,\"children\":0}")[0] == -1);
    }

    /**
     * 嵌套 frame 的路径推导：主文档(depth0) → 子 A(1,idx0) / 子 B(1,idx1) → B 的子(2,[1,0])。
     */
    private static void testNestedPaths() {
        List<OnlineExtraction.FrameNode> frames = new ArrayList<>();
        frames.add(node("https://x.cn/main", "外壳", 0, true));
        frames.add(node("https://x.cn/a", "A", 1, true));
        frames.add(node("https://x.cn/b", "B", 1, true));
        frames.add(node("https://x.cn/b/inner", "B内层", 2, true));
        OnlineExtraction.Discovery discovery = new OnlineExtraction.Discovery(
                "https://x.cn/main", "外壳", frames, "", 0);
        List<FrameDump.Candidate> candidates = FrameDump.candidates(discovery);
        check("★主文档路径为空", candidates.get(0).path().isEmpty());
        check("★第一个子 frame 路径 [0]", candidates.get(1).path().equals("[0]"));
        check("★第二个子 frame 路径 [1]", candidates.get(2).path().equals("[1]"));
        check("★嵌套帧路径 [1,0]", candidates.get(3).path().equals("[1,0]"));
        check("路径可直接喂给 frameResolver",
                FrameDump.frameResolver(candidates.get(3).path())
                        .contains("frames[1].document.defaultView.frames[0]"));
    }

    private static void testUrlsTail() {
        check("URL 尾段两段",
                FrameDump.urlTail("https://jwgl.scau.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html")
                        .equals("kbcx/xskbcx_cxXskbcxIndex.html"));
        check("去掉查询串", FrameDump.urlTail("https://x.cn/a/b.do?id=1").equals("a/b.do"));
        check("空 URL", FrameDump.urlTail("").isEmpty());
    }

    // ------------------------------------------------------------------
    // 逐 frame 解析（离线夹具）
    // ------------------------------------------------------------------

    private static void testPerFrameParseKingo() {
        String html = readFixture(KINGO);
        List<String[]> dumps = new ArrayList<>();
        dumps.add(new String[]{"frame#1 frmReport",
                "https://x.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html", html});
        FrameDump.Report report = FrameDump.parseAll(dumps, 20, 12);
        check("★kingosoft 作为 frame dump 解析出 11 门课", report.courseCount() == 11);
        check("★kingosoft 解析出 19 条排课", report.detailCount() == 19);
        check("命中 1 个 frame", report.hitFrames() == 1);
        check("结果记录字节数", report.results.get(0).bytes == html.length());
        check("未截断", !report.results.get(0).truncated);
        check("诊断描述含「11 门课」", report.results.get(0).describe().contains("11"));
    }

    private static void testPerFrameParseScau() {
        String html = readFixture(SCAU);
        List<String[]> dumps = new ArrayList<>();
        dumps.add(new String[]{"frame#2 kbgrid",
                "https://jwgl.scau.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html", html});
        FrameDump.Report report = FrameDump.parseAll(dumps, 20, 15);
        check("★华农作为 frame dump 解析出 14 门课", report.courseCount() == 14);
        check("★华农 22 条排课", report.detailCount() == 22);
        check("命中插件为 ScauGrid 或等价的课表插件",
                !report.results.get(0).winner.isEmpty());
    }

    private static void testMergeDeduplicates() {
        // 同一个 frame 的 HTML 喂两次：去重后课数不翻倍
        String html = readFixture(KINGO);
        List<String[]> dumps = new ArrayList<>();
        dumps.add(new String[]{"frame#1", "https://x.edu.cn/kb", html});
        dumps.add(new String[]{"frame#1-dup", "https://x.edu.cn/kb2", html});
        FrameDump.Report report = FrameDump.parseAll(dumps, 20, 12);
        check("★重复 frame 去重后仍是 11 门课", report.courseCount() == 11);
        check("★重复 frame 去重后仍是 19 条", report.detailCount() == 19);
        check("两个 frame 都算命中", report.hitFrames() == 2);
        check("诊断汇总含 2 个框架",
                FrameDump.parseAll(dumps, 20, 12).summary().contains("2 \u4e2a\u6846\u67b6"));
    }

    private static void testEmptyAndBrokenFrames() {
        List<String[]> dumps = new ArrayList<>();
        dumps.add(new String[]{"frame#0 empty", "https://x.cn/a", ""});
        dumps.add(new String[]{"frame#1 junk", "https://x.cn/b", "<html><body>404</body></html>"});
        dumps.add(new String[]{"frame#2 good", "https://x.cn/c", readFixture(KINGO)});
        FrameDump.Report report = FrameDump.parseAll(dumps, 20, 12);
        check("空 frame 记为失败", report.results.get(0).error.contains("空"));
        check("无课表 frame 记为未识别", report.results.get(1).error.contains("未识别"));
        check("★好的 frame 仍然解析成功（互不影响）", report.courseCount() == 11);
        check("命中数 1", report.hitFrames() == 1);
    }

    private static void testDiagnostics() {
        List<String[]> dumps = new ArrayList<>();
        dumps.add(new String[]{"frame#0 a", "https://x.cn/a", readFixture(KINGO)});
        dumps.add(new String[]{"frame#1 b", "https://x.cn/b", "<html></html>"});
        FrameDump.Report report = FrameDump.parseAll(dumps, 20, 12);
        String first = report.results.get(0).describe();
        String second = report.results.get(1).describe();
        check("★诊断显示每个 frame 的大小", first.contains("KB") || first.contains("字符"));
        check("★诊断显示命中 frame 的课程数", first.contains("11 门课"));
        check("诊断显示未命中原因", second.contains("未识别"));
        check("summary 含合并总数", report.summary().contains("11 门课"));
    }

    // ------------------------------------------------------------------

    private static boolean selectAndRead(FrameDump.Candidate candidate) {
        candidate.setSelected(true);
        return candidate.selected;
    }

    private static FrameDump.Candidate find(List<FrameDump.Candidate> candidates, String contains) {
        for (FrameDump.Candidate candidate : candidates) {
            if (candidate.frame.url.contains(contains)) return candidate;
        }
        return null;
    }

    private static OnlineExtraction.FrameNode node(String url, String title, int depth, boolean accessible) {
        return new OnlineExtraction.FrameNode(url, title, depth, "",
                accessible, accessible, accessible);
    }

    private static String readFixture(String path) {
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

    private static void report() {
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
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
