package dev.bbsfusion.site;

import dev.bbsfusion.core.TopicDetail;
import org.jsoup.Jsoup;
import org.junit.Test;

import static org.junit.Assert.*;

public final class ForumHtmlParsersPollTest {
    private static final String URL = "https://stage1st.com/2b/thread-1-1-1.html";

    @Test
    public void attachesSiblingPollToItsPostWithoutDuplicatingTheBodyOrReplies() {
        String html = "<h1>选哪个方案？</h1>"
                + "<div class='plc' id='pid1'><div class='message'>"
                + "<div id='postmessage_1' class='postmessage'>说明正文</div>" + poll()
                + "</div></div>"
                + "<div class='plc' id='pid2'><div class='message'>回复</div></div>";

        TopicDetail detail = ForumHtmlParsers.extractTopic(Jsoup.parse(html, URL), URL);

        assertEquals(2, detail.posts.size());
        assertEquals("说明正文", detail.posts.get(0).content);
        assertEquals(1, detail.posts.get(0).polls.size());
        assertEquals("选哪个方案？", detail.posts.get(0).polls.get(0).question);
        assertEquals("方案甲", detail.posts.get(0).polls.get(0).options.get(0).text);
        assertTrue(detail.posts.get(1).polls.isEmpty());
    }

    @Test
    public void keepsPollOnlyOpeningPostBeforeReplies() {
        String html = "<div id='post_1'><div id='postmessage_1'></div>" + poll() + "</div>"
                + "<div id='post_2'><div id='postmessage_2'>回复</div></div>";

        TopicDetail detail = ForumHtmlParsers.extractTopic(Jsoup.parse(html, URL), URL);

        assertEquals(2, detail.posts.size());
        assertEquals("", detail.posts.get(0).content);
        assertEquals(1, detail.posts.get(0).polls.size());
        assertEquals("回复", detail.posts.get(1).content);
    }

    @Test
    public void removesAnEmbeddedPollFromPlainBodyText() {
        String html = "<div class='plc' id='pid1'><div class='message'>正文前"
                + poll() + "正文后</div></div>";

        TopicDetail detail = ForumHtmlParsers.extractTopic(Jsoup.parse(html, URL), URL);

        assertEquals(1, detail.posts.size());
        assertEquals(1, detail.posts.get(0).polls.size());
        assertTrue(detail.posts.get(0).content.contains("正文前"));
        assertTrue(detail.posts.get(0).content.contains("正文后"));
        assertFalse(detail.posts.get(0).content.contains("方案甲"));
        assertFalse(detail.posts.get(0).content.contains("单选投票"));
    }

    @Test
    public void contentNodeFallbackDoesNotAssignTheSamePollToOtherPosts() {
        String html = "<div id='postmessage_1'></div><script>var count = 1;</script>"
                + "<div class='bt pd2'>" + poll() + "</div>"
                + "<div id='postmessage_2'>下一楼</div>";

        TopicDetail detail = ForumHtmlParsers.extractTopic(Jsoup.parse(html, URL), URL);

        assertEquals(2, detail.posts.size());
        assertEquals(1, detail.posts.get(0).polls.size());
        assertTrue(detail.posts.get(1).polls.isEmpty());
    }

    @Test
    public void preservesReadableFallbackForAnUnrecognizedPollLayout() {
        String html = "<div class='plc' id='pid1'><div class='message'>"
                + "<form id='poll'><div>旧版投票：方案甲 3 票，方案乙 1 票</div></form>"
                + "</div></div>";

        TopicDetail detail = ForumHtmlParsers.extractTopic(Jsoup.parse(html, URL), URL);

        assertEquals(1, detail.posts.size());
        assertTrue(detail.posts.get(0).content.contains("方案甲 3 票"));
        assertTrue(detail.posts.get(0).polls.isEmpty());
    }

    private static String poll() {
        return "<form id='poll' name='poll'><div class='poll_txt'>单选投票</div>"
                + "<div class='poll_box'><p><label for='option_1'>1.方案甲</label></p>"
                + "<p><label for='option_2'>2.方案乙</label></p></div></form>";
    }
}
