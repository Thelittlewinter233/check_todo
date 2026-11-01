package com.checktodo.app.widget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 小卡片集合视图 Factory 的<b>快照式</b>状态机（纯 Java，可单测）。
 *
 * <p><b>为什么之前"偶发串状态"治不好：</b>旧实现 {@link WidgetFactoryState} 允许
 * {@code onDataSetChanged} 用<b>实时读取</b>的存储模式覆盖构造时从 URI 解析出的模式：</p>
 *
 * <pre>
 * // 旧代码（已删除）
 * int storedMode = state.mode();
 * if (appWidgetId != INVALID) storedMode = CheckTodoWidget.getMode(context, appWidgetId); // ← 实时读
 * ...
 * state.load(tasks.size(), todos.size(), courses.size(), storedMode);                    // ← 覆盖快照
 * </pre>
 *
 * <p>于是出现这条竞争：用户快速连点切换 → 存储模式已变成 B，但启动器还在用旧 Factory 的
 * 行；此时一次迟到的 {@code onDataSetChanged} 把 state 改成 B，而列表字段里装的仍是 A 模式的数据
 * → {@code getCount()} 返回 B 的行数、{@code getViewAt} 却从 A 的列表取 → 串视图、越界、点错条目。</p>
 *
 * <p><b>根治办法（三条不变量）：</b></p>
 * <ol>
 *   <li><b>构造即冻结</b>：{@code (widgetId, mode)} 在构造时确定，之后<b>永不修改</b>。
 *       任何"实时再读"都是 bug，本类不提供读取实时状态的入口。</li>
 *   <li><b>行数与内容同源</b>：{@link #bind} 一次性交给行数就代表内容已就绪，
 *       不存在"行数已更新、内容还是旧的"的中间态。</li>
 *   <li><b>模式可校验</b>：{@link #accepts(int)} 供 Provider 校验点击携带的 mode —— 旧模式的
 *       残留点击会被 {@link #staleReason(int)} 明确拒绝，而不是作用到新模式。</li>
 * </ol>
 *
 * <p>本类刻意<b>不</b>持有 Android 类型，因此"快速连续切换 + 数据变更"的竞争可以用纯 Java
 * 稳定复现（见 {@code WidgetFactorySnapshotTest}）。</p>
 */
public final class WidgetFactorySnapshot {

    /** 未绑定内容的快照：{@link #count()} 恒为 0，宁可空态也不吐旧行。 */
    public static final WidgetFactorySnapshot EMPTY =
            new WidgetFactorySnapshot(WidgetModes.MODE_TASKS, -1, 0, false);

    private final int mode;
    private final int widgetId;
    private final int count;
    private final boolean bound;

    private WidgetFactorySnapshot(int mode, int widgetId, int count, boolean bound) {
        this.mode = WidgetModes.normalize(mode);
        this.widgetId = widgetId;
        this.count = Math.max(0, count);
        this.bound = bound;
    }

    /**
     * 构造时冻结：<b>URI 模式是权威</b>，解析失败才回落存储模式。
     *
     * <p><b>为什么 URI 赢过存储（2026-10-26 真机「标题课表对上打卡正文」的根因）：</b>
     * 服务 URI 与卡片标题、切换按钮的 PendingIntent 是<b>同一次 {@code updateWidget}</b>
     * 里从同一个存储读数生成的——它们天然同代。而存储是异步漂移的另一路信号：启动器
     * 应用标题与 Factory 重建正文发生在不同时刻，若 Factory 以「更新的存储」覆盖 URI，
     * 正文就会跳到上屏标题之外的世代，出现 title 与内容错位一格、且随切换继续轮转。
     * 所以正文世代只跟 URI；存储推进世代的唯一合法路径是「启动器按新 data URI 重绑
     * → 新 Factory 冻结新模式」。</p>
     *
     * @param serviceUri   集合视图服务 Intent 的 data（{@code checktodo://widget/<id>/mode/<mode>}）
     * @param storedMode   存储模式，仅当 URI 无法解析（老启动器丢 data 的极端情况）时回落
     */
    public static WidgetFactorySnapshot freeze(int widgetId, String serviceUri, int storedMode) {
        int fromUri = WidgetModes.modeFromUri(serviceUri);
        int mode = fromUri >= 0 ? WidgetModes.normalize(fromUri) : WidgetModes.normalize(storedMode);
        return new WidgetFactorySnapshot(mode, widgetId, 0, false);
    }

    /** 测试/内部使用：直接指定模式冻结。 */
    public static WidgetFactorySnapshot of(int widgetId, int mode) {
        return new WidgetFactorySnapshot(mode, widgetId, 0, false);
    }

    /**
     * {@code onDataSetChanged} 里一次性绑定内容：
     * <b>行数就是在这一步算好的</b>，之后 {@code getViewAt} 只读快照，不再查任何仓库。
     */
    public WidgetFactorySnapshot bind(int rowCount) {
        return new WidgetFactorySnapshot(mode, widgetId, rowCount, true);
    }

    public int mode() {
        return mode;
    }

    public int widgetId() {
        return widgetId;
    }

    /** 当前行数；未绑定内容时为 0。 */
    public int count() {
        return count;
    }

    /** 是否已完成一次内容绑定。未绑定时 {@code getViewAt} 返回 null，不吐旧行。 */
    public boolean loaded() {
        return bound;
    }

    /**
     * 校验点击携带的模式。
     *
     * @return 空串 = 可处理；非空 = 丢弃原因（供诊断日志）
     */
    public String staleReason(int clickedMode) {
        if (clickedMode == mode) return "";
        return "模式已切换（点击属于 " + WidgetModes.title(clickedMode)
                + "，当前 " + WidgetModes.title(mode) + "），已丢弃";
    }

    public boolean accepts(int clickedMode) {
        return clickedMode == mode;
    }

    /** 快照模式下的行数上限校验：position 越界时返回 false（触发 getViewAt 返回 null）。 */
    public boolean hasRow(int position) {
        return position >= 0 && position < count;
    }

    /** 诊断用：快照的不可变描述。 */
    public String describe() {
        return "widget=" + widgetId + " mode=" + WidgetModes.title(mode) + " rows=" + count;
    }

    /**
     * 列表内容在 {@code onDataSetChanged} 里一次性重建后的不可变载体。
     *
     * <p>{@code getViewAt} 只能从这里取行，禁止"当时再查仓库"。
     * 行与快照的模式绑定，交给 UI 前已定型。</p>
     */
    public static final class Rows<T> {
        private final int mode;
        private final List<T> items;

        private Rows(int mode, List<T> items) {
            this.mode = mode;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }

        public static <T> Rows<T> of(int mode, List<T> items) {
            return new Rows<>(mode, items == null ? new ArrayList<T>() : items);
        }

        public int mode() {
            return mode;
        }

        public int size() {
            return items.size();
        }

        public T get(int position) {
            return items.get(position);
        }

        public List<T> items() {
            return items;
        }

        public boolean has(int position) {
            return position >= 0 && position < items.size();
        }
    }
}
