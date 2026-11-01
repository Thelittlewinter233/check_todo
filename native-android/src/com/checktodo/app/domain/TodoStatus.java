package com.checktodo.app.domain;

/**
 * 待办状态机（纯 Java，不依赖 Android 类）：
 * <ul>
 *   <li>{@link #NOT_STARTED} 当前时间早于开始时间；</li>
 *   <li>{@link #IN_PROGRESS} 已开始且未过截止时间；</li>
 *   <li>{@link #EXPIRED} 未完成且当前时间已过截止时间；</li>
 *   <li>{@link #COMPLETED} 已勾选完成，优先级高于时间判定。</li>
 * </ul>
 */
public enum TodoStatus {
    NOT_STARTED,
    IN_PROGRESS,
    EXPIRED,
    COMPLETED
}
