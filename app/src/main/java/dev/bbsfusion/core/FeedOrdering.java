package dev.bbsfusion.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class FeedOrdering {
    private static final long TIMELINE_WINDOW_MILLIS = 10 * 60_000L;

    private FeedOrdering() {
    }

    public static List<TopicSummary> order(List<TopicSummary> topics) {
        long newestTimeMillis = newestTimeMillis(topics);
        Map<Long, List<TopicSummary>> timedByWindow = new TreeMap<>();
        Map<String, List<TopicSummary>> untimedBySite = new LinkedHashMap<>();
        for (TopicSummary topic : topics) {
            if (topic.sortTimeMillis > 0L) {
                long window = (newestTimeMillis - topic.sortTimeMillis) / TIMELINE_WINDOW_MILLIS;
                List<TopicSummary> bucket = timedByWindow.get(window);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    timedByWindow.put(window, bucket);
                }
                bucket.add(topic);
            } else {
                List<TopicSummary> siteTopics = untimedBySite.get(topic.siteId);
                if (siteTopics == null) {
                    siteTopics = new ArrayList<>();
                    untimedBySite.put(topic.siteId, siteTopics);
                }
                siteTopics.add(topic);
            }
        }

        List<TopicSummary> ordered = new ArrayList<>();
        for (List<TopicSummary> bucket : timedByWindow.values()) {
            ordered.addAll(orderTimelineWindow(bucket));
        }
        ordered.addAll(roundRobin(untimedBySite));
        return ordered;
    }

    private static long newestTimeMillis(List<TopicSummary> topics) {
        long newest = 0L;
        for (TopicSummary topic : topics) {
            if (topic.sortTimeMillis > newest) {
                newest = topic.sortTimeMillis;
            }
        }
        return newest;
    }

    private static List<TopicSummary> orderTimelineWindow(List<TopicSummary> topics) {
        Collections.sort(topics, Comparator.comparingLong(
                (TopicSummary topic) -> topic.sortTimeMillis
        ).reversed());

        Map<String, List<TopicSummary>> bySite = new LinkedHashMap<>();
        for (TopicSummary topic : topics) {
            List<TopicSummary> siteTopics = bySite.get(topic.siteId);
            if (siteTopics == null) {
                siteTopics = new ArrayList<>();
                bySite.put(topic.siteId, siteTopics);
            }
            siteTopics.add(topic);
        }
        return roundRobin(bySite);
    }

    private static List<TopicSummary> roundRobin(Map<String, List<TopicSummary>> bySite) {
        List<TopicSummary> result = new ArrayList<>();
        int index = 0;
        boolean added;
        do {
            added = false;
            for (List<TopicSummary> siteTopics : bySite.values()) {
                if (index < siteTopics.size()) {
                    result.add(siteTopics.get(index));
                    added = true;
                }
            }
            index++;
        } while (added);
        return result;
    }
}
