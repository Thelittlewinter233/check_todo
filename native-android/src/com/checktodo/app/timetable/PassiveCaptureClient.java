package com.checktodo.app.timetable;

import android.content.Context;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import com.checktodo.app.domain.PassiveCapture;
import com.checktodo.app.domain.PassiveCapturePolicy;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 被动捕获网络旁路：在页面正常浏览过程中，把 WebView 收到的文本响应复制一份进捕获池。
 *
 * <p><b>⚠ 第十五轮回归修复说明：</b>初版上线后用户<b>无法登录</b>教务，学校统一身份认证
 * （CAS/OAuth）返回「该项目应用未注册，无法登录」。原因是初版为了「多捕获一点」而放得太宽，
 * 踩了四条红线：</p>
 * <ol>
 *   <li><b>没有过滤 HTTP 方法</b>——登录表单是 POST，被接管后走我们自己的连接，SSO 校验失败；</li>
 *   <li><b>跟随重定向再返回</b>——{@code setInstanceFollowRedirects(true)} 让 30x 被静默吃掉，
 *       CAS 的 {@code Location} 跳转与 {@code ?ticket=ST-...} 回调时序被打乱；</li>
 *   <li><b>没有认证 URL 黑名单</b>——{@code /cas/login}、{@code /sso/auth} 这类请求也被代理；</li>
 *   <li><b>响应头一个都没透传</b>——构造 {@code WebResourceResponse} 时从未传入 responseHeaders，
 *       {@code Set-Cookie} 全部丢失，CookieManager 拿不到会话票，后续请求全是未认证状态。</li>
 * </ol>
 *
 * <p>现在所有「该不该拦」的判断都收敛到 {@link PassiveCapturePolicy}（纯函数、可单测），
 * 本类只负责在决策为「捕获」时执行网络与 Tee，并且<b>每一层都有 failsafe</b>：
 * 任何异常都返回 {@code null} 交回系统原生处理。</p>
 *
 * <p><b>总原则：宁可少捕获，不可破坏登录。</b></p>
 */
final class PassiveCaptureClient extends android.webkit.WebViewClient {

    /** 页面导航事件回调（仍在 WebView 的线程上调用）。 */
    interface Navigation {
        void onStarted(String url);

        void onFinished(String url);

        void onMainFrameError();
    }

    /** 捕获回调：在后台线程上调用。 */
    interface Listener {
        void onCaptured(PassiveCapture.Captured captured);
    }

    private static final int BODY_LIMIT = PassiveCapture.MAX_BODY_BYTES;

    private final Listener listener;
    private final Navigation navigation;
    private final ExecutorService drainer;
    private volatile boolean closed;
    /** 总开关：用户在导入对话框里关闭后，本类退化为「全部放行」。 */
    private volatile boolean enabled = true;
    private final AtomicInteger captured = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();

    PassiveCaptureClient(Context context, Listener listener, Navigation navigation) {
        // context 仅用于未来可能需要的 CookieManager 初始化；不持有引用避免泄漏。
        this.listener = listener;
        this.navigation = navigation;
        this.drainer = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "passive-capture");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /** 打开/关闭被动捕获。关闭后所有请求一律返回 null，恢复原生行为。 */
    void setEnabled(boolean value) {
        this.enabled = value;
    }

    boolean isEnabled() {
        return enabled;
    }

    // ---- 导航行为：保持与改造前完全一致 ----

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        if (navigation != null) navigation.onStarted(url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        if (navigation != null) navigation.onFinished(url);
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        if (navigation != null && request != null && request.isForMainFrame()) {
            navigation.onMainFrameError();
        }
    }

    int capturedCount() {
        return captured.get();
    }

    int skippedCount() {
        return skipped.get();
    }

    void close() {
        closed = true;
        drainer.shutdownNow();
    }

