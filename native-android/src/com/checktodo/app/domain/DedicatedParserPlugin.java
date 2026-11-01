package com.checktodo.app.domain;

/**
 * 选校专用 parser 的注册表桥接。
 *
 * <p>WakeUp 家族的 5 种 parser（正方式 / 金智 / URP / Kingosoft / ElementUI）是按
 * 「用户在选校列表里选了哪所学校」路由的，属于<b>外部知识</b>，不该硬编码进注册表。
 * 这里用一个可注入的委托把它挂成最高优先级的插件：能用专用 parser 时优先用它
 * （它对特定系统最准），不能用时自然落到新正方/KingoGrid/通用网格。</p>
 *
 * <p>没有注入委托时，本插件 {@link #supports} 恒为 false，等于不存在——所以 JVM 测试
 * 不需要任何 WakeUp 依赖。</p>
 */
public final class DedicatedParserPlugin implements TimetableParserRegistry.Parser {

    /** 由控制器注入的实际解析逻辑。 */
    public interface Delegate {
        /**
         * @return 解析结果；{@code null} 表示当前没有可用专用 parser（未选校）。
         */
        HtmlTimetableParser.Result parse(String body);
    }

    private static volatile Delegate delegate;

    /** 注入（传 null 解除）。通常在打开教务对话框时按当前选校注入。 */
    public static void setDelegate(Delegate value) {
        delegate = value;
    }

    @Override
    public String name() {
        return "Dedicated";
    }

    @Override
    public int priority() {
        // 最高优先级：选校专用规则比通用启发式更可信。
        return 0;
    }

    @Override
    public boolean supports(TimetableParserRegistry.Input input) {
        return delegate != null && input != null && !input.body.trim().isEmpty();
    }

    @Override
    public HtmlTimetableParser.Result parse(TimetableParserRegistry.Input input) {
        Delegate current = delegate;
        if (current == null) return new HtmlTimetableParser.Result();
        HtmlTimetableParser.Result result = current.parse(input.body);
        return result == null ? new HtmlTimetableParser.Result() : result;
    }
}
