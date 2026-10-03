package dev.bbsfusion.site;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class NetworkClientTest {
    @Test
    public void queryOnlyRedirectKeepsTheCurrentResourcePath() throws Exception {
        List<String> urls = new ArrayList<>();
        NetworkClient.execute("https://forum.test/dir/topic?old=1", null, false, "text/html", null,
                new RecordingCookies(), connection -> {
                    urls.add(connection.request().url().toString());
                    return urls.size() == 1 ? response(302, "?next=2") : response(200, null);
                });
        assertEquals(List.of("https://forum.test/dir/topic?old=1",
                "https://forum.test/dir/topic?next=2"), urls);
    }

    @Test
    public void acceptsLoginWordsInsideAReadablePostAndVisitorFooter() throws Exception {
        NetworkClient.throwIfAccessBlocked(Jsoup.parse(
                "<title>登录功能讨论</title><div id='postmessage_1'>为什么提示请登录？</div>"
                        + "<footer>未登录，请登录后回复</footer>"
        ));
        NetworkClient.throwIfAccessBlocked(Jsoup.parse(
                "<title>普通讨论</title><p>未登录也可以看帖，请登录的提示应该放哪里？</p>"
        ));
    }

    @Test
    public void stillReportsExplicitLoginAndPermissionErrorPages() {
        IOException login = assertThrows(IOException.class, () -> NetworkClient.throwIfAccessBlocked(
                Jsoup.parse("<title>提示信息 - S1</title><div id='messagetext'>请登录后访问</div>")
        ));
        assertTrue(login.getMessage().startsWith("需要登录"));
        IOException permission = assertThrows(IOException.class, () -> NetworkClient.throwIfAccessBlocked(
                Jsoup.parse("<title>提示信息 - S1</title><div id='messagetext'>没有访问权限</div>")
        ));
        assertTrue(permission.getMessage().contains("没有访问权限"));
        assertThrows(IOException.class, () -> NetworkClient.throwIfAccessBlocked(Jsoup.parse(
                "<title>登录 - S1</title><form><input type='password'></form>"
        )));
    }

    @Test
    public void selectsCookiesForEveryTargetAndNeverForTheReferrer() throws Exception {
        RecordingCookies cookies = new RecordingCookies();
        cookies.values.put("https://forum.test/topic", "forum_session=private");
        cookies.values.put("https://cdn.test/image", "cdn_session=allowed");
        cookies.values.put("https://cdn.test/public/image", "public_pref=small");
        List<String> requestedUrls = new ArrayList<>();
        List<String> sentCookies = new ArrayList<>();
        List<String> sentReferrers = new ArrayList<>();
        NetworkClient.execute("https://forum.test/topic", "https://unrelated.test/login",
                false, "text/html", null, cookies, connection -> {
                    assertFalse(connection.request().followRedirects());
                    requestedUrls.add(connection.request().url().toString());
                    sentCookies.add(connection.request().header("Cookie"));
                    sentReferrers.add(connection.request().header("Referer"));
                    int request = requestedUrls.size();
                    return request == 1 ? response(302, "https://cdn.test/image")
                            : request == 2 ? response(302, "/public/image") : response(200, null);
                });

        assertEquals(List.of("https://forum.test/topic", "https://cdn.test/image",
                "https://cdn.test/public/image"), requestedUrls);
        assertEquals(requestedUrls, cookies.requestedUrls);
        assertEquals(List.of("forum_session=private", "cdn_session=allowed", "public_pref=small"), sentCookies);
        assertNull(sentReferrers.get(1));
    }

    @Test
    public void doesNotCarryCookiesToATargetWithoutCookies() throws Exception {
        RecordingCookies cookies = new RecordingCookies();
        cookies.values.put("https://forum.test/topic", "session=private");
        List<String> sentCookies = new ArrayList<>();
        NetworkClient.execute("https://forum.test/topic", null, false, "text/html", null,
                cookies, connection -> {
                    sentCookies.add(connection.request().header("Cookie"));
                    return sentCookies.size() == 1 ? response(302, "https://other.test/topic")
                            : response(200, null);
                });
        assertEquals("session=private", sentCookies.get(0));
        assertNull(sentCookies.get(1));
    }

    @Test
    public void preservesAllSetCookieHeadersAndAttributesIncludingDeletion() throws Exception {
        RecordingCookies cookies = new RecordingCookies();
        String session = "session=new; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=600";
        String deletion = "old=; Domain=forum.test; Path=/account; Max-Age=0; "
                + "Expires=Thu, 01 Jan 1970 00:00:00 GMT";
        NetworkClient.execute("https://forum.test/topic", null, false, "text/html", null,
                cookies, connection -> cookies.savedHeaders.isEmpty()
                        ? response(302, "/next", session, deletion) : response(200, null));
        assertEquals(List.of(session, deletion), cookies.savedHeaders);
        assertEquals(List.of("https://forum.test/topic", "https://forum.test/topic"), cookies.savedUrls);
    }

    @Test
    public void rejectsCrossOriginPostRedirectBeforeSendingASecondRequest() {
        RecordingCookies cookies = new RecordingCookies();
        int[] requests = {0};
        assertThrows(IOException.class, () -> NetworkClient.execute(
                "https://ngabbs.com/app_api.php", null, false, "application/json",
                Map.of("tid", "123"), cookies, connection -> {
                    requests[0]++;
                    assertEquals(Connection.Method.POST, connection.request().method());
                    return response(307, "https://untrusted.test/collect");
                }));
        assertEquals(1, requests[0]);
    }

    @Test
    public void preservesPostDataOnASameOrigin307Redirect() throws Exception {
        RecordingCookies cookies = new RecordingCookies();
        int[] requests = {0};
        NetworkClient.execute("https://ngabbs.com/app_api.php", null, false, "application/json",
                Map.of("tid", "123"), cookies, connection -> {
                    requests[0]++;
                    assertEquals(Connection.Method.POST, connection.request().method());
                    assertEquals("123", connection.request().data().iterator().next().value());
                    return requests[0] == 1 ? response(307, "/api") : response(200, null);
                });
        assertEquals(2, requests[0]);
    }

    @Test
    public void rejectsHttpsDowngradesAndNonHttpRedirects() {
        for (String location : List.of("http://forum.test/next", "file:///private")) {
            int[] requests = {0};
            assertThrows(IOException.class, () -> NetworkClient.execute(
                    "https://forum.test/topic", null, false, "text/html", null,
                    new RecordingCookies(), connection -> {
                        requests[0]++;
                        return response(302, location);
                    }));
            assertEquals(1, requests[0]);
        }
    }

    @Test
    public void boundsRedirectLoops() {
        int[] requests = {0};
        assertThrows(IOException.class, () -> NetworkClient.execute(
                "https://forum.test/topic", null, false, "text/html", null,
                new RecordingCookies(), connection -> {
                    requests[0]++;
                    return response(302, "/topic");
                }));
        assertTrue(requests[0] > 1 && requests[0] <= 10);
    }

    private static Connection.Response response(int status, String location, String... setCookies) {
        return (Connection.Response) Proxy.newProxyInstance(Connection.Response.class.getClassLoader(),
                new Class<?>[]{Connection.Response.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "statusCode": return status;
                        case "body": return "";
                        case "header": return "Location".equalsIgnoreCase((String) args[0]) ? location : null;
                        case "headers": return "Set-Cookie".equalsIgnoreCase((String) args[0])
                                ? List.of(setCookies) : Collections.emptyList();
                        default: throw new AssertionError("Unexpected response method: " + method.getName());
                    }
                });
    }

    private static final class RecordingCookies implements NetworkClient.CookieStore {
        final Map<String, String> values = new HashMap<>();
        final List<String> requestedUrls = new ArrayList<>();
        final List<String> savedUrls = new ArrayList<>();
        final List<String> savedHeaders = new ArrayList<>();

        @Override
        public String get(String url) {
            requestedUrls.add(url);
            return values.get(url);
        }

        @Override
        public void set(String url, String header) {
            savedUrls.add(url);
            savedHeaders.add(header);
        }
    }
}
