package dev.bbsfusion.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public final class SubscriptionStore {
    private static final String PREFS_NAME = "bbsfusion_subscriptions";
    private static final String KEY_GROUPS = "groups_v1";
    private static final String KEY_SELECTED_GROUP = "selected_group_id";
    private static final String DEFAULT_GROUP_ID = "default";
    private static final String S1_GROUP_ID = "builtin_s1";
    private static final String NGA_GROUP_ID = "builtin_nga";

    private SubscriptionStore() {
    }

    public static List<SubscriptionGroup> loadGroups(Context context) {
        SharedPreferences prefs = prefs(context);
        String raw = prefs.getString(KEY_GROUPS, "");
        if (raw == null || raw.trim().isEmpty()) {
            List<SubscriptionGroup> defaults = defaultGroups();
            saveGroups(context, defaults);
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
            return normalized;
        } catch (JSONException ignored) {
            List<SubscriptionGroup> defaults = defaultGroups();
            saveGroups(context, defaults);
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
        return prefs(context).getString(KEY_SELECTED_GROUP, DEFAULT_GROUP_ID);
    }

    public static void setSelectedGroupId(Context context, String groupId) {
        prefs(context).edit().putString(KEY_SELECTED_GROUP, groupId).apply();
    }

    public static SubscriptionGroup defaultGroup() {
        return new SubscriptionGroup(DEFAULT_GROUP_ID, "综合", BoardCatalog.defaultGroupBoards());
    }

    public static List<SubscriptionGroup> displayGroups(Context context) {
        return displayGroups(loadGroups(context));
    }

    static List<SubscriptionGroup> displayGroups(List<SubscriptionGroup> groups) {
        return new ArrayList<>(groups);
    }

    private static List<SubscriptionGroup> defaultGroups() {
        List<SubscriptionGroup> groups = new ArrayList<>();
        groups.add(defaultGroup());
        groups.add(new SubscriptionGroup(S1_GROUP_ID, "S1", BoardCatalog.defaultS1GroupBoards()));
        groups.add(new SubscriptionGroup(NGA_GROUP_ID, "NGA", BoardCatalog.defaultNgaGroupBoards()));
        return groups;
    }

    private static List<SubscriptionGroup> normalizeGroups(List<SubscriptionGroup> source) {
        if (source.isEmpty()) {
            return defaultGroups();
        }

        List<SubscriptionGroup> groups = new ArrayList<>(source);
        boolean changed = false;

        int defaultIndex = indexOfGroup(groups, DEFAULT_GROUP_ID);
        if (defaultIndex < 0) {
            groups.add(0, defaultGroup());
            defaultIndex = 0;
            changed = true;
        } else {
            SubscriptionGroup current = groups.get(defaultIndex);
            if ("默认订阅".equals(current.name)) {
                groups.set(defaultIndex, new SubscriptionGroup(current.id, "综合", current.boards));
                changed = true;
            }
        }

        if (indexOfGroup(groups, S1_GROUP_ID) < 0) {
            groups.add(defaultIndex + 1, new SubscriptionGroup(
                    S1_GROUP_ID,
                    "S1",
                    BoardCatalog.defaultS1GroupBoards()
            ));
            changed = true;
        }

        if (indexOfGroup(groups, NGA_GROUP_ID) < 0) {
            int s1Index = indexOfGroup(groups, S1_GROUP_ID);
            groups.add(s1Index + 1, new SubscriptionGroup(
                    NGA_GROUP_ID,
                    "NGA",
                    BoardCatalog.defaultNgaGroupBoards()
            ));
            changed = true;
        }

        return changed ? groups : source;
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
