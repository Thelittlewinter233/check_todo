package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 华农（新版正方 jwglxt）课表网格的定点解析器。
 *
 * <p><b>为什么需要定点解析：</b>新正方课表页 {@code /kbcx/xskbcx_cxXskbcxIndex.html}
 * 数据由 POST 返回后渲染成一张特殊结构的网格，通用启发式看不懂它：</p>
 * <ul>
 *   <li>表头是「时间段 | 节次 | 星期一..星期日」<b>9 列</b>，比通用网格（5-8 列）多一列；</li>
 *   <li>课程不是在单元格文本里换行堆叠，而是嵌在 {@code <div class="timetable_con">} 里，
 *       一个单元格可能塞多个 div（同一天同一节有两门课）；</li>
 *   <li>真正的坐标（星期、起始节）不在表头推算里，而是直接写在单元格 id 上：{@code id="3-1"}
 *       就是「星期三第 1 节」，{@code rowspan} 是连节跨度。</li>
 * </ul>
 *
 * <p><b>结构事实（来自真实页面 {@code scau.htm} 实测）：</b></p>
 * <pre>{@code
 * <table id="kbgrid_table_0" class="... timetable1">
 *   <tr><td colspan="9"><div class="timetable_title">…罗欣悦的课表…</div></td></tr>
 *   <tr><td><span class="time">时间段</span></td><td><span class="time">节次</span></td>
 *       <td><span class="time">星期一</span></td> … <td><span class="time">星期日</span></td></tr>
 *   <tr><td rowspan="5"><span class="time">上午</span></td>
 *       <td><span class="festival">1</span><span>08:00</span><br><span>08:40</span></td>
 *       <td rowspan="1" id="1-1" class="td_wrap"></td>
 *       …
 *       <td rowspan="2" id="3-1" class="td_wrap">
 *         <div class="timetable_con text-left">
 *           <u class="title1" …>【调】大学数学Ⅰ</u>          ← 调课标记
 *           <p>(1-2节)4-14周</p>                            ← 节次范围 + 周次
 *           <p>  上课地点:4203</p>                           ← 教室（带前缀）
 *           <p>卢建平</p>                                    ← 教师
 *           <p>26种子科学1-2,26林学(林学+食工双学位)1</p>     ← 班级名单
 *           <p>选课人数:100</p>                              ← 忽略
 *         </div>
 *       </td></tr>
 *   …
 * }</pre>
 *
 * <p><b>坐标推导策略（主→辅）：</b></p>
 * <ol>
 *   <li><b>主</b>：单元格 {@code id="天-节"} 直接给星期与起始节；</li>
 *   <li><b>辅</b>：跨度优先取 {@code rowspan}，其次取文本里的 {@code (N-M节)}；
 *       两者冲突时以 rowspan 为准（它是布局事实），但保留文本节次进诊断；</li>
 *   <li>没有 id 的单元格（极少数）回落到表头推断，失败则记警告而不是静默丢课。</li>
 * </ol>
 *
 * <p>本类零 Android 依赖，纯 Java 可测。</p>
 */
public final class ScauGridParser {

    /** 表格 id 前缀。一个页面可能有多张（多个 tab/学期）。 */
    private static final String TABLE_ID_PREFIX = "kbgrid_table_";
    /** 表格 class 特征。 */
    private static final String TABLE_CLASS = "timetable1";

