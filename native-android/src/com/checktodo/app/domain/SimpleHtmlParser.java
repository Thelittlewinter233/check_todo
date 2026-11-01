package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 手写容错 HTML 解析器（零依赖，Jsoup 替代）。
 *
 * <p>只做导入课表需要的最小子集：提取所有顶层 {@code <table>}，
 * 把每个 table 展开为「行 x 列」的规则网格文本。容错规则：</p>
 *
 * <ul>
 *   <li>标签大小写混用、属性顺序不定、单双引号或无引号均支持</li>
 *   <li>未闭合的 td/tr/table 按下一个结构性标签或文档结尾自动收口</li>
 *   <li>{@code <br>} 转为单元格内换行，供上层按行拆分周次/教师/教室</li>
 *   <li>rowspan/colspan 展开为规则网格，空位填空串；同一单元格文本会
 *       复制到其覆盖的所有行（上层用「上下相邻同文本」识别连节课）</li>
 *   <li>{@code <script>}/{@code <style>} 内容整体跳过，避免 JS 污染单元格</li>
 *   <li>常见 HTML 实体与全角空格解码</li>
 * </ul>
 */
public final class SimpleHtmlParser {
    /** 一个 table 展开后的规则网格：rows.get(r).get(c) 为纯文本，空单元格为 ""。 */
    public static final class Table {
        public final String id;
        public final List<List<String>> rows = new ArrayList<>();
        /** 未展开的原生行：保留 tr/td 的 class/id 与 rowspan/colspan 元数据。 */
        public final List<Row> structuredRows = new ArrayList<>();

        public int columns() {
            int width = 0;
            for (List<String> row : rows) width = Math.max(width, row.size());
            return width;
        }

        Table(String id) {
            this.id = id == null ? "" : id;
        }
    }

    /** 原生 tr：className 为空表示未声明。 */
    public static final class Row {
        public final String className;
        public final List<Cell> cells = new ArrayList<>();

        Row(String className) {
            this.className = className == null ? "" : className;
        }
    }

    /** 原生 td/th：text 已经去标签、解码实体并按 <br>/<div> 折行。 */
    public static final class Cell {
        public final String text;
        public final String id;
        public final String className;
        public final int rowspan;
        public final int colspan;

        Cell(String text, String id, String className, int rowspan, int colspan) {
            this.text = text == null ? "" : text;
            this.id = id == null ? "" : id;
            this.className = className == null ? "" : className;
            this.rowspan = rowspan;
            this.colspan = colspan;
        }
    }

