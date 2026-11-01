package com.checktodo.app.domain;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 被动捕获的纯 Java 核心：Tee 流、捕获记录、LRU 捕获池。
 *
 * <p><b>为什么需要被动捕获：</b>新版正方（jwglxt）课表页
 * {@code /kbcx/xskbcx_cxXskbcxIndex.html} 本身只是查询表单壳，真正的课表数据由用户点「查询」后
 * 以 <b>POST</b>（{@code gnmkdm=N2151} + {@code xnm/xqm}）返回。我们的两条主动通道
 * （页内 {@code fetch} 代理、{@code HttpURLConnection} 直取）都只发 GET，对这类端点天然无效——
 * 这不是调参问题，是方法不对。被动捕获换了个方向：<b>不重放请求，而是把 WebView 自己已经成功
 * 拿到的响应复制一份</b>。用户点了查询，浏览器拿到了数据，我们就顺手存下来。</p>
 *
 * <p><b>为什么不用 {@code shouldInterceptRequest} 返回自定义流的「纯转发」：</b>
 * 那需要自己完整接管响应（头、gzip、编码、分块），任何一个细节错都会让页面白屏。
 * 这里采用「上游连接 + Tee」：请求照常交给系统网络栈语义，我们只在读的路径上复制字节，
 * 对页面的影响仅限于多一次流包装。</p>
 *
 * <p>本类零 Android 依赖，全部逻辑可在 JVM 直接测试。</p>
 */
public final class PassiveCapture {

    /** 单个响应体的捕获上限：2MB。超过即截断并标记，不写半截正文去骗解析器。 */
    public static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    /** 捕获池总量上限：8MB。 */
    public static final int MAX_POOL_BYTES = 8 * 1024 * 1024;

    /** 捕获池条目数上限（防止大量小响应把 LRU 链表撑爆）。 */
    public static final int MAX_ENTRIES = 120;

    private PassiveCapture() {
    }

    // ------------------------------------------------------------------
    // Tee 流
    // ------------------------------------------------------------------

    /**
     * 边读边复制的输入流。
     *
     * <p>WebView 从 {@link #read} 读到自己的字节流；每次读到的区间同时喂给 {@link Sink}，
     * 由 <b>另一个线程</b> 排空。这样主读路径永不等待捕获侧，页面加载速度不受影响。</p>
     *
     * <p><b>为什么 sink 要异步排空：</b>若在 {@code read()} 里同步写缓冲，捕获就成了主路径的一部分——
     * 捕获侧一旦慢（GC、锁竞争、大 body 复制），页面就会卡。异步后最坏情况只是丢弃后续字节，
     * 页面依旧流畅。</p>
     *
     * <p><b>截断语义：</b>一旦总量超过 {@code limit}，只保留前 {@code limit} 字节，
     * 并把 {@code truncated} 置真。绝不让半个响应体冒充完整响应。</p>
     *
     * <p>本类不是线程安全的：{@code read()} 只应在读线程调用，{@link Sink} 的写入由 sink 自己保证。</p>
     */
    public static final class TeeInputStream extends InputStream {
        private final InputStream upstream;
        private final Sink sink;

        public TeeInputStream(InputStream upstream, Sink sink) {
            this.upstream = upstream;
            this.sink = sink;
        }

        /** 包装上游流；{@code bodyLimit} 为单响应捕获上限。 */
        public static TeeInputStream wrap(InputStream upstream, int bodyLimit) {
            return new TeeInputStream(upstream, new Sink(bodyLimit));
        }

        public Sink sink() {
            return sink;
        }

        @Override
        public int read() throws IOException {
            int value = upstream.read();
            if (value >= 0) sink.feed(new byte[]{(byte) value}, 0, 1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = upstream.read(buffer, offset, length);
            if (count > 0) sink.feed(buffer, offset, count);
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            // skip 也要记账：跳过即意味着这些字节「没被看到」，不能算进捕获体。
            return upstream.skip(count);
        }

        @Override
        public int available() throws IOException {
            return upstream.available();
        }

        @Override
        public void close() throws IOException {
            try {
                upstream.close();
            } finally {
                sink.finish();
            }
        }
    }

