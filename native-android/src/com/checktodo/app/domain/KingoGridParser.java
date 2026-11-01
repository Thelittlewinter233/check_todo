package com.checktodo.app.domain;

/**
 * KINGOSOFT 结构化网格插件（{@code xskcb*.jsp} 的 {@code #mytable} 表格）。
 *
 * <p>北师大珠海案例就是它。放在通用网格之前（priority 20），因为
 * {@link KingoReportParser} 对「td.td 内四行文本」的结构比通用启发式更准确。
 * 不命中时返回空结果，注册表会继续试下一个插件。</p>
 */
public final class KingoGridParser implements TimetableParserRegistry.Parser {
    @Override
    public String name() {
        return "KingoGrid";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public boolean supports(TimetableParserRegistry.Input input) {
        if (input == null || !input.looksHtml()) return false;
        String body = input.body;
        String url = input.url.toLowerCase(java.util.Locale.ROOT);
        return body.contains("mytable") || body.contains("td1")
                || url.contains("xskcb") || url.contains("kingosoft");
    }

    @Override
    public HtmlTimetableParser.Result parse(TimetableParserRegistry.Input input) {
        java.util.List<SimpleHtmlParser.Table> tables = SimpleHtmlParser.tables(input.body);
        HtmlTimetableParser.Result result = KingoReportParser.parse(
                tables, input.maxWeek, input.nodes);
        if (result.details.isEmpty()) {
            result.warnings.add("KINGOSOFT 网格未识别（缺 #mytable 或表头）");
        }
        return result;
    }
}
