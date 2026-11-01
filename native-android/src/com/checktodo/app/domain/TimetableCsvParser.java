package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯 Java CSV 解析器，语义对齐 WakeUp：逗号/分号/Tab 分隔，
 * 表头可显式给出，也兼容「课程名,星期,开始节,结束节,老师,教室,周次」7 列无表头布局。
 */
public final class TimetableCsvParser {
    public static final class Result {
        public final List<CourseBase> bases = new ArrayList<>();
        public final List<CourseDetail> details = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
    }

    private TimetableCsvParser() {
    }

    public static Result parse(String csv, int maxWeek) {
        Result result = new Result();
        if (csv == null || csv.trim().isEmpty()) {
            result.errors.add("文件为空");
            return result;
        }
        String[] lines = csv.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        Map<String, String> nameToBaseId = new LinkedHashMap<>();
        int[] indices = null;
        int startLine = 0;

        if (lines.length > 0) {
            String[] first = split(lines[0]);
            int[] detected = detectHeader(first);
            if (detected != null) {
                indices = detected;
                startLine = 1;
            }
        }
        if (indices == null) {
            indices = new int[]{0, 1, 2, 3, 4, 5, 6};
        }

        for (int i = startLine; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            parseLine(line, i + 1, indices, maxWeek, nameToBaseId, result);
        }
        return result;
    }

    private static void parseLine(String line, int lineNumber, int[] indices, int maxWeek,
                                  Map<String, String> nameToBaseId, Result result) {
        String[] columns = split(line);
        if (columns.length < 7) {
            result.errors.add("第 " + lineNumber + " 行：至少需要 7 列");
            return;
        }
        String name = value(columns, indices[0]);
        String dayText = value(columns, indices[1]);
        String startText = value(columns, indices[2]);
        String endText = value(columns, indices[3]);
        String teacher = value(columns, indices[4]);
        String room = value(columns, indices[5]);
        // 部分导出的周次列表会占据多个 CSV 列（1,3,5,7），这里合并周次列之后的所有列。
        StringBuilder weekBuilder = new StringBuilder();
        for (int i = Math.max(0, indices[6]); i < columns.length; i++) {
            if (weekBuilder.length() > 0) weekBuilder.append(',');
            weekBuilder.append(columns[i].trim());
        }
        String weekText = weekBuilder.toString();

        if (name.trim().isEmpty()) {
            result.errors.add("第 " + lineNumber + " 行：课程名为空");
            return;
        }
        int day = parseDay(dayText);
        if (day < 1 || day > 7) {
            result.errors.add("第 " + lineNumber + " 行：星期无效");
            return;
        }
        int startNode = parseInt(startText);
        int endNode = parseInt(endText);
        if (startNode < 1 || startNode > 60 || endNode < 1 || endNode > 60 || endNode < startNode) {
            result.errors.add("第 " + lineNumber + " 行：节次必须在 1-60 且结束不早于开始");
            return;
        }
        List<int[]> weeks = parseWeeks(weekText, maxWeek);
        if (weeks.isEmpty()) {
            result.errors.add("第 " + lineNumber + " 行：周次无效");
            return;
        }

        String key = name.trim();
        String baseId = nameToBaseId.get(key);
        if (baseId == null) {
            baseId = "course-" + (nameToBaseId.size() + 1);
            nameToBaseId.put(key, baseId);
            result.bases.add(new CourseBase(baseId, key, 0, "", 0));
        }
        for (int[] week : weeks) {
            int type = week[2];
            String id = baseId + "-" + day + "-" + week[0] + "-" + week[1] + "-" + type + "-" + startNode;
            result.details.add(new CourseDetail(id, baseId, day, startNode, endNode - startNode + 1,
                    week[0], week[1], type, room, teacher, 0));
        }
    }

