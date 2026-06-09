package dev.bbsfusion.site;

import dev.bbsfusion.core.Post;
import dev.bbsfusion.core.TopicDetail;
import dev.bbsfusion.core.TopicSummary;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ForumHtmlParsersTest {
    @Test
    public void extractsS1TopicLinks() {
        Document document = Jsoup.parse(
                "<a href=\"forum.php?mod=viewthread&tid=123\">一个测试帖子</a>" +
                        "<a href=\"forum.php?mod=viewthread&tid=123\">重复标题</a>" +
                        "<a href=\"forum.php?mod=forumdisplay&fid=75\">版块</a>",
                "https://stage1st.com/2b/"
        );

        List<TopicSummary> topics = ForumHtmlParsers.extractTopics(
                document,
                "s1",
                "https://stage1st.com/2b/forum.php?mod=forumdisplay&fid=75",
                "S1"
        );

        assertEquals(1, topics.size());
        assertEquals("一个测试帖子", topics.get(0).title);
        assertTrue(topics.get(0).url.contains("tid=123"));
    }

    @Test
    public void extractsNgaTopicLinks() {
        Document document = Jsoup.parse(
                "<a href=\"read.php?tid=456\">NGA 测试帖子</a>" +
                        "<a href=\"thread.php?fid=-7\">版块</a>",
                "https://bbs.nga.cn/"
        );

        List<TopicSummary> topics = ForumHtmlParsers.extractTopics(
                document,
                "nga",
                "https://bbs.nga.cn/thread.php?fid=-7",
                "NGA"
        );

        assertEquals(1, topics.size());
        assertEquals("NGA 测试帖子", topics.get(0).title);
        assertTrue(topics.get(0).url.contains("tid=456"));
    }

    @Test
    public void parsesForumTimeAsShanghaiTime() {
        long expected = LocalDateTime.of(2026, 6, 8, 17, 50)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .toEpochMilli();

        assertEquals(expected, ForumHtmlParsers.parseTimeMillis("2026-6-8 17:50"));
    }

    @Test
    public void extractsDiscuzPostAuthors() {
        Document document = Jsoup.parse(
                "<div id=\"post_1\"><table id=\"pid1\"><tr>" +
                        "<td class=\"pls\"><div class=\"authi\"><a class=\"xw1\" href=\"space-uid-1.html\">alice</a></div></td>" +
                        "<td><div class=\"authi\"><em>发表于 2026-6-8 17:50</em></div>" +
                        "<td class=\"t_f\" id=\"postmessage_1\">这是一段足够长的帖子正文内容。</td></td>" +
                        "</tr></table></div>",
                "https://stage1st.com/2b/"
        );

        TopicDetail detail = ForumHtmlParsers.extractTopic(document, "https://stage1st.com/2b/thread-1-1-1.html");

        assertEquals(1, detail.posts.size());
        assertEquals("alice", detail.posts.get(0).author);
        assertEquals("发表于 2026-6-8 17:50", detail.posts.get(0).meta);
        assertEquals("这是一段足够长的帖子正文内容。", detail.posts.get(0).content);
    }

    @Test
    public void extractsDiscuzMobilePostAuthors() {
        Document document = Jsoup.parse(
                "<div class=\"plc cl\" id=\"pid69622648\">" +
                        "<div class=\"avatar\"><img src=\"avatar.jpg\" /></div>" +
                        "<div class=\"display pi pione\">" +
                        "<ul class=\"authi\"><li class=\"mtit\"><span class=\"z\">" +
                        "<a href=\"home.php?mod=space&amp;uid=464256&amp;mobile=2\">活久见</a>" +
                        "</span><em>发表于 2026-6-8 18:20</em></li></ul>" +
                        "<div class=\"message\">这是一段来自手机页的足够长的帖子正文内容。" +
                        "<img file=\"attachments/month_0608/sample.jpg\" src=\"static/image/common/none.gif\" />" +
                        "</div>" +
                        "</div></div>",
                "https://stage1st.com/2b/"
        );

        TopicDetail detail = ForumHtmlParsers.extractTopic(document, "https://stage1st.com/2b/thread-1-1-1.html");

        assertEquals(1, detail.posts.size());
        assertEquals("活久见", detail.posts.get(0).author);
        assertEquals("https://stage1st.com/2b/avatar.jpg", detail.posts.get(0).avatarUrl);
        assertEquals("发表于 2026-6-8 18:20", detail.posts.get(0).meta);
        assertEquals("这是一段来自手机页的足够长的帖子正文内容。", detail.posts.get(0).content);
        assertEquals("https://stage1st.com/2b/attachments/month_0608/sample.jpg", detail.posts.get(0).imageUrls.get(0));
    }

    @Test
    public void keepsDiscuzSmileyLabelsInPostText() {
        Document document = Jsoup.parse(
                "<div id=\"post_1\"><table id=\"pid1\"><tr><td>" +
                        "<div class=\"authi\"><a class=\"xw1\">alice</a></div>" +
                        "<td class=\"t_f\" id=\"postmessage_1\">正文前" +
                        "<img src=\"static/image/smiley/default/lol.gif\" alt=\"[笑]\" />" +
                        "正文后，这是一段足够长的内容。</td></td></tr></table></div>",
                "https://stage1st.com/2b/"
        );

        TopicDetail detail = ForumHtmlParsers.extractTopic(document, "https://stage1st.com/2b/thread-1-1-1.html");

        assertEquals("正文前 [笑] 正文后，这是一段足够长的内容。", detail.posts.get(0).content);
        assertEquals(0, detail.posts.get(0).imageUrls.size());
    }

    @Test
    public void extractsGenericPostAuthors() {
        Document document = Jsoup.parse(
                "<div class=\"postrow\">" +
                        "<span class=\"poster\">bob</span>" +
                        "<div class=\"postcontent\">这是另一段足够长的帖子正文内容。</div>" +
                        "</div>",
                "https://bbs.nga.cn/"
        );

        TopicDetail detail = ForumHtmlParsers.extractTopic(document, "https://bbs.nga.cn/read.php?tid=1");
        Post post = detail.posts.get(0);

        assertEquals("bob", post.author);
        assertEquals("这是另一段足够长的帖子正文内容。", post.content);
    }
}
