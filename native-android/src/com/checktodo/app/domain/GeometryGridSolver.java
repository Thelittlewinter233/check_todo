package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layout-based timetable extraction: maps visible course blocks to weekdays
 * (nearest day-header X) and periods (row-mark Y overlap). Works without any
 * table markup, so it is the generic fallback for AJAX/div timetables.
 */
public final class GeometryGridSolver {
    /** A visible course block: x/y are rect centers in the same frame viewport. */
    public static final class Block {
        public final String text;
        public final double x;
        public final double y;
        public final double w;
        public final double h;

        public Block(String text, double x, double y, double w, double h) {
            this.text = text == null ? "" : text.trim();
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /** A day header or period mark: x/y are rect centers. */
    public static final class Mark {
        public final String text;
        public final double x;
        public final double y;

        public Mark(String text, double x, double y) {
            this.text = text == null ? "" : text.trim();
            this.x = x;
            this.y = y;
        }
    }

    private static final Pattern DAY = Pattern.compile("^(?:星期|周)([一二三四五六日])$");
    private static final Pattern SINGLE = Pattern.compile("^([一二三四五六日])$");
    private static final Pattern PERIOD_TEXT =
            Pattern.compile("^第?([一二三四五六七八九十]{1,3}|\\d{1,2})节?$");
    private static final Pattern DIGITS = Pattern.compile("^(\\d{1,2})$");
    private static final Pattern TIME = Pattern.compile("^\\d{1,2}:\\d{2}(?:-\\d{1,2}:\\d{2})?$");
    private static final Pattern TEACHER =
            Pattern.compile("^[\\u4e00-\\u9fa5·]{2,4}(?:[,，、][\\u4e00-\\u9fa5·]{2,4})*$");
    private static final Pattern ROOM = Pattern.compile("[楼室馆号区层街座操]|\\d{3,}|[A-Za-z]\\d+");

    private static final String[] DAY_CHARS = {"一", "二", "三", "四", "五", "六", "日"};

    private GeometryGridSolver() {
    }

    public static HtmlTimetableParser.Result solve(List<Block> blocks, List<Mark> dayHeaders,
                                                    List<Mark> periodMarks, int maxWeek, int nodes) {
        HtmlTimetableParser.Result result = new HtmlTimetableParser.Result();
        if (blocks == null || blocks.isEmpty()) return result;
        List<Block> leaves = distinctLeaves(blocks);

        Map<Integer, Double> columns = dayColumns(dayHeaders);
        boolean promoted = false;
        if (columns.size() < 5) {
            // Single-character marks were kept as period candidates; when the
            // page has no 星期X headers they are the day columns instead.
            Map<Integer, Double> fromSingles = singleCharColumns(periodMarks);
            if (fromSingles.size() > columns.size()) {
                columns = fromSingles;
                promoted = true;
            }
        }
        if (columns.size() < 5) {
            result.warnings.add("几何网格：未找到 5 个以上星期表头，跳过几何解算");
            return result;
        }

        List<int[]> rows = rowMarks(periodMarks, promoted);
        boolean clustered = rows.isEmpty();
        List<List<Block>> rowClusters = clustered ? clusterByY(leaves) : null;
        if (clustered && (rowClusters == null || rowClusters.size() < 2)) {
            result.warnings.add("几何网格：未找到节次标记且课程块无法聚类，跳过几何解算");
            return result;
        }

        Map<String, String> idsByName = new LinkedHashMap<>();
        int counter = 0;
        for (Block block : leaves) {
            Parsed parsed = parseText(block.text);
            if (parsed == null || parsed.name.isEmpty()) continue;
            int day = nearestDay(columns, block.x);
            int startNode;
            int step;
            if (parsed.node != null) {
                startNode = parsed.node[0];
                step = Math.max(1, parsed.node[1] - parsed.node[0] + 1);
            } else if (clustered) {
                startNode = clusterIndex(rowClusters, block) + 1;
                step = 1;
            } else {
                int[] span = rowSpan(rows, block);
                startNode = span[0];
                step = Math.max(1, span[1] - span[0] + 1);
            }
            if (day < 1 || day > 7) continue;
            if (startNode < 1 || startNode + step - 1 > Math.max(1, nodes)) {
                result.warnings.add("「" + parsed.name + "」：节次 " + startNode + "-"
                        + (startNode + step - 1) + " 超出当前时间表 1-" + Math.max(1, nodes) + "，已跳过");
                continue;
            }
            String baseId = idsByName.get(parsed.name);
            if (baseId == null) {
                baseId = "geo-" + idsByName.size();
                idsByName.put(parsed.name, baseId);
                result.bases.add(new CourseBase(baseId, parsed.name, 0, "", 0));
            }
            for (WeekExpression.Segment week : parsed.weeks) {
                if (week.start < 1 || week.end < week.start || week.end > Math.max(1, maxWeek)) continue;
                result.details.add(new CourseDetail("geo-detail-" + counter++, baseId, day,
                        startNode, step, week.start, week.end, week.type,
                        parsed.room, parsed.teacher, 0));
            }
        }
        if (!result.details.isEmpty()) {
            result.gridInfo = "几何网格：" + result.bases.size() + " 门课 / " + result.details.size()
                    + " 条排课（" + (clustered ? "节次按行聚类，置信度低" : "星期表头 + 节次标记") + "）";
        } else {
            result.warnings.add("几何网格：发现课程块但未能解算出排课");
        }
        return result;
    }

    /** Parent containers repeat their children's innerText; keep the smallest rect. */
    private static List<Block> distinctLeaves(List<Block> blocks) {
        List<Block> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparingDouble(block -> block.w * block.h));
        List<Block> kept = new ArrayList<>();
        for (Block candidate : sorted) {
            boolean containsLeaf = false;
            for (Block leaf : kept) {
                if (contains(candidate, leaf) && candidate.text.contains(leaf.text)) {
                    containsLeaf = true;
                    break;
                }
            }
            if (!containsLeaf) kept.add(candidate);
        }
        return kept;
    }