    /**
     * 捕获侧缓冲：接收 Tee 喂来的字节，受上限约束。
     *
     * <p>写入方法 {@code synchronized}：读线程写、后台线程读，必须可见性。
     * 加锁范围只有一次 {@code arraycopy}，不会成为主路径瓶颈。</p>
     */
    public static final class Sink {
        private final int limit;
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        private boolean truncated;
        private boolean finished;

        public Sink(int limit) {
            this.limit = Math.max(0, limit);
        }

        synchronized void feed(byte[] data, int offset, int length) {
            if (finished || data == null || length <= 0) return;
            int room = limit - buffer.size();
            if (room <= 0) {
                truncated = true;
                return;
            }
            int take = Math.min(room, length);
            buffer.write(data, offset, take);
            if (take < length) truncated = true;
        }

        synchronized void finish() {
            finished = true;
        }

        public synchronized boolean truncated() {
            return truncated;
        }

        public synchronized int size() {
            return buffer.size();
        }

        /** 取到目前已捕获的字节（截断时就是前 limit 字节）。 */
        public synchronized byte[] bytes() {
            return buffer.toByteArray();
        }

        public synchronized String text() {
            return new String(buffer.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
        }
    }

    // ------------------------------------------------------------------
    // 捕获记录
    // ------------------------------------------------------------------

    /** 一条被捕获的响应。 */
    public static final class Captured {
        public final String url;
        public final String method;
        public final int status;
        public final String contentType;
        public final String body;
        public final long capturedAt;
        public final boolean truncated;
        /** 内容摘要，用于去重与诊断定位。 */
        public final String hash;

        public Captured(String url, String method, int status, String contentType,
                        String body, long capturedAt, boolean truncated) {
            this.url = url == null ? "" : url;
            this.method = method == null || method.isEmpty() ? "GET" : method.toUpperCase(Locale.ROOT);
            this.status = status;
            this.contentType = contentType == null ? "" : contentType;
            this.body = body == null ? "" : body;
            this.capturedAt = capturedAt;
            this.truncated = truncated;
            this.hash = digest(this.body);
        }

        /** XMLHttpRequest 风格的标记：这条响应值得当「数据接口」看待吗？ */
        public boolean isDataLike() {
            String lower = url.toLowerCase(Locale.ROOT);
            String type = contentType.toLowerCase(Locale.ROOT);
            if (type.contains("json")) return true;
            if (!lower.contains(".")) return true; // 无扩展名的路由通常就是接口
            return lower.contains("kbcx") || lower.contains("xskbcx") || lower.contains("kbtable")
                    || lower.contains("list") || lower.contains("query") || lower.contains(".do")
                    || lower.contains(".action") || lower.contains("api");
        }

        public int sizeBytes() {
            return body.getBytes(java.nio.charset.Charset.forName("UTF-8")).length;
        }

        public String shortUrl() {
            int query = url.indexOf('?');
            String base = query > 0 ? url.substring(0, query) : url;
            base = base.replace("https://", "").replace("http://", "");
            return base.length() > 90 ? base.substring(0, 90) + "…" : base;
        }
    }

    private static String digest(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(body.getBytes(java.nio.charset.Charset.forName("UTF-8")));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", out[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(body.hashCode());
        }
    }

    // ------------------------------------------------------------------
    // LRU 捕获池
    // ------------------------------------------------------------------

    /**
     * 捕获池：内存 + LRU，总量 8MB。
     *
     * <p><b>为什么选内存而不是落盘：</b></p>
     * <ul>
     *   <li>捕获发生在 {@code shouldInterceptRequest} 的后台排空线程里，内存写入只需一次
     *       {@code byte[]} 拷贝；落盘要开文件、走 IO、还要处理并发写，直接把页面加载路径变长。</li>
     *   <li>容量有界（8MB）时内存代价可控，而一次提取所需的响应体通常只有几十到几百 KB。</li>
     *   <li>捕获内容是**登录态下的个人数据**（含课表、可能的姓名学号），落盘会留下明文残影；
     *       内存池随对话框销毁即消失，隐私面更小。</li>
     * </ul>
     * <p>代价：进程被系统回收时捕获丢失。但捕获只在打开的对话框生命周期内被消费，
     * 这个代价可接受。</p>
     *
     * <p>去重口径：<b>URL 相同且内容哈希相同</b>视为重复，只刷新时间戳不重复占容量；
     * URL 相同但内容变了（例如翻了学期）会作为新条目保留。</p>
     */
    public static final class CapturePool {
        private final int maxBytes;
        private final int maxEntries;
        /** accessOrder=true 的 LinkedHashMap 天然按访问顺序做 LRU。 */
        private final Map<String, Captured> entries = new LinkedHashMap<>(16, 0.75f, true);
        private long totalBytes;
        private int duplicates;
        private int dropped;

