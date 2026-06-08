package dev.bbsfusion.site;

import dev.bbsfusion.core.BoardDefinition;
import dev.bbsfusion.core.Post;
import dev.bbsfusion.core.TopicDetail;
import dev.bbsfusion.core.TopicSummary;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
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
                        + "\"post_stream\":{\"posts\":[{"
                        + "\"display_username\":\"alice\","
                        + "\"avatar_template\":\"/user_avatar/linux.do/alice/{size}/1_2.png\","
                        + "\"created_at\":\"2026-06-08T07:10:00.000Z\","
                        + "\"updated_at\":\"2026-06-08T08:10:00.000Z\","
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
        assertEquals("正文", post.content);
        assertEquals("https://linux.do/uploads/default/original/1/sample.png", post.imageUrls.get(0));
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
}