    private static boolean contains(Block outer, Block inner) {
        return Math.abs(inner.x - outer.x) < outer.w / 2
                && Math.abs(inner.y - outer.y) < outer.h / 2;
    }

    private static Map<Integer, Double> dayColumns(List<Mark> marks) {
        Map<Integer, Double> columns = new LinkedHashMap<>();
        if (marks == null) return columns;
        for (Mark mark : marks) {
            Matcher matcher = DAY.matcher(mark.text);
            if (!matcher.matches()) continue;
            int day = dayNumber(matcher.group(1));
            if (day > 0 && !columns.containsKey(day)) columns.put(day, mark.x);
        }
        return columns;
    }

    private static Map<Integer, Double> singleCharColumns(List<Mark> marks) {
        Map<Integer, Double> columns = new LinkedHashMap<>();
        if (marks == null) return columns;
        for (Mark mark : marks) {
            Matcher matcher = SINGLE.matcher(mark.text);
            if (!matcher.matches()) continue;
            int day = dayNumber(matcher.group(1));
            if (day > 0 && !columns.containsKey(day)) columns.put(day, mark.x);
        }
        return columns;
    }

    private static List<int[]> rowMarks(List<Mark> marks, boolean skipSingleChars) {
        List<int[]> rows = new ArrayList<>();
        if (marks == null) return rows;
        Map<Integer, Integer> byNode = new LinkedHashMap<>();
        List<Mark> times = new ArrayList<>();
        for (Mark mark : marks) {
            Integer node = nodeNumber(mark.text, skipSingleChars);
            if (node != null) {
                if (!byNode.containsKey(node)) byNode.put(node, (int) Math.round(mark.y));
            } else if (TIME.matcher(mark.text).matches()) {
                times.add(mark);
            }
        }
        if (byNode.size() >= 3) {
            for (Map.Entry<Integer, Integer> entry : byNode.entrySet()) {
                rows.add(new int[]{entry.getKey(), entry.getValue()});
            }
        } else if (times.size() >= 3) {
            times.sort(Comparator.comparingDouble(mark -> mark.y));
            for (int i = 0; i < times.size(); i++) {
                rows.add(new int[]{i + 1, (int) Math.round(times.get(i).y)});
            }
        } else {
            for (Map.Entry<Integer, Integer> entry : byNode.entrySet()) {
                rows.add(new int[]{entry.getKey(), entry.getValue()});
            }
        }
        rows.sort(Comparator.comparingInt(row -> row[1]));
        return rows;
    }

    private static Integer nodeNumber(String text, boolean skipSingleChars) {
        if (text == null) return null;
        String value = text.trim();
        Matcher digits = DIGITS.matcher(value);
        if (digits.matches()) {
            int node = Integer.parseInt(value);
            return node >= 1 && node <= 30 ? node : null;
        }
        if (skipSingleChars && SINGLE.matcher(value).matches()) return null;
        Matcher period = PERIOD_TEXT.matcher(value);
        if (!period.matches()) return null;
        int node = parseNodeText(period.group(1));
        return node >= 1 && node <= 30 ? node : null;
    }

    private static int parseNodeText(String value) {
        if (value.matches("\\d+")) return Integer.parseInt(value);
        return chineseNumber(value);
    }

