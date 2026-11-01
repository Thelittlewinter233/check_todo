package com.checktodo.app.domain;

/**
 * 一次提取的状态机（纯 Java，可 JVM 测试）。
 *
 * <p><b>为什么需要它：</b>用户要求「取消 30s 上限、加停止按钮」，这两件事都改变了
 * 「什么时候结束」的语义。旧实现把结束条件写死在时间预算里，按钮恢复依赖超时；
 * 新模型下必须有一个显式状态机来回答三个问题：</p>
 * <ol>
 *   <li>现在能不能开始一次提取？（{@link State#IDLE} 才可以）</li>
 *   <li>现在能不能停止？（{@link State#RUNNING} 才可以；重复点停止是幂等的）</li>
 *   <li>停止之后是否保留已拿到的结果？（<b>保留</b>——这是用户明确要求的）</li>
 * </ol>
 *
 * <p><b>为什么单个布尔不够：</b>「停止」和「自然完成」和「看门狗强制结束」三路都能让 UI 恢复，
 * 但它们对结果的处置不同：</p>
 * <ul>
 *   <li>自然完成：结果完整，正常导入；</li>
 *   <li>用户停止：<b>保留</b>已捕获与已解析的内容（不能因为用户手快点了停止就丢掉数据）；</li>
 *   <li>看门狗触发：说明后台真的死锁了，结果可能不完整，但仍保留已经拿到的部分。</li>
 * </ul>
 *
 * <p>本类线程安全。</p>
 */
public final class ExtractionState {

    public enum State {
        /** 空闲：可以开始。 */
        IDLE,
        /** 运行中：按钮显示为「停止」。 */
        RUNNING,
        /** 用户主动停止。 */
        STOPPED,
        /** 自然完成。 */
        COMPLETED,
        /** 被看门狗判定为死锁后强制结束。 */
        WATCHDOG_ABORTED
    }

    /** 结束原因，进状态栏与诊断。 */
    private State state = State.IDLE;
    private long startedAt;
    private long endedAt;

    /** 尝试开始。已在运行则返回 false（防连点）。 */
    public synchronized boolean start(long nowMillis) {
        if (state == State.RUNNING) return false;
        state = State.RUNNING;
        startedAt = nowMillis;
        endedAt = 0;
        return true;
    }

    /**
     * 请求停止。
     *
     * @return true = 这次调用真的从 RUNNING 转出去了（调用方应执行一次收尾）；
     *         false = 当前不在运行，或已被别的路径收尾（幂等）
     */
    public synchronized boolean stop(long nowMillis) {
        if (state != State.RUNNING) return false;
        state = State.STOPPED;
        endedAt = nowMillis;
        return true;
    }

    /** 自然完成。 */
    public synchronized boolean complete(long nowMillis) {
        if (state != State.RUNNING) return false;
        state = State.COMPLETED;
        endedAt = nowMillis;
        return true;
    }

    /** 看门狗强制结束。 */
    public synchronized boolean abortByWatchdog(long nowMillis) {
        if (state != State.RUNNING) return false;
        state = State.WATCHDOG_ABORTED;
        endedAt = nowMillis;
        return true;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized boolean isRunning() {
        return state == State.RUNNING;
    }

    /** 是否应该保留已拿到的数据（除 IDLE 外都保留）。 */
    public synchronized boolean keepsResults() {
        return state == State.STOPPED || state == State.COMPLETED
                || state == State.WATCHDOG_ABORTED;
    }

    public synchronized long elapsed(long nowMillis) {
        if (startedAt == 0) return 0;
        long end = endedAt > 0 ? endedAt : nowMillis;
        return Math.max(0L, end - startedAt);
    }

    /** 状态栏文案。 */
    public synchronized String statusText(long nowMillis) {
        switch (state) {
            case RUNNING:
                return "提取中… 已运行 " + elapsed(nowMillis) / 1000 + "s（可点「停止」）";
            case STOPPED:
                return "已停止（保留已捕获结果）· 用时 " + elapsed(nowMillis) / 1000 + "s";
            case COMPLETED:
                return "提取完成 · 用时 " + elapsed(nowMillis) / 1000 + "s";
            case WATCHDOG_ABORTED:
                return "后台无响应，已强制结束并保留已有结果（" + elapsed(nowMillis) / 1000 + "s）";
            default:
                return "未开始";
        }
    }

    /** 回到空闲，允许下一次提取。 */
    public synchronized void reset() {
        state = State.IDLE;
        startedAt = 0;
        endedAt = 0;
    }
}
