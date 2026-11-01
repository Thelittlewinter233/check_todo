package com.checktodo.app.domain;

/**
 * 华农（新版正方 jwglxt）网格插件。
 *
 * <p>优先级 10，排在 {@code NewZhengFang}(15) <b>之前</b>。原因：华农课表页的 URL 也含
 * {@code kbcx}，而 {@code NewZhengFang} 的 URL 特征探测会命中它；但本插件只认
 * {@code kbgrid_table_*} 这种带明确 id 的网格，识别条件更严格、结论更准确，
 * 所以必须先试，否则会被通用新正方启发式抢先。</p>
 *
 * <p>放在注册表里的意义：教务导入的「被动捕获池」与「候选 GET」两条通道拿到的响应
 * 都经过注册表，因此 POST 型系统一旦捕获到课表页，这个插件就会命中原生网格。</p>
 */
public final class ScauGridPlugin implements TimetableParserRegistry.Parser {
    @Override
    public String name() {
        return "ScauGrid";
    }

    @Override
    public int priority() {
        // 必须早于 NewZhengFang(15)：华农页面的 URL 也含 kbcx，而 NewZhengFang 的
        // 特征探测会命中它；但华农的结构是明确的 kbgrid_table 网格，
        // 定点解析比通用新正方启发更准确，应当先试。
        return 10;
    }

    @Override
    public boolean supports(TimetableParserRegistry.Input input) {
        if (input == null || !input.looksHtml()) return false;
        return ScauGridParser.looksLikeScau(input.body);
    }

    @Override
    public HtmlTimetableParser.Result parse(TimetableParserRegistry.Input input) {
        ScauGridParser.Outcome outcome = ScauGridParser.parse(
                input.body, input.maxWeek, input.nodes);
        if (outcome.isEmpty() && outcome.result.warnings.isEmpty()) {
            outcome.result.warnings.add("华农网格：识别到页面但未解析出课程块");
        }
        return outcome.result;
    }
}
