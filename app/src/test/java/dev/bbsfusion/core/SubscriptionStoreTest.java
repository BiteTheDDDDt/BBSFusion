package dev.bbsfusion.core;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

        List<SubscriptionGroup> normalized = SubscriptionStore.normalizeGroups(groups, true);

        for (SubscriptionGroup group : normalized) {
            assertFalse("default".equals(group.id));
        }
    }

    @Test
    public void normalizeGroupsKeepsOneEquivalentForumDefault() {
        List<SubscriptionGroup> groups = new ArrayList<>();
        groups.add(new SubscriptionGroup("group_v2ex", "v2ex", BoardCatalog.defaultV2exGroupBoards()));
        groups.add(new SubscriptionGroup("builtin_v2ex", "V2EX", BoardCatalog.defaultV2exGroupBoards()));

        List<SubscriptionGroup> normalized = SubscriptionStore.normalizeGroups(groups, true);

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
    public void normalLoadsDoNotRestoreDeletedBuiltInGroups() {
        List<SubscriptionGroup> groups = SubscriptionStore.defaultGroups();
        groups.remove(1);

        List<SubscriptionGroup> normalized = SubscriptionStore.normalizeGroups(groups);

        assertEquals(3, normalized.size());
        assertEquals(0, countGroupId(normalized, "builtin_nga"));
    }

    @Test
    public void legacyDefaultsAreAddedOnlyDuringMigration() {
        List<SubscriptionGroup> oldGroups = Collections.singletonList(group("custom"));
        List<SubscriptionGroup> migrated = SubscriptionStore.normalizeGroups(oldGroups, true);
        assertEquals(5, migrated.size());
        migrated.removeIf(group -> group.id.startsWith("builtin_"));

        List<SubscriptionGroup> reloaded = SubscriptionStore.normalizeGroups(migrated, false);

        assertEquals(1, reloaded.size());
        assertEquals("custom", reloaded.get(0).id);
    }

    @Test
    public void normalLoadsPreserveUserNamesAndEquivalentGroups() {
        List<SubscriptionGroup> groups = Arrays.asList(
                new SubscriptionGroup("first", "v2ex", BoardCatalog.defaultV2exGroupBoards()),
                new SubscriptionGroup("second", "V2EX", BoardCatalog.defaultV2exGroupBoards())
        );

        List<SubscriptionGroup> normalized = SubscriptionStore.normalizeGroups(groups);

        assertEquals(2, normalized.size());
        assertEquals("v2ex", normalized.get(0).name);
        assertEquals("V2EX", normalized.get(1).name);
    }

    @Test
    public void persistedDeletionSurvivesMultipleReloads() {
        SharedPreferences prefs = inMemoryPreferences();
        List<SubscriptionGroup> groups = SubscriptionStore.loadGroups(prefs);
        groups.removeIf(group -> group.id.equals("builtin_nga"));
        SubscriptionStore.saveGroups(prefs, groups);

        assertEquals(3, SubscriptionStore.loadGroups(prefs).size());
        assertEquals(0, countGroupId(SubscriptionStore.loadGroups(prefs), "builtin_nga"));
    }

    @Test
    public void startupDefaultIsMigratedOnceAndDoesNotFollowCurrentGroup() {
        SharedPreferences prefs = inMemoryPreferences();
        prefs.edit().putString("selected_group_id", "builtin_nga").apply();
        SubscriptionStore.loadGroups(prefs);
        assertEquals("builtin_nga", SubscriptionStore.defaultGroupId(prefs));

        prefs.edit().putString("selected_group_id", "builtin_s1").apply();
        SubscriptionStore.loadGroups(prefs);

        assertEquals("builtin_nga", SubscriptionStore.defaultGroupId(prefs));
        assertEquals("builtin_s1", prefs.getString("selected_group_id", ""));
    }

    @Test
    public void legacyMigrationKeepsTheSelectedForumWhenEquivalentGroupsAreMerged() {
        SharedPreferences prefs = inMemoryPreferences();
        SubscriptionStore.saveGroups(prefs, Arrays.asList(
                group("unrelated"),
                new SubscriptionGroup("my_nga", "NGA", BoardCatalog.defaultNgaGroupBoards()),
                new SubscriptionGroup("builtin_nga", "NGA", BoardCatalog.defaultNgaGroupBoards())
        ));
        prefs.edit().putInt("groups_schema_version", 0)
                .putString("selected_group_id", "builtin_nga").apply();

        SubscriptionStore.loadGroups(prefs);

        assertEquals("my_nga", prefs.getString("selected_group_id", ""));
        assertEquals("my_nga", SubscriptionStore.defaultGroupId(prefs));
    }

    @Test
    public void reloadRepairsDeletedCurrentAndDefaultSelections() {
        SharedPreferences prefs = inMemoryPreferences();
        SubscriptionStore.loadGroups(prefs);
        prefs.edit().putString("selected_group_id", "builtin_nga")
                .putString("default_group_id", "builtin_v2ex").apply();
        SubscriptionStore.saveGroups(prefs, Collections.singletonList(group("custom")));

        List<SubscriptionGroup> reloaded = SubscriptionStore.loadGroups(prefs);

        assertEquals(1, reloaded.size());
        assertEquals("custom", prefs.getString("selected_group_id", ""));
        assertEquals("custom", SubscriptionStore.defaultGroupId(prefs));
    }

    @Test
    public void repairsPersistedLossyBoardNamesWithoutResettingTheSubscription() {
        SharedPreferences prefs = inMemoryPreferences();
        List<BoardDefinition> boards = new ArrayList<>();
        for (BoardDefinition board : BoardCatalog.defaultS1GroupBoards()) {
            boards.add(new BoardDefinition(board.siteId, board.boardId, legacyMojibake(board.title),
                    "https://custom.example/board/" + board.boardId, "https://custom.example/referrer",
                    legacyMojibake(board.sourceLabel)));
        }
        assertEquals("\ufffd\ufffd\u03f7\ufffd\ufffd\u0333", boards.get(1).title);
        SubscriptionStore.saveGroups(prefs, Collections.singletonList(new SubscriptionGroup("custom", "我的阅读组", boards)));
        prefs.edit().putString("selected_group_id", "custom").putString("default_group_id", "custom").apply();

        List<SubscriptionGroup> restored = SubscriptionStore.loadGroups(prefs);

        assertEquals(1, restored.size());
        SubscriptionGroup group = restored.get(0);
        assertEquals("custom", group.id);
        assertEquals("我的阅读组", group.name);
        assertEquals(3, group.boards.size());
        assertEquals("卓明谷", group.boards.get(0).title);
        assertEquals("游戏论坛", group.boards.get(1).title);
        assertEquals("S1 游戏论坛", group.boards.get(1).sourceLabel);
        assertEquals("动漫论坛", group.boards.get(2).title);
        assertEquals("https://custom.example/board/4", group.boards.get(1).url);
        assertEquals("https://custom.example/referrer", group.boards.get(1).referrer);
        assertEquals("custom", SubscriptionStore.defaultGroupId(prefs));
        String repairedJson = prefs.getString("groups_v1", "");
        assertFalse(repairedJson.contains("\ufffd"));
        SubscriptionStore.loadGroups(prefs);
        assertEquals(repairedJson, prefs.getString("groups_v1", ""));
    }

    @Test
    public void boardNameRepairPreservesValidCustomFieldsAndUnknownBoards() {
        SharedPreferences prefs = inMemoryPreferences();
        BoardDefinition valid = new BoardDefinition("s1", "4", "我的游戏区", "custom:game", "custom:ref", "自定义来源");
        BoardDefinition badLabel = new BoardDefinition("s1", "6", "我的动漫区", "custom:anime", "custom:ref", "S1 \ufffd");
        BoardDefinition badTitle = new BoardDefinition("s1", "157", "\ufffd", "custom:water", "custom:ref", "保留有效来源");
        BoardDefinition unknown = new BoardDefinition("s1", "unknown", "\ufffd", "custom:unknown", "custom:ref", "\ufffd");
        SubscriptionStore.saveGroups(prefs, Collections.singletonList(new SubscriptionGroup(
                "mine", "自定义组名", Arrays.asList(valid, badLabel, badTitle, unknown))));

        List<BoardDefinition> restored = SubscriptionStore.loadGroups(prefs).get(0).boards;

        assertEquals("我的游戏区", restored.get(0).title);
        assertEquals("自定义来源", restored.get(0).sourceLabel);
        assertEquals("custom:game", restored.get(0).url);
        assertEquals("我的动漫区", restored.get(1).title);
        assertEquals("S1 我的动漫区", restored.get(1).sourceLabel);
        assertEquals("卓明谷", restored.get(2).title);
        assertEquals("保留有效来源", restored.get(2).sourceLabel);
        assertEquals("\ufffd", restored.get(3).title);
        assertEquals("\ufffd", restored.get(3).sourceLabel);
    }

    @Test
    public void repairsLegacyDefaultBoardNamesAcrossAllFourSites() {
        SharedPreferences prefs = inMemoryPreferences();
        List<SubscriptionGroup> defaults = SubscriptionStore.defaultGroups();
        List<SubscriptionGroup> broken = new ArrayList<>();
        for (SubscriptionGroup group : defaults) {
            List<BoardDefinition> boards = new ArrayList<>();
            for (BoardDefinition board : group.boards) {
                boards.add(new BoardDefinition(board.siteId, board.boardId, legacyMojibake(board.title),
                        board.url, board.referrer, legacyMojibake(board.sourceLabel)));
            }
            broken.add(new SubscriptionGroup(group.id, group.name, boards));
        }
        SubscriptionStore.saveGroups(prefs, broken);

        List<SubscriptionGroup> restored = SubscriptionStore.loadGroups(prefs);

        assertEquals(4, restored.size());
        for (int groupIndex = 0; groupIndex < defaults.size(); groupIndex++) {
            List<BoardDefinition> expected = defaults.get(groupIndex).boards;
            List<BoardDefinition> actual = restored.get(groupIndex).boards;
            assertEquals(expected.size(), actual.size());
            for (int boardIndex = 0; boardIndex < expected.size(); boardIndex++) {
                assertEquals(expected.get(boardIndex).title, actual.get(boardIndex).title);
                assertEquals(expected.get(boardIndex).sourceLabel, actual.get(boardIndex).sourceLabel);
                assertEquals(expected.get(boardIndex).url, actual.get(boardIndex).url);
            }
        }
    }

    @Test
    public void repairsCorruptCatalogNamesBeforeTheyCanOverrideTheBuiltInDirectory() {
        SharedPreferences prefs = inMemoryPreferences();
        BoardDefinition original = BoardCatalog.defaultS1GroupBoards().get(1);
        SubscriptionStore.saveBoardCatalog(prefs, Collections.singletonList(new BoardDefinition(
                original.siteId, original.boardId, legacyMojibake(original.title),
                "https://custom.example/game", "https://custom.example/", legacyMojibake(original.sourceLabel))));

        List<BoardDefinition> catalog = SubscriptionStore.loadBoardCatalog(prefs);
        BoardDefinition restored = BoardCatalog.filter(catalog, "s1", "游戏论坛").get(0);

        assertEquals("s1:4", restored.key());
        assertEquals("S1 游戏论坛", restored.sourceLabel);
        assertEquals("https://custom.example/game", restored.url);
        assertFalse(prefs.getString("board_catalog_v1", "").contains("\ufffd"));
        assertTrue(prefs.getString("board_catalog_v1", "").contains("游戏论坛"));
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

    private static String legacyMojibake(String value) {
        return new String(value.getBytes(Charset.forName("GBK")), StandardCharsets.UTF_8);
    }

    private static SharedPreferences inMemoryPreferences() {
        Map<String, Object> values = new HashMap<>();
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "putString":
                        case "putInt":
                            values.put((String) args[0], args[1]);
                            return proxy;
                        case "apply":
                            return null;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                }
        );
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getString":
                        case "getInt":
                            return values.getOrDefault((String) args[0], args[1]);
                        case "edit":
                            return editor;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                }
        );
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
