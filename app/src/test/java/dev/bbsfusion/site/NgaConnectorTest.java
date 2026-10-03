package dev.bbsfusion.site;

import dev.bbsfusion.core.BoardDefinition;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NgaConnectorTest {
    @Test
    public void usesApiTopicTitleAndPageCountWhenFirstPageContainsNineteenPosts() throws Exception {
        JSONObject root = apiPage(19).put("tsubject", "真实接口的主题标题")
                .put("subject", "兼容字段")
                .put("currentPage", 1).put("totalPage", 2).put("perPage", 20).put("vrows", 35);
        dev.bbsfusion.core.TopicDetail detail = NgaConnector.parseTopicFromApi(
                root, "https://bbs.nga.cn/read.php?tid=1", 1);
        assertEquals("真实接口的主题标题", detail.title);
        assertEquals(19, detail.posts.size());
        assertTrue(detail.hasMore);
    }

    @Test
    public void aFullLastApiPageDoesNotOfferAnotherPage() throws Exception {
        JSONObject root = apiPage(20).put("currentPage", 2).put("totalPage", 2);
        assertFalse(NgaConnector.parseTopicFromApi(root, "https://bbs.nga.cn/read.php?tid=1", 2).hasMore);
    }

    @Test
    public void usesVisibleRowsAndPageSizeIfTotalPageIsMissing() throws Exception {
        JSONObject root = apiPage(19).put("currentPage", 1).put("perPage", 20).put("vrows", 35);
        assertTrue(NgaConnector.parseTopicFromApi(root, "https://bbs.nga.cn/read.php?tid=1", 1).hasMore);
        root = apiPage(20).put("currentPage", 2).put("perPage", 20).put("vrows", 40);
        assertFalse(NgaConnector.parseTopicFromApi(root, "https://bbs.nga.cn/read.php?tid=1", 2).hasMore);
    }

    @Test
    public void emptyPagesAndClampedEarlierPagesDoNotAppendOrContinue() throws Exception {
        JSONObject empty = apiPage(0).put("currentPage", 3).put("totalPage", 5);
        assertFalse(NgaConnector.parseTopicFromApi(empty, "https://bbs.nga.cn/read.php?tid=1", 3).hasMore);
        JSONObject clamped = apiPage(20).put("currentPage", 2).put("totalPage", 2);
        dev.bbsfusion.core.TopicDetail detail = NgaConnector.parseTopicFromApi(
                clamped, "https://bbs.nga.cn/read.php?tid=1", 3);
        assertEquals(0, detail.posts.size());
        assertFalse(detail.hasMore);
    }

    private static JSONObject apiPage(int count) throws Exception {
        org.json.JSONArray posts = new org.json.JSONArray();
        for (int i = 0; i < count; i++) {
            posts.put(new JSONObject().put("content", "回复 " + i).put("subject", ""));
        }
        return new JSONObject().put("result", posts);
    }

    @Test
    public void laterPagesRetainPidInsteadOfInventingALocalFloorNumber() throws Exception {
        JSONObject root = new JSONObject("{\"result\":["
                + "{\"pid\":123,\"content\":\"被回复的正文\"},"
                + "{\"pid\":124,\"content\":\"[pid=123]引用正文[/pid]我的回复\"}]}");
        assertEquals("回复 #123：引用正文",
                NgaConnector.parseTopicFromApi(root, "https://bbs.nga.cn/read.php?tid=1", 2)
                        .posts.get(1).replyContext);
        assertEquals("回复 1楼：引用正文",
                NgaConnector.parseTopicFromApi(root, "https://bbs.nga.cn/read.php?tid=1", 1)
                        .posts.get(1).replyContext);
    }

    @Test
    public void retainsAllPostsAlreadyReturnedByTheApi() throws Exception {
        org.json.JSONArray posts = new org.json.JSONArray();
        for (int i = 0; i < 65; i++) {
            posts.put(new JSONObject().put("content", "回复 " + i));
        }
        JSONObject root = new JSONObject().put("result", posts);
        assertEquals(65, NgaConnector.parseTopicFromApi(root, "https://bbs.nga.cn/read.php?tid=1", 1)
                .posts.size());
    }

    @Test
    public void buildsTopicFallbackPageBeforeFragmentsAndReplacesExistingPage() {
        assertEquals("https://bbs.nga.cn/read.php?tid=123&page=3",
                NgaConnector.pagedTopicUrl("https://bbs.nga.cn/read.php?tid=123#post_7", 3));
        assertEquals("https://bbs.nga.cn/read.php?tid=123&page=2&foo=bar",
                NgaConnector.pagedTopicUrl("https://bbs.nga.cn/read.php?tid=123&page=8&foo=bar", 2));
        assertEquals("https://bbs.nga.cn/read.php?tid=123&page=1",
                NgaConnector.pagedTopicUrl("https://bbs.nga.cn/read.php?tid=123&page=8", 1));
    }

    @Test
    public void keepsSingleCharacterRepliesFromApi() throws Exception {
        JSONObject root = new JSONObject(
                "{\"subject\":\"主题\",\"result\":[{\"author\":\"alice\",\"content\":\"好\"}]}"
        );
        assertEquals(1, NgaConnector.parseTopicFromApi(root, "https://bbs.nga.cn/read.php?tid=1", 1)
                .posts.size());
        assertEquals("好", NgaConnector.parseTopicFromApi(root, "https://bbs.nga.cn/read.php?tid=1", 1)
                .posts.get(0).content);
    }

    @Test
    public void buildsBoardFormForPagedFidBoards() {
        BoardDefinition board = new BoardDefinition(
                "nga",
                "-7",
                "网事杂谈",
                "https://bbs.nga.cn/thread.php?fid=-7",
                "https://bbs.nga.cn/",
                "NGA 网事杂谈"
        );

        Map<String, String> form = NgaConnector.boardForm(board, 3);

        assertEquals("-7", form.get("fid"));
        assertEquals("3", form.get("page"));
    }

    @Test
    public void buildsBoardFormForPagedStidBoards() {
        BoardDefinition board = new BoardDefinition(
                "nga",
                "stid:123",
                "子版",
                "https://bbs.nga.cn/thread.php?stid=123",
                "https://bbs.nga.cn/",
                "NGA 子版"
        );

        Map<String, String> form = NgaConnector.boardForm(board, 2);

        assertEquals("123", form.get("stid"));
        assertEquals("2", form.get("page"));
    }

    @Test
    public void buildsWebFallbackBoardPageUrls() {
        assertEquals(
                "https://bbs.nga.cn/thread.php?fid=-7&page=3",
                NgaConnector.pagedBoardUrl("https://bbs.nga.cn/thread.php?fid=-7", 3)
        );
    }

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
    public void rendersNamedUbbEmoticonsAsInlineImages() {
        String content = "正文[s:ng:doge]中间[s:ac:哭笑]后续[s:pg:响指]";

        NgaConnector.ParsedApiContent parsed = NgaConnector.parseApiContent(new JSONObject(), content);

        assertEquals("正文 [s:ng:doge] 中间 [s:ac:哭笑] 后续 [s:pg:响指]", parsed.text);
        assertEquals(0, parsed.imageUrls.size());
        assertEquals(3, parsed.inlineImages.size());
        assertEquals(
                "https://img4.nga.178.com/ngabbs/post/smile/ng_11.png",
                parsed.inlineImages.get(0).sourceUrl
        );
        assertEquals(
                "https://img4.nga.178.com/ngabbs/post/smile/ac15.png",
                parsed.inlineImages.get(1).sourceUrl
        );
        assertEquals(
                "https://img4.nga.178.com/ngabbs/post/smile/pg14.png",
                parsed.inlineImages.get(2).sourceUrl
        );
    }

    @Test
    public void treatsNgaSmileImageUrlsAsInlineImages() {
        String content = "正文[img]https://img4.nga.178.com/ngabbs/post/smile/ac15.png[/img]后续";

        NgaConnector.ParsedApiContent parsed = NgaConnector.parseApiContent(new JSONObject(), content);

        assertEquals("正文 [s:ac:哭笑] 后续", parsed.text);
        assertEquals(0, parsed.imageUrls.size());
        assertEquals(1, parsed.inlineImages.size());
        assertEquals(
                "https://img4.nga.178.com/ngabbs/post/smile/ac15.png",
                parsed.inlineImages.get(0).sourceUrl
        );
    }

    @Test
    public void preservesApiContentLineBreaks() {
        String content = "第一行[br]第二行<br />第三行<div>第四段</div><p>第五段</p>";

        NgaConnector.ParsedApiContent parsed = NgaConnector.parseApiContent(new JSONObject(), content);

        assertEquals("第一行\n第二行\n第三行\n第四段\n第五段", parsed.text);
    }

    @Test
    public void collapsesApiContentExtraBlankLines() {
        String content = "第一行[br][br]第二行<div><p>第三段</p></div>";

        NgaConnector.ParsedApiContent parsed = NgaConnector.parseApiContent(new JSONObject(), content);

        assertEquals("第一行\n第二行\n第三段", parsed.text);
    }

    @Test
    public void separatesQuoteFromApiContent() {
        String content = "[quote]alice 发表于 2026-6-8 17:50 原文[/quote]回复正文";

        NgaConnector.ParsedApiContent parsed = NgaConnector.parseApiContent(new JSONObject(), content);

        assertEquals("引用：alice 发表于 2026-6-8 17:50 原文", parsed.replyContext);
        assertEquals("回复正文", parsed.text);
    }

    @Test
    public void mapsPidReplyContextToKnownFloorNumber() {
        Map<String, Integer> floorByPid = new HashMap<>();
        floorByPid.put("123", 2);
        String content = "[pid=123]alice 发表于 2026-6-8 17:50 原文[/pid]回复正文";

        NgaConnector.ParsedApiContent parsed = NgaConnector.parseApiContent(
                new JSONObject(),
                content,
                floorByPid
        );

        assertEquals("回复 2楼：alice 发表于 2026-6-8 17:50 原文", parsed.replyContext);
        assertEquals("回复正文", parsed.text);
    }

    @Test
    public void keepsPidWhenReplyFloorIsUnknown() {
        String content = "[pid=123]alice 发表于 2026-6-8 17:50 原文[/pid]回复正文";

        NgaConnector.ParsedApiContent parsed = NgaConnector.parseApiContent(new JSONObject(), content);

        assertEquals("回复 #123：alice 发表于 2026-6-8 17:50 原文", parsed.replyContext);
        assertEquals("回复正文", parsed.text);
    }

    @Test
    public void cleansCommonBbcodeAndHtmlEntitiesFromApiContent() {
        String content = "&gt; [url=https://example.test]链接文本[/url] [/url] "
                + "[b]Post by [uid=123]alice[/uid] (2026-6-8 17:50):[/b] 正文";

        NgaConnector.ParsedApiContent parsed = NgaConnector.parseApiContent(new JSONObject(), content);

        assertEquals("链接文本 正文", parsed.text);
    }

    @Test
    public void removesReplyToHeadersFromApiContent() {
        String content = "&gt; Reply to Post by [uid=123]alice[/uid] (2026-6-8 17:50): "
                + "[url=https://example.test]引用来源[/url]<br/><br/>正文内容";

        NgaConnector.ParsedApiContent parsed = NgaConnector.parseApiContent(new JSONObject(), content);

        assertEquals("正文内容", parsed.text);
    }
}
