package dev.bbsfusion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import dev.bbsfusion.core.BoardDefinition;
import dev.bbsfusion.core.ConnectorRegistry;
import dev.bbsfusion.core.FeedOrdering;
import dev.bbsfusion.core.ForumConnector;
import dev.bbsfusion.core.SubscriptionGroup;
import dev.bbsfusion.core.SubscriptionStore;
import dev.bbsfusion.core.TopicSummary;
import dev.bbsfusion.ui.TopicAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<TopicSummary> topics = new ArrayList<>();
    private final List<SubscriptionGroup> shortcutGroups = new ArrayList<>();
    private final Map<String, List<TopicSummary>> topicCache = new HashMap<>();
    private final Set<String> loadedTargets = new HashSet<>();

    private SubscriptionGroup selectedGroup;
    private TopicAdapter adapter;
    private TextView status;
    private final List<Button> shortcutButtons = new ArrayList<>();
    private HorizontalScrollView shortcutScroll;
    private LinearLayout shortcutContainer;
    private Button configButton;
    private Button loginButton;
    private Button refreshButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        reloadShortcutGroups();
        setContentView(createContentView());
        selectGroup(selectedGroup, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadShortcutGroups();
        if (adapter != null) {
            updateShortcutButtonLabels();
            selectGroup(selectedGroup, false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 247, 244));

        TextView title = new TextView(this);
        title.setText("BBSFusion");
        title.setTextColor(Color.rgb(32, 33, 36));
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(16), dp(18), dp(16), dp(4));
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        status = new TextView(this);
        status.setTextColor(Color.rgb(95, 99, 104));
        status.setTextSize(13);
        status.setPadding(dp(16), 0, dp(16), dp(12));
        root.addView(status);

        shortcutScroll = new HorizontalScrollView(this);
        shortcutScroll.setHorizontalScrollBarEnabled(false);
        shortcutScroll.setFillViewport(false);
        shortcutContainer = new LinearLayout(this);
        shortcutContainer.setOrientation(LinearLayout.HORIZONTAL);
        shortcutContainer.setPadding(dp(12), 0, dp(6), dp(8));
        shortcutContainer.setGravity(Gravity.CENTER_VERTICAL);
        shortcutScroll.addView(shortcutContainer, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                dp(52)
        ));
        root.addView(shortcutScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        ));
        updateShortcutButtonLabels();

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(12), 0, dp(12), dp(12));
        actions.setGravity(Gravity.CENTER_VERTICAL);

        configButton = makeButton("板块配置");
        configButton.setOnClickListener(v -> startActivity(new Intent(this, SubscriptionActivity.class)));
        loginButton = makeButton("原站登录");
        loginButton.setOnClickListener(v -> openLogin());
        refreshButton = makeButton("刷新");
        refreshButton.setOnClickListener(v -> refreshTopics());

        actions.addView(configButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(loginButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(refreshButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(actions);

        adapter = new TopicAdapter(this, topics);
        ListView listView = new ListView(this);
        listView.setDividerHeight(1);
        listView.setBackgroundColor(Color.rgb(247, 247, 244));
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            TopicSummary topic = topics.get(position);
            Intent intent = new Intent(this, TopicActivity.class);
            intent.putExtra(TopicActivity.EXTRA_SITE_ID, topic.siteId);
            intent.putExtra(TopicActivity.EXTRA_TITLE, topic.title);
            intent.putExtra(TopicActivity.EXTRA_URL, topic.url);
            startActivity(intent);
        });

        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        return root;
    }

    private void selectGroup(SubscriptionGroup group, boolean autoRefresh) {
        selectedGroup = group;
        if (selectedGroup != null) {
            SubscriptionStore.setSelectedGroupId(this, selectedGroup.id);
        }
        renderSelectedTarget(autoRefresh);
    }

    private void renderSelectedTarget(boolean autoRefresh) {
        if (selectedGroup == null) {
            status.setText("还没有订阅组，请先进入板块配置。");
            return;
        }
        String key = targetKey();
        topics.clear();
        List<TopicSummary> cached = topicCache.get(key);
        if (cached != null) {
            topics.addAll(cached);
        }
        adapter.notifyDataSetChanged();
        updateButtonState();
        scrollSelectedShortcutIntoView();

        if (cached != null) {
            status.setText(targetName() + " 已选择，显示上次加载的 " + cached.size() + " 个帖子。");
        } else {
            int boardCount = selectedGroup.boards.size();
            status.setText(selectedGroup.name + " 已选择：" + boardCount + " 个板块。点刷新加载。");
        }

        if (autoRefresh && !loadedTargets.contains(key)) {
            refreshTopics();
        }
    }

    private void refreshTopics() {
        refreshGroup(selectedGroup);
    }

    private void refreshGroup(SubscriptionGroup group) {
        String key = targetKey();
        refreshButton.setEnabled(false);
        status.setText("正在加载 " + group.name + "...");

        executor.execute(() -> {
            List<TopicSummary> result = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            for (BoardDefinition board : group.boards) {
                try {
                    ForumConnector connector = ConnectorRegistry.byId(board.siteId);
                    result.addAll(connector.fetchTopics(board));
                } catch (Exception error) {
                    failures.add(board.sourceLabel + "：" + concise(error.getMessage()));
                }
            }
            List<TopicSummary> deduped = dedupe(result);
            mainHandler.post(() -> applyLoadedTopics(key, group.name, deduped, failures));
        });
    }

    private void applyLoadedTopics(
            String key,
            String name,
            List<TopicSummary> result,
            List<String> failures
    ) {
        refreshButton.setEnabled(true);
        loadedTargets.add(key);
        topicCache.put(key, new ArrayList<>(result));
        if (!key.equals(targetKey())) {
            return;
        }

        topics.clear();
        topics.addAll(result);
        adapter.notifyDataSetChanged();

        if (result.isEmpty() && !failures.isEmpty()) {
            status.setText(name + " 加载失败：" + joinFailures(failures));
        } else if (result.isEmpty()) {
            status.setText(name + " 未解析到帖子。请先登录，或页面结构需要适配。");
        } else if (!failures.isEmpty()) {
            status.setText(name + " 加载完成：" + result.size() + " 个帖子；部分失败：" + joinFailures(failures));
        } else {
            status.setText(name + " 加载完成：" + result.size() + " 个帖子。");
        }
    }

    private void applyLoadFailure(String key, String name, String message) {
        refreshButton.setEnabled(true);
        loadedTargets.add(key);
        if (!key.equals(targetKey())) {
            return;
        }
        status.setText(name + " 加载失败：" + concise(message));
    }

    private void openLogin() {
        if (selectedGroup == null) {
            status.setText("还没有订阅组，先去板块配置里添加。");
            return;
        }

        List<ForumConnector> connectors = loginConnectorsForGroup();
        if (connectors.isEmpty()) {
            status.setText("当前订阅组没有板块，先去板块配置里添加。");
        } else if (connectors.size() == 1) {
            ForumConnector connector = connectors.get(0);
            OriginalWebActivity.open(this, connector.loginUrl(), connector.name() + " 登录");
        } else {
            String[] labels = new String[connectors.size()];
            for (int i = 0; i < connectors.size(); i++) {
                labels[i] = connectors.get(i).name();
            }
            new AlertDialog.Builder(this)
                    .setTitle("选择登录站点")
                    .setItems(labels, (dialog, which) -> {
                        ForumConnector connector = connectors.get(which);
                        OriginalWebActivity.open(this, connector.loginUrl(), connector.name() + " 登录");
                    })
                    .show();
        }
    }

    private List<ForumConnector> loginConnectorsForGroup() {
        Set<String> siteIds = new HashSet<>();
        List<ForumConnector> connectors = new ArrayList<>();
        for (BoardDefinition board : selectedGroup.boards) {
            if (siteIds.add(board.siteId)) {
                connectors.add(ConnectorRegistry.byId(board.siteId));
            }
        }
        return connectors;
    }

    private List<TopicSummary> dedupe(List<TopicSummary> source) {
        Map<String, TopicSummary> byUrl = new LinkedHashMap<>();
        for (TopicSummary topic : source) {
            String key = topic.siteId + ":" + topic.url;
            TopicSummary existing = byUrl.get(key);
            if (existing == null || topic.sortTimeMillis >= existing.sortTimeMillis) {
                byUrl.put(key, topic);
            }
        }
        return FeedOrdering.order(new ArrayList<>(byUrl.values()));
    }

    private String targetKey() {
        return "group:" + selectedGroup.id;
    }

    private String targetName() {
        return selectedGroup.name;
    }

    private String joinFailures(List<String> failures) {
        StringBuilder builder = new StringBuilder();
        int max = Math.min(2, failures.size());
        for (int i = 0; i < max; i++) {
            if (i > 0) {
                builder.append("；");
            }
            builder.append(failures.get(i));
        }
        if (failures.size() > max) {
            builder.append(" 等 ").append(failures.size()).append(" 个板块");
        }
        return builder.toString();
    }

    private String concise(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "未知错误";
        }
        String cleaned = message.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        int maxLength = 80;
        if (cleaned.length() > maxLength) {
            return cleaned.substring(0, maxLength) + "...";
        }
        return cleaned;
    }

    private void updateButtonState() {
        for (Button button : shortcutButtons) {
            Object tag = button.getTag();
            boolean selected = selectedGroup != null && selectedGroup.id.equals(tag);
            paintButton(button, selected);
        }
        paintButton(configButton, false);
        paintButton(loginButton, false);
        paintButton(refreshButton, false);
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setPadding(dp(6), 0, dp(6), 0);
        return button;
    }

    private void paintButton(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        if (selected) {
            button.setTextColor(Color.WHITE);
            button.setBackgroundColor(Color.rgb(37, 108, 90));
        } else {
            button.setTextColor(Color.rgb(32, 33, 36));
            button.setBackgroundColor(Color.rgb(236, 235, 230));
        }
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private void reloadShortcutGroups() {
        shortcutGroups.clear();
        shortcutGroups.addAll(SubscriptionStore.displayGroups(this));
        selectedGroup = SubscriptionStore.selectedGroup(this);
    }

    private void updateShortcutButtonLabels() {
        if (shortcutContainer == null) {
            return;
        }
        shortcutContainer.removeAllViews();
        shortcutButtons.clear();
        for (SubscriptionGroup group : shortcutGroups) {
            Button button = makeGroupButton(group.name);
            button.setTag(group.id);
            button.setOnClickListener(v -> {
                SubscriptionGroup selected = groupById((String) v.getTag());
                if (selected == null) {
                    status.setText("这个订阅组不存在，请先进入板块配置。");
                    return;
                }
                selectGroup(selected, true);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(44)
            );
            params.rightMargin = dp(6);
            shortcutContainer.addView(button, params);
            shortcutButtons.add(button);
        }
        updateButtonState();
        scrollSelectedShortcutIntoView();
    }

    private Button makeGroupButton(String label) {
        Button button = makeButton(label);
        button.setMinWidth(dp(92));
        button.setMaxWidth(dp(180));
        return button;
    }

    private void scrollSelectedShortcutIntoView() {
        if (shortcutScroll == null || selectedGroup == null) {
            return;
        }
        for (Button button : shortcutButtons) {
            Object tag = button.getTag();
            if (selectedGroup.id.equals(tag)) {
                shortcutScroll.post(() -> scrollShortcutButtonIntoView(button));
                return;
            }
        }
    }

    private void scrollShortcutButtonIntoView(Button button) {
        if (shortcutScroll == null || button.getWidth() <= 0) {
            return;
        }
        int visibleLeft = shortcutScroll.getScrollX();
        int visibleRight = visibleLeft + shortcutScroll.getWidth();
        int margin = dp(12);
        int buttonLeft = button.getLeft();
        int buttonRight = button.getRight();
        if (buttonLeft < visibleLeft + margin) {
            shortcutScroll.smoothScrollTo(Math.max(0, buttonLeft - margin), 0);
        } else if (buttonRight > visibleRight - margin) {
            shortcutScroll.smoothScrollTo(Math.max(0, buttonRight - shortcutScroll.getWidth() + margin), 0);
        }
    }

    private SubscriptionGroup groupById(String groupId) {
        for (SubscriptionGroup group : shortcutGroups) {
            if (group.id.equals(groupId)) {
                return group;
            }
        }
        return null;
    }
}
