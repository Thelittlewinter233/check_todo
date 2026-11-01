import com.checktodo.app.widget.WidgetModeStore;
import com.checktodo.app.widget.WidgetModes;
import com.checktodo.app.widget.WidgetSwitchLogic;

/**
 * 复现真机问题：桌面三视图卡片「串状态」且切换时整体 off-by-one 轮转。
 *
 * <p>根因：切换按钮的 PendingIntent 只带 appWidgetId，ACTION_SWITCH 用 <b>存储模式 +1</b>
 * 写回。启动器在内存压力/重建后会继续渲染上一代 RemoteViews（用户看到的模式与存储不一致），
 * 此时按「切换」等于基于看不见的状态轮转 → 每张卡都错一位，且无法自愈。</p>
 *
 * <p>修复口径（本测试即规格）：切换按钮携带「所见模式」（该代 RemoteViews 渲染的模式），
 * Provider 以 seen+1 为准并写回存储，模式由此自愈；没有携带模式的旧卡片点击回落存储。</p>
 */
public final class WidgetSwitchStaleTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testSeenModeDrivesSwitch();
        testStaleClickSelfHeals();
        testCycleCloses();
        testUnknownFallsBackToStore();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    /** 所见模式 +1，而不是存储值 +1。 */
    private static void testSeenModeDrivesSwitch() {
        WidgetModeStore store = new WidgetModeStore.Memory();
        store.write(7, WidgetModes.MODE_COURSES);       // 存储已是课表
        WidgetSwitchLogic.Result result = WidgetSwitchLogic.apply(store, 7,
                WidgetModes.MODE_TASKS);                // 用户看到的却是打卡
        check("所见打卡 +1 得到待办（不是以存储课表为基础轮转）",
                result.mode == WidgetModes.MODE_TODOS);
        check("结果写回存储", store.read(7) == WidgetModes.MODE_TODOS);
        check("所见 != 存储时标记为旧代", result.stale);

        // 正常路径：所见与存储一致，不算旧代。
        WidgetModeStore normal = new WidgetModeStore.Memory();
        normal.write(8, WidgetModes.MODE_TODOS);
        WidgetSwitchLogic.Result next = WidgetSwitchLogic.apply(normal, 8, WidgetModes.MODE_TODOS);
        check("所见 == 存储时不视为旧代", !next.stale && next.mode == WidgetModes.MODE_COURSES);
    }

    /** 旧代点击：以所见模式为准写回，实现自愈。 */
    private static void testStaleClickSelfHeals() {
        WidgetModeStore store = new WidgetModeStore.Memory();
        store.write(9, WidgetModes.MODE_TODOS);         // 存储 = 待办
        WidgetSwitchLogic.Result result = WidgetSwitchLogic.apply(store, 9,
                WidgetModes.MODE_COURSES);              // 所见 = 课表（旧代）
        check("旧代点击被标记 stale", result.stale);
        check("旧代点击从所见课表继续轮转", result.mode == WidgetModes.MODE_TASKS);
        check("自愈：存储被拉回所见轮转结果", store.read(9) == WidgetModes.MODE_TASKS);
        check("自愈后需要整卡刷新", result.requiresRefresh);
    }

    /** 打卡→待办→课表→打卡 闭环，每步都从所见模式出发。 */
    private static void testCycleCloses() {
        WidgetModeStore store = new WidgetModeStore.Memory();
        int mode = WidgetModes.MODE_TASKS;
        int[] expected = {WidgetModes.MODE_TODOS, WidgetModes.MODE_COURSES, WidgetModes.MODE_TASKS,
                WidgetModes.MODE_TODOS};
        boolean ok = true;
        for (int want : expected) {
            mode = WidgetSwitchLogic.apply(store, 3, mode).mode;
            ok = ok && mode == want;
        }
        check("模式轮转闭环（连续四次切换回到正确序列）", ok);
        check("闭环轮转后存储与所见同步", store.read(3) == mode);
    }

    /** 未携带模式（升级前的老卡片）：回落存储值 +1，保持旧行为。 */
    private static void testUnknownFallsBackToStore() {
        WidgetModeStore store = new WidgetModeStore.Memory();
        store.write(5, WidgetModes.MODE_TODOS);
        WidgetSwitchLogic.Result result = WidgetSwitchLogic.apply(store, 5,
                WidgetSwitchLogic.UNKNOWN);
        check("未知所见模式回落存储", result.mode == WidgetModes.MODE_COURSES);
        check("回落分支不报 stale", !result.stale);
        check("回落分支同样写回", store.read(5) == WidgetModes.MODE_COURSES);
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }
}
