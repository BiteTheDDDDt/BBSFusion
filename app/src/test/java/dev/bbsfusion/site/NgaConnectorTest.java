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
}
