package dev.bbsfusion.core;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

public final class GitHubReleasesTest {
    private static final String API_URL =
            "https://api.github.com/repos/BiteTheDDDDt/BBSFusion/releases/latest";
    private static final String STABLE =
            "{\"tag_name\":\"v0.5.1\",\"draft\":false,\"prerelease\":false}";

    @Test
    public void comparesNumericSegmentsAndIgnoresMissingTrailingZeros() {
        assertEquals(-1, GitHubReleases.compareVersions("v0.5", "v0.5.1"));
        assertEquals(1, GitHubReleases.compareVersions("0.10", "v0.9"));
        assertEquals(0, GitHubReleases.compareVersions("v0.5", "0.5.0.0"));
        assertEquals(0, GitHubReleases.compareVersions("V00.005.00", "v0.5"));
        assertEquals(1, GitHubReleases.compareVersions("v1", "0.99.999"));
        assertEquals(-1, GitHubReleases.compareVersions("v0.5.0.1", "0.5.1"));
    }

    @Test
    public void doesNotOverflowOnLargeNumericSegments() {
        assertEquals(1, GitHubReleases.compareVersions("1.100000000000000000000000000000",
                "1.99999999999999999999999999999"));
        assertEquals(0, GitHubReleases.compareVersions("0.000000000000000000000000000005", "0.5"));
    }

    @Test
    public void rejectsUnknownVersionFormatsOnBothSides() {
        for (String invalid : new String[] {null, "", "v", ".5", "0.", "0..5", "-0.5",
                "v0.5-rc1", "0.5+build", "version 0.5", " 0.5", "0.5\n", "０.５",
                "v0.5<script>", new String(new char[129]).replace('\0', '1')}) {
            assertThrows(IllegalArgumentException.class,
                    () -> GitHubReleases.compareVersions(invalid, "0.5"));
            assertThrows(IllegalArgumentException.class,
                    () -> GitHubReleases.compareVersions("0.5", invalid));
        }
    }

    @Test
    public void onlyAcceptsAnExplicitStableReleaseWithRecognizedTag() throws Exception {
        assertEquals("v0.5.1", GitHubReleases.parseLatestVersion(STABLE));
        assertEquals("v0.5.1", GitHubReleases.parseLatestVersion(STABLE.substring(0,
                STABLE.length() - 1) + ",\"html_url\":\"https://untrusted.example/\"}"));
        assertEquals("https://github.com/BiteTheDDDDt/BBSFusion/releases/latest",
                GitHubReleases.RELEASES_URL);
        for (String invalid : new String[] {null, "", "<html>error</html>", "[]", "null",
                "{}", STABLE + " trailing", STABLE.replace("false", "true"),
                "{\"tag_name\":\"v0.5\",\"draft\":false,\"prerelease\":true}",
                "{\"tag_name\":\"v0.5\",\"draft\":false}",
                "{\"tag_name\":\"v0.5\",\"draft\":\"false\",\"prerelease\":false}",
                "{\"tag_name\":5,\"draft\":false,\"prerelease\":false}",
                STABLE.replace("v0.5.1", "v0.6-beta")}) {
            assertThrows(IOException.class, () -> GitHubReleases.parseLatestVersion(invalid));
        }
    }

    @Test
    public void configuresAnAnonymousBoundedRequestWithoutRedirects() throws Exception {
        FakeConnection connection = new FakeConnection(200, STABLE.getBytes(StandardCharsets.UTF_8));

        assertEquals("v0.5.1", GitHubReleases.fetchLatestVersion(connection));

        assertEquals("GET", connection.getRequestMethod());
        assertFalse(connection.getInstanceFollowRedirects());
        assertFalse(connection.getUseCaches());
        assertEquals(8_000, connection.getConnectTimeout());
        assertEquals(8_000, connection.getReadTimeout());
        assertNull(connection.getRequestProperty("Cookie"));
        assertNull(connection.getRequestProperty("Authorization"));
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"));
        assertTrue(connection.closed);
        assertTrue(connection.disconnected);
    }

    @Test
    public void rejectsRedirectsAndErrorStatusesBeforeReadingTheirBodies() throws Exception {
        for (int status : new int[] {301, 302, 307, 308, 403, 404, 429, 500}) {
            FakeConnection connection = new FakeConnection(status, new byte[0]);
            assertThrows(IOException.class, () -> GitHubReleases.fetchLatestVersion(connection));
            assertFalse(connection.bodyOpened);
            assertTrue(connection.disconnected);
        }
    }

    @Test
    public void distinguishesRateLimitingFromOtherNetworkFailures() throws Exception {
        FakeConnection exhausted = new FakeConnection(403, new byte[0]);
        exhausted.remaining = "0";
        assertThrows(GitHubReleases.RateLimitedException.class,
                () -> GitHubReleases.fetchLatestVersion(exhausted));
        FakeConnection limited = new FakeConnection(429, new byte[0]);
        assertThrows(GitHubReleases.RateLimitedException.class,
                () -> GitHubReleases.fetchLatestVersion(limited));
    }

    @Test
    public void enforcesTheByteLimitForChunkedAndDeclaredBodies() throws Exception {
        byte[] oversized = new byte[128 * 1024 + 1];
        Arrays.fill(oversized, (byte) ' ');
        FakeConnection chunked = new FakeConnection(200, oversized);
        assertThrows(IOException.class, () -> GitHubReleases.fetchLatestVersion(chunked));
        assertTrue(chunked.closed);
        assertTrue(chunked.disconnected);

        FakeConnection declared = new FakeConnection(200, oversized);
        declared.declaredLength = oversized.length;
        assertThrows(IOException.class, () -> GitHubReleases.fetchLatestVersion(declared));
        assertFalse(declared.bodyOpened);
        assertTrue(declared.disconnected);

        byte[] boundary = new byte[128 * 1024];
        Arrays.fill(boundary, (byte) ' ');
        byte[] json = STABLE.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(json, 0, boundary, 0, json.length);
        assertEquals("v0.5.1", GitHubReleases.fetchLatestVersion(new FakeConnection(200, boundary)));
    }

    private static final class FakeConnection extends HttpURLConnection {
        final int status;
        final byte[] body;
        long declaredLength = -1;
        String remaining;
        boolean bodyOpened;
        boolean closed;
        boolean disconnected;

        FakeConnection(int status, byte[] body) throws Exception {
            super(new URL(API_URL));
            this.status = status;
            this.body = body;
        }

        @Override public int getResponseCode() { return status; }
        @Override public long getContentLengthLong() { return declaredLength; }
        @Override public String getHeaderField(String name) {
            return "X-RateLimit-Remaining".equalsIgnoreCase(name) ? remaining : null;
        }
        @Override public InputStream getInputStream() {
            bodyOpened = true;
            return new ByteArrayInputStream(body) {
                @Override public void close() throws IOException {
                    closed = true;
                    super.close();
                }
            };
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
