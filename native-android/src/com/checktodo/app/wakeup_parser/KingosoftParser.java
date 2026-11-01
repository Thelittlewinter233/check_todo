package com.checktodo.app.wakeup_parser;

import java.util.List;
import java.util.Map;

/**
 * Rewritten from WakeUp schedule_parser.parser.o00O0OOO.
 * Supports the old outer-array contract and the modern Kingosoft AJAX list shape:
 * {list:[{kcmc,jsxm,xkd,zc,xq,jc}]}, where zc is a Chinese week expression,
 * xq is 1..7 and jc is a node range.
 */
public final class KingosoftParser implements WakeupParser {
    @Override
    public String name() {
        return "Kingosoft";
    }

    @Override
    public WakeupParserResult parse(String source) {
        WakeupParserResult result = new WakeupParserResult();
        result.parserName = name();
        result.nodes = 12;
        result.maxWeek = 20;
        try {
            Object root = SimpleJson.parse(source == null ? "[]" : source.trim());
            List<Object> modern = modernCourses(root);
            if (modern != null) {
                for (Object courseValue : modern) {
                    Map<String, Object> course = SimpleJson.object(courseValue);
                    if (course.isEmpty()) continue;
                    int day = WeekText.parse(SimpleJson.string(course.get("xq")));
                    int[] nodes = nodeRange(SimpleJson.string(course.get("jc")));
                    String weekText = firstText(course, "zc", "week", "weeks");
                    int[] weeks = WeekText.range(weekText);
                    result.courses.add(new WakeupCourse(
                            firstText(course, "kcmc", "name", "courseName"), day,
                            firstText(course, "xkd", "js", "room"),
                            firstText(course, "jsxm", "teacher"), nodes[0], nodes[1],
                            weeks[0], weeks[1], WeekText.type(weekText),
                            SimpleJson.number(first(course, "xf", "credit"))));
                }
            } else {
            List<Object> outer = SimpleJson.array(root);
            for (int weekIndex = 0; weekIndex < outer.size(); weekIndex++) {
                Map<String, Object> item = SimpleJson.object(outer.get(weekIndex));
                if (item.isEmpty()) continue;
                int week = weekIndex + 1;
                for (int day = 1; day <= 7; day++) {
                    List<Object> dayCourses = SimpleJson.array(item.get("week" + day));
                    for (Object courseValue : dayCourses) {
                        Map<String, Object> course = SimpleJson.object(courseValue);
                        if (course.isEmpty()) continue;
                        int[] nodes = nodeRange(SimpleJson.string(course.get("jcxx")));
                        result.courses.add(new WakeupCourse(
                                SimpleJson.string(course.get("kcmc")), day,
                                SimpleJson.string(course.get("skdd")),
                                SimpleJson.string(course.get("rkjs")), nodes[0], nodes[1],
                                week, week, 0, SimpleJson.number(course.get("xf"))));
                    }
                }
            }
            }
        } catch (Exception ignored) {
            result.warnings.add("Kingosoft: input is not valid course JSON");
        }
        if (result.isEmpty() && result.warnings.isEmpty()) {
            result.warnings.add("Kingosoft: no courses in JSON");
        }
        return result;
    }

    private static int[] nodeRange(String value) {
        return WeekText.range(value);
    }

    private static List<Object> modernCourses(Object root) {
        Map<String, Object> map = SimpleJson.object(root);
        if (map.isEmpty()) return null;
        List<Object> value = SimpleJson.array(first(map, "list", "rows", "data"));
        if (value.isEmpty()) return null;
        Map<String, Object> first = SimpleJson.object(value.get(0));
        return first.containsKey("kcmc") || first.containsKey("xq") ? value : null;
    }

    private static Object first(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) return value;
        }
        return null;
    }

    private static String firstText(Map<String, Object> map, String... keys) {
        return SimpleJson.string(first(map, keys));
    }
}
