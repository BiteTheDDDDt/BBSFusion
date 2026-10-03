package dev.bbsfusion.core;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/** Validates every image hop before any credentials or bytes are sent. */
public final class ImageRequestPolicy {
    private ImageRequestPolicy() {
    }

    public static URI parse(String value) throws IOException {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IOException("无效的图片地址。");
            }
            return uri;
        } catch (URISyntaxException | IllegalArgumentException | NullPointerException error) {
            throw new IOException("无效的图片地址。", error);
        }
    }

    public static URI redirect(URI current, String location) throws IOException {
        if (location == null || location.trim().isEmpty()) {
            throw new IOException("图片重定向缺少地址。");
        }
        URI next;
        try {
            next = parse(HttpUrlResolver.resolve(current, location.trim()).toString());
        } catch (IllegalArgumentException error) {
            throw new IOException("无效的图片重定向。", error);
        }
        if ("https".equalsIgnoreCase(current.getScheme())
                && !"https".equalsIgnoreCase(next.getScheme())) {
            throw new IOException("图片重定向不能降级到 HTTP。");
        }
        return next;
    }

    public interface CookieLookup {
        String get(String url);
    }

    public static String cookiesFor(URI target, CookieLookup cookies) {
        String value = cookies.get(target.toString());
        return value == null ? "" : value;
    }

    public static String referrer(String topicUrl, URI target) {
        try {
            URI topic = parse(topicUrl);
            if ("https".equalsIgnoreCase(topic.getScheme()) && !"https".equalsIgnoreCase(target.getScheme())) {
                return "";
            }
            if (topic.getScheme().equalsIgnoreCase(target.getScheme())
                    && topic.getHost().equalsIgnoreCase(target.getHost()) && topic.getPort() == target.getPort()) {
                return new URI(topic.getScheme(), topic.getAuthority(), topic.getPath(), topic.getQuery(), null).toString();
            }
            return new URI(topic.getScheme(), topic.getAuthority(), "/", null, null).toString();
        } catch (IOException | URISyntaxException ignored) {
            return "";
        }
    }
}