    /** 课程块容器。 */
    private static final Pattern BLOCK = Pattern.compile(
            "<div[^>]*class\\s*=\\s*[\"']([^\"']*timetable_con[^\"']*)[\"'][^>]*>(.*?)</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 标题：新页面用 <u class="title1">（带调课标记），旧页面用 <span class="title1">。 */
    private static final Pattern TITLE = Pattern.compile(
            "<(?:u|span|a|h[1-6])[^>]*class\\s*=\\s*[\"'][^\"']*title1[^\"']*[\"'][^>]*>(.*?)</(?:u|span|a|h[1-6])>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 段落。 */
    private static final Pattern PARAGRAPH = Pattern.compile(
            "<p[^>]*>(.*?)</p>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 节次时间：<span class="festival">1</span><span>08:00</span><br><span>08:40</span> */
    private static final Pattern NODE_TIME = Pattern.compile(
            "<span[^>]*class\\s*=\\s*[\"'][^\"']*festival[^\"']*[\"'][^>]*>\\s*(\\d{1,2})\\s*</span>"
                    + "[\\s\\S]{0,80}?(\\d{1,2}:\\d{2})[\\s\\S]{0,40}?(\\d{1,2}:\\d{2})",
            Pattern.CASE_INSENSITIVE);
    /** 节次范围：(1-2节) / (第3-4节) / 3-4节 */
    private static final Pattern NODE_RANGE = Pattern.compile(
            "[(\\uff08]\\s*(?:\\u7b2c)?\\s*(\\d{1,2})\\s*[-~\\u2014]\\s*(\\d{1,2})\\s*[\\u8282\\u7bc0]?\\s*[)\\uff09]"
                    + "|(?:\\u7b2c)?\\s*(\\d{1,2})\\s*[-~\\u2014]\\s*(\\d{1,2})\\s*[\\u8282\\u7bc0]");
    /** 教室前缀，锚定行首：多行式里「上课地点:xxx」单独成行。 */
    private static final Pattern ROOM_PREFIX = Pattern.compile(
            "^\\s*(?:\\u4e0a\\u8bfe\\u5730\\u70b9|\\u5730\\u70b9|\\u6559\\u5ba4|\\u4e0a\\u8bfe\\u6559\\u5ba4|location|room)\\s*[:\\uff1a]\\s*",
            Pattern.CASE_INSENSITIVE);
    /** 同一前缀的非锚定版本：单行式里它出现在行中间，用来定位教室字段。 */
    private static final Pattern ROOM_INLINE = Pattern.compile(
            "(?:\\u4e0a\\u8bfe\\u5730\\u70b9|\\u5730\\u70b9|\\u6559\\u5ba4|\\u4e0a\\u8bfe\\u6559\\u5ba4|location|room)\\s*[:\\uff1a]\\s*",
            Pattern.CASE_INSENSITIVE);
    /** 需要忽略的行：选课人数、教学班、课程号等元数据。 */
    private static final Pattern IGNORED = Pattern.compile(
            "^\\s*(?:\\u9009\\u8bfe\\u4eba\\u6570|\\u4eba\\u6570|\\u6559\\u5b66\\u73ed|\\u8bfe\\u7a0b\\u53f7|"
                    + "\\u5b66\\u5206|\\u8bfe\\u7a0b\\u7f16\\u53f7|\\u73ed\\u7ea7\\u540d\\u79f0|count)\\s*[:\\uff1a]");
    /** 名字里的调课标记：【调】【补】等，需剥离只留纯课名。 */
    private static final Pattern MARKER = Pattern.compile(
            "[\\u3010\\[][\\u8c03\\u8865\\u505c\\u6539][\\u3011\\]]");

    private ScauGridParser() {
    }

    /** 解析结果：课程 + 从页面里读到的节次时间表（用于校准）。 */
    public static final class Outcome {
        public final HtmlTimetableParser.Result result = new HtmlTimetableParser.Result();
        /** 页面上读到的节次时间，可能为空。 */
        public final List<TimeNode> timeNodes = new ArrayList<>();
        /** 识别到的表格标题（含学期与姓名），进诊断。 */
        public String tableTitle = "";
        public int tablesMatched;
        public int blocksSeen;

        public boolean isEmpty() {
            return result.details.isEmpty();
        }
    }

    /** 快速识别：这个 HTML 里是否有华农网格。 */
    public static boolean looksLikeScau(String html) {
        if (html == null || html.isEmpty()) return false;
        String lower = html.toLowerCase(Locale.ROOT);
        if (lower.contains(TABLE_ID_PREFIX)) return true;
        return lower.contains(TABLE_CLASS) && lower.contains("timetable_con");
    }

    /**
     * 从已解析的表格结构里找华农网格。
     *
     * <p>用 {@link SimpleHtmlParser.Table#id} 与 class 判定，而不是再正则扫一遍原文——
     * 结构化解析已经处理了大小写、无引号属性、未闭合标签等容错。</p>
     */
    public static boolean isScauTable(SimpleHtmlParser.Table table, String html) {
        if (table == null) return false;
        String id = table.id.toLowerCase(Locale.ROOT);
        if (id.startsWith(TABLE_ID_PREFIX)) return true;
        // 同一页面还有 kblist_table（课程列表）等普通表格。没有 kbgrid_table_ 前缀时，
        // 必须同时满足「class 含 timetable1」与「表格里确有 timetable_con 课程块」才认，
        // 否则会把列表表格误判成网格并报出「有网格无课程块」的假警告。
        if (table.structuredRows.isEmpty()) return false;
        boolean hasTimetableClass = false;
        for (SimpleHtmlParser.Row row : table.structuredRows) {
            if (row.className.toLowerCase(Locale.ROOT).contains(TABLE_CLASS)) {
                hasTimetableClass = true;
                break;
            }
        }
        if (!hasTimetableClass) return false;
        for (SimpleHtmlParser.Row row : table.structuredRows) {
            for (SimpleHtmlParser.Cell cell : row.cells) {
                if (cell.className.toLowerCase(Locale.ROOT).contains("td_wrap")) return true;
            }
        }
        return false;
    }

    /** 入口：直接从 HTML 文本解析（HTML 导入与教务 DOM 通道都走这里）。 */
    public static Outcome parse(String html, int maxWeek, int nodes) {
        return parse(SimpleHtmlParser.tables(html), html, maxWeek, nodes);
    }

    public static Outcome parse(List<SimpleHtmlParser.Table> tables, String html,
                                int maxWeek, int nodes) {
        Outcome outcome = new Outcome();
        if (tables == null || tables.isEmpty()) return outcome;

        // 节次时间表：与课表无关但同页可得，顺手抽出来供时间表校准。
        outcome.timeNodes.addAll(parseTimeNodes(html));

        for (SimpleHtmlParser.Table table : tables) {
            if (!isScauTable(table, html)) continue;
            outcome.tablesMatched++;
            if (outcome.tableTitle.isEmpty()) {
                outcome.tableTitle = tableTitle(table);
            }
            parseTable(table, maxWeek, nodes, outcome);
        }
        if (outcome.tablesMatched > 0) {
            outcome.result.gridInfo = "华农（新正方）网格：" + outcome.tablesMatched + " 张表，"
                    + outcome.blocksSeen + " 个课程块，解析 " + outcome.result.bases.size()
                    + " 门课 / " + outcome.result.details.size() + " 条排课"
                    + (outcome.tableTitle.isEmpty() ? "" : "（" + outcome.tableTitle + "）");
        }
        return outcome;
    }

    // ------------------------------------------------------------------
    // 网格解析
    // ------------------------------------------------------------------

    private static void parseTable(SimpleHtmlParser.Table table, int maxWeek, int nodes,
                                   Outcome outcome) {
        // id -> 课程名，保证同一门课（含被【调】标记的多次出现）共用一个 baseId。
        Map<String, String> baseIds = new LinkedHashMap<>();

        for (SimpleHtmlParser.Row row : table.structuredRows) {
            for (SimpleHtmlParser.Cell cell : row.cells) {
                if (cell.text.isEmpty() && cell.id.isEmpty()) continue;
                // 华农的课程只出现在 id 形如「天-节」的 td_wrap 单元格里。
                int[] coord = coordinate(cell);
                if (coord == null) continue;

                // 结构化文本里 div 已被折叠成换行文本，标题与 <p> 都在里面；
                // 为保证「一个 div 一条排课」，这里对原始单元格文本按 timetable_con 的
                // 语义边界再切一次（结构化解析把 div 折成了 \n，标题行与段落顺序保留）。
                List<Block> blocks = splitBlocks(cell.text);
                if (blocks.isEmpty()) continue;

                for (Block block : blocks) {
                    outcome.blocksSeen++;
                    ParsedCourse course = classify(block, maxWeek);
                    if (course.name.isEmpty()) {
                        outcome.result.warnings.add("网格 " + cell.id + " 的课程块没有课程名，已跳过");
                        continue;
                    }
                    String baseId = baseIds.get(course.name);
                    if (baseId == null) {
                        baseId = "scau-" + baseIds.size();
                        baseIds.put(course.name, baseId);
                        outcome.result.bases.add(new CourseBase(baseId, course.name, 0,
                                course.room, 0.0));
                    }
                    if (course.segments.isEmpty()) {
                        // 没有周次信息：按全学期导入并记警告（宁可导入也不静默丢）。
                        outcome.result.warnings.add("课程「" + course.name + "」缺少周次，按 1-"
                                + Math.max(1, maxWeek) + " 周导入");
                        outcome.result.details.add(new CourseDetail(
                                baseId + "-" + outcome.result.details.size(), baseId,
                                coord[0], coord[1], coord[2], 1, Math.max(1, maxWeek),
                                CourseDetail.TYPE_ALL, course.room, course.teacher, 0));
                        continue;
                    }
                    for (WeekExpression.Segment segment : course.segments) {
                        outcome.result.details.add(new CourseDetail(
                                baseId + "-" + outcome.result.details.size(), baseId,
                                coord[0], coord[1], coord[2],
                                segment.start, segment.end, segment.type,
                                course.room, course.teacher, 0));
                    }
                }
            }
        }

        if (!table.structuredRows.isEmpty() && outcome.blocksSeen == 0) {
            outcome.result.warnings.add("识别到华农网格但没有任何课程块（可能是空课表）");
        }
    }

    /**
     * 单元格坐标：{@code id="天-节"} + rowspan → {星期, 起始节, 跨度}。
     * 没有可解析的 id 时返回 null（该单元格不承载课程）。
     */
    private static int[] coordinate(SimpleHtmlParser.Cell cell) {
        String id = cell.id.trim();
        if (id.isEmpty()) return null;
        int dash = -1;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '-') {
                dash = i;
                break;
            }
        }
        if (dash <= 0 || dash >= id.length() - 1) return null;
        int day = parseInt(id.substring(0, dash));
        int node = parseInt(id.substring(dash + 1));
        if (day < 1 || day > 7 || node < 1) return null;
        int span = Math.max(1, cell.rowspan);
        return new int[]{day, node, span};
    }

    // ------------------------------------------------------------------
    // 课程块切分与分类
    // ------------------------------------------------------------------

    /** 一个课程块：标题 + 若干段落。 */
    private static final class Block {
        String title = "";
        final List<String> paragraphs = new ArrayList<>();
    }

    /**
     * 把单元格文本切成若干课程块。
     *
     * <p>{@link SimpleHtmlParser} 把 {@code <div>}/{@code <p>} 都折成了换行，因此这里按行还原：
     * 一行若是「课程名」（其后紧跟节次/地点/教师行），就开启新块。判定依据是
     * <b>行内含周次或行内紧跟的行含周次</b>——标题行本身通常不含周次，但它的下一行含。</p>
     */
    private static List<Block> splitBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        if (text == null || text.isEmpty()) return blocks;

        // SimpleHtmlParser 在相邻 div 之间插入 U+0001 边界行（见其 BLOCK_BOUNDARY 注释），
        // 单个 <p> 只产生普通换行。按边界标记切段即可精确还原「一个 div 一条排课」。
        String[] chunks = text.split(java.util.regex.Pattern.quote(SimpleHtmlParser.BLOCK_BOUNDARY));
        for (String chunk : chunks) {
            Block block = new Block();
            for (String rawLine : chunk.split("\\r?\\n|\\r")) {
                String line = rawLine.replace('\u3000', ' ').trim();
                if (line.isEmpty()) continue;
                if (block.title.isEmpty()) {
                    // 段内第一行是标题；若它带「上课地点:」前缀说明这根本不是课程块，
                    // 整段丢弃（避免把表头信息当课）。
                    if (isStrongParagraph(line)) {
                        block = null;
                        break;
                    }
                    block.title = line;
                } else {
                    block.paragraphs.add(line);
                }
            }
            if (block != null && !block.title.isEmpty()) blocks.add(block);
        }
        return blocks;
    }