    /**
     * 拦截入口。
     *
     * <p><b>failsafe 分层</b>：这一层只做「取参数 + 调纯函数决策」；任何一步出问题都返回 null。
     * 网络层的失败在 {@link #intercept} 里再兜一次。</p>
     */
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (closed || !enabled) return null;
        if (Build.VERSION.SDK_INT < 21) return null;
        try {
            String url = request.getUrl() == null ? "" : request.getUrl().toString();
            String method = request.getMethod();
            PassiveCapturePolicy.Decision decision =
                    PassiveCapturePolicy.shouldIntercept(enabled, url, method);
            if (!decision.intercept) {
                skipped.incrementAndGet();
                return null;
            }
            Map<String, String> incoming = request.getRequestHeaders();
            // Api21 的 getRequestHeaders() 可能返回 null。
            if (incoming == null) incoming = new LinkedHashMap<>();
            return intercept(url, method, incoming);
        } catch (Throwable throwable) {
            // 连 Error 都兜：这里抛出去会让页面白屏。
            return null;
        }
    }

    /**
     * 执行捕获：连接上游 → 判状态码（30x 立即放行）→ 透传头 → 文本响应包 Tee。
     */
    private WebResourceResponse intercept(String url, String method, Map<String, String> incoming) {
        HttpURLConnection connection = null;
        try {
            // 规则 4：Cookie 完整重注入（CookieManager 是 WebView 的唯一事实源）。
            String managedCookie = safeCookie(url);
            Map<String, String> headers = PassiveCapturePolicy.upstreamHeaders(incoming, managedCookie);

            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            // 规则 2 的关键：不自动跟随，否则拿不到 30x 就没法放行。
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method == null || method.isEmpty() ? "GET" : method);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            int status = connection.getResponseCode();

            // 规则 2：30x / 204 / 304 / 非 2xx 一律交回系统原生处理。
            PassiveCapturePolicy.Decision forward = PassiveCapturePolicy.shouldForwardResponse(status);
            if (!forward.intercept) {
                skipped.incrementAndGet();
                disconnect(connection);
                return null;
            }

            String contentType = connection.getContentType();
            String encoding = connection.getContentEncoding();
            if (!PassiveCapture.isCapturableType(contentType)) {
                // 非文本：本来也不该进来（静态资源已在决策层放行），保守起见交回系统。
                disconnect(connection);
                return null;
            }
            InputStream stream = connection.getInputStream();
            if (stream == null) {
                disconnect(connection);
                return null;
            }

            // 规则 4：响应头逐条透传，Set-Cookie 必须带上，否则会话断裂。
            Map<String, String> responseHeaders =
                    PassiveCapturePolicy.forwardableResponseHeaders(connection.getHeaderFields());

            final PassiveCapture.TeeInputStream tee = new PassiveCapture.TeeInputStream(
                    stream, new PassiveCapture.Sink(BODY_LIMIT));
            final HttpURLConnection owned = connection;
            final String finalUrl = url;
            final String finalMethod = method;
            final int finalStatus = status;
            final String finalType = contentType;
            final boolean compressed = encoding != null && !encoding.isEmpty()
                    && !encoding.equalsIgnoreCase("identity");
            TeeInputStreamClosing body = new TeeInputStreamClosing(tee, owned, new Runnable() {
                @Override
                public void run() {
                    deliver(finalUrl, finalMethod, finalStatus, finalType, tee.sink(), compressed);
                }
            });

            String mime = PassiveCapture.mimeOf(contentType);
            if (mime.isEmpty()) mime = "text/html";
            String charset = charsetOf(contentType);
            return new WebResourceResponse(mime, charset, finalStatus,
                    reasonPhrase(finalStatus), responseHeaders, body);
        } catch (IOException exception) {
            // 规则 5：网络失败 → 交回系统自己再试一次，不能让我们的失败变成页面失败。
            disconnect(connection);
            skipped.incrementAndGet();
            return null;
        } catch (Throwable throwable) {
            disconnect(connection);
            skipped.incrementAndGet();
            return null;
        }
    }

    private String safeCookie(String url) {
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            return cookie == null ? "" : cookie;
        } catch (Throwable ignored) {
            // CookieManager 在极少数情况下会抛（WebView 未初始化 / 线程问题）。
            return "";
        }
    }

    private static String reasonPhrase(int status) {
        if (status == 200) return "OK";
        if (status == 201) return "Created";
        if (status == 202) return "Accepted";
        if (status == 206) return "Partial Content";
        return "";
    }

    private void deliver(final String url, final String method, final int status,
                         final String contentType, final PassiveCapture.Sink sink,
                         final boolean compressed) {
        if (closed) return;
        try {
            drainer.execute(new Runnable() {
                @Override
                public void run() {
                    if (closed) return;
                    try {
                        byte[] bytes = sink.bytes();
                        if (bytes.length == 0) return;
                        String body = decode(bytes, contentType);
                        if (body.trim().isEmpty()) return;
                        if (compressed) {
                            body = "[compressed " + bytes.length + " bytes]";
                        }
                        PassiveCapture.Captured item = new PassiveCapture.Captured(
                                url, method, status, contentType, body,
                                System.currentTimeMillis(), sink.truncated());
                        captured.incrementAndGet();
                        if (listener != null) listener.onCaptured(item);
                    } catch (Throwable ignored) {
                        // 捕获失败绝不能影响页面。
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // executor 已关闭。
        }
    }

    private static String decode(byte[] bytes, String contentType) {
        return com.checktodo.app.domain.HtmlTimetableParser.decode(bytes);
    }

    private static String charsetOf(String contentType) {
        if (contentType == null) return "utf-8";
        String lower = contentType.toLowerCase(java.util.Locale.ROOT);
        int at = lower.indexOf("charset=");
        if (at < 0) return "utf-8";
        String value = contentType.substring(at + 8).trim();
        int semicolon = value.indexOf(';');
        if (semicolon > 0) value = value.substring(0, semicolon);
        value = value.replace("\"", "").replace("'", "").trim();
        return value.isEmpty() ? "utf-8" : value;
    }

    private static void disconnect(HttpURLConnection connection) {
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    /** 读完即交付：在 close() 时触发捕获交付，并释放上游连接。 */
    private static final class TeeInputStreamClosing extends InputStream {
        private final InputStream delegate;
        private final HttpURLConnection connection;
        private final Runnable onClosed;
        private boolean closed;

        TeeInputStreamClosing(InputStream delegate, HttpURLConnection connection, Runnable onClosed) {
            this.delegate = delegate;
            this.connection = connection;
            this.onClosed = onClosed;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return delegate.read(buffer, offset, length);
        }

        @Override
        public long skip(long count) throws IOException {
            return delegate.skip(count);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                delegate.close();
            } finally {
                disconnect(connection);
                if (onClosed != null) onClosed.run();
            }
        }
    }
}
