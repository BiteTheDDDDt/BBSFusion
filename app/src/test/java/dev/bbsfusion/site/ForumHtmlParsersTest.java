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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ForumHtmlParsersTest {
    @Test
    public void malformedDatesAreUnknownInsteadOfFailingTheEntirePage() {
        assertEquals(0L, ForumHtmlParsers.parseTimeMillis("2026-13-40 25:61"));
        assertEquals(0L, ForumHtmlParsers.parseTimeMillis("今天 25:99"));
        assertEquals(0L, ForumHtmlParsers.parseTimeMillis("999999999999999999999999秒前"));
    }

    @Test
    public void retainsEveryStructuredPostWhenAPageHasMoreThanForty() {
        StringBuilder html = new StringBuilder();
        for (int i = 1; i <= 65; i++) {
            html.append(structuredPost(String.valueOf(i), "user" + i, "回复 " + i));
        }
        TopicDetail detail = ForumHtmlParsers.extractTopic(
                Jsoup.parse(html.toString()), "https://stage1st.com/2b/thread-1-1-1.html"
        );
        assertEquals(65, detail.posts.size());
        assertEquals("user65", detail.posts.get(64).author);
        assertEquals("回复 65", detail.posts.get(64).content);
    }

    @Test
    public void retainsEveryIdentifiedContentNodeWhenAPageHasMoreThanForty() {
        StringBuilder html = new StringBuilder();
        for (int i = 1; i <= 65; i++) {
            html.append("<div id='postmessage_").append(i).append("'>回复 ")
                    .append(i).append("</div>");
        }
        TopicDetail detail = ForumHtmlParsers.extractTopic(
                Jsoup.parse(html.toString()), "https://stage1st.com/2b/thread-1-1-1.html"
        );
        assertEquals(65, detail.posts.size());
        assertEquals("回复 65", detail.posts.get(64).content);
    }

    @Test
    public void keepsShortRepliesAndStandaloneEmoticons() {
        String html = structuredPost("1", "alice", "完整正文，这是一个普通帖子。")
                + structuredPost("2", "bob", "谢谢")
                + structuredPost("3", "carol",
                "<img src='static/image/smiley/default/lol.gif' alt='[笑]'>");
        TopicDetail detail = ForumHtmlParsers.extractTopic(
                Jsoup.parse(html, "https://stage1st.com/2b/"),
                "https://stage1st.com/2b/thread-1-1-1.html"
        );

        assertEquals(3, detail.posts.size());
        assertEquals("bob", detail.posts.get(1).author);
        assertEquals("谢谢", detail.posts.get(1).content);
        assertEquals(1, detail.posts.get(2).inlineImages.size());
    }

    @Test
    public void keepsIdenticalRepliesFromDifferentPostsWithoutDuplicatingNestedContainers() {
        String html = "<div id='post_1'><div class='post'>"
                + "<span class='author'>alice</span><div id='postmessage_1'>完全相同的回复内容。</div>"
                + "</div></div>" + structuredPost("2", "bob", "完全相同的回复内容。");
        TopicDetail detail = ForumHtmlParsers.extractTopic(
                Jsoup.parse(html), "https://stage1st.com/2b/thread-1-1-1.html"
        );

        assertEquals(2, detail.posts.size());
        assertEquals("alice", detail.posts.get(0).author);
        assertEquals("bob", detail.posts.get(1).author);
        assertEquals(detail.posts.get(0).content, detail.posts.get(1).content);
    }

    @Test
    public void keepsShortPostsWhenOnlyContentNodesAreAvailable() {
        TopicDetail detail = ForumHtmlParsers.extractTopic(
                Jsoup.parse("<div id='postmessage_1'>谢谢</div><div id='postmessage_2'>谢谢</div>"),
                "https://stage1st.com/2b/thread-1-1-1.html"
        );
        assertEquals(2, detail.posts.size());
    }

    @Test
    public void ignoresNumericCitationsAndOtherTopicPaginationLinks() {
        String html = structuredPost("1", "alice", "正文")
                + "<a href='https://example.test/citation'>2</a>"
                + "<div class='pg'><a href='thread-999-2-1.html'>2</a></div>"
                + "<a href='https://other.test/thread-1-2-1.html'>下一页</a>";
        assertFalse(ForumHtmlParsers.extractTopic(
                Jsoup.parse(html, "https://stage1st.com/2b/"),
                "https://stage1st.com/2b/thread-1-1-1.html"
        ).hasMore);
    }

    @Test
    public void recognizesSameTopicPaginationForDiscuzAndNga() {
        assertTrue(ForumHtmlParsers.extractTopic(
                Jsoup.parse("<div class='pg'><a href='thread-1-2-1.html'>2</a></div>",
                        "https://stage1st.com/2b/"),
                "https://stage1st.com/2b/thread-1-1-1.html", 1
        ).hasMore);
        assertTrue(ForumHtmlParsers.extractTopic(
                Jsoup.parse("<div class='pages'><a href='read.php?tid=1&amp;page=3'>3</a></div>",
                        "https://bbs.nga.cn/"),
                "https://bbs.nga.cn/read.php?tid=1&page=2", 2
        ).hasMore);
        assertTrue(ForumHtmlParsers.extractTopic(
                Jsoup.parse("<a rel='next' href='read.php?tid=1&amp;page=2'>更多</a>",
                        "https://bbs.nga.cn/"),
                "https://bbs.nga.cn/read.php?tid=1", 1
        ).hasMore);
    }

    @Test
    public void doesNotTreatCurrentPageOrSkippedPagesAsNext() {
        assertFalse(ForumHtmlParsers.extractTopic(
                Jsoup.parse("<div class='pg'><a href='thread-1-2-1.html'>2</a>"
                                + "<a href='thread-1-4-1.html'>4</a></div>",
                        "https://stage1st.com/2b/"),
                "https://stage1st.com/2b/thread-1-2-1.html", 2
        ).hasMore);
    }

    private static String structuredPost(String id, String author, String content) {
        return "<div id='post_" + id + "'><span class='author'>" + author
                + "</span><div id='postmessage_" + id + "'>" + content + "</div></div>";
    }

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
    public void extractsS1DesktopReplyCount() {
        Document document = Jsoup.parse(
                "<table><tbody id=\"normalthread_123\"><tr>" +
                        "<th><a class=\"xst\" href=\"thread-123-1-1.html\">一个桌面版帖子</a></th>" +
                        "<td class=\"num\"><a>42</a><em>1000</em></td>" +
                        "<td class=\"by\"><em><a>2026-6-8 17:50</a></em></td>" +
                        "</tr></tbody></table>",
                "https://stage1st.com/2b/"
        );

        List<TopicSummary> topics = ForumHtmlParsers.extractTopics(
                document,
                "s1",
                "https://stage1st.com/2b/forum-157-1.html",
                "S1"
        );

        assertEquals(1, topics.size());
        assertEquals("S1 · 2026-6-8 17:50 · 42 回复", topics.get(0).meta);
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
        assertEquals("发表于 2026-6-8 17:50", detail.posts.get(0).postedMeta);
        assertEquals("这是一段足够长的帖子正文内容。", detail.posts.get(0).content);
    }

    @Test
    public void ignoresNonTimeDiscuzAuthorActionsWhenExtractingPostTime() {
        Document document = Jsoup.parse(
                "<div id=\"post_1\"><table id=\"pid1\"><tr>" +
                        "<td><div class=\"authi\"><em>只看该作者</em><em id=\"authorposton1\">发表于 2026-6-8 17:50</em></div>" +
                        "<div class=\"pstatus\">本帖最后由 alice 于 2026-6-8 18:10 编辑</div>" +
                        "<td class=\"t_f\" id=\"postmessage_1\">这是一段足够长的帖子正文内容。</td></td>" +
                        "</tr></table></div>",
                "https://stage1st.com/2b/"
        );

        TopicDetail detail = ForumHtmlParsers.extractTopic(document, "https://stage1st.com/2b/thread-1-1-1.html");
        Post post = detail.posts.get(0);

        assertEquals("发表于 2026-6-8 17:50 · 编辑 2026-6-8 18:10", post.meta);
        assertEquals("发表于 2026-6-8 17:50", post.postedMeta);
        assertEquals("编辑 2026-6-8 18:10", post.editedMeta);
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
        assertEquals(1, detail.posts.get(0).inlineImages.size());
        assertEquals(
                "https://stage1st.com/2b/static/image/smiley/default/lol.gif",
                detail.posts.get(0).inlineImages.get(0).sourceUrl
        );
    }

    @Test
    public void preservesDiscuzPostLineBreaks() {
        Document document = Jsoup.parse(
                "<div id=\"post_1\"><table id=\"pid1\"><tr><td>" +
                        "<div class=\"authi\"><a class=\"xw1\">alice</a></div>" +
                        "<td class=\"t_f\" id=\"postmessage_1\">" +
                        "第一行<br/>第二行<div>第三段</div><p>第四段" +
                        "<img src=\"static/image/smiley/default/lol.gif\" alt=\"[笑]\" />" +
                        "</p></td></td></tr></table></div>",
                "https://stage1st.com/2b/"
        );

        TopicDetail detail = ForumHtmlParsers.extractTopic(document, "https://stage1st.com/2b/thread-1-1-1.html");

        assertEquals("第一行\n第二行\n第三段\n第四段 [笑]", detail.posts.get(0).content);
    }

    @Test
    public void collapsesDiscuzExtraBlankLines() {
        Document document = Jsoup.parse(
                "<div class=\"postrow\">" +
                        "<span class=\"poster\">alice</span>" +
                        "<div class=\"postcontent\">" +
                        "第一行<br/><br/>第二行<div><p>第三段内容足够长</p></div>" +
                        "</div></div>",
                "https://stage1st.com/2b/"
        );

        TopicDetail detail = ForumHtmlParsers.extractTopic(document, "https://stage1st.com/2b/thread-1-1-1.html");

        assertEquals("第一行\n第二行\n第三段内容足够长", detail.posts.get(0).content);
    }

    @Test
    public void separatesDiscuzQuoteFromPostText() {
        Document document = Jsoup.parse(
                "<div id=\"post_1\"><table id=\"pid1\"><tr><td>" +
                        "<div class=\"authi\"><a class=\"xw1\">alice</a></div>" +
                        "<td class=\"t_f\" id=\"postmessage_1\">" +
                        "<div class=\"quote\"><blockquote>bob 发表于 2026-6-8 17:50 原文内容</blockquote></div>" +
                        "回复正文，这是一段足够长的内容。</td></td></tr></table></div>",
                "https://stage1st.com/2b/"
        );

        TopicDetail detail = ForumHtmlParsers.extractTopic(document, "https://stage1st.com/2b/thread-1-1-1.html");

        assertEquals("引用：bob 发表于 2026-6-8 17:50 原文内容", detail.posts.get(0).replyContext);
        assertEquals("回复正文，这是一段足够长的内容。", detail.posts.get(0).content);
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
