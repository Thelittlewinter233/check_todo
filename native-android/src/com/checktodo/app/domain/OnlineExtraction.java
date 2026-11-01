package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「轻发现 + 按需取数据」提取架构的纯 Java 核心。
 *
 * <p>为什么重做：旧架构把「遍历全部 DOM + 采集全部表格 outerHTML + 几何扫描 + div 格子」
 * 塞进<b>单个</b> {@code evaluateJavascript} 表达式，再依赖它的返回值回传。这条路有两个结构性死穴：</p>
 * <ol>
 *   <li><b>JS 单表达式没有超时控制</b>。脚本一旦在某个 frame 的 {@code querySelectorAll('body *')}
 *       或 {@code getComputedStyle} 上卡住（合成大页面、被 CSP 拦截、死循环劫持），
 *       {@code ValueCallback} 就永不回调。UI 侧只能看到「提取中…」，诊断永远是初始的「尚未扫描」——
 *       这正是用户反馈的现象。</li>
 *   <li><b>巨型返回值不可靠</b>。数 MB 的 JSON 要经过 JS 序列化 → WebView 桥 → Java 反序列化，
 *       任何一环失败（返回 {@code null}、"null"、或转义截断）都会让整轮提取无声无息地丢掉，
 *       而旧代码对这种失败没有任何显式记录。</li>
 * </ol>
 *
 * <p>新架构把职责拆开：</p>
 * <ul>
 *   <li><b>通道 1 轻发现</b>：只返回 frame 树（url/title/可否访问 cookie），不扫元素、不取 outerHTML，
 *       毫秒级、几乎不可能卡住。</li>
 *   <li><b>通道 2 页内 fetch 代理</b>：对候选 URL 单独注入小脚本，用 {@code fetch} 取文本，
 *       再经 {@code addJavascriptInterface} <b>分块</b>回传，绕开巨型返回值。</li>
 *   <li><b>通道 3 深度扫描</b>：几何/DOM 全量扫描降级为可选，UI 明确标注较慢。</li>
 * </ul>
 *
 * <p>本类不含任何 Android 依赖，全部逻辑可在 JVM 直接测试。</p>
 */
public final class OnlineExtraction {

    /** 分块大小：256KB。足够小到不会撞上桥接/转义限制，又不至于让回调次数爆炸。 */
    public static final int CHUNK_BYTES = 256 * 1024;

    /** 单个候选响应体的拼接上限：8MB。超过即截断并记诊断，避免 OOM。 */
    public static final int MAX_BODY_CHARS = 8 * 1024 * 1024;

    private OnlineExtraction() {
    }

    // ------------------------------------------------------------------
    // 通道 1：frame 发现
    // ------------------------------------------------------------------

    /** 发现到的一个文档节点（主文档或某个 frame）。 */
    public static final class FrameNode {
        public final String url;
        public final String title;
        /** 0=主文档，1=第一层 iframe，2=第二层…… */
        public final int depth;
        /** frame 的 name/id，用于诊断；主文档为空。 */
        public final String name;
        /** 该文档是否可被脚本访问（跨域 frame 为 false）。 */
        public final boolean accessible;
        /** 该文档的 location.href 是否可读（跨域时为 false）。 */
        public final boolean hasUrl;
        /** 是否可读到 document.cookie（跨域或 httpOnly-only 时为 false）。 */
        public final boolean cookieVisible;

        public FrameNode(String url, String title, int depth, String name,
                         boolean accessible, boolean hasUrl, boolean cookieVisible) {
            this.url = url == null ? "" : url;
            this.title = title == null ? "" : title;
            this.depth = depth;
            this.name = name == null ? "" : name;
            this.accessible = accessible;
            this.hasUrl = hasUrl;
            this.cookieVisible = cookieVisible;
        }
    }