    private static int chineseNumber(String text) {
        int value = 0;
        int current = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '十') {
                value += current == 0 ? 10 : current * 10;
                current = 0;
            } else if (c >= '一' && c <= '九') {
                current = c - '一' + 1;
            }
        }
        return value + current;
    }

    private static int dayNumber(String value) {
        for (int i = 0; i < DAY_CHARS.length; i++) {
            if (DAY_CHARS[i].equals(value)) return i + 1;
        }
        return 0;
    }

    private static int nearestDay(Map<Integer, Double> columns, double x) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<Integer, Double> entry : columns.entrySet()) {
            double distance = Math.abs(entry.getValue() - x);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getKey();
            }
        }
        return best;
    }

    private static int[] rowSpan(List<int[]> rows, Block block) {
        int start = 0;
        int end = 0;
        double best = Double.MAX_VALUE;
        for (int[] row : rows) {
            if (row[1] >= block.y - block.h / 2 && row[1] <= block.y + block.h / 2) {
                if (start == 0 || row[0] < start) start = row[0];
                if (row[0] > end) end = row[0];
            }
        }
        if (start == 0) {
            for (int[] row : rows) {
                double distance = Math.abs(row[1] - block.y);
                if (distance < best) {
                    best = distance;
                    start = row[0];
                    end = row[0];
                }
            }
        }
        return new int[]{start, end};
    }

    private static List<List<Block>> clusterByY(List<Block> blocks) {
        List<Block> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparingDouble(block -> block.y));
        List<List<Block>> clusters = new ArrayList<>();
        double clusterBottom = Double.NaN;
        for (Block block : sorted) {
            double top = block.y - block.h / 2;
            double bottom = block.y + block.h / 2;
            if (!clusters.isEmpty() && top <= clusterBottom + 8) {
                clusters.get(clusters.size() - 1).add(block);
                clusterBottom = Math.max(clusterBottom, bottom);
            } else {
                List<Block> cluster = new ArrayList<>();
                cluster.add(block);
                clusters.add(cluster);
                clusterBottom = bottom;
            }
        }
        return clusters;
    }

    private static int clusterIndex(List<List<Block>> clusters, Block block) {
        for (int i = 0; i < clusters.size(); i++) {
            for (Block member : clusters.get(i)) {
                if (member == block) return i;
            }
        }
        return 0;
    }

    private static Parsed parseText(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String[] rawLines = text.split("\\n", -1);
        List<String> lines = new ArrayList<>();
        for (String rawLine : rawLines) {
            String value = rawLine.replace('\u00A0', ' ').trim();
            if (!value.isEmpty()) lines.add(value);
        }
        if (lines.isEmpty()) return null;
        int weekIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (looksLikeWeekLine(lines.get(i))) {
                weekIndex = i;
                break;
            }
        }
        if (weekIndex < 0) return null;
        Parsed parsed = new Parsed();
        parsed.name = weekIndex == 0 ? "" : lines.get(0);
        parsed.node = WeekExpression.node(lines.get(weekIndex));
        WeekExpression.Result weeks = WeekExpression.parse(lines.get(weekIndex), Integer.MAX_VALUE);
        parsed.weeks.addAll(weeks.segments);
        if (parsed.name.isEmpty() && weekIndex == 0 && lines.size() > 1) {
            parsed.name = lines.get(1);
        }
        if (parsed.name.isEmpty() || PERIOD_TEXT.matcher(parsed.name).matches()) {
            return null;
        }
        for (int i = 1; i < lines.size(); i++) {
            if (i == weekIndex) continue;
            String value = lines.get(i);
            if (value.equals(parsed.name)) continue;
            if (parsed.teacher.isEmpty() && TEACHER.matcher(value).matches()) {
                parsed.teacher = value;
            } else if (parsed.room.isEmpty() && ROOM.matcher(value).find()) {
                parsed.room = value;
            }
        }
        return parsed;
    }

    /** Bare digits appear in course names (e.g. 形势与政策3); require real week syntax. */
    private static boolean looksLikeWeekLine(String line) {
        String value = line == null ? "" : line.trim();
        if (value.isEmpty()) return false;
        if (WeekExpression.node(value) != null) return true;
        if (value.matches(".*\\d+\\s*[-~]\\s*\\d+.*")) return true;
        if (value.matches(".*\\d+\\s*周.*") || value.matches(".*周.*\\d+.*")) return true;
        if (value.contains("(单)") || value.contains("(双)")
                || value.contains("（单）") || value.contains("（双）")) return true;
        if (value.matches(".*第\\s*\\d+\\s*[-~]?\\s*\\d*\\s*节.*")) return true;
        return false;
    }

    private static final class Parsed {
        String name = "";
        String room = "";
        String teacher = "";
        int[] node;
        final List<WeekExpression.Segment> weeks = new ArrayList<>();
    }
}
