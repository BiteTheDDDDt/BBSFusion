package dev.bbsfusion.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FeedOrdering {
    private FeedOrdering() {
    }

    public static List<TopicSummary> order(List<TopicSummary> topics) {
        List<TopicSummary> timed = new ArrayList<>();
        Map<String, List<TopicSummary>> untimedBySite = new LinkedHashMap<>();
        for (TopicSummary topic : topics) {
            if (topic.sortTimeMillis > 0L) {
                timed.add(topic);
            } else {
                List<TopicSummary> siteTopics = untimedBySite.get(topic.siteId);
                if (siteTopics == null) {
                    siteTopics = new ArrayList<>();
                    untimedBySite.put(topic.siteId, siteTopics);
                }
                siteTopics.add(topic);
            }
        }

        Collections.sort(timed, (left, right) -> Long.compare(
                right.sortTimeMillis,
                left.sortTimeMillis
        ));
        timed.addAll(roundRobin(untimedBySite));
        return timed;
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
