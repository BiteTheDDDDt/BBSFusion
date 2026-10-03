package dev.bbsfusion.core;

import org.junit.Test;
import java.net.URI;
import static org.junit.Assert.assertEquals;

public final class HttpUrlResolverTest {
    private final URI base = URI.create("https://forum.test/a%20b/topic?old=1#old");

    @Test
    public void resolvesEmptyQueryAndFragmentReferencesWithoutLosingTheResource() {
        assertEquals("https://forum.test/a%20b/topic?old=1", HttpUrlResolver.resolve(base, "").toString());
        assertEquals("https://forum.test/a%20b/topic?", HttpUrlResolver.resolve(base, "?").toString());
        assertEquals("https://forum.test/a%20b/topic?old=1#new", HttpUrlResolver.resolve(base, "#new").toString());
    }

    @Test
    public void stillResolvesRelativePathsAndOtherOrigins() {
        assertEquals("https://forum.test/next", HttpUrlResolver.resolve(base, "../next").toString());
        assertEquals("https://other.test/path", HttpUrlResolver.resolve(base, "//other.test/path").toString());
    }
}
