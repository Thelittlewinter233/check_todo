package test;

import com.checktodo.app.domain.OneShotGate;

/**
 * 「框架」按钮 UI 线程死锁的回归测试。
 *
 * <p><b>真机现场：</b>点「框架」→ 10s 后「框架发现超时」；刷新列表 → 「共 0 个文档」。
 * 与页面快慢无关，<b>每次必现</b>。</p>
 *
 * <p><b>根因（代码层事实）：</b>{@code discoverFramesForPicker} 由「框架」按钮的
 * {@code onClick} 在 <b>UI 线程</b>上同步调用，内部 {@code evaluateForPicker} 走
 * {@code runOnUi}——因已在主线程而<b>内联</b>执行 {@code evaluateJavascript}，随后
 * {@code latch.await(10s)} 把 UI 线程阻塞住。而 {@code ValueCallback.onReceiveValue}
 * 恰恰要投递回 UI 线程主循环：等待方阻塞了投递方，{@code countDown} 永不发生 →
 * 必然 10s 超时 → 返回 null → 候选列表被 clear 后不再填充 → 0 个文档。</p>
 *
 * <p><b>修复：</b>发现流程改异步 {@code evaluateAsync}（UI 线程只投递，不等待；
 * 结果或超时经回调回 UI 线程再续跑），并以 {@code OneShotGate} 仲裁
 * 「回调 / 超时 / 异常」三路只算一路、看门狗可撤销。本测试分别钉死：
 * 仲裁的 once 语义（含多线程竞争）、以及发现流程不再包含任何 UI 线程阻塞等待。</p>
 */
public final class FramePickerThreadingRegressionTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testGateAcceptsFirstOnly();
        testGateRaceExactlyOneWinner();
        testDiscoveryDoesNotBlockOnUiThread();
        testEvaluateAsyncStructure();
        testGenerationGuard();
        report();
    }

    // ------------------------------------------------------------------
    // OneShotGate：三路仲裁的 once 语义
    // ------------------------------------------------------------------

    private static void testGateAcceptsFirstOnly() {
        OneShotGate gate = new OneShotGate();
        check("★首次到达的一路被接受", gate.settle());
        check("★其余路（晚到回调/超时/异常）全部被拒", !gate.settle());
        check("重复到达也拒", !gate.settle());
        check("isSettled 在首路后为 true", gate.isSettled());
        OneShotGate fresh = new OneShotGate();
        check("新实例未定格", !fresh.isSettled());
    }

    /** 回调与超时看门狗并发到达时，必须恰好只有一路胜出。 */
    private static void testGateRaceExactlyOneWinner() throws Exception {
        for (int round = 0; round < 20; round++) {
            final OneShotGate gate = new OneShotGate();
            final int[] winners = new int[1];
            final int threads = 8;
            Thread[] pool = new Thread[threads];
            final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            for (int i = 0; i < threads; i++) {
                pool[i] = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                        } catch (InterruptedException ignored) {
                            return;
                        }
                        if (gate.settle()) {
                            synchronized (winners) {
                                winners[0]++;
                            }
                        }
                    }
                });
                pool[i].start();
            }
            start.countDown();
            for (Thread thread : pool) thread.join();
            check("竞争轮 " + round + "：8 路并发恰好 1 路胜出", winners[0] == 1);
        }
    }

    // ------------------------------------------------------------------
    // 读源码断言：发现流程不得在 UI 线程阻塞
    // ------------------------------------------------------------------

    private static void testDiscoveryDoesNotBlockOnUiThread() {
        String source = readSource("src/com/checktodo/app/timetable/TimetableController.java");
        String discovery = methodBody(source, "private void discoverFramesForPicker");
        check("★discoverFramesForPicker 不再调用阻塞版 evaluateForPicker",
                !discovery.contains("evaluateForPicker("));
        check("★discoverFramesForPicker 不再出现 latch.await（UI 线程死锁源头）",
                !discovery.contains("latch.await"));
        check("★discoverFramesForPicker 改走异步 evaluateAsync",
                discovery.contains("evaluateAsync("));
        check("超时提示保留可重试文案",
                discovery.contains("刷新列表"));
    }

    private static void testEvaluateAsyncStructure() {
        String source = readSource("src/com/checktodo/app/timetable/TimetableController.java");
        String body = methodBody(source, "private void evaluateAsync");
        check("evaluateAsync 存在（异步注入入口）", !body.isEmpty());
        check("★超时看门狗用 postDelayed 调度", body.contains("postDelayed"));
        check("★回调成功后移除看门狗（removeCallbacks）", body.contains("removeCallbacks"));
        check("★三路仲裁走 OneShotGate（每轮新建，不复用）",
                body.contains("new OneShotGate()"));
        check("★回调路径经 gate.settle() 仲裁", body.contains(".settle()"));
        check("★WebView 抛异常立即以超时语义结束（catch RuntimeException）",
                body.contains("catch (RuntimeException"));
        check("OneShotGate 在 domain 包（纯 Java 可测）",
                readSource("src/com/checktodo/app/domain/OneShotGate.java").contains("package com.checktodo.app.domain;"));
    }

    private static void testGenerationGuard() {
        String source = readSource("src/com/checktodo/app/timetable/TimetableController.java");
        String discovery = methodBody(source, "private void discoverFramesForPicker");
        String probe = methodBody(source, "private void probeFrames");
        check("★发现回调带轮次守卫（刷新后旧回调不得写入新列表）",
                discovery.contains("frameDiscoveryGeneration"));
        check("★probeFrames 完成回调同样带轮次守卫（旧探测不得覆盖新一轮状态）",
                probe.contains("frameDiscoveryGeneration"));
        check("轮次计数器字段存在且原子",
                source.contains("private final AtomicInteger frameDiscoveryGeneration = new AtomicInteger(0)"));
    }

    // ------------------------------------------------------------------

    /** 截取从方法签名起、到下一个成员（字段/javadoc/private 方法）或文件尾的方法体（够用于结构断言）。 */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) return "";
        int endByMethod = source.indexOf("\n    private ", start + signature.length());
        int endByJavadoc = source.indexOf("\n    /**", start + signature.length());
        int next = Math.min(endByMethod < 0 ? source.length() : endByMethod,
                endByJavadoc < 0 ? source.length() : endByJavadoc);
        return source.substring(start, next);
    }

    private static String readSource(String path) {
        java.io.InputStream stream = null;
        try {
            stream = new java.io.FileInputStream(new java.io.File(path));
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) >= 0) buffer.write(chunk, 0, count);
            return new String(buffer.toByteArray(), "UTF-8");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("读取源码失败：" + path, exception);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (java.io.IOException ignored) {
                    // ignore
                }
            }
        }
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

    private static void report() {
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
