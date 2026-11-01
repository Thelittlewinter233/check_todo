package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 时间表编辑的<b>单一数据源</b>。
 *
 * <p><b>为什么需要这个类：</b>修复一个真实缺陷——在「节次时间表」弹窗里增/删一节后返回，
 * 「课表设置」里的「每天节数」输入框数值不跟着变。根因是节数在两处各存一份：</p>
 * <ul>
 *   <li>设置弹窗：{@code int[] draftCount}（配合 {@code timeTableDraft} 列表）</li>
 *   <li>时间表弹窗：{@code int[] liveCount}</li>
 * </ul>
 * <p>两边靠回调手动对齐，任一路径漏调同步就脱节。而且输入框本身也是第三个"副本"，
 * 用户改输入框、弹窗增删节，三条写入路径互相覆盖。</p>
 *
 * <p>现在把「节数 + 节点列表」收进同一个对象，任何改动都必须经过它的方法，
 * 不可能再出现两处数值不一致。纯 Java、无 Android 依赖，可直接单测。</p>
 *
 * <p><b>设计取舍：</b></p>
 * <ul>
 *   <li>节数与节点列表<b>永远同长</b>：{@link #nodeCount()} 直接返回 {@code nodes.size()}，
 *       不单独存一个 int，从结构上消除"两个数字打架"的可能；</li>
 *   <li>改动都是"保留已填值"：裁剪只砍尾部，扩充按默认规则补，不会丢用户已填的时间；</li>
 *   <li>所有方法返回 {@code this} 便于链式调用，并保证幂等（重复设同一节数不产生变化）。</li>
 * </ul>
 */
public final class TimeTableDraft {

    /** 合法节数区间，与设置弹窗的 1..20 校验一致。 */
    public static final int MIN_NODES = 1;
    public static final int MAX_NODES = TimeTableSettings.MAX_NODES;

    private final List<TimeNode> nodes = new ArrayList<>();

    private TimeTableDraft(List<TimeNode> initial) {
        if (initial != null) {
            for (TimeNode node : initial) {
                if (node != null) nodes.add(node);
            }
        }
    }

    /** 用当前已保存的时间表与节数建立草稿（会按节数裁剪/补齐）。 */
    public static TimeTableDraft of(List<TimeNode> current, int nodeCount) {
        TimeTableDraft draft = new TimeTableDraft(null);
        draft.applyCount(nodeCount, current);
        return draft;
    }

    /** 空草稿（内部为全默认表，节数 = 1）。 */
    public static TimeTableDraft empty() {
        TimeTableDraft draft = new TimeTableDraft(null);
        draft.applyCount(MIN_NODES, null);
        return draft;
    }

    /** 当前节数。恒等于 {@link #nodes()} 的长度。 */
    public int nodeCount() {
        return nodes.size();
    }

    /** 节点快照（不可变视图语义：调用方拿到的副本，改动不影响本对象）。 */
    public List<TimeNode> nodes() {
        return new ArrayList<>(nodes);
    }

    public TimeNode node(int index) {
        return TimeTableSettings.find(nodes, index);
    }

    /**
     * 设置节数（用户直接在输入框里改时调用）。
     *
     * <p>保留已有节次的时间，缺少的按默认规则补齐，多余的裁掉尾部。</p>
     *
     * @return true = 节数确实变了
     */
    public boolean setNodeCount(int requested) {
        int next = clamp(requested);
        if (next == nodes.size()) return false;
        applyCount(next, nodes);
        return true;
    }

    /** 加一节（编辑器「增加一节」）。到上限返回 false。 */
    public boolean addNode() {
        if (nodes.size() >= MAX_NODES) return false;
        applyCount(nodes.size() + 1, nodes);
        return true;
    }

    /** 删一节（编辑器「删除一节」，从尾部砍）。到下限返回 false。 */
    public boolean removeNode() {
        if (nodes.size() <= MIN_NODES) return false;
        applyCount(nodes.size() - 1, nodes);
        return true;
    }

    /** 恢复默认：把 1..当前节数的每节时间重置为默认值，节数保持不变。 */
    public void resetDefaults() {
        applyCount(nodes.size(), null);
    }

    /** 用新的起止时间替换某一节；非法或不存在的节次返回 false。 */
    public boolean setNodeTime(int node, String startTime, String endTime) {
        if (node < 1 || node > nodes.size()) return false;
        if (TimeTableSettings.minutes(startTime) < 0 || TimeTableSettings.minutes(endTime) < 0) {
            return false;
        }
        List<TimeNode> next = TimeTableSettings.withNode(nodes, node, startTime, endTime);
        // 节数不变：withNode 只替换一节，长度应保持一致。
        int count = nodes.size();
        nodes.clear();
        nodes.addAll(TimeTableSettings.resize(next, count));
        return true;
    }

    /** 整表校验（保存时调用）。返回第一条问题，合法时返回空串。 */
    public String validate() {
        return TimeTableSettings.validate(nodes, nodes.size());
    }

    /**
     * 找出所有有问题的节次（行内静默标记用：编辑器渲染时把这些行标红）。
     *
     * <p>判定口径与 {@link #validate()} 一致，但返回全部问题而不是第一条，
     * 且不依赖整体数量（编辑过程中节数可能还没对齐）。</p>
     */
    public List<Issue> issues() {
        List<Issue> out = new ArrayList<>();
        Integer previousEnd = null;
        int previousNode = 0;
        for (int i = 0; i < nodes.size(); i++) {
            TimeNode node = nodes.get(i);
            if (node == null) continue;
            // 空节（华农作息只有 15 节，第 16 节起默认为空）必须单独提示「请填写」，
            // 不能落进下面的「格式」分支被误报成格式错误或倒置；也退出相邻比较链。
            if (TimeTableSettings.isUnset(node)) {
                out.add(new Issue(node.node, "请填写第 " + node.node + " 节时间"));
                previousEnd = null;
                continue;
            }
            int start = TimeTableSettings.minutes(node.startTime);
            int end = TimeTableSettings.minutes(node.endTime);
            if (start < 0 || end < 0) {
                out.add(new Issue(node.node, "第 " + node.node + " 节时间格式应为 HH:mm"));
                previousEnd = null;
                continue;
            }
            if (start >= end) {
                out.add(new Issue(node.node, "第 " + node.node + " 节开始时间必须早于结束时间"));
                previousEnd = null;
                continue;
            }
            if (previousEnd != null && start < previousEnd) {
                out.add(new Issue(node.node, "第 " + node.node + " 节与第 " + previousNode
                        + " 节时间交叉"));
            }
            previousEnd = end;
            previousNode = node.node;
        }
        return out;
    }

    /** 某一节是否有问题（渲染行时用）。 */
    public String issueOf(int node) {
        for (Issue issue : issues()) {
            if (issue.node == node) return issue.message;
        }
        return "";
    }

    public boolean isValid() {
        return validate().isEmpty();
    }

    /**
     * 把多条行内问题拼成一句可读摘要（节次号 + 原因，用「；」连接）。
     *
     * <p>为什么列出全部而不是第一条：用户反馈「我的时间设置没有问题」，说明他对问题出在哪一节
     * 毫无头绪。只给第一条会让他改完一处再被拒一次，反复试错；一次列出所有问题行，
     * 配合编辑器里的红字，用户能一轮改完。</p>
     *
     * <p>条数过多时（>6）保留前 6 条并提示剩余数量，避免胶囊条撑爆屏幕。</p>
     */
    public static String summarize(List<Issue> issues) {
        if (issues == null || issues.isEmpty()) return "";
        int limit = Math.min(issues.size(), 6);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (i > 0) out.append("；");
            out.append(issues.get(i).message);
        }
        if (issues.size() > limit) {
            out.append("；等共 ").append(issues.size()).append(" 处问题");
        }
        return out.toString();
    }

    // ------------------------------------------------------------------

    /** 一条行内问题。 */
    public static final class Issue {
        public final int node;
        public final String message;

        Issue(int node, String message) {
            this.node = node;
            this.message = message;
        }

        @Override
        public String toString() {
            return message;
        }
    }

    private void applyCount(int requested, List<TimeNode> source) {
        int count = clamp(requested);
        List<TimeNode> resized = TimeTableSettings.resize(source, count);
        nodes.clear();
        nodes.addAll(resized);
    }

    private static int clamp(int value) {
        if (value < MIN_NODES) return MIN_NODES;
        return Math.min(value, MAX_NODES);
    }
}
