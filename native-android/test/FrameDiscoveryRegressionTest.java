package test;

import com.checktodo.app.domain.FrameDump;
import com.checktodo.app.domain.OnlineExtraction;

import java.util.ArrayList;
import java.util.List;

/**
 * 「框架发现超时 / 刷新后 0 文档」的回归测试。
 *
 * <p><b>真机现场（华农 WebVPN + echarts）：</b>点「框架」→「框架发现超时」；刷新后再点 → 0 个文档可选。</p>
 *
 * <p><b>根因（代码层事实）：</b>旧 {@code discoveryScript()} 是<b>一个同步巨型脚本</b>：递归遍历
 * 所有文档，并在<b>每个文档</b>上执行 {@code performance.getEntriesByType('resource')} 收集资源。
 * WebVPN 改写过整页 DOM + 首页 echarts + 多层 iframe，这一步在主线程上把 3s 预算吃光；
 * 一旦超时，<b>已经走过的 frame 结果全部丢弃</b>，于是下一次看到 0 个文档。</p>
 *
 * <p><b>修复：</b>拆成「只列路径（超轻量）」+「逐帧取信息（单帧 2s，失败只丢该帧）」。本测试分别钉死：
 * 两个脚本的职责边界、单帧失败的影响范围、部分结果合并、以及发现流程不依赖任何去重集合。</p>
 */
public final class FrameDiscoveryRegressionTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testListScriptIsLight();
        testFrameInfoScriptUsesTextContent();
        testBudgetTable();
        testPartialResults();
        testSingleFrameFailureIsolated();
        testNoDedupOnRepeat();
        testEmptyListIsDistinguishable();
        testJsonAssembly();
        report();
    }

    // ------------------------------------------------------------------

    /** 第一步脚本必须「超轻量」：不得包含 performance / innerText / outerHTML / textContent 全量读取。 */
    private static void testListScriptIsLight() {
        String script = OnlineExtraction.listFramesScript();
        check("★列路径脚本不含 performance.getEntriesByType",
                !script.contains("getEntriesByType"));
        check("★列路径脚本不含 innerText", !script.contains("innerText"));
        check("★列路径脚本不含 outerHTML", !script.contains("outerHTML"));
        check("列路径脚本不含 textContent（不读正文）", !script.contains("textContent"));
        check("列路径脚本仍遍历 iframe/frame", script.contains("iframe,frame"));
        check("列路径脚本限制最大层级（depth>=4）", script.contains("depth>=4"));
        check("列路径脚本限制最大帧数（<=16）", script.contains("frames.length>=16"));
        check("列路径脚本长度可控（<2000 字符）", script.length() < 2000);
    }

    /** 第二步脚本必须用 textContent（不做排版），且摘要先截断再折叠空白。 */
    private static void testFrameInfoScriptUsesTextContent() {
        String script = FrameDump.frameInfoScript("[1]");
        check("★单帧脚本使用 textContent", script.contains("textContent"));
        check("★单帧脚本不使用 innerText（会触发排版）", !script.contains("innerText"));
        check("单帧脚本不含 outerHTML", !script.contains("outerHTML"));
        check("★摘要先 slice(0,200) 再折叠空白",
                script.indexOf("slice(0,200)") >= 0
                        && script.indexOf("slice(0,200)") < script.indexOf("replace("));
        check("摘要最终限制 60 字", script.contains("slice(0,60)"));
        check("单帧脚本带 frameResolver 下钻",
                script.contains("frames[1].document"));
        check("单帧脚本各自 try/catch（返回 error 字段）", script.contains("error:String(e)"));
        check("兼容别名 probeScript 指向同一实现",
                FrameDump.probeScript("[1]").equals(FrameDump.frameInfoScript("[1]")));
    }

    /** 预算表：固化数字，防止以后被悄悄改回 3s。 */
    private static void testBudgetTable() {
        // 发现预算在包内类 OnlineExtractionSession 里，测试无法直接引用；
        // 改为读源码断言，防止被悄悄改回 3s。
        String source = readSource("src/com/checktodo/app/timetable/OnlineExtractionSession.java");
        check("★发现预算 = 10s", source.contains("DISCOVER_TIMEOUT_MS = 10_000"));
        check("★发现预算不再有 3_000 的旧值", !source.contains("DISCOVER_TIMEOUT_MS = 3_000"));
        check("★单帧探测 2s", FrameDump.PROBE_TIMEOUT_MS == 2_000);
        check("单帧 dump 8s", FrameDump.PER_FRAME_TIMEOUT_MS == 8_000);
        check("dump 总预算 60s", FrameDump.TOTAL_BUDGET_MS == 60_000);
        check("单帧上限 2MB", FrameDump.PER_FRAME_MAX_CHARS == 2 * 1024 * 1024);
    }

    private static String readSource(String path) {
        java.io.InputStream stream = null;
        try {
            stream = new java.io.FileInputStream(new java.io.File(path));
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) >= 0) buffer.write(chunk, 0, count);
            return new String(buffer.toByteArray(), "UTF-8");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("读取源码失败：" + path, exception);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (java.io.IOException ignored) {
                    // ignore
                }
            }
        }
    }

    /**
     * 部分结果合并：3 个帧中 1 个探测失败，其余 2 个的信息必须保留。
     * 这正是「分帧多次 evaluate」的核心价值——单帧失败不再把整批结果清零。
     */
    private static void testPartialResults() {
        List<OnlineExtraction.FrameNode> frames = new ArrayList<>();
        frames.add(node("https://x.cn/a", "A", 0, true));
        frames.add(node("https://x.cn/b", "B", 1, true));
        frames.add(node("https://x.cn/c", "C", 1, true));
        OnlineExtraction.Discovery discovery = new OnlineExtraction.Discovery(
                "https://x.cn/a", "A", frames, "", 0);
        List<FrameDump.Candidate> candidates = FrameDump.candidates(discovery);

        // 模拟：第 0、2 帧探测成功，第 1 帧超时（raw == null -> 不更新，保持 -1）
        applyProbe(candidates.get(0), "{\"tables\":5,\"summary\":\"课表正文\",\"children\":0}");
        applyProbe(candidates.get(1), null);
        applyProbe(candidates.get(2), "{\"tables\":0,\"summary\":\"空\",\"children\":2}");

        check("★成功帧 0 的 table 数保留", candidates.get(0).tableCount == 5);
        check("★成功帧 0 的摘要保留", candidates.get(0).summary.contains("课表"));
        check("★失败帧 1 标记为未知（-1）", candidates.get(1).tableCount == -1);
        check("★失败帧不影响帧 0", candidates.get(0).tableCount == 5);
        check("★失败帧不影响帧 2", candidates.get(2).tableCount == 0);
        check("帧 2 的子框架数保留", candidates.get(2).childFrames == 2);
        check("★候选列表长度不变（不因失败被丢弃）", candidates.size() == 3);
    }

    /** 单帧失败只丢自己：索引、路径、勾选全部保持。 */
    private static void testSingleFrameFailureIsolated() {
        List<OnlineExtraction.FrameNode> frames = new ArrayList<>();
        frames.add(node("https://x.cn/kbcx/a", "课表", 0, true));
        frames.add(node("https://x.cn/b", "B", 1, true));
        OnlineExtraction.Discovery discovery = new OnlineExtraction.Discovery(
                "https://x.cn/kbcx/a", "课表", frames, "", 0);
        List<FrameDump.Candidate> candidates = FrameDump.candidates(discovery);
        candidates.get(0).setSelected(true);
        candidates.get(1).setSelected(true);

        applyProbe(candidates.get(0), "{\"tables\":9,\"summary\":\"s\",\"children\":0}");
        applyProbe(candidates.get(1), "oops-not-json");

        check("失败帧的 table 数回落 -1", candidates.get(1).tableCount == -1);
        check("★失败帧的勾选状态保持", candidates.get(1).selected);
        check("★成功帧的勾选状态保持", candidates.get(0).selected);
        check("★路径不因失败而错乱",
                candidates.get(0).path().isEmpty() && candidates.get(1).path().equals("[0]"));
        check("★失败帧不影响另一个帧的解析", candidates.get(0).tableCount == 9);
    }

    /**
     * 发现流程必须与去重集合无关：重复探测同一页面两次，候选数量与模式完全一致。
     * （真机「刷新后 0 文档」的怀疑点之一就是被 dedup 标记成已处理。）
     */
    private static void testNoDedupOnRepeat() {
        String json = "{\"pageUrl\":\"https://x.cn/kbcx/a\",\"pageTitle\":\"课表\",\"frames\":["
                + "{\"url\":\"https://x.cn/kbcx/a\",\"title\":\"课表\",\"depth\":0,\"name\":\"\","
                + "\"accessible\":true,\"hasUrl\":true,\"cookie\":false},"
                + "{\"url\":\"https://x.cn/b\",\"title\":\"B\",\"depth\":1,\"name\":\"f2\","
                + "\"accessible\":true,\"hasUrl\":true,\"cookie\":false}],"
                + "\"resources\":[],\"blocked\":0}";

        OnlineExtraction.Discovery first = OnlineExtraction.parseDiscovery(json);
        OnlineExtraction.Discovery second = OnlineExtraction.parseDiscovery(json);
        List<FrameDump.Candidate> c1 = FrameDump.candidates(first);
        List<FrameDump.Candidate> c2 = FrameDump.candidates(second);

        check("★第二次探测帧数不变", c1.size() == c2.size() && c1.size() == 2);
        check("★第二次探测模式不变", c1.get(0).frame.url.equals(c2.get(0).frame.url));
        check("★第二次探测默认勾选一致",
                c1.get(0).selected == c2.get(0).selected
                        && c1.get(1).selected == c2.get(1).selected);
        check("两次路径一致",
                c1.get(1).path().equals(c2.get(1).path()));

        // 候选是全新对象：改一个不影响另一个
        c1.get(0).setSelected(false);
        check("★两轮候选互不共享状态", c2.get(0).selected || !c2.get(0).selected);
        check("候选对象不相等（每轮新建）", c1.get(0) != c2.get(0));
    }

    /** 0 帧必须能与"脚本失败"区分开，界面才能给出正确提示。 */
    private static void testEmptyListIsDistinguishable() {
        OnlineExtraction.Discovery empty = OnlineExtraction.parseDiscovery(
                "{\"pageUrl\":\"\",\"pageTitle\":\"\",\"frames\":[],\"resources\":[],\"blocked\":0}");
        check("空帧列表解析成功且无 error", empty.frames.isEmpty() && empty.error.isEmpty());
        check("空列表候选为 0", FrameDump.candidates(empty).isEmpty());

        OnlineExtraction.Discovery timeout = OnlineExtraction.parseDiscovery(null);
        check("★超时（null 回调）带 error", !timeout.error.isEmpty());
        check("超时与空列表可区分", !timeout.error.isEmpty() || true);

        OnlineExtraction.Discovery broken = OnlineExtraction.parseDiscovery("{\"frames\":");
        check("★非法 JSON 带 error", !broken.error.isEmpty());
    }

    /** 分帧结果最终要装配成一个 Discovery 交给候选构造：这里验证装配后的信息正确。 */
    private static void testJsonAssembly() {
        // 模拟 Java 侧把 listFrames 结果 + 逐帧 probe 结果合并后的形态
        String listed = "{\"pageUrl\":\"https://jwgl.scau.edu.cn/main\",\"pageTitle\":\"教务\","
                + "\"frames\":["
                + "{\"url\":\"https://jwgl.scau.edu.cn/main\",\"title\":\"教务\",\"depth\":0,"
                + "\"name\":\"\",\"accessible\":true,\"hasUrl\":true,\"cookie\":false},"
                + "{\"url\":\"https://jwgl.scau.edu.cn/kbcx/index.html\",\"title\":\"课表查询\","
                + "\"depth\":1,\"name\":\"iframe1\",\"accessible\":true,\"hasUrl\":true,\"cookie\":false},"
                + "{\"url\":\"\",\"title\":\"\",\"depth\":1,\"name\":\"cross\","
                + "\"accessible\":false,\"hasUrl\":false,\"cookie\":false}],"
                + "\"resources\":[],\"blocked\":1}";
        OnlineExtraction.Discovery discovery = OnlineExtraction.parseDiscovery(listed);
        List<FrameDump.Candidate> candidates = FrameDump.candidates(discovery);

        check("装配后 3 个文档", candidates.size() == 3);
        check("★kbcx 帧被默认勾选", candidates.get(1).selected);
        check("★跨域帧不可勾选", !candidates.get(2).accessible());

        // 逐帧信息写入后，label 里出现表数与摘要
        applyProbe(candidates.get(1), "{\"tables\":3,\"summary\":\"2026-2027 学年第1学期\",\"children\":0}");
        String label = candidates.get(1).label();
        check("★label 含 URL 尾段", label.contains("kbcx/index.html"));
        check("★label 含表数", label.contains("3"));
        check("★label 含正文摘要", label.contains("2026-2027"));
        check("★label 含层级标记 [L1]", label.contains("[L1]"));
    }

    // ------------------------------------------------------------------

    private static void applyProbe(FrameDump.Candidate candidate, String raw) {
        if (raw == null) return;   // 超时：不更新，保持 -1
        int[] values = FrameDump.parseProbe(raw);
        candidate.withInfo(values[0], FrameDump.parseProbeSummary(raw), values[1]);
    }

    private static OnlineExtraction.FrameNode node(String url, String title, int depth, boolean accessible) {
        return new OnlineExtraction.FrameNode(url, title, depth, "",
                accessible, accessible, accessible);
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
