package dev.bbsfusion.core;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

/** Cookie deletion must match the stored domain and path, not only its name. */
public final class SessionCookiePolicy {
    private SessionCookiePolicy() { }

    public static Set<String> pathsFor(String url) {
        Set<String> paths = new LinkedHashSet<>();
        paths.add("/");
        try {
            String path = URI.create(url).getRawPath();
            if (path == null || !path.startsWith("/")) {
                return paths;
            }
            // Include both the explicit Path value and every possible default directory path.
            paths.add(path);
            for (int slash = path.indexOf('/', 1); slash >= 0; slash = path.indexOf('/', slash + 1)) {
                paths.add(path.substring(0, slash));
                paths.add(path.substring(0, slash + 1));
            }
        } catch (IllegalArgumentException ignored) {
            // The root remains safe for a malformed optional URL.
        }
        return paths;
    }

    public static Set<String> names(String header) {
        Set<String> names = new LinkedHashSet<>();
        if (header == null) {
            return names;
        }
        for (String part : header.split(";")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                String name = part.substring(0, equals).trim();
                if (name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    public static String expiredCookie(String name, String path, String domain) {
        if (name.startsWith("__Host-") && (!"/".equals(path) || !domain.isEmpty())) {
            return "";
        }
        return name + "=; Path=" + path
                + "; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly"
                + (domain.isEmpty() ? "" : "; Domain=" + domain);
    }
}
