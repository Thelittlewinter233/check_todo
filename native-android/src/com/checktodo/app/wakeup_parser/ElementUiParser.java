package com.checktodo.app.wakeup_parser;

import com.checktodo.app.jshim.Document;
import com.checktodo.app.jshim.Element;
import com.checktodo.app.jshim.Elements;
import com.checktodo.app.jshim.Jsoup;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewritten from WakeUp schedule_parser.parser.o0O00OOO.
 * Supports both the Element-UI card layout and its table fallback.
 */
public final class ElementUiParser implements WakeupParser {
    private static final Pattern TIME = Pattern.compile("([一二三四五六七日天])(\\d+)-(\\d+)");

    @Override
    public String name() {
        return "Element UI";
    }

    @Override
    public WakeupParserResult parse(String source) {
        WakeupParserResult result = new WakeupParserResult();
        result.parserName = name();
        result.nodes = 12;
        result.maxWeek = 20;
        Document document = Jsoup.parse(source);
        for (Element card : document.select("div.arranged-content div.el-card.arranged-course-card")) {
            Map<String, String> fields = cardFields(card);
            addEntries(result, fields.getOrDefault("课程名", ""),
                    fields.getOrDefault("上课教师", ""), fields.getOrDefault("上课地点", ""),
                    fields.getOrDefault("上课时间", ""));
        }
        if (!result.isEmpty()) return result;

        for (Element row : document.select(
                "div.arranged-content table.el-table__body tbody tr.el-table__row")) {
            Elements columns = row.select("td");
            if (columns.size() < 11) continue;
            addEntries(result, columns.get(1).text(), columns.get(5).text(),
                    columns.get(7).text(), columns.get(6).text());
        }
        if (result.isEmpty()) result.warnings.add("Element UI：未识别到排课卡片或表格");
        return result;
    }

    private static Map<String, String> cardFields(Element card) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (Element item : card.select("div.card-item")) {
            Element label = item.selectFirst("div.label");
            Element value = item.selectFirst("div.value");
            if (label == null || value == null) continue;
            String key = label.text().replace(":", "").replace("：", "").trim();
            fields.put(key, value.text().trim());
        }
        return fields;
    }

    private static void addEntries(WakeupParserResult result, String name, String teacher,
                                   String room, String time) {
        if (name == null || name.trim().isEmpty() || time == null || time.trim().isEmpty()) return;
        String parenthesized = "";
        int open = time.indexOf('(');
        int close = time.indexOf(')', open + 1);
        if (open >= 0 && close > open) parenthesized = time.substring(open + 1, close);
        Matcher matcher = TIME.matcher(time);
        while (matcher.find()) {
            int day = day(matcher.group(1));
            int start = WeekText.parse(matcher.group(2));
            int end = WeekText.parse(matcher.group(3));
            if (day < 1 || start < 1 || end < start) continue;
            int[] weeks = WeekText.range(parenthesized.isEmpty() ? time : parenthesized);
            result.courses.add(new WakeupCourse(name, day, room, teacher, start, end,
                    weeks[0], weeks[1], WeekText.type(time), 0));
        }
    }

    private static int day(String value) {
        switch (value) {
            case "一": return 1;
            case "二": return 2;
            case "三": return 3;
            case "四": return 4;
            case "五": return 5;
            case "六": return 6;
            case "日": case "天": return 7;
            default: return -1;
        }
    }
}