    /**
     * 通道 1 的结果：页面 URL + frame 树 + performance 资源候选。
     */
    public static final class Discovery {
        public final String pageUrl;
        public final String pageTitle;
        /** 按文档访问顺序排列，index 0 一定是主文档。 */
        public final List<FrameNode> frames;
        /** 各可访问 frame 的 xmlhttprequest/fetch 资源 URL（去重、最多 30 条）。 */
        public final List<String> resources;
        /** 脚本自身抛出的异常信息；空表示正常。 */
        public final String error;
        /** 无法访问（跨域）的 frame 数量，用于诊断「为什么没发现某页」。 */
        public final int blockedFrames;

        public Discovery(String pageUrl, String pageTitle, List<FrameNode> frames,
                         String error, int blockedFrames) {
            this(pageUrl, pageTitle, frames, new ArrayList<String>(), error, blockedFrames);
        }

        public Discovery(String pageUrl, String pageTitle, List<FrameNode> frames,
                         List<String> resources, String error, int blockedFrames) {
            this.pageUrl = pageUrl == null ? "" : pageUrl;
            this.pageTitle = pageTitle == null ? "" : pageTitle;
            this.frames = frames == null ? new ArrayList<FrameNode>() : frames;
            this.resources = resources == null ? new ArrayList<String>() : resources;
            this.error = error == null ? "" : error;
            this.blockedFrames = blockedFrames;
        }

        public List<FrameNode> accessibleFrames() {
            List<FrameNode> out = new ArrayList<>();
            for (FrameNode frame : frames) {
                if (frame.accessible && !frame.url.isEmpty()) out.add(frame);
            }
            return out;
        }
    }

    /**
     * 第一步：<b>只列 frame 路径</b>（超轻量）。
     *
     * <p><b>为什么必须与"取信息"拆开：</b>华农 WebVPN 页面会改写整页 DOM，首页还挂着
     * echarts + 多层嵌套 iframe。旧实现用一个同步巨型脚本递归遍历所有文档、并在每个文档上
     * 调 {@code performance.getEntriesByType('resource')}——这一步在真机上实测能把主线程占满
     * 数秒，直接超出 3s 预算，于是「框架发现超时」；而且一旦超时，<b>整个结果全丢</b>，
     * 用户只能看到 0 个文档。</p>
     *
     * <p>现在这一步只做：读 location / title / 子 frame 数量，逐级下钻，<b>不碰
     * performance、不碰 innerText、不碰 outerHTML</b>。结果小、快、可在 10s 内稳定返回。</p>
     */
    public static String listFramesScript() {
        return "(function(){try{var frames=[];"
                + "function read(loc){try{var h=loc.href;return h?String(h):'';}catch(e){return '';}}"
                + "function one(doc,depth,label){"
                + "var url=read(doc.location),title='',acc=!!url;"
                + "try{title=String(doc.title||'').slice(0,80);}catch(e){title='';}"
                + "frames.push({url:url,title:title,depth:depth,name:label||'',"
                + "accessible:acc,hasUrl:!!url,cookie:false});"
                + "if(depth>=4||frames.length>=16)return;"
                + "var list;try{list=doc.querySelectorAll('iframe,frame');}catch(e){return;}"
                + "for(var i=0;i<list.length;i++){if(frames.length>=16)break;"
                + "var el=list[i],label2='';try{label2=String(el.getAttribute('name')||el.id||'');}catch(e){}"
                + "var sub;try{sub=el.contentDocument;}catch(e){sub=null;}"
                + "if(!sub){frames.push({url:'',title:'',depth:depth+1,name:label2,"
                + "accessible:false,hasUrl:false,cookie:false});continue;}"
                + "one(sub,depth+1,label2);}}"
                + "one(document,0,'');"
                + "return JSON.stringify({pageUrl:frames.length?frames[0].url:'',"
                + "pageTitle:document.title||'',frames:frames,resources:[],blocked:0});"
                + "}catch(e){return JSON.stringify({pageUrl:'',pageTitle:'',frames:[],resources:[],"
                + "blocked:0,error:String(e)});}})()";
    }

