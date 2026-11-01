package com.checktodo.app.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 被动捕获的<b>拦截决策</b>：该不该拦、该怎么改写头、响应要不要透传。
 *
 * <p><b>为什么把这部分抽成纯函数：</b>第十五轮上线后出现严重回归——用户无法登录教务，
 * 学校统一身份认证（CAS/OAuth）报「该项目应用未注册，无法登录」。根因全在「决策」而不是
 * 「取数据」，而决策逻辑原本埋在 {@code WebViewClient.shouldInterceptRequest} 里，
 * 依赖 {@code HttpURLConnection} 与 {@code WebResourceRequest}，在 JVM 里无法被测到。
 * 抽出来之后，每一条安全规则都可以在单元测试里被钉死，不会再被下一次改动悄悄破坏。</p>
 *
 * <p><b>总原则：宁可少捕获，不可破坏登录。</b>捕获是「锦上添花」，登录是「一票否决」。</p>
 */
public final class PassiveCapturePolicy {

    /** 认证/会话相关 URL 关键词（大小写不敏感）。命中即绝不拦截。 */
    private static final String[] AUTH_TOKENS = {
            "login", "logon", "signin", "sign_in", "auth", "oauth", "openid", "saml",
            "cas", "sso", "logout", "signout", "ticket", "session", "j_spring_security",
            "servicelogin", "servicevalidate", "proxyvalidate", "idp", "captcha", "verify"
    };

    /**
     * 传输层头：这些必须由我们自己控制，不能原样转发。
     *
     * <p>为什么这些特殊：</p>
     * <ul>
     *   <li>{@code host} —— 由 URL 决定，手工设置可能冲突；</li>
     *   <li>{@code connection} / {@code keep-alive} / {@code proxy-*} —— 逐跳头，不应跨代理传递；</li>
     *   <li>{@code content-length} —— GET 无 body，带上会让上游等待 body；</li>
     *   <li>{@code accept-encoding} —— 必须改写为 identity，否则拿到压缩体无法解析；</li>
     *   <li>{@code cookie} —— 由 CookieManager 完整重注入，避免被 WebView 传进来的部分 Cookie 覆盖；</li>
     *   <li>{@code upgrade-insecure-requests} / {@code transfer-encoding} —— 语义由上游连接决定。</li>
     * </ul>
     */
    private static final String[] DROPPED_REQUEST_HEADERS = {
            "host", "connection", "keep-alive", "proxy-connection", "transfer-encoding",
            "content-length", "upgrade-insecure-requests", "cookie", "accept-encoding"
    };

    /**
     * 响应头白名单：只透传这些，其余丢弃。
     *
     * <p><b>Set-Cookie 必须在名单里</b>——第十五轮的实现构造 {@code WebResourceResponse} 时
     * <b>从未传入 responseHeaders</b>，导致经拦截路径返回的响应把 {@code Set-Cookie} 全部丢掉：
     * WebView 的 CookieManager 拿不到会话票，后续请求全部未认证，表现就是「登录不上」。</p>
     */
    private static final String[] FORWARDED_RESPONSE_HEADERS = {
            "set-cookie", "set-cookie2", "location", "content-language", "cache-control",
            "expires", "pragma", "vary", "x-frame-options", "content-security-policy",
            "strict-transport-security", "date", "last-modified", "etag", "access-control-allow-origin"
    };

    private PassiveCapturePolicy() {
    }

    /** 决策结果，便于诊断与测试断言「为什么放行」。 */
    public static final class Decision {
        public final boolean intercept;
        /** 放行原因（intercept=false 时非空），进诊断。 */
        public final String reason;

        Decision(boolean intercept, String reason) {
            this.intercept = intercept;
            this.reason = reason == null ? "" : reason;
        }

        static Decision yes() {
            return new Decision(true, "");
        }

        static Decision no(String reason) {
            return new Decision(false, reason);
        }

        @Override
        public String toString() {
            return intercept ? "捕获" : "放行（" + reason + "）";
        }
    }