    /** 带明确前缀的信息行（地点/人数/学分等），无论内容像什么都不能当标题。 */
    /** 带明确前缀的信息行（地点/人数/学分等），无论内容像什么都不能当标题。 */
    private static boolean isStrongParagraph(String line) {
        if (ROOM_PREFIX.matcher(line).find()) return true;
        return IGNORED.matcher(line).find();
    }

    /**
     * 是否周次行：必须含「周」字，且能解析出至少一段合法周次。
     *
     * <p>为什么不能只靠 {@link WeekExpression#contains}：它把纯数字区间也当周次，
     * 于是班级名单「26种子科学1-2」会被读成「1-2 周」。华农（以及绝大多数教务）
     * 的周次行一定带「周」字，用它做门禁既准确又不损失真实周次。</p>
     */
    public static boolean isWeekLine(String text) {
        if (text == null) return false;
        if (!text.contains("\u5468") && !text.toLowerCase(Locale.ROOT).contains("week")
                && !text.matches("(?s).*\\d\\s*w\\b.*")) {
            return false;
        }
        return WeekExpression.contains(text);
    }

    /** 标题行特征：不以「上课地点/选课人数」等前缀开头，且不含纯数字时间串。 */
    private static boolean isTitleLike(String line) {
        if (ROOM_PREFIX.matcher(line).find()) return false;
        if (IGNORED.matcher(line).find()) return false;
        if (line.contains("\u4e0a\u8bfe\u5730\u70b9")) return false;
        if (line.matches(".*\\d{1,2}:\\d{2}\\s*[-~]\\s*\\d{1,2}:\\d{2}.*")) return false;
        return true;
    }