        public CapturePool() {
            this(MAX_POOL_BYTES, MAX_ENTRIES);
        }

        public CapturePool(int maxBytes, int maxEntries) {
            this.maxBytes = Math.max(0, maxBytes);
            this.maxEntries = Math.max(1, maxEntries);
        }

        /**
         * 存入一条捕获。
         *
         * @return true = 新条目（占容量）；false = 重复被跳过或超容量被丢弃
         */
        public synchronized boolean add(Captured captured) {
            if (captured == null || captured.body.isEmpty()) return false;
            String key = keyOf(captured.url, captured.hash);
            if (entries.containsKey(key)) {
                // 重复：touch 一下维持 LRU 语义（LinkedHashMap accessOrder 已在 get 时完成）。
                duplicates++;
                return false;
            }
            int size = captured.sizeBytes();
            if (size > maxBytes) {
                dropped++;
                return false;
            }
            entries.put(key, captured);
            totalBytes += size;
            evict();
            return true;
        }

        private void evict() {
            Iterator<Map.Entry<String, Captured>> iterator = entries.entrySet().iterator();
            while ((totalBytes > maxBytes || entries.size() > maxEntries) && iterator.hasNext()) {
                Map.Entry<String, Captured> eldest = iterator.next();
                totalBytes -= eldest.getValue().sizeBytes();
                iterator.remove();
                dropped++;
            }
            if (totalBytes < 0) totalBytes = 0;
        }

        private static String keyOf(String url, String hash) {
            return (url == null ? "" : url) + '#' + (hash == null ? "" : hash);
        }

        /** 按捕获顺序（旧→新）返回快照。 */
        public synchronized List<Captured> snapshot() {
            return new ArrayList<>(entries.values());
        }

        /** 数据接口优先（JSON / 无扩展名路由 / 课表关键词），其余按捕获时间。 */
        public synchronized List<Captured> dataLike() {
            List<Captured> out = new ArrayList<>();
            for (Captured captured : entries.values()) {
                if (captured.isDataLike()) out.add(captured);
            }
            return out;
        }

        public synchronized int size() {
            return entries.size();
        }

        public synchronized int dataLikeCount() {
            return dataLike().size();
        }

        public synchronized long totalBytes() {
            return totalBytes;
        }

        public synchronized int duplicates() {
            return duplicates;
        }

        public synchronized int dropped() {
            return dropped;
        }

        public synchronized void clear() {
            entries.clear();
            totalBytes = 0;
        }

        /** 诊断用：一行摘要。 */
        public synchronized String summary() {
            return "被动捕获 " + entries.size() + " 个响应 · 数据接口 " + dataLikeCount()
                    + " 个 · " + (totalBytes / 1024) + "KB / " + (maxBytes / 1024) + "KB"
                    + (duplicates > 0 ? " · 去重 " + duplicates : "");
        }
    }

    // ------------------------------------------------------------------
    // 内容类型过滤
    // ------------------------------------------------------------------

    /**
     * 是否值得捕获：只看这三类文本响应。
     *
     * <p>不抓图片/字体/脚本：体积大、解析无意义，纯粹浪费 8MB 配额。</p>
     */
    public static boolean isCapturableType(String contentType) {
        if (contentType == null) return false;
        String type = mimeOf(contentType).toLowerCase(Locale.ROOT);
        return type.equals("text/html")
                || type.equals("application/xhtml+xml")
                || type.equals("application/json")
                || type.equals("text/json")
                || type.equals("text/plain");
    }

    /** 去掉 charset 等参数，只保留 MIME。 */
    public static String mimeOf(String contentType) {
        if (contentType == null) return "";
        int semicolon = contentType.indexOf(';');
        return (semicolon > 0 ? contentType.substring(0, semicolon) : contentType).trim();
    }
}
