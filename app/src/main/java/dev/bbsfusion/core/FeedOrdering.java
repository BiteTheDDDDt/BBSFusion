package dev.bbsfusion.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class FeedOrdering {
    private FeedOrdering() {
    }

    public static List<TopicSummary> order(List<TopicSummary> topics) {
        Map<Long, List<TopicSummary>> timedByMinute = new TreeMap<>(Collections.reverseOrder());
        Map<String, List<TopicSummary>> untimedBySite = new LinkedHashMap<>();
        for (TopicSummary topic : topics) {
            if (topic.sortTimeMillis > 0L) {
                long minute = topic.sortTimeMillis / 60_000L;
                List<TopicSummary> bucket = timedByMinute.get(minute);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    timedByMinute.put(minute, bucket);
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
        for (List<TopicSummary> bucket : timedByMinute.values()) {
            ordered.addAll(orderMinuteBucket(bucket));
        }
        ordered.addAll(roundRobin(untimedBySite));
        return ordered;
    }

    private static List<TopicSummary> orderMinuteBucket(List<TopicSummary> topics) {
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
