package dev.bbsfusion.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class FeedOrderingTest {
    @Test
    public void ordersTopicsByLastReplyTimeDescending() {
        long base = 1_780_920_000_000L;
        List<TopicSummary> ordered = FeedOrdering.order(Arrays.asList(
                topic("nga", "nga-1", base + 230_000L),
                topic("s1", "s1-1", base + 280_000L),
                topic("nga", "nga-2", base + 220_000L),
                topic("s1", "s1-2", base + 120_000L)
        ));

        assertEquals("s1-1", ordered.get(0).title);
        assertEquals("nga-1", ordered.get(1).title);
        assertEquals("nga-2", ordered.get(2).title);
        assertEquals("s1-2", ordered.get(3).title);
    }

    @Test
    public void keepsSourceFutureTimesInStrictOrder() {
        long now = 1_780_920_150_000L;
        List<TopicSummary> ordered = FeedOrdering.order(Arrays.asList(
                topic("s1", "future-1", now + 6 * 86_400_000L),
                topic("s1", "future-2", now + 5 * 86_400_000L),
                topic("nga", "current-1", now - 30_000L),
                topic("nga", "current-2", now - 60_000L)
        ));

        assertEquals("future-1", ordered.get(0).title);
        assertEquals("future-2", ordered.get(1).title);
        assertEquals("current-1", ordered.get(2).title);
        assertEquals("current-2", ordered.get(3).title);
    }

    @Test
    public void interleavesUntimedTopicsBySiteAfterTimedTopics() {
        List<TopicSummary> ordered = FeedOrdering.order(Arrays.asList(
                topic("nga", "nga-1", 0L),
                topic("nga", "nga-2", 0L),
                topic("s1", "s1-1", 0L),
                topic("s1", "s1-2", 0L),
                topic("nga", "timed", 100L)
        ));

        assertEquals("timed", ordered.get(0).title);
        assertEquals("nga-1", ordered.get(1).title);
        assertEquals("s1-1", ordered.get(2).title);
        assertEquals("nga-2", ordered.get(3).title);
        assertEquals("s1-2", ordered.get(4).title);
    }

    private static TopicSummary topic(String siteId, String title, long sortTimeMillis) {
        return new TopicSummary(siteId, title, "https://example.test/" + title, siteId, sortTimeMillis);
    }
}
