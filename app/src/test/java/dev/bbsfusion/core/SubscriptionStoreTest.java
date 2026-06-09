package dev.bbsfusion.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class SubscriptionStoreTest {
    @Test
    public void displayGroupsKeepEveryConfiguredGroup() {
        List<SubscriptionGroup> groups = Arrays.asList(
                group("one"),
                group("two"),
                group("three"),
                group("four")
        );

        List<SubscriptionGroup> displayGroups = SubscriptionStore.displayGroups(groups);

        assertEquals(4, displayGroups.size());
        assertEquals("one", displayGroups.get(0).id);
        assertEquals("two", displayGroups.get(1).id);
        assertEquals("three", displayGroups.get(2).id);
        assertEquals("four", displayGroups.get(3).id);
    }

    @Test
    public void displayGroupsKeepConfiguredOrder() {
        List<SubscriptionGroup> groups = Arrays.asList(
                group("one"),
                group("two"),
                group("three"),
                group("four")
        );

        List<SubscriptionGroup> displayGroups = SubscriptionStore.displayGroups(groups);

        assertEquals("one", displayGroups.get(0).id);
        assertEquals("two", displayGroups.get(1).id);
        assertEquals("three", displayGroups.get(2).id);
        assertEquals("four", displayGroups.get(3).id);
    }

    private static SubscriptionGroup group(String id) {
        return new SubscriptionGroup(id, id, Collections.emptyList());
    }
}
