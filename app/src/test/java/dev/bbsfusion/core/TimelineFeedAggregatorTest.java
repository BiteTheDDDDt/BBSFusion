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
            topics.add(new TopicSummary(
                    siteId,
                    siteId + "-" + time,
                    "https://example.test/" + siteId + "/" + time,
                    siteId,
                    minute(time)
            ));
        }
        return topics;
    }

    private static long minute(long value) {
        return value * 60_000L;
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
            calls.put(board.key(), calls(board) + 1);
            List<TopicSummary> topics = pages.get(key(board, page));
            return topics == null ? Collections.emptyList() : topics;
        }

        private static String key(BoardDefinition board, int page) {
            return board.key() + ":" + page;
        }
    }
}
