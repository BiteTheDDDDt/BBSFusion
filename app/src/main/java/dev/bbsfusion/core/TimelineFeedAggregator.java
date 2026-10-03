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

    public static Result load(
            List<BoardDefinition> boards,
            PageFetcher fetcher,
            int targetTopicCount,
            int maxPagesPerSource,
            int maxVisibleTopicCount
    ) {
        return newSession(boards).load(fetcher, targetTopicCount, maxPagesPerSource, maxVisibleTopicCount);
    }

    public static Session newSession(List<BoardDefinition> boards) {
        return new Session(boards);
    }

    /** Retains page cursors and fetched topics until the user explicitly refreshes the group. */
    public static final class Session {
        private final List<SourceState> sources = new ArrayList<>();

        private Session(List<BoardDefinition> boards) {
            if (boards == null) {
                return;
            }
            Map<String, BoardDefinition> uniqueBoards = new LinkedHashMap<>();
            for (BoardDefinition board : boards) {
                uniqueBoards.put(board.key(), board);
            }
            for (BoardDefinition board : uniqueBoards.values()) {
                sources.add(new SourceState(board));
            }
        }

        /** maxPagesPerSource limits additional requests in this call, not the lifetime of a source. */
        public synchronized Result load(
                PageFetcher fetcher,
                int targetTopicCount,
                int maxPagesPerSource,
                int maxVisibleTopicCount
        ) {
            List<String> failures = new ArrayList<>();
            for (SourceState source : sources) {
                source.beginLoad(maxPagesPerSource);
                if (source.pagesFetched == 0 || source.retryPending) {
                    source.loadNext(fetcher, failures);
                }
            }

            while (true) {
                long watermark = watermarkMillis(sources);
                List<TopicSummary> visible = visibleTopics(sources, watermark);
                if (timedCount(visible) >= targetTopicCount) {
                    break;
                }
                SourceState source = sourceBlockingWatermark(sources, watermark);
                if (source == null && visible.size() < targetTopicCount) {
                    source = untimedSource(sources);
                }
                if (source == null) {
                    break;
                }
                source.loadNext(fetcher, failures);
            }

            long watermark = watermarkMillis(sources);
            List<TopicSummary> ordered = FeedOrdering.order(visibleTopics(sources, watermark));
            int visibleLimit = Math.max(0, maxVisibleTopicCount);
            if (ordered.size() > visibleLimit) {
                ordered = new ArrayList<>(ordered.subList(0, visibleLimit));
            }
            boolean hasMore = allTopics(sources).size() > ordered.size();
            for (SourceState source : sources) {
                // A request budget or a transient failure is not evidence that the site has ended.
                hasMore |= !source.exhausted;
            }
            return new Result(ordered, failures, watermark == Long.MIN_VALUE ? 0L : watermark, hasMore);
        }
    }

    private static SourceState sourceBlockingWatermark(List<SourceState> sources, long watermark) {
        for (SourceState source : sources) {
            if (source.canLoadMore() && source.hasTimedTopics() && source.tailTimeMillis() == watermark) {
                return source;
            }
        }
        return null;
    }

    private static SourceState untimedSource(List<SourceState> sources) {
        for (SourceState source : sources) {
            if (source.canLoadMore() && !source.hasTimedTopics()) {
                return source;
            }
        }
        return null;
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

    private static int timedCount(List<TopicSummary> topics) {
        int count = 0;
        for (TopicSummary topic : topics) {
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
        public final boolean hasMore;

        Result(List<TopicSummary> topics, List<String> failures, long watermarkMillis, boolean hasMore) {
            this.topics = Collections.unmodifiableList(new ArrayList<>(topics));
            this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
            this.watermarkMillis = watermarkMillis;
            this.hasMore = hasMore;
        }
    }

    private static final class SourceState {
        final BoardDefinition board;
        final Map<String, TopicSummary> topicsByKey = new LinkedHashMap<>();
        int pagesFetched;
        boolean exhausted;
        int remainingRequests;
        boolean failedThisLoad;
        boolean retryPending;
        long oldestObservedTimeMillis = Long.MAX_VALUE;

        SourceState(BoardDefinition board) {
            this.board = board;
        }

        void beginLoad(int maxPagesPerSource) {
            remainingRequests = Math.max(0, maxPagesPerSource);
            failedThisLoad = false;
        }

        boolean canLoadMore() {
            return !exhausted && !failedThisLoad && remainingRequests > 0
                    && !Thread.currentThread().isInterrupted();
        }

        boolean hasTimedTopics() {
            return tailTimeMillis() != Long.MIN_VALUE;
        }

        long tailTimeMillis() {
            // A reply can update a duplicate topic on a later page. That must not move
            // an already-covered timeline boundary forward and hide visible topics.
            return oldestObservedTimeMillis == Long.MAX_VALUE ? Long.MIN_VALUE : oldestObservedTimeMillis;
        }

        void loadNext(PageFetcher fetcher, List<String> failures) {
            if (!canLoadMore()) {
                return;
            }

            int page = pagesFetched + 1;
            List<TopicSummary> pageTopics;
            remainingRequests--;
            try {
                pageTopics = fetcher.fetch(board, page);
            } catch (Exception error) {
                failures.add(board.sourceLabel + " 第 " + page + " 页：" + concise(error.getMessage()));
                failedThisLoad = true;
                retryPending = true;
                return;
            }
            pagesFetched = page;
            retryPending = false;

            if (pageTopics == null || pageTopics.isEmpty()) {
                exhausted = true;
                return;
            }

            int added = 0;
            for (TopicSummary topic : pageTopics) {
                if (topic.sortTimeMillis > 0L) {
                    oldestObservedTimeMillis = Math.min(oldestObservedTimeMillis, topic.sortTimeMillis);
                }
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
