package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 新版正方（jwglxt）课表插件的<b>尽力</b>解析器。
 *
 * <p><b>背景（来自用户的真实现象与 MA 判断，已在代码中验证）：</b>新版正方课表页
 * {@code /kbcx/xskbcx_cxXskbcxIndex.html} 只是查询表单壳，用户点「查询」后由
 * <b>POST</b>（{@code gnmkdm=N2151}，带 {@code xnm/xqm}）返回数据。我们的两条主动通道
 * （页内 {@code fetch} 代理、{@code HttpURLConnection} 直取）都只发 GET，
 * 所以对华农这类站点永远拿不到数据——被动捕获通道才是正解。</p>
 *
 * <p><b>诚实说明：新版正方「查询」返回的确切结构没有公开样例</b>（本仓库没有华农的响应体），
 * 所以这个插件是<b>按已知惯例的尽力实现</b>，而不是「已验证的定点解析」。它做三件事：</p>
 * <ol>
 *   <li><b>识别</b>：URL 含 {@code kbcx/xskbcx/jwglxt/CXKBCX}，或正文含新正方特征
 *       （{@code kbList}/{@code xqj}/{@code jcs}/{@code kcmc}），或内容类型为 JSON 且含
 *       {@code kcmc}/{@code jsxx}/{@code cdmc}。</li>
 *   <li><b>JSON 路径</b>：依次尝试 {@code tmpList}、{@code xsKbList}、{@code xkkList}、
 *       {@code kbList}、{@code items}、{@code rows}、{@code data} 等常见数组字段；每个元素按
 *       {@code kcmc(课程名称) / xqj(星期几) / jcs(节次, 形如 "1-2" 或 "1-2节") /
 *       jc(节次数字) / zcd(周次, 形如 "1-16周" 或 "1,3,5") / cdmc(场地名称) / xm(教师) /
 *       jsxx(教师信息)} 提取。</li>
 *   <li><b>HTML 路径</b>：若是 HTML 片段，交给通用网格解析（新正方课表片段本质上也是
 *       一个「星期列 + 节次行」的表格）。</li>
 * </ol>
 *
 * <p>解析失败时，上层会把<b>响应体原文</b>写进诊断证据包（见 {@code PassiveCapture} 的
 * 证据输出），用户回传后即可补定点规则——这是刻意设计的「可迭代闭环」，
 * 而不是假装已经能解析所有新版正方。</p>
 */
public final class NewZhengFangParser implements TimetableParserRegistry.Parser {

    /** 新正方课表 URL 特征。 */
    private static final String[] URL_HINTS = {
            "kbcx", "xskbcx", "cxXskbcx", "jwglxt", "kbtable"
    };

    /** 新正方课表 JSON 的常见字段名（按优先级）。 */
    private static final String[] LIST_FIELDS = {
            "tmpList", "xsKbList", "xkkList", "kbList", "kbcxList", "items", "rows", "data", "list"
    };

    @Override
    public String name() {
        return "NewZhengFang";
    }

    @Override
    public int priority() {
        // 15：晚于 ScauGrid(10) 的定点网格，早于通用网格(100)。
        // 它认识新正方的 JSON 字段名，但没有结构化网格时只能做启发式猜测。
        return 15;
    }

    @Override
    public boolean supports(TimetableParserRegistry.Input input) {
        if (input == null) return false;
        String url = input.url.toLowerCase(Locale.ROOT);
        for (String hint : URL_HINTS) {
            if (url.contains(hint.toLowerCase(Locale.ROOT))) return true;
        }
        String body = input.body;
        if (input.looksJson()) {
            return body.contains("kcmc") || body.contains("xqj") || body.contains("jsxx")
                    || body.contains("cdmc") || body.contains("zcd");
        }
        // HTML 片段：新正方的课程格 id/class 常带 kbcontent / kbtable
        return body.contains("kbcontent") || body.contains("kbtable")
                || (body.contains("xqj") && body.contains("jcs"));
    }

    @Override
    public HtmlTimetableParser.Result parse(TimetableParserRegistry.Input input) {
        HtmlTimetableParser.Result result = new HtmlTimetableParser.Result();
        if (input.looksJson()) {
            int parsed = parseJson(input.body, input.maxWeek, input.nodes, result);
            result.gridInfo = "新正方 JSON：解析 " + parsed + " 条排课";
            if (parsed == 0) {
                result.warnings.add("新正方 JSON 未识别到课程字段（tmpList/kbList 等），请回传响应体");
            }
            return result;
        }
        if (input.looksHtml()) {
            HtmlTimetableParser.Result html = HtmlTimetableParser.parse(
                    input.body, input.maxWeek, input.nodes);
            if (!html.details.isEmpty()) {
                html.gridInfo = "新正方 HTML 片段：" + html.gridInfo;
                return html;
            }
        }
        result.warnings.add("新正方：响应未匹配任何已知结构，已记入诊断证据包");
        return result;
    }

    // ------------------------------------------------------------------
    // JSON 路径
    // ------------------------------------------------------------------

