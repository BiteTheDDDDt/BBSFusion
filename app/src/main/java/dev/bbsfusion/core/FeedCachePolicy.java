package dev.bbsfusion.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FeedCachePolicy {
    private FeedCachePolicy() {
    }

    public static String key(SubscriptionGroup group) {
        if (group == null) { return ""; }
        List<String> boards = new ArrayList<>();
        for (BoardDefinition board : group.boards) {
            boards.add(part(board.key()) + part(board.url) + part(board.referrer) + part(board.sourceLabel));
        }
        Collections.sort(boards);
        return part(group.id) + boards;
    }

    private static String part(String value) {
        return value.length() + ":" + value;
    }

    /** Keep readable content during transient failures; successful refreshes can remove old topics. */
    public static List<TopicSummary> updated(List<TopicSummary> previous, List<TopicSummary> fetched,
                                              boolean hasFailures) {
        if (!hasFailures || previous == null || previous.isEmpty()) {
            return new ArrayList<>(fetched);
        }
        Map<String, TopicSummary> merged = new LinkedHashMap<>();
        for (TopicSummary topic : previous) { merged.put(topic.siteId + ":" + topic.url, topic); }
        for (TopicSummary topic : fetched) { merged.put(topic.siteId + ":" + topic.url, topic); }
        return FeedOrdering.order(new ArrayList<>(merged.values()));
    }
}