    /**
     * 是否拦截这个请求。
     *
     * <p>顺序很重要：先把「绝对不能碰」的判掉（非 http、非 GET、认证 URL、静态资源），
     * 再交给后续网络阶段。任何一条命中都返回放行。</p>
     *
     * @param enabled   被动捕获总开关（用户在对话框里可关闭）
     * @param url       绝对 URL
     * @param method    HTTP 方法；null 视为 GET
     */
    public static Decision shouldIntercept(boolean enabled, String url, String method) {
        if (!enabled) return Decision.no("被动捕获已关闭");
        if (url == null || url.isEmpty()) return Decision.no("空 URL");
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return Decision.no("非 http(s) 协议");
        }
        // 规则 1：只拦 GET。登录几乎都是 POST，POST 一律不碰。
        String verb = method == null || method.trim().isEmpty()
                ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        if (!verb.equals("GET")) return Decision.no("非 GET（" + verb + "）");
        // 规则 3：认证相关 URL 一律不碰（CAS/OAuth 的整条链路）。
        String token = matchAuthToken(lower);
        if (token != null) return Decision.no("认证相关 URL（" + token + "）");
        if (isStaticAsset(url)) return Decision.no("静态资源");
        return Decision.yes();
    }

    /** 返回命中的认证关键词；未命中返回 null。 */
    public static String matchAuthToken(String url) {
        if (url == null) return null;
        String lower = url.toLowerCase(Locale.ROOT);
        for (String token : AUTH_TOKENS) {
            if (lower.contains(token)) return token;
        }
        return null;
    }

    public static boolean isStaticAsset(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        int query = lower.indexOf('?');
        int hash = lower.indexOf('#');
        int cut = lower.length();
        if (query >= 0) cut = Math.min(cut, query);
        if (hash >= 0) cut = Math.min(cut, hash);
        String path = lower.substring(0, cut);
        String[] suffixes = {".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".ico", ".svg",
                ".woff", ".woff2", ".ttf", ".otf", ".eot", ".mp4", ".webm", ".mp3", ".pdf",
                ".zip", ".rar", ".7z", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx"};
        for (String suffix : suffixes) {
            if (path.endsWith(suffix)) return true;
        }
        return false;
    }

    /**
     * 上游响应是否值得接管。
     *
     * <p>规则 2：<b>30x 永不拦</b>。自定义流会丢掉 {@code Location} 的跟随语义（WebView 需要
     * 自己处理跳转、维持 Referer 与 Cookie 时序），而 OAuth/QAuth 回调强依赖这个时序。</p>
     */
    public static Decision shouldForwardResponse(int status) {
        if (status >= 300 && status < 400) return Decision.no("重定向 " + status);
        if (status == 204 || status == 304) return Decision.no("无实体 " + status);
        if (status < 200 || status >= 400) return Decision.no("非 2xx（" + status + "）");
        return Decision.yes();
    }

    /**
     * 计算要转发给上游的请求头。
     *
     * <p><b>回归根因之二：</b>第十五轮虽然「没有显式删除 Origin/Referer」，但它把整个决策埋进
     * 网络方法里，且用 {@code setRequestProperty} 逐条写入——{@code Map} 传入可能是大小写混杂的
     * 原始头集合，任何逐跳过滤写错就会连带丢掉 SSO 校验用的头。这里改为纯函数 + 白/黑名单双显式，
     * 并对大小写做归一，保证 Origin、Referer、User-Agent 与自定义头一定原样转发。</p>
     *
     * @param incoming WebView 交来的请求头（可为 null）
     * @return 保序的转发头（已剔除传输头与非 http 头）
     */
    public static Map<String, String> forwardableHeaders(Map<String, String> incoming) {
        Map<String, String> out = new LinkedHashMap<>();
        if (incoming == null) return out;
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.trim().isEmpty()) continue;
            String value = entry.getValue();
            if (value == null) continue;
            if (isDroppedRequestHeader(name)) continue;
            putCaseInsensitive(out, name, value);
        }
        return out;
    }

    public static boolean isDroppedRequestHeader(String name) {
        if (name == null) return true;
        String lower = name.trim().toLowerCase(Locale.ROOT);
        for (String dropped : DROPPED_REQUEST_HEADERS) {
            if (lower.equals(dropped)) return true;
        }
        // 逐跳的 proxy-* 一族
        return lower.startsWith("proxy-");
    }

    private static void putCaseInsensitive(Map<String, String> map, String name, String value) {
        for (String existing : new ArrayList<>(map.keySet())) {
            if (existing.equalsIgnoreCase(name)) {
                map.put(existing, value);
                return;
            }
        }
        map.put(name, value);
    }

    /**
     * 计算要回给 WebView 的响应头。
     *
     * <p>只保留白名单内的头，其中 {@code Set-Cookie} 会全部保留（多个 Set-Cookie 不能合并成一个）。</p>
     */
    public static Map<String, String> forwardableResponseHeaders(Map<String, List<String>> upstream) {
        Map<String, String> out = new LinkedHashMap<>();
        if (upstream == null) return out;
        for (Map.Entry<String, List<String>> entry : upstream.entrySet()) {
            String name = entry.getKey();
            if (name == null || entry.getValue() == null) continue;
            if (!isForwardedResponseHeader(name)) continue;
            // 多值头（Set-Cookie）用换行拼接：WebResourceResponse 的头是 Map<String,String>，
            // WebView 支持按 \n 拆回多个值。
            StringBuilder joined = new StringBuilder();
            for (String value : entry.getValue()) {
                if (value == null) continue;
                if (joined.length() > 0) joined.append('\n');
                joined.append(value);
            }
            if (joined.length() > 0) out.put(name, joined.toString());
        }
        return out;
    }

    public static boolean isForwardedResponseHeader(String name) {
        if (name == null) return false;
        String lower = name.trim().toLowerCase(Locale.ROOT);
        for (String allowed : FORWARDED_RESPONSE_HEADERS) {
            if (lower.equals(allowed)) return true;
        }
        return false;
    }

    /**
     * 组装最终要发给上游的完整头集合：转发头 + {@code Accept-Encoding: identity} + 完整 Cookie。
     *
     * <p>Cookie 用 {@code CookieManager.getCookie(url)} 的结果<b>整体覆盖</b>，而不是与
     * WebView 传来的 Cookie 拼接——拼接容易产生重复键与顺序问题，直接覆盖语义更清晰。
     * 若 CookieManager 为空则保留 WebView 传来的 Cookie（如果它没被丢弃）。</p>
     */
    public static Map<String, String> upstreamHeaders(Map<String, String> incoming, String managedCookie) {
        Map<String, String> out = forwardableHeaders(incoming);
        // WebView 传来的 Cookie 已被 forwardableHeaders 丢弃，这里按需回补。
        if (managedCookie != null && !managedCookie.isEmpty()) {
            out.put("Cookie", managedCookie);
        } else if (incoming != null) {
            for (Map.Entry<String, String> entry : incoming.entrySet()) {
                if (entry.getKey() == null) continue;
                if (entry.getKey().equalsIgnoreCase("cookie") && entry.getValue() != null
                        && !entry.getValue().isEmpty()) {
                    out.put("Cookie", entry.getValue());
                    break;
                }
            }
        }
        out.put("Accept-Encoding", "identity");
        return out;
    }

    /** 诊断文案：一条请求的决策理由。 */
    public static String describe(String url, String method) {
        Decision decision = shouldIntercept(true, url, method);
        return method + " " + url + " → " + decision;
    }
}
