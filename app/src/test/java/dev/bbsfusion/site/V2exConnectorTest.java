package dev.bbsfusion.site;

import dev.bbsfusion.core.BoardDefinition;
import dev.bbsfusion.core.TopicSummary;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class V2exConnectorTest {
    @Test
    public void extractsTopicsFromPublicApi() throws Exception {
        JSONArray array = new JSONArray(
                "[{"
                        + "\"id\":123,"
                        + "\"title\":\"一个 V2EX 测试主题\","
                        + "\"url\":\"https://www.v2ex.com/t/123\","
                        + "\"last_touched\":1780915200,"
                        + "\"replies\":8,"
                        + "\"node\":{\"title\":\"程序员\"}"
                        + "}]"
        );
        BoardDefinition board = new BoardDefinition(
                "v2ex",
                "latest",
                "最新",
                "https://www.v2ex.com/recent",
                "https://www.v2ex.com/",
                "V2EX 最新"
        );

        List<TopicSummary> topics = V2exConnector.parseTopics(array, board);

        assertEquals(1, topics.size());
        assertEquals("一个 V2EX 测试主题", topics.get(0).title);
        assertEquals("https://www.v2ex.com/t/123", topics.get(0).url);
        assertTrue(topics.get(0).meta.contains("V2EX 程序员"));
        assertEquals(1780915200L * 1000L, topics.get(0).sortTimeMillis);
    }

    @Test
    public void extractsRenderedTextAndImages() {
        V2exConnector.ParsedContent content = V2exConnector.parsedContent(
                "第一行<br><img src=\"//cdn.v2ex.com/a.png\">第二行",
                ""
        );

        assertEquals("第一行 第二行", content.text);
        assertEquals(1, content.imageUrls.size());
        assertEquals("https://cdn.v2ex.com/a.png", content.imageUrls.get(0));
    }

    @Test
    public void keepsInlineEmojiLabelsInRenderedText() {
        V2exConnector.ParsedContent content = V2exConnector.parsedContent(
                "正文<img class=\"emoji\" src=\"/emoji/smile.png\" alt=\":smile:\">后续",
                ""
        );

        assertEquals("正文 :smile: 后续", content.text);
        assertEquals(0, content.imageUrls.size());
        assertEquals(1, content.inlineImages.size());
        assertEquals("https://www.v2ex.com/emoji/smile.png", content.inlineImages.get(0).sourceUrl);
    }

    @Test
    public void separatesBlockquoteFromRenderedText() {
        V2exConnector.ParsedContent content = V2exConnector.parsedContent(
                "<blockquote>被回复的内容</blockquote><p>回复正文</p>",
                ""
        );

        assertEquals("引用：被回复的内容", content.replyContext);
        assertEquals("回复正文", content.text);
    }

    @Test
    public void extractsTopicIdFromTopicUrl() {
        assertEquals("456", V2exConnector.topicIdFromUrl("https://www.v2ex.com/t/456#reply1"));
    }

    @Test
    public void extractsPostTimeMetadata() throws Exception {
        JSONObject item = new JSONObject("{\"created\":1780912200,\"last_modified\":1780915800}");

        assertEquals(
                "发表于 2026-6-8 17:50 · 编辑 2026-6-8 18:50",
                V2exConnector.postMeta(item)
        );
    }
}
