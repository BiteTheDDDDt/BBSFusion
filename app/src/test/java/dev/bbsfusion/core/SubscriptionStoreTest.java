package dev.bbsfusion.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class SubscriptionStoreTest {
    @Test
    public void defaultGroupsUseOneEntryPerForum() {
        List<SubscriptionGroup> groups = SubscriptionStore.defaultGroups();

        assertEquals(4, groups.size());
        assertEquals("builtin_s1", groups.get(0).id);
        assertEquals("S1", groups.get(0).name);
        assertEquals("builtin_nga", groups.get(1).id);
        assertEquals("NGA", groups.get(1).name);
        assertEquals("builtin_v2ex", groups.get(2).id);
        assertEquals("V2EX", groups.get(2).name);
        assertEquals("builtin_linuxdo", groups.get(3).id);
        assertEquals("Linux.do", groups.get(3).name);
        for (SubscriptionGroup group : groups) {
            assertFalse(group.boards.isEmpty());
        }
    }

    @Test
    public void normalizeGroupsRemovesLegacyAggregateDefault() {
        List<SubscriptionGroup> groups = new ArrayList<>();
        groups.add(new SubscriptionGroup(
                "default",
                "综合",
                BoardCatalog.merge(BoardCatalog.defaultNgaGroupBoards(), BoardCatalog.defaultS1GroupBoards())
        ));
        groups.add(new SubscriptionGroup("builtin_s1", "S1", BoardCatalog.defaultS1GroupBoards()));
        groups.add(new SubscriptionGroup("builtin_nga", "NGA", BoardCatalog.defaultNgaGroupBoards()));

        List<SubscriptionGroup> normalized = SubscriptionStore.normalizeGroups(groups);

        for (SubscriptionGroup group : normalized) {
            assertFalse("default".equals(group.id));
        }
    }

    @Test
    public void normalizeGroupsKeepsOneEquivalentForumDefault() {
        List<SubscriptionGroup> groups = new ArrayList<>();
        groups.add(new SubscriptionGroup("group_v2ex", "v2ex", BoardCatalog.defaultV2exGroupBoards()));
        groups.add(new SubscriptionGroup("builtin_v2ex", "V2EX", BoardCatalog.defaultV2exGroupBoards()));

        List<SubscriptionGroup> normalized = SubscriptionStore.normalizeGroups(groups);

        assertEquals("group_v2ex", normalized.get(0).id);
        assertEquals("V2EX", normalized.get(0).name);
        assertEquals(0, countGroupId(normalized, "builtin_v2ex"));
    }

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

    private static int countGroupId(List<SubscriptionGroup> groups, String id) {
        int count = 0;
        for (SubscriptionGroup group : groups) {
            if (id.equals(group.id)) {
                count++;
            }
        }
        return count;
    }
}
