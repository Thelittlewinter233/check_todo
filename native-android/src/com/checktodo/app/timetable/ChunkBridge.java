package com.checktodo.app.timetable;

import android.webkit.JavascriptInterface;

import com.checktodo.app.domain.OnlineExtraction;

import java.util.concurrent.Executor;

/**
 * 分块回传的 Java 端接收器：由 {@code WebView.addJavascriptInterface} 注入为
 * {@code window.__checkTodoSink}，供页内 fetch 代理脚本调用。
 *
 * <p><b>为什么用 JavascriptInterface 而不是 evaluateJavascript 的返回值：</b>
 * {@code ValueCallback} 只有「一次性返回一个大字符串」这一条路，数 MB 的 HTML 极易在
 * 序列化/转义/桥接环节失败（返回 null 或截断），并且没有任何超时手段——这正是旧实现
 * 「点提取永远停在提取中、诊断永远『尚未扫描』」的根因之一。分块回传把单次过桥的数据量
 * 压到 256KB，失败可以被逐块观测，也不会因为一次巨型转义而整轮丢数据。</p>
 *
 * <p><b>线程：</b>这些方法由 WebView 在<b>它自己的线程</b>上调用，不是 UI 线程，也不能假设
 * 是后台线程。因此这里只做一件事——把回调原样投递给后台 executor，
 * {@link OnlineExtraction.ChunkAssembler} 的读写全部发生在后台单线程，不需要加锁。</p>
 *
 * <p>所有方法都 {@code try/catch} 包住：JS 侧任何异常参数都不能让桥接层抛出，
 * 否则会打断 fetch 链并让 Java 侧永远等不到 done/fail。</p>
 */
final class ChunkBridge {

    private final OnlineExtraction.ChunkAssembler assembler;
    private final Executor executor;
    private volatile boolean closed;

    ChunkBridge(OnlineExtraction.ChunkAssembler assembler, Executor executor) {
        this.assembler = assembler;
        this.executor = executor;
    }

    /** dismiss 时调用：之后所有回调都丢弃，避免向已销毁的对话框投递。 */
    void close() {
        closed = true;
    }

    private void post(final Runnable task) {
        if (closed) return;
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    if (closed) return;
                    try {
                        task.run();
                    } catch (RuntimeException ignored) {
                        // 单块处理失败不应中断整轮：后续块仍可能到达。
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // executor 已在 dismiss 时关闭，静默丢弃。
        }
    }

    @JavascriptInterface
    public void begin(final String id, final String url) {
        post(new Runnable() {
            @Override
            public void run() {
                assembler.begin(id, url);
            }
        });
    }

    @JavascriptInterface
    public void chunk(final String id, final int seq, final int total, final String text) {
        post(new Runnable() {
            @Override
            public void run() {
                assembler.chunk(id, seq, total, text);
            }
        });
    }

    @JavascriptInterface
    public void done(final String id, final String status, final int length) {
        post(new Runnable() {
            @Override
            public void run() {
                assembler.done(id, status, length);
            }
        });
    }

    @JavascriptInterface
    public void fail(final String id, final String reason) {
        post(new Runnable() {
            @Override
            public void run() {
                assembler.fail(id, reason);
            }
        });
    }
}
