package dev.bbsfusion.core;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public final class FeedCachePolicyTest {
    private BoardDefinition board(String id, String url) {
        return new BoardDefinition("s1", id, id, url, "https://example.test", id);
    }

    @Test public void boardChangesInvalidateCacheEvenWithSameGroupId() {
        SubscriptionGroup original = new SubscriptionGroup("g", "Group", Arrays.asList(board("1", "https://example.test/1")));
        SubscriptionGroup changed = new SubscriptionGroup("g", "Group", Arrays.asList(board("2", "https://example.test/2")));
        assertNotEquals(FeedCachePolicy.key(original), FeedCachePolicy.key(changed));
        SubscriptionGroup newUrl = new SubscriptionGroup("g", "Group", Arrays.asList(board("1", "https://example.test/new")));
        assertNotEquals(FeedCachePolicy.key(original), FeedCachePolicy.key(newUrl));
    }

    @Test public void renamingOrReorderingDoesNotDiscardUnchangedFeed() {
        BoardDefinition a = board("a", "https://example.test/a");
        BoardDefinition b = board("b", "https://example.test/b");
        assertEquals(FeedCachePolicy.key(new SubscriptionGroup("g", "Before", Arrays.asList(a,b))),
                FeedCachePolicy.key(new SubscriptionGroup("g", "After", Arrays.asList(b,a))));
    }

    @Test public void failedRefreshKeepsOldContentAndUpdatesSuccessfulTopics() {
        TopicSummary old = new TopicSummary("s1", "old", "url", "", 1);
        TopicSummary updated = new TopicSummary("s1", "new", "url", "", 2);
        List<TopicSummary> previous = Collections.singletonList(old);
        assertEquals(previous, FeedCachePolicy.updated(previous, Collections.emptyList(), true));
        assertEquals(Collections.singletonList(updated), FeedCachePolicy.updated(previous, Collections.singletonList(updated), true));
        assertTrue(FeedCachePolicy.updated(previous, Collections.emptyList(), false).isEmpty());
    }
}
