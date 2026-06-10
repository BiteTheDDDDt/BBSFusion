package dev.bbsfusion.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TimelineFeedAggregator {
    private static final int DEFAULT_TARGET_TOPIC_COUNT = 100;
    private static final int DEFAULT_MAX_PAGES_PER_SOURCE = 5;
    private static final int DEFAULT_MAX_VISIBLE_TOPIC_COUNT = 200;

    private TimelineFeedAggregator() {
    }

    public static Result load(List<BoardDefinition> boards, PageFetcher fetcher) {
        return load(
                boards,
                fetcher,
                DEFAULT_TARGET_TOPIC_COUNT,
                DEFAULT_MAX_PAGES_PER_SOURCE,
                DEFAULT_MAX_VISIBLE_TOPIC_COUNT
        );
    }

    static Result load(
            List<BoardDefinition> boards,
            PageFetcher fetcher,
            int targetTopicCount,
            int maxPagesPerSource,
            int maxVisibleTopicCount
    ) {
        if (boards == null || boards.isEmpty()) {
            return new Result(Collections.emptyList(), Collections.emptyList(), 0L);
        }

        List<SourceState> sources = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (BoardDefinition board : boards) {
            SourceState source = new SourceState(board);
            source.loadNext(fetcher, maxPagesPerSource, failures);
            sources.add(source);
        }

        while (visibleTimedCount(sources, watermarkMillis(sources)) < targetTopicCount) {
            SourceState source = sourceBlockingWatermark(sources, maxPagesPerSource);
            if (source == null) {
                break;
            }
            source.loadNext(fetcher, maxPagesPerSource, failures);
        }

        long watermark = watermarkMillis(sources);
        List<TopicSummary> visible = visibleTopics(sources, watermark);
        if (visible.isEmpty()) {
            visible = allTopics(sources);
        }
        List<TopicSummary> ordered = FeedOrdering.order(visible);
        if (ordered.size() > maxVisibleTopicCount) {
            ordered = new ArrayList<>(ordered.subList(0, maxVisibleTopicCount));
        }
        return new Result(ordered, failures, watermark == Long.MIN_VALUE ? 0L : watermark);
    }

    private static SourceState sourceBlockingWatermark(List<SourceState> sources, int maxPagesPerSource) {
        SourceState selected = null;
        long selectedTail = Long.MIN_VALUE;
        for (SourceState source : sources) {
            if (!source.canLoadMore(maxPagesPerSource) || !source.hasTimedTopics()) {
                continue;
            }
            long tail = source.tailTimeMillis();
            if (tail > selectedTail) {
                selected = source;
                selectedTail = tail;
            }
        }
        return selected;
    }

    private static long watermarkMillis(List<SourceState> sources) {
        long watermark = Long.MIN_VALUE;
        for (SourceState source : sources) {
            if (source.exhausted || !source.hasTimedTopics()) {
                continue;
            }
            watermark = Math.max(watermark, source.tailTimeMillis());
        }
        return watermark;
    }

    private static int visibleTimedCount(List<SourceState> sources, long watermark) {
        int count = 0;
        for (TopicSummary topic : visibleTopics(sources, watermark)) {
            if (topic.sortTimeMillis > 0L) {
                count++;
            }
        }
        return count;
    }

    private static List<TopicSummary> visibleTopics(List<SourceState> sources, long watermark) {
        if (watermark == Long.MIN_VALUE) {
            return allTopics(sources);
        }
        Map<String, TopicSummary> byKey = new LinkedHashMap<>();
        for (SourceState source : sources) {
            for (TopicSummary topic : source.topicsByKey.values()) {
                if (topic.sortTimeMillis > 0L && topic.sortTimeMillis < watermark) {
                    continue;
                }
                putNewest(byKey, topic);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static List<TopicSummary> allTopics(List<SourceState> sources) {
        Map<String, TopicSummary> byKey = new LinkedHashMap<>();
        for (SourceState source : sources) {
            for (TopicSummary topic : source.topicsByKey.values()) {
                putNewest(byKey, topic);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static void putNewest(Map<String, TopicSummary> byKey, TopicSummary topic) {
        String key = topic.siteId + ":" + topic.url;
        TopicSummary existing = byKey.get(key);
        if (existing == null || topic.sortTimeMillis >= existing.sortTimeMillis) {
            byKey.put(key, topic);
        }
    }

    private static String concise(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "未知错误";
        }
        String cleaned = message.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        int maxLength = 80;
        if (cleaned.length() > maxLength) {
            return cleaned.substring(0, maxLength) + "...";
        }
        return cleaned;
    }

    public interface PageFetcher {
        List<TopicSummary> fetch(BoardDefinition board, int page) throws Exception;
    }

    public static final class Result {
        public final List<TopicSummary> topics;
        public final List<String> failures;
        public final long watermarkMillis;

        Result(List<TopicSummary> topics, List<String> failures, long watermarkMillis) {
            this.topics = Collections.unmodifiableList(new ArrayList<>(topics));
            this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
            this.watermarkMillis = watermarkMillis;
        }
    }

    private static final class SourceState {
        final BoardDefinition board;
        final Map<String, TopicSummary> topicsByKey = new LinkedHashMap<>();
        int pagesFetched;
        boolean exhausted;

        SourceState(BoardDefinition board) {
            this.board = board;
        }

        boolean canLoadMore(int maxPagesPerSource) {
            return !exhausted && pagesFetched < maxPagesPerSource;
        }

        boolean hasTimedTopics() {
            return tailTimeMillis() != Long.MIN_VALUE;
        }

        long tailTimeMillis() {
            long tail = Long.MAX_VALUE;
            for (TopicSummary topic : topicsByKey.values()) {
                if (topic.sortTimeMillis > 0L) {
                    tail = Math.min(tail, topic.sortTimeMillis);
                }
            }
            return tail == Long.MAX_VALUE ? Long.MIN_VALUE : tail;
        }

        void loadNext(PageFetcher fetcher, int maxPagesPerSource, List<String> failures) {
            if (!canLoadMore(maxPagesPerSource)) {
                return;
            }

            int page = pagesFetched + 1;
            List<TopicSummary> pageTopics;
            try {
                pageTopics = fetcher.fetch(board, page);
            } catch (Exception error) {
                failures.add(board.sourceLabel + " 第 " + page + " 页：" + concise(error.getMessage()));
                exhausted = true;
                return;
            }
            pagesFetched = page;

            if (pageTopics == null || pageTopics.isEmpty()) {
                exhausted = true;
                return;
            }

            int added = 0;
            for (TopicSummary topic : pageTopics) {
                String key = topic.siteId + ":" + topic.url;
                if (!topicsByKey.containsKey(key)) {
                    topicsByKey.put(key, topic);
                    added++;
                } else {
                    putNewest(topicsByKey, topic);
                }
            }

            if (added == 0) {
                exhausted = true;
            }
        }
    }
}
