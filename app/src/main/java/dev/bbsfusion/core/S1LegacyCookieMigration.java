package dev.bbsfusion.core;

import java.util.ArrayList;
import java.util.List;

/** Repairs only the known S1 salt cookie scope left by older app versions. */
public final class S1LegacyCookieMigration {
    public static final String COOKIE_NAME = "B7Y9_2f85_saltkey";
    public static final String FORUM_URL = "https://stage1st.com/2b/";
    public static final String ROOT_URL = "https://stage1st.com/";
    public static final String IMAGE_URL = "https://img.stage1st.com/forum/";

    private S1LegacyCookieMigration() {
    }

    public interface CookieStore {
        String get(String url);

        /** Completes the write before returning. */
        void set(String url, String cookie);
    }

    public static boolean repair(CookieStore store) {
        if (!saltValues(store.get(ROOT_URL)).isEmpty()
                || !saltValues(store.get(IMAGE_URL)).isEmpty()) {
            return false;
        }
        List<String> source = saltValues(store.get(FORUM_URL));
        if (source.size() != 1 || !source.get(0).matches("[A-Za-z0-9]{8}")) {
            return false;
        }
        String salt = source.get(0);
        // v0.4 NetworkClient.saveCookies saved only name=value, losing the site's scope and flags.
        // Preserve the salt paired with auth; the lost original expiry cannot be reconstructed.
        store.set(ROOT_URL, COOKIE_NAME + "=" + salt
                + "; Domain=.stage1st.com; Path=/; Secure; HttpOnly");
        List<String> imageValues = saltValues(store.get(IMAGE_URL));
        if (imageValues.size() != 1 || !salt.equals(imageValues.get(0))) {
            return false;
        }
        store.set(FORUM_URL, COOKIE_NAME + "=; Path=/2b; Max-Age=0"
                + "; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly");
        return true;
    }

    private static List<String> saltValues(String header) {
        List<String> values = new ArrayList<>();
        if (header == null) {
            return values;
        }
        for (String part : header.split(";")) {
            int equals = part.indexOf('=');
            String name = (equals < 0 ? part : part.substring(0, equals)).trim();
            if (COOKIE_NAME.equals(name)) {
                values.add(equals < 0 ? "" : part.substring(equals + 1).trim());
            }
        }
        return values;
    }
}
