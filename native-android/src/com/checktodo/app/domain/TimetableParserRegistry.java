package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 插件化课表 parser 体系。
 *
 * <p><b>设计动机：</b>现有解析链路是「选校专用 parser（WakeUp 家族，固定 5 种）+ 通用
 * {@code HtmlTimetableParser}」的硬编码组合，加一种新教务系统要改工厂、改控制器、改 switch。
 * 教务系统版本迭代很快（新版正方 jwglxt 与旧版正方的表格结构就完全不同），
 * 与其不断打补丁，不如把「识别某种响应」抽象成一个可插拔单元：每个插件自己声明
 * <b>能不能处理</b>（{@link #supports}）和<b>怎么处理</b>（{@link #parse}），
 * 注册表按优先级依次尝试，第一个产出课程的结果胜出。</p>
 *
 * <p><b>为什么用「能力探测」而不是「学校类型路由」：</b>用户常常选错学校，或者学校换了系统
 * 而清单没更新。按响应内容探测（URL 特征 / 正文特征 / 内容类型）比按学校名路由更抗变化，
 * 而且同一个站点可能有多种响应（HTML 壳 + JSON 数据），探测式能自然覆盖。</p>
 *
 * <p>插件只依赖纯 Java，可在 JVM 直接测试；注册表本身无状态，线程安全。</p>
 */
public final class TimetableParserRegistry {

    /** 一次解析的输入。 */
    public static final class Input {
        public final String body;
        public final String url;
        public final String contentType;
        public final int maxWeek;
        public final int nodes;

        public Input(String body, String url, String contentType, int maxWeek, int nodes) {
            this.body = body == null ? "" : body;
            this.url = url == null ? "" : url;
            this.contentType = contentType == null ? "" : contentType;
            this.maxWeek = maxWeek;
            this.nodes = nodes;
        }

        public boolean looksJson() {
            String trimmed = body.trim();
            if (contentType.toLowerCase(Locale.ROOT).contains("json")) return true;
            if (trimmed.isEmpty()) return false;
            char first = trimmed.charAt(0);
            return first == '{' || first == '[';
        }

        public boolean looksHtml() {
            String trimmed = body.trim();
            if (trimmed.isEmpty()) return false;
            return trimmed.charAt(0) == '<';
        }
    }

    /** 一个插件。 */
    public interface Parser {
        /** 插件名，进诊断。 */
        String name();

        /** 优先级：数字小的先试。 */
        int priority();

        /** 能否处理这个响应。必须廉价（只做字符串特征判断），不能抛异常。 */
        boolean supports(Input input);

        /** 解析。返回非空 details 视为成功。允许抛异常，注册表会兜住并记诊断。 */
        HtmlTimetableParser.Result parse(Input input);
    }

    /** 一次尝试的记录，进诊断证据包。 */
    public static final class Attempt {
        public final String parser;
        public final boolean supported;
        public final int details;
        public final String note;

        Attempt(String parser, boolean supported, int details, String note) {
            this.parser = parser;
            this.supported = supported;
            this.details = details;
            this.note = note == null ? "" : note;
        }

        @Override
        public String toString() {
            if (!supported) return parser + "：不适用";
            return parser + "：" + details + " 条排课" + (note.isEmpty() ? "" : "（" + note + "）");
        }
    }

    /** 一次解析的完整结果。 */
    public static final class Outcome {
        public final HtmlTimetableParser.Result result = new HtmlTimetableParser.Result();
        public final List<Attempt> attempts = new ArrayList<>();
        public String winner = "";

        public boolean isEmpty() {
            return result.details.isEmpty();
        }
    }

    private static final List<Parser> PLUGINS = new ArrayList<>();

    private TimetableParserRegistry() {
    }

    static {
        // 注册顺序无关，实际按 priority 排序；同优先级按注册顺序（稳定）。
        register(new DedicatedParserPlugin());
        register(new NewZhengFangParser());
        register(new HtmlGridParser());
        register(new KingoGridParser());
        register(new ScauGridPlugin());
    }

    public static synchronized void register(Parser parser) {
        if (parser != null) PLUGINS.add(parser);
    }

    public static synchronized List<Parser> plugins() {
        List<Parser> sorted = new ArrayList<>(PLUGINS);
        sorted.sort((a, b) -> {
            int byPriority = Integer.compare(a.priority(), b.priority());
            return byPriority != 0 ? byPriority : Integer.compare(PLUGINS.indexOf(a), PLUGINS.indexOf(b));
        });
        return sorted;
    }

    /**
     * 按优先级依次尝试，第一个产出课程的插件获胜。
     *
     * <p>即使已有插件成功，也<b>不</b>继续跑后面的：不同插件的解析口径可能冲突，
     * 混在一起会导致重复课程。要合并多来源，应在上层按响应分别解析再 merge。</p>
     */
    public static Outcome parse(Input input) {
        Outcome outcome = new Outcome();
        if (input == null || input.body.trim().isEmpty()) {
            outcome.result.errors.add("响应体为空");
            return outcome;
        }
        for (Parser plugin : plugins()) {
            boolean supported;
            try {
                supported = plugin.supports(input);
            } catch (RuntimeException exception) {
                outcome.attempts.add(new Attempt(plugin.name(), false, 0,
                        "supports() 异常：" + exception.getMessage()));
                continue;
            }
            if (!supported) {
                outcome.attempts.add(new Attempt(plugin.name(), false, 0, ""));
                continue;
            }
            try {
                HtmlTimetableParser.Result result = plugin.parse(input);
                int details = result == null ? 0 : result.details.size();
                outcome.attempts.add(new Attempt(plugin.name(), true, details,
                        result == null || result.warnings.isEmpty() ? "" : result.warnings.get(0)));
                if (details > 0) {
                    outcome.result.bases.addAll(result.bases);
                    outcome.result.details.addAll(result.details);
                    outcome.result.warnings.addAll(result.warnings);
                    outcome.result.gridInfo = result.gridInfo;
                    outcome.winner = plugin.name();
                    return outcome;
                }
            } catch (RuntimeException exception) {
                outcome.attempts.add(new Attempt(plugin.name(), true, 0,
                        "parse() 异常：" + exception.getMessage()));
            }
        }
        return outcome;
    }

    /** 诊断用：把 attempts 拼成多行。 */
    public static String describe(Outcome outcome) {
        if (outcome == null) return "";
        StringBuilder out = new StringBuilder();
        for (Attempt attempt : outcome.attempts) {
            out.append("  · ").append(attempt).append('\n');
        }
        if (!outcome.winner.isEmpty()) {
            out.append("  → 命中 ").append(outcome.winner).append('\n');
        } else {
            out.append("  → 所有插件均未解析出课程\n");
        }
        return out.toString();
    }
}