    /** 表头识别：任一列命中常见别名即按表头解析，否则按固定 7 列处理。 */
    private static int[] detectHeader(String[] columns) {
        if (columns == null || columns.length < 7) return null;
        int name = -1;
        int day = -1;
        int startNode = -1;
        int endNode = -1;
        int teacher = -1;
        int room = -1;
        int weeks = -1;
        for (int i = 0; i < columns.length; i++) {
            String value = normalize(columns[i]);
            if (name < 0 && (value.equals("课程") || value.equals("课程名") || value.equals("课程名称") || value.equals("name"))) name = i;
            else if (day < 0 && (value.equals("星期") || value.equals("周几") || value.equals("day"))) day = i;
            else if (startNode < 0 && (value.equals("开始节") || value.equals("开始节次") || value.equals("startnode"))) startNode = i;
            else if (endNode < 0 && (value.equals("结束节") || value.equals("结束节次") || value.equals("endnode"))) endNode = i;
            else if (teacher < 0 && (value.equals("老师") || value.equals("教师") || value.equals("teacher"))) teacher = i;
            else if (room < 0 && (value.equals("教室") || value.equals("地点") || value.equals("room"))) room = i;
            else if (weeks < 0 && (value.equals("周次") || value.equals("周") || value.equals("weeks"))) weeks = i;
        }
        if (name < 0 || weeks < 0 || startNode < 0 || endNode < 0) return null;
        if (day < 0) day = name + 1;
        if (teacher < 0) teacher = day + 1;
        if (room < 0) room = teacher + 1;
        return new int[]{name, day, startNode, endNode, teacher, room, weeks};
    }

    private static List<int[]> parseWeeks(String text, int maxWeek) {
        List<int[]> result = new ArrayList<>();
        WeekExpression.Result parsed = WeekExpression.parse(text, maxWeek);
        for (WeekExpression.Segment segment : parsed.segments) {
            result.add(new int[]{segment.start, segment.end, segment.type});
        }
        if (!result.isEmpty()) return result;
        if (text == null || text.trim().isEmpty()) return result;
        String normalized = text.trim().replace("周", "").replace(" ", "");
        boolean odd = normalized.contains("单");
        boolean even = normalized.contains("双");
        normalized = normalized.replace("单", "").replace("双", "");
        if (normalized.contains(",")) {
            for (String token : normalized.split(",")) {
                int week = parseInt(token);
                if (week >= 1 && week <= maxWeek) result.add(new int[]{week, week, CourseDetail.TYPE_ALL});
            }
            return result;
        }
        int start;
        int end;
        if (normalized.contains("-")) {
            String[] parts = normalized.split("-");
            if (parts.length != 2) return result;
            start = parseInt(parts[0]);
            end = parseInt(parts[1]);
        } else {
            start = parseInt(normalized);
            end = start;
        }
        if (start < 1 || end < start || end > Math.max(1, maxWeek)) return result;
        int type = odd ? CourseDetail.TYPE_ODD : even ? CourseDetail.TYPE_EVEN : CourseDetail.TYPE_ALL;
        result.add(new int[]{start, end, type});
        return result;
    }

    private static int parseDay(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        String value = text.trim();
        if (value.matches("[1-7]")) return Integer.parseInt(value);
        String normalized = value.replace("星期", "").replace("周", "");
        if (normalized.equals("一") || normalized.equalsIgnoreCase("mon")) return 1;
        if (normalized.equals("二") || normalized.equalsIgnoreCase("tue")) return 2;
        if (normalized.equals("三") || normalized.equalsIgnoreCase("wed")) return 3;
        if (normalized.equals("四") || normalized.equalsIgnoreCase("thu")) return 4;
        if (normalized.equals("五") || normalized.equalsIgnoreCase("fri")) return 5;
        if (normalized.equals("六") || normalized.equalsIgnoreCase("sat")) return 6;
        if (normalized.equals("日") || normalized.equals("天") || normalized.equalsIgnoreCase("sun")) return 7;
        return parseInt(value);
    }

    private static int parseInt(String text) {
        if (text == null) return 0;
        String value = text.trim().replaceAll("[^0-9-]", "");
        if (value.isEmpty()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String value(String[] columns, int index) {
        return index >= 0 && index < columns.length ? columns[index].trim() : "";
    }

    private static String[] split(String line) {
        return line.trim().split("[,;\\t]", -1);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replace(" ", "");
    }
}
