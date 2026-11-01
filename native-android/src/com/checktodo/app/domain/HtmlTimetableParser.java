package com.checktodo.app.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 教务系统 HTML 课表识别器（零依赖）。
 *
 * <p>思路对齐 WakeUp 的通用 HTML 导入：从所有 table 里启发式挑出「课表主网格」，
 * 再把每个单元格文本解析为中间模型 Course{name, day, room, teacher, startNode,
 * endNode, startWeek, endWeek, type}，多段周次拆成多条排课。</p>
 */
public final class HtmlTimetableParser {
    public static final class Result {
        public final List<CourseBase> bases = new ArrayList<>();
        public final List<CourseDetail> details = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        /** 识别出的网格信息，用于预览与排查。 */
        public String gridInfo = "";
    }

    /** 周次范围：1-16、1~16、1-16周、1-16周(单)、1-16（双）、1-16单。 */
    private static final Pattern WEEK_RANGE = Pattern.compile(
            "(?<![0-9])(\\d{1,2})\\s*[-~]\\s*(\\d{1,2})"
                    + "(?:\\s*周?\\s*(?:[（(]\\s*(单|双)\\s*[）)]|(单|双)))?(?![0-9])");
    /** 逗号周次列表：1,3,5,7。 */
    private static final Pattern WEEK_LIST = Pattern.compile(
            "(?<![0-9])(\\d{1,2}(?:\\s*[,，、]\\s*\\d{1,2})+)(?:\\s*周)?(?![0-9])");
    /** 单周次：3周、16周(单)、16（双）。 */
    private static final Pattern WEEK_SINGLE = Pattern.compile(
            "(?<![0-9])(\\d{1,2})\\s*周?\\s*(?:[（(]\\s*(单|双)\\s*[）)]|(单|双))?(?![0-9])");
    /** 节次片段：第3-4节、3-4节、[1-3]节、[1-3]、[5]节，先剔除再找周次，避免误当周次。 */
    private static final Pattern NODE_SEGMENT = Pattern.compile(
            "(?:\\[\\s*(?:第)?\\s*\\d{1,2}\\s*(?:[-~]\\s*\\d{1,2})?\\s*节?\\s*\\]\\s*节?)"
                    + "|(?:第?\\s*\\d{1,2}\\s*[-~]\\s*\\d{1,2}\\s*节)");
    /** 方括号节次：[1-3]节、[1-3]、[5]节；优先级高于网格行号推导。 */
    private static final Pattern NODE_BRACKET = Pattern.compile(
            "\\[\\s*(?:第)?\\s*(\\d{1,2})\\s*(?:[-~]\\s*(\\d{1,2}))?\\s*节?\\s*\\]");
    private static final Pattern ROOM_KEYWORD = Pattern.compile("[楼室馆号区层街座操]");
    private static final Pattern ROOM_LIKE = Pattern.compile("\\d{3,}|[A-Za-z]\\d+[-]?\\d*");
    private static final Pattern TEACHER_LIKE =
            Pattern.compile("^[\\u4e00-\\u9fa5·]{2,4}(?:[,，、][\\u4e00-\\u9fa5·]{2,4})*$");
    private static final Pattern PURE_NUMBER = Pattern.compile("^\\d{1,2}$");

