package dev.bbsfusion.site;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class S1ConnectorTest {
    @Test
    public void topicPagesResetAndKeepQueryParametersBeforeFragments() {
        assertEquals("https://stage1st.com/2b/thread-12-1-1.html",
                S1Connector.pagedTopicUrl("https://stage1st.com/2b/thread-12-8-1.html#post12", 1));
        assertEquals("https://stage1st.com/2b/forum.php?mod=viewthread&tid=12&page=2",
                S1Connector.pagedTopicUrl("https://stage1st.com/2b/forum.php?mod=viewthread&tid=12#post12", 2));
    }

    @Test
    public void boardPageQueryPrecedesTheFragmentAndCanResetPageOne() {
        assertEquals("https://stage1st.com/2b/forum.php?mod=forumdisplay&fid=157&page=3",
                S1Connector.pagedBoardUrl(
                        "https://stage1st.com/2b/forum.php?mod=forumdisplay&fid=157#last", 3));
        assertEquals("https://stage1st.com/2b/forum-157-1.html",
                S1Connector.pagedBoardUrl("https://stage1st.com/2b/forum-157-8.html#last", 1));
    }

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
