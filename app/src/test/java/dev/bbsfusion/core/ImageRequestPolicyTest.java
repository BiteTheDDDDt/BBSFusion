package dev.bbsfusion.core;

import org.junit.Test;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class ImageRequestPolicyTest {
    @Test
    public void queryOnlyRedirectPreservesEncodedImagePath() throws Exception {
        assertEquals("https://images.test/a%20b/image.png?size=large%2Ffull",
                ImageRequestPolicy.redirect(
                        ImageRequestPolicy.parse("https://images.test/a%20b/image.png?size=small#old"),
                        "?size=large%2Ffull").toString());
    }

    @Test
    public void externalImagesOnlyLookUpTheirOwnCookiesIncludingRedirects() throws Exception {
        List<String> queried = new ArrayList<>();
        ImageRequestPolicy.CookieLookup lookup = url -> {
            queried.add(url);
            return url.startsWith("https://linux.do/") ? "session=private" : null;
        };
        URI original = ImageRequestPolicy.parse("https://linux.do/image.png");
        assertEquals("session=private", ImageRequestPolicy.cookiesFor(original, lookup));
        URI redirected = ImageRequestPolicy.redirect(original, "https://images.example/image.png");
        assertEquals("", ImageRequestPolicy.cookiesFor(redirected, lookup));
        assertEquals(Arrays.asList(original.toString(), redirected.toString()), queried);
    }

    @Test
    public void relativeRedirectKeepsTheOrigin() throws Exception {
        assertEquals("https://example.test/image.png", ImageRequestPolicy.redirect(
                ImageRequestPolicy.parse("https://example.test/a/image"), "../image.png").toString());
    }

    @Test public void crossOriginImagesDoNotReceivePrivateTopicPaths() throws Exception {
        String topic = "https://linux.do/t/private/123?secret=example#post2";
        assertEquals("https://linux.do/", ImageRequestPolicy.referrer(topic,
                ImageRequestPolicy.parse("https://images.example/a.png")));
        assertEquals("", ImageRequestPolicy.referrer(topic,
                ImageRequestPolicy.parse("http://images.example/a.png")));
        assertEquals("https://linux.do/t/private/123?secret=example", ImageRequestPolicy.referrer(topic,
                ImageRequestPolicy.parse("https://linux.do/a.png")));
    }

    @Test(expected = IOException.class)
    public void rejectsHttpsDowngrade() throws Exception {
        ImageRequestPolicy.redirect(ImageRequestPolicy.parse("https://example.test/a"), "http://example.test/b");
    }

    @Test(expected = IOException.class)
    public void rejectsNonHttpRedirects() throws Exception {
        ImageRequestPolicy.redirect(ImageRequestPolicy.parse("https://example.test/a"), "file:///private");
    }

    @Test(expected = IOException.class)
    public void rejectsEmbeddedCredentials() throws Exception {
        ImageRequestPolicy.parse("https://session:secret@example.test/image");
    }
}