    private static final class ParsedCourse {
        String name = "";
        String room = "";
        String teacher = "";
        final List<WeekExpression.Segment> segments = new ArrayList<>();
    }

    /**
     * 行分类：含周次 → 周次；以「上课地点:」等前缀开头 → 教室；「选课人数:」→ 忽略；
     * 纯人名 → 教师；其余 → 班级（丢弃但不计警告，班级不进课表模型）。
     */
    private static ParsedCourse classify(Block block, int maxWeek) {
        ParsedCourse course = new ParsedCourse();
        course.name = cleanTitle(block.title);
        boolean weeksFound = false;
        for (String paragraph : block.paragraphs) {
            String line = paragraph.trim();
            if (line.isEmpty()) continue;
            // 单行式：所有信息塞在一个 <p> 里，用空格分隔。
            // 必须先拆，否则 WeekExpression 会在整行里扫出假周次
            // （如「5B304」的 5、「26种子科学1-2」的 1-2）。
            if (isSingleLineForm(line)) {
                applySingleLine(line, maxWeek, course);
                weeksFound = weeksFound || !course.segments.isEmpty();
                continue;
            }
            if (IGNORED.matcher(line).find()) continue;
            Matcher room = ROOM_PREFIX.matcher(line);
            if (room.find()) {
                String value = line.substring(room.end()).trim();
                if (!value.isEmpty() && course.room.isEmpty()) course.room = value;
                continue;
            }
            // 周次行：先剥掉节次括号，避免 (1-2节) 里的 1-2 被误当周次。
            // 华农的周次行一律带「周」字（4-14周 / 第8-8周 / 4-7周,9-14周），
            // 而班级名单「26种子科学1-2,26林学…」里的数字不带「周」——必须靠这个区分，
            // 否则班级名单会被解析成 1-2 周、1-3 周，给每门课凭空加上假排课。
            String stripped = stripNodeRange(line);
            if (isWeekLine(stripped)) {
                WeekExpression.Result weeks = WeekExpression.parse(stripped, maxWeek);
                if (!weeks.segments.isEmpty()) {
                    course.segments.addAll(weeks.segments);
                    weeksFound = true;
                }
                continue;
            }
            if (course.teacher.isEmpty() && looksLikePersonName(line)) {
                course.teacher = line;
                continue;
            }
            // 班级名单：不参与课表模型，静默丢弃（不是错误）。
        }
        return course;
    }

