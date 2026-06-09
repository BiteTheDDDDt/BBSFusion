package dev.bbsfusion.site;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public final class NgaConnectorTest {
    @Test
    public void extractsAuthorAndAvatarFromNestedAuthorObject() throws Exception {
        JSONObject item = new JSONObject(
                "{"
                        + "\"author\":{"
                        + "\"username\":\"鸟身猪面像\","
                        + "\"avatar\":\"/avatars/user.jpg\""
                        + "}"
                        + "}"
        );

        assertEquals("鸟身猪面像", NgaConnector.authorFromPostJson(item, new JSONObject(), 0));
        assertEquals(
                "http://img.nga.178.com/avatars/user.jpg",
                NgaConnector.avatarFromPostJson(item, new JSONObject())
        );
    }

    @Test
    public void extractsImagesFromContentAndAttachments() throws Exception {
        JSONObject item = new JSONObject(
                "{"
                        + "\"attachments\":{\"0\":{\"url\":\"attachments/mon_202606/08/sample.jpg\"}}"
                        + "}"
        );
        String content = "正文[img]./mon_202606/08/inline.png[/img]";

        List<String> imageUrls = NgaConnector.imageUrlsFromApiPost(item, content);

        assertEquals(2, imageUrls.size());
        assertEquals("http://img.nga.178.com/attachments/mon_202606/08/inline.png", imageUrls.get(0));
        assertEquals("http://img.nga.178.com/attachments/mon_202606/08/sample.jpg", imageUrls.get(1));
    }

    @Test
    public void extractsPostTimeMetadata() throws Exception {
        JSONObject item = new JSONObject(
                "{"
                        + "\"postdate\":1780912200,"
                        + "\"lastmodify\":1780915800"
                        + "}"
        );

        String meta = NgaConnector.postMetaFromApiPost(item);

        assertEquals("发表于 2026-6-8 17:50 · 编辑 2026-6-8 18:50", meta);
    }

    @Test
    public void extractsPostTimeMetadataFromStringFields() throws Exception {
        JSONObject item = new JSONObject(
                "{"
                        + "\"postDate\":\"2026-6-8 17:50\","
                        + "\"lastModify\":\"2026-6-8 18:50\""
                        + "}"
        );

        String meta = NgaConnector.postMetaFromApiPost(item);

        assertEquals("发表于 2026-6-8 17:50 · 编辑 2026-6-8 18:50", meta);
    }

    @Test
    public void keepsHtmlEmoticonLabelsInApiContent() {
        String content = "正文<img src=\"/smiley/ac/lol.png\" alt=\"[笑]\">后续";

        assertEquals("正文 [笑] 后续", NgaConnector.cleanApiContent(content));
        NgaConnector.ParsedApiContent parsed = NgaConnector.parseApiContent(new JSONObject(), content);
        assertEquals(1, parsed.inlineImages.size());
        assertEquals("https://bbs.nga.cn/smiley/ac/lol.png", parsed.inlineImages.get(0).sourceUrl);
    }

    @Test
    public void separatesQuoteFromApiContent() {
        String content = "[quote]alice 发表于 2026-6-8 17:50 原文[/quote]回复正文";

        NgaConnector.ParsedApiContent parsed = NgaConnector.parseApiContent(new JSONObject(), content);

        assertEquals("引用：alice 发表于 2026-6-8 17:50 原文", parsed.replyContext);
        assertEquals("回复正文", parsed.text);
    }
}