    private static final String[] DAY_CHARS = {"一", "二", "三", "四", "五", "六", "日"};
    private static final String[] DAY_EN = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};

    private HtmlTimetableParser() {
    }

    /**
     * 解析 HTML 课表。
     *
     * @param html    页面文本
     * @param maxWeek 学期总周数，用于周次校验
     * @param nodes   每天节数，超出该范围的排课跳过并记入 warnings
     */
    public static Result parse(String html, int maxWeek, int nodes) {
        Result result = new Result();
        List<SimpleHtmlParser.Table> tables = SimpleHtmlParser.tables(html);
        if (tables.isEmpty()) {
            result.errors.add("未找到课表表格：页面里没有 <table>，请确认保存的是教务课表页，或改用 CSV 导入");
            return result;
        }
        HtmlTimetableParser.Result kingo = KingoReportParser.parse(tables, maxWeek, nodes);
        if (!kingo.details.isEmpty()) return kingo;
        // 华农（新版正方 jwglxt）：9 列表头 + id="天-节" 坐标 + div.timetable_con 课程块。
        // 通用启发式看不懂这种结构，必须在它之前定点识别。
        ScauGridParser.Outcome scau = ScauGridParser.parse(tables, html, maxWeek, nodes);
        if (!scau.result.details.isEmpty()) return scau.result;
        Grid best = null;
        int bestScore = 0;
        for (SimpleHtmlParser.Table table : tables) {
            Grid grid = Grid.from(table);
            if (grid == null) continue;
            int score = grid.weekCellCount * 10 + grid.rows.size();
            if (score > bestScore) {
                bestScore = score;
                best = grid;
            }
        }
        if (best == null || best.weekCellCount < 1) {
            result.errors.add("未找到课表表格：没有识别到 5-8 列、含星期表头与周次文本的主网格，建议改用 CSV 导入");
            return result;
        }
        result.gridInfo = "识别网格：" + best.columns + " 列 × " + best.rows.size() + " 行（表头第 "
                + (best.headerRow + 1) + " 行，" + best.weekCellCount + " 个含周次单元格）";
        extractCourses(best, maxWeek, nodes, result);
        if (result.details.isEmpty() && result.errors.isEmpty()) {
            result.errors.add("识别到网格但没有解析出任何课程，请检查单元格格式，或改用 CSV 导入");
        }
        return result;
    }

    /**
     * 字节按编码解码：优先 UTF-8（含 BOM 剥离与严格校验），失败回落 GBK。
     * 教务页面常见 GBK；GBK 字节几乎不可能是合法 UTF-8，回落可靠。
     */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return decodeStrictUtf8(bytes, 3, bytes.length - 3);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        return decodeStrictUtf8(bytes, 0, bytes.length);
    }

    private static String decodeStrictUtf8(byte[] bytes, int offset, int length) {
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException ignored) {
            return new String(bytes, offset, length, Charset.forName("GBK"));
        }
    }

    // ---- 网格识别 ----

    /** 候选课表网格：列数 5-8、存在含星期表头、含周次文本。 */
    private static final class Grid {
        final List<List<String>> rows;
        final int columns;
        final int headerRow;
        final Map<Integer, Integer> dayColumns = new LinkedHashMap<>();
        final Integer nodeColumn;
        final int weekCellCount;

        private Grid(List<List<String>> rows, int columns, int headerRow,
                     Map<Integer, Integer> dayColumns, Integer nodeColumn, int weekCellCount) {
            this.rows = rows;
            this.columns = columns;
            this.headerRow = headerRow;
            this.dayColumns.putAll(dayColumns);
            this.nodeColumn = nodeColumn;
            this.weekCellCount = weekCellCount;
        }

        static Grid from(SimpleHtmlParser.Table table) {
            if (table.rows.size() < 3) return null;
            int columns = table.columns();
            if (columns < 5 || columns > 8) return null;
            int headerRow = findHeaderRow(table.rows);
            if (headerRow < 0) return null;
            Map<Integer, Integer> dayColumns = new LinkedHashMap<>();
            for (int c = 0; c < table.rows.get(headerRow).size(); c++) {
                int day = parseDayHeader(table.rows.get(headerRow).get(c));
                if (day >= 1 && day <= 7 && !dayColumns.containsValue(day)) {
                    dayColumns.put(c, day);
                }
            }
            if (dayColumns.size() < 3) return null;
            int weekCellCount = 0;
            for (int r = headerRow + 1; r < table.rows.size(); r++) {
                for (int c : dayColumns.keySet()) {
                    String text = cell(table.rows, r, c);
                    if (!text.isEmpty() && containsWeek(text)) weekCellCount++;
                }
            }
            if (weekCellCount < 1) return null;
            Integer nodeColumn = findNodeColumn(table.rows, headerRow, dayColumns);
            return new Grid(table.rows, columns, headerRow, dayColumns, nodeColumn, weekCellCount);
        }

        int nodeOf(int row) {
            if (nodeColumn != null) {
                String value = cell(rows, row, nodeColumn);
                if (PURE_NUMBER.matcher(value).matches()) return Integer.parseInt(value);
            }
            return row - headerRow;
        }
    }

    private static int findHeaderRow(List<List<String>> rows) {
        int best = -1;
        int bestCount = 0;
        for (int r = 0; r < Math.min(rows.size(), 5); r++) {
            int count = 0;
            for (String cellText : rows.get(r)) {
                if (parseDayHeader(cellText) >= 1) count++;
            }
            if (count > bestCount) {
                bestCount = count;
                best = r;
            }
        }
        return bestCount >= 3 ? best : -1;
    }

    /** 星期表头识别：周一/星期一/Monday/数字 1-7。 */
    private static int parseDayHeader(String text) {
        if (text == null) return 0;
        String value = text.replace(" ", "").replace("\u00A0", "").trim();
        if (value.isEmpty()) return 0;
        String lower = value.toLowerCase(Locale.ROOT);
        for (int i = 0; i < DAY_EN.length; i++) {
            if (lower.startsWith(DAY_EN[i])) return i + 1;
        }
        String dayPart = value;
        if (dayPart.startsWith("星期")) dayPart = dayPart.substring(2);
        else if (dayPart.startsWith("周")) dayPart = dayPart.substring(1);
        if (!dayPart.isEmpty()) {
            for (int i = 0; i < DAY_CHARS.length; i++) {
                if (dayPart.startsWith(DAY_CHARS[i])) return i + 1;
            }
            if (dayPart.startsWith("天")) return 7;
        }
        if (value.length() <= 4 && value.charAt(0) >= '1' && value.charAt(0) <= '7') {
            return value.charAt(0) - '0';
        }
        return 0;
    }

    /** 节次列：数据行里多数为纯数字且递增的列（通常是第 0 列）。 */
    private static Integer findNodeColumn(List<List<String>> rows, int headerRow,
                                          Map<Integer, Integer> dayColumns) {
        int dataRows = rows.size() - headerRow - 1;
        if (dataRows < 2) return null;
        int width = 0;
        for (List<String> row : rows) width = Math.max(width, row.size());
        for (int c = 0; c < width; c++) {
            if (dayColumns.containsKey(c)) continue;
            int numeric = 0;
            int ascending = 0;
            int previous = 0;
            for (int r = headerRow + 1; r < rows.size(); r++) {
                String value = cell(rows, r, c);
                if (PURE_NUMBER.matcher(value).matches()) {
                    numeric++;
                    int current = Integer.parseInt(value);
                    if (current > previous) ascending++;
                    previous = current;
                } else {
                    previous = 0;
                }
            }
            if (numeric >= dataRows * 0.6 && ascending >= numeric * 0.6) return c;
        }
        return null;
    }

    // ---- 单元格解析 ----

    private static void extractCourses(Grid grid, int maxWeek, int nodes, Result result) {
        Map<String, String> nameToBaseId = new LinkedHashMap<>();
        List<List<String>> rows = grid.rows;
        int counter = 0;
        for (int r = grid.headerRow + 1; r < rows.size(); r++) {
            for (Map.Entry<Integer, Integer> entry : grid.dayColumns.entrySet()) {
                int column = entry.getKey();
                int day = entry.getValue();
                String text = cell(rows, r, column);
                if (text.isEmpty()) continue;
                if (r > grid.headerRow + 1) {
                    String above = cell(rows, r - 1, column);
                    if (!above.isEmpty() && above.equals(text)) continue; // rowspan 延续行
                }
                int endRow = r;
                while (endRow + 1 < rows.size() && text.equals(cell(rows, endRow + 1, column))) {
                    endRow++;
                }
                int startNode = grid.nodeOf(r);
                int endNode = grid.nodeOf(endRow);
                for (List<String> block : splitBlocks(text)) {
                    counter++;
                    parseBlock(block, day, startNode, endNode, maxWeek, nodes,
                            nameToBaseId, counter, result);
                }
            }
        }
    }

    /**
     * 同一单元格多门课分块：出现下一门课名时，当前块需已具备「周次 + 教室」
     * 或至少三行（课名/周次/教师），避免把教师行误判为新课程。
     */
    private static List<List<String>> splitBlocks(String text) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        boolean currentHasWeek = false;
        boolean currentHasRoom = false;
        for (String line : text.split("\n")) {
            if (line.trim().isEmpty()) continue;
            boolean week = containsWeek(line);
            boolean room = isRoomLine(line);
            boolean nameLike = !week && !room;
            if (nameLike && currentHasWeek && (currentHasRoom || current.size() >= 3)
                    && !current.isEmpty()) {
                blocks.add(current);
                current = new ArrayList<>();
                currentHasWeek = false;
                currentHasRoom = false;
            }
            if (week) currentHasWeek = true;
            if (room) currentHasRoom = true;
            current.add(line);
        }
        if (!current.isEmpty()) blocks.add(current);
        return blocks;
    }

    private static void parseBlock(List<String> lines, int day, int startNode, int endNode,
                                   int maxWeek, int nodes, Map<String, String> nameToBaseId,
                                   int counter, Result result) {
        List<WeekSegment> weeks = new ArrayList<>();
        String room = "";
        List<String> nameCandidates = new ArrayList<>();
        for (String line : lines) {
            if (startNode > 0) {
                Matcher bracket = NODE_BRACKET.matcher(line);
                if (bracket.find()) {
                    int bracketStart = parseIntSafe(bracket.group(1));
                    int bracketEnd = bracket.group(2) == null ? bracketStart : parseIntSafe(bracket.group(2));
                    if (bracketEnd >= bracketStart && bracketEnd <= nodes) {
                        startNode = bracketStart;
                        endNode = bracketEnd;
                    }
                }
            }
            String cleaned = NODE_SEGMENT.matcher(line).replaceAll(" ");
            weeks.addAll(weekSegments(cleaned, maxWeek, result));
            if (room.isEmpty() && isRoomLine(line)) {
                room = line;
            } else if (!containsWeek(cleaned)) {
                nameCandidates.add(line);
            }
        }
        String name = nameCandidates.isEmpty() ? "" : nameCandidates.get(0).trim();
        String teacher = "";
        for (int i = 1; i < nameCandidates.size(); i++) {
            String candidate = nameCandidates.get(i).trim();
            if (TEACHER_LIKE.matcher(candidate).matches() || candidate.contains(",")
                    || candidate.contains("，")) {
                teacher = candidate;
                break;
            }
        }
        if (name.isEmpty()) {
            result.warnings.add("第 " + counter + " 个单元格：课程名为空，已跳过");
            return;
        }
        if (startNode < 1 || endNode < startNode || endNode > nodes) {
            result.warnings.add("「" + name + "」：节次 " + startNode + "-" + endNode
                    + " 超出当前时间表 1-" + nodes + "，已跳过（可在课表设置增加每天节数后重试）");
            return;
        }
        if (weeks.isEmpty()) {
            result.warnings.add("「" + name + "」：未识别到周次，按 1-" + maxWeek + " 周导入");
            weeks.add(new WeekSegment(1, maxWeek, CourseDetail.TYPE_ALL));
        }
        String baseId = nameToBaseId.get(name);
        if (baseId == null) {
            baseId = "html-course-" + (nameToBaseId.size() + 1);
            nameToBaseId.put(name, baseId);
            result.bases.add(new CourseBase(baseId, name, 0, "", 0));
        }
        for (WeekSegment week : weeks) {
            String id = baseId + "-" + day + "-" + startNode + "-" + endNode + "-"
                    + week.start + "-" + week.end + "-" + week.type + "-" + counter;
            result.details.add(new CourseDetail(id, baseId, day, startNode,
                    endNode - startNode + 1, week.start, week.end, week.type, room, teacher, 0));
        }
    }

    /** 一行里可能有多个周次段（1-8周、10-16周(双)）。 */
    private static List<WeekSegment> weekSegments(String line, int maxWeek, Result result) {
        List<WeekSegment> segments = new ArrayList<>();
        WeekExpression.Result parsed = WeekExpression.parse(line, maxWeek);
        result.warnings.addAll(parsed.warnings);
        for (WeekExpression.Segment segment : parsed.segments) {
            segments.add(new WeekSegment(segment.start, segment.end, segment.type));
        }
        return segments;
    }

    private static List<WeekSegment> legacyWeekSegments(String line, int maxWeek, Result result) {
        List<WeekSegment> segments = new ArrayList<>();
        Matcher list = WEEK_LIST.matcher(line);
        if (list.find()) {
            for (String token : list.group(1).split("[,，、]")) {
                int week = parseIntSafe(token);
                addWeek(segments, week, week, CourseDetail.TYPE_ALL, maxWeek, result);
            }
            return segments; // 周次列表已覆盖本行
        }
        Matcher range = WEEK_RANGE.matcher(line);
        int consumed = 0;
        while (range.find()) {
            if (range.start() < consumed) continue; // 防止重叠匹配
            consumed = range.end();
            int type = typeOf(range.group(3) != null ? range.group(3) : range.group(4));
            addWeek(segments, parseIntSafe(range.group(1)), parseIntSafe(range.group(2)),
                    type, maxWeek, result);
        }
        if (!segments.isEmpty()) return segments;
        Matcher single = WEEK_SINGLE.matcher(line);
        if (line.contains("周") && single.find()) {
            int type = typeOf(single.group(2) != null ? single.group(2) : single.group(3));
            addWeek(segments, parseIntSafe(single.group(1)), parseIntSafe(single.group(1)),
                    type, maxWeek, result);
        }
        return segments;
    }

    private static void addWeek(List<WeekSegment> segments, int start, int end, int type,
                                int maxWeek, Result result) {
        if (start < 1 || end < start) {
            result.warnings.add("周次 " + start + "-" + end + " 无效，已跳过");
            return;
        }
        if (end > maxWeek) {
            if (start > maxWeek) {
                result.warnings.add("周次 " + start + "-" + end + " 超出总周数 " + maxWeek + "，已跳过");
                return;
            }
            result.warnings.add("周次 " + start + "-" + end + " 超出总周数，已按 " + start + "-"
                    + maxWeek + " 周导入");
            end = maxWeek;
        }
        segments.add(new WeekSegment(start, end, type));
    }

    private static int typeOf(String marker) {
        if ("单".equals(marker)) return CourseDetail.TYPE_ODD;
        if ("双".equals(marker)) return CourseDetail.TYPE_EVEN;
        return CourseDetail.TYPE_ALL;
    }

    private static boolean containsWeek(String text) {
        if (WeekExpression.contains(text)) return true;
        return WEEK_RANGE.matcher(text).find() || WEEK_LIST.matcher(text).find()
                || (text.contains("周") && WEEK_SINGLE.matcher(text).find());
    }

    private static boolean isRoomLine(String line) {
        String value = line.trim();
        if (value.isEmpty()) return false;
        if (ROOM_KEYWORD.matcher(value).find()) return true;
        return ROOM_LIKE.matcher(value).find();
    }

    private static String cell(List<List<String>> rows, int row, int col) {
        if (row < 0 || row >= rows.size()) return "";
        List<String> cells = rows.get(row);
        if (col < 0 || col >= cells.size()) return "";
        String value = cells.get(col);
        return value == null ? "" : value;
    }

    private static int parseIntSafe(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static final class WeekSegment {
        final int start;
        final int end;
        final int type;

        WeekSegment(int start, int end, int type) {
            this.start = start;
            this.end = end;
            this.type = type;
        }
    }
}
