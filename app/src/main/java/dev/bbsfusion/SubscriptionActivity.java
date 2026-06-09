package dev.bbsfusion;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import dev.bbsfusion.core.BoardCatalog;
import dev.bbsfusion.core.BoardDefinition;
import dev.bbsfusion.core.ConnectorRegistry;
import dev.bbsfusion.core.ForumConnector;
import dev.bbsfusion.core.SubscriptionGroup;
import dev.bbsfusion.core.SubscriptionStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SubscriptionActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final List<SubscriptionGroup> groups = new ArrayList<>();
    private final List<BoardDefinition> visibleBoards = new ArrayList<>();
    private final Map<String, BoardDefinition> boardByKey = new LinkedHashMap<>();
    private final Map<String, CheckBox> checkBoxes = new HashMap<>();

    private Spinner groupSpinner;
    private EditText groupName;
    private LinearLayout boardsContainer;
    private TextView status;
    private Button refreshCatalogButton;

    private int selectedGroupIndex;
    private boolean changingSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        groups.addAll(SubscriptionStore.loadGroups(this));
        visibleBoards.addAll(BoardCatalog.builtInBoards());
        selectedGroupIndex = selectedGroupIndex();
        setContentView(createContentView());
        renderGroupSpinner();
        renderGroupEditor();
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

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(4));

        Button back = makeButton("返回");
        back.setOnClickListener(v -> finish());
        TextView title = new TextView(this);
        title.setText("板块配置");
        title.setTextColor(Color.rgb(32, 33, 36));
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(8), 0, 0, 0);

        bar.addView(back, new LinearLayout.LayoutParams(dp(88), dp(44)));
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(bar);

        status = new TextView(this);
        status.setTextColor(Color.rgb(95, 99, 104));
        status.setTextSize(13);
        status.setPadding(dp(16), 0, dp(16), dp(10));
        root.addView(status);

        groupSpinner = new Spinner(this);
        groupSpinner.setPadding(dp(12), 0, dp(12), 0);
        root.addView(groupSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        groupName = new EditText(this);
        groupName.setSingleLine(true);
        groupName.setTextSize(16);
        groupName.setHint("订阅组名称");
        groupName.setPadding(dp(16), 0, dp(16), 0);
        root.addView(groupName, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        ));

        LinearLayout groupActions = new LinearLayout(this);
        groupActions.setOrientation(LinearLayout.HORIZONTAL);
        groupActions.setPadding(dp(12), dp(6), dp(12), dp(6));

        Button newGroup = makeButton("新建组");
        newGroup.setOnClickListener(v -> createGroup());
        Button deleteGroup = makeButton("删除组");
        deleteGroup.setOnClickListener(v -> deleteGroup());
        Button setCurrent = makeButton("默认打开");
        setCurrent.setOnClickListener(v -> saveCurrentGroup(true, true));

        groupActions.addView(newGroup, new LinearLayout.LayoutParams(0, dp(44), 1));
        groupActions.addView(deleteGroup, new LinearLayout.LayoutParams(0, dp(44), 1));
        groupActions.addView(setCurrent, new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(groupActions);

        LinearLayout orderActions = new LinearLayout(this);
        orderActions.setOrientation(LinearLayout.HORIZONTAL);
        orderActions.setPadding(dp(12), 0, dp(12), dp(6));

        Button moveLeft = makeButton("左移");
        moveLeft.setOnClickListener(v -> moveSelectedGroup(-1));
        Button moveRight = makeButton("右移");
        moveRight.setOnClickListener(v -> moveSelectedGroup(1));

        orderActions.addView(moveLeft, new LinearLayout.LayoutParams(0, dp(44), 1));
        orderActions.addView(moveRight, new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(orderActions);

        LinearLayout boardActions = new LinearLayout(this);
        boardActions.setOrientation(LinearLayout.HORIZONTAL);
        boardActions.setPadding(dp(12), 0, dp(12), dp(8));

        Button save = makeButton("保存");
        save.setOnClickListener(v -> saveCurrentGroup(false, true));
        refreshCatalogButton = makeButton("刷新目录");
        refreshCatalogButton.setOnClickListener(v -> refreshCatalog());

        boardActions.addView(save, new LinearLayout.LayoutParams(0, dp(44), 1));
        boardActions.addView(refreshCatalogButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(boardActions);

        ScrollView scrollView = new ScrollView(this);
        boardsContainer = new LinearLayout(this);
        boardsContainer.setOrientation(LinearLayout.VERTICAL);
        boardsContainer.setPadding(dp(12), 0, dp(12), dp(24));
        scrollView.addView(boardsContainer);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        return root;
    }

    private void renderGroupSpinner() {
        List<String> names = new ArrayList<>();
        for (SubscriptionGroup group : groups) {
            names.add(group.name);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                names
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (changingSpinner || position == selectedGroupIndex) {
                    return;
                }
                saveCurrentGroup(false, false);
                selectedGroupIndex = position;
                renderGroupEditor();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };

        changingSpinner = true;
        groupSpinner.setOnItemSelectedListener(null);
        groupSpinner.setAdapter(adapter);
        groupSpinner.setSelection(selectedGroupIndex, false);
        groupSpinner.setOnItemSelectedListener(listener);
        changingSpinner = false;
    }

    private void renderGroupEditor() {
        SubscriptionGroup group = selectedGroup();
        groupName.setText(group.name);
        mergeVisibleBoards(group.boards);
        renderBoardChecks();
        String currentLabel = group.id.equals(SubscriptionStore.selectedGroupId(this))
                ? "启动默认"
                : "非启动默认";
        status.setText(group.name + " 包含 " + group.boards.size()
                + " 个板块；可选 " + visibleBoards.size() + " 个。" + currentLabel + "，勾选后保存。");
    }

    private void renderBoardChecks() {
        SubscriptionGroup group = selectedGroup();
        Set<String> selectedKeys = new HashSet<>();
        for (BoardDefinition board : group.boards) {
            selectedKeys.add(board.key());
        }

        boardsContainer.removeAllViews();
        boardByKey.clear();
        checkBoxes.clear();

        for (BoardDefinition board : visibleBoards) {
            boardByKey.put(board.key(), board);
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(board.sourceLabel);
            checkBox.setTextSize(15);
            checkBox.setTextColor(Color.rgb(32, 33, 36));
            checkBox.setChecked(selectedKeys.contains(board.key()));
            checkBox.setPadding(0, dp(8), 0, dp(8));
            checkBoxes.put(board.key(), checkBox);
            boardsContainer.addView(checkBox, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
        }
    }

    private void createGroup() {
        saveCurrentGroup(false, false);
        String id = "group_" + System.currentTimeMillis();
        groups.add(new SubscriptionGroup(id, "订阅组 " + (groups.size() + 1), new ArrayList<>()));
        selectedGroupIndex = groups.size() - 1;
        SubscriptionStore.saveGroups(this, groups);
        SubscriptionStore.setSelectedGroupId(this, id);
        renderGroupSpinner();
        renderGroupEditor();
        status.setText("新订阅组已创建。");
    }

    private void deleteGroup() {
        if (groups.size() <= 1) {
            status.setText("至少保留一个订阅组。");
            return;
        }
        SubscriptionGroup removed = groups.remove(selectedGroupIndex);
        if (selectedGroupIndex >= groups.size()) {
            selectedGroupIndex = groups.size() - 1;
        }
        if (removed.id.equals(SubscriptionStore.selectedGroupId(this))) {
            SubscriptionStore.setSelectedGroupId(this, selectedGroup().id);
        }
        SubscriptionStore.saveGroups(this, groups);
        renderGroupSpinner();
        renderGroupEditor();
        status.setText("订阅组已删除。");
    }

    private void moveSelectedGroup(int direction) {
        if (groups.size() <= 1) {
            status.setText("只有一个订阅组，不能调整顺序。");
            return;
        }
        saveCurrentGroup(false, false);
        int targetIndex = selectedGroupIndex + direction;
        if (targetIndex < 0) {
            status.setText("已经在最左侧。");
            return;
        }
        if (targetIndex >= groups.size()) {
            status.setText("已经在最右侧。");
            return;
        }
        Collections.swap(groups, selectedGroupIndex, targetIndex);
        selectedGroupIndex = targetIndex;
        SubscriptionStore.saveGroups(this, groups);
        renderGroupSpinner();
        renderGroupEditor();
        status.setText(selectedGroup().name + " 已移动到第 " + (selectedGroupIndex + 1)
                + " 位，主页顶部会按这个顺序显示。");
    }

    private void saveCurrentGroup(boolean makeCurrent, boolean refreshSpinner) {
        if (groups.isEmpty() || selectedGroupIndex < 0 || selectedGroupIndex >= groups.size()) {
            return;
        }

        SubscriptionGroup oldGroup = selectedGroup();
        List<BoardDefinition> selectedBoards = selectedBoards();
        String name = groupName.getText().toString().trim();
        if (name.isEmpty()) {
            name = oldGroup.name;
        }

        SubscriptionGroup updated = new SubscriptionGroup(oldGroup.id, name, selectedBoards);
        groups.set(selectedGroupIndex, updated);
        SubscriptionStore.saveGroups(this, groups);
        if (makeCurrent) {
            SubscriptionStore.setSelectedGroupId(this, updated.id);
        }
        if (refreshSpinner) {
            renderGroupSpinner();
        }
        if (makeCurrent) {
            status.setText(updated.name + " 已设为默认打开并保存：" + selectedBoards.size() + " 个板块。");
        } else {
            status.setText(updated.name + " 已保存：" + selectedBoards.size() + " 个板块。");
        }
    }

    private List<BoardDefinition> selectedBoards() {
        List<BoardDefinition> selected = new ArrayList<>();
        for (Map.Entry<String, CheckBox> entry : checkBoxes.entrySet()) {
            if (entry.getValue().isChecked()) {
                BoardDefinition board = boardByKey.get(entry.getKey());
                if (board != null) {
                    selected.add(board);
                }
            }
        }
        return selected;
    }

    private void refreshCatalog() {
        saveCurrentGroup(false, false);
        refreshCatalogButton.setEnabled(false);
        status.setText("正在刷新板块目录...");

        executor.execute(() -> {
            List<BoardDefinition> fetched = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            for (ForumConnector connector : ConnectorRegistry.all()) {
                try {
                    fetched.addAll(connector.fetchAvailableBoards());
                } catch (Exception error) {
                    failures.add(connector.name() + "：" + concise(error.getMessage()));
                }
            }

            mainHandler.post(() -> {
                refreshCatalogButton.setEnabled(true);
                mergeVisibleBoards(fetched);
                renderBoardChecks();
                if (failures.isEmpty()) {
                    status.setText("目录已刷新：" + visibleBoards.size() + " 个可见板块。");
                } else {
                    status.setText("目录已部分刷新：" + visibleBoards.size() + " 个可见板块；" + failures.get(0));
                }
            });
        });
    }

    private void mergeVisibleBoards(List<BoardDefinition> boards) {
        Map<String, BoardDefinition> merged = new LinkedHashMap<>();
        for (BoardDefinition board : visibleBoards) {
            merged.put(board.key(), board);
        }
        for (BoardDefinition board : boards) {
            if (!merged.containsKey(board.key())) {
                merged.put(board.key(), board);
            }
        }
        visibleBoards.clear();
        visibleBoards.addAll(merged.values());
    }

    private int selectedGroupIndex() {
        String selectedId = SubscriptionStore.selectedGroupId(this);
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).id.equals(selectedId)) {
                return i;
            }
        }
        return 0;
    }

    private SubscriptionGroup selectedGroup() {
        if (groups.isEmpty()) {
            return SubscriptionStore.defaultGroup();
        }
        return groups.get(selectedGroupIndex);
    }

    private String concise(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "未知错误";
        }
        String cleaned = message.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        int maxLength = 60;
        if (cleaned.length() > maxLength) {
            return cleaned.substring(0, maxLength) + "...";
        }
        return cleaned;
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(32, 33, 36));
        button.setBackgroundColor(Color.rgb(236, 235, 230));
        button.setPadding(dp(6), 0, dp(6), 0);
        return button;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
