package dev.bbsfusion.site;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class S1ConnectorTest {
    @Test
    public void buildsDesktopBoardPageUrls() {
        assertEquals(
                "https://stage1st.com/2b/forum-157-3.html",
                S1Connector.pagedBoardUrl("https://stage1st.com/2b/forum-157-1.html", 3)
        );
    }

    @Test
    public void buildsQueryBoardPageUrls() {
        assertEquals(
                "https://stage1st.com/2b/forum.php?mod=forumdisplay&fid=157&page=2",
                S1Connector.pagedBoardUrl(
                        "https://stage1st.com/2b/forum.php?mod=forumdisplay&fid=157",
                        2
                )
        );
    }
}
