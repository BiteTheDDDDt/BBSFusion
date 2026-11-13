package dev.bbsfusion.core;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Explicit, anonymous release checks, independent of forum sessions and NetworkClient. */
public final class GitHubReleases {
    public static final String RELEASES_URL =
            "https://github.com/BiteTheDDDDt/BBSFusion/releases/latest";
    private static final String API_URL =
            "https://api.github.com/repos/BiteTheDDDDt/BBSFusion/releases/latest";
    private static final int TIMEOUT_MILLIS = 8_000;
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final Pattern VERSION = Pattern.compile("[vV]?[0-9]+(?:\\.[0-9]+)*");

    private GitHubReleases() {
    }

    public static final class RateLimitedException extends IOException {
        private RateLimitedException() {
            super("GitHub release check rate limited");
        }
    }

    /** Performs network I/O; callers must invoke this off the UI thread, on user request. */
    public static String fetchLatestVersion() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        return fetchLatestVersion(connection);
    }

    static String fetchLatestVersion(HttpURLConnection connection) throws IOException {
        try {
            if (!API_URL.equals(connection.getURL().toExternalForm())) {
                throw new IOException("Unexpected release endpoint");
            }
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "BBSFusion-UpdateChecker");
            // No WebView CookieManager, forum NetworkClient, or Authorization header.
            // The app does not install a java.net.CookieHandler or global authenticator.
            int status = connection.getResponseCode();
            if (status == 429 || (status == HttpURLConnection.HTTP_FORBIDDEN
                    && "0".equals(connection.getHeaderField("X-RateLimit-Remaining")))) {
                throw new RateLimitedException();
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Release check failed (HTTP " + status + ")");
            }
            if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) {
                throw new IOException("Release response is too large");
            }
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (body.size() + count > MAX_RESPONSE_BYTES) {
                        throw new IOException("Release response is too large");
                    }
                    body.write(buffer, 0, count);
                }
                return parseLatestVersion(new String(body.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    static String parseLatestVersion(String payload) throws IOException {
        try {
            JSONTokener tokenizer = new JSONTokener(payload == null ? "" : payload);
            Object value = tokenizer.nextValue();
            if (!(value instanceof JSONObject) || tokenizer.nextClean() != 0) {
                throw new IOException("Invalid release response");
            }
            JSONObject release = (JSONObject) value;
            if (!Boolean.FALSE.equals(release.opt("draft"))
                    || !Boolean.FALSE.equals(release.opt("prerelease"))) {
                throw new IOException("No stable release in response");
            }
            Object tag = release.opt("tag_name");
            if (!(tag instanceof String)) {
                throw new IOException("Invalid release version");
            }
            versionParts((String) tag);
            return (String) tag;
        } catch (JSONException | IllegalArgumentException error) {
            // Do not expose arbitrary response bodies through parser exception messages.
            throw new IOException("Invalid release response");
        }
    }

    /** Returns a negative/zero/positive value when left is older/equal/newer than right. */
    public static int compareVersions(String left, String right) {
        String[] leftParts = versionParts(left);
        String[] rightParts = versionParts(right);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            String leftPart = i < leftParts.length ? leftParts[i] : "0";
            String rightPart = i < rightParts.length ? rightParts[i] : "0";
            int result = Integer.compare(leftPart.length(), rightPart.length());
            if (result == 0) {
                result = leftPart.compareTo(rightPart);
            }
            if (result != 0) {
                return Integer.signum(result);
            }
        }
        return 0;
    }

    private static String[] versionParts(String version) {
        if (version == null || version.length() > 128 || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Unrecognized version");
        }
        String number = version.startsWith("v") || version.startsWith("V")
                ? version.substring(1) : version;
        String[] parts = number.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            int start = 0;
            while (start < parts[i].length() - 1 && parts[i].charAt(start) == '0') {
                start++;
            }
            parts[i] = parts[i].substring(start);
        }
        return parts;
    }
}