    private static final Map<String, String> NAMED_ENTITIES = buildEntities();
    private static final Pattern ENTITY = Pattern.compile("&#(x[0-9A-Fa-f]+|[0-9]+);|&([A-Za-z][A-Za-z0-9]*);");
    private static final Pattern ROWSPAN = Pattern.compile("\\browspan\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>/]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLSPAN = Pattern.compile("\\bcolspan\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>/]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLASS_ATTR = Pattern.compile("\\bclass\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>/]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_ATTR = Pattern.compile("\\bid\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>/]+))", Pattern.CASE_INSENSITIVE);

    /**
     * 单元格内的「块边界」标记。
     *
     * <p>教务课表常把一个单元格拆成多个语义块（华农新正方：一个 td 里放 2-3 个
     * {@code <div class="timetable_con">}）。上层需要知道块与块的边界，但普通空行
     * 会被 normalizeCell 压掉。这里插入非空白控制字符 U+0001 单独成行，
     * 它不会被 trim 去掉，也不会影响既有按行解析器（只会多看到一条无意义短行）。</p>
     */
    static final String BLOCK_BOUNDARY = "\u200B";

    private SimpleHtmlParser() {
    }

    /** 提取 HTML 中的所有顶层表格；无 table 或 HTML 为空时返回空列表。 */
    public static List<Table> tables(String html) {
        List<Table> result = new ArrayList<>();
        if (html == null || html.isEmpty()) return result;
        State state = new State();
        int i = 0;
        int length = html.length();
        while (i < length) {
            char ch = html.charAt(i);
            if (ch != '<') {
                if (state.skipUntil == null && state.tableDepth > 0 && state.cellOpen) {
                    state.cellText.append(ch);
                }
                i++;
                continue;
            }
            int close = findTagEnd(html, i);
            if (close < 0) break; // 文档在标签中间截断：按已收集内容收口
            String raw = html.substring(i + 1, close);
            if (raw.startsWith("!--")) {
                int end = html.indexOf("-->", close + 1);
                i = end >= 0 ? end + 3 : length;
                continue;
            }
            boolean closing = raw.startsWith("/");
            String name = tagName(raw);
            if (state.skipUntil != null) {
                if (closing && name.equals(state.skipUntil)) state.skipUntil = null;
                i = close + 1;
                continue;
            }
            if ((name.equals("script") || name.equals("style")) && !closing) {
                state.skipUntil = name;
                i = close + 1;
                continue;
            }
            handleTag(state, closing, name, raw);
            i = close + 1;
        }
        // 兜底：关闭所有未闭合的 cell/row/table
        if (state.tableDepth > 0) {
            finishCell(state);
            finishRow(state);
            result.add(finishTable(state));
        }
        result.addAll(state.finished);
        return result;
    }

    // ---- 标签处理 ----

    private static void handleTag(State state, boolean closing, String name, String raw) {
        if (name.equals("table")) {
            if (closing) {
                if (state.tableDepth > 0) {
                    state.tableDepth--;
                    if (state.tableDepth == 0) state.finished.add(finishTable(state));
                }
            } else {
                state.tableDepth++;
                if (state.tableDepth == 1) {
                    state.nextTableId = attrText(raw, ID_ATTR);
                    startTable(state);
                }
            }
            return;
        }
        if (state.tableDepth != 1) return; // 嵌套 table 的 tr/td 忽略，文本并入外层
        if (name.equals("tr")) {
            if (closing) {
                finishCell(state);
                finishRow(state);
            } else {
                finishCell(state);
                finishRow(state);
                state.nextRowClass = attrText(raw, CLASS_ATTR);
                startRow(state);
            }
        } else if (name.equals("td") || name.equals("th")) {
            if (closing) {
                finishCell(state);
            } else {
                finishCell(state);
                startCell(state, raw);
            }
        } else if (name.equals("br") && state.cellOpen) {
            state.cellText.append('\n');
        } else if (name.equals("div") && !closing && state.cellOpen) {
            // 用「两个换行」标记相邻 div 的块边界：上层（华农网格）靠它切分同一单元格里
            // 的多个课程块。normalizeCell 会把连续空行压成一个 \n\n，边界因此保留下来；
            // 单个 <p> 只产生一个 \n，不会被误认为块边界。
            state.cellText.append("\n" + BLOCK_BOUNDARY + "\n");
        } else if ((name.equals("p") || name.equals("li")) && !closing && state.cellOpen) {
            // <p> 折行：华农等新正方把「课程名 + 若干 <p> 信息行」塞进一个 td，
            // 不折行会让标题与周次粘连成一行，拿不到行边界。
            state.cellText.append('\n');
        }
        // 其余标签（b/span/div/font 等）原样吞掉，文本留在当前 cell
    }

    private static void startTable(State state) {
        state.rows.clear();
        state.structuredRows.clear();
        state.spans.clear();
        state.row = null;
        state.cellOpen = false;
        state.cellText.setLength(0);
    }

    private static Table finishTable(State state) {
        finishCell(state);
        finishRow(state);
        Table table = new Table(state.nextTableId);
        table.structuredRows.addAll(state.structuredRows);
        state.structuredRows.clear();
        int width = 0;
        for (List<String> row : state.rows) width = Math.max(width, row.size());
        for (List<String> row : state.rows) {
            List<String> padded = new ArrayList<>(row);
            while (padded.size() < width) padded.add("");
            for (int c = 0; c < padded.size(); c++) {
                if (padded.get(c) == null) padded.set(c, "");
            }
            table.rows.add(padded);
        }
        state.rows.clear();
        state.spans.clear();
        state.row = null;
        state.cellOpen = false;
        return table;
    }

    private static void startRow(State state) {
        state.rowIndex = state.rows.size();
        state.row = new ArrayList<>();
        state.structuredRow = new Row(state.nextRowClass);
        // 先放置之前行 rowspan 延续下来的单元格，再让本行 td 从剩余空位列填充
        for (Span span : state.spans) {
            if (state.rowIndex > span.row && state.rowIndex < span.row + span.rowspan) {
                for (int k = 0; k < span.colspan; k++) {
                    setCell(state, span.col + k, span.text);
                }
            }
        }
    }

    private static void finishRow(State state) {
        if (state.row != null) {
            state.rows.add(state.row);
            state.row = null;
        }
        if (state.structuredRow != null) {
            state.structuredRows.add(state.structuredRow);
            state.structuredRow = null;
        }
    }

    private static void startCell(State state, String rawTag) {
        state.cellOpen = true;
        state.cellText.setLength(0);
        state.cellRowspan = clamp(attrInt(rawTag, ROWSPAN, 1));
        state.cellColspan = clamp(attrInt(rawTag, COLSPAN, 1));
        state.cellId = attrText(rawTag, ID_ATTR);
        state.cellClass = attrText(rawTag, CLASS_ATTR);
    }

    private static void finishCell(State state) {
        if (!state.cellOpen || state.row == null) {
            state.cellOpen = false;
            return;
        }
        String text = normalizeCell(state.cellText.toString());
        if (state.structuredRow != null) {
            state.structuredRow.cells.add(new Cell(text, state.cellId, state.cellClass,
                    state.cellRowspan, state.cellColspan));
        }
        int col = nextFreeColumn(state);
        for (int k = 0; k < state.cellColspan; k++) {
            setCell(state, col + k, text);
        }
        if (state.cellRowspan > 1) {
            state.spans.add(new Span(state.rowIndex, col, state.cellRowspan, state.cellColspan, text));
        }
        state.cellOpen = false;
    }

    private static int nextFreeColumn(State state) {
        int col = 0;
        while (col < state.row.size() && state.row.get(col) != null) col++;
        return col;
    }

    private static void setCell(State state, int col, String text) {
        while (state.row.size() <= col) state.row.add(null);
        state.row.set(col, text);
    }

    private static String normalizeCell(String raw) {
        String decoded = decodeEntities(raw);
        decoded = decoded.replace('\u00A0', ' ').replace('\u3000', ' ');
        String[] lines = decoded.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            // 块边界标记（见 div 分支）：单独成行且不含其他字符时保留为边界行。
            // trim() 不会去掉 \u0001（非空白字符），因此能在压缩空行时存活。
            if (line.trim().equals(BLOCK_BOUNDARY)) {
                if (out.length() > 0) out.append('\n');
                out.append(BLOCK_BOUNDARY);
                continue;
            }
            String trimmed = line.replaceAll("[ \t\r\f]+", " ").trim();
            if (trimmed.isEmpty()) continue; // 丢掉纯空行，保留有效行
            if (out.length() > 0) out.append('\n');
            out.append(trimmed);
        }
        return out.toString();
    }

    private static String decodeEntities(String text) {
        if (text.indexOf('&') < 0) return text;
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            String value;
            if (matcher.group(1) != null) {
                String digits = matcher.group(1);
                int code;
                try {
                    code = digits.startsWith("x") || digits.startsWith("X")
                            ? Integer.parseInt(digits.substring(1), 16)
                            : Integer.parseInt(digits, 10);
                } catch (NumberFormatException ignored) {
                    code = -1;
                }
                value = code >= 0 && code <= 0x10FFFF ? new String(Character.toChars(code)) : matcher.group();
            } else {
                value = NAMED_ENTITIES.getOrDefault(matcher.group(2), matcher.group());
            }
            out.append(value);
            last = matcher.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    private static String tagName(String raw) {
        int start = raw.startsWith("/") ? 1 : 0;
        int end = start;
        while (end < raw.length() && Character.isLetterOrDigit(raw.charAt(end))) end++;
        return raw.substring(start, end).toLowerCase();
    }

    /** 找标签的 '>'，跳过属性里的引号内容，容忍内嵌 JS 中的比较符。 */
    private static int findTagEnd(String html, int start) {
        boolean single = false;
        boolean dbl = false;
        for (int i = start + 1; i < html.length(); i++) {
            char ch = html.charAt(i);
            if (single) {
                if (ch == '\'') single = false;
            } else if (dbl) {
                if (ch == '"') dbl = false;
            } else if (ch == '\'') {
                single = true;
            } else if (ch == '"') {
                dbl = true;
            } else if (ch == '>') {
                return i;
            }
        }
        return -1;
    }

    private static int attrInt(String rawTag, Pattern pattern, int fallback) {
        Matcher matcher = pattern.matcher(rawTag);
        if (!matcher.find()) return fallback;
        String value = matcher.group(2) != null ? matcher.group(2)
                : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String attrText(String rawTag, Pattern pattern) {
        Matcher matcher = pattern.matcher(rawTag);
        if (!matcher.find()) return "";
        String value = matcher.group(2) != null ? matcher.group(2)
                : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
        return value == null ? "" : value.trim();
    }

    private static int clamp(int value) {
        // rowspan/colspan 值异常（0 或超大）时按 1 处理，避免恶意页面撑爆网格
        return value < 1 || value > 200 ? 1 : value;
    }

    private static Map<String, String> buildEntities() {
        Map<String, String> map = new HashMap<>();
        map.put("nbsp", " ");
        map.put("amp", "&");
        map.put("lt", "<");
        map.put("gt", ">");
        map.put("quot", "\"");
        map.put("apos", "'");
        map.put("ensp", " ");
        map.put("emsp", " ");
        map.put("hellip", "…");
        map.put("middot", "·");
        map.put("bull", "•");
        map.put("deg", "°");
        map.put("mdash", "—");
        map.put("ndash", "–");
        map.put("times", "×");
        return map;
    }

    /** 一个待延续的合并单元格。 */
    private static final class Span {
        final int row;
        final int col;
        final int rowspan;
        final int colspan;
        final String text;

        Span(int row, int col, int rowspan, int colspan, String text) {
            this.row = row;
            this.col = col;
            this.rowspan = rowspan;
            this.colspan = colspan;
            this.text = text;
        }
    }

    /** 解析器状态：只维护当前 table 的行/格/合并信息。 */
    private static final class State {
        int tableDepth;
        String nextTableId = "";
        int rowIndex = -1;
        final List<List<String>> rows = new ArrayList<>();
        final List<Row> structuredRows = new ArrayList<>();
        final List<Span> spans = new ArrayList<>();
        final List<Table> finished = new ArrayList<>();
        List<String> row;
        Row structuredRow;
        String nextRowClass = "";
        boolean cellOpen;
        final StringBuilder cellText = new StringBuilder();
        int cellRowspan = 1;
        int cellColspan = 1;
        String cellId = "";
        String cellClass = "";
        String skipUntil;
    }
}
