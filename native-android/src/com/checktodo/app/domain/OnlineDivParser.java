package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the div-grid timetables used by modern Kingosoft pages. The WebView
 * script returns each non-empty course cell with its text and grid position.
 */
public final class OnlineDivParser {
    private static final Pattern WEEK_RANGE = Pattern.compile(
            "(?<![0-9])(\\d{1,2})\\s*[-~]\\s*(\\d{1,2})\\s*周?\\s*(?:[（(]\\s*(单|双)\\s*[）)]|(单|双))?");
    private static final Pattern WEEK_LIST = Pattern.compile(
            "(?<![0-9])(\\d{1,2}(?:\\s*[,，、]\\s*\\d{1,2})+)\\s*周?");
    private static final Pattern NODE_BRACKET = Pattern.compile(
            "\\[\\s*(?:第)?\\s*(\\d{1,2})\\s*(?:[-~]\\s*(\\d{1,2}))?\\s*节?\\s*\\]");
    private static final Pattern TEACHER = Pattern.compile(
            "^[\\u4e00-\\u9fa5·]{2,4}(?:[,，、][\\u4e00-\\u9fa5·]{2,4})*$");
    private static final Pattern ROOM = Pattern.compile("[楼室馆号区层街座操]|\\d{3,}|[A-Za-z]\\d+");

    private OnlineDivParser() {
    }

    public static HtmlTimetableParser.Result parse(List<OnlineImportLogic.GridCell> cells,
                                                    int maxWeek, int nodes) {
        HtmlTimetableParser.Result result = new HtmlTimetableParser.Result();
        if (cells == null || cells.isEmpty()) return result;
        Map<String, String> idsByName = new LinkedHashMap<>();
        int counter = 0;
        for (OnlineImportLogic.GridCell cell : cells) {
            if (cell == null || cell.text.trim().isEmpty()) continue;
            Parsed parsed = parseCell(cell);
            if (parsed == null || parsed.name.isEmpty()) continue;
            int day = cell.id.matches("\\d+_\\d+") ? Math.max(1, cell.column) : cell.column + 1;
            int row = cell.id.matches("\\d+_\\d+") ? Math.max(1, cell.row) : cell.row + 1;
            if (parsed.node > 0) row = parsed.node;
            if (day < 1 || day > 7 || row < 1 || row > Math.max(1, nodes)) continue;
            String baseId = idsByName.get(parsed.name);
            if (baseId == null) {
                baseId = "div-" + idsByName.size();
                idsByName.put(parsed.name, baseId);
                result.bases.add(new CourseBase(baseId, parsed.name, 0, "", 0));
            }
            int validWeeks = 0;
            for (WeekExpression.Segment week : parsed.weeks) {
                if (week.start < 1 || week.end < week.start || week.end > Math.max(1, maxWeek)) continue;
                result.details.add(new CourseDetail("div-detail-" + counter++, baseId, day,
                        row, parsed.step, week.start, week.end, week.type,
                        parsed.room, parsed.teacher, 0));
                validWeeks++;
            }
            if (validWeeks == 0) continue;
        }
        if (result.details.isEmpty()) {
            result.warnings.add("div 网格：发现候选格子但未能解析出课程");
        } else {
            result.gridInfo = "div 网格：" + result.details.size() + " 个课程格";
        }
        return result;
    }

    private static Parsed parseCell(OnlineImportLogic.GridCell cell) {
        String[] lines = cell.text.replace("；", ";").replace(",", "，").split("[\\n;]+");
        List<String> values = new ArrayList<>();
        for (String line : lines) {
            String value = line.replace('\u00A0', ' ').trim();
            if (!value.isEmpty()) values.add(value);
        }
        if (values.isEmpty()) return null;
        Parsed parsed = new Parsed();
        parsed.name = cleanName(values.get(0));
        Matcher node = NODE_BRACKET.matcher(cell.text);
        if (node.find()) {
            parsed.node = parseInt(node.group(1), 0);
            int end = parseInt(node.group(2), parsed.node);
            if (end >= parsed.node) parsed.step = end - parsed.node + 1;
        }
        for (int i = 1; i < values.size(); i++) {
            String value = values.get(i);
            if (parsed.teacher.isEmpty() && TEACHER.matcher(value).matches()) {
                parsed.teacher = value;
            } else if (parsed.room.isEmpty() && ROOM.matcher(value).find()) {
                parsed.room = value;
            }
        }
        WeekExpression.Result weeks = WeekExpression.parse(cell.text, Integer.MAX_VALUE);
        parsed.weeks.addAll(weeks.segments);
        if (!parsed.weeks.isEmpty()) return parsed;
        return null;
    }

    private static int weekType(String left, String right) {
        String value = left == null ? (right == null ? "" : right) : left;
        if (value.equals("单")) return CourseDetail.TYPE_ODD;
        if (value.equals("双")) return CourseDetail.TYPE_EVEN;
        return CourseDetail.TYPE_ALL;
    }

    private static String cleanName(String value) {
        return value.replace("课程名称", "").replace("课程", "").trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static final class Parsed {
        String name = "";
        String room = "";
        String teacher = "";
        int node;
        int step = 1;
        final List<WeekExpression.Segment> weeks = new ArrayList<>();
    }
}
