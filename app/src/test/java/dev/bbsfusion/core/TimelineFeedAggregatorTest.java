package dev.bbsfusion.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TimelineFeedAggregatorTest {
    @Test
    public void extendsSourceThatBlocksTheTimelineWatermark() {
        BoardDefinition s1 = board("s1", "157");
        BoardDefinition nga = board("nga", "-7");
        FakeFetcher fetcher = new FakeFetcher()
                .page(s1, 1, topics("s1", 100, 90, 10))
                .page(nga, 1, topics("nga", 110, 105, 104))
                .page(nga, 2, topics("nga", 95, 80));

        TimelineFeedAggregator.Result result = TimelineFeedAggregator.load(
                Arrays.asList(s1, nga),
                fetcher,
                5,
                3,
                20
        );

        assertEquals(
                Arrays.asList(minute(110), minute(105), minute(104), minute(100), minute(95), minute(90), minute(80)),
                times(result.topics)
        );
        assertEquals(minute(80), result.watermarkMillis);
        assertEquals(1, fetcher.calls(s1));
        assertEquals(2, fetcher.calls(nga));
        assertTrue(result.hasMore);
    }

    @Test
    public void omitsTopicsOlderThanTheWatermarkWhenPageBudgetRunsOut() {
        BoardDefinition s1 = board("s1", "157");
        BoardDefinition nga = board("nga", "-7");
        FakeFetcher fetcher = new FakeFetcher()
                .page(s1, 1, topics("s1", 100, 20))
                .page(nga, 1, topics("nga", 110, 105));

        TimelineFeedAggregator.Result result = TimelineFeedAggregator.load(
                Arrays.asList(s1, nga),
                fetcher,
                10,
                1,
                20
        );

        assertEquals(Arrays.asList(minute(110), minute(105)), times(result.topics));
        assertEquals(minute(105), result.watermarkMillis);
        assertFalse(times(result.topics).contains(minute(20)));
        assertTrue(result.hasMore);
    }

    @Test
    public void sessionContinuesAfterTheRequestBudgetWithoutRefetchingFirstPages() {
        BoardDefinition s1 = board("s1", "157");
        FakeFetcher fetcher = new FakeFetcher()
                .page(s1, 1, topics("s1", 100, 90))
                .page(s1, 2, topics("s1", 80, 70))
                .page(s1, 3, topics("s1", 60, 50));
        TimelineFeedAggregator.Session session = TimelineFeedAggregator.newSession(Collections.singletonList(s1));

        TimelineFeedAggregator.Result first = session.load(fetcher, 10, 2, 10);
        TimelineFeedAggregator.Result next = session.load(fetcher, 10, 2, 10);

        assertEquals(4, first.topics.size());
        assertTrue(first.hasMore);
        assertEquals(6, next.topics.size());
        assertFalse(next.hasMore);
        assertEquals(Arrays.asList(1, 2, 3, 4), fetcher.requestedPages);
    }

    @Test
    public void loadsUntimedPagesAndKeepsTheirOriginalOrder() {
        BoardDefinition s1 = board("s1", "157");
        FakeFetcher fetcher = new FakeFetcher()
                .page(s1, 1, untimedTopics("one", "two"))
                .page(s1, 2, untimedTopics("three", "four"))
                .page(s1, 3, untimedTopics("five"));
        TimelineFeedAggregator.Session session = TimelineFeedAggregator.newSession(Collections.singletonList(s1));

        TimelineFeedAggregator.Result first = session.load(fetcher, 4, 5, 4);
        assertEquals(4, first.topics.size());
        assertEquals("three", first.topics.get(2).title);
        assertTrue(first.hasMore);

        TimelineFeedAggregator.Result next = session.load(fetcher, 6, 5, 6);
        assertEquals(5, next.topics.size());
        assertEquals("five", next.topics.get(4).title);
        assertFalse(next.hasMore);
        assertEquals(Arrays.asList(1, 2, 3, 4), fetcher.requestedPages);
    }

    @Test
    public void keepsTruncatedCachedTopicsAvailableAfterSourcesEnd() {
        BoardDefinition s1 = board("s1", "157");
        List<TopicSummary> mixed = new ArrayList<>(topics("s1", 100));
        mixed.addAll(untimedTopics("one", "two", "three", "four"));
        FakeFetcher fetcher = new FakeFetcher().page(s1, 1, mixed);
        TimelineFeedAggregator.Session session = TimelineFeedAggregator.newSession(Collections.singletonList(s1));

        TimelineFeedAggregator.Result first = session.load(fetcher, 3, 5, 3);
        assertEquals(3, first.topics.size());
        assertTrue(first.hasMore);
        assertEquals(2, fetcher.calls(s1));

        TimelineFeedAggregator.Result next = session.load(fetcher, 6, 5, 6);
        assertEquals(5, next.topics.size());
        assertFalse(next.hasMore);
        assertEquals(2, fetcher.calls(s1));
    }

    @Test
    public void retriesFailedPageOnTheNextLoadAndRetainsSuccessfulTopics() {
        BoardDefinition s1 = board("s1", "157");
        TimelineFeedAggregator.Session session = TimelineFeedAggregator.newSession(Collections.singletonList(s1));
        List<Integer> requestedPages = new ArrayList<>();
        TimelineFeedAggregator.Result first = session.load((board, page) -> {
            requestedPages.add(page);
            if (page == 2) {
                throw new java.io.IOException("offline");
            }
            return topics("s1", 100, 90);
        }, 4, 5, 4);

        assertEquals(2, first.topics.size());
        assertEquals(1, first.failures.size());
        assertTrue(first.hasMore);
        TimelineFeedAggregator.Result next = session.load((board, page) -> {
            requestedPages.add(page);
            return topics("s1", 80, 70);
        }, 4, 5, 4);

        assertEquals(4, next.topics.size());
        assertTrue(next.failures.isEmpty());
        assertEquals(Arrays.asList(1, 2, 2), requestedPages);
    }

    @Test
    public void explicitlyRetriesFailedPagesEvenWhenOtherSourcesAlreadyFilledTheTarget() {
        BoardDefinition s1 = board("s1", "157");
        BoardDefinition nga = board("nga", "-7");
        TimelineFeedAggregator.Session session = TimelineFeedAggregator.newSession(Arrays.asList(s1, nga));
        TimelineFeedAggregator.Result first = session.load((board, page) -> {
            if (board == s1 && page == 2) {
                throw new java.io.IOException("offline");
            }
            return Collections.singletonList(topic(board.siteId, board.siteId + page, 100));
        }, 3, 2, 3);
        assertEquals(3, first.topics.size());
        assertEquals(1, first.failures.size());
        List<String> retryCalls = new ArrayList<>();

        TimelineFeedAggregator.Result retried = session.load((board, page) -> {
            retryCalls.add(board.siteId + page);
            return Collections.singletonList(topic(board.siteId, board.siteId + page, 90));
        }, 3, 2, 3);

        assertEquals(Collections.singletonList("s12"), retryCalls);
        assertTrue(retried.failures.isEmpty());
    }

    @Test
    public void newerDuplicateDoesNotAdvanceTheWatermarkAndHidePreviouslyVisibleTopics() {
        BoardDefinition s1 = board("s1", "157");
        BoardDefinition nga = board("nga", "-7");
        FakeFetcher fetcher = new FakeFetcher()
                .page(s1, 1, topics("s1", 300, 175, 100))
                .page(nga, 1, topics("nga", 250, 150))
                .page(nga, 2, Arrays.asList(topic("nga", "nga-150", 260), topic("nga", "nga-200", 200)));
        TimelineFeedAggregator.Session session = TimelineFeedAggregator.newSession(Arrays.asList(s1, nga));

        TimelineFeedAggregator.Result first = session.load(fetcher, 4, 1, 10);
        TimelineFeedAggregator.Result more = session.load(fetcher, 5, 1, 10);

        assertEquals(minute(150), first.watermarkMillis);
        assertTrue(times(first.topics).contains(minute(175)));
        assertEquals(minute(150), more.watermarkMillis);
        assertTrue(times(more.topics).contains(minute(175)));
        assertEquals(minute(300), more.topics.get(0).sortTimeMillis);
        assertEquals(minute(260), more.topics.get(1).sortTimeMillis);
    }

    @Test
    public void doesNotSpendOtherSourceRequestsWhenTheWatermarkSourceReachesItsBudget() {
        BoardDefinition s1 = board("s1", "157");
        BoardDefinition nga = board("nga", "-7");
        FakeFetcher fetcher = new FakeFetcher()
                .page(s1, 1, topics("s1", 100, 20))
                .page(nga, 1, topics("nga", 110, 105))
                .page(nga, 2, topics("nga", 104, 103));

        TimelineFeedAggregator.Result result = TimelineFeedAggregator.load(
                Arrays.asList(s1, nga), fetcher, 10, 2, 10
        );

        assertEquals(1, fetcher.calls(s1));
        assertEquals(2, fetcher.calls(nga));
        assertEquals(minute(103), result.watermarkMillis);
        assertTrue(result.hasMore);
    }

    @Test
    public void reportsNoMoreWhenSourcesAreExhausted() {
        BoardDefinition s1 = board("s1", "157");
        FakeFetcher fetcher = new FakeFetcher()
                .page(s1, 1, topics("s1", 100, 90))
                .page(s1, 2, Collections.emptyList());

        TimelineFeedAggregator.Result result = TimelineFeedAggregator.load(
                Collections.singletonList(s1),
                fetcher,
                10,
                3,
                20
        );

        assertEquals(Arrays.asList(minute(100), minute(90)), times(result.topics));
        assertFalse(result.hasMore);
        assertEquals(2, fetcher.calls(s1));
    }

    private static BoardDefinition board(String siteId, String boardId) {
        return new BoardDefinition(
                siteId,
                boardId,
                siteId + "-" + boardId,
                "https://example.test/" + siteId + "/" + boardId,
                "https://example.test/",
                siteId.toUpperCase() + " " + boardId
        );
    }

    private static List<TopicSummary> topics(String siteId, long... times) {
        List<TopicSummary> topics = new ArrayList<>();
        for (long time : times) {
            topics.add(topic(siteId, siteId + "-" + time, time));
        }
        return topics;
    }

    private static TopicSummary topic(String siteId, String id, long time) {
        return new TopicSummary(siteId, id, "https://example.test/" + siteId + "/" + id,
                siteId, minute(time));
    }

    private static long minute(long value) {
        return value * 60_000L;
    }

    private static List<TopicSummary> untimedTopics(String... titles) {
        List<TopicSummary> topics = new ArrayList<>();
        for (String title : titles) {
            topics.add(new TopicSummary("s1", title, "https://example.test/" + title, "s1", 0L));
        }
        return topics;
    }

    private static List<Long> times(List<TopicSummary> topics) {
        List<Long> times = new ArrayList<>();
        for (TopicSummary topic : topics) {
            times.add(topic.sortTimeMillis);
        }
        return times;
    }

    private static final class FakeFetcher implements TimelineFeedAggregator.PageFetcher {
        private final Map<String, List<TopicSummary>> pages = new HashMap<>();
        private final Map<String, Integer> calls = new HashMap<>();
        private final List<Integer> requestedPages = new ArrayList<>();

        FakeFetcher page(BoardDefinition board, int page, List<TopicSummary> topics) {
            pages.put(key(board, page), topics);
            return this;
        }

        int calls(BoardDefinition board) {
            Integer value = calls.get(board.key());
            return value == null ? 0 : value;
        }

        @Override
        public List<TopicSummary> fetch(BoardDefinition board, int page) {
            requestedPages.add(page);
            calls.put(board.key(), calls(board) + 1);
            List<TopicSummary> topics = pages.get(key(board, page));
            return topics == null ? Collections.emptyList() : topics;
        }

        private static String key(BoardDefinition board, int page) {
            return board.key() + ":" + page;
        }
    }
}
