package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashSet;
import java.net.URL;

/**
 * 教务系统 WebView 导入的纯 Java 部分：注入脚本、提取结果 JSON 解析、
 * URL 规范化、历史记录与多次抓取合并去重。
 */
public final class OnlineImportLogic {
    public static final int HISTORY_LIMIT = 5;

    /** WebView evaluateJavascript 返回的一页课表候选。 */
    public static final class Capture {
        public final String url;
        public final String title;
        public final List<String> tables;
        public final List<String> frameUrls;
        public final List<String> resources;
        public final List<GridCell> cells;
        public final List<GeometryGridSolver.Block> geometryCourses;
        public final List<GeometryGridSolver.Mark> geometryDays;
        public final List<GeometryGridSolver.Mark> geometryPeriods;
        public final int scannedDocs;
        public final int tablesSeen;
        public final int matchedTables;
        public final int divCells;
        public final int resourceCount;
        public final int frameDepth;

        Capture(String url, String title, List<String> tables, List<String> frameUrls,
                List<String> resources, List<GridCell> cells,
                List<GeometryGridSolver.Block> geometryCourses,
                List<GeometryGridSolver.Mark> geometryDays,
                List<GeometryGridSolver.Mark> geometryPeriods,
                int scannedDocs, int tablesSeen, int matchedTables,
                int divCells, int resourceCount, int frameDepth) {
            this.url = url == null ? "" : url;
            this.title = title == null ? "" : title;
            this.tables = tables;
            this.frameUrls = frameUrls;
            this.resources = resources;
            this.cells = cells;
            this.geometryCourses = geometryCourses;
            this.geometryDays = geometryDays;
            this.geometryPeriods = geometryPeriods;
            this.scannedDocs = scannedDocs;
            this.tablesSeen = tablesSeen;
            this.matchedTables = matchedTables;
            this.divCells = divCells;
            this.resourceCount = resourceCount;
            this.frameDepth = frameDepth;
        }
    }

    /** Tracks XHR candidates that have already been fetched by automatic polling. */
    public static final class ResourceRefreshState {
        private final Set<String> attempted = new LinkedHashSet<>();

        public List<String> refresh(List<String> candidates, boolean manual, int limit) {
            if (manual) attempted.clear();
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            if (candidates != null) {
                for (String candidate : candidates) {
                    String url = normalizeUrl(candidate);
                    if (!url.isEmpty()) normalized.add(url);
                }
            }
            List<String> next = new ArrayList<>();
            for (String url : normalized) {
                if (next.size() >= limit) break;
                if (!attempted.contains(url)) {
                    next.add(url);
                    attempted.add(url);
                }
            }
            return next;
        }

        public Set<String> attempted() {
            return new LinkedHashSet<>(attempted);
        }

        public void reset() {
            attempted.clear();
        }
    }

    /**
     * 单轮 XHR 重取的总时间预算。
     *
     * <p>为什么需要：{@code ResourceRefreshState} 只限制「本轮几次」（15 次），但每次
     * {@code fetchResource} 都有 10s 连接 + 10s 读取超时，15 个不可达候选最坏会串成
     * 15×20s≈300s。提取虽然在后台线程执行、不再卡 UI，但用户看到的是「提取中…」按钮
     * 长时间不恢复，与卡死无差别。这里用一个纯逻辑的截止时间把最坏耗时钉死。</p>
     *
     * <p>纯 Java 且以毫秒时间戳注入，便于 JVM 单测覆盖边界，不依赖 System.currentTimeMillis。</p>
     */
    public static final class FetchBudget {
        private final long deadline;
        private int spent;

        /** @param startMillis 本轮起始时间；@param budgetMillis 本轮总预算，非正数视为 0。 */
        public FetchBudget(long startMillis, long budgetMillis) {
            long span = Math.max(0L, budgetMillis);
            this.deadline = startMillis + span;
        }

        /** 还能再发起一次请求吗？已耗尽则为 false。 */
        public boolean canStart(long nowMillis) {
            return nowMillis < deadline;
        }

        /** 记录一次已发起的请求。 */
        public void record() {
            spent++;
        }

        public int spent() {
            return spent;
        }

