package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * frame HTML 转储通道（把「控制台 copy 课表 frame 的 outerHTML + HTML 导入」自动化）。
 *
 * <p><b>为什么需要这条通道：</b>课表页往往是「外壳 + iframe」结构，真正的课表在某个
 * iframe 里。经由 {@code fetch} 拿到的只能原始 HTML 或 JSON 接口；而被脚本渲染过、或需要
 * 现场会话才能拉取的 DOM，只有<b>在那个 frame 自己的上下文里</b>读 {@code documentElement.outerHTML}
 * 才能拿到——这正是用户手动「控制台 copy + 存 HTML + 导入」成功的原因。本类把这一步自动化。</p>
 *
 * <p><b>交互原则（用户明确要求）：</b>默认<b>手动勾选</b>，不做自动全量遍历。
 * 列表阶段只用「轻量信息脚本」取 table 数量与正文摘要，<b>绝不</b>序列化 outerHTML；
 * 用户勾好后再对选中项逐个 dump。两者脚本严格分离，杜绝列表卡顿。</p>
 *
 * <p><b>为什么把脚本与解析放在纯 Java 域层：</b>脚本内容、分块协议、逐 frame 解析合并
 * 都是可以离线验证的逻辑；放进 Controller 就只能靠真机试。这里全部可单测。</p>
 */
public final class FrameDump {

    /** 单个 frame 的 HTML 上限：2MB。超过即截断并标记，避免一个巨型 DOM 拖垮整轮。 */
    public static final int PER_FRAME_MAX_CHARS = 2 * 1024 * 1024;

    /** 单 frame dump 超时：8s（静默返回意味着 frame 已卸载或被 CSP 拦）。 */
    public static final int PER_FRAME_TIMEOUT_MS = 8_000;

    /** 整轮总预算：60s。用户明确放宽（原来是 30s），因为逐个 dump 多个 frame 需要时间。 */
    public static final int TOTAL_BUDGET_MS = 60_000;

    /** 单个 frame 的轻量信息探测超时：2s。只是读几个属性，不该更久。 */
    public static final int PROBE_TIMEOUT_MS = 2_000;

    private FrameDump() {
    }

    // ------------------------------------------------------------------
    // 可转储的 frame 选择
    // ------------------------------------------------------------------

    /**
     * 一个「可勾选」的 frame 条目：在发现问题树的基础上，补上轻量信息与默认勾选建议。
     */
    public static final class Candidate {
        /** 在 discovery.frames 里的下标（跨域也算，便于展示「不可访问」）。 */
        public final int index;
        public final OnlineExtraction.FrameNode frame;
        /** 轻量信息：document 里的 table 数量；-1 表示尚未探测。 */
        public int tableCount = -1;
        /** 轻量信息：正文前 60 字摘要。 */
        public String summary = "";
        /** 该 frame 内部还有几个子 frame（嵌套）。 */
        public int childFrames;
        public boolean selected;
        /** 在同层（同父、同 depth）中的序号，用于从顶层逐级下钻。 */
        public int siblingIndex;
        /** 从顶层到本 frame 的下标链，如 [2,1] 表示主文档第 3 个 frame 的第 2 个 frame。 */
        public int[] chain = new int[0];

        public Candidate(int index, OnlineExtraction.FrameNode frame) {
            this.index = index;
            this.frame = frame;
        }

        public boolean accessible() {
            return frame.accessible;
        }

        /** 课表类特征：URL 或标题命中 kbcx/kb/课表/周/课程。 */
        public boolean courseLike() {
            String text = (frame.url + " " + frame.title + " " + summary).toLowerCase(Locale.ROOT);
            // 兼容 kbcx / kbgrid / frmKbList 这类正方、Kingosoft 的课表命名。
            return text.contains("kbcx") || text.contains("kb") || text.contains("\u8bfe\u8868")
                    || text.contains("\u8bfe\u7a0b") || text.contains("\u5468");
        }