    private static int parseJson(String body, int maxWeek, int nodes,
                                 HtmlTimetableParser.Result result) {
        Object root;
        try {
            root = OnlineImportLogic.readJsonValue(body);
        } catch (RuntimeException exception) {
            result.warnings.add("新正方 JSON 解析失败：" + exception.getMessage());
            return 0;
        }
        List<?> rows = findRowList(root);
        if (rows == null || rows.isEmpty()) return 0;

        Map<String, String> baseIds = new LinkedHashMap<>();
        for (Object item : rows) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> row = (Map<?, ?>) item;
            String name = firstString(row, "kcmc", "courseName", "kcmcDisplay", "name");
            if (name.isEmpty()) continue;
            String teacher = firstString(row, "xm", "jsxx", "teacher", "jsxm");
            String room = firstString(row, "cdmc", "room", "jxdd", "cdmcDisplay");
            String dayText = firstString(row, "xqj", "day", "xingqi");
            String nodeText = firstString(row, "jcs", "jcor", "jc", "nodes");
            String weekText = firstString(row, "zcd", "weeks", "zc");

            int day = parseDay(dayText);
            if (day < 1) continue;
            int[] span = parseNodes(nodeText);
            if (span[0] < 1) continue;
            int startWeek = 1;
            int endWeek = Math.max(1, maxWeek);
            int type = CourseDetail.TYPE_ALL;
            int[] weeks = parseWeeks(weekText, maxWeek);
            if (weeks != null) {
                startWeek = weeks[0];
                endWeek = weeks[1];
                type = weeks[2];
            }
            String baseId = baseIds.get(name);
            if (baseId == null) {
                baseId = "zf-" + baseIds.size();
                baseIds.put(name, baseId);
                result.bases.add(new CourseBase(baseId, name, 0, room, 0.0));
            }
            result.details.add(new CourseDetail(baseId + "-" + result.details.size(), baseId,
                    day, span[0], span[1], startWeek, endWeek, type, room, teacher, 0));
        }
        // 节次超出配置上限时告警（与其它 parser 口径一致）
        for (CourseDetail detail : result.details) {
            if (detail.startNode > nodes) {
                result.warnings.add("排课节次超过当前节次配置：" + detail.startNode + " > " + nodes);
                break;
            }
        }
        return result.details.size();
    }

    /** 在 JSON 树里找第一个「看起来像课表行列表」的数组。 */
    @SuppressWarnings("unchecked")
    private static List<?> findRowList(Object root) {
        if (root instanceof List) {
            List<?> list = (List<?>) root;
            if (!list.isEmpty() && list.get(0) instanceof Map) return list;
            return null;
        }
        if (!(root instanceof Map)) return null;
        Map<?, ?> map = (Map<?, ?>) root;
        for (String field : LIST_FIELDS) {
            Object value = map.get(field);
            List<?> list = findRowList(value);
            if (list != null) return list;
        }
        // 兜底：任何 value 是「含 kcmc 的对象数组」的字段
        for (Object value : map.values()) {
            if (value instanceof List) {
                List<?> list = (List<?>) value;
                if (!list.isEmpty() && list.get(0) instanceof Map
                        && containsCourseKey((Map<?, ?>) list.get(0))) {
                    return list;
                }
            }
        }
        return null;
    }

    private static boolean containsCourseKey(Map<?, ?> map) {
        for (Object key : map.keySet()) {
            String k = String.valueOf(key);
            if (k.equals("kcmc") || k.equals("courseName")) return true;
        }
        return false;
    }

    private static String firstString(Map<?, ?> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                return ((String) value).trim();
            }
            if (value instanceof Number) return String.valueOf(value);
        }
        return "";
    }

    /** "星期一"/"1"/"周一" → 1..7。 */
    public static int parseDay(String text) {
        if (text == null) return -1;
        String value = text.trim();
        if (value.isEmpty()) return -1;
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        for (int i = 0; i < names.length; i++) {
            if (value.contains(names[i])) return i + 1;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '1' && c <= '7') return c - '0';
        }
        return -1;
    }

    /** "1-2" / "1-2节" / "3" / "第3节" → {起始节, 长度}。 */
    public static int[] parseNodes(String text) {
        if (text == null) return new int[]{-1, 1};
        List<Integer> numbers = numbers(text);
        if (numbers.isEmpty()) return new int[]{-1, 1};
        int start = numbers.get(0);
        int end = numbers.size() > 1 ? numbers.get(1) : start;
        if (end < start) {
            int swap = start;
            start = end;
            end = swap;
        }
        return new int[]{start, Math.max(1, end - start + 1)};
    }

    /** "1-16周" / "1-16周(单)" / "1,3,5" / "1-8,10-16" → {起, 止, 单双}。 */
    public static int[] parseWeeks(String text, int maxWeek) {
        if (text == null || text.trim().isEmpty()) return null;
        String value = text.trim();
        int type = CourseDetail.TYPE_ALL;
        if (value.contains("单")) type = CourseDetail.TYPE_ODD;
        else if (value.contains("双")) type = CourseDetail.TYPE_EVEN;
        List<Integer> numbers = numbers(value);
        if (numbers.isEmpty()) return null;
        int start = numbers.get(0);
        int end = numbers.size() > 1 ? numbers.get(1) : start;
        if (end < start) {
            int swap = start;
            start = end;
            end = swap;
        }
        start = Math.max(1, Math.min(start, maxWeek));
        end = Math.max(start, Math.min(end, maxWeek));
        return new int[]{start, end, type};
    }

    private static List<Integer> numbers(String text) {
        List<Integer> out = new ArrayList<>();
        int current = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                current = (current < 0 ? 0 : current * 10) + (c - '0');
            } else if (current >= 0) {
                out.add(current);
                current = -1;
            }
        }
        if (current >= 0) out.add(current);
        return out;
    }
}