        /** 剩余预算毫秒数，最少 0。用于把单次请求超时夹到剩余额度内。 */
        public long remaining(long nowMillis) {
            long left = deadline - nowMillis;
            return left > 0 ? left : 0L;
        }
    }

    /** Network response metadata used by the diagnostics evidence pack. */
    public static final class ResourceResponse {
        public final int status;
        public final String contentType;
        public final String body;
        /** 来源 URL：进诊断与插件路由（URL 特征是新正方的识别依据之一）。 */
        public final String originUrl;

        public ResourceResponse(int status, String contentType, String body) {
            this(status, contentType, body, "");
        }

        public ResourceResponse(int status, String contentType, String body, String originUrl) {
            this.status = status;
            this.contentType = contentType == null ? "" : contentType;
            this.body = body == null ? "" : body;
            this.originUrl = originUrl == null ? "" : originUrl;
        }
    }

    /** div 网格里的一个课程格。row/column 保留页面原值，由解析器自行换算。 */
    public static final class GridCell {
        public final String text;
        public final String id;
        public final String className;
        public final int row;
        public final int column;

        public GridCell(String text, String id, String className, int row, int column) {
            this.text = safe(text);
            this.id = safe(id);
            this.className = safe(className);
            this.row = row;
            this.column = column;
        }

        private static String safe(String value) {
            return value == null ? "" : value.trim();
        }
    }

    /** 合并后的可导入课程集合。 */
    public static final class Merged {
        public final List<CourseBase> bases = new ArrayList<>();
        public final List<CourseDetail> details = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public int duplicates;

        public int courseCount() {
            return bases.size();
        }

        public int detailCount() {
            return details.size();
        }
    }

    private OnlineImportLogic() {
    }