        /** 列表展示用短描述：URL 尾段 + 标题 + 轻量信息。 */
        public String label() {
            String tail = urlTail(frame.url);
            StringBuilder out = new StringBuilder();
            out.append(tail.isEmpty() ? ("frame#" + index) : tail);
            if (!frame.title.isEmpty()) out.append(" \u00b7 ").append(trim(frame.title, 24));
            if (frame.depth > 0) out.append(" [L").append(frame.depth).append("]");
            if (!frame.accessible) out.append(" [\u8de8\u57df]");
            if (childFrames > 0) out.append(" \u542b ").append(childFrames).append(" \u4e2a\u5b50\u6846\u67b6");
            if (tableCount >= 0) out.append(" \u00b7 ").append(tableCount).append(" \u5f20\u8868");
            if (!summary.isEmpty()) out.append(" \u00b7 ").append(trim(summary, 60));
            return out.toString();
        }

        public Candidate withInfo(int tables, String text, int children) {
            this.tableCount = tables;
            this.summary = text == null ? "" : text;
            this.childFrames = Math.max(0, children);
            return this;
        }

        public void setSelected(boolean value) {
            this.selected = value && frame.accessible;
        }

        /** 下钻路径（如 {@code [2,1]}）；主文档为空串。 */
        public String path() {
            if (chain.length == 0) return "";
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < chain.length; i++) {
                if (i > 0) out.append(',');
                out.append(chain[i]);
            }
            return "[" + out + "]";
        }
    }

    /**
     * 由发现结果构造勾选列表。
     *
     * <p><b>默认勾选策略（用户要求「默认不勾或仅勾课表特征」）：</b>只自动勾选
     * <b>可访问且特征像课表</b>的 frame；一个都不像时<b>全不勾</b>，把选择权完全交给用户。
     * 这样既不会漏掉明显目标，也不会在奇怪页面上自动 dump 一堆无关文档。</p>
     */
    public static List<Candidate> candidates(OnlineExtraction.Discovery discovery) {
        List<Candidate> out = new ArrayList<>();
        if (discovery == null) return out;
        // 每个 depth 下「已出现几个」：同一父节点的子 frame 在发现脚本里是连续 push 的，
        // 因此同层出现次序就是它的下标。depth 0 是主文档，没有下标。
        Map<Integer, Integer> perDepth = new LinkedHashMap<>();
        int[] parentChain = new int[0];
        for (int i = 0; i < discovery.frames.size(); i++) {
            OnlineExtraction.FrameNode frame = discovery.frames.get(i);
            if (frame == null) continue;
            if (frame.depth <= 0) {
                parentChain = new int[0];
            } else {
                Integer seen = perDepth.get(frame.depth);
                int index = seen == null ? 0 : seen;
                perDepth.put(frame.depth, index + 1);
                int[] chain = new int[frame.depth];
                int copy = Math.min(parentChain.length, frame.depth - 1);
                System.arraycopy(parentChain, 0, chain, 0, copy);
                chain[frame.depth - 1] = index;
                parentChain = chain;
            }
            // 跨域帧（url 与 title 都为空）也保留：列表里显示 [跨域] 让用户知道
            // 「这里有一块读不到的内容」，而不是让它凭空消失（会被误读成页面没有 frame）。
            if (frame.url.isEmpty() && frame.title.isEmpty() && frame.name.isEmpty()
                    && !frame.accessible && frame.depth == 0) {
                continue;   // 只过滤主文档都读不到的极端情况
            }
            Candidate candidate = new Candidate(i, frame);
            candidate.chain = parentChain.clone();
            candidate.siblingIndex = frame.depth <= 0 ? 0 : parentChain[frame.depth - 1];
            out.add(candidate);
        }
        applyDefaultSelection(out);
        return out;
    }

    /** 只勾选可访问且像课表的；都不像则全不勾。 */
    public static void applyDefaultSelection(List<Candidate> candidates) {
        boolean any = false;
        for (Candidate candidate : candidates) {
            if (candidate.accessible() && candidate.courseLike()) {
                candidate.selected = true;
                any = true;
            }
        }
        if (!any) {
            for (Candidate candidate : candidates) candidate.selected = false;
        }
    }

    /** 选中的、可访问的下标。 */
    public static List<Candidate> selected(List<Candidate> candidates) {
        List<Candidate> out = new ArrayList<>();
        if (candidates == null) return out;
        for (Candidate candidate : candidates) {
            if (candidate.selected && candidate.accessible()) out.add(candidate);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 脚本（轻量探测 / dump 严格分离）
    // ------------------------------------------------------------------

    /**
     * 第二步：<b>单帧</b>轻量信息（table 数 / 正文前 60 字 / 子 frame 数）。
     *
     * <p>要点：</p>
     * <ul>
     *   <li>用 {@code textContent} 而不是 {@code innerText}——后者会触发强制排版（layout），
     *       在大 DOM 上单次可达数百毫秒；{@code textContent} 只读文本节点，不做排版计算。</li>
     *   <li>摘要先 {@code slice(0, 200)} <b>再</b>做空白折叠，避免对整段正文跑正则。</li>
     *   <li>每个属性各自 try/catch，单帧失败只影响该帧（返回 error 字段），不影响其它帧。</li>
     * </ul>
     */
    public static String frameInfoScript(String path) {
        return "(function(){try{"
                + "var d=" + frameResolver(path) + ";"
                + "var t=-1;try{t=d.querySelectorAll('table').length;}catch(e){t=-1;}"
                + "var s='';try{var raw=String((d.body?d.body.textContent:d.documentElement.textContent)||'');"
                + "s=raw.slice(0,200).replace(/\\s+/g,' ').trim().slice(0,60);}catch(e){s='';}"
                + "var c=0;try{c=d.querySelectorAll('iframe,frame').length;}catch(e){c=0;}"
                + "var url='';try{url=String(d.location.href||'');}catch(e){url='';}"
                + "var title='';try{title=String(d.title||'').slice(0,80);}catch(e){title='';}"
                + "return JSON.stringify({tables:t,summary:s,children:c,url:url,title:title});"
                + "}catch(e){return JSON.stringify({tables:-1,summary:'',children:0,url:'',title:'',"
                + "error:String(e)});}})()";
    }

    /**
     * 兼容旧调用：等价于 {@link #frameInfoScript(String)}。
     *
     * @deprecated 用 {@link #frameInfoScript(String)} 表达意图更清楚。
     */
    @Deprecated
    public static String probeScript(String path) {
        return frameInfoScript(path);
    }

    /**
     * dump 脚本：在该 frame 自己的上下文里一次性取 {@code documentElement.outerHTML}，
     * 分块回传。块大小与 {@link OnlineExtraction#CHUNK_BYTES} 一致（256KB，与 fetch 代理同口径；
     * 8MB 单帧上限 ≤ 32 块，不触碰 ChunkAssembler 的 40 块 parts 上限）。
     *
     * <p><b>两个真机必修点（由 ScriptEngineSimulationTest 在 Nashorn 里先红后绿钉死）：</b></p>
     * <ul>
     *   <li>整个脚本必须是<b>带括号的 IIFE</b>——旧版裸写 {@code function(){...}()}，
     *       处于语句位置是语法错误，WebView 上必报 SyntaxError，脚本根本不执行；</li>
     *   <li>{@code chunk} 必须传<b>实算 total</b>——旧版传 0，而 {@code ChunkAssembler.chunk}
     *       对 {@code total<=0} 直接拒绝，done 时必然「没有任何数据块回传」。</li>
     * </ul>
     *
     * <p>与 fetch 代理共用 {@code sink.begin/chunk/done/fail} 协议，因此 Java 侧无需新解析器。</p>
     */
    public static String dumpScript(String requestId, String httpsUrl, String path) {
        String id = js(requestId);
        int chunkBytes = OnlineExtraction.CHUNK_BYTES;
        return "(function(){try{"
                + "var d=" + frameResolver(path) + ";"
                + "var h='';try{h=String(d.documentElement.outerHTML||'');}catch(e){"
                + "window.__checkTodoSink.fail('" + id + "','frame 内读取 outerHTML 失败：'+e);return;}"
                + "var url='';try{url=String(d.location.href||'');}catch(e){url='" + js(httpsUrl) + "';}"
                + "window.__checkTodoSink.begin('" + id + "',url);"
                + "var step=" + chunkBytes + ",total=Math.ceil(h.length/step)||1,n=0,i=0;"
                + "for(;i<h.length;i+=step){window.__checkTodoSink.chunk('" + id + "',n++,total,"
                + "h.slice(i,i+step));}"
                + "window.__checkTodoSink.done('" + id + "','ok',h.length);"
                + "}catch(e){try{window.__checkTodoSink.fail('" + id + "',String(e));}catch(x){}}})()";
    }

    /**
     * 把「路径」编译成 JS 表达式，从顶层 window 逐级下钻到目标 document。
     *
     * <p>路径为空表示主文档（{@code document}）。每一级都用 {@code frames[i]} 而不是
     * {@code querySelectorAll('iframe')}：后者顺序受 DOM 变动影响，而 {@code window.frames}
     * 是稳定的文档顺序，且能拿到跨域 frame 的 window（虽然其 document 不可读）。</p>
     */
    public static String frameResolver(String path) {
        if (path == null || path.trim().isEmpty()) return "document";
        StringBuilder out = new StringBuilder("document");
        for (String part : path.replace("[", "").replace("]", "").split(",")) {
            String value = part.trim();
            if (value.isEmpty()) continue;
            int index;
            try {
                index = Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                continue;
            }
            out.append(".defaultView.frames[").append(index).append("].document");
        }
        return out.toString();
    }

    /** 解析探测脚本的返回值。 */
    public static int[] parseProbe(String raw) {
        // 返回 {tables, children}；解析失败给 {-1, 0}
        int[] fallback = new int[]{-1, 0};
        if (raw == null || raw.trim().isEmpty()) return fallback;
        String text = raw.trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            text = unescapeJsonString(text.substring(1, text.length() - 1));
        }
        if (!text.startsWith("{")) return fallback;
        return new int[]{jsonInt(text, "tables", -1), jsonInt(text, "children", 0)};
    }

    public static String parseProbeSummary(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String text = raw.trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            text = unescapeJsonString(text.substring(1, text.length() - 1));
        }
        if (!text.startsWith("{")) return "";
        String key = "\"summary\":\"";
        int at = text.indexOf(key);
        if (at < 0) return "";
        StringBuilder out = new StringBuilder();
        for (int i = at + key.length(); i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                out.append(text.charAt(++i));
                continue;
            }
            if (c == '"') break;
            out.append(c);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // 逐 frame 解析与合并
    // ------------------------------------------------------------------

    /** 单个 frame 的解析结果，用于诊断展示（哪个 frame 出了几门课）。 */
    public static final class FrameResult {
        public final String label;
        public final String url;
        public final int bytes;
        public final boolean truncated;
        public int courses;
        public int details;
        public String winner = "";
        public String error = "";

        FrameResult(String label, String url, int bytes, boolean truncated) {
            this.label = label;
            this.url = url;
            this.bytes = bytes;
            this.truncated = truncated;
        }

        public String describe() {
            StringBuilder out = new StringBuilder(label);
            out.append(" \u00b7 ").append(kb(bytes));
            if (truncated) out.append("(\u5df2\u622a\u65ad)");
            if (!error.isEmpty()) {
                out.append(" \u00b7 \u5931\u8d25\uff1a").append(error);
            } else {
                out.append(" \u00b7 ").append(courses).append(" \u95e8\u8bfe / ")
                        .append(details).append(" \u6761");
                if (!winner.isEmpty()) out.append(" \u00b7 ").append(winner);
            }
            return out.toString();
        }
    }

    /** 逐 frame 解析并合并：走现成注册表，与 HTML 导入完全同一条链。 */
    public static final class Report {
        public final List<FrameResult> results = new ArrayList<>();
        public OnlineImportLogic.Merged merged = new OnlineImportLogic.Merged();

        public int courseCount() {
            return merged.courseCount();
        }

        public int detailCount() {
            return merged.detailCount();
        }

        /** 至少出一个课程的空 frame 数量（诊断「为什么没结果」）。 */
        public int hitFrames() {
            int count = 0;
            for (FrameResult result : results) {
                if (result.courses > 0 || result.details > 0) count++;
            }
            return count;
        }

        public String summary() {
            return results.size() + " \u4e2a\u6846\u67b6\uff0c" + hitFrames() + " \u4e2a\u547d\u4e2d\uff0c\u5408\u5e76 "
                    + courseCount() + " \u95e8\u8bfe / " + detailCount() + " \u6761\u6392\u8bfe";
        }
    }

    /**
     * 逐 frame 解析、合并去重。
     *
     * @param dumps 每个元素是 {label, url, html}
     */
    public static Report parseAll(List<String[]> dumps, int maxWeek, int nodes) {
        Report report = new Report();
        if (dumps == null) return report;
        for (String[] dump : dumps) {
            if (dump == null || dump.length < 3) continue;
            String label = dump[0] == null ? "" : dump[0];
            String url = dump[1] == null ? "" : dump[1];
            String html = dump[2] == null ? "" : dump[2];
            boolean truncated = html.length() >= PER_FRAME_MAX_CHARS;
            FrameResult result = new FrameResult(label, url, html.length(), truncated);
            if (html.trim().isEmpty()) {
                result.error = "\u7a7a\u5185\u5bb9";
                report.results.add(result);
                continue;
            }
            TimetableParserRegistry.Outcome outcome = TimetableParserRegistry.parse(
                    new TimetableParserRegistry.Input(html, url, "text/html", maxWeek, nodes));
            result.courses = outcome.result.bases.size();
            result.details = outcome.result.details.size();
            result.winner = outcome.winner;
            if (outcome.isEmpty()) {
                result.error = "\u672a\u8bc6\u522b\u5230\u8bfe\u8868";
            } else {
                report.merged = OnlineImportLogic.merge(report.merged.bases, report.merged.details,
                        outcome.result.bases, outcome.result.details);
            }
            report.results.add(result);
        }
        return report;
    }

    /** 多个 frame 的内容快照，供 Controller 直接喂给导入预览。 */
    public static OnlineImportLogic.Merged mergeAll(List<String[]> dumps, int maxWeek, int nodes) {
        return parseAll(dumps, maxWeek, nodes).merged;
    }

    // ------------------------------------------------------------------

    /** URL 尾段（最多两段，去掉查询串），用于列表展示。 */
    public static String urlTail(String url) {
        if (url == null || url.isEmpty()) return "";
        String value = url;
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int hash = value.indexOf('#');
        if (hash >= 0) value = value.substring(0, hash);
        String[] parts = value.split("/");
        java.util.List<String> tail = new ArrayList<>();
        for (int i = parts.length - 1; i >= 0 && tail.size() < 2; i--) {
            if (parts[i].isEmpty()) continue;
            tail.add(0, parts[i]);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < tail.size(); i++) {
            if (i > 0) out.append('/');
            out.append(tail.get(i));
        }
        return out.toString();
    }

    static String kb(int chars) {
        if (chars < 1024) return chars + " \u5b57\u7b26";
        return (chars / 1024) + " KB";
    }

    private static String trim(String text, int limit) {
        if (text == null) return "";
        String value = text.trim();
        return value.length() <= limit ? value : value.substring(0, limit) + "\u2026";
    }

    private static String js(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '\'' || c == '"') out.append('\\').append(c);
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '<') out.append("\\x3c");
            else out.append(c);
        }
        return out.toString();
    }

    private static String unescapeJsonString(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                if (next == 'n') out.append('\n');
                else if (next == 'r') out.append('\r');
                else if (next == 't') out.append('\t');
                else if (next == 'u' && i + 4 < value.length()) {
                    try {
                        out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                        i += 4;
                    } catch (NumberFormatException ignored) {
                        out.append(next);
                    }
                } else {
                    out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int jsonInt(String json, String key, int fallback) {
        String marker = "\"" + key + "\":";
        int at = json.indexOf(marker);
        if (at < 0) return fallback;
        int i = at + marker.length();
        StringBuilder digits = new StringBuilder();
        if (i < json.length() && json.charAt(i) == '-') {
            digits.append('-');
            i++;
        }
        while (i < json.length() && Character.isDigit(json.charAt(i))) {
            digits.append(json.charAt(i));
            i++;
        }
        if (digits.length() == 0 || digits.toString().equals("-")) return fallback;
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
