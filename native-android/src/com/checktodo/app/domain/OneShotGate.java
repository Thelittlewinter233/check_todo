package com.checktodo.app.domain;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「回调 / 超时 / 异常」三路仲裁：一局脚本注入的结果只接受<b>第一次到达</b>的一路。
 *
 * <p><b>为什么需要（真机死锁教训的配套不变量）：</b>异步注入脚本后，同一局可能有三个终点
 * 竞争送达——{@code onReceiveValue} 回调、超时看门狗、WebView 销毁异常。任何一个终点先到，
 * 其余终点都必须失效：看门狗已触发后晚到的回调不得再投递；回调先到后，看门狗到期也必须
 * 变成空操作（所以看门狗要能被 {@code removeCallbacks} 撤销，撤不到的靠本门槛兜底）。</p>
 *
 * <p>纯 Java、无 Android 依赖，多线程竞争语义（8 线程并发恰好 1 路胜出）在 JVM 测试钉死。</p>
 */
public final class OneShotGate {
    private final AtomicBoolean settled = new AtomicBoolean(false);

    /** 首次到达的一路返回 true；其余（重复、晚到、被抢先）一律 false。 */
    public boolean settle() {
        return settled.compareAndSet(false, true);
    }

    /** 是否已有任何一路到达。 */
    public boolean isSettled() {
        return settled.get();
    }
}
