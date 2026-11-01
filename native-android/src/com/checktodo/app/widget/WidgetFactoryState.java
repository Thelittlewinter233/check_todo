package com.checktodo.app.widget;

/**
 * 小卡片 Factory 的「模式 + 行数 + 是否已加载」纯 Java 状态机。
 *
 * <p>真机问题：当前课表没有课程时切换三视图会串状态——个别启动器在
 * {@code onDataSetChanged} 之前就向 Factory 取视图，或复用旧 Factory 的行缓存，
 * 导致课表视图标题下仍显示上一个模式的打卡/待办条目。</p>
 *
 * <p><b>已废弃（2026-10-25）：</b>本类允许 {@code onDataSetChanged} 用实时存储模式覆盖
 * 构造时的模式，正是串状态的根因。请改用 {@link WidgetFactorySnapshot}（构造即冻结、
 * 行数与内容同源、模式可校验）。本类仅供旧单测引用，不再被生产代码使用。</p>
 *
 * <p>规则：</p>
 * <ul>
 *   <li>服务 URI 只作为 Factory 重建瞬间的初始模式（data 随模式变化）。</li>
 *   <li>存储模式是唯一事实源，{@code onDataSetChanged} 时以它覆盖。</li>
 *   <li>加载完成前一律不可渲染、行数为 0，杜绝旧行残留。</li>
 * </ul>
 */
@Deprecated
public final class WidgetFactoryState {
    private int mode = WidgetModes.MODE_TASKS;
    private int tasks;
    private int todos;
    private int courses;
    private boolean loaded;

    public WidgetFactoryState(String serviceUri) {
        int fromUri = WidgetModes.modeFromUri(serviceUri);
        if (fromUri >= 0) mode = WidgetModes.normalize(fromUri);
    }

    /** onDataSetChanged 时调用：以存储模式为准刷新行数。 */
    public void load(int tasks, int todos, int courses, int storedMode) {
        this.mode = WidgetModes.normalize(storedMode);
        this.tasks = Math.max(0, tasks);
        this.todos = Math.max(0, todos);
        this.courses = Math.max(0, courses);
        this.loaded = true;
    }

    /** 未完成一次 onDataSetChanged 之前不渲染任何行，避免串视图。 */
    public boolean canRender() {
        return loaded;
    }

    public int count() {
        if (!loaded) return 0;
        if (mode == WidgetModes.MODE_TODOS) return todos;
        return mode == WidgetModes.MODE_COURSES ? courses : tasks;
    }

    public int mode() {
        return mode;
    }
}