    /**
     * 页面加载完成后注入。只返回行列数像课表、非隐藏且含「周」特征的 table outerHTML，
     * 避免把登录页、菜单、成绩等普通表格全部传回 Java。
     */
    public static String extractionScript() {
        return "(function(){var out=[],cells=[],resources={},frameUrls=[],frames=0,tables=0,matched=0,depth=0,payload=0;"
                + "var geoCourses=[],geoDays=[],geoPeriods=[];"
                + "var WEEK=/\\d+\\s*[-~]?\\s*\\d*\\s*周|\\[\\s*\\d+\\s*[-~]?\\s*\\d*\\s*节?\\s*\\]|[（(]\\s*[单双]\\s*[）)]|第\\s*\\d+\\s*[-~]?\\s*\\d*\\s*节/;"
                + "var DAYRE=/^(星期|周)[一二三四五六日]$/;"
                + "var PERRE=/^第?([一二三四五六七八九十]{1,3}|\\d{1,2})节?$/;"
                + "var TIMERE=/^\\d{1,2}:\\d{2}(-\\d{1,2}:\\d{2})?$/;"
                + "function scanGeo(doc){try{"
                + "var els=doc.querySelectorAll('body *');"
                + "for(var g=0;g<els.length&&g<3000;g++){var el=els[g];"
                + "var tag=String(el.tagName||'').toUpperCase();"
                + "if(tag==='SCRIPT'||tag==='STYLE'||tag==='NOSCRIPT'||tag==='NAV'||tag==='HEADER'||tag==='TITLE'||tag==='META'||tag==='LINK'||tag==='IFRAME'||tag==='FRAME')continue;"
                + "if(el.clientWidth<1||el.clientHeight<1)continue;"
                + "var all='';try{all=el.textContent||'';}catch(e){continue;}"
                + "if(all.length>500)continue;"
                + "var r;try{r=el.getBoundingClientRect();}catch(e){continue;}"
                + "if(!r||r.width<8||r.height<6)continue;"
                + "var t=all.trim();if(!t)continue;"
                + "if(DAYRE.test(t)){if(geoDays.length<30)geoDays.push({t:t,x:r.left+r.width/2,y:r.top+r.height/2});continue;}"
                + "if(PERRE.test(t)||TIMERE.test(t)||(t.length<=2&&/^\\d{1,2}$/.test(t))){if(geoPeriods.length<60)geoPeriods.push({t:t,x:r.left+r.width/2,y:r.top+r.height/2});continue;}"
                + "if(!WEEK.test(t)||geoCourses.length>=120)continue;"
                + "var txt='';try{txt=el.innerText||all;}catch(e){txt=all;}"
                + "var v=txt.trim();if(!v||v.length>400)continue;"
                + "var lines=v.split('\\n').map(function(s){return s.trim();}).filter(function(s){return s.length>0;});"
                + "if(lines.length<1||lines.length>6)continue;"
                + "geoCourses.push({t:txt,x:r.left+r.width/2,y:r.top+r.height/2,w:r.width,h:r.height});"
                + "}}catch(e){}}"
                + "function scan(doc,d){try{if(!doc)return;"
                + "var win=doc.defaultView;frames++;if(d>depth)depth=d;"
                + "try{if(frameUrls.length<12)frameUrls.push(doc.location.href);}catch(e){}"
                + "try{var entries=win.performance.getEntriesByType('resource');"
                + "for(var q=0;q<entries.length;q++){var en=entries[q];"
                + "if((en.initiatorType==='xmlhttprequest'||en.initiatorType==='fetch')"
                + "&&/^https?:/.test(en.name))resources[en.name]=1;}}catch(e){}"
                + "var nodes=doc.querySelectorAll('table');"
                + "tables+=nodes.length;"
                + "for(var i=0;i<nodes.length&&out.length<12;i++){var t=nodes[i];"
                + "var rows=t.querySelectorAll('tr');if(rows.length<5)continue;"
                + "var cols=0;for(var r=0;r<rows.length;r++){var c=rows[r].querySelectorAll('td,th').length;"
                + "if(c>cols)cols=c;}if(cols<5||cols>9)continue;"
                + "var text=t.textContent||'';if(text.indexOf('周')<0)continue;"
                + "try{var style=doc.defaultView.getComputedStyle(t);"
                + "if(style.display==='none'||style.visibility==='hidden')continue;}catch(e){}"
                + "var html=t.outerHTML;if(html&&html.length<500000&&payload+html.length<1500000){payload+=html.length;matched++;out.push(html);}}"
                + "var inner=doc.querySelectorAll('iframe,frame');"
                + "for(var f=0;f<inner.length&&frames<12;f++){scan(inner[f].contentDocument,d+1);}"
                + "try{var cand=doc.querySelectorAll('div,td');"
                + "for(var v=0;v<cand.length&&cells.length<200;v++){var el=cand[v];"
                + "var cls=String(el.className||''),eid=String(el.id||'');"
                + "var hit=/kbcontent|kbtable|course|glyphicon-free/i.test(cls)||/^\\d+_\\d+$/.test(eid);"
                + "var text=(el.textContent||'').replace(/\\s+/g,' ').trim();"
                + "if(!hit||!text||text.length>400||text.indexOf('周')<0)continue;"
                + "var row=0,col=0,parts=eid.split('_');"
                + "if(parts.length===2){row=parseInt(parts[0],10);col=parseInt(parts[1],10);}"
                + "else{try{var p=el.parentNode,cs=p.children;"
                + "for(var k=0;k<cs.length;k++){if(cs[k]===el){col=k;row=Array.prototype.indexOf.call(p.parentNode.children,p);break;}}}"
                + "catch(e){row=0;col=0;}}"
                + "cells.push({t:text,c:cls,i:eid,r:row,n:col});}}catch(e){}"
                + "scanGeo(doc);"
                + "}catch(e){}}scan(document,0);"
                + "return JSON.stringify({url:location.href,title:document.title,tables:out,"
                + "frameUrls:frameUrls,"
                + "cells:cells,resources:Object.keys(resources).slice(0,30),"
                + "geometry:{courses:geoCourses,days:geoDays,periods:geoPeriods},"
                + "stats:{docs:frames,tables:tables,matched:matched,divs:cells.length,"
                + "resources:Object.keys(resources).length,depth:depth,"
                + "geo:geoCourses.length,geoDays:geoDays.length,geoPeriods:geoPeriods.length}});})()";
    }

