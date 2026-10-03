package dev.bbsfusion.core;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.*;

public final class SessionCookiePolicyTest {
    @Test
    public void deletionIncludesS1DirectoryAndDefaultCookiePaths() {
        Set<String> paths = SessionCookiePolicy.pathsFor("https://stage1st.com/2b/member.php?mod=logging");
        assertTrue(paths.contains("/"));
        assertTrue(paths.contains("/2b"));
        assertTrue(paths.contains("/2b/"));
        assertTrue(paths.contains("/2b/member.php"));
        assertFalse(paths.stream().anyMatch(path -> path.contains("?")));
        assertTrue(SessionCookiePolicy.pathsFor("https://v2ex.com/api/topics/show.json").contains("/api/topics"));
    }

    @Test
    public void cookieNamesAreDeduplicatedWithoutIncludingValues() {
        Set<String> names = SessionCookiePolicy.names("session=a=b; token=secret; session=other; invalid name=x");
        assertEquals(2, names.size());
        assertTrue(names.contains("session"));
        assertTrue(names.contains("token"));
    }

    @Test
    public void prefixedCookiesHaveValidDeletionAttributes() {
        String host = SessionCookiePolicy.expiredCookie("__Host-session", "/", "");
        assertTrue(host.contains("Secure"));
        assertTrue(host.contains("Max-Age=0"));
        assertFalse(host.contains("Domain="));
        assertEquals("", SessionCookiePolicy.expiredCookie("__Host-session", "/2b/", ""));
        assertEquals("", SessionCookiePolicy.expiredCookie("__Host-session", "/", "stage1st.com"));
        assertTrue(SessionCookiePolicy.expiredCookie("session", "/2b/", ".stage1st.com").contains("Path=/2b/"));
    }
}
