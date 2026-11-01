package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared parser for timetable week expressions.
 *
 * Supported forms: "1-16", "7", "14-14", "1-5,7-16", "1-16周", "1-16周(单)",
 * "1-16周(双)", "1-16[5-6]节". A comma-separated expression produces one segment
 * per contiguous range; odd/even markers apply to the segment they follow.
 */
public final class WeekExpression {
    public static final class Segment {
        public final int start;
        public final int end;
        public final int type;

        Segment(int start, int end, int type) {
            this.start = start;
            this.end = end;
            this.type = type;
        }
    }

    public static final class Result {
        public final List<Segment> segments = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
    }

    private static final Pattern SEGMENT = Pattern.compile(
            "(?<![0-9])([0-9]{1,2})(?:\\s*[-~]\\s*([0-9]{1,2}))?"
                    + "(?:\\s*(?:\u5468|week|w))?"
                    + "(?:\\s*(?:[()\\uff08]\\s*(\u5355|\u53cc)\\s*[)\\uff09]"
                    + "|(\u5355|\u53cc)(?:\\u5468)?))?"
                    + "(?![0-9\\]])");
    private static final Pattern NODE = Pattern.compile(
            "\\[\\s*(?:\u7b2c)?\\s*([0-9]{1,2})\\s*(?:[-~]\\s*([0-9]{1,2}))?"
                    + "(?:\\s*(?:\u8282|\u7bc0))?\\s*\\]");

    private WeekExpression() {
    }

    public static Result parse(String text, int maxWeek) {
        Result result = new Result();
        if (text == null || text.trim().isEmpty()) return result;
        int effectiveMax = Math.max(1, maxWeek);
        Matcher matcher = SEGMENT.matcher(NODE.matcher(text).replaceAll(" "));
        while (matcher.find()) {
            int start = integer(matcher.group(1));
            int end = matcher.group(2) == null ? start : integer(matcher.group(2));
            String marker = matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            int type = type(marker);
            if (start < 1 || end < start) {
                result.warnings.add("周次 " + start + "-" + end + " 无效，已跳过");
                continue;
            }
            if (start > effectiveMax) {
                result.warnings.add("周次 " + start + "-" + end + " 超出总周数 " + effectiveMax + "，已跳过");
                continue;
            }
            if (end > effectiveMax) {
                result.warnings.add("周次 " + start + "-" + end + " 超出总周数，按 "
                        + start + "-" + effectiveMax + " 导入");
                end = effectiveMax;
            }
            result.segments.add(new Segment(start, end, type));
        }
        return result;
    }

    public static boolean contains(String text) {
        return text != null && SEGMENT.matcher(NODE.matcher(text).replaceAll(" ")).find();
    }

    public static int[] node(String text) {
        if (text == null) return null;
        Matcher matcher = NODE.matcher(text);
        if (!matcher.find()) return null;
        int start = integer(matcher.group(1));
        int end = matcher.group(2) == null ? start : integer(matcher.group(2));
        return new int[]{start, end};
    }

    private static int type(String marker) {
        if ("\u5355".equals(marker)) return CourseDetail.TYPE_ODD;
        if ("\u53cc".equals(marker)) return CourseDetail.TYPE_EVEN;
        return CourseDetail.TYPE_ALL;
    }

    private static int integer(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
