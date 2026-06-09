package dev.bbsfusion.site;

import dev.bbsfusion.core.BoardDefinition;
import dev.bbsfusion.core.Post;
import dev.bbsfusion.core.TopicDetail;
import dev.bbsfusion.core.TopicSummary;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class LinuxDoConnectorTest {
    @Test
    public void extractsTopicsFromDiscourseJson() throws Exception {
        JSONObject root = new JSONObject(
                "{"
                        + "\"category_list\":{\"categories\":[{\"id\":4,\"name\":\"开发调优\"}]},"
                        + "\"topic_list\":{\"topics\":[{"
                        + "\"id\":201,"
                        + "\"slug\":\"sample-topic\","
                        + "\"title\":\"一个 Linux.do 测试主题\","
                        + "\"category_id\":4,"
                        + "\"bumped_at\":\"2026-06-08T12:00:00.000Z\","
                        + "\"reply_count\":3"
                        + "}]}"
                        + "}"
        );
        BoardDefinition board = new BoardDefinition(
                "linuxdo",
                "latest",
                "最新",
                "https://linux.do/latest",
                "https://linux.do/",
                "Linux.do 最新"
        );

        List<TopicSummary> topics = LinuxDoConnector.parseTopicsFromJson(root, board);

        assertEquals(1, topics.size());
        assertEquals("一个 Linux.do 测试主题", topics.get(0).title);
        assertEquals("https://linux.do/t/sample-topic/201", topics.get(0).url);
        assertTrue(topics.get(0).meta.contains("Linux.do 开发调优"));
        assertTrue(topics.get(0).sortTimeMillis > 0L);
    }

    @Test
    public void extractsBoardsFromCategoriesJson() throws Exception {
        JSONObject root = new JSONObject(
                "{"
                        + "\"category_list\":{\"categories\":[{"
                        + "\"id\":4,"
                        + "\"slug\":\"develop\","
                        + "\"name\":\"开发调优\","
                        + "\"subcategory_list\":[{\"id\":14,\"slug\":\"resource\",\"name\":\"资源荟萃\"}]"
                        + "}]}"
                        + "}"
        );

        List<BoardDefinition> boards = LinuxDoConnector.parseBoardsFromJson(root);

        assertEquals(2, boards.size());
        assertEquals("c:develop:4", boards.get(0).boardId);
        assertEquals("c:resource:14", boards.get(1).boardId);
    }

    @Test
    public void extractsTopicPostsFromDiscourseJson() throws Exception {
        JSONObject root = new JSONObject(
                "{"
                        + "\"title\":\"帖子标题\","
                        + "\"post_stream\":{\"stream\":[101,102],\"posts\":[{"
                        + "\"display_username\":\"alice\","
                        + "\"avatar_template\":\"/user_avatar/linux.do/alice/{size}/1_2.png\","
                        + "\"created_at\":\"2026-06-08T07:10:00.000Z\","
                        + "\"updated_at\":\"2026-06-08T08:10:00.000Z\","
                        + "\"reply_to_post_number\":2,"
                        + "\"cooked\":\"<p>正文</p><p><img src=\\\"/uploads/default/original/1/sample.png\\\"></p>\""
                        + "}]}"
                        + "}"
        );

        TopicDetail detail = LinuxDoConnector.parseTopicFromJson(root, "https://linux.do/t/topic/1");
        Post post = detail.posts.get(0);

        assertEquals("帖子标题", detail.title);
        assertEquals("alice", post.author);
        assertEquals("https://linux.do/user_avatar/linux.do/alice/96/1_2.png", post.avatarUrl);
        assertEquals("发表于 2026-6-8 15:10 · 编辑 2026-6-8 16:10", post.meta);
        assertEquals("回复 #2", post.replyContext);
        assertEquals("正文", post.content);
        assertEquals("https://linux.do/uploads/default/original/1/sample.png", post.imageUrls.get(0));
    }

    @Test
    public void sortsDiscourseJsonPostsByPostNumber() throws Exception {
        JSONObject root = new JSONObject(
                "{"
                        + "\"title\":\"帖子标题\","
                        + "\"post_stream\":{\"stream\":[101,102],\"posts\":["
                        + "{"
                        + "\"id\":102,"
                        + "\"post_number\":2,"
                        + "\"display_username\":\"bob\","
                        + "\"cooked\":\"<p>二楼</p>\""
                        + "},"
                        + "{"
                        + "\"id\":101,"
                        + "\"post_number\":1,"
                        + "\"display_username\":\"alice\","
                        + "\"cooked\":\"<p>一楼</p>\""
                        + "}"
                        + "]}"
                        + "}"
        );

        TopicDetail detail = LinuxDoConnector.parseTopicFromJson(root, "https://linux.do/t/topic/1");

        assertEquals("alice", detail.posts.get(0).author);
        assertEquals("一楼", detail.posts.get(0).content);
        assertEquals("bob", detail.posts.get(1).author);
        assertEquals("二楼", detail.posts.get(1).content);
    }

    @Test
    public void buildsTopicJsonUrlWithSlugBeforeIdFallback() {
        List<String> urls = LinuxDoConnector.jsonUrlsForTopic(
                "https://linux.do/t/sample-topic/201?u=alice#post_3"
        );

        assertEquals(2, urls.size());
        assertEquals("https://linux.do/t/sample-topic/201.json", urls.get(0));
        assertEquals("https://linux.do/t/201.json", urls.get(1));
    }

    @Test
    public void keepsExistingTopicJsonUrl() {
        List<String> urls = LinuxDoConnector.jsonUrlsForTopic("https://linux.do/t/sample-topic/201.json");

        assertEquals(2, urls.size());
        assertEquals("https://linux.do/t/sample-topic/201.json", urls.get(0));
        assertEquals("https://linux.do/t/201.json", urls.get(1));
    }

    @Test
    public void buildsTopicRssUrlWithSlugBeforeIdFallback() {
        List<String> urls = LinuxDoConnector.rssUrlsForTopic(
                "https://linux.do/t/sample-topic/201?u=alice#post_3"
        );

        assertEquals(2, urls.size());
        assertEquals("https://linux.do/t/sample-topic/201.rss", urls.get(0));
        assertEquals("https://linux.do/t/201.rss", urls.get(1));
    }

    @Test
    public void buildsTopicJsonRequestUrlWithDiscourseQuery() {
        assertEquals(
                "https://linux.do/t/sample-topic/201.json?track_visit=false&forceLoad=true",
                LinuxDoConnector.topicJsonRequestUrl("https://linux.do/t/sample-topic/201.json")
        );
    }

    @Test
    public void buildsTopicPostsPaginationUrl() {
        assertEquals(
                "https://linux.do/t/201/posts.json?include_suggested=false&post_ids%5B%5D=101&post_ids%5B%5D=102",
                LinuxDoConnector.postsJsonUrlForTopic("201", List.of("101", "102"))
        );
    }

    @Test
    public void extractsTopicPostsFromRssFallback() {
        Document document = Jsoup.parse(
                "<rss><channel>"
                        + "<title>RSS 帖子标题</title>"
                        + "<link>https://linux.do/t/sample-topic/201</link>"
                        + "<description><![CDATA[<p>楼主正文</p>]]></description>"
                        + "<item>"
                        + "<title>RSS 帖子标题</title>"
                        + "<dc:creator><![CDATA[bob]]></dc:creator>"
                        + "<description><![CDATA[<p>回复正文</p>"
                        + "<p><a href=\"https://linux.do/t/sample-topic/201/2\">阅读完整话题</a></p>]]></description>"
                        + "<pubDate>Tue, 09 Jun 2026 06:16:45 +0000</pubDate>"
                        + "</item>"
                        + "</channel></rss>",
                "https://linux.do/t/sample-topic/201.rss",
                Parser.xmlParser()
        );

        TopicDetail detail = LinuxDoConnector.parseTopicFromRss(document, "https://linux.do/t/sample-topic/201");

        assertEquals("RSS 帖子标题", detail.title);
        assertEquals(2, detail.posts.size());
        assertEquals("主题", detail.posts.get(0).author);
        assertEquals("楼主正文", detail.posts.get(0).content);
        assertEquals("bob", detail.posts.get(1).author);
        assertEquals("回复正文", detail.posts.get(1).content);
        assertTrue(detail.posts.get(1).meta.startsWith("发表于 "));
    }

    @Test
    public void sortsRssFallbackRepliesOldestFirst() {
        Document document = Jsoup.parse(
                "<rss><channel>"
                        + "<title>RSS 帖子标题</title>"
                        + "<description><![CDATA[<p>楼主正文</p>]]></description>"
                        + "<item>"
                        + "<dc:creator><![CDATA[newer]]></dc:creator>"
                        + "<description><![CDATA[<p>较新的回复</p>]]></description>"
                        + "<pubDate>Tue, 09 Jun 2026 07:16:45 +0000</pubDate>"
                        + "</item>"
                        + "<item>"
                        + "<dc:creator><![CDATA[older]]></dc:creator>"
                        + "<description><![CDATA[<p>较旧的回复</p>]]></description>"
                        + "<pubDate>Tue, 09 Jun 2026 06:16:45 +0000</pubDate>"
                        + "</item>"
                        + "</channel></rss>",
                "https://linux.do/t/sample-topic/201.rss",
                Parser.xmlParser()
        );

        TopicDetail detail = LinuxDoConnector.parseTopicFromRss(document, "https://linux.do/t/sample-topic/201");

        assertEquals("主题", detail.posts.get(0).author);
        assertEquals("older", detail.posts.get(1).author);
        assertEquals("较旧的回复", detail.posts.get(1).content);
        assertEquals("newer", detail.posts.get(2).author);
        assertEquals("较新的回复", detail.posts.get(2).content);
    }

    @Test
    public void extractsTopicsFromCrawlerHtml() {
        Document document = Jsoup.parse(
                "<a href=\"/t/sample-topic/201\">一个 HTML 主题</a>"
                        + "<a href=\"/t/sample-topic/201\">重复</a>"
                        + "<a href=\"/c/develop/4\">开发调优</a>",
                "https://linux.do/latest"
        );
        BoardDefinition board = new BoardDefinition(
                "linuxdo",
                "latest",
                "最新",
                "https://linux.do/latest",
                "https://linux.do/",
                "Linux.do 最新"
        );

        List<TopicSummary> topics = LinuxDoConnector.parseTopicsFromHtml(document, board);

        assertEquals(1, topics.size());
        assertEquals("一个 HTML 主题", topics.get(0).title);
        assertEquals("https://linux.do/t/sample-topic/201", topics.get(0).url);
    }

    @Test
    public void extractsTopicsFromRssFeed() {
        Document document = Jsoup.parse(
                "<rss><channel><item>"
                        + "<title>一个 RSS 主题</title>"
                        + "<link>https://linux.do/t/topic/2349999</link>"
                        + "<category>前沿快讯</category>"
                        + "<description><![CDATA[<p>正文</p><p><small>3 个帖子 - 2 位参与者</small></p>]]></description>"
                        + "<pubDate>Tue, 09 Jun 2026 06:16:45 +0000</pubDate>"
                        + "</item></channel></rss>",
                "https://linux.do/latest.rss",
                Parser.xmlParser()
        );
        BoardDefinition board = new BoardDefinition(
                "linuxdo",
                "latest",
                "最新",
                "https://linux.do/latest",
                "https://linux.do/",
                "Linux.do 最新"
        );

        List<TopicSummary> topics = LinuxDoConnector.parseTopicsFromRss(document, board);

        assertEquals(1, topics.size());
        assertEquals("一个 RSS 主题", topics.get(0).title);
        assertEquals("https://linux.do/t/topic/2349999", topics.get(0).url);
        assertTrue(topics.get(0).meta.contains("Linux.do 前沿快讯"));
        assertTrue(topics.get(0).meta.contains("2 回复"));
        assertTrue(topics.get(0).sortTimeMillis > 0L);
    }

    @Test
    public void keepsInlineEmojiLabelsInCookedHtml() {
        LinuxDoConnector.ParsedContent content = LinuxDoConnector.parsedCooked(
                "<p>正文<img class=\"emoji\" src=\"/images/emoji/twitter/smile.png\" title=\":smile:\">后续</p>"
        );

        assertEquals("正文 :smile: 后续", content.text);
        assertEquals(0, content.imageUrls.size());
        assertEquals(1, content.inlineImages.size());
        assertEquals("https://linux.do/images/emoji/twitter/smile.png", content.inlineImages.get(0).sourceUrl);
    }

    @Test
    public void preservesCookedParagraphBreaks() {
        LinuxDoConnector.ParsedContent content = LinuxDoConnector.parsedCooked(
                "<p>第一段</p><p>第二段<br>第三行</p>"
        );

        assertEquals("第一段\n第二段\n第三行", content.text);
    }

    @Test
    public void separatesQuoteFromCookedHtml() {
        LinuxDoConnector.ParsedContent content = LinuxDoConnector.parsedCooked(
                "<aside class=\"quote\"><blockquote>引用内容</blockquote></aside><p>回复正文</p>"
        );

        assertEquals("引用：引用内容", content.replyContext);
        assertEquals("回复正文", content.text);
    }
}