    /**
     * 兼容入口：等价于 {@link #listFramesScript()}。
     *
     * <p>「提取课表」主链路仍调用它；返回值里的 {@code resources} 现为空数组——
     * 资源收集原本靠每个文档上的 {@code performance.getEntriesByType}，在 WebVPN+echarts
     * 页面上极慢，是「框架发现超时」的直接原因，已下沉到被动捕获/fetch 代理承担。</p>
     */
    public static String discoveryScript() {
        return listFramesScript();
    }

    /**
     * 解析通道 1 的返回值。
     *
     * <p>与旧 {@code parseCapture} 不同的是：任何异常形态（null、"null"、空串、Object 包围、
     * 非法 JSON）都<b>不会</b>返回 null 让调用方猜，而是返回带 {@code error} 的
     * {@link Discovery}，调用方据此把失败原因写进诊断。</p>
     */
    public static Discovery parseDiscovery(String raw) {
        if (raw == null) {
            return new Discovery("", "", new ArrayList<FrameNode>(), "回调返回 null：脚本未执行或页面尚未就绪", 0);
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return new Discovery("", "", new ArrayList<FrameNode>(), "回调返回空串：脚本执行结果为空", 0);
        }
        if (text.equals("null")) {
            return new Discovery("", "", new ArrayList<FrameNode>(),
                    "回调返回 \"null\"：脚本抛出未捕获异常或被 WebView 拒绝执行", 0);
        }
        Object value;
        try {
            value = OnlineImportLogic.readJsonValue(text);
        } catch (RuntimeException exception) {
            return new Discovery("", "", new ArrayList<FrameNode>(),
                    "返回值不是合法 JSON：" + exception.getMessage(), 0);
        }
        if (!(value instanceof Map)) {
            return new Discovery("", "", new ArrayList<FrameNode>(),
                    "返回值不是 JSON 对象", 0);
        }
        Map<?, ?> map = (Map<?, ?>) value;
        List<FrameNode> nodes = new ArrayList<>();
        Object framesValue = map.get("frames");
        if (framesValue instanceof List) {
            for (Object item : (List<?>) framesValue) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> frame = (Map<?, ?>) item;
                nodes.add(new FrameNode(str(frame.get("url")), str(frame.get("title")),
                        integer(frame.get("depth")), str(frame.get("name")),
                        bool(frame.get("accessible")), bool(frame.get("hasUrl")),
                        bool(frame.get("cookie"))));
            }
        }
        List<String> resources = new ArrayList<>();
        Object resourcesValue = map.get("resources");
        if (resourcesValue instanceof List) {
            for (Object item : (List<?>) resourcesValue) {
                if (item instanceof String && !((String) item).trim().isEmpty()) {
                    resources.add(((String) item).trim());
                }
            }
        }
        return new Discovery(str(map.get("pageUrl")), str(map.get("pageTitle")), nodes,
                resources, "", integer(map.get("blocked")));
    }

    // ------------------------------------------------------------------
    // 通道 2：页内 fetch 代理
    // ------------------------------------------------------------------

    /**
     * 候选 URL 的发现与排序：frame 树里的文档 URL 优先（最可能是课表本体），
     * 其次是 performance 资源条目。沿用旧 {@link OnlineImportLogic#resourceUrls} 的同站过滤与
     * 课表关键词排序，保证不轰炸教务系统。
     */
    public static List<String> candidates(Discovery discovery, List<String> resources, int limit) {
        String page = discovery == null ? "" : discovery.pageUrl;
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (discovery != null) {
            // frame 文档 URL 排在资源之前：真实案例里 frmReport 的 src 就是课表本体。
            for (FrameNode frame : discovery.frames) {
                if (!frame.accessible) continue;
                String url = OnlineImportLogic.resolveUrl(page, frame.url);
                if (!url.isEmpty()) ordered.add(url);
            }
        }
        List<String> resourceUrls = OnlineImportLogic.resourceUrls(resources, page);
        ordered.addAll(resourceUrls);
        // 同站过滤：frame URL 也要过一遍，避免把第三方广告 frame 拿去 fetch。
        String pageHost = hostOf(page);
        List<String> accepted = new ArrayList<>();
        for (String url : ordered) {
            String candidateHost = hostOf(url);
            if (candidateHost.isEmpty()) continue;
            if (pageHost.isEmpty() || !OnlineImportLogic.sameAcademicSitePublic(pageHost, candidateHost)) continue;
            accepted.add(url);
        }
        accepted.sort(Comparator.comparingInt(OnlineExtraction::score)
                .thenComparingInt(url -> url.equals(page) ? 1 : 0)
                .thenComparing(url -> url));
        return accepted.size() > limit ? new ArrayList<>(accepted.subList(0, limit)) : accepted;
    }

    private static int score(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        // xskcb / xskb / kbcx 是 Kingosoft 与多数国产教务的课表页特征，
        // 真实案例 wsxk.xskcb10319.jsp 必须排在 jquery.js 之前。
        if (lower.contains("xskcb") || lower.contains("xskb") || lower.contains("kbcx")
                || lower.contains("kbxx") || lower.contains("kbtable")) return 0;
        if (lower.contains("schedule") || lower.contains("timetable") || lower.contains("kebiao")) return 1;
        if (lower.contains(".js") || lower.contains(".css") || lower.contains(".png")
                || lower.contains(".jpg") || lower.contains(".gif") || lower.contains(".ico")
                || lower.contains(".woff") || lower.contains(".svg")) return 5;
        return 3;
    }

    private static String hostOf(String url) {
        try {
            if (url == null || url.isEmpty()) return "";
            String host = new java.net.URL(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 通道 2 的注入脚本：在<b>指定</b> frame 上下文里 {@code fetch} 一个 URL，
     * 把文本按 {@link #CHUNK_BYTES} 分块回传给 {@code window.__checkTodoSink}。
     *
     * <p>协议（Java 侧由 {@link ChunkAssembler} 消费）：</p>
     * <pre>
     * sink.begin(id, url)                  —— 开始一个响应
     * sink.chunk(id, seq, total, text)     —— 第 seq 块（0 起），total 为总块数
     * sink.done(id, status, length)        —— 正常结束
     * sink.fail(id, reason)                —— 失败（跨域/网络/非 2xx/超时/abort）
     * </pre>
     *
     * <p>全部回调都包在 try/catch 里：即使某个 sink 方法抛错，也不会让 fetch 链断掉而导致
     * Java 侧永远等不到 done/fail。</p>
     *
     * @param requestId 本轮请求编号，Java 侧据此把块归到对应候选
     * @param url       绝对 URL（调用方已把相对地址解析到该 frame 的基准）
     */
    public static String fetchProxyScript(String requestId, String url) {
        String id = jsString(requestId);
        String target = jsString(url);
        return "(function(){var id=" + id + ",url=" + target + ";"
                + "var sink=window.__checkTodoSink,ABORT=window.__checkTodoAbort||{};"
                + "function call(name,a,b,c,d){try{if(sink&&sink[name])sink[name].call(sink,id,a,b,c,d);}catch(e){}}"
                + "if(!sink){return;}"
                + "abortCheckSet(ABORT,id);"
                + "call('begin',url);"
                + "if(ABORT[id]){call('fail','aborted before start');return;}"
                + "try{"
                + "fetch(url,{credentials:'include',cache:'no-store',redirect:'follow'})"
                + ".then(function(res){"
                + "if(ABORT[id]){call('fail','aborted after response');return null;}"
                + "if(!res.ok){call('fail','HTTP '+res.status);return null;}"
                + "return res.text().then(function(text){"
                + "if(ABORT[id]){call('fail','aborted while reading');return;}"
                + "var size=" + CHUNK_BYTES + ",total=Math.ceil(text.length/size)||1;"
                + "for(var i=0;i<total;i++){"
                + "if(ABORT[id]){call('fail','aborted during chunk '+i);return;}"
                + "try{call('chunk',i,total,text.substring(i*size,(i+1)*size));}"
                + "catch(e){call('fail','chunk '+i+' threw: '+e);return;}}"
                + "call('done',String(res.status),text.length);});"
                + "}).catch(function(e){call('fail','fetch rejected: '+(e&&e.message?e.message:e));});"
                + "}catch(e){call('fail','fetch threw: '+(e&&e.message?e.message:e));}"
                + "function abortCheckSet(a,k){try{a[k]=a[k]||false;}catch(e){}}"
                + "})()";
    }

    /** 取消标志脚本：把 {@code __checkTodoAbort[id]} 置 true，正在跑的 fetch 会在下一个检查点退出。 */
    public static String abortScript(String requestId) {
        return "(function(){try{window.__checkTodoAbort=window.__checkTodoAbort||{};"
                + "window.__checkTodoAbort[" + jsString(requestId) + "]=true;}catch(e){}})()";
    }

    /** 全局取消：dismiss 时调用一次，让所有在途请求退出。 */
    public static String abortAllScript() {
        return "(function(){try{window.__checkTodoAbort={__all:true};"
                + "var s=window.__checkTodoSink;if(s&&s.fail)s.fail('__all','aborted: dialog closed');}catch(e){}})()";
    }

    private static String jsString(String value) {
        if (value == null) return "''";
        StringBuilder out = new StringBuilder(value.length() + 16);
        out.append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '\'': out.append("\\'"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\u2028': out.append("\\u2028"); break;
                case '\u2029': out.append("\\u2029"); break;
                case '<': out.append("\\x3c"); break;
                default: out.append(c);
            }
        }
        return out.append('\'').toString();
    }

    // ------------------------------------------------------------------
    // 通道 2：分块拼装（Java 侧）
    // ------------------------------------------------------------------

    /** 一个候选的拼装状态。 */
    public static final class Assembled {
        public final String requestId;
        public final String url;
        public final StringBuilder body = new StringBuilder();
        public int totalChunks;
        public int receivedChunks;
        public boolean done;
        public boolean failed;
        public String error = "";
        public int status;
        public int declaredLength;
        public boolean truncated;

        Assembled(String requestId, String url) {
            this.requestId = requestId;
            this.url = url == null ? "" : url;
        }

        public String bodyText() {
            return body.toString();
        }

        public boolean complete() {
            return done && !failed;
        }
    }

    /**
     * 分块拼装器：把 {@code sink.begin/chunk/done/fail} 回调还原成完整响应体。
     *
     * <p>关键设计：<b>按 requestId 分别累积</b>，因此多个候选可以乱序、甚至并发回传。
     * 块按 seq 放入 {@code parts[]}，只有候选真的完成时才按序拼接——避免乱序到达时拼错。
     * 任何一块缺失都会在 {@link #done} 时被识别为不完整，而不是悄悄返回半截 HTML。</p>
     *
     * <p>非线程安全：调用方保证同一实例只在一个后台线程上被调用（WebView 的
     * JavascriptInterface 回调实际在 WebView 线程，需由调用方串行投递到后台线程）。</p>
     */
    public static final class ChunkAssembler {
        private final Map<String, State> states = new java.util.LinkedHashMap<>();

        private static final class State {
            final String url;
            String[] parts;
            int total;
            int received;
            boolean done;
            boolean failed;
            String error = "";
            int status;
            int declaredLength;
            boolean truncated;

            State(String url) {
                this.url = url == null ? "" : url;
            }
        }

        public void begin(String requestId, String url) {
            if (requestId == null) return;
            states.put(requestId, new State(url));
        }

        /**
         * 收一块。
         *
         * @return 是否接受（false = 未知 id / 超出总块数 / 已失败）
         */
        public boolean chunk(String requestId, int seq, int total, String text) {
            State state = states.get(requestId);
            if (state == null || state.failed) return false;
            if (total <= 0) return false;
            if (seq < 0 || seq >= total) return false;
            if (state.parts == null) {
                // 防御异常 total：不得超过「8MB / 256KB」的量级。
                int cap = MAX_BODY_CHARS / CHUNK_BYTES + 8;
                state.parts = new String[Math.min(total, cap)];
                state.total = Math.min(total, cap);
                if (total > cap) state.truncated = true;
            }
            if (seq >= state.parts.length) return false;
            if (state.parts[seq] == null) {
                state.parts[seq] = text == null ? "" : text;
                state.received++;
            }
            return true;
        }

        public void done(String requestId, String status, int length) {
            State state = states.get(requestId);
            if (state == null) return;
            state.done = true;
            state.status = parseInt(status);
            state.declaredLength = length;
            if (state.parts == null) {
                state.failed = true;
                state.error = "没有任何数据块回传";
                return;
            }
            if (state.received < state.total) {
                state.failed = true;
                state.error = "数据块不完整：" + state.received + "/" + state.total;
            }
        }

        public void fail(String requestId, String reason) {
            State state = states.get(requestId);
            if (state == null) return;
            state.failed = true;
            state.error = reason == null ? "未知失败" : reason;
        }

        public boolean isDone(String requestId) {
            State state = states.get(requestId);
            return state != null && (state.done || state.failed);
        }

        public boolean hasFailed(String requestId) {
            State state = states.get(requestId);
            return state != null && state.failed;
        }

        public String errorOf(String requestId) {
            State state = states.get(requestId);
            return state == null ? "" : state.error;
        }

        /** 取出拼装结果；未完成时返回的对象 {@code complete()==false}。 */
        public Assembled result(String requestId) {
            State state = states.get(requestId);
            if (state == null) return new Assembled(requestId, "");
            Assembled out = new Assembled(requestId, state.url);
            out.totalChunks = state.total;
            out.receivedChunks = state.received;
            out.done = state.done;
            out.failed = state.failed;
            out.error = state.error;
            out.status = state.status;
            out.declaredLength = state.declaredLength;
            out.truncated = state.truncated;
            if (state.parts != null && !state.failed) {
                for (String part : state.parts) {
                    if (out.body.length() >= MAX_BODY_CHARS) {
                        out.truncated = true;
                        break;
                    }
                    if (part != null) out.body.append(part);
                }
            }
            return out;
        }

        public Set<String> pending() {
            Set<String> out = new HashSet<>();
            for (Map.Entry<String, State> entry : states.entrySet()) {
                if (!entry.getValue().done && !entry.getValue().failed) out.add(entry.getKey());
            }
            return out;
        }

        private static int parseInt(String value) {
            if (value == null) return 0;
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
    }

    // ------------------------------------------------------------------
    // 通道级超时预算
    // ------------------------------------------------------------------

    /**
     * 全流程硬超时：把「提取中…永不结束」变成「最多 30s 一定结束」。
     *
     * <p>与 {@link OnlineImportLogic.FetchBudget} 的分工：FetchBudget 管<b>一轮 HTTP 抓取</b>的次数与
     * 时长；本类管<b>整条提取流水线</b>的阶段划分与总上限，并额外支持 {@link #cancel()}
     * （dismiss 时立即让所有阶段判定为超时）。</p>
     *
     * <p>纯 Java、时间戳注入，便于 JVM 测试边界。</p>
     */
    public static final class ExtractionDeadline {
        public static final String PHASE_DISCOVER = "发现";
        public static final String PHASE_FETCH = "取数据";
        public static final String PHASE_PARSE = "解析";
        public static final String PHASE_DONE = "完成";
        public static final String PHASE_CANCELLED = "已取消";
        public static final String PHASE_TIMEOUT = "超时";

        private final long startMillis;
        private final long budgetMillis;
        private long deadline;
        private volatile boolean cancelled;
        private String phase = PHASE_DISCOVER;
        private int candidatesSeen;
        private int candidatesOk;

        public ExtractionDeadline(long startMillis, long budgetMillis) {
            this.startMillis = startMillis;
            this.budgetMillis = Math.max(0L, budgetMillis);
            this.deadline = startMillis + this.budgetMillis;
        }

        public void enterPhase(String next, long nowMillis) {
            this.phase = next == null ? phase : next;
        }

        public String phase() {
            return cancelled ? PHASE_CANCELLED : phase;
        }

        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public boolean expired(long nowMillis) {
            return cancelled || nowMillis >= deadline;
        }

        /** 还能继续做下一件事吗？超时或取消都返回 false。 */
        public boolean canContinue(long nowMillis) {
            return !expired(nowMillis);
        }

        public long remaining(long nowMillis) {
            long left = deadline - nowMillis;
            return left > 0 ? left : 0L;
        }

        /** 单次操作的超时：取「剩余预算」与「单次上限」的较小值，且不小于 floorMillis。 */
        public int stepTimeout(long nowMillis, int capMillis, int floorMillis) {
            long left = remaining(nowMillis);
            if (left <= 0) return 0;
            long value = Math.min(left, Math.max(0, capMillis));
            return (int) Math.max(Math.max(0, floorMillis), value);
        }

        public void noteCandidate(boolean ok) {
            candidatesSeen++;
            if (ok) candidatesOk++;
        }

        public int candidatesSeen() {
            return candidatesSeen;
        }

        public int candidatesOk() {
            return candidatesOk;
        }

        public long elapsed(long nowMillis) {
            return Math.max(0L, nowMillis - startMillis);
        }

        public long budgetMillis() {
            return budgetMillis;
        }

        /** 结束状态一句话，直接进状态栏。 */
        public String summary(long nowMillis) {
            if (cancelled) return "提取已取消（对话框关闭）";
            if (nowMillis >= deadline) {
                return "提取超时：已扫描 " + candidatesSeen + " 候选，成功 " + candidatesOk
                        + "（" + budgetMillis / 1000 + "s 上限）";
            }
            return "提取完成：已扫描 " + candidatesSeen + " 候选，成功 " + candidatesOk
                    + "，用时 " + elapsed(nowMillis) / 1000 + "s";
        }
    }

    // ------------------------------------------------------------------
    // 诊断缓冲
    // ------------------------------------------------------------------

    /**
     * 线程安全的阶段化诊断缓冲。提取过程中每一阶段都追加一行，UI 可随时读取，
     * 因此「提取中也能点诊断看进度」，而不是像旧实现那样只在全流程结束后一次性写。
     */
    public static final class Diagnostics {
        private final StringBuilder text = new StringBuilder();
        private final long startMillis;
        private int events;

        public Diagnostics(long startMillis) {
            this.startMillis = startMillis;
        }

        public synchronized void line(long nowMillis, String message) {
            long elapsed = Math.max(0L, nowMillis - startMillis);
            text.append('[').append(elapsed / 1000).append('.').append((elapsed % 1000) / 100)
                    .append("s] ").append(message == null ? "" : message).append('\n');
            events++;
        }

        public synchronized void section(String title) {
            text.append("── ").append(title == null ? "" : title).append(" ──\n");
            events++;
        }

        public synchronized String text() {
            return text.toString();
        }

        public synchronized int events() {
            return events;
        }
    }

    private static String str(Object value) {
        return value instanceof String ? (String) value : "";
    }

    private static int integer(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        return value instanceof String && "true".equalsIgnoreCase(((String) value).trim());
    }
}
