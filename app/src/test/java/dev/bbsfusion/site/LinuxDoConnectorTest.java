package dev.bbsfusion.site;

import dev.bbsfusion.core.BoardDefinition;
import dev.bbsfusion.core.Post;
import dev.bbsfusion.core.Poll;
import dev.bbsfusion.core.TopicDetail;
import dev.bbsfusion.core.TopicSummary;

import org.json.JSONObject;
import org.json.JSONArray;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LinuxDoConnectorTest {
    @Test
    public void countsPagesFromStreamIdsWithoutTreatingPostNumbersAsOffsets() throws Exception {
        org.json.JSONArray ids = new org.json.JSONArray();
        for (int i = 0; i < 45; i++) { ids.put(1000 + i); }
        JSONObject root = new JSONObject().put("title", "topic").put("post_stream", new JSONObject()
                .put("stream", ids).put("posts", new org.json.JSONArray()
                        .put(new JSONObject().put("post_number", 51).put("cooked", "<p>first</p>"))
                        .put(new JSONObject().put("post_number", 54).put("cooked", "<p>second</p>"))));
        TopicDetail detail = LinuxDoConnector.parseTopicFromJson(root, "https://linux.do/t/201", 3);
        assertEquals(3, detail.lastPage);
        assertEquals(0, detail.pageSize);
        assertEquals(51, detail.posts.get(0).floorNumber);
        assertEquals(54, detail.posts.get(1).floorNumber);
        org.junit.Assert.assertFalse(detail.hasMore);
        assertTrue(LinuxDoConnector.parseTopicFromJson(root, "https://linux.do/t/201", 2).hasMore);
    }

    @Test
    public void fallbackDocumentsDoNotClaimToContainTheLastSourcePage() {
        TopicDetail html = LinuxDoConnector.parseTopicFromHtml(
                Jsoup.parse("<article><div class='cooked'>partial content</div></article>"),
                "https://linux.do/t/201");
        TopicDetail rss = LinuxDoConnector.parseTopicFromRss(Jsoup.parse(
                "<rss><channel><title>topic</title><item><guid>1</guid><description>partial content</description>"
                        + "</item></channel></rss>", "https://linux.do/", Parser.xmlParser()),
                "https://linux.do/t/201");
        assertEquals(0, html.lastPage);
        assertEquals(0, rss.lastPage);
        assertEquals(0, html.posts.get(0).floorNumber);
        assertEquals(0, rss.posts.get(0).floorNumber);
    }

    @Test
    public void jsonPollOnlyPostsRetainProvidedResultsIncludingGenuineZeroVotes() throws Exception {
        JSONObject poll = new JSONObject().put("name", "poll").put("title", "选择 <b>系统</b>")
                .put("type", "regular").put("status", "open").put("results", "always")
                .put("options", new JSONArray()
                        .put(new JSONObject().put("html", "<b>Linux</b>").put("votes", 2))
                        .put(new JSONObject().put("html", "Windows").put("votes", 0)));
        Post post = parsePollPost(new JSONObject().put("post_number", 42).put("cooked", "")
                .put("polls", new JSONArray().put(poll)));

        assertEquals("", post.content);
        assertEquals(1, post.polls.size());
        Poll parsed = post.polls.get(0);
        assertEquals("选择 系统", parsed.question);
        assertEquals("Linux", parsed.options.get(0).text);
        assertEquals("2 票", parsed.options.get(0).result);
        assertEquals("0 票", parsed.options.get(1).result);
        assertTrue(parsed.status.contains("单选"));
        assertTrue(parsed.status.contains("投票进行中"));
    }

    @Test
    public void missingVotesRemainHiddenAndCookedPollPlaceholderCountsAreRemoved() throws Exception {
        String cooked = "<p>前文</p>" + cookedPoll("weekend", "周末安排", "旅行", "阅读") + "<p>后文</p>";
        JSONObject poll = new JSONObject().put("name", "weekend")
                .put("type", "multiple").put("status", "open").put("results", "on_vote")
                .put("voters", 123).put("options", new JSONArray()
                        .put(new JSONObject().put("html", "旅行"))
                        .put(new JSONObject().put("html", "阅读")));
        Post post = parsePollPost(new JSONObject().put("cooked", cooked)
                .put("polls", new JSONArray().put(poll)));

        assertEquals(1, post.polls.size());
        assertEquals("周末安排", post.polls.get(0).question);
        assertEquals("", post.polls.get(0).options.get(0).result);
        assertEquals("", post.polls.get(0).options.get(1).result);
        assertTrue(post.polls.get(0).status.contains("结果请在原站查看"));
        assertTrue(post.content.contains("前文"));
        assertTrue(post.content.contains("后文"));
        assertFalse(post.content.contains("旅行"));
        assertFalse(post.content.contains("voters"));
        assertFalse(post.content.contains("123"));
    }

    @Test
    public void pollsKeepServerOptionOrderAndIgnoreInvalidVoteValues() throws Exception {
        JSONObject poll = new JSONObject().put("title", "公开选项").put("status", "closed")
                .put("options", new JSONArray()
                        .put(new JSONObject().put("html", "<img alt='图案甲' src='/one.png'>").put("votes", -1))
                        .put(new JSONObject().put("html", "第二项").put("votes", "unknown"))
                        .put(new JSONObject().put("html", "第三项").put("votes", JSONObject.NULL)));
        Post post = parsePollPost(new JSONObject().put("polls", new JSONArray().put(poll)));

        assertEquals(3, post.polls.get(0).options.size());
        assertEquals("图案甲", post.polls.get(0).options.get(0).text);
        assertEquals("第二项", post.polls.get(0).options.get(1).text);
        for (Poll.Option option : post.polls.get(0).options) {
            assertEquals("", option.result);
        }
        assertTrue(post.polls.get(0).status.contains("投票已结束"));
    }

    @Test
    public void htmlFallbackKeepsPollOnlyPostAndNeverUsesCookedPlaceholderCounts() {
        String poll = cookedPoll("poll", "午餐", "米饭", "面条");
        TopicDetail detail = LinuxDoConnector.parseTopicFromHtml(
                Jsoup.parse("<h1>主题</h1><article><div class='cooked'>" + poll + "</div></article>"),
                "https://linux.do/t/topic/1");

        assertEquals(1, detail.posts.size());
        assertEquals("", detail.posts.get(0).content);
        assertEquals("午餐", detail.posts.get(0).polls.get(0).question);
        assertEquals(2, detail.posts.get(0).polls.get(0).options.size());
        assertEquals("", detail.posts.get(0).polls.get(0).options.get(0).result);
    }

    @Test
    public void quotedPollsDoNotBecomeTheCurrentPostsPollsAndOrdinaryListsRemainText() {
        LinuxDoConnector.ParsedContent parsed = LinuxDoConnector.parsedCooked(
                "<aside class='quote'>" + cookedPoll("quote", "引用投票", "甲", "乙") + "</aside>"
                        + "<ul><li>普通列表</li></ul>" + cookedPoll("own", "当前投票", "丙", "丁"));

        assertEquals(1, parsed.polls.size());
        assertEquals("当前投票", parsed.polls.get(0).question);
        assertTrue(parsed.text.contains("普通列表"));
        assertFalse(parsed.text.contains("当前投票"));
        assertTrue(parsed.replyContext.contains("引用投票"));
    }

    @Test
    public void rssFallbackDeduplicatesTheOpeningPollButKeepsDifferentPolls() {
        String first = cookedPoll("first", "第一个问题", "甲", "乙");
        String second = cookedPoll("second", "第二个问题", "丙", "丁");
        String xml = "<rss><channel><title>主题</title><description><![CDATA[" + first + "]]></description>"
                + "<item><guid>one</guid><description><![CDATA[" + first + "]]></description></item>"
                + "<item><guid>two</guid><description><![CDATA[" + second + "]]></description></item>"
                + "</channel></rss>";
        TopicDetail detail = LinuxDoConnector.parseTopicFromRss(
                Jsoup.parse(xml, "https://linux.do/", Parser.xmlParser()), "https://linux.do/t/topic/1");

        assertEquals(2, detail.posts.size());
        assertEquals("第一个问题", detail.posts.get(0).polls.get(0).question);
        assertEquals("第二个问题", detail.posts.get(1).polls.get(0).question);
    }

    private static Post parsePollPost(JSONObject post) throws Exception {
        JSONObject root = new JSONObject().put("title", "主题").put("post_stream",
                new JSONObject().put("posts", new JSONArray().put(post)));
        TopicDetail detail = LinuxDoConnector.parseTopicFromJson(root, "https://linux.do/t/topic/1");
        assertEquals(1, detail.posts.size());
        return detail.posts.get(0);
    }

    // Matches Discourse's official poll markdown output. poll-info contains rendering placeholders.
    private static String cookedPoll(String name, String title, String first, String second) {
        return "<div class='poll' data-poll-name='" + name + "' data-poll-type='regular' data-poll-status='open'>"
                + "<div><div class='poll-container'><div class='poll-title'>" + title + "</div><ul>"
                + "<li data-poll-option-id='a'>" + first + "</li>"
                + "<li data-poll-option-id='b'>" + second + "</li></ul></div>"
                + "<div class='poll-info'><p><span class='info-number'>0</span> voters</p></div></div></div>";
    }

    @Test
    public void responseParsersRetainAllIdentifiedPostsBeyondEighty() throws Exception {
        StringBuilder html = new StringBuilder("<h1>主题</h1>");
        StringBuilder rss = new StringBuilder("<rss><channel><title>主题</title>");
        org.json.JSONArray posts = new org.json.JSONArray();
        for (int i = 1; i <= 90; i++) {
            html.append("<article><div class='cooked'>回复 ").append(i).append("</div></article>");
            rss.append("<item><guid>post-").append(i).append("</guid><description>回复 ")
                    .append(i).append("</description></item>");
            posts.put(new JSONObject().put("post_number", i).put("cooked", "<p>回复 " + i + "</p>"));
        }
        rss.append("</channel></rss>");
        assertEquals(90, LinuxDoConnector.parseTopicFromHtml(
                Jsoup.parse(html.toString()), "https://linux.do/t/topic/201").posts.size());
        assertEquals(90, LinuxDoConnector.parseTopicFromRss(
                Jsoup.parse(rss.toString(), "https://linux.do/", Parser.xmlParser()),
                "https://linux.do/t/topic/201").posts.size());
        JSONObject page = new JSONObject().put("post_stream", new JSONObject().put("posts", posts));
        assertEquals(90, LinuxDoConnector.parseTopicFromJson(
                page, "https://linux.do/t/topic/201", 2, false, "主题").posts.size());
    }

    @Test
    public void rssFallbackPrefersTheOpeningItemAndDeduplicatesOnlyItsStableId() {
        String xml = "<rss><channel><title>主题</title><description>主帖正文</description>"
                + "<item><guid>post-1</guid><dc:creator>alice</dc:creator><description>主帖正文</description></item>"
                + "<item><guid>post-1</guid><dc:creator>alice</dc:creator><description>主帖正文</description></item>"
                + "<item><guid>post-2</guid><dc:creator>bob</dc:creator><description>主帖正文</description></item>"
                + "</channel></rss>";
        TopicDetail detail = LinuxDoConnector.parseTopicFromRss(
                Jsoup.parse(xml, "https://linux.do/t/sample-topic/201.rss", Parser.xmlParser()),
                "https://linux.do/t/sample-topic/201");
        assertEquals(2, detail.posts.size());
        assertEquals("alice", detail.posts.get(0).author);
        assertEquals("bob", detail.posts.get(1).author);
    }

    @Test
    public void htmlFallbackParsesNestedPostContainersOnlyOnceAndKeepsSameTextReplies() {
        String html = "<h1>主题</h1><article><a href='/u/alice'>alice</a>"
                + "<div class='topic-body'><div class='crawler-post'><div class='cooked'>相同回复</div></div></div>"
                + "</article><article><a href='/u/bob'>bob</a>"
                + "<div class='topic-body'><div class='cooked'>相同回复</div></div></article>";
        TopicDetail detail = LinuxDoConnector.parseTopicFromHtml(
                Jsoup.parse(html, "https://linux.do/"), "https://linux.do/t/sample-topic/201");
        assertEquals(2, detail.posts.size());
        assertEquals("alice", detail.posts.get(0).author);
        assertEquals("bob", detail.posts.get(1).author);
    }

    @Test
    public void rssFallbackKeepsDifferentPostsWithIdenticalText() {
        String xml = "<rss><channel><title>主题</title><description>主帖正文</description>"
                + "<item><guid>post-1</guid><dc:creator>alice</dc:creator><description>相同回复</description></item>"
                + "<item><guid>post-2</guid><dc:creator>bob</dc:creator><description>相同回复</description></item>"
                + "</channel></rss>";
        TopicDetail detail = LinuxDoConnector.parseTopicFromRss(
                Jsoup.parse(xml, "https://linux.do/t/sample-topic/201.rss", Parser.xmlParser()),
                "https://linux.do/t/sample-topic/201");
        assertEquals(3, detail.posts.size());
        assertEquals("alice", detail.posts.get(1).author);
        assertEquals("bob", detail.posts.get(2).author);
    }

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
        assertEquals("回复 2楼", post.replyContext);
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
