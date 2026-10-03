package dev.bbsfusion;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import dev.bbsfusion.core.BoardCatalog;
import dev.bbsfusion.core.BoardDefinition;
import dev.bbsfusion.core.ConnectorRegistry;
import dev.bbsfusion.core.ForumConnector;
import dev.bbsfusion.core.SubscriptionGroup;
import dev.bbsfusion.core.SubscriptionStore;
import dev.bbsfusion.ui.WindowInsetsHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final List<BoardDefinition> filteredBoards = new ArrayList<>();
    private final Set<String> draftBoardKeys = new LinkedHashSet<>();
    private static final String[] SITE_IDS = {"", "s1", "nga", "v2ex", "linuxdo"};

    private Spinner groupSpinner;
    private EditText groupName;
    private EditText boardSearch;
    private Spinner siteSpinner;
    private ArrayAdapter<BoardDefinition> boardAdapter;
    private TextView status;
    private Button refreshCatalogButton;

    private int selectedGroupIndex;
    private boolean changingSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        groups.addAll(SubscriptionStore.loadGroups(this));
        visibleBoards.addAll(SubscriptionStore.loadBoardCatalog(this));
        selectedGroupIndex = selectedGroupIndex();
        if (savedInstanceState != null) {
            String editingId = savedInstanceState.getString("editing_group_id");
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).id.equals(editingId)) {
                    selectedGroupIndex = i;
                    break;
                }
            }
        }
        setContentView(createContentView());
        renderGroupSpinner();
        renderGroupEditor();
        if (savedInstanceState != null) {
            groupName.setText(savedInstanceState.getString("draft_name", selectedGroup().name));
            ArrayList<String> savedKeys = savedInstanceState.getStringArrayList("draft_board_keys");
            if (savedKeys != null) {
                draftBoardKeys.clear();
                draftBoardKeys.addAll(savedKeys);
            }
            boardSearch.setText(savedInstanceState.getString("board_query", ""));
            siteSpinner.setSelection(savedInstanceState.getInt("site_filter", 0));
            renderBoardChecks();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("editing_group_id", selectedGroup().id);
        outState.putString("draft_name", groupName.getText().toString());
        outState.putStringArrayList("draft_board_keys", new ArrayList<>(draftBoardKeys));
        outState.putString("board_query", boardSearch.getText().toString());
        outState.putInt("site_filter", siteSpinner.getSelectedItemPosition());
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        saveCurrentGroup(false, false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 247, 244));
        WindowInsetsHelper.apply(root);

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

        LinearLayout management = new LinearLayout(this);
        management.setOrientation(LinearLayout.VERTICAL);
        ScrollView managementScroll = new ScrollView(this);
        managementScroll.setFillViewport(false);
        managementScroll.addView(management, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout catalog = new LinearLayout(this);
        catalog.setOrientation(LinearLayout.VERTICAL);

        status = new TextView(this);
        status.setTextColor(Color.rgb(95, 99, 104));
        status.setTextSize(13);
        status.setPadding(dp(16), 0, dp(16), dp(10));
        management.addView(status);

        groupSpinner = new Spinner(this);
        groupSpinner.setPadding(dp(12), 0, dp(12), 0);
        management.addView(groupSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        groupName = new EditText(this);
        groupName.setSingleLine(true);
        groupName.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        groupName.setTextSize(16);
        groupName.setHint("订阅组名称");
        groupName.setPadding(dp(16), 0, dp(16), 0);
        management.addView(groupName, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        ));

        LinearLayout groupActions = new LinearLayout(this);
        groupActions.setOrientation(LinearLayout.HORIZONTAL);
        groupActions.setPadding(dp(12), dp(6), dp(12), dp(6));

        Button newGroup = makeButton("新建组");
        newGroup.setOnClickListener(v -> createGroup());
        Button deleteGroup = makeButton("删除组");
        deleteGroup.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("删除订阅组")
                .setMessage("删除“" + selectedGroup().name + "”？组内板块配置将被移除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> deleteGroup())
                .show());
        Button setCurrent = makeButton("默认打开");
        setCurrent.setOnClickListener(v -> saveCurrentGroup(true, true));

        groupActions.addView(newGroup, new LinearLayout.LayoutParams(0, dp(44), 1));
        groupActions.addView(deleteGroup, new LinearLayout.LayoutParams(0, dp(44), 1));
        groupActions.addView(setCurrent, new LinearLayout.LayoutParams(0, dp(44), 1));
        management.addView(groupActions);

        LinearLayout orderActions = new LinearLayout(this);
        orderActions.setOrientation(LinearLayout.HORIZONTAL);
        orderActions.setPadding(dp(12), 0, dp(12), dp(6));

        Button moveLeft = makeButton("左移");
        moveLeft.setOnClickListener(v -> moveSelectedGroup(-1));
        Button moveRight = makeButton("右移");
        moveRight.setOnClickListener(v -> moveSelectedGroup(1));

        orderActions.addView(moveLeft, new LinearLayout.LayoutParams(0, dp(44), 1));
        orderActions.addView(moveRight, new LinearLayout.LayoutParams(0, dp(44), 1));
        management.addView(orderActions);

        LinearLayout boardActions = new LinearLayout(this);
        boardActions.setOrientation(LinearLayout.HORIZONTAL);
        boardActions.setPadding(dp(12), 0, dp(12), dp(8));

        Button save = makeButton("保存");
        save.setOnClickListener(v -> saveCurrentGroup(false, true));
        refreshCatalogButton = makeButton("刷新目录");
        refreshCatalogButton.setOnClickListener(v -> refreshCatalog());

        boardActions.addView(save, new LinearLayout.LayoutParams(0, dp(44), 1));
        boardActions.addView(refreshCatalogButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        management.addView(boardActions);

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setPadding(dp(12), 0, dp(12), dp(4));
        siteSpinner = new Spinner(this);
        ArrayAdapter<String> siteAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"全部站点", "S1", "NGA", "V2EX", "Linux.do"});
        siteAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        siteSpinner.setAdapter(siteAdapter);
        boardSearch = new EditText(this);
        boardSearch.setSingleLine(true);
        boardSearch.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        boardSearch.setTextSize(14);
        boardSearch.setHint("搜索板块名称 / ID");
        filters.addView(siteSpinner, new LinearLayout.LayoutParams(dp(120), dp(48)));
        filters.addView(boardSearch, new LinearLayout.LayoutParams(0, dp(48), 1));
        catalog.addView(filters);

        ListView boardList = new ListView(this);
        boardList.setPadding(dp(12), 0, dp(12), dp(24));
        boardList.setClipToPadding(false);
        boardAdapter = new ArrayAdapter<BoardDefinition>(this, android.R.layout.simple_list_item_1, filteredBoards) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                CheckBox checkBox = convertView instanceof CheckBox ? (CheckBox) convertView : new CheckBox(SubscriptionActivity.this);
                BoardDefinition board = getItem(position);
                checkBox.setOnCheckedChangeListener(null);
                checkBox.setText(board.sourceLabel);
                checkBox.setTextSize(15);
                checkBox.setTextColor(Color.rgb(32, 33, 36));
                checkBox.setPadding(0, dp(8), 0, dp(8));
                checkBox.setChecked(draftBoardKeys.contains(board.key()));
                checkBox.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) {
                        draftBoardKeys.add(board.key());
                    } else {
                        draftBoardKeys.remove(board.key());
                    }
                });
                return checkBox;
            }
        };
        boardList.setAdapter(boardAdapter);
        catalog.addView(boardList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        // Only the finite management controls scroll together. The board ListView remains an
        // independently measured, recycled list even on short windows or with the keyboard open.
        LinearLayout panels = new LinearLayout(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int width = View.MeasureSpec.getSize(widthMeasureSpec);
                int height = View.MeasureSpec.getSize(heightMeasureSpec);
                boolean sideBySide = width >= dp(560) && width > height;
                int orientation = sideBySide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
                if (getOrientation() != orientation) {
                    setOrientation(orientation);
                }
                int managementWidth = sideBySide ? Math.min(dp(340), Math.max(dp(260), Math.round(width * 0.4f)))
                        : LinearLayout.LayoutParams.MATCH_PARENT;
                int managementHeight = sideBySide ? LinearLayout.LayoutParams.MATCH_PARENT
                        : Math.min(dp(344), Math.round(height * 0.55f));
                updatePanelSize(managementScroll, managementWidth, managementHeight, 0);
                updatePanelSize(catalog, sideBySide ? 0 : LinearLayout.LayoutParams.MATCH_PARENT,
                        sideBySide ? LinearLayout.LayoutParams.MATCH_PARENT : 0, 1);
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        panels.addView(managementScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        panels.addView(catalog, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(panels, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        boardSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                renderBoardChecks();
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        siteSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                renderBoardChecks();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        return root;
    }

    private void updatePanelSize(View view, int width, int height, float weight) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        if (params.width != width || params.height != height || params.weight != weight) {
            params.width = width;
            params.height = height;
            params.weight = weight;
            view.setLayoutParams(params);
        }
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
                renderGroupSpinner();
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
        mergeVisibleBoards(group.boards, false);
        draftBoardKeys.clear();
        for (BoardDefinition board : group.boards) {
            draftBoardKeys.add(board.key());
        }
        renderBoardChecks();
        String currentLabel = group.id.equals(SubscriptionStore.defaultGroupId(this))
                ? "启动默认"
                : "非启动默认";
        status.setText(group.name + " 包含 " + group.boards.size()
                + " 个板块；可选 " + visibleBoards.size() + " 个。" + currentLabel + "，离开或切换时自动保存。");
    }

    private void renderBoardChecks() {
        if (boardAdapter == null) {
            return;
        }
        int siteIndex = Math.max(0, siteSpinner.getSelectedItemPosition());
        filteredBoards.clear();
        filteredBoards.addAll(BoardCatalog.filter(visibleBoards, SITE_IDS[siteIndex], boardSearch.getText().toString()));
        boardAdapter.notifyDataSetChanged();
    }

    private void createGroup() {
        saveCurrentGroup(false, false);
        String id = "group_" + System.currentTimeMillis();
        groups.add(new SubscriptionGroup(id, "订阅组 " + (groups.size() + 1), new ArrayList<>()));
        selectedGroupIndex = groups.size() - 1;
        SubscriptionStore.saveGroups(this, groups);
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
        if (removed.id.equals(SubscriptionStore.defaultGroupId(this))) {
            SubscriptionStore.setDefaultGroupId(this, selectedGroup().id);
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
            SubscriptionStore.setDefaultGroupId(this, updated.id);
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
        for (BoardDefinition board : visibleBoards) {
            if (draftBoardKeys.contains(board.key())) {
                selected.add(board);
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
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                try {
                    fetched.addAll(connector.fetchAvailableBoards());
                } catch (Exception error) {
                    failures.add(connector.name() + "：" + concise(error.getMessage()));
                }
            }

            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                refreshCatalogButton.setEnabled(true);
                mergeVisibleBoards(fetched, true);
                SubscriptionStore.saveBoardCatalog(this, visibleBoards);
                renderBoardChecks();
                if (failures.isEmpty()) {
                    status.setText("目录已刷新：" + visibleBoards.size() + " 个可见板块。");
                } else {
                    status.setText("目录已部分刷新：" + visibleBoards.size() + " 个可见板块；" + failures.get(0));
                }
            });
        });
    }

    private void mergeVisibleBoards(List<BoardDefinition> boards, boolean replaceExisting) {
        Map<String, BoardDefinition> merged = new LinkedHashMap<>();
        for (BoardDefinition board : visibleBoards) {
            merged.put(board.key(), board);
        }
        for (BoardDefinition board : boards) {
            if (replaceExisting || !merged.containsKey(board.key())) {
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