    /**
     * 按 WakeUp 反编译指引整理的家族操作提示。用户需要在正确的教务页面
     * （例如点过「检索」按钮后的课表）再触发导入，识别率才可靠。
     */
    public static String guideFor(String type, String importType) {
        String key = familyKey(type, importType);
        if (key.equals("kingosoft")) {
            return "登录后进入 主控→教学安排 或 班级课表→格式二，点教务上的检索按钮，出现课表后再点导入";
        }
        if (key.equals("zf")) {
            return "进入课表页面并选好学期；首页课表不可导入";
        }
        if (key.equals("jz")) {
            return "信息查询→学生个人课表，切换到学期课表";
        }
        if (key.equals("urp") || key.equals("urp_new")) {
            return "打开本学期课表页面";
        }
        return "打开课表页面后点导入";
    }

    private static String familyKey(String type, String importType) {
        String value = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) value = importType == null ? "" : importType.trim().toLowerCase(Locale.ROOT);
        if (value.equals("kingo_new")) return "kingosoft";
        return value;
    }

    /**
     * 解析 evaluateJavascript 的回调值。该值可能是 JSON 对象，也可能是
     * JSON.stringify 后再被 WebView 编码的 JSON 字符串，两种输入都兼容。
     */
    public static Capture parseCapture(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;
        Object value = new JsonReader(text).readTop();
        if (value instanceof String) value = new JsonReader((String) value).readTop();
        if (!(value instanceof Map)) return null;
        Map<?, ?> map = (Map<?, ?>) value;
        Object tablesValue = map.get("tables");
        List<String> tables = new ArrayList<>();
        if (tablesValue instanceof List) {
            for (Object table : (List<?>) tablesValue) {
                if (table instanceof String && !((String) table).trim().isEmpty()) tables.add((String) table);
            }
        }
        List<String> frameUrls = new ArrayList<>();
        Object frameUrlsValue = map.get("frameUrls");
        if (frameUrlsValue instanceof List) {
            for (Object frameUrl : (List<?>) frameUrlsValue) {
                if (frameUrl instanceof String && !((String) frameUrl).trim().isEmpty()) {
                    frameUrls.add(((String) frameUrl).trim());
                }
            }
        }
        List<String> resources = new ArrayList<>();
        Object resourcesValue = map.get("resources");
        if (resourcesValue instanceof List) {
            for (Object resource : (List<?>) resourcesValue) {
                if (resource instanceof String && !((String) resource).trim().isEmpty()) {
                    resources.add(((String) resource).trim());
                }
            }
        }
        List<GridCell> cells = new ArrayList<>();
        Object cellsValue = map.get("cells");
        if (cellsValue instanceof List) {
            for (Object cellValue : (List<?>) cellsValue) {
                if (!(cellValue instanceof Map)) continue;
                Map<?, ?> cell = (Map<?, ?>) cellValue;
                Object cellText = cell.get("t");
                if (!(cellText instanceof String) || ((String) cellText).trim().isEmpty()) continue;
                cells.add(new GridCell((String) cellText, string(cell.get("i")),
                        string(cell.get("c")), intValue(cell.get("r")), intValue(cell.get("n"))));
            }
        }
        Object url = map.get("url");
        Object title = map.get("title");
        Object stats = map.get("stats");
        int scannedDocs;
        int tablesSeen;
        int matchedTables;
        int divCells;
        int resourceCount;
        int frameDepth = 0;
        if (stats instanceof Map) {
            Map<?, ?> statsMap = (Map<?, ?>) stats;
            scannedDocs = intValue(statsMap.get("docs"));
            tablesSeen = intValue(statsMap.get("tables"));
            matchedTables = intValue(statsMap.get("matched"));
            divCells = intValue(statsMap.get("divs"));
            resourceCount = intValue(statsMap.get("resources"));
            frameDepth = intValue(statsMap.get("depth"));
        } else {
            scannedDocs = tables.isEmpty() ? 0 : 1;
            tablesSeen = tables.size();
            matchedTables = tables.size();
            divCells = cells.size();
            resourceCount = resources.size();
        }
        List<GeometryGridSolver.Block> geometryCourses = new ArrayList<>();
        List<GeometryGridSolver.Mark> geometryDays = new ArrayList<>();
        List<GeometryGridSolver.Mark> geometryPeriods = new ArrayList<>();
        Object geometry = map.get("geometry");
        if (geometry instanceof Map) {
            Map<?, ?> geometryMap = (Map<?, ?>) geometry;
            parseBlocks(geometryMap.get("courses"), geometryCourses);
            parseMarks(geometryMap.get("days"), geometryDays);
            parseMarks(geometryMap.get("periods"), geometryPeriods);
        }
        return new Capture(url instanceof String ? (String) url : "",
                title instanceof String ? (String) title : "", tables, frameUrls, resources, cells,
                geometryCourses, geometryDays, geometryPeriods,
                scannedDocs, tablesSeen, matchedTables, divCells, resourceCount, frameDepth);
    }

    private static void parseBlocks(Object value, List<GeometryGridSolver.Block> out) {
        if (!(value instanceof List)) return;
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> block = (Map<?, ?>) item;
            String text = string(block.get("t"));
            if (text.trim().isEmpty()) continue;
            out.add(new GeometryGridSolver.Block(text, doubleValue(block.get("x")),
                    doubleValue(block.get("y")), doubleValue(block.get("w")),
                    doubleValue(block.get("h"))));
        }
    }

    private static void parseMarks(Object value, List<GeometryGridSolver.Mark> out) {
        if (!(value instanceof List)) return;
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> mark = (Map<?, ?>) item;
            String text = string(mark.get("t"));
            if (text.trim().isEmpty()) continue;
            out.add(new GeometryGridSolver.Mark(text, doubleValue(mark.get("x")),
                    doubleValue(mark.get("y"))));
        }
    }

    private static double doubleValue(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
    private static String string(Object value) {
        return value instanceof String ? (String) value : "";
    }

    /**
     * XHR 候选过滤：只保留 http/https、与当前教务页面同站点或同一高校主域的 URL；
     * 课表关键词优先，最多 30 条。
     */
    public static List<String> resourceUrls(List<String> raw, String pageUrl) {
        String page = normalizeUrl(pageUrl);
        String pageHost = host(page);
        List<String> valid = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (raw != null) {
            for (String item : raw) {
                String url = normalizeUrl(item);
                String candidateHost = host(url);
                if (url.isEmpty() || candidateHost.isEmpty() || !seen.add(url)) continue;
                if (pageHost.isEmpty() || !sameAcademicSite(pageHost, candidateHost)) continue;
                valid.add(url);
            }
        }
        valid.sort(Comparator.comparingInt(OnlineImportLogic::resourceScore).thenComparing(url -> url));
        return valid.size() > 30 ? new ArrayList<>(valid.subList(0, 30)) : valid;
    }

    private static int resourceScore(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("kbcx") || lower.contains("xskb") || lower.contains("kbxx")) return 0;
        if (lower.contains("课表") || lower.contains("我的课表")) return 0;
        if (lower.contains("proext")) return 1;
        if (lower.contains("pk") || lower.contains("schedule") || lower.contains("timetable")) return 2;
        return 3;
    }

    public static String htmlEvidence(String html, int maxLength) {
        return escapeEvidence(truncate(compactWhitespace(html), maxLength));
    }

    public static String responsePreview(String body, int maxLength) {
        return escapeEvidence(truncate(compactWhitespace(body), maxLength));
    }

    public static String resourceEvidence(String url, int status, String contentType, String preview,
                                          String parserSummary) {
        StringBuilder out = new StringBuilder();
        out.append("URL: ").append(safeEvidence(url)).append('\n')
                .append("HTTP: ").append(status).append('\n')
                .append("Content-Type: ").append(safeEvidence(contentType)).append('\n')
                .append("Preview: ").append(safeEvidence(preview)).append('\n')
                .append("Parsers: ").append(safeEvidence(parserSummary));
        return out.toString();
    }

    public static String tableEvidence(int index, String reason, String html) {
        return "Table #" + index + "\nReason: " + safeEvidence(reason)
                + "\nHTML: " + htmlEvidence(html, 3000);
    }

    private static String compactWhitespace(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("[\\s\\u3000]+", " ");
    }

    private static String escapeEvidence(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String safeEvidence(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "";
        return maxLength <= 0 || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String host(String url) {
        try {
            if (url == null || url.isEmpty()) return "";
            return new URL(url).getHost().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean sameAcademicSite(String left, String right) {
        if (left.equals(right)) return true;
        String leftDomain = academicDomain(left);
        String rightDomain = academicDomain(right);
        return !leftDomain.isEmpty() && leftDomain.equals(rightDomain);
    }

    /** 供 {@link OnlineExtraction} 复用的同站判定（同为 xx.edu.cn 即视为同一教务站点）。 */
    static boolean sameAcademicSitePublic(String left, String right) {
        if (left == null || right == null) return false;
        return sameAcademicSite(left.toLowerCase(Locale.ROOT), right.toLowerCase(Locale.ROOT));
    }

    private static String academicDomain(String host) {
        String[] parts = host.split("\\.");
        if (parts.length <= 2) return host;
        String end = parts[parts.length - 1];
        String penultimate = parts[parts.length - 2];
        if (end.equals("cn") && (penultimate.equals("edu") || penultimate.equals("com")
                || penultimate.equals("net") || penultimate.equals("org"))) {
            int count = 3;
            return lastLabels(parts, count);
        }
        return lastLabels(parts, 2);
    }

    private static String lastLabels(String[] parts, int count) {
        if (parts.length < count) return String.join(".", parts);
        StringBuilder out = new StringBuilder();
        for (int i = parts.length - count; i < parts.length; i++) {
            if (out.length() > 0) out.append('.');
            out.append(parts[i]);
        }
        return out.toString();
    }

    private static int intValue(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        return 0;
    }

    /**
     * 补全默认 http，并只放行 http/https。用户只输域名/路径时按普通教务网址处理；
     * javascript:、file:、ftp: 等一律拒绝，避免 WebView 被导入入口当跳板。
     */
    /**
     * 把（可能是相对的）URL 解析到某个基准页之上，返回绝对 http/https URL。
     *
     * <p>真实案例需要它：外壳里 {@code frmReport} 的 src 是
     * {@code ../student/wsxk.xskcb10319.jsp?params=...}，只有相对外壳
     * {@code https://jwxt.bnuzh.edu.cn/homes.html} 解析才能得到可请求的绝对地址。
     * 同时处理 src 属性中的折行空白（浏览器忽略、Java 不会忽略）。</p>
     *
     * @return 绝对 URL；基准或输入非法时返回空串
     */
    public static String resolveUrl(String base, String input) {
        if (input == null) return "";
        String cleaned = stripWhitespace(input);
        if (cleaned.isEmpty()) return "";
        String absolute = normalizeUrl(cleaned);
        if (!absolute.isEmpty() && cleaned.contains("://")) return absolute;
        String baseUrl = normalizeUrl(base);
        if (baseUrl.isEmpty()) return absolute;
        try {
            URL resolved = new URL(new URL(baseUrl), cleaned);
            // URL 不折叠 ../：只对 path 做规整（query 里可能有 &amp; 等字符，不能整体走 toURI）。
            String path = resolved.getPath();
            String normalizedPath = normalizePath(path);
            String query = resolved.getQuery();
            StringBuilder out = new StringBuilder();
            out.append(resolved.getProtocol()).append("://").append(resolved.getAuthority())
                    .append(normalizedPath);
            if (query != null && !query.isEmpty()) out.append('?').append(query);
            return out.toString();
        } catch (Exception ignored) {
            return absolute;
        }
    }

    /** 折叠 path 里的 {@code .} 与 {@code ..} 段，保留前导斜杠与末尾斜杠语义。 */
    static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        String[] segments = path.split("/", -1);
        List<String> out = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
                continue;
            }
            out.add(segment);
        }
        StringBuilder result = new StringBuilder();
        for (String segment : out) result.append('/').append(segment);
        if (result.length() == 0) return "/";
        // 输入以 / 结尾则保留结尾斜杠。
        if (path.endsWith("/") || path.endsWith("/.") || path.endsWith("/..")) result.append('/');
        return result.toString();
    }

    public static String normalizeUrl(String input) {
        if (input == null) return "";
        String value = stripWhitespace(input);
        if (value.isEmpty()) return "";
        int schemeEnd = value.indexOf(':');
        if (schemeEnd > 0) {
            String scheme = value.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return "";
        }
        if (value.startsWith("//")) value = "http:" + value;
        if (!value.contains("://")) value = "http://" + value;
        String lower = value.toLowerCase();
        if (lower.startsWith("http://")) return value.substring(7).isEmpty() ? "" : value;
        if (lower.startsWith("https://")) return value.substring(8).isEmpty() ? "" : value;
        return "";
    }

    /**
     * 去掉 URL 里的所有空白（含换行/制表/全角空格）。
     *
     * <p>真机案例：Kingosoft 外壳里 {@code frmReport} 的 {@code src} 属性被源码折行成
     * {@code ...?params=xxx&t=\r\n\r\nN0Uz...}，浏览器会忽略这些空白，但字符串直接拿去
     * {@code new URL()} 会得到一个带换行的错误 URL，请求 404。必须在规范化阶段清掉。</p>
     */
    private static String stripWhitespace(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u3000' || c == '\f') continue;
            out.append(c);
        }
        return out.toString();
    }

    /** 新 URL 排最前，规范化后去重，最多保留 5 条。 */
    public static List<String> history(List<String> previous, String candidate) {
        String latest = normalizeUrl(candidate);
        List<String> result = new ArrayList<>();
        if (!latest.isEmpty()) result.add(latest);
        if (previous != null) {
            for (String item : previous) {
                String url = normalizeUrl(item);
                if (!url.isEmpty() && !result.contains(url) && result.size() < HISTORY_LIMIT) {
                    result.add(url);
                }
            }
        }
        return result.size() > HISTORY_LIMIT ? new ArrayList<>(result.subList(0, HISTORY_LIMIT)) : result;
    }

    /**
     * 多次抓取合并。重复口径：课程名 + 星期 + 开始/结束节次 + 周次范围 + 单双周。
 * 教室或教师缺失时用后续抓到的一条补齐，其余字段保留首次结果。
     */
    public static Merged merge(List<CourseBase> existingBases, List<CourseDetail> existingDetails,
                               List<CourseBase> incomingBases, List<CourseDetail> incomingDetails) {
        Merged merged = new Merged();
        merged.bases.addAll(copyBases(existingBases));
        merged.details.addAll(existingDetails == null ? new ArrayList<>() : existingDetails);

        Map<String, String> baseIdByName = new LinkedHashMap<>();
        for (CourseBase base : merged.bases) {
            if (base != null && !base.name.trim().isEmpty()) baseIdByName.putIfAbsent(base.name, base.id);
        }
        for (CourseBase base : incomingBases == null ? new ArrayList<CourseBase>() : incomingBases) {
            if (base == null || base.name.trim().isEmpty()) continue;
            String mapped = baseIdByName.get(base.name);
            if (mapped != null) continue;
            String id = base.id == null || base.id.isEmpty() ? "online-" + merged.bases.size() : base.id;
            baseIdByName.put(base.name, id);
            merged.bases.add(new CourseBase(id, base.name, 0, base.note, base.credit));
        }

        Map<String, CourseDetail> existingByKey = new LinkedHashMap<>();
        for (CourseDetail detail : merged.details) {
            if (detail != null) existingByKey.put(detailKey(baseName(merged.bases, detail), detail), detail);
        }
        for (CourseDetail detail : incomingDetails == null ? new ArrayList<CourseDetail>() : incomingDetails) {
            if (detail == null) continue;
            String name = baseName(incomingBases, detail);
            String key = detailKey(name, detail);
            CourseDetail old = existingByKey.get(key);
            String baseId = baseIdByName.get(name);
            if (baseId == null) {
                baseId = "online-" + merged.bases.size();
                merged.bases.add(new CourseBase(baseId, name, 0, "", 0));
                baseIdByName.put(name, baseId);
            }
            if (old == null) {
                CourseDetail next = new CourseDetail("online-" + merged.details.size(), baseId,
                        detail.day, detail.startNode, detail.step, detail.startWeek, detail.endWeek,
                        detail.type, detail.room, detail.teacher, detail.level);
                merged.details.add(next);
                existingByKey.put(key, next);
            } else {
                merged.duplicates++;
                CourseDetail next = fillMissing(old, detail);
                if (next != old) {
                    int index = merged.details.indexOf(old);
                    merged.details.set(index, next);
                    existingByKey.put(key, next);
                }
            }
        }
        return merged;
    }

    public static Merged merge(HtmlTimetableParser.Result incoming) {
        return merge(null, null, incoming == null ? null : incoming.bases,
                incoming == null ? null : incoming.details);
    }

    private static List<CourseBase> copyBases(List<CourseBase> bases) {
        List<CourseBase> result = new ArrayList<>();
        for (CourseBase base : bases == null ? new ArrayList<CourseBase>() : bases) {
            if (base != null && !base.name.trim().isEmpty()) result.add(base);
        }
        return result;
    }

    private static CourseDetail fillMissing(CourseDetail current, CourseDetail newer) {
        String room = current.room.isEmpty() ? newer.room : current.room;
        String teacher = current.teacher.isEmpty() ? newer.teacher : current.teacher;
        if (room.equals(current.room) && teacher.equals(current.teacher)) return current;
        return new CourseDetail(current.id, current.baseId, current.day, current.startNode,
                current.step, current.startWeek, current.endWeek, current.type, room, teacher, current.level);
    }

    private static String detailKey(String name, CourseDetail detail) {
        return name + "|" + detail.day + "|" + detail.startNode + "|" + detail.endNode()
                + "|" + detail.startWeek + "|" + detail.endWeek + "|" + detail.type;
    }

    private static String baseName(List<CourseBase> bases, CourseDetail detail) {
        if (bases != null) {
            for (CourseBase base : bases) {
                if (base != null && base.id != null && base.id.equals(detail.baseId)) return base.name;
            }
        }
        return "";
    }

    /**
     * 供 {@link OnlineExtraction} 复用的 JSON 读取入口：把 WebView 返回值解析成
     * Map/List/String 结构，并兼容「JSON 字符串再被包一层」的两种形态。
     * 解析失败抛出 RuntimeException，由调用方转成可读诊断。
     */
    static Object readJsonValue(String raw) {
        Object value = new JsonReader(raw).readTop();
        if (value instanceof String) value = new JsonReader((String) value).readTop();
        return value;
    }

    /** 极小 JSON 读取器：只支持对象/数组/字符串/数字/布尔/null，够 WebView 返回值使用。 */
    private static final class JsonReader {
        private final String text;
        private int position;

        JsonReader(String text) {
            this.text = text;
        }

        Object readTop() {
            Object value = readValue();
            skipWhitespace();
            if (position != text.length()) throw new IllegalArgumentException("Trailing JSON data");
            return value;
        }

        private Object readValue() {
            skipWhitespace();
            if (position >= text.length()) throw new IllegalArgumentException("Unexpected JSON end");
            char current = text.charAt(position);
            if (current == '{') return readObject();
            if (current == '[') return readArray();
            if (current == '"') return readString();
            if (current == 't') return readKeyword("true", Boolean.TRUE);
            if (current == 'f') return readKeyword("false", Boolean.FALSE);
            if (current == 'n') return readKeyword("null", null);
            return readNumber();
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (match('}')) return result;
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                result.put(key, readValue());
                skipWhitespace();
                if (match('}')) return result;
                expect(',');
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (match(']')) return result;
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (match(']')) return result;
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (position < text.length()) {
                char current = text.charAt(position++);
                if (current == '"') return out.toString();
                if (current != '\\') {
                    out.append(current);
                    continue;
                }
                if (position >= text.length()) throw new IllegalArgumentException("Unexpected escape end");
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"': case '\\': case '/':
                        out.append(escaped);
                        break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        out.append(readUnicodeEscape());
                        break;
                    default:
                        throw new IllegalArgumentException("Bad escape: \\" + escaped);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private Number readNumber() {
            int start = position;
            while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
            String value = text.substring(start, position);
            if (value.isEmpty()) throw new IllegalArgumentException("Bad JSON number");
            if (value.indexOf('.') < 0 && value.indexOf('e') < 0 && value.indexOf('E') < 0) {
                try {
                    return Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                }
            }
            return Double.parseDouble(value);
        }

        private Object readKeyword(String keyword, Object value) {
            if (!text.startsWith(keyword, position)) throw new IllegalArgumentException("Bad JSON keyword");
            position += keyword.length();
            return value;
        }

        private char readUnicodeEscape() {
            if (position + 4 > text.length()) throw new IllegalArgumentException("Bad unicode escape");
            String hex = text.substring(position, position + 4);
            position += 4;
            return (char) Integer.parseInt(hex, 16);
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (position >= text.length() || text.charAt(position) != expected) {
                throw new IllegalArgumentException("Expected " + expected);
            }
            position++;
        }

        private boolean match(char expected) {
            if (position < text.length() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }
    }
}
