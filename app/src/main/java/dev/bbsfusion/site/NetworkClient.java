package dev.bbsfusion.site;

import android.webkit.CookieManager;
import dev.bbsfusion.core.HttpUrlResolver;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;

final class NetworkClient {
    private static final int TIMEOUT_MILLIS = 15000;
    private static final int MAX_BODY_SIZE = 3 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 8;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36 BBSFusion/0.1";
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36 BBSFusion/0.1";
    private static final String NGA_APP_USER_AGENT = "NGA_skull/6.0.5(iPhone10,3;iOS 12.0.1)";

    static {
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("java.net.preferIPv6Addresses", "false");
    }

    private NetworkClient() {
    }

    static Document get(String url, String referrer) throws IOException {
        return get(url, referrer, false);
    }

    static Document getDesktop(String url, String referrer) throws IOException {
        return get(url, referrer, true);
    }

    static JSONObject getJsonObject(String url, String referrer) throws IOException {
        String body = getBody(url, referrer, true, "application/json,text/plain,*/*");
        try {
            return new JSONObject(body);
        } catch (JSONException error) {
            throw new IOException("JSON 返回无法解析。", error);
        }
    }

    static JSONArray getJsonArray(String url, String referrer) throws IOException {
        String body = getBody(url, referrer, true, "application/json,text/plain,*/*");
        try {
            return new JSONArray(body);
        } catch (JSONException error) {
            throw new IOException("JSON 返回无法解析。", error);
        }
    }

    static Document getXml(String url, String referrer) throws IOException {
        String body = getBody(url, referrer, true, "application/rss+xml,application/xml,text/xml,*/*");
        return Jsoup.parse(body, url, Parser.xmlParser());
    }

    static JSONObject postNgaApi(String actionUrl, Map<String, String> formData) throws IOException {
        Connection.Response response = request(
                actionUrl, null, false, "application/json,text/plain,*/*", formData
        );

        try {
            JSONObject json = new JSONObject(response.body());
            int code = json.optInt("code", 0);
            if (code != 0) {
                String message = json.optString("msg", "接口返回错误");
                if (message.contains("未登录")) {
                    throw new IOException("需要登录：请点“原站登录”，完成登录后返回刷新。");
                }
                throw new IOException(message);
            }
            return json;
        } catch (JSONException error) {
            throw new IOException("NGA 接口返回无法解析。", error);
        }
    }

    private static Document get(String url, String referrer, boolean desktop) throws IOException {
        Connection.Response response = request(url, referrer, desktop,
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", null);

        Document document = response.parse();
        throwIfAccessBlocked(document);
        return document;
    }

    private static String getBody(
            String url,
            String referrer,
            boolean desktop,
            String accept
    ) throws IOException {
        Connection.Response response = request(url, referrer, desktop, accept, null);
        return response.body();
    }

    private static Connection baseConnection(String url, String referrer, boolean desktop) {
        Connection connection = Jsoup.connect(url)
                .userAgent(desktop ? DESKTOP_USER_AGENT : USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                .timeout(TIMEOUT_MILLIS)
                .maxBodySize(MAX_BODY_SIZE)
                .followRedirects(false)
                .ignoreContentType(true)
                .ignoreHttpErrors(true);
        if (referrer != null && !referrer.trim().isEmpty()) {
            connection.referrer(referrer);
        }
        return connection;
    }

    private static Connection.Response request(
            String url,
            String referrer,
            boolean desktop,
            String accept,
            Map<String, String> formData
    ) throws IOException {
        CookieManager cookieManager = CookieManager.getInstance();
        try {
            return execute(url, referrer, desktop, accept, formData, new CookieStore() {
                @Override
                public String get(String targetUrl) {
                    return cookieManager.getCookie(targetUrl);
                }

                @Override
                public void set(String targetUrl, String header) {
                    cookieManager.setCookie(targetUrl, header);
                }
            }, Connection::execute);
        } finally {
            cookieManager.flush();
        }
    }

    // Each redirect gets a fresh connection and cookies selected for that URL only.
    // Never let a transport copy a manually supplied Cookie header to the next host.
    static Connection.Response execute(
            String url,
            String referrer,
            boolean desktop,
            String accept,
            Map<String, String> formData,
            CookieStore cookies,
            ResponseFetcher fetcher
    ) throws IOException {
        URI original = webUri(url);
        URI current = original;
        String currentReferrer = referrer;
        boolean post = formData != null;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            String currentUrl = current.toString();
            Connection connection = baseConnection(currentUrl, currentReferrer, desktop)
                    .header("Accept", accept);
            if (post) {
                connection.method(Connection.Method.POST).header("X-User-Agent", NGA_APP_USER_AGENT);
                for (Map.Entry<String, String> entry : formData.entrySet()) {
                    connection.data(entry.getKey(), entry.getValue());
                }
            }
            String cookie = cookies.get(currentUrl);
            if (cookie != null && !cookie.trim().isEmpty()) {
                connection.header("Cookie", cookie);
            }

            Connection.Response response;
            try {
                response = fetcher.fetch(connection);
            } catch (IOException error) {
                throw annotateConnectionError(currentUrl, error);
            }
            for (String header : response.headers("Set-Cookie")) {
                cookies.set(currentUrl, header);
            }

            int status = response.statusCode();
            boolean redirect = status == 301 || status == 302 || status == 303
                    || status == 307 || status == 308;
            if (!redirect) {
                if (status >= 400) {
                    response.body();
                    throw new IOException("站点返回 HTTP " + status + "。可尝试原站登录或换网络环境。");
                }
                return response;
            }

            // Consume the intermediate body so its connection is released.
            response.body();
            String location = response.header("Location");
            if (location == null || location.trim().isEmpty() || redirects == MAX_REDIRECTS) {
                throw new IOException("站点重定向无效或次数过多。请使用原站打开。");
            }
            URI next;
            try {
                next = webUri(HttpUrlResolver.resolve(current, location.trim()).toString());
            } catch (IllegalArgumentException error) {
                throw new IOException("站点返回了无效的重定向地址。", error);
            }
            if ("https".equalsIgnoreCase(current.getScheme())
                    && !"https".equalsIgnoreCase(next.getScheme())) {
                throw new IOException("已停止从 HTTPS 跳转到不安全连接。请使用原站打开。");
            }
            if (formData != null && !sameOrigin(original, next)) {
                throw new IOException("接口重定向到了其他站点。请使用原站打开。");
            }
            currentReferrer = sameOrigin(current, next) ? currentUrl : null;
            if (status == 301 || status == 302 || status == 303) {
                post = false;
            }
            current = next;
        }
        throw new IOException("站点重定向次数过多。");
    }

    private static URI webUri(String url) throws IOException {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && uri.getUserInfo() == null) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // Report malformed and unsupported targets as a normal fetch failure.
        }
        throw new IOException("站点链接不是有效的 HTTP 或 HTTPS 地址。");
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    interface CookieStore {
        String get(String url);

        void set(String url, String header);
    }