    // ------------------------------------------------------------------
    // 单行式支持
    // ------------------------------------------------------------------

    /**
     * 单行式判定：同一行里既有周次又有教室前缀。
     *
     * <pre>{@code
     * 4-17周  上课地点:东区田径看台2  赵燕  26种子科学2  选课人数:40
     * }</pre>
     */
    public static boolean isSingleLineForm(String line) {
        if (line == null) return false;
        if (!ROOM_INLINE.matcher(line).find()) return false;
        return findWeekSpan(line) != null;
    }

    /**
     * 从单行里切出「周次段」——从第一个周次数字起，连续吞掉由逗号连接的各段。
     *
     * <p>为什么不能直接把整行交给 {@link WeekExpression#parse}：它会扫遍全文，
     * 把「5B304」的 5、「26种子科学1-2」的 1-2 都当成周次。这里只取开头那一段，
     * 后面遇到第一个非「数字/周/逗号/单双/空格」字符就停。</p>
     *
     * @return 周次段文本（已 strip 节次括号）；没有则 null
     */
    public static String findWeekSpan(String line) {
        if (line == null) return null;
        String stripped = stripNodeRange(line);
        int start = -1;
        for (int i = 0; i < stripped.length(); i++) {
            if (Character.isDigit(stripped.charAt(i))) {
                start = i;
                break;
            }
        }
        if (start < 0) return null;
        int end = start;
        boolean sawWeekMark = false;
        while (end < stripped.length()) {
            char c = stripped.charAt(end);
            boolean digit = c >= '0' && c <= '9';
            boolean dash = c == '-' || c == '~' || c == '\u2014';
            boolean comma = c == ',' || c == '\uff0c' || c == '\u3001';
            boolean week = c == '\u5468';
            boolean marker = c == '\u5355' || c == '\u53cc';
            boolean bracket = c == '(' || c == ')' || c == '\uff08' || c == '\uff09';
            boolean space = c == ' ' || c == '\t' || c == '\u3000';
            if (!(digit || dash || comma || week || marker || bracket || space)) break;
            if (week) sawWeekMark = true;
            // 遇到「数字 + 空白」后紧跟的下一段若不是周次（例如「26种子科学」），
            // 会在下面的数字校验里被拦下；这里先按字符集推进。
            end++;
        }
        if (!sawWeekMark) return null;
        String span = stripped.substring(start, end).trim();
        // 去掉尾部悬空的逗号/空格
        while (span.endsWith(",") || span.endsWith("\uff0c") || span.endsWith(" ")) {
            span = span.substring(0, span.length() - 1).trim();
        }
        return span.isEmpty() ? null : span;
    }

