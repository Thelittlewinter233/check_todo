package com.checktodo.app.domain;

/**
 * 通用 HTML 网格插件：对任意 HTML 响应跑启发式表格识别。
 *
 * <p>放在注册表靠后的位置（priority 100），作为「没认出特定系统」时的兜底。
 * 它的能力完全来自 {@link HtmlTimetableParser}，本插件只负责判断「这份响应值不值得试」
 * 与把失败原因写清楚。</p>
 */
public final class HtmlGridParser implements TimetableParserRegistry.Parser {
    @Override
    public String name() {
        return "HtmlGrid";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean supports(TimetableParserRegistry.Input input) {
        if (input == null || !input.looksHtml()) return false;
        // 只有一个 div 的碎片不值得跑完整网格识别。
        return input.body.length() >= 200 && input.body.contains("<table")
                || input.body.contains("<div");
    }

    @Override
    public HtmlTimetableParser.Result parse(TimetableParserRegistry.Input input) {
        HtmlTimetableParser.Result result = HtmlTimetableParser.parse(
                input.body, input.maxWeek, input.nodes);
        if (result.details.isEmpty() && result.errors.isEmpty()) {
            result.warnings.add("通用网格：未识别到课表表格（表头/单元格格式不符合启发式）");
        }
        return result;
    }
}
