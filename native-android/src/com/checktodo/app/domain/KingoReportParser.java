package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured parser for KINGOSOFT report grids (for example xskcb*.jsp).
 *
 * The page has a table id "mytable", a header row class "H", a td.td1 node label
 * in Chinese numerals, and td.td course cells. Each course cell contains sibling
 * div blocks with four lines: name / teacher / weeks[nodes] / room.
 */
public final class KingoReportParser {
    private static final String[] NODE_NAMES = {
            "\u4e00", "\u4e8c", "\u4e09", "\u56db", "\u4e94", "\u516d"
    };
    private static final String[] DAY_NAMES = {
            "\u661f\u671f\u4e00", "\u661f\u671f\u4e8c", "\u661f\u671f\u4e09",
            "\u661f\u671f\u56db", "\u661f\u671f\u4e94", "\u661f\u671f\u516d",
            "\u661f\u671f\u65e5"
    };

    private KingoReportParser() {
    }

    public static HtmlTimetableParser.Result parse(List<SimpleHtmlParser.Table> tables,
                                                    int maxWeek, int nodes) {
        HtmlTimetableParser.Result result = new HtmlTimetableParser.Result();
        if (tables == null || tables.isEmpty()) return result;
        for (SimpleHtmlParser.Table table : tables) {
            if (!looksLikeKingoReport(table)) continue;
            List<Integer> days = headerDays(table);
            if (days.size() < 5) continue;
            parseRows(table, days, maxWeek, nodes, result);
            if (!result.details.isEmpty()) {
                result.gridInfo = "KINGOSOFT 课程表：识别 " + days.size() + " 天，解析 "
                        + result.bases.size() + " 门课 / " + result.details.size() + " 条排课";
                return result;
            }
        }
        return result;
    }

    private static boolean looksLikeKingoReport(SimpleHtmlParser.Table table) {
        if ("mytable".equalsIgnoreCase(tableId(table))) return true;
        for (SimpleHtmlParser.Row row : table.structuredRows) {
            if ("H".equalsIgnoreCase(row.className) && headerTextCount(row) >= 5) return true;
        }
        return false;
    }

    private static String tableId(SimpleHtmlParser.Table table) {
        return table.id;
    }

    private static int headerTextCount(SimpleHtmlParser.Row row) {
        int count = 0;
        for (SimpleHtmlParser.Cell cell : row.cells) {
            if (day(cell.text) > 0) count++;
        }
        return count;
    }

    private static List<Integer> headerDays(SimpleHtmlParser.Table table) {
        List<Integer> days = new ArrayList<>();
        for (SimpleHtmlParser.Row row : table.structuredRows) {
            if (!"H".equalsIgnoreCase(row.className)) continue;
            for (SimpleHtmlParser.Cell cell : row.cells) {
                int day = day(cell.text);
                if (day > 0) days.add(day);
            }
            if (days.size() >= 5) return days;
            days.clear();
        }
        return days;
    }

    private static void parseRows(SimpleHtmlParser.Table table, List<Integer> days,
                                  int maxWeek, int nodes, HtmlTimetableParser.Result result) {
        Map<String, String> baseIds = new LinkedHashMap<>();
        int counter = 0;
        for (SimpleHtmlParser.Row row : table.structuredRows) {
            int nodeIndex = nodeIndex(row);
            if (nodeIndex < 0) continue;
            int rowNode = node(row.cells.get(nodeIndex).text);
            if (rowNode < 1) continue;
            for (int c = nodeIndex + 1; c < row.cells.size(); c++) {
                int dayIndex = c - nodeIndex - 1;
                if (dayIndex >= days.size()) break;
                int day = days.get(dayIndex);
                String text = row.cells.get(c).text;
                if (text.trim().isEmpty()) continue;
                counter = parseCell(text, day, rowNode, maxWeek, nodes, baseIds, counter, result);
            }
        }
    }

    private static int parseCell(String text, int day, int rowNode, int maxWeek, int nodes,
                                 Map<String, String> baseIds, int counter,
                                 HtmlTimetableParser.Result result) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!WeekExpression.contains(line) || WeekExpression.node(line) == null) continue;
            String name = i >= 2 ? lines[i - 2].trim() : "";
            String teacher = i >= 1 ? lines[i - 1].trim() : "";
            String room = i + 1 < lines.length ? lines[i + 1].trim() : "";
            if (name.isEmpty()) continue;
            int[] node = WeekExpression.node(line);
            int startNode = node[0];
            int endNode = Math.max(node[0], node[1]);

            String baseId = baseIds.get(name);
            if (baseId == null) {
                baseId = "kingo-course-" + (baseIds.size() + 1);
                baseIds.put(name, baseId);
                result.bases.add(new CourseBase(baseId, name, 0, "", 0));
            }
            if (startNode < 1 || endNode < startNode || endNode > Math.max(1, nodes)) {
                result.warnings.add("\u300c" + name + "\u300d：节次 " + startNode + "-" + endNode
                        + " 超出当前时间表 1-" + Math.max(1, nodes) + "，已跳过");
                continue;
            }
            WeekExpression.Result weeks = WeekExpression.parse(line, maxWeek);
            result.warnings.addAll(weeks.warnings);
            if (weeks.segments.isEmpty()) {
                result.warnings.add("\u300c" + name + "\u300d：未识别到周次，已跳过");
                continue;
            }
            for (WeekExpression.Segment week : weeks.segments) {
                String id = baseId + "-" + day + "-" + startNode + "-" + endNode + "-"
                        + week.start + "-" + week.end + "-" + week.type + "-" + counter++;
                result.details.add(new CourseDetail(id, baseId, day, startNode,
                        endNode - startNode + 1, week.start, week.end, week.type,
                        room, teacher, 0));
            }
        }
        return counter;
    }

    private static int nodeIndex(SimpleHtmlParser.Row row) {
        for (int i = 0; i < Math.min(2, row.cells.size()); i++) {
            SimpleHtmlParser.Cell cell = row.cells.get(i);
            if ("td1".equalsIgnoreCase(cell.className) && node(cell.text) > 0) return i;
        }
        return -1;
    }

    private static int node(String text) {
        String value = text == null ? "" : text.trim();
        for (int i = 0; i < NODE_NAMES.length; i++) {
            if (value.equals(NODE_NAMES[i])) return i + 1;
        }
        return 0;
    }

    private static int day(String text) {
        String value = text == null ? "" : text.trim();
        for (int i = 0; i < DAY_NAMES.length; i++) {
            if (value.startsWith(DAY_NAMES[i])) return i + 1;
        }
        if (value.length() == 1) {
            for (int i = 0; i < NODE_NAMES.length; i++) {
                if (value.equals(NODE_NAMES[i])) return i + 1;
            }
        }
        return 0;
    }
}
