package dev.bbsfusion;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import dev.bbsfusion.core.ConnectorRegistry;
import dev.bbsfusion.core.FeedCachePolicy;
import dev.bbsfusion.core.SubscriptionGroup;
import dev.bbsfusion.core.SubscriptionStore;
import dev.bbsfusion.core.TimelineFeedAggregator;
import dev.bbsfusion.core.TopicSummary;
import dev.bbsfusion.ui.TopicAdapter;
import dev.bbsfusion.ui.WindowInsetsHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int INITIAL_TOPIC_TARGET = 120;
    private static final int TOPIC_LOAD_STEP = 120;
    private static final int MAX_TOPIC_TARGET = 480;
    private static final int MIN_PAGES_PER_SOURCE = 5;
    private static final int MAX_PAGES_PER_SOURCE = 12;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<TopicSummary> topics = new ArrayList<>();
    private final List<SubscriptionGroup> shortcutGroups = new ArrayList<>();
    private final Map<String, List<TopicSummary>> topicCache = new HashMap<>();
    private final Map<String, Integer> topicTargets = new HashMap<>();
    private final Map<String, Boolean> targetHasMore = new HashMap<>();
    private final Map<String, TimelineFeedAggregator.Session> feedSessions = new HashMap<>();
    private final Map<String, int[]> scrollPositions = new HashMap<>();
    private final Set<String> failedTargets = new HashSet<>();
    private final Set<String> loadedTargets = new HashSet<>();
    private final Set<String> loadingTargets = new HashSet<>();

    private SubscriptionGroup selectedGroup;
    private TopicAdapter adapter;
    private TextView status;
    private final List<Button> shortcutButtons = new ArrayList<>();
    private HorizontalScrollView shortcutScroll;
    private LinearLayout shortcutContainer;
    private Button configButton;
    private Button loginButton;
    private Button refreshButton;
    private Button moreButton;
    private ListView topicListView;
    private float listTouchDownX;
    private float listTouchDownY;
    private boolean userScrolling;
    private boolean destroyed;
    private String renderedKey = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        reloadShortcutGroups();
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof FeedState) {
            FeedState state = (FeedState) retained;
            topicCache.putAll(state.cache);
            topicTargets.putAll(state.targets);
            targetHasMore.putAll(state.hasMore);
            feedSessions.putAll(state.sessions);
            scrollPositions.putAll(state.positions);
            loadedTargets.addAll(state.loaded);
            failedTargets.addAll(state.failed);
            SubscriptionGroup restored = groupById(state.selectedId);
            if (restored != null) { selectedGroup = restored; }
        } else if (savedInstanceState == null) {
            selectedGroup = SubscriptionStore.defaultGroup(this);
        }
        setContentView(createContentView());
        selectGroup(selectedGroup, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadShortcutGroups();
        if (adapter != null) {
            updateShortcutButtonLabels();
            selectGroup(selectedGroup, true);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        saveScrollPosition();
        return new FeedState(this);
    }

    private static final class FeedState {
        final Map<String, List<TopicSummary>> cache;
        final Map<String, Integer> targets;
        final Map<String, Boolean> hasMore;
        final Map<String, TimelineFeedAggregator.Session> sessions;
        final Map<String, int[]> positions;
        final Set<String> loaded;
        final Set<String> failed;
        final String selectedId;

        FeedState(MainActivity activity) {
            cache = new HashMap<>(activity.topicCache);
            targets = new HashMap<>(activity.topicTargets);
            hasMore = new HashMap<>(activity.targetHasMore);
            sessions = new HashMap<>(activity.feedSessions);
            positions = new HashMap<>(activity.scrollPositions);
            loaded = new HashSet<>(activity.loadedTargets);
            failed = new HashSet<>(activity.failedTargets);
            // Let the user explicitly retry requests interrupted by a configuration change.
            failed.addAll(activity.loadingTargets);
            selectedId = activity.selectedGroup == null ? "" : activity.selectedGroup.id;
        }
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 247, 244));
        WindowInsetsHelper.apply(root);

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
        loginButton = makeButton("会话管理");
        loginButton.setOnClickListener(v -> startActivity(new Intent(this, SessionActivity.class)));
        refreshButton = makeButton("刷新");
        refreshButton.setOnClickListener(v -> refreshTopics());

        actions.addView(configButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(loginButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(refreshButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(actions);

        adapter = new TopicAdapter(this, topics);
        topicListView = new ListView(this);
        topicListView.setDividerHeight(1);
        topicListView.setBackgroundColor(Color.rgb(247, 247, 244));
        moreButton = makeButton("加载更多");
        moreButton.setOnClickListener(v -> refreshGroup(selectedGroup, true));
        topicListView.addFooterView(moreButton, null, false);
        topicListView.setAdapter(adapter);
        topicListView.setOnTouchListener(this::handleTopicListTouch);
        topicListView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                userScrolling = scrollState != SCROLL_STATE_IDLE;
            }

            @Override
            public void onScroll(
                    AbsListView view,
                    int firstVisibleItem,
                    int visibleItemCount,
                    int totalItemCount
            ) {
                if (userScrolling && totalItemCount > 1 && firstVisibleItem + visibleItemCount >= totalItemCount - 4) {
                    maybeLoadMoreTopics();
                }
            }
        });
        topicListView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= topics.size()) { return; }
            TopicSummary topic = topics.get(position);
            Intent intent = new Intent(this, TopicActivity.class);
            intent.putExtra(TopicActivity.EXTRA_SITE_ID, topic.siteId);
            intent.putExtra(TopicActivity.EXTRA_TITLE, topic.title);
            intent.putExtra(TopicActivity.EXTRA_URL, topic.url);
            startActivity(intent);
        });

        root.addView(topicListView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        return root;
    }

    private void selectGroup(SubscriptionGroup group, boolean autoRefresh) {
        saveScrollPosition();
        selectedGroup = group;
        if (selectedGroup != null) {
            SubscriptionStore.setSelectedGroupId(this, selectedGroup.id);
        }
        renderSelectedTarget(autoRefresh);
    }

    private void saveScrollPosition() {
        if (!renderedKey.isEmpty() && topicListView != null) {
            View first = topicListView.getChildAt(0);
            if (first != null) {
                scrollPositions.put(renderedKey, new int[]{topicListView.getFirstVisiblePosition(), first.getTop()});
            }
        }
    }

    private void renderSelectedTarget(boolean autoRefresh) {
        if (selectedGroup == null) {
            status.setText("还没有订阅组，请先进入板块配置。");
            return;
        }
        String key = targetKey();
        renderedKey = key;
        topics.clear();
        List<TopicSummary> cached = topicCache.get(key);
        if (cached != null) {
            topics.addAll(cached);
        }
        adapter.notifyDataSetChanged();
        int[] position = scrollPositions.get(key);
        topicListView.setSelectionFromTop(position == null ? 0 : position[0], position == null ? 0 : position[1]);
        updateButtonState();
        updateLoadingControls();
        scrollSelectedShortcutIntoView();

        if (cached != null) {
            status.setText(targetName() + " 已选择，显示上次加载的 " + cached.size() + " 个帖子。");
        } else {
            int boardCount = selectedGroup.boards.size();
            status.setText(selectedGroup.name + " 已选择：" + boardCount + " 个板块。点刷新加载。");
        }
        if (failedTargets.contains(key)) {
            status.setText(targetName() + " 上次加载未完成，点刷新重试。" + (cached == null ? "" : "已保留原列表。"));
        } else if (loadingTargets.contains(key)) {
            status.setText("正在加载 " + targetName() + "...");
        }

        if (autoRefresh && !loadedTargets.contains(key) && !loadingTargets.contains(key) && !failedTargets.contains(key)) {
            refreshTopics();
        }
    }

    private void refreshTopics() {
        refreshGroup(selectedGroup, false);
    }

    private void refreshGroup(SubscriptionGroup group, boolean loadMore) {
        if (destroyed) { return; }
        if (group == null) {
            status.setText("还没有订阅组，请先进入板块配置。");
            return;
        }
        String key = groupKey(group);
        if (loadingTargets.contains(key)) { return; }
        int targetCount = loadMore ? nextTopicTarget(key) : INITIAL_TOPIC_TARGET;
        List<TopicSummary> cached = topicCache.get(key);
        if (loadMore && !failedTargets.contains(key) && cached != null && cached.size() >= MAX_TOPIC_TARGET) {
            targetHasMore.put(key, false);
            updateLoadingControls();
            if (key.equals(targetKey())) {
                status.setText(group.name + " 已加载到当前上限：" + topics.size() + " 个帖子。");
            }
            return;
        }
        loadingTargets.add(key);
        failedTargets.remove(key);
        TimelineFeedAggregator.Session activeSession = loadMore ? feedSessions.get(key) : null;
        if (activeSession == null) {
            activeSession = TimelineFeedAggregator.newSession(group.boards);
            feedSessions.put(key, activeSession);
        }
        final TimelineFeedAggregator.Session session = activeSession;
        updateLoadingControls();
        status.setText(loadMore ? "正在加载更多 " + group.name + "..." : "正在加载 " + group.name + "...");

        executor.execute(() -> {
            try {
                TimelineFeedAggregator.Result result = session.load(
                        (board, page) -> ConnectorRegistry.byId(board.siteId).fetchTopics(board, page),
                        targetCount,
                        pagesForTarget(targetCount),
                        targetCount
                );
                mainHandler.post(() -> applyLoadedTopics(
                        key,
                        group.name,
                        result,
                        targetCount,
                        loadMore
                ));
            } catch (Exception error) {
                mainHandler.post(() -> applyLoadFailure(key, group.name, error.getMessage()));
            }
        });
    }

    private void applyLoadedTopics(
            String key,
            String name,
            TimelineFeedAggregator.Result result,
            int targetCount,
            boolean loadMore
    ) {
        if (destroyed) { return; }
        loadingTargets.remove(key);
        if (!isCurrentConfiguration(key)) { return; }
        boolean failed = !result.failures.isEmpty();
        if (failed) { failedTargets.add(key); } else { failedTargets.remove(key); loadedTargets.add(key); }
        if (!result.topics.isEmpty() || !failed) { topicTargets.put(key, targetCount); }
        targetHasMore.put(key, result.hasMore && result.topics.size() < MAX_TOPIC_TARGET);
        List<TopicSummary> updated = FeedCachePolicy.updated(topicCache.get(key), result.topics, failed);
        if (updated.size() > MAX_TOPIC_TARGET) {
            updated = new ArrayList<>(updated.subList(0, MAX_TOPIC_TARGET));
        }
        topicCache.put(key, updated);
        updateLoadingControls();
        if (!key.equals(targetKey())) {
            return;
        }

        topics.clear();
        topics.addAll(updated);
        adapter.notifyDataSetChanged();
        if (!loadMore && !failed) { topicListView.setSelection(0); }

        if (result.topics.isEmpty() && !result.failures.isEmpty()) {
            status.setText(name + " 加载失败：" + joinFailures(result.failures)
                    + (updated.isEmpty() ? "" : "；已保留原列表。"));
        } else if (result.topics.isEmpty()) {
            status.setText(name + " 未解析到帖子。请先登录，或页面结构需要适配。");
        } else if (!result.failures.isEmpty()) {
            status.setText(name + " 部分加载失败，已保留可读内容：" + joinFailures(result.failures));
        } else if (loadMore && Boolean.FALSE.equals(targetHasMore.get(key))) {
            status.setText(name + " 已加载 " + result.topics.size() + " 个帖子，已到当前末尾。");
        } else if (loadMore) {
            status.setText(name + " 已加载更多：" + result.topics.size() + " 个帖子。");
        } else {
            status.setText(name + " 加载完成：" + result.topics.size() + " 个帖子。");
        }
    }

    private void applyLoadFailure(String key, String name, String message) {
        if (destroyed) { return; }
        loadingTargets.remove(key);
        failedTargets.add(key);
        updateLoadingControls();
        if (!key.equals(targetKey())) {
            return;
        }
        status.setText(name + " 加载失败：" + concise(message));
    }

    private String targetKey() {
        return groupKey(selectedGroup);
    }

    private String groupKey(SubscriptionGroup group) {
        return FeedCachePolicy.key(group);
    }

    private boolean isCurrentConfiguration(String key) {
        for (SubscriptionGroup group : shortcutGroups) {
            if (key.equals(groupKey(group))) { return true; }
        }
        return false;
    }

    private void updateLoadingControls() {
        if (refreshButton == null || moreButton == null) { return; }
        String key = targetKey();
        boolean loading = loadingTargets.contains(key);
        refreshButton.setEnabled(!loading);
        List<TopicSummary> cached = topicCache.get(key);
        moreButton.setVisibility(cached == null || cached.isEmpty() ? View.GONE : View.VISIBLE);
        boolean hasMore = failedTargets.contains(key) || !Boolean.FALSE.equals(targetHasMore.get(key));
        moreButton.setEnabled(!loading && hasMore);
        moreButton.setText(loading ? "正在加载..." : failedTargets.contains(key) ? "重试加载更多"
                : hasMore ? "加载更多" : cached != null && cached.size() >= MAX_TOPIC_TARGET ? "已到当前数量上限" : "已到末尾");
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
        Set<String> valid = new HashSet<>();
        for (SubscriptionGroup group : shortcutGroups) { valid.add(groupKey(group)); }
        topicCache.keySet().retainAll(valid);
        feedSessions.keySet().retainAll(valid);
        topicTargets.keySet().retainAll(valid);
        targetHasMore.keySet().retainAll(valid);
        loadedTargets.retainAll(valid);
        failedTargets.retainAll(valid);
        scrollPositions.keySet().retainAll(valid);
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

    private void maybeLoadMoreTopics() {
        if (selectedGroup == null || topics.isEmpty()) {
            return;
        }
        String key = targetKey();
        if (destroyed || failedTargets.contains(key) || loadingTargets.contains(key) || Boolean.FALSE.equals(targetHasMore.get(key))) {
            return;
        }
        refreshGroup(selectedGroup, true);
    }

    private int currentTopicTarget(String key) {
        Integer current = topicTargets.get(key);
        return current == null ? INITIAL_TOPIC_TARGET : current;
    }

    private int nextTopicTarget(String key) {
        return Math.min(MAX_TOPIC_TARGET, currentTopicTarget(key) + TOPIC_LOAD_STEP);
    }

    private int pagesForTarget(int targetCount) {
        int pages = (targetCount + 79) / 80 + 2;
        return Math.min(MAX_PAGES_PER_SOURCE, Math.max(MIN_PAGES_PER_SOURCE, pages));
    }

    private void selectAdjacentGroup(int direction) {
        if (selectedGroup == null || shortcutGroups.size() <= 1) {
            return;
        }
        int index = selectedGroupIndex();
        int next = index + direction;
        if (index < 0 || next < 0 || next >= shortcutGroups.size()) {
            return;
        }
        selectGroup(shortcutGroups.get(next), true);
    }

    private int selectedGroupIndex() {
        if (selectedGroup == null) {
            return -1;
        }
        for (int i = 0; i < shortcutGroups.size(); i++) {
            if (selectedGroup.id.equals(shortcutGroups.get(i).id)) {
                return i;
            }
        }
        return -1;
    }

    private boolean handleTopicListTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            listTouchDownX = event.getX();
            listTouchDownY = event.getY();
            return false;
        }
        if (event.getAction() != MotionEvent.ACTION_UP) {
            return false;
        }

        float deltaX = event.getX() - listTouchDownX;
        float deltaY = event.getY() - listTouchDownY;
        if (Math.abs(deltaX) >= dp(100) && Math.abs(deltaX) > Math.abs(deltaY) * 1.4f) {
            selectAdjacentGroup(deltaX < 0 ? 1 : -1);
            return true;
        }
        return false;
    }
}
