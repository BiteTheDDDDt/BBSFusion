package dev.bbsfusion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;
import android.util.LruCache;
import android.webkit.CookieManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import dev.bbsfusion.core.ConnectorRegistry;
import dev.bbsfusion.core.ForumConnector;
import dev.bbsfusion.core.Post;
import dev.bbsfusion.core.TopicDetail;
import dev.bbsfusion.core.ImageRequestPolicy;
import dev.bbsfusion.core.ImageLoadFailure;
import dev.bbsfusion.ui.WindowInsetsHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TopicActivity extends Activity {
    public static final String EXTRA_SITE_ID = "site_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_URL = "url";
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final int MAX_BITMAP_SIDE = 2048;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, FutureTask<ImageResult>> imageRequests = new ConcurrentHashMap<>();
    private final Set<HttpURLConnection> imageConnections = ConcurrentHashMap.newKeySet();
    private BindingToken bindingToken;

    private final List<Post> posts = new ArrayList<>();
    private final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getAllocationByteCount();
        }
    };
    private ListView postsList;
    private BaseAdapter postAdapter;
    private TextView titleView;
    private TextView statusView;
    private Button loadMoreButton;
    private Button retryButton;
    private volatile boolean destroyed;
    private boolean pageFailed;
    private boolean userScrolling;
    private boolean restoringPosition;
    private int requestGeneration;
    private volatile int imageGeneration;

    private ForumConnector connector;
    private String topicUrl;
    private String initialTitle;
    private int loadedPage = 1;
    private int failedPage = 1;
    private boolean failedAppend;
    private boolean isLoadingPage;
    private boolean hasMorePages;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String siteId = intent.getStringExtra(EXTRA_SITE_ID);
        initialTitle = intent.getStringExtra(EXTRA_TITLE);
        topicUrl = intent.getStringExtra(EXTRA_URL);
        connector = ConnectorRegistry.byId(siteId == null ? "s1" : siteId);

        setContentView(createContentView());
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof ReadingState) {
            ReadingState state = (ReadingState) retained;
            posts.addAll(state.posts);
            loadedPage = state.page;
            hasMorePages = state.hasMore;
            pageFailed = state.retryNeeded;
            failedPage = state.retryPage;
            failedAppend = state.retryAppend;
            retryButton.setText(pageFailed ? "重试" : "刷新");
            titleView.setText(state.title);
            postAdapter.notifyDataSetChanged();
            updateLoadMoreButton();
            restoringPosition = true;
            postsList.setSelectionFromTop(state.position, state.offset);
            postsList.post(() -> restoringPosition = false);
            statusView.setText(pageFailed
                    ? "已恢复阅读位置，上次加载未完成，点击重试。"
                    : "已恢复阅读位置，共 " + posts.size() + " 段内容。");
        } else {
            loadTopic();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        requestGeneration++;
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        imageExecutor.shutdownNow();
        for (FutureTask<ImageResult> request : imageRequests.values()) { request.cancel(true); }
        for (HttpURLConnection connection : imageConnections) { connection.disconnect(); }
        imageCache.evictAll();
        super.onDestroy();
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        View first = postsList.getChildAt(0);
        return new ReadingState(posts, loadedPage, hasMorePages, titleView.getText().toString(),
                postsList.getFirstVisiblePosition(), first == null ? 0 : first.getTop(),
                pageFailed || isLoadingPage, failedPage, failedAppend);
    }

    private static final class ReadingState {
        final List<Post> posts;
        final int page;
        final boolean hasMore;
        final String title;
        final int position;
        final int offset;
        final boolean retryNeeded;
        final int retryPage;
        final boolean retryAppend;

        ReadingState(List<Post> posts, int page, boolean hasMore, String title, int position, int offset,
                boolean retryNeeded, int retryPage, boolean retryAppend) {
            this.posts = new ArrayList<>(posts);
            this.page = page;
            this.hasMore = hasMore;
            this.title = title;
            this.position = position;
            this.offset = offset;
            this.retryNeeded = retryNeeded;
            this.retryPage = retryPage;
            this.retryAppend = retryAppend;
        }
    }

    private static final class BindingToken {
        volatile boolean active = true;
    }

    private boolean active(BindingToken token) {
        return !destroyed && (token == null || token.active);
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 247, 244));
        WindowInsetsHelper.apply(root);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));

        Button back = makeButton("返回");
        back.setOnClickListener(v -> finish());
        Button open = makeButton("原站");
        open.setOnClickListener(v -> OriginalWebActivity.open(this, topicUrl, connector.name()));
        retryButton = makeButton("刷新");
        retryButton.setOnClickListener(v -> {
            if (pageFailed) {
                loadTopicPage(failedPage, failedAppend);
            } else {
                loadTopic();
            }
        });

        bar.addView(back, new LinearLayout.LayoutParams(dp(88), dp(44)));
        bar.addView(open, new LinearLayout.LayoutParams(dp(88), dp(44)));
        bar.addView(retryButton, new LinearLayout.LayoutParams(dp(88), dp(44)));
        root.addView(bar);

        titleView = new TextView(this);
        titleView.setText(initialTitle == null ? "帖子详情" : initialTitle);
        titleView.setTextColor(Color.rgb(32, 33, 36));
        titleView.setTextSize(20);
        titleView.setPadding(dp(16), dp(8), dp(16), dp(6));
        titleView.setMaxLines(4);
        titleView.setTextIsSelectable(true);
        root.addView(titleView);

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(95, 99, 104));
        statusView.setTextSize(13);
        statusView.setPadding(dp(16), 0, dp(16), dp(12));
        root.addView(statusView);

        postsList = new ListView(this);
        postsList.setDividerHeight(1);
        postsList.setPadding(0, 0, 0, dp(24));
        postAdapter = new BaseAdapter() {
            @Override public int getCount() { return posts.size(); }
            @Override public Post getItem(int position) { return posts.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return createPostView(getItem(position), position + 1, convertView);
            }
        };
        postsList.setAdapter(postAdapter);
        postsList.setRecyclerListener(view -> {
            if (view.getTag() instanceof BindingToken) { ((BindingToken) view.getTag()).active = false; }
        });
        postsList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                userScrolling = state != SCROLL_STATE_IDLE;
            }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                if (userScrolling && !restoringPosition && total > 0 && first + visible >= total - 2) {
                    maybeAutoLoadMore();
                }
            }
        });

        root.addView(postsList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        loadMoreButton = makeButton("加载更多");
        loadMoreButton.setVisibility(View.GONE);
        loadMoreButton.setEnabled(false);
        loadMoreButton.setOnClickListener(v -> loadNextPage());
        root.addView(loadMoreButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
        ));

        return root;
    }

    private void loadTopic() {
        if (isLoadingPage || destroyed) { return; }
        imageGeneration++;
        imageCache.evictAll();
        loadTopicPage(1, false);
    }

    private void loadTopicPage(int page, boolean append) {
        if (destroyed || isLoadingPage) {
            return;
        }
        isLoadingPage = true;
        pageFailed = false;
        failedPage = page;
        failedAppend = append;
        int generation = ++requestGeneration;
        retryButton.setEnabled(false);
        updateLoadMoreButton();
        statusView.setText("正在加载 " + connector.name() + " 帖子...");
        executor.execute(() -> {
            try {
                TopicDetail detail = connector.fetchTopicPage(topicUrl, page);
                mainHandler.post(() -> {
                    if (destroyed || generation != requestGeneration) { return; }
                    isLoadingPage = false;
                    retryButton.setEnabled(true);
                    retryButton.setText("刷新");
                    if (append) {
                        appendTopic(detail);
                    } else {
                        renderTopic(detail);
                    }
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (destroyed || generation != requestGeneration) { return; }
                    isLoadingPage = false;
                    pageFailed = true;
                    failedPage = page;
                    failedAppend = append;
                    retryButton.setEnabled(true);
                    retryButton.setText("重试");
                    updateLoadMoreButton();
                    statusView.setText("加载失败：" + error.getMessage());
                });
            }
        });
    }

    private void renderTopic(TopicDetail detail) {
        titleView.setText(detail.title);
        posts.clear();
        posts.addAll(detail.posts);
        postAdapter.notifyDataSetChanged();
        loadedPage = detail.pageNumber;
        hasMorePages = detail.hasMore;
        updateLoadMoreButton();
        statusView.setText("已加载 " + detail.posts.size() + " 段内容。");
        postsList.setSelection(0);
    }

    private void appendTopic(TopicDetail detail) {
        posts.addAll(detail.posts);
        postAdapter.notifyDataSetChanged();
        loadedPage = detail.pageNumber;
        hasMorePages = detail.hasMore && !detail.posts.isEmpty();
        updateLoadMoreButton();
        statusView.setText("已加载到第 " + loadedPage + " 页，新增 " + detail.posts.size() + " 段内容。");
    }

    private void updateLoadMoreButton() {
        if (loadMoreButton == null) {
            return;
        }
        loadMoreButton.setVisibility(hasMorePages ? View.VISIBLE : View.GONE);
        loadMoreButton.setEnabled(!isLoadingPage);
        loadMoreButton.setText(isLoadingPage ? "正在加载更多..." : pageFailed ? "重试加载" : "加载更多");
    }

    private void maybeAutoLoadMore() {
        if (!destroyed && !pageFailed && hasMorePages && !isLoadingPage) {
            loadNextPage();
        }
    }

    private void loadNextPage() {
        if (hasMorePages && !isLoadingPage) {
            loadTopicPage(pageFailed ? failedPage : loadedPage + 1, pageFailed ? failedAppend : true);
        }
    }

    private View createPostView(Post post, int floorNumber, View recycled) {
        LinearLayout row = recycled instanceof LinearLayout ? (LinearLayout) recycled : new LinearLayout(this);
        if (row.getTag() instanceof BindingToken) { ((BindingToken) row.getTag()).active = false; }
        bindingToken = new BindingToken();
        row.setTag(bindingToken);
        row.removeAllViews();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(12));

        FrameLayout avatarFrame = makeAvatarFrame(post.avatarUrl);
        row.addView(avatarFrame, new LinearLayout.LayoutParams(dp(42), dp(42)));
        if (isHttpUrl(post.avatarUrl) && !isDefaultAvatarUrl(post.avatarUrl)) {
            ImageView avatarView = makeRemoteImageView();
            avatarFrame.addView(avatarView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));
            loadRemoteImage(avatarView, post.avatarUrl, dp(42), dp(42), false);
        }

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(dp(12), 0, 0, 0);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView authorView = new TextView(this);
        authorView.setText(post.author);
        authorView.setTextColor(Color.rgb(37, 108, 90));
        authorView.setTextSize(13);
        authorView.setMaxLines(1);
        headerRow.addView(authorView, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));

        if (floorNumber > 0) {
            TextView floorView = new TextView(this);
            floorView.setText(floorLabel(floorNumber));
            floorView.setTextColor(Color.rgb(117, 117, 117));
            floorView.setTextSize(12);
            floorView.setGravity(Gravity.END);
            floorView.setPadding(dp(8), 0, 0, 0);
            headerRow.addView(floorView);
        }

        headerRow.setPadding(0, 0, 0, dp(2));
        textColumn.addView(headerRow);

        addMetaViews(textColumn, post);

        if (!post.replyContext.isEmpty()) {
            TextView replyView = new TextView(this);
            replyView.setText(post.replyContext);
            replyView.setTextColor(Color.rgb(83, 83, 83));
            replyView.setTextSize(13);
            replyView.setLineSpacing(0, 1.05f);
            replyView.setTextIsSelectable(true);
            replyView.setBackgroundColor(Color.rgb(238, 237, 232));
            replyView.setPadding(dp(8), dp(6), dp(8), dp(6));
            LinearLayout.LayoutParams replyParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            replyParams.bottomMargin = dp(8);
            textColumn.addView(replyView, replyParams);
        }

        TextView contentView = new TextView(this);
        contentView.setText(spannablePostContent(post, contentView));
        contentView.setTextColor(Color.rgb(32, 33, 36));
        contentView.setTextSize(16);
        contentView.setLineSpacing(0, 1.05f);
        contentView.setTextIsSelectable(true);
        textColumn.addView(contentView);

        for (String imageUrl : post.imageUrls) {
            if (!isHttpUrl(imageUrl)) {
                continue;
            }
            ImageView postImageView = makeRemoteImageView();
            FrameLayout imageFrame = new FrameLayout(this);
            imageFrame.addView(postImageView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, dp(160)));
            ImageLoadUi imageUi = new ImageLoadUi(postImageView, imageUrl,
                    contentImageWidth(), dp(420), true, bindingToken, false);
            imageFrame.addView(imageUi.container, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            imageParams.topMargin = dp(8);
            textColumn.addView(imageFrame, imageParams);
            imageUi.load();
            postImageView.setOnClickListener(v -> showImagePreview(imageUrl));
        }

        row.addView(textColumn, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));
        bindingToken = null;
        return row;
    }

    private String floorLabel(int floorNumber) {
        return floorNumber + "楼";
    }

    private FrameLayout makeAvatarFrame(String avatarUrl) {
        FrameLayout frame = new FrameLayout(this);
        ImageView placeholder = new ImageView(this);
        placeholder.setImageResource(defaultAvatarResource(avatarUrl));
        placeholder.setBackgroundColor(Color.rgb(224, 224, 218));
        placeholder.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(placeholder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        return frame;
    }

    private void addMetaViews(LinearLayout textColumn, Post post) {
        boolean added = false;
        if (!post.postedMeta.isEmpty()) {
            addMetaView(textColumn, post.postedMeta);
            added = true;
        }
        if (!post.editedMeta.isEmpty()) {
            addMetaView(textColumn, post.editedMeta);
            added = true;
        }
        if (!added && !post.meta.isEmpty()) {
            addMetaView(textColumn, post.meta);
        }
    }

    private void addMetaView(LinearLayout textColumn, String text) {
        TextView metaView = new TextView(this);
        metaView.setText(text);
        metaView.setTextColor(Color.rgb(117, 117, 117));
        metaView.setTextSize(12);
        metaView.setPadding(0, 0, 0, dp(4));
        textColumn.addView(metaView);
    }

    private void showImagePreview(String imageUrl) {
        ImageView imageView = makeRemoteImageView();
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setPadding(dp(8), dp(8), dp(8), dp(8));
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        frame.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                Math.max(dp(240), screenHeight - dp(180))
        ));
        BindingToken previewToken = new BindingToken();
        ImageLoadUi imageUi = new ImageLoadUi(imageView, imageUrl,
                Math.max(dp(240), screenWidth - dp(48)),
                Math.max(dp(240), screenHeight - dp(180)), false, previewToken, true);
        frame.addView(imageUi.container, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(frame)
                .setPositiveButton("关闭", null)
                .create();
        dialog.setOnShowListener(d -> imageUi.load());
        dialog.setOnDismissListener(d -> previewToken.active = false);
        dialog.show();
    }

    private final class ImageLoadUi {
        final LinearLayout container = new LinearLayout(TopicActivity.this);
        final TextView message = new TextView(TopicActivity.this);
        final LinearLayout actions = new LinearLayout(TopicActivity.this);
        final BindingToken token;
        final ImageView imageView;
        final String imageUrl;
        final int width;
        final int height;
        final boolean resize;
        boolean loading;

        ImageLoadUi(ImageView imageView, String imageUrl, int width, int height,
                boolean resize, BindingToken token, boolean dark) {
            this.imageView = imageView;
            this.imageUrl = imageUrl;
            this.width = width;
            this.height = height;
            this.resize = resize;
            this.token = token;
            container.setOrientation(LinearLayout.VERTICAL);
            container.setGravity(Gravity.CENTER);
            container.setPadding(dp(8), dp(4), dp(8), dp(4));
            message.setGravity(Gravity.CENTER);
            message.setTextSize(13);
            message.setTextColor(dark ? Color.WHITE : Color.rgb(83, 83, 83));
            container.addView(message);
            actions.setGravity(Gravity.CENTER);
            Button retry = makeButton("重试");
            retry.setOnClickListener(v -> load());
            Button browser = makeButton("浏览器查看");
            browser.setOnClickListener(v -> openTopicInBrowser());
            actions.addView(retry, new LinearLayout.LayoutParams(dp(80), dp(44)));
            actions.addView(browser, new LinearLayout.LayoutParams(dp(120), dp(44)));
            container.addView(actions);
            actions.setVisibility(View.GONE);
        }

        void load() {
            if (loading || !active(token)) { return; }
            loading = true;
            message.setText("正在加载图片...");
            container.setVisibility(View.VISIBLE);
            actions.setVisibility(View.GONE);
            loadRemoteImage(imageView, imageUrl, width, height, resize, this);
        }

        void complete(ImageResult result) {
            loading = false;
            if (result.bitmap != null) {
                container.setVisibility(View.GONE);
            } else {
                message.setText(result.failure);
                actions.setVisibility(View.VISIBLE);
                container.setVisibility(View.VISIBLE);
            }
        }
    }

    private void openTopicInBrowser() {
        if (!isHttpUrl(topicUrl)) { return; }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(topicUrl))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private static final class ImageResult {
        final Bitmap bitmap;
        final String failure;

        private ImageResult(Bitmap bitmap, String failure) {
            this.bitmap = bitmap;
            this.failure = failure;
        }

        static ImageResult loaded(Bitmap bitmap) { return new ImageResult(bitmap, ""); }
        static ImageResult failed(String failure) { return new ImageResult(null, failure); }
    }

    private CharSequence spannablePostContent(Post post, TextView contentView) {
        SpannableStringBuilder builder = new SpannableStringBuilder(post.content);
        Map<String, List<String>> sources = new HashMap<>();
        for (Post.InlineImage inlineImage : post.inlineImages) {
            if (!isHttpUrl(inlineImage.sourceUrl) || inlineImage.label.isEmpty()) {
                continue;
            }
            List<String> urls = sources.get(inlineImage.label);
            if (urls == null) {
                urls = new ArrayList<>();
                sources.put(inlineImage.label, urls);
            }
            urls.add(inlineImage.sourceUrl);
        }
        for (Map.Entry<String, List<String>> entry : sources.entrySet()) {
            int searchStart = 0;
            int occurrence = 0;
            int start;
            while ((start = post.content.indexOf(entry.getKey(), searchStart)) >= 0) {
                int end = start + entry.getKey().length();
                List<String> urls = entry.getValue();
                String url = urls.get(Math.min(occurrence++, urls.size() - 1));
                loadInlineImage(contentView, builder, url, start, end);
                searchStart = end;
            }
        }
        return builder;
    }

    private void loadInlineImage(
            TextView textView,
            SpannableStringBuilder builder,
            String imageUrl,
            int start,
            int end
    ) {
        if (destroyed) { return; }
        BindingToken token = bindingToken;
        imageExecutor.execute(() -> {
            if (!active(token) || Thread.currentThread().isInterrupted()) { return; }
            Bitmap bitmap = fetchRemoteImage(imageUrl, dp(96), dp(96)).bitmap;
            if (bitmap == null) {
                return;
            }
            mainHandler.post(() -> {
                if (!active(token)) { return; }
                BitmapDrawable drawable = new BitmapDrawable(getResources(), scaledInlineBitmap(bitmap));
                drawable.setBounds(0, 0, drawable.getBitmap().getWidth(), drawable.getBitmap().getHeight());
                builder.setSpan(
                        new ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM),
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                );
                textView.setText(builder);
                textView.setTextIsSelectable(true);
            });
        });
    }

    private Bitmap scaledInlineBitmap(Bitmap bitmap) {
        int height = dp(22);
        if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return bitmap;
        }
        int width = Math.max(1, Math.round(height * (bitmap.getWidth() / (float) bitmap.getHeight())));
        width = Math.min(dp(96), width);
        if (bitmap.getWidth() == width && bitmap.getHeight() == height) {
            return bitmap;
        }
        return Bitmap.createScaledBitmap(bitmap, width, height, true);
    }

    private int defaultAvatarResource(String avatarUrl) {
        if (isS1DefaultAvatarUrl(avatarUrl)) {
            return R.drawable.ic_default_avatar_s1;
        }
        if (isNgaDefaultAvatarUrl(avatarUrl)) {
            return R.drawable.ic_default_avatar_nga;
        }
        if (isS1Connector()) {
            return R.drawable.ic_default_avatar_s1;
        }
        if (isV2exConnector()) {
            return R.drawable.ic_default_avatar_v2ex;
        }
        if (isLinuxDoConnector()) {
            return R.drawable.ic_default_avatar_linuxdo;
        }
        return R.drawable.ic_default_avatar_nga;
    }

    private ImageView makeRemoteImageView() {
        ImageView imageView = new ImageView(this);
        imageView.setBackgroundColor(Color.rgb(224, 224, 218));
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setAdjustViewBounds(true);
        return imageView;
    }

    private void loadRemoteImage(
            ImageView imageView,
            String imageUrl,
            int targetWidth,
            int maxHeight,
            boolean resizeToBitmap
    ) {
        loadRemoteImage(imageView, imageUrl, targetWidth, maxHeight, resizeToBitmap, null);
    }

    private void loadRemoteImage(
            ImageView imageView,
            String imageUrl,
            int targetWidth,
            int maxHeight,
            boolean resizeToBitmap,
            ImageLoadUi stateView
    ) {
        if (destroyed) { return; }
        BindingToken token = stateView == null ? bindingToken : stateView.token;
        imageView.setTag(imageUrl);
        imageExecutor.execute(() -> {
            if (!active(token) || Thread.currentThread().isInterrupted()) { return; }
            ImageResult result = fetchRemoteImage(imageUrl, targetWidth, maxHeight);
            mainHandler.post(() -> {
                if (!active(token)) { return; }
                Object tag = imageView.getTag();
                if (!(tag instanceof String) || !imageUrl.equals(tag)) {
                    return;
                }
                if (result.bitmap != null) {
                    if (resizeToBitmap) {
                        resizeImageView(imageView, result.bitmap, targetWidth, maxHeight);
                    }
                    imageView.setImageBitmap(result.bitmap);
                }
                if (stateView != null) { stateView.complete(result); }
            });
        });
    }

    private ImageResult fetchRemoteImage(String imageUrl, int targetWidth, int targetHeight) {
        String cacheKey = imageGeneration + "|" + imageUrl + "|" + targetWidth + "x" + targetHeight;
        Bitmap cached = imageCache.get(cacheKey);
        if (cached != null) { return ImageResult.loaded(cached); }
        FutureTask<ImageResult> request = new FutureTask<>(() -> downloadRemoteImage(imageUrl, targetWidth, targetHeight, cacheKey));
        FutureTask<ImageResult> existing = imageRequests.putIfAbsent(cacheKey, request);
        boolean owner = existing == null;
        if (!owner) { request = existing; }
        try {
            if (owner) { request.run(); }
            return request.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return ImageResult.failed("图片加载已取消");
        } catch (Exception ignored) {
            return ImageResult.failed("图片加载失败");
        } finally {
            if (owner) { imageRequests.remove(cacheKey, request); }
        }
    }

    private ImageResult downloadRemoteImage(String imageUrl, int targetWidth, int targetHeight, String cacheKey) {
        HttpURLConnection connection = null;
        try {
            URI target;
            try {
                target = ImageRequestPolicy.parse(imageUrl);
            } catch (IOException error) {
                return ImageResult.failed("图片地址无效");
            }
            for (int hop = 0; hop <= 5; hop++) {
                if (destroyed || Thread.currentThread().isInterrupted()) {
                    return ImageResult.failed("图片加载已取消");
                }
                connection = (HttpURLConnection) target.toURL().openConnection();
                imageConnections.add(connection);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36 BBSFusion/0.1"
                );
                connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
                String referrer = ImageRequestPolicy.referrer(topicUrl, target);
                if (!referrer.isEmpty()) { connection.setRequestProperty("Referer", referrer); }
                String cookie = ImageRequestPolicy.cookiesFor(target, CookieManager.getInstance()::getCookie);
                if (!cookie.isEmpty()) {
                    connection.setRequestProperty("Cookie", cookie);
                }
                int response = connection.getResponseCode();
                if (response == 301 || response == 302 || response == 303 || response == 307 || response == 308) {
                    try {
                        target = ImageRequestPolicy.redirect(target, connection.getHeaderField("Location"));
                    } catch (IOException error) {
                        return ImageResult.failed("图片跳转地址无效或不安全");
                    }
                    imageConnections.remove(connection);
                    connection.disconnect();
                    connection = null;
                    continue;
                }
                if (response < 200 || response >= 300) {
                    return ImageResult.failed(ImageLoadFailure.httpStatus(response));
                }
                try (InputStream input = connection.getInputStream()) {
                    byte[] data = readImageBytes(input);
                    if (data == null) {
                        return ImageResult.failed(destroyed || Thread.currentThread().isInterrupted()
                                ? "图片加载已取消" : "图片超过 12 MB，请在浏览器查看");
                    }
                    if (data.length == 0) {
                        return ImageResult.failed("图片内容为空");
                    }
                    Bitmap bitmap = decodeSampledBitmap(data, targetWidth, targetHeight);
                    if (bitmap == null) { return ImageResult.failed("无法解码图片，请在浏览器查看"); }
                    if (!destroyed) { imageCache.put(cacheKey, bitmap); }
                    return ImageResult.loaded(bitmap);
                }
            }
            return ImageResult.failed("图片跳转次数过多");
        } catch (Exception error) {
            return ImageResult.failed(ImageLoadFailure.connection(error));
        } finally {
            if (connection != null) {
                imageConnections.remove(connection);
                connection.disconnect();
            }
        }
    }

    private byte[] readImageBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (destroyed || Thread.currentThread().isInterrupted()) { return null; }
            total += read;
            if (total > MAX_IMAGE_BYTES) {
                return null;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private Bitmap decodeSampledBitmap(byte[] data, int targetWidth, int targetHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            try {
                return BitmapFactory.decodeByteArray(data, 0, data.length);
            } catch (OutOfMemoryError ignored) {
                return null;
            }
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(
                bounds.outWidth,
                bounds.outHeight,
                targetWidth,
                targetHeight
        );
        try {
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (OutOfMemoryError ignored) {
            return null;
        }
    }

    private int sampleSize(int width, int height, int targetWidth, int targetHeight) {
        int requestedWidth = targetWidth > 0 ? targetWidth : MAX_BITMAP_SIDE;
        int requestedHeight = targetHeight > 0 ? targetHeight : MAX_BITMAP_SIDE;
        requestedWidth = Math.min(Math.max(1, requestedWidth), MAX_BITMAP_SIDE);
        requestedHeight = Math.min(Math.max(1, requestedHeight), MAX_BITMAP_SIDE);

        int sample = 1;
        while (width / sample > requestedWidth * 2
                || height / sample > requestedHeight * 2
                || width / sample > MAX_BITMAP_SIDE
                || height / sample > MAX_BITMAP_SIDE) {
            sample *= 2;
        }
        return Math.max(1, sample);
    }

    private void resizeImageView(ImageView imageView, Bitmap bitmap, int targetWidth, int maxHeight) {
        int bitmapWidth = bitmap.getWidth();
        int bitmapHeight = bitmap.getHeight();
        if (bitmapWidth <= 0 || bitmapHeight <= 0) {
            return;
        }
        int height = Math.round(targetWidth * (bitmapHeight / (float) bitmapWidth));
        height = Math.max(dp(96), Math.min(maxHeight, height));
        View parent = (View) imageView.getParent();
        int availableWidth = parent == null ? targetWidth : parent.getWidth();
        if (availableWidth > 0) {
            targetWidth = availableWidth;
        }
        ViewGroup.LayoutParams existing = imageView.getLayoutParams();
        ViewGroup.LayoutParams params;
        if (existing instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams linearParams = (LinearLayout.LayoutParams) existing;
            linearParams.width = targetWidth;
            linearParams.height = height;
            linearParams.topMargin = dp(8);
            params = linearParams;
        } else if (existing instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams frameParams = (FrameLayout.LayoutParams) existing;
            frameParams.width = targetWidth;
            frameParams.height = height;
            params = frameParams;
        } else {
            params = new ViewGroup.LayoutParams(targetWidth, height);
        }
        imageView.setLayoutParams(params);
    }

    private int contentImageWidth() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        return Math.max(dp(160), screenWidth - dp(16 + 42 + 12 + 16));
    }

    private boolean isHttpUrl(String value) {
        return value != null
                && (value.startsWith("https://") || value.startsWith("http://"));
    }

    private boolean isDefaultAvatarUrl(String value) {
        return isBlankAvatarUrl(value) || isS1DefaultAvatarUrl(value) || isNgaDefaultAvatarUrl(value);
    }

    private boolean isS1DefaultAvatarUrl(String value) {
        if (isBlankAvatarUrl(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.equals("https://avatar.stage1st.com/noavatar.svg")
                || normalized.equals("http://avatar.stage1st.com/noavatar.svg")
                || normalized.endsWith("/noavatar.svg");
    }

    private boolean isNgaDefaultAvatarUrl(String value) {
        if (isBlankAvatarUrl(value)) {
            return false;
        }
        return value.trim().toLowerCase().startsWith("data:image/svg+xml");
    }

    private boolean isBlankAvatarUrl(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isS1Connector() {
        return connector != null && "s1".equals(connector.id());
    }

    private boolean isV2exConnector() {
        return connector != null && "v2ex".equals(connector.id());
    }

    private boolean isLinuxDoConnector() {
        return connector != null && "linuxdo".equals(connector.id());
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(32, 33, 36));
        button.setBackgroundColor(Color.rgb(236, 235, 230));
        return button;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
