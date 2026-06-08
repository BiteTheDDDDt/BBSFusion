package dev.bbsfusion.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FeedOrdering {
    private FeedOrdering() {
    }

    public static List<TopicSummary> order(List<TopicSummary> topics) {
        List<TopicSummary> sorted = new ArrayList<>(topics);
        Collections.sort(sorted, (left, right) -> Long.compare(
                right.sortTimeMillis,
                left.sortTimeMillis
        ));
        return sorted;
    }
}
