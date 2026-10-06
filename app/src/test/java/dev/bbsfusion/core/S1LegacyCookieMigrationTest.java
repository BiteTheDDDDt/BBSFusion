package dev.bbsfusion.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public final class S1LegacyCookieMigrationTest {
    private static final String NAME = S1LegacyCookieMigration.COOKIE_NAME;
    private static final String FORUM = S1LegacyCookieMigration.FORUM_URL;
    private static final String ROOT = S1LegacyCookieMigration.ROOT_URL;
    private static final String IMAGE = S1LegacyCookieMigration.IMAGE_URL;
    private static final String SALT = "aB3dE6gH";

    @Test
    public void restoresOriginalSaltWithoutChangingAuthOrExtendingExpiry() {
        TestStore store = legacyStore(NAME + "=" + SALT + "; unrelated=keep");

        assertTrue(S1LegacyCookieMigration.repair(store));

        assertEquals(2, store.writes.size());
        assertEquals(ROOT, store.writes.get(0).url);
        assertEquals(NAME + "=" + SALT + "; Domain=.stage1st.com; Path=/; Secure; HttpOnly",
                store.writes.get(0).cookie);
        assertEquals(FORUM, store.writes.get(1).url);
        assertEquals(NAME + "=; Path=/2b; Max-Age=0"
                        + "; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly",
                store.writes.get(1).cookie);
        assertEquals("B7Y9_2f85_auth=paired-auth; " + NAME + "=" + SALT,
                store.headers.get(IMAGE));
        assertEquals("other_session=keep; " + NAME + "=" + SALT, store.headers.get(ROOT));
        assertTrue(store.verifiedBeforeDeletion);
        assertFalse(S1LegacyCookieMigration.repair(store));
        assertEquals(2, store.writes.size());
    }

    @Test
    public void neverOverwritesAnExistingRootOrImageSaltEvenIfEmpty() {
        for (String url : new String[] {ROOT, IMAGE}) {
            for (String value : new String[] {"", "different", SALT}) {
                TestStore store = legacyStore(NAME + "=" + SALT);
                store.headers.put(url, NAME + "=" + value);

                assertFalse(S1LegacyCookieMigration.repair(store));
                assertTrue(store.writes.isEmpty());
            }
        }
    }

    @Test
    public void refusesDuplicateSourceSaltsEvenWhenTheirValuesMatch() {
        for (String value : new String[] {SALT, "zY7xW4vU", ""}) {
            TestStore store = legacyStore(NAME + "=" + SALT + "; " + NAME + "=" + value);

            assertFalse(S1LegacyCookieMigration.repair(store));
            assertTrue(store.writes.isEmpty());
        }
    }

    @Test
    public void onlyAcceptsTheExactCookieNameAndEightAsciiAlphanumericCharacters() {
        for (String value : new String[] {"", "short", "123456789", "abcd-123", "abcd_123",
                "abcd=123", "abcd%123", "abcd\n123", "abcd\u00e9123"}) {
            TestStore store = legacyStore(NAME + "=" + value);
            assertFalse(S1LegacyCookieMigration.repair(store));
            assertTrue(store.writes.isEmpty());
        }
        for (String header : new String[] {null, "", "other_saltkey=" + SALT,
                "b7y9_2f85_saltkey=" + SALT, "B7Y9_2f85_auth=" + SALT}) {
            TestStore store = legacyStore(header);
            assertFalse(S1LegacyCookieMigration.repair(store));
            assertTrue(store.writes.isEmpty());
        }
    }

    @Test
    public void neverDeletesTheLegacyCookieWhenTheWriteIsNotVisibleAtTheImageUrl() {
        TestStore store = legacyStore(NAME + "=" + SALT);
        store.acceptWrite = false;

        assertFalse(S1LegacyCookieMigration.repair(store));

        assertEquals(1, store.writes.size());
        assertEquals(ROOT, store.writes.get(0).url);
        assertEquals(NAME + "=" + SALT, store.headers.get(FORUM));
    }

    @Test
    public void requiresOneExactlyMatchingSaltWhenVerifyingTheImageScope() {
        for (String header : new String[] {NAME + "=zY7xW4vU",
                NAME + "=" + SALT + "; " + NAME + "=" + SALT}) {
            TestStore store = legacyStore(NAME + "=" + SALT);
            store.imageAfterWrite = header;

            assertFalse(S1LegacyCookieMigration.repair(store));
            assertEquals(1, store.writes.size());
            assertEquals(NAME + "=" + SALT, store.headers.get(FORUM));
        }
    }

    private static TestStore legacyStore(String forumHeader) {
        TestStore store = new TestStore();
        store.headers.put(FORUM, forumHeader);
        store.headers.put(ROOT, "other_session=keep");
        store.headers.put(IMAGE, "B7Y9_2f85_auth=paired-auth");
        return store;
    }

    private static final class Write {
        final String url;
        final String cookie;

        Write(String url, String cookie) {
            this.url = url;
            this.cookie = cookie;
        }
    }

    private static final class TestStore implements S1LegacyCookieMigration.CookieStore {
        final Map<String, String> headers = new HashMap<>();
        final List<Write> writes = new ArrayList<>();
        boolean acceptWrite = true;
        String imageAfterWrite;
        boolean readImageAfterWrite;
        boolean verifiedBeforeDeletion;

        @Override
        public String get(String url) {
            if (IMAGE.equals(url) && !writes.isEmpty()) {
                readImageAfterWrite = true;
            }
            return headers.get(url);
        }

        @Override
        public void set(String url, String cookie) {
            writes.add(new Write(url, cookie));
            if (ROOT.equals(url) && acceptWrite) {
                String pair = cookie.substring(0, cookie.indexOf(';'));
                headers.put(ROOT, headers.get(ROOT) + "; " + pair);
                headers.put(IMAGE, imageAfterWrite == null
                        ? headers.get(IMAGE) + "; " + pair : imageAfterWrite);
            } else if (FORUM.equals(url)) {
                verifiedBeforeDeletion = readImageAfterWrite;
            }
        }
    }
}
