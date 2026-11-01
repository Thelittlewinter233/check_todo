package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 节次时间表的纯逻辑：默认表、HH:mm 解析与校验、节数联动、相邻节次重叠检查。
 *
 * <p>本类不依赖 Android，可被单元测试直接调用；持久化仍由 TimetableRepository 负责。</p>
 *
 * <p><b>默认作息来源：</b>华南农业大学（新版正方 jwglxt）课表页 {@code scau.htm} 的
 * 节次列实测刻度（{@code <span class="festival">N</span><span>起</span><br><span>止</span>}）。
 * 现改为硬编码常量表 {@link #SCAU_START} / {@link #SCAU_END}，不再用「45 分钟 + 10 分钟」
 * 规则推演——推演出来的作息与实际差得远（例如第 3 节实际是 09:55，推演会得到 09:40）。</p>
 */
public final class TimeTableSettings {
    /** 每天最大节数（用户可手动加到 20）。与设置弹窗的 1..20 校验一致。 */
    public static final int MAX_NODES = 20;

    /**
     * 华农实测评次表：15 节。索引 0 对应第 1 节。
     *
     * <p>来自 {@code scau.htm} 节次列，逐节抄录（含午休与晚休的真实间隔）：</p>
     * <pre>
     * 1  08:00-08:40   2  08:45-09:25   3  09:55-10:35   4  10:40-11:20   5  11:25-12:05
     * 6  12:40-13:20   7  13:25-14:05   8  14:30-15:10   9  15:15-15:55   10 16:25-17:05
     * 11 17:10-17:50   12 17:55-18:35   13 19:30-20:10   14 20:15-20:55   15 21:00-21:40
     * </pre>
     *
     * <p>注意这里不是「每节 45 分钟」：华农单节是 <b>40 分钟</b>，节间 5 分钟，
     * 上午第 2、3 节之间与午休、晚休处有较长间隔。照抄实测值比推演更准确。</p>
     */
    private static final String[] SCAU_START = {
            "08:00", "08:45", "09:55", "10:40", "11:25",
            "12:40", "13:25", "14:30", "15:15", "16:25",
            "17:10", "17:55", "19:30", "20:15", "21:00"
    };
    private static final String[] SCAU_END = {
            "08:40", "09:25", "10:35", "11:20", "12:05",
            "13:20", "14:05", "15:10", "15:55", "17:05",
            "17:50", "18:35", "20:10", "20:55", "21:40"
    };

    /**
     * 默认时间表节数 = 华农作息覆盖的 15 节。
     *
     * <p>用户仍可把节数加到 {@link #MAX_NODES}（20），但 16..20 节超出了华农表，
     * <b>不猜</b>：这几节返回空时间（{@link #UNSET}），由用户在编辑器里手填，
     * 保存时若仍为空则提示「请填写第 N 节时间」。</p>
     */
    public static final int DEFAULT_NODES = SCAU_START.length;
    /** 每节默认时长（分钟），仅作展示参考；实际起止以常量表为准。 */
    public static final int DEFAULT_NODE_MINUTES = 40;

    /** 空时间占位：表示该节尚未填写。 */
    public static final String UNSET = "";

    private TimeTableSettings() {
    }

    /** 默认时间表：华农 15 节。 */
    public static List<TimeNode> defaultTable() {
        return generate(DEFAULT_NODES);
    }

    /**
     * 按默认规则生成 1..count 节的时间表。
     *
     * <p>1..15 节取华农常量表；16..20 节（超出华农表）返回 {@link #UNSET} 空值，
     * 由用户手填——<b>不推演、不兜底</b>。之前的实现把第 16 节往后挤到 23:25-23:59
     * 的 4 分钟区间里，那是为了让「默认值合法」而造出的假数据，对用户毫无意义。</p>
     */
    public static List<TimeNode> generate(int count) {
        List<TimeNode> nodes = new ArrayList<>();
        int total = Math.max(1, Math.min(MAX_NODES, count));
        for (int node = 1; node <= total; node++) {
            nodes.add(node <= DEFAULT_NODES ? scauNode(node) : new TimeNode(node, UNSET, UNSET));
        }
        return nodes;
    }

    /** 第 node 节的华农时间。 */
    private static TimeNode scauNode(int node) {
        int index = node - 1;
        if (index < 0 || index >= DEFAULT_NODES) return new TimeNode(node, UNSET, UNSET);
        return new TimeNode(node, SCAU_START[index], SCAU_END[index]);
    }

    /**
     * 第 node 节的默认时间。
     *
     * <p>1..15 节取华农常量表；16..20 节返回空（{@link #UNSET}），由用户手填。</p>
     *
     * <p>与总节数无关：从常量表按 index 直取，不依赖 {@code generate(n)} 的末节，
     * 因此 {@code resize} 逐节补齐时前后一致、不会错位。</p>
     */
    public static TimeNode defaultNode(int node) {
        return scauNode(Math.max(1, Math.min(MAX_NODES, node)));
    }

    /**
     * 节数联动：返回恰好 1..nodeCount 的时间表。
     *
     * <p>已有节次保留原值，缺失节次按默认规则补齐；超出 nodeCount 的节次直接裁剪。
     * 老数据（只有 12 节或顺序错乱）也能得到连续、有序的结果。</p>
     *
     * <p><b>补齐时的防交叉规则（2026-10-25）：</b>直接套常量表会出问题——老用户第 5 节
     * 是旧作息的 14:00，而华农第 6 节是 12:40，补进去就「插在了前面」，整表变交叉、
     * 保存被拒。因此补齐时若默认值早于前一节结束，就把起点顺延到前一节结束之后
     * （保持原节时长），保证「补出来的表一定不交叉」。</p>
     */
    public static List<TimeNode> resize(List<TimeNode> current, int nodeCount) {
        int count = Math.max(1, Math.min(MAX_NODES, nodeCount));
        List<TimeNode> byNode = new ArrayList<>();
        for (int node = 1; node <= count; node++) {
            TimeNode existing = find(current, node);
            if (existing != null) {
                byNode.add(existing);
                continue;
            }
            byNode.add(fillAfter(defaultNode(node), byNode));
        }
        return byNode;
    }

    /**
     * 补齐的默认值若会与前一节交叉，就顺延到前一节结束之后（保留原时长）。
     *
     * <p>顺延不能跨天：前一节结束接近 23:59 时，再顺延 40 分钟会被 {@link #format(int)}
     * 取模成次日凌晨（23:25 + 40 → 00:05），反而变成「结束早于开始」的倒置。
     * 因此这里把结束时间夹在 23:59 之内，并保证至少有 1 分钟区间。</p>
     */
    private static TimeNode fillAfter(TimeNode node, List<TimeNode> placedBefore) {
        if (placedBefore.isEmpty() || isUnset(node)) return node;
        TimeNode previous = placedBefore.get(placedBefore.size() - 1);
        if (isUnset(previous)) return node;
        int previousEnd = minutes(previous.endTime);
        int start = minutes(node.startTime);
        int end = minutes(node.endTime);
        if (previousEnd < 0 || start < 0 || end < 0) return node;
        if (start >= previousEnd) return node;
        final int dayEnd = 23 * 60 + 59;
        int duration = Math.max(1, end - start);
        int newStart = Math.min(previousEnd, dayEnd - 1);
        int newEnd = Math.min(newStart + duration, dayEnd);
        if (newEnd <= newStart) newEnd = Math.min(newStart + 1, dayEnd);
        if (newEnd <= newStart) return new TimeNode(node.node, UNSET, UNSET);
        return new TimeNode(node.node, format(newStart), format(newEnd));
    }

    public static TimeNode find(List<TimeNode> nodes, int node) {
        if (nodes == null) return null;
        for (TimeNode item : nodes) {
            if (item != null && item.node == node) return item;
        }
        return null;
    }

    /** 该节是否尚未填写（起或止为空）。 */
    public static boolean isUnset(TimeNode node) {
        return node == null || isBlank(node.startTime) || isBlank(node.endTime);
    }

    private static boolean isBlank(String value) {
        return value == null || stripWhitespace(value).isEmpty();
    }

    /**
     * 校验整张表：每节 HH:mm 合法、开始早于结束、相邻节次不重叠（后一节开始 ≥ 前一节结束）。
     *
     * @return 错误文案，合法时返回空字符串
     */
    public static String validate(List<TimeNode> nodes, int nodeCount) {
        if (nodes == null || nodes.isEmpty()) return "时间表为空";
        List<TimeNode> sorted = new ArrayList<>();
        for (TimeNode node : nodes) {
            if (node != null) sorted.add(node);
        }
        Collections.sort(sorted, new Comparator<TimeNode>() {
            @Override
            public int compare(TimeNode left, TimeNode right) {
                return Integer.compare(left.node, right.node);
            }
        });
        if (sorted.size() != nodeCount) {
            return "时间表节次数量应为 " + nodeCount + " 节";
        }
        int expectedNode = 1;
        for (TimeNode node : sorted) {
            if (node.node != expectedNode) return "缺少第 " + expectedNode + " 节时间";
            expectedNode++;
        }
        Integer previousEnd = null;
        for (TimeNode node : sorted) {
            // 空节单独报「请填写」，不能落进下面的「格式」分支被误报成格式/倒置错误。
            if (isUnset(node)) {
                return "请填写第 " + node.node + " 节时间";
            }
            int start = minutes(node.startTime);
            int end = minutes(node.endTime);
            if (start < 0 || end < 0) {
                return "第 " + node.node + " 节时间格式应为 HH:mm";
            }
            if (start >= end) {
                return "第 " + node.node + " 节开始时间必须早于结束时间";
            }
            if (previousEnd != null && start < previousEnd) {
                return "第 " + node.node + " 节开始时间不能早于第 " + (node.node - 1) + " 节结束时间";
            }
            previousEnd = end;
        }
        return "";
    }

    /**
     * 保存前校验：用<b>目标节数</b>（用户改后的节数）而不是库里的旧节数。
     *
     * <p><b>这是保存误报的根因修复点。</b>{@code TimetableRepository.saveTimeNodes} 曾用
     * {@code sheet.config.nodes}（旧值）校验草稿；用户只把「每天节数」从 13 改成 15，
     * 草稿有 15 节而校验按 13 节比较，直接报「时间表节次数量应为 13 节」，
     * 界面文案又是「保存失败，请检查各节起止时间」——于是用户看到「我的时间没问题却保存不了」。</p>
     *
     * <p>现在把「草稿校验」与「落库校验」统一到这一个函数：先按目标节数规整，再整表校验。
     * 只要它返回空串，落库就一定成功；反之一定有真问题。</p>
     *
     * @param nodes         编辑后的草稿
     * @param targetNodeCount 目标节数（来自输入框/编辑器，而不是库里的旧配置）
     * @return 错误文案，合法时返回空串
     */
    public static String validateForSave(List<TimeNode> nodes, int targetNodeCount) {
        int count = Math.max(1, Math.min(MAX_NODES, targetNodeCount));
        List<TimeNode> normalized = resize(nodes, count);
        return validate(normalized, count);
    }

    /** 单节校验：用于 TimePicker 改完一节后立即提示。 */
    public static String validateNode(TimeNode node) {
        if (node == null) return "时间表为空";
        if (isUnset(node)) return "请填写第 " + node.node + " 节时间";
        int start = minutes(node.startTime);
        int end = minutes(node.endTime);
        if (start < 0 || end < 0) return "第 " + node.node + " 节时间格式应为 HH:mm";
        if (start >= end) return "第 " + node.node + " 节开始时间必须早于结束时间";
        return "";
    }

    /** HH:mm → 当天分钟数；非法返回 -1。接受 8:05 这类单位数小时写法。 */
    public static int minutes(String hhmm) {
        if (hhmm == null) return -1;
        // String.trim() 只去掉 <= U+0020 的字符，中文输入法打出的全角空格（U+3000）、
        // 不换行空格（U+00A0）、零宽空格（U+200B）都会残留，导致 digits() 失败返回 -1，
        // 最终被上层当成「时间格式错误」——这是"时间看着没问题却报格式错"的典型来源。
        String value = stripWhitespace(hhmm);
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) return -1;
        String hourPart = value.substring(0, colon);
        String minutePart = value.substring(colon + 1);
        if (!digits(hourPart) || !digits(minutePart)) return -1;
        int hour = Integer.parseInt(hourPart);
        int minute = Integer.parseInt(minutePart);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return -1;
        return hour * 60 + minute;
    }

    /** 去掉首尾所有空白类字符（含全角空格/不换行空格/零宽字符）。 */
    static String stripWhitespace(String text) {
        if (text == null) return "";
        int start = 0;
        int end = text.length();
        while (start < end && isBlankLike(text.charAt(start))) start++;
        while (end > start && isBlankLike(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    private static boolean isBlankLike(char c) {
        if (c <= ' ') return true;                       // 含 \t \n \r 普通空格
        return c == '\u3000' || c == '\u00A0' || c == '\u200B'
                || c == '\uFEFF' || c == '\u2028' || c == '\u2029' || c == '\u1680';
    }

    /** 分钟数 → HH:mm，跨天时取模。 */
    public static String format(int minutesOfDay) {
        int value = ((minutesOfDay % (24 * 60)) + 24 * 60) % (24 * 60);
        return String.format(Locale.US, "%02d:%02d", value / 60, value % 60);
    }

    /** 用新的起止时间替换某一节，返回新列表（不改入参）。 */
    public static List<TimeNode> withNode(List<TimeNode> current, int node, String startTime, String endTime) {
        List<TimeNode> result = new ArrayList<>();
        boolean replaced = false;
        for (TimeNode item : current) {
            if (item != null && item.node == node) {
                result.add(new TimeNode(node, startTime, endTime));
                replaced = true;
            } else if (item != null) {
                result.add(item);
            }
        }
        if (!replaced) result.add(new TimeNode(node, startTime, endTime));
        Collections.sort(result, new Comparator<TimeNode>() {
            @Override
            public int compare(TimeNode left, TimeNode right) {
                return Integer.compare(left.node, right.node);
            }
        });
        return result;
    }

    /** 某节展示区间：开始-结束；缺失时返回空串。 */
    public static String rangeLabel(List<TimeNode> nodes, int node) {
        TimeNode item = find(nodes, node);
        if (item == null) return "";
        return item.startTime + "-" + item.endTime;
    }

    /** 多节课程的整体时间区间，例如第 1-2 节 → 08:00-09:40；缺数据时返回空串。 */
    public static String spanLabel(List<TimeNode> nodes, int startNode, int endNode) {
        TimeNode start = find(nodes, startNode);
        TimeNode end = find(nodes, Math.max(startNode, endNode));
        if (start == null || end == null) return "";
        return start.startTime + "-" + end.endTime;
    }

    private static boolean digits(String value) {
        if (value.isEmpty() || value.length() > 2) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }
}
