package com.checktodo.app.wakeup_parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

/** Shared Chinese week-expression parser used by the rewritten WakeUp families. */
public final class WeekText {
    private static final Pattern RANGE = Pattern.compile("(\\d{1,2})\\s*[-~]\\s*(\\d{1,2})");
    private static final Pattern SINGLE = Pattern.compile("(\\d{1,2})");

    private WeekText() {
    }

    public static int type(String text) {
        String value = text == null ? "" : text;
        if (value.contains("单") || value.toLowerCase(Locale.ROOT).contains("odd")) return 1;
        if (value.contains("双") || value.toLowerCase(Locale.ROOT).contains("even")) return 2;
        return 0;
    }

    public static int[] range(String text) {
        String value = clean(text);
        Matcher range = RANGE.matcher(value);
        int start = -1;
        int end = -1;
        while (range.find()) {
            int candidateStart = parse(range.group(1));
            int candidateEnd = parse(range.group(2));
            if (candidateStart > 0 && candidateEnd >= candidateStart) {
                if (start < 0) start = candidateStart;
                end = candidateEnd;
            }
        }
        if (start < 0) {
            Matcher single = SINGLE.matcher(value);
            while (single.find()) {
                int week = parse(single.group(1));
                if (week > 0) {
                    if (start < 0) start = week;
                    end = week;
                }
            }
        }
        return start > 0 ? new int[]{start, Math.max(start, end)} : new int[]{1, 20};
    }

    public static int parse(String value) {
        try {
            return Integer.parseInt((value == null ? "" : value).trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    public static String clean(String value) {
        return (value == null ? "" : value)
                .replace("第", " ")
                .replace("周", " ")
                .replace("星期", " ")
                .replace("　", " ")
                .trim();
    }
}
