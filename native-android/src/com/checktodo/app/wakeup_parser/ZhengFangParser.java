package com.checktodo.app.wakeup_parser;

import com.checktodo.app.jshim.Document;
import com.checktodo.app.jshim.Element;
import com.checktodo.app.jshim.Elements;
import com.checktodo.app.jshim.Jsoup;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewritten from WakeUp schedule_parser.parser.p950zf.OooO.
 * Layout: #table_tb, td#td_day-node, nested per-course table rows.
 */
public final class ZhengFangParser implements WakeupParser {
    @Override
    public String name() {
        return "ZhengFang";
    }

    @Override
    public WakeupParserResult parse(String source) {
        WakeupParserResult result = new WakeupParserResult();
        result.parserName = name();
        result.nodes = 12;
        result.maxWeek = 20;
        Document document = Jsoup.parse(source);
        Element table = document.getElementById("table_tb");
        if (table == null) table = document.selectFirst("table.mtt_table");
        if (table == null) return result;

        Elements cells = table.select("td");
        for (Element cell : cells) {
            String id = cell.id();
            if (!id.startsWith("td_") || !cell.hasAttr("rowspan")) continue;
            String[] position = id.substring(3).split("-");
            if (position.length < 2) continue;
            int day = WeekText.parse(position[0]);
            int startNode = WeekText.parse(position[1]);
            int step = Math.max(1, WeekText.parse(cell.attr("rowspan")));
            if (day < 1 || day > 7 || startNode < 1) continue;

            for (Element courseTable : cell.select("table")) {
                for (Element row : courseTable.select("tr")) {
                    Elements columns = row.select("td");
                    if (columns.size() < 5) continue;
                    String name = columns.get(0).text();
                    if (name.trim().isEmpty()) continue;
                    addCourse(result, name, day, startNode, step, columns.get(2).text(),
                            columns.get(3).text(), columns.get(4).text());
                }
            }
        }
        if (result.isEmpty()) result.warnings.add("ZhengFang：识别到表格但未解析出课程");
        return result;
    }

    private static void addCourse(WakeupParserResult result, String name, int day, int startNode,
                                  int step, String room, String teacher, String weeks) {
        List<String> pieces = split(weeks);
        if (pieces.isEmpty()) pieces.add(weeks);
        for (String piece : pieces) {
            int[] range = WeekText.range(piece);
            result.courses.add(new WakeupCourse(name, day, room, teacher, startNode,
                    startNode + step - 1, range[0], range[1], WeekText.type(piece), 0));
        }
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
