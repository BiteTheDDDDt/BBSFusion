package dev.bbsfusion.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SubscriptionStore {
    private static final String PREFS_NAME = "bbsfusion_subscriptions";
    private static final String KEY_GROUPS = "groups_v1";
    private static final String KEY_SELECTED_GROUP = "selected_group_id";
    private static final String KEY_DEFAULT_GROUP = "default_group_id";
    private static final String KEY_SCHEMA_VERSION = "groups_schema_version";
    private static final int SCHEMA_VERSION = 1;
    private static final String KEY_BOARD_CATALOG = "board_catalog_v1";
    private static final String LEGACY_AGGREGATE_GROUP_ID = "default";
    private static final String S1_GROUP_ID = "builtin_s1";
    private static final String NGA_GROUP_ID = "builtin_nga";
    private static final String V2EX_GROUP_ID = "builtin_v2ex";
    private static final String LINUXDO_GROUP_ID = "builtin_linuxdo";

    private SubscriptionStore() {
    }

    public static List<SubscriptionGroup> loadGroups(Context context) {
        return loadGroups(prefs(context));
    }

    static List<SubscriptionGroup> loadGroups(SharedPreferences prefs) {
        String raw = prefs.getString(KEY_GROUPS, "");
        if (raw == null || raw.trim().isEmpty()) {
            List<SubscriptionGroup> defaults = defaultGroups();
            saveGroups(prefs, defaults);
            ensureSelectedGroupExists(prefs, defaults);
            return defaults;
        }

        try {
            JSONArray array = new JSONArray(raw);
            List<SubscriptionGroup> groups = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                groups.add(SubscriptionGroup.fromJson(array.getJSONObject(i)));
            }
            boolean migrateLegacyDefaults = prefs.getInt(KEY_SCHEMA_VERSION, 0) < SCHEMA_VERSION;
            List<SubscriptionGroup> normalized = repairGroupBoardNames(normalizeGroups(groups, migrateLegacyDefaults));
            if (normalized != groups || migrateLegacyDefaults) {
                saveGroups(prefs, normalized);
            }
            ensureSelectedGroupExists(prefs, normalized, groups);
            return normalized;
        } catch (JSONException ignored) {
            List<SubscriptionGroup> defaults = defaultGroups();
            saveGroups(prefs, defaults);
            ensureSelectedGroupExists(prefs, defaults);
            return defaults;
        }
    }

    public static void saveGroups(Context context, List<SubscriptionGroup> groups) {
        saveGroups(prefs(context), groups);
    }

    static void saveGroups(SharedPreferences prefs, List<SubscriptionGroup> groups) {
        JSONArray array = new JSONArray();
        try {
            for (SubscriptionGroup group : groups) {
                array.put(group.toJson());
            }
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit()
                .putString(KEY_GROUPS, array.toString())
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .apply();
    }

    public static List<BoardDefinition> loadBoardCatalog(Context context) {
        return loadBoardCatalog(prefs(context));
    }

    static List<BoardDefinition> loadBoardCatalog(SharedPreferences prefs) {
        List<BoardDefinition> cached = new ArrayList<>();
        String raw = prefs.getString(KEY_BOARD_CATALOG, "[]");
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < array.length(); i++) {
                cached.add(BoardDefinition.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
            // The built-in directory is still usable if an older cache cannot be read.
        }
        List<BoardDefinition> repaired = repairBoardNames(cached, knownBoards());
        if (repaired != cached) {
            saveBoardCatalog(prefs, repaired);
        }
        return BoardCatalog.merge(BoardCatalog.builtInBoards(), repaired);
    }

    public static void saveBoardCatalog(Context context, List<BoardDefinition> boards) {
        saveBoardCatalog(prefs(context), boards);
    }

    static void saveBoardCatalog(SharedPreferences prefs, List<BoardDefinition> boards) {
        JSONArray array = new JSONArray();
        try {
            for (BoardDefinition board : boards) {
                array.put(board.toJson());
            }
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString(KEY_BOARD_CATALOG, array.toString()).apply();
    }

    private static Map<String, BoardDefinition> knownBoards() {
        Map<String, BoardDefinition> known = new LinkedHashMap<>();
        for (BoardDefinition board : BoardCatalog.builtInBoards()) {
            known.put(board.key(), board);
        }
        return known;
    }

    private static List<SubscriptionGroup> repairGroupBoardNames(List<SubscriptionGroup> groups) {
        Map<String, BoardDefinition> known = knownBoards();
        List<SubscriptionGroup> repaired = new ArrayList<>();
        boolean changed = false;
        for (SubscriptionGroup group : groups) {
            List<BoardDefinition> boards = repairBoardNames(group.boards, known);
            if (boards != group.boards) {
                repaired.add(new SubscriptionGroup(group.id, group.name, boards));
                changed = true;
            } else {
                repaired.add(group);
            }
        }
        return changed ? repaired : groups;
    }

    private static List<BoardDefinition> repairBoardNames(List<BoardDefinition> boards,
                                                          Map<String, BoardDefinition> known) {
        List<BoardDefinition> repaired = new ArrayList<>();
        boolean changed = false;
        for (BoardDefinition board : boards) {
            BoardDefinition canonical = known.get(board.key());
            boolean brokenTitle = board.title.indexOf('\ufffd') >= 0;
            boolean brokenLabel = board.sourceLabel.indexOf('\ufffd') >= 0;
            if (canonical != null && (brokenTitle || brokenLabel)) {
                String title = brokenTitle ? canonical.title : board.title;
                String prefix = canonical.sourceLabel.substring(0, canonical.sourceLabel.length() - canonical.title.length());
                String label = brokenLabel ? prefix + title : board.sourceLabel;
                repaired.add(new BoardDefinition(board.siteId, board.boardId, title, board.url, board.referrer, label));
                changed = true;
            } else {
                repaired.add(board);
            }
        }
        // Repair only names with explicit decoding loss; URLs, subscriptions and user labels stay intact.
        return changed ? repaired : boards;
    }

    public static SubscriptionGroup selectedGroup(Context context) {
        List<SubscriptionGroup> groups = loadGroups(context);
        String selectedId = selectedGroupId(context);
        for (SubscriptionGroup group : groups) {
            if (group.id.equals(selectedId)) {
                return group;
            }
        }
        return groups.get(0);
    }

    public static String selectedGroupId(Context context) {
        return prefs(context).getString(KEY_SELECTED_GROUP, S1_GROUP_ID);
    }

    public static void setSelectedGroupId(Context context, String groupId) {
        prefs(context).edit().putString(KEY_SELECTED_GROUP, groupId).apply();
    }

    public static String defaultGroupId(Context context) {
        return defaultGroupId(prefs(context));
    }

    static String defaultGroupId(SharedPreferences prefs) {
        return prefs.getString(KEY_DEFAULT_GROUP, prefs.getString(KEY_SELECTED_GROUP, S1_GROUP_ID));
    }

    public static void setDefaultGroupId(Context context, String groupId) {
        prefs(context).edit().putString(KEY_DEFAULT_GROUP, groupId).apply();
    }

    public static SubscriptionGroup defaultGroup(Context context) {
        List<SubscriptionGroup> groups = loadGroups(context);
        String defaultId = defaultGroupId(context);
        for (SubscriptionGroup group : groups) {
            if (group.id.equals(defaultId)) {
                return group;
            }
        }
        return groups.get(0);
    }

    public static SubscriptionGroup defaultGroup() {
        return new SubscriptionGroup(S1_GROUP_ID, "S1", BoardCatalog.defaultS1GroupBoards());
    }

    public static List<SubscriptionGroup> displayGroups(Context context) {
        return displayGroups(loadGroups(context));
    }

    static List<SubscriptionGroup> displayGroups(List<SubscriptionGroup> groups) {
        return new ArrayList<>(groups);
    }

    static List<SubscriptionGroup> defaultGroups() {
        List<SubscriptionGroup> groups = new ArrayList<>();
        groups.add(defaultGroup());
        groups.add(new SubscriptionGroup(NGA_GROUP_ID, "NGA", BoardCatalog.defaultNgaGroupBoards()));
        groups.add(new SubscriptionGroup(V2EX_GROUP_ID, "V2EX", BoardCatalog.defaultV2exGroupBoards()));
        groups.add(new SubscriptionGroup(LINUXDO_GROUP_ID, "Linux.do", BoardCatalog.defaultLinuxDoGroupBoards()));
        return groups;
    }

    static List<SubscriptionGroup> normalizeGroups(List<SubscriptionGroup> source) {
        return normalizeGroups(source, false);
    }

    static List<SubscriptionGroup> normalizeGroups(List<SubscriptionGroup> source, boolean migrateLegacyDefaults) {
        if (source.isEmpty()) {
            return defaultGroups();
        }
        if (!migrateLegacyDefaults) {
            return source;
        }

        List<SubscriptionGroup> groups = new ArrayList<>(source);
        boolean changed = false;

        if (removeUnchangedLegacyAggregate(groups)) {
            changed = true;
        }

        if (groups.isEmpty()) {
            return defaultGroups();
        }

        if (ensureForumDefaultGroup(groups, S1_GROUP_ID, "S1", BoardCatalog.defaultS1GroupBoards())) {
            changed = true;
        }
        if (ensureForumDefaultGroup(groups, NGA_GROUP_ID, "NGA", BoardCatalog.defaultNgaGroupBoards())) {
            changed = true;
        }
        if (ensureForumDefaultGroup(groups, V2EX_GROUP_ID, "V2EX", BoardCatalog.defaultV2exGroupBoards())) {
            changed = true;
        }
        if (ensureForumDefaultGroup(groups, LINUXDO_GROUP_ID, "Linux.do", BoardCatalog.defaultLinuxDoGroupBoards())) {
            changed = true;
        }

        return changed ? groups : source;
    }

    private static boolean ensureForumDefaultGroup(
            List<SubscriptionGroup> groups,
            String id,
            String name,
            List<BoardDefinition> boards
    ) {
        int keeperIndex = indexOfDefaultLikeGroup(groups, id, name, boards);
        if (keeperIndex < 0) {
            groups.add(new SubscriptionGroup(id, name, boards));
            return true;
        }

        boolean changed = false;
        SubscriptionGroup keeper = groups.get(keeperIndex);
        if (!name.equals(keeper.name) && isCanonicalizableName(keeper.name, name)) {
            groups.set(keeperIndex, new SubscriptionGroup(keeper.id, name, keeper.boards));
            changed = true;
        }

        for (int i = groups.size() - 1; i >= 0; i--) {
            if (i == keeperIndex) {
                continue;
            }
            SubscriptionGroup group = groups.get(i);
            if (sameBoardSet(group.boards, boards) && isDefaultLikeGroup(group, id, name)) {
                groups.remove(i);
                changed = true;
                if (i < keeperIndex) {
                    keeperIndex--;
                }
            }
        }
        return changed;
    }

    private static boolean removeUnchangedLegacyAggregate(List<SubscriptionGroup> groups) {
        for (int i = 0; i < groups.size(); i++) {
            SubscriptionGroup group = groups.get(i);
            if (isUnchangedLegacyAggregate(group)) {
                groups.remove(i);
                return true;
            }
        }
        return false;
    }

    private static boolean isUnchangedLegacyAggregate(SubscriptionGroup group) {
        if (!LEGACY_AGGREGATE_GROUP_ID.equals(group.id)) {
            return false;
        }
        if (!"综合".equals(group.name) && !"默认订阅".equals(group.name)) {
            return false;
        }
        return sameBoardSet(group.boards, BoardCatalog.defaultGroupBoards())
                || sameBoardSet(group.boards, legacyS1NgaBoards());
    }

    private static List<BoardDefinition> legacyS1NgaBoards() {
        return BoardCatalog.merge(
                BoardCatalog.defaultS1GroupBoards(),
                BoardCatalog.defaultNgaGroupBoards()
        );
    }

    private static int indexOfDefaultLikeGroup(
            List<SubscriptionGroup> groups,
            String id,
            String name,
            List<BoardDefinition> boards
    ) {
        for (int i = 0; i < groups.size(); i++) {
            SubscriptionGroup group = groups.get(i);
            if (sameBoardSet(group.boards, boards) && isDefaultLikeGroup(group, id, name)) {
                return i;
            }
        }
        int explicitIndex = indexOfGroup(groups, id);
        if (explicitIndex >= 0) {
            return explicitIndex;
        }
        return -1;
    }

    private static boolean isDefaultLikeGroup(SubscriptionGroup group, String id, String name) {
        return group.id.equals(id) || isCanonicalizableName(group.name, name);
    }

    private static boolean isCanonicalizableName(String currentName, String canonicalName) {
        return normalizeGroupName(currentName).equals(normalizeGroupName(canonicalName));
    }

    private static String normalizeGroupName(String name) {
        return name.replace(".", "")
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean sameBoardSet(List<BoardDefinition> first, List<BoardDefinition> second) {
        if (first.size() != second.size()) {
            return false;
        }
        Set<String> keys = new HashSet<>();
        for (BoardDefinition board : first) {
            keys.add(board.key());
        }
        for (BoardDefinition board : second) {
            if (!keys.remove(board.key())) {
                return false;
            }
        }
        return keys.isEmpty();
    }

    private static void ensureSelectedGroupExists(SharedPreferences prefs, List<SubscriptionGroup> groups) {
        ensureSelectedGroupExists(prefs, groups, groups);
    }

    private static void ensureSelectedGroupExists(SharedPreferences prefs, List<SubscriptionGroup> groups,
                                                  List<SubscriptionGroup> previousGroups) {
        if (groups.isEmpty()) {
            return;
        }
        String selectedId = prefs.getString(KEY_SELECTED_GROUP, S1_GROUP_ID);
        String defaultId = defaultGroupId(prefs);
        SharedPreferences.Editor editor = prefs.edit();
        if (indexOfGroup(groups, selectedId) < 0) {
            editor.putString(KEY_SELECTED_GROUP, replacementGroupId(selectedId, previousGroups, groups));
        }
        if (indexOfGroup(groups, defaultId) < 0) {
            defaultId = replacementGroupId(defaultId, previousGroups, groups);
        }
        // Pin the migrated default once; later browsing must not change the startup preference.
        editor.putString(KEY_DEFAULT_GROUP, defaultId).apply();
    }

    private static String replacementGroupId(String removedId, List<SubscriptionGroup> previousGroups,
                                              List<SubscriptionGroup> groups) {
        int previousIndex = indexOfGroup(previousGroups, removedId);
        if (previousIndex >= 0) {
            SubscriptionGroup previous = previousGroups.get(previousIndex);
            for (SubscriptionGroup group : groups) {
                if (isCanonicalizableName(previous.name, group.name) && sameBoardSet(previous.boards, group.boards)) {
                    return group.id;
                }
            }
        }
        return groups.get(0).id;
    }

    private static int indexOfGroup(List<SubscriptionGroup> groups, String id) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
