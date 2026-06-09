package dev.bbsfusion.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SubscriptionStore {
    private static final String PREFS_NAME = "bbsfusion_subscriptions";
    private static final String KEY_GROUPS = "groups_v1";
    private static final String KEY_SELECTED_GROUP = "selected_group_id";
    private static final String LEGACY_AGGREGATE_GROUP_ID = "default";
    private static final String S1_GROUP_ID = "builtin_s1";
    private static final String NGA_GROUP_ID = "builtin_nga";
    private static final String V2EX_GROUP_ID = "builtin_v2ex";
    private static final String LINUXDO_GROUP_ID = "builtin_linuxdo";

    private SubscriptionStore() {
    }

    public static List<SubscriptionGroup> loadGroups(Context context) {
        SharedPreferences prefs = prefs(context);
        String raw = prefs.getString(KEY_GROUPS, "");
        if (raw == null || raw.trim().isEmpty()) {
            List<SubscriptionGroup> defaults = defaultGroups();
            saveGroups(context, defaults);
            ensureSelectedGroupExists(context, defaults);
            return defaults;
        }

        try {
            JSONArray array = new JSONArray(raw);
            List<SubscriptionGroup> groups = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                groups.add(SubscriptionGroup.fromJson(array.getJSONObject(i)));
            }
            List<SubscriptionGroup> normalized = normalizeGroups(groups);
            if (normalized != groups) {
                saveGroups(context, normalized);
            }
            ensureSelectedGroupExists(context, normalized);
            return normalized;
        } catch (JSONException ignored) {
            List<SubscriptionGroup> defaults = defaultGroups();
            saveGroups(context, defaults);
            ensureSelectedGroupExists(context, defaults);
            return defaults;
        }
    }

    public static void saveGroups(Context context, List<SubscriptionGroup> groups) {
        JSONArray array = new JSONArray();
        try {
            for (SubscriptionGroup group : groups) {
                array.put(group.toJson());
            }
        } catch (JSONException ignored) {
            return;
        }
        prefs(context).edit().putString(KEY_GROUPS, array.toString()).apply();
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
        if (source.isEmpty()) {
            return defaultGroups();
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

    private static void ensureSelectedGroupExists(Context context, List<SubscriptionGroup> groups) {
        if (groups.isEmpty()) {
            return;
        }
        String selectedId = selectedGroupId(context);
        if (indexOfGroup(groups, selectedId) >= 0) {
            return;
        }
        setSelectedGroupId(context, groups.get(0).id);
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
