package com.checktodo.app.wakeup_parser;

import com.checktodo.app.jshim.Document;
import com.checktodo.app.jshim.Element;
import com.checktodo.app.jshim.Elements;
import com.checktodo.app.jshim.Jsoup;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewritten from WakeUp schedule_parser.parser.p949jz.OooO0o.
 * Layout: table id jsTbl_* or MsoNormalTable mtt_table, td[xq][jc], .arrage children.
 */
public final class JinZhiParser implements WakeupParser {
    @Override
    public String name() {
        return "JinZhi";
    }

    @Override
    public WakeupParserResult parse(String source) {
        WakeupParserResult result = new WakeupParserResult();
        result.parserName = name();
        result.nodes = 12;
        result.maxWeek = 20;
        Document document = Jsoup.parse(source);
        Element table = findTable(document);
        if (table == null) return result;
        for (Element cell : table.select("td")) {
            String style = cell.attr("style").toLowerCase();
            if (style.contains("display") && style.contains("none")) continue;
            if (!cell.hasAttr("xq") || !cell.hasAttr("jc")) continue;
            int day = WeekText.parse(cell.attr("xq"));
            int startNode = WeekText.parse(cell.attr("jc"));
            int step = Math.max(1, WeekText.parse(cell.attr("rowspan")));
            if (day < 1 || day > 7 || startNode < 1) continue;
            for (Element arrangement : cell.select("arrage")) {
                Elements parts = arrangement.children();
                if (parts.size() < 3) continue;
                String weekText = parts.get(0).text();
                String name = cleanName(parts.get(1).text());
                String room = parts.get(2).text();
                String teacher = parts.size() > 3 ? parts.get(3).text() : "";
                if (name.trim().isEmpty()) continue;
                for (String piece : split(weekText)) {
                    int[] range = WeekText.range(piece);
                    result.courses.add(new WakeupCourse(name, day, room, teacher, startNode,
                            startNode + step - 1, range[0], range[1], WeekText.type(piece), 0));
                }
            }
        }
        if (result.isEmpty()) result.warnings.add("JinZhi：识别到表格但未解析出课程");
        return result;
    }

    private static Element findTable(Document document) {
        for (Element table : document.select("table")) {
            String id = table.id();
            String className = table.className();
            if (id.startsWith("jsTbl_") || className.contains("MsoNormalTable")
                    || className.contains("mtt_table")) {
                return table;
            }
        }
        return null;
    }

    private static String cleanName(String value) {
        String name = value == null ? "" : value.trim();
        int open = name.indexOf('(');
        int openFull = name.indexOf('（');
        if (open >= 0) return name.substring(0, open).trim();
        if (openFull >= 0) return name.substring(0, openFull).trim();
        return name;
    }

    private static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        for (String item : (value == null ? "" : value).split("[,，、;；\\s]+")) {
            String piece = item.replace("第", "").replace("周", "").trim();
            if (!piece.isEmpty()) out.add(piece);
        }
        return out;
    }
}