    /**
     * 拆分单行式并填充课程字段：周次段 → 周次；「上课地点:」后到下一个分隔 → 教室；
     * 其后的段按人名启发 → 教师；再往后 → 班级（丢弃）；「选课人数:」→ 忽略。
     */
    /**
     * 拆分单行式并填充课程字段。
     *
     * <p><b>为什么不能按「双空格」切分：</b>{@link SimpleHtmlParser#normalizeCell} 会把
     * 连续空白压成单个空格（`[ \t\r\f]+ → " "`），原始 HTML 里的双空格分隔符到这一层
     * 已经全部变成单空格。因此只能靠<b>语义锚点</b>定位：</p>
     * <ol>
     *   <li>周次段：从第一个数字起到第一个非「数字/周/逗号/单双」字符止；</li>
     *   <li>教室：`上课地点:` 之后到下一个中文人名或已知前缀；</li>
     *   <li>教师：教室之后的第一个「像人名」的片段；</li>
     *   <li>其余（班级名单、选课人数）丢弃。</li>
     * </ol>
     */
    private static void applySingleLine(String line, int maxWeek, ParsedCourse course) {
        String weekSpan = findWeekSpan(line);
        if (weekSpan != null) {
            WeekExpression.Result weeks = WeekExpression.parse(weekSpan, maxWeek);
            course.segments.addAll(weeks.segments);
        }

        Matcher room = ROOM_INLINE.matcher(line);
        if (!room.find()) return;

        String rest = line.substring(room.end()).trim();
        // 教室名到「第一个不是教室名允许字符」为止。教室名常见形态：
        // 5B304 / DS806 / 东区田径看台2 / 教一101 / 5C403 / 主楼A301。
        // 允许：汉字、字母、数字、短横、下划线、括号、点；遇到空格即结束。
        int cut = 0;
        while (cut < rest.length()) {
            char c = rest.charAt(cut);
            if (c == ' ' || c == '\t' || c == '\u3000') break;
            cut++;
        }
        String roomValue = rest.substring(0, cut).trim();
        if (!roomValue.isEmpty() && course.room.isEmpty()) course.room = roomValue;

        // 教师：教室名之后按空格切段，跳过班级名单与人数，取第一段像人名的。
        String tail = rest.substring(cut).trim();
        for (String part : tail.split("\\s+")) {
            String value = part.trim();
            if (value.isEmpty()) continue;
            if (IGNORED.matcher(value).find()) break;
            if (looksLikePersonName(value)) {
                if (course.teacher.isEmpty()) course.teacher = value;
                break;
            }
            // 遇到班级名单（含数字/斜杠/逗号）就停，避免继续把后面的内容当教师。
            break;
        }
    }



