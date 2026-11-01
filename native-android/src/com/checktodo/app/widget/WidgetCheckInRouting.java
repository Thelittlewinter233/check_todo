package com.checktodo.app.widget;

import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.TaskLogic;
import com.checktodo.app.domain.Todo;
import com.checktodo.app.domain.VerifyMode;

import java.util.Date;
import java.util.Set;

/**
 * 小卡片条目点击的路由决策（纯函数，JVM 可测）。
 *
 * <p><b>为什么抽出来</b>：AI 拍照判定任务的打卡必须回到 App 内走「相机 → L1 预检 →
 * L2 判定」链路，小卡片不能直接写库；而普通任务的打卡、以及「已完成 → 取消打卡确认」
 * 两条既有路径必须一字不改（零回归）。把三分支收敛成一个纯函数后，路由策略可测，
 * Provider 只剩「按决策执行 Intent」的薄壳。</p>
 *
 * <p><b>不触碰世代权威</b>：本类只在 {@code handleItemAction} 已通过
 * {@link WidgetFactorySnapshot#accepts(int)} 世代校验之后被调用，
 * 不参与模式判定、不读实时存储。</p>
 */
public final class WidgetCheckInRouting {

    private WidgetCheckInRouting() {
    }

    /** 点击条目后 Provider 应执行的动作。 */
    public enum Action {
        /** 普通任务且当前可打卡：卡片内直接写库（既有行为）。 */
        CHECK_IN,
        /** AI 拍照任务且当前可打卡：打开 App 进入拍照判定流程。 */
        OPEN_APP_AI_CHECKIN,
        /** 今日已完成：打开 App 走「取消今日打卡」确认链路（既有行为）。 */
        OPEN_APP_CANCEL,
        /** 普通待办：卡片内直接切换完成态（既有行为）。 */
        TOGGLE_TODO,
        /** AI 拍照待办且未完成：打开 App 进入拍照判定流程（完成即消失，卡片不写库）。 */
        OPEN_APP_AI_TODO,
        /** 其余状态（未开始 / 已错过 / 请假 / 非重复日）：卡片不执行动作。 */
        IGNORE
    }

    /**
     * 决策。
     *
     * @param task            被点击的任务；null → {@link Action#IGNORE}
     * @param completedDates  该任务已完成的日期键集合；null 视为空集
     * @param now             当前时刻
     */
    public static Action decide(Task task, Set<String> completedDates, Date now) {
        if (task == null || now == null) return Action.IGNORE;
        Set<String> completed = completedDates == null ? java.util.Collections.<String>emptySet()
                : completedDates;
        if (TaskLogic.canCheckIn(task, completed, now)) {
            return isAiPhoto(task) ? Action.OPEN_APP_AI_CHECKIN : Action.CHECK_IN;
        }
        if (completed.contains(TaskLogic.dateKey(now))) return Action.OPEN_APP_CANCEL;
        return Action.IGNORE;
    }

    /**
     * 待办行点击决策：卡片上只存在未完成待办（完成即消失），
     * 普通 → 卡片内切换；AI 拍照 → 打开 App 走拍照判定；已完成 → 忽略（防御）。
     */
    public static Action decideTodo(Todo todo) {
        if (todo == null || todo.completed) return Action.IGNORE;
        return todo.isAiPhoto() ? Action.OPEN_APP_AI_TODO : Action.TOGGLE_TODO;
    }

    /** 该任务是否需要拍照判定。 */
    public static boolean isAiPhoto(Task task) {
        return task != null && task.verifyMode == VerifyMode.AI_PHOTO;
    }

    /** 该待办是否需要拍照判定。 */
    public static boolean isAiPhoto(Todo todo) {
        return todo != null && todo.verifyMode == VerifyMode.AI_PHOTO;
    }

    /** 可打卡条目的动作按钮文案：AI 任务提示需要拍照。 */
    public static String activeActionLabel(Task task) {
        return isAiPhoto(task) ? "拍照打卡" : "打卡";
    }

    /** 未完成待办的动作按钮文案：AI 待办提示需要拍照。 */
    public static String todoActionLabel(Todo todo) {
        return isAiPhoto(todo) ? "拍照完成" : "完成";
    }
}
