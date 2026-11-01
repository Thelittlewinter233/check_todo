package com.checktodo.app.wakeup_parser;

import com.checktodo.app.jshim.Document;
import com.checktodo.app.jshim.Element;
import com.checktodo.app.jshim.Elements;
import com.checktodo.app.jshim.Jsoup;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewritten from WakeUp schedule_parser.parser.OooO (URP new/AJAX layout).
 * Layout: table.pages-...tableContent and td[_nk] blocks.
 */
public final class UrpNewParser implements WakeupParser {
    @Override
    public String name() {
        return "URP New";
    }

    @Override
    public WakeupParserResult parse(String source) {
        WakeupParserResult result = new WakeupParserResult();
        result.parserName = name();
        result.nodes = 12;
        result.maxWeek = 20;
        Document document = Jsoup.parse(source);
        Element table = null;
        for (Element candidate : document.select("table")) {
            if (candidate.className().contains("tableContent")) {
                table = candidate;
                break;
            }
        }
        if (table == null) return result;

        Elements cells = table.select("td[_nk]");
        for (int index = 0; index < cells.size(); index++) {
            Element cell = cells.get(index);
            if (cell.attr("_nk").trim().isEmpty()) continue;
            List<String> lines = lines(cell);
            if (lines.size() < 5) continue;
            String name = lines.get(0);
            String teacher = lines.get(1);
            String room = lines.get(2);
            String weeks = lines.get(3);
            String nodes = lines.get(4);
            int day = cell.hasAttr("day") ? WeekText.parse(cell.attr("day")) : index + 1;
            int[] nodeRange = nodeRange(nodes);
            for (String piece : split(weeks)) {
                int[] range = WeekText.range(piece);
                result.courses.add(new WakeupCourse(name, day, room, teacher, nodeRange[0],
                        nodeRange[1], range[0], range[1], WeekText.type(piece), 0));
            }
        }
        if (result.isEmpty()) result.warnings.add("URP New：识别到表格但未解析出课程");
        return result;
    }

    private static List<String> lines(Element cell) {
        List<String> out = new ArrayList<>();
        for (Element child : cell.children()) {
            String text = child.text().trim();
            if (!text.isEmpty() && !out.contains(text)) out.add(text);
        }
        if (out.isEmpty()) {
            String text = cell.text().trim();
            if (!text.isEmpty()) out.add(text);
        }
        return out;
    }

    private static int[] nodeRange(String value) {
        int[] range = WeekText.range(value);
        return new int[]{range[0], range[1]};
    }

    private static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        for (String item : (value == null ? "" : value).split("[,，、;；\\s]+")) {
            String piece = item.trim();
            if (!piece.isEmpty()) out.add(piece);
        }
        return out;
    }
}
