package test;

import com.checktodo.app.domain.PassiveCapturePolicy;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 被动捕获<b>安全规则</b>回归测试。
 *
 * <p>背景：第十五轮初版被动捕获上线后，用户无法登录教务，学校统一身份认证（CAS/OAuth）
 * 报「该项目应用未注册，无法登录」。根因是拦得太宽。本测试把每一条红线钉死，
 * 确保同样的回归不可能再发生。</p>
 *
 * <p>被测的全是 {@link PassiveCapturePolicy} 里的纯函数——不构造
 * {@code HttpURLConnection}，因此在 JVM 里完全可测。</p>
 */
public final class PassiveCapturePolicyTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testMethodFilter();
        testAuthUrlFilter();
        testStaticAssetFilter();
        testRedirectNeverIntercepted();
        testRequestHeaderFidelity();
        testDroppedTransportHeaders();
        testCookieReinjection();
        testResponseHeaderForwarding();
        testMasterSwitch();
        testFailsafeInputs();
        testRegressionScenarios();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // 规则 1：只拦 GET
    // ------------------------------------------------------------------

    private static void testMethodFilter() {
        String kb = "https://jwgl.hzau.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html";
        check("GET 可捕获", PassiveCapturePolicy.shouldIntercept(true, kb, "GET").intercept);
        check("小写 get 也可捕获", PassiveCapturePolicy.shouldIntercept(true, kb, "get").intercept);
        check("null 方法视为 GET", PassiveCapturePolicy.shouldIntercept(true, kb, null).intercept);
        check("空方法视为 GET", PassiveCapturePolicy.shouldIntercept(true, kb, "").intercept);

        // ★回归红线：登录表单是 POST，绝不能碰
        for (String verb : new String[]{"POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "post"}) {
            PassiveCapturePolicy.Decision d = PassiveCapturePolicy.shouldIntercept(true, kb, verb);
            check("★" + verb + " 一律放行", !d.intercept);
            check(verb + " 放行原因含「非 GET」", d.reason.contains("非 GET"));
        }
    }

    // ------------------------------------------------------------------
    // 规则 3：认证相关 URL 绝不拦
    // ------------------------------------------------------------------

    private static void testAuthUrlFilter() {
        String[] authUrls = {
                "https://cas.hzau.edu.cn/cas/login?service=https%3A%2F%2Fjwgl",
                "https://cas.hzau.edu.cn/cas/serviceValidate?ticket=ST-123",
                "https://jwgl.hzau.edu.cn/sso/auth?redirect=/kb",
                "https://auth.hzau.edu.cn/oauth2/authorize?client_id=app",
                "https://x.edu.cn/oauth/callback?code=abc",
                "https://x.edu.cn/openid/login",
                "https://x.edu.cn/saml/SSO",
                "https://x.edu.cn/cas/logout",
                "https://x.edu.cn/signin",
                "https://x.edu.cn/session/refresh",
                "https://x.edu.cn/ticket/ST-9f8e",
                "https://x.edu.cn/j_spring_security_check",
                "https://x.edu.cn/captcha.png",
                "https://x.edu.cn/verify?token=1",
                "https://idp.hzau.edu.cn/profile/SAML2",
                "https://x.edu.cn/LOGIN", // 大小写不敏感
                "https://x.edu.cn/ServiceLogin"
        };
        for (String url : authUrls) {
            PassiveCapturePolicy.Decision d = PassiveCapturePolicy.shouldIntercept(true, url, "GET");
            check("★认证 URL 放行：" + shorten(url), !d.intercept);
        }

        // 正常课表 URL 仍要能捕获
        String[] okUrls = {
                "https://jwgl.hzau.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html",
                "https://jwxt.bnuzh.edu.cn/student/wsxk.xskcb10319.jsp?params=eG49",
                "https://x.edu.cn/kb/list",
                "https://x.edu.cn/api/schedule"
        };
        for (String url : okUrls) {
            check("非认证 URL 可捕获：" + shorten(url),
                    PassiveCapturePolicy.shouldIntercept(true, url, "GET").intercept);
        }

        check("matchAuthToken 命中 login", "login".equals(
                PassiveCapturePolicy.matchAuthToken("https://x/CAS/Login")));
        check("matchAuthToken 未命中返回 null",
                PassiveCapturePolicy.matchAuthToken("https://x/kb/list") == null);
    }

    private static void testStaticAssetFilter() {
        String[] assets = {
                "https://x.edu.cn/app.js", "https://x.edu.cn/a.css?ver=1",
                "https://x.edu.cn/img/logo.png", "https://x.edu.cn/f.woff2",
                "https://x.edu.cn/i/icon.svg#frag", "https://x.edu.cn/report.pdf"
        };
        for (String url : assets) {
            check("静态资源放行：" + shorten(url),
                    !PassiveCapturePolicy.shouldIntercept(true, url, "GET").intercept);
        }
        check("路径含 js 但不是 .js 结尾仍可捕获",
                PassiveCapturePolicy.shouldIntercept(true, "https://x.edu.cn/jsp/list", "GET").intercept);
        check("query 里含 .js 不影响判定",
                PassiveCapturePolicy.shouldIntercept(true, "https://x.edu.cn/kb?file=app.js", "GET").intercept);
    }

    // ------------------------------------------------------------------
    // 规则 2：30x 永不拦
    // ------------------------------------------------------------------

    private static void testRedirectNeverIntercepted() {
        for (int status : new int[]{300, 301, 302, 303, 307, 308}) {
            PassiveCapturePolicy.Decision d = PassiveCapturePolicy.shouldForwardResponse(status);
            check("★" + status + " 重定向必须放行", !d.intercept);
            check(status + " 放行原因含「重定向」", d.reason.contains("重定向"));
        }
        check("204 无实体放行", !PassiveCapturePolicy.shouldForwardResponse(204).intercept);
        check("304 放行", !PassiveCapturePolicy.shouldForwardResponse(304).intercept);
        check("404 放行", !PassiveCapturePolicy.shouldForwardResponse(404).intercept);
        check("500 放行", !PassiveCapturePolicy.shouldForwardResponse(500).intercept);
        check("200 可接管", PassiveCapturePolicy.shouldForwardResponse(200).intercept);
        check("201 可接管", PassiveCapturePolicy.shouldForwardResponse(201).intercept);
        check("206 可接管", PassiveCapturePolicy.shouldForwardResponse(206).intercept);
        check("399 边界仍算重定向", !PassiveCapturePolicy.shouldForwardResponse(399).intercept);
        check("400 边界不算重定向", !PassiveCapturePolicy.shouldForwardResponse(400).intercept);
    }

    // ------------------------------------------------------------------
    // 规则 4：头部保真
    // ------------------------------------------------------------------

    private static void testRequestHeaderFidelity() {
        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("Origin", "https://jwgl.hzau.edu.cn");
        incoming.put("Referer", "https://jwgl.hzau.edu.cn/kbcx/x");
        incoming.put("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
        incoming.put("Accept", "text/html,application/json");
        incoming.put("Accept-Language", "zh-CN,zh;q=0.9");
        incoming.put("X-Requested-With", "XMLHttpRequest");
        incoming.put("X-Custom-Auth-Header", "abc123");

        Map<String, String> out = PassiveCapturePolicy.forwardableHeaders(incoming);
        check("★Origin 必须转发（SSO 校验）", "https://jwgl.hzau.edu.cn".equals(out.get("Origin")));
        check("★Referer 必须转发（OAuth 校验）", "https://jwgl.hzau.edu.cn/kbcx/x".equals(out.get("Referer")));
        check("User-Agent 必须转发", out.get("User-Agent").startsWith("Mozilla/5.0"));
        check("Accept 转发", out.get("Accept") != null);
        check("Accept-Language 转发", out.get("Accept-Language") != null);
        check("X-Requested-With 转发", "XMLHttpRequest".equals(out.get("X-Requested-With")));
        check("★自定义头原样转发", "abc123".equals(out.get("X-Custom-Auth-Header")));
        check("转发头总数为 7", out.size() == 7);

        check("null 输入返回空 Map", PassiveCapturePolicy.forwardableHeaders(null).isEmpty());
        Map<String, String> withNullKey = new LinkedHashMap<>();
        withNullKey.put(null, "v");
        withNullKey.put("X-Ok", "1");
        Map<String, String> filtered = PassiveCapturePolicy.forwardableHeaders(withNullKey);
        check("null 头名被跳过", filtered.size() == 1 && filtered.containsKey("X-Ok"));
    }

    private static void testDroppedTransportHeaders() {
        String[] dropped = {"Host", "Connection", "Keep-Alive", "Proxy-Connection",
                "Transfer-Encoding", "Content-Length", "Upgrade-Insecure-Requests",
                "Cookie", "Accept-Encoding", "Proxy-Authorization"};
        for (String name : dropped) {
            check("传输头被剔除：" + name, PassiveCapturePolicy.isDroppedRequestHeader(name));
        }
        check("普通头不被剔除", !PassiveCapturePolicy.isDroppedRequestHeader("Referer"));
        check("null 头名视为剔除", PassiveCapturePolicy.isDroppedRequestHeader(null));

        Map<String, String> incoming = new LinkedHashMap<>();
        for (String name : dropped) incoming.put(name, "x");
        incoming.put("Referer", "https://x/");
        Map<String, String> out = PassiveCapturePolicy.forwardableHeaders(incoming);
        check("剔除后只剩 Referer", out.size() == 1 && out.containsKey("Referer"));
    }

    private static void testCookieReinjection() {
        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("Origin", "https://x");
        incoming.put("Cookie", "stale=1; partial=2");

        // CookieManager 有完整 Cookie → 整体覆盖
        Map<String, String> out = PassiveCapturePolicy.upstreamHeaders(incoming, "SID=abc; TOKEN=xyz; JSESSIONID=p");
        check("★Cookie 完整重注入（3 个键）",
                out.get("Cookie").equals("SID=abc; TOKEN=xyz; JSESSIONID=p"));
        check("被覆盖后不含 WebView 的旧 Cookie", !out.get("Cookie").contains("stale"));
        check("Origin 与 Cookie 共存", out.containsKey("Origin"));
        check("Accept-Encoding 被改写为 identity", "identity".equals(out.get("Accept-Encoding")));

        // CookieManager 为空 → 回落到 WebView 传来的 Cookie
        Map<String, String> fallback = PassiveCapturePolicy.upstreamHeaders(
                new LinkedHashMap<>(incoming), "");
        check("CookieManager 为空时回落到原 Cookie",
                "stale=1; partial=2".equals(fallback.get("Cookie")));

        // 两边都空 → 不带 Cookie 头
        Map<String, String> none = PassiveCapturePolicy.upstreamHeaders(new LinkedHashMap<String, String>(), "");
        check("都为空时不产生 Cookie 头", !none.containsKey("Cookie"));
        check("仍强制 identity", "identity".equals(none.get("Accept-Encoding")));
    }

    private static void testResponseHeaderForwarding() {
        Map<String, List<String>> upstream = new LinkedHashMap<>();
        upstream.put("Set-Cookie", Arrays.asList("SID=abc; Path=/; HttpOnly", "TOKEN=z; Path=/; Secure"));
        upstream.put("Location", Arrays.asList("https://x/next"));
        upstream.put("Content-Type", Arrays.asList("text/html; charset=UTF-8"));
        upstream.put("X-Powered-By", Arrays.asList("secret-server"));
        upstream.put("Cache-Control", Arrays.asList("no-store"));

        Map<String, String> out = PassiveCapturePolicy.forwardableResponseHeaders(upstream);
        check("★Set-Cookie 必须透传", out.containsKey("Set-Cookie"));
        check("多个 Set-Cookie 用换行保留",
                out.get("Set-Cookie").equals("SID=abc; Path=/; HttpOnly\nTOKEN=z; Path=/; Secure"));
        check("Location 透传", out.containsKey("Location"));
        check("Cache-Control 透传", out.containsKey("Cache-Control"));
        check("X-Powered-By 不转发（非白名单）", !out.containsKey("X-Powered-By"));
        check("Content-Type 不在响应头白名单（由 WebResourceResponse 参数承担）",
                !out.containsKey("Content-Type"));

        check("Set-Cookie2 在白名单", PassiveCapturePolicy.isForwardedResponseHeader("Set-Cookie2"));
        check("set-cookie 大小写不敏感", PassiveCapturePolicy.isForwardedResponseHeader("set-cookie"));
        check("未知头不在白名单", !PassiveCapturePolicy.isForwardedResponseHeader("X-Anything"));
        check("null 不入白名单", !PassiveCapturePolicy.isForwardedResponseHeader(null));
        check("null 上游头返回空 Map",
                PassiveCapturePolicy.forwardableResponseHeaders(null).isEmpty());
    }

    // ------------------------------------------------------------------
    // 规则 6：总开关 & 规则 5：failsafe
    // ------------------------------------------------------------------

    private static void testMasterSwitch() {
        String kb = "https://jwgl.hzau.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html";
        check("开关开启时可捕获", PassiveCapturePolicy.shouldIntercept(true, kb, "GET").intercept);
        PassiveCapturePolicy.Decision off = PassiveCapturePolicy.shouldIntercept(false, kb, "GET");
        check("★开关关闭时一律放行", !off.intercept);
        check("关闭原因写明", off.reason.contains("已关闭"));
        check("关闭时连认证 URL 也放行",
                !PassiveCapturePolicy.shouldIntercept(false, "https://x/cas/login", "GET").intercept);
    }

    private static void testFailsafeInputs() {
        check("null URL 放行", !PassiveCapturePolicy.shouldIntercept(true, null, "GET").intercept);
        check("空 URL 放行", !PassiveCapturePolicy.shouldIntercept(true, "", "GET").intercept);
        check("javascript: 放行",
                !PassiveCapturePolicy.shouldIntercept(true, "javascript:void(0)", "GET").intercept);
        check("file: 放行",
                !PassiveCapturePolicy.shouldIntercept(true, "file:///sdcard/a.html", "GET").intercept);
        check("data: 放行",
                !PassiveCapturePolicy.shouldIntercept(true, "data:text/html,x", "GET").intercept);
        check("空输入 describe 不抛异常",
                !PassiveCapturePolicy.describe("", "").isEmpty());
        check("null 输入 describe 不抛异常",
                PassiveCapturePolicy.describe(null, null).contains("放行"));
    }

    // ------------------------------------------------------------------
    // 回归场景：按真实登录链路逐步断言
    // ------------------------------------------------------------------

    /**
     * 复现「进入学校官网 → CAS 登录 → 回调带 ticket → 进教务 → 点查询」的完整请求序列，
     * 断言每一个会破坏登录的请求都被放行。
     */
    private static void testRegressionScenarios() {
        String[][] flow = {
                // url, method, 期望是否拦截
                {"https://jwgl.hzau.edu.cn/", "GET", "capture"},
                {"https://jwgl.hzau.edu.cn/cas/login?service=https%3A%2F%2Fjwgl.hzau.edu.cn%2F", "GET", "pass"},
                {"https://jwgl.hzau.edu.cn/cas/login", "POST", "pass"},
                {"https://cas.hzau.edu.cn/cas/serviceValidate?ticket=ST-123-abc", "GET", "pass"},
                {"https://jwgl.hzau.edu.cn/?ticket=ST-123-abc", "GET", "pass"},
                {"https://jwgl.hzau.edu.cn/oauth2/callback?code=x", "GET", "pass"},
                {"https://jwgl.hzau.edu.cn/cas/logout", "GET", "pass"},
                {"https://jwgl.hzau.edu.cn/sso/session/keepalive", "GET", "pass"},
                {"https://jwgl.hzau.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html", "GET", "capture"},
                {"https://jwgl.hzau.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html", "POST", "pass"},
                {"https://jwgl.hzau.edu.cn/js/jquery.js", "GET", "pass"}
        };
        for (String[] row : flow) {
            boolean actual = PassiveCapturePolicy.shouldIntercept(true, row[0], row[1]).intercept;
            boolean expected = "capture".equals(row[2]);
            String label = (expected ? "应捕获 " : "应放行 ") + row[1] + " " + shorten(row[0]);
            check(label, actual == expected);
        }

        // 30x 在整条链上都必须放行（CAS 302 → login → 302 → 带 ticket 回到业务页）
        for (int status : new int[]{302, 302, 303}) {
            check("CAS 链路中的 " + status + " 放行",
                    !PassiveCapturePolicy.shouldForwardResponse(status).intercept);
        }
    }

    // ------------------------------------------------------------------

    private static String shorten(String url) {
        if (url == null) return "null";
        return url.length() > 62 ? url.substring(0, 62) + "…" : url;
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
}
