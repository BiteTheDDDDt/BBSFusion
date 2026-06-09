package dev.bbsfusion.site;

import android.webkit.CookieManager;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
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
        Connection connection = Jsoup.connect(actionUrl)
                .userAgent(USER_AGENT)
                .header("X-User-Agent", NGA_APP_USER_AGENT)
                .header("Accept", "application/json,text/plain,*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                .timeout(TIMEOUT_MILLIS)
                .maxBodySize(MAX_BODY_SIZE)
                .method(Connection.Method.POST)
                .ignoreContentType(true)
                .ignoreHttpErrors(true);

        for (Map.Entry<String, String> entry : formData.entrySet()) {
            connection.data(entry.getKey(), entry.getValue());
        }

        CookieManager cookieManager = CookieManager.getInstance();
        appendCookieHeader(connection, cookieManager, actionUrl);
        appendCookieHeader(connection, cookieManager, "https://bbs.nga.cn/");

        Connection.Response response = connection.execute();
        saveCookies(cookieManager, response);

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
        Connection connection = baseConnection(url, referrer, desktop)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

        Connection.Response response = execute(connection, url, referrer);

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
        Connection connection = baseConnection(url, referrer, desktop)
                .header("Accept", accept);
        Connection.Response response = execute(connection, url, referrer);
        return response.body();
    }

    private static Connection baseConnection(String url, String referrer, boolean desktop) {
        Connection connection = Jsoup.connect(url)
                .userAgent(desktop ? DESKTOP_USER_AGENT : USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                .timeout(TIMEOUT_MILLIS)
                .maxBodySize(MAX_BODY_SIZE)
                .followRedirects(true)
                .ignoreContentType(true)
                .ignoreHttpErrors(true);
        if (referrer != null && !referrer.trim().isEmpty()) {
            connection.referrer(referrer);
        }
        return connection;
    }

    private static Connection.Response execute(
            Connection connection,
            String url,
            String referrer
    ) throws IOException {
        CookieManager cookieManager = CookieManager.getInstance();
        appendCookieHeader(connection, cookieManager, url);
        if (referrer != null && !referrer.trim().isEmpty()) {
            appendCookieHeader(connection, cookieManager, referrer);
        }

        Connection.Response response;
        try {
            response = connection.execute();
        } catch (IOException error) {
            throw annotateConnectionError(url, error);
        }
        saveCookies(cookieManager, response);
        if (response.statusCode() >= 400) {
            throw new IOException("站点返回 HTTP " + response.statusCode() + "。可尝试原站登录或换网络环境。");
        }
        return response;
    }

    private static void appendCookieHeader(
            Connection connection,
            CookieManager cookieManager,
            String url
    ) {
        String cookie = cookieManager.getCookie(url);
        if (cookie == null || cookie.trim().isEmpty()) {
            return;
        }

        String existing = connection.request().header("Cookie");
        if (existing == null || existing.trim().isEmpty()) {
            connection.header("Cookie", cookie);
        } else {
            connection.header("Cookie", existing + "; " + cookie);
        }
    }

    private static void saveCookies(CookieManager cookieManager, Connection.Response response) {
        String responseUrl = response.url().toString();
        for (Map.Entry<String, String> cookie : response.cookies().entrySet()) {
            cookieManager.setCookie(responseUrl, cookie.getKey() + "=" + cookie.getValue());
        }
        cookieManager.flush();
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

    private static void throwIfAccessBlocked(Document document) throws IOException {
        String title = document.title() == null ? "" : document.title();
        String text = document.body() == null ? "" : document.body().text();
        String normalized = text.toLowerCase(Locale.ROOT);
        if (text.contains("未登录")
                || text.contains("请登录")
                || text.contains("登录后访问")
                || text.contains("你可能需要")
                && text.contains("登录")
                || normalized.contains("login required")) {
            throw new IOException("需要登录：请点“原站登录”，完成登录后返回刷新。");
        }
        if (title.contains("提示信息")) {
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