    /**
     * 判断是否像人名：2~4 个汉字，或 2~3 个名字用逗号分隔（多教师）。
     * 排除含数字、括号、英文、常见非人名词的行。
     */
    public static boolean looksLikePersonName(String text) {
        if (text == null) return false;
        String value = text.trim();
        if (value.isEmpty()) return false;
        if (value.length() > 30) return false;
        if (value.matches(".*\\d.*")) return false;
        if (value.contains("(") || value.contains("（") || value.contains("/")) return false;
        String[] parts = value.split("[,，、]");
        if (parts.length == 0 || parts.length > 6) return false;
        for (String part : parts) {
            String name = part.trim();
            if (name.length() < 2 || name.length() > 4) return false;
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c < 0x4E00 || c > 0x9FFF) return false;
            }
        }
        return true;
    }

    /** 剥离【调】【补】等标记，只留纯课程名。 */
    public static String cleanTitle(String title) {
        if (title == null) return "";
        String value = MARKER.matcher(title).replaceAll("").trim();
        // 也可能有中文括号形式的标记，如「（调）」
        value = value.replaceAll("^[\\uff08(]\\s*[\\u8c03\\u8865\\u505c]\\s*[\\uff09)]", "").trim();
        return value;
    }

    /** 去掉节次范围修饰（(1-2节) / 第1-2节），避免被周次解析误吃。 */
    public static String stripNodeRange(String text) {
        if (text == null) return "";
        return NODE_RANGE.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------
    // 节次时间表 / 标题
    // ------------------------------------------------------------------

    /** 抽取页面上的节次时间：{@code <span class="festival">1</span><span>08:00</span><br><span>08:40</span>}。 */
    public static List<TimeNode> parseTimeNodes(String html) {
        List<TimeNode> nodes = new ArrayList<>();
        if (html == null || html.isEmpty()) return nodes;
        Matcher matcher = NODE_TIME.matcher(html);
        Map<Integer, TimeNode> byNode = new LinkedHashMap<>();
        while (matcher.find()) {
            int node = parseInt(matcher.group(1));
            String start = normalizeTime(matcher.group(2));
            String end = normalizeTime(matcher.group(3));
            if (node < 1 || start.isEmpty() || end.isEmpty()) continue;
            if (!byNode.containsKey(node)) byNode.put(node, new TimeNode(node, start, end));
        }
        nodes.addAll(byNode.values());
        return nodes;
    }

    private static String normalizeTime(String value) {
        if (value == null) return "";
        String[] parts = value.trim().split(":");
        if (parts.length != 2) return "";
        int hour = parseInt(parts[0]);
        int minute = parseInt(parts[1]);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return "";
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    private static String tableTitle(SimpleHtmlParser.Table table) {
        for (SimpleHtmlParser.Row row : table.structuredRows) {
            for (SimpleHtmlParser.Cell cell : row.cells) {
                String text = cell.text.replace('\n', ' ').trim();
                if (text.isEmpty()) continue;
                if (text.contains("课表") || text.contains("学期")) {
                    return text.length() > 60 ? text.substring(0, 60) + "…" : text;
                }
            }
        }
        return "";
    }

    private static int parseInt(String value) {
        if (value == null) return -1;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
