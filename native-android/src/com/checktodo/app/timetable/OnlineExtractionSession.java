package com.checktodo.app.timetable;

import com.checktodo.app.domain.OnlineExtraction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次「提取课表」的运行时上下文：后台单线程 executor、分块拼装器、桥接对象、
 * 诊断缓冲与硬超时表。
 *
 * <p><b>为什么要一个专门的类：</b>旧实现在
 * {@code TimetableController#extractOnlinePage} 里用一堆互相引用的 final 局部变量 +
 * 匿名 Runnable 把「发现 / 取数据 / 解析 / 展示」揉成 100 多行的嵌套结构，导致两件事做不到：</p>
 * <ul>
 *   <li><b>无法硬超时</b>：没有统一的可取消上下文，{@code evaluateJavascript} 的回调不回来
 *       就永远等下去。</li>
 *   <li><b>无法中途看进度</b>：诊断只在全流程结束时一次性赋值。</li>
 * </ul>
 *
 * <p>本类把所有可变状态集中到一处，并保证：</p>
 * <ol>
 *   <li>所有阶段都在<b>同一个后台单线程</b>上跑（{@code worker}）。WebView 的桥接回调也投递到
 *       这个 executor，因此 {@link OnlineExtraction.ChunkAssembler} 天然串行、无需加锁。</li>
 *   <li>{@link #close()} 幂等：取消 deadline、关闭桥接、{@code shutdownNow} executor，
 *       三重保证不再有任何任务排在后面。</li>
 *   <li>阶段等待统一走 {@link #await}：带剩余预算，超时返回 false 而不是无限阻塞。</li>
 * </ol>
 */
final class OnlineExtractionSession {

    /**
     * 全流程预算：<b>无上限</b>（用户明确要求取消 30s 硬上限）。
     *
     * <p>理由：被动捕获是持续性的——用户可能要在教务系统里翻页、切学期、多次查询，
     * 数据才逐步进入捕获池。固定 30s 会把这些正当操作全部判死。改为由用户点「停止」
     * 或自然跑完来结束。</p>
     */
    static final long TOTAL_BUDGET_MS = Long.MAX_VALUE / 4;

    /**
     * 通道 1（frame 发现）单步上限：<b>10s</b>。
     *
     * <p>原为 3s，真机在华农 WebVPN 页面（改写过整页 DOM + echarts + 嵌套 iframe）上稳定超时。
     * 现在发现脚本已拆成「只列路径」的超轻量版本，10s 是给慢设备/巨 DOM 的余量，
     * 而不是掩盖脚本本身的开销。</p>
     */
    static final int DISCOVER_TIMEOUT_MS = 10_000;

    /** 单个候选 fetch 的上限：8s。 */
    static final int FETCH_TIMEOUT_MS = 8_000;

    /** 单个候选 Cookie 直取的上限：8s。 */
    static final int DIRECT_TIMEOUT_MS = 8_000;

    /** 单轮最多取几个候选（fetch 代理 + 直取合计）。 */
    static final int MAX_CANDIDATES = 20;

    /** 单轮最多尝试几个候选用 fetch 代理（其余直接走直取）。 */
    static final int MAX_FETCH_PROXY_CANDIDATES = 8;

    /**
     * 看门狗：只有「连续 N 毫秒没有任何活动」才强制恢复按钮。
     *
     * <p>它<b>不是</b>正常流程的一部分——正常结束靠自然完成或用户停止。
     * 这里只防真正的死锁（后台线程被原生调用永久卡住、桥接彻底失联）。</p>
     */
    static final long WATCHDOG_IDLE_MS = 180_000L;

    private final long startMillis;
    final OnlineExtraction.ExtractionDeadline deadline;
    final OnlineExtraction.Diagnostics diagnostics;
    final OnlineExtraction.ChunkAssembler assembler = new OnlineExtraction.ChunkAssembler();
    private final ExecutorService worker;
    private final ChunkBridge bridge;
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile boolean closed;
    /** 最近一次「有进展」的时间戳，看门狗据此判断是否真死锁。 */
    private volatile long lastActivityMillis;

    OnlineExtractionSession() {
        this.startMillis = System.currentTimeMillis();
        this.deadline = new OnlineExtraction.ExtractionDeadline(startMillis, TOTAL_BUDGET_MS);
        this.diagnostics = new OnlineExtraction.Diagnostics(startMillis);
        this.lastActivityMillis = startMillis;
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "online-extract");
                thread.setDaemon(true);
                return thread;
            }
        });
        this.bridge = new ChunkBridge(assembler, worker);
    }

    ChunkBridge bridge() {
        return bridge;
    }

    ExecutorService worker() {
        return worker;
    }

    boolean isClosed() {
        return closed;
    }

    long startMillis() {
        return startMillis;
    }

    /** 生成本轮唯一的请求 id，形如 {@code r3}。 */
    String nextRequestId() {
        return "r" + sequence.incrementAndGet();
    }

    void log(String message) {
        lastActivityMillis = System.currentTimeMillis();
        diagnostics.line(lastActivityMillis, message);
    }

    /** 标记一次进展（解析/取数据/阶段切换都算）。 */
    void touch() {
        lastActivityMillis = System.currentTimeMillis();
    }

    /** 距最近一次活动过去的毫秒数。 */
    long idleMillis() {
        return Math.max(0L, System.currentTimeMillis() - lastActivityMillis);
    }

    /**
     * 在当前线程等待某个条件成立，最多等 min(剩余总预算, capMillis)。
     *
     * <p><b>这是「提取中永不结束」的关键护栏</b>：所有「等 WebView 回调」的地方都必须经过这里，
     * 因此任何一条通道卡住都只是让本轮提前走超时分支，而不是让 UI 无限转圈。</p>
     *
     * @return true = 条件已满足；false = 超时或已取消
     */
    boolean await(CountDownLatch latch, int capMillis) {
        long now = System.currentTimeMillis();
        if (!deadline.canContinue(now)) return false;
        int timeout = deadline.stepTimeout(now, capMillis, 0);
        if (timeout <= 0) return false;
        try {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 剩余预算（毫秒），已取消或超时为 0。 */
    long remainingMillis() {
        return deadline.remaining(System.currentTimeMillis());
    }

    /**
     * 幂等关闭：取消 deadline → 关桥接 → shutdownNow。
     *
     * <p>顺序很重要：先 cancel 让所有 {@link #await} 立即返回 false，
     * 再 close 桥接丢弃在途回调，最后 shutdownNow 打断可能还在跑的任务。</p>
     */
    void close() {
        if (closed) return;
        closed = true;
        deadline.cancel();
        bridge.close();
        worker.shutdownNow();
    }

    /** 本轮结束（正常/超时/取消）后统一的收尾文案。 */
    String summary() {
        String text = deadline.summary(System.currentTimeMillis());
        List<String> extra = pendingDescription();
        if (!extra.isEmpty()) {
            StringBuilder out = new StringBuilder(text);
            for (String item : extra) out.append("；").append(item);
            return out.toString();
        }
        return text;
    }

    private List<String> pendingDescription() {
        List<String> out = new ArrayList<>();
        if (closed) out.add("已中止后台任务");
        return out;
    }
}
