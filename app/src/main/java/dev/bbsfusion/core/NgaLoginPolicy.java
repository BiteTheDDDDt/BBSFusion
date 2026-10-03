package dev.bbsfusion.core;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** The native login bridge is available only on the explicitly opened NGA login page. */
public final class NgaLoginPolicy {
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "bbs.nga.cn", "ngabbs.com", "www.nga.cn"
    ));

    private NgaLoginPolicy() { }

    public static boolean isTrustedLoginUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !HOSTS.contains(uri.getHost()) || !"/nuke.php".equals(uri.getRawPath())) {
                return false;
            }
            String query = uri.getRawQuery();
            if (query == null) {
                return false;
            }
            boolean login = false;
            boolean account = false;
            for (String parameter : query.split("&")) {
                if (parameter.startsWith("__lib=")) {
                    if (login || !"__lib=login".equals(parameter)) {
                        return false;
                    }
                    login = true;
                } else if (parameter.startsWith("__act=")) {
                    if (account || !"__act=account".equals(parameter)) {
                        return false;
                    }
                    account = true;
                }
            }
            return login && account;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static boolean validCredentials(String uid, String token) {
        return uid != null && uid.matches("[1-9][0-9]{0,19}")
                && token != null && token.length() <= 4096
                && token.matches("[\\x21\\x23-\\x2B\\x2D-\\x3A\\x3C-\\x5B\\x5D-\\x7E]+");
    }

    public static boolean isCurrentLoginPage(int expectedGeneration, int currentGeneration,
            String expectedUrl, String currentUrl) {
        return expectedGeneration == currentGeneration && expectedUrl != null
                && expectedUrl.equals(currentUrl) && isTrustedLoginUrl(currentUrl);
    }

    public static String origin(String value) {
        if (!isTrustedLoginUrl(value)) {
            return "";
        }
        return "https://" + URI.create(value).getHost();
    }
}
