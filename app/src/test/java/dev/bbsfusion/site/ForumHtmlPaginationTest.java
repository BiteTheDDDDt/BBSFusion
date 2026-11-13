package dev.bbsfusion.site;

import dev.bbsfusion.core.TopicDetail;
import org.jsoup.Jsoup;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ForumHtmlPaginationTest {
    private static final String URL = "https://stage1st.com/2b/thread-12-1-1.html";

    @Test public void readsDesktopFloorsAndFullFirstPageSizeWithoutDoubleCountingContainers() {
        String html = "<div id='post_100'><table id='pid100'><tr><td><div class='pi'><strong>"
                + "<a id='postnum100'>楼主</a></strong></div><div class='t_f'>opening</div>"
                + "</td></tr></table><div id='post_rate_div_100'></div></div>"
                + "<div id='post_101'><div class='pi'><strong><a id='postnum101'><em>2</em><sup>#</sup>"
                + "</a></strong></div><div class='t_f'>reply</div></div>"
                + "<div class='pg'><strong>1</strong><a href='thread-12-2-1.html' class='nxt'>下一页</a>"
                + "<label><span title='共 3 页'> / 3 页</span></label></div>";
        TopicDetail detail = parse(html, 1);
        assertEquals(2, detail.posts.size());
        assertEquals(1, detail.posts.get(0).floorNumber);
        assertEquals(2, detail.posts.get(1).floorNumber);
        assertEquals(3, detail.lastPage);
        assertEquals(2, detail.pageSize);
        assertTrue(detail.hasMore);
    }

    @Test public void preservesActualMobilePageWhenTheServerClampsTheRequestedPage() {
        TopicDetail detail = parse(mobilePost(41) + mobilePost(42)
                + "<div class='pg'><a href='thread-12-1-1.html'>上一页</a><strong>2</strong>"
                + "<label><input name='custompage' value='2'><span title='共 2 页'> / 2 页</span></label></div>", 999);
        assertEquals(2, detail.pageNumber);
        assertEquals(2, detail.lastPage);
        assertEquals(0, detail.pageSize);
        assertFalse(detail.hasMore);
        assertEquals(41, detail.posts.get(0).floorNumber);
        assertEquals(42, detail.posts.get(1).floorNumber);
    }

    @Test public void doesNotMistakeTheLastVisibleWindowLinkForTheLastPage() {
        TopicDetail detail = parse(mobilePost(1) + mobilePost(2)
                + "<div class='pg'><strong>1</strong><a href='thread-12-2-1.html'>2</a>"
                + "<a href='thread-12-10-1.html'>10</a></div>", 1);
        assertEquals(0, detail.lastPage);
        assertTrue(detail.hasMore);
    }

    @Test public void acceptsOnlySameTopicAndOriginLastPageLinks() {
        TopicDetail detail = parse(mobilePost(41)
                + "<div class='pg'><strong>2</strong><a class='last' href='thread-12-30-1.html'>... 30</a>"
                + "<a class='last' href='thread-99-999-1.html'>末页</a>"
                + "<a class='last' href='https://other.example/thread-12-999-1.html'>末页</a></div>", 2);
        assertEquals(30, detail.lastPage);
        assertTrue(detail.hasMore);
    }

    @Test public void gapsAndUnidentifiedPostsDoNotInventArithmeticPageSizes() {
        TopicDetail detail = parse(mobilePost(1) + mobilePost(3)
                + "<div class='pg'><strong>1</strong><a href='thread-12-2-1.html'>2</a></div>", 1);
        assertEquals(0, detail.pageSize);
        assertEquals(3, detail.posts.get(1).floorNumber);
        detail = parse("<div class='message'>A post mentioning 123# in its text</div>", 1);
        assertEquals(0, detail.posts.get(0).floorNumber);
        assertEquals(0, detail.lastPage);
        assertEquals(0, detail.pageSize);
    }

    private static TopicDetail parse(String html, int page) {
        return ForumHtmlParsers.extractTopic(Jsoup.parse(html, URL), URL, page);
    }

    private static String mobilePost(int floor) {
        return "<div class='plc cl' id='pid" + floor + "'><div class='display pi'><ul class='authi'>"
                + "<li class='mtit'><span class='y'>" + (floor == 1 ? "楼主" : floor + "<sup>#</sup>")
                + "</span><span class='z'><a>author</a></span></li></ul>"
                + "<div class='message'>post " + floor + "</div></div></div>";
    }
}
