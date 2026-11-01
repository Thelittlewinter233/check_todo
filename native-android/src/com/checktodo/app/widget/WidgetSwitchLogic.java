package com.checktodo.app.widget;

/**
 * 切换按钮的模式推进逻辑（纯 Java，可单测）。
 *
 * <p><b>2026-10-26 真机问题：</b>卡片之间「串状态」，按「切换」后整体 off-by-one 轮转且不自愈。</p>
 *
 * <p><b>根因：</b>切换按钮的 PendingIntent 只带 {@code appWidgetId}，{@code ACTION_SWITCH}
 * 分支用 {@code nextMode(getMode(...))}——即「<b>存储模式</b> +1」。而启动器在内存压力 /
 * widget 重建后会继续渲染上一代 RemoteViews，此时<b>用户看到的模式 ≠ 存储模式</b>：
 * 用户看到课表，按切换却按存储里的打卡 +1 得到待办，下一张卡同理再错一位 → 整体像轮转。
 * 条目点击有 {@code EXTRA_MODE} 校验，切换按钮却没有，所以这条路径永远自愈不了。</p>
 *
 * <p><b>修复口径：</b>切换按钮的 PendingIntent 同样携带「这一代渲染所见的模式」，
 * 以<b>所见模式</b>为基准 +1 写回存储：</p>
 * <ul>
 *   <li>所见 == 存储：正常前进一格；</li>
 *   <li>所见 ≠ 存储（旧代点击）：标记 {@link Result#stale}，但仍按所见 +1 并写回存储，
 *       于是存储被拉回到用户真正看到的那一代，下一次点击即恢复正常 —— <b>自愈</b>；</li>
 *   <li>未携带模式（升级前的老卡片）：回落存储值 +1，与旧行为一致。</li>
 * </ul>
 */
public final class WidgetSwitchLogic {

    /** 未携带「所见模式」的点击。 */
    public static final int UNKNOWN = -1;

    private WidgetSwitchLogic() {
    }

    /** 一次切换的结果：推进后的模式 + 是否属于旧代点击 + 是否必须整卡刷新。 */
    public static final class Result {
        public final int mode;
        public final boolean stale;
        public final boolean requiresRefresh;

        Result(int mode, boolean stale, boolean requiresRefresh) {
            this.mode = mode;
            this.stale = stale;
            this.requiresRefresh = requiresRefresh;
        }
    }

    /**
     * 以「所见模式」为准推进一格并写回存储。
     *
     * @param store    模式存储
     * @param appWidgetId 卡片实例 id
     * @param seenMode 该代 RemoteViews 渲染的模式；{@link #UNKNOWN} 表示未携带
     */
    public static Result apply(WidgetModeStore store, int appWidgetId, int seenMode) {
        int stored = store.read(appWidgetId);
        boolean carried = seenMode != UNKNOWN;
        int base = carried ? WidgetModes.normalize(seenMode) : stored;
        int mode = WidgetModes.next(base);
        // 存储先写：启动器下一轮 onUpdate / 复用 Factory 都以存储为权威。
        store.write(appWidgetId, mode);
        boolean stale = carried && WidgetModes.normalize(seenMode) != stored;
        return new Result(mode, stale, true);
    }
}
