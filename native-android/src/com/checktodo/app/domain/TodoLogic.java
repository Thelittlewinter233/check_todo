package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class TodoLogic {
    private static final int MAX_DURATION_MINUTES = 24 * 60;
    /** 小卡片上「未开始」待办的展示窗口：开始时间距今 48 小时以内。 */
    public static final long WIDGET_UPCOMING_WINDOW_MS = 48L * 60 * 60 * 1000;

    private TodoLogic() {
    }

    public static List<String> validateTodo(String title, long startTime, long endTime,
                                            int durationMinutes) {
        List<String> errors = new ArrayList<>();
        if (title == null || title.trim().isEmpty()) errors.add("请填写待办标题");
        if (endTime <= startTime) errors.add("结束时间必须晚于开始时间");
        if (durationMinutes < 0 || durationMinutes > MAX_DURATION_MINUTES) {
            errors.add("花费时长必须在 0 到 1440 分钟之间");
        }
        return errors;
    }

    public static List<Todo> sortByEndTime(List<Todo> todos) {
        List<Todo> result = new ArrayList<>(todos);
        Collections.sort(result, new Comparator<Todo>() {
            @Override
            public int compare(Todo left, Todo right) {
                int byEnd = Long.compare(left.endTime, right.endTime);
                return byEnd != 0 ? byEnd : Long.compare(left.createdAt, right.createdAt);
            }
        });
        return Collections.unmodifiableList(result);
    }

    /**
     * 紧急度排序（2026-10-27 需求变更）：按「最晚开始时刻 = endTime − 花费时长」升序——
     * 越紧急越靠前；同一时刻按创建先后。展示列表与小卡片统一走本排序（pending /
     * completed / filterWidgetTodos），保证 App 内与卡片口径一致。
     */
    public static List<Todo> sortByUrgency(List<Todo> todos) {
        List<Todo> result = new ArrayList<>(todos);
        result.sort((left, right) -> {
            int byKey = Long.compare(urgencyKey(left), urgencyKey(right));
            return byKey != 0 ? byKey : Long.compare(left.createdAt, right.createdAt);
        });
        return Collections.unmodifiableList(result);
    }

    /** 最晚开始时刻（epoch 毫秒）：截止时刻 − 花费时长（负值按默认 60 分钟兜底）。 */
    private static long urgencyKey(Todo todo) {
        return todo.endTime - ReminderLogic.requiredTodoMinutes(todo) * 60_000L;
    }

    public static List<Todo> pending(List<Todo> todos) {
        List<Todo> result = new ArrayList<>();
        for (Todo todo : sortByUrgency(todos)) if (!todo.completed) result.add(todo);
        return Collections.unmodifiableList(result);
    }

    public static List<Todo> completed(List<Todo> todos) {
        List<Todo> result = new ArrayList<>();
        for (Todo todo : sortByUrgency(todos)) if (todo.completed) result.add(todo);
        return Collections.unmodifiableList(result);
    }

    /**
     * 待办状态判定：完成态优先，其次按当前时间落在开始/截止区间的位置归类。
     */
    public static TodoStatus status(Todo todo, long now) {
        if (todo.completed) return TodoStatus.COMPLETED;
        if (now < todo.startTime) return TodoStatus.NOT_STARTED;
        if (now < todo.endTime) return TodoStatus.IN_PROGRESS;
        return TodoStatus.EXPIRED;
    }

    /**
     * 小卡片展示过滤（纯函数）：已过期与进行中全部显示；未开始只显示开始时间在
     * 未来 48 小时之内（含恰好 48 小时）的条目；<b>已完成不显示</b>——2026-10-28
     * 需求变更：卡片上完成即消失，撤销统一回 App 内待办列表做（{@link #completed}
     * 与 MainActivity 的撤销功能不受影响）。
     * 结果按 {@link #sortByUrgency} 升序，已过期的最紧急，因此自然排在列表顶部。
     */
    public static List<Todo> filterWidgetTodos(List<Todo> todos, long now) {
        List<Todo> result = new ArrayList<>();
        for (Todo todo : sortByUrgency(todos)) {
            if (todo.completed) continue;
            TodoStatus status = status(todo, now);
            if (status == TodoStatus.NOT_STARTED
                    && todo.startTime - now > WIDGET_UPCOMING_WINDOW_MS) {
                continue;
            }
            result.add(todo);
        }
        return Collections.unmodifiableList(result);
    }

    public static String formatDuration(int minutes) {
        if (minutes <= 0) return "未记录时长";
        return minutes / 60 > 0
                ? minutes / 60 + " 小时 " + minutes % 60 + " 分钟"
                : minutes + " 分钟";
    }
}