    interface ResponseFetcher {
        Connection.Response fetch(Connection connection) throws IOException;
    }

    private static IOException annotateConnectionError(String url, IOException error) {
        String host = hostFromUrl(url).toLowerCase(Locale.ROOT);
        String message = error.getMessage() == null ? "" : error.getMessage();
        String normalized = message.toLowerCase(Locale.ROOT);
        boolean likelyNetworkProblem = normalized.contains("failed to connect")
                || normalized.contains("unable to resolve host")
                || normalized.contains("no address associated")
                || normalized.contains("timed out")
                || normalized.contains("connection refused");
        if ((host.endsWith("v2ex.com") || host.endsWith("linux.do")) && likelyNetworkProblem) {
            String site = host.endsWith("v2ex.com") ? "V2EX" : "Linux.do";
            return new IOException(site + " 连接失败：当前网络、DNS 或代理可能没有生效。"
                    + "如果是在模拟器里测试，请给模拟器配置系统代理或确保 TUN 接管模拟器。"
                    + "原始错误：" + concise(message), error);
        }
        return error;
    }

    private static String hostFromUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    static void throwIfAccessBlocked(Document document) throws IOException {
        String title = document.title() == null ? "" : document.title();
        // A visitor prompt or a discussion about logging in is not an access error.
        if (document.selectFirst("tbody[id^=normalthread_], #threadlist, .threadlist, "
                + ".t_f, [id^=postmessage_], .plc[id^=pid] .message, "
                + ".postcontent, [id^=postcontent], .post_content, .topic-body, article") != null) {
            return;
        }
        Element notice = document.selectFirst("#messagetext, #messagetext_0, .alert_error, "
                + ".alert_error_login, .permission-denied, #errorbox");
        boolean promptPage = title.trim().startsWith("提示信息");
        String text = notice != null ? notice.text()
                : promptPage && document.body() != null ? document.body().text() : "";
        String normalized = text.toLowerCase(Locale.ROOT);
        String normalizedTitle = title.trim().toLowerCase(Locale.ROOT);
        boolean loginPage = (normalizedTitle.startsWith("登录") || normalizedTitle.startsWith("登陆")
                || normalizedTitle.startsWith("login") || normalizedTitle.startsWith("log in")
                || normalizedTitle.startsWith("sign in"))
                && document.selectFirst("form input[type=password]") != null;
        if (loginPage || text.contains("未登录")
                || text.contains("请登录")
                || text.contains("登录后访问")
                || text.contains("你可能需要")
                && text.contains("登录")
                || normalized.contains("login required")) {
            throw new IOException("需要登录：请点“原站登录”，完成登录后返回刷新。");
        }
        if (promptPage) {
            throw new IOException("站点提示：" + concise(text));
        }
    }

    private static String concise(String text) {
        if (text == null) {
            return "页面不可访问。";
        }
        String cleaned = text.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return "页面不可访问。";
        }
        int titleIndex = cleaned.indexOf("提示信息");
        if (titleIndex >= 0) {
            cleaned = cleaned.substring(titleIndex + "提示信息".length()).trim();
        }
        int maxLength = 80;
        if (cleaned.length() > maxLength) {
            return cleaned.substring(0, maxLength) + "...";
        }
        return cleaned;
    }
}
