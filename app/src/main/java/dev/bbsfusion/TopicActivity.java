package dev.bbsfusion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;
import android.webkit.CookieManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.bbsfusion.core.ConnectorRegistry;
import dev.bbsfusion.core.ForumConnector;
import dev.bbsfusion.core.Post;
import dev.bbsfusion.core.TopicDetail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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

    private LinearLayout postsContainer;
    private TextView titleView;
    private TextView statusView;
    private Button loadMoreButton;

    private ForumConnector connector;
    private String topicUrl;
    private String initialTitle;
    private int loadedPage = 1;
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
        loadTopic();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        imageExecutor.shutdownNow();
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 247, 244));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));

        Button back = makeButton("返回");
        back.setOnClickListener(v -> finish());
        Button open = makeButton("原站");
        open.setOnClickListener(v -> OriginalWebActivity.open(this, topicUrl, connector.name()));

        bar.addView(back, new LinearLayout.LayoutParams(dp(88), dp(44)));
        bar.addView(open, new LinearLayout.LayoutParams(dp(88), dp(44)));
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

        ScrollView scrollView = new ScrollView(this);
        postsContainer = new LinearLayout(this);
        postsContainer.setOrientation(LinearLayout.VERTICAL);
        postsContainer.setPadding(0, 0, 0, dp(24));
        scrollView.addView(postsContainer);

        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        loadMoreButton = makeButton("加载更多");
        loadMoreButton.setVisibility(View.GONE);
        loadMoreButton.setOnClickListener(v -> loadTopicPage(loadedPage + 1, true));
        root.addView(loadMoreButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
        ));

        return root;
    }

    private void loadTopic() {
        loadedPage = 1;
        hasMorePages = false;
        loadTopicPage(1, false);
    }

    private void loadTopicPage(int page, boolean append) {
        if (isLoadingPage) {
            return;
        }
        isLoadingPage = true;
        updateLoadMoreButton();
        statusView.setText("正在加载 " + connector.name() + " 帖子...");
        executor.execute(() -> {
            try {
                TopicDetail detail = connector.fetchTopicPage(topicUrl, page);
                mainHandler.post(() -> {
                    isLoadingPage = false;
                    if (append) {
                        appendTopic(detail);
                    } else {
                        renderTopic(detail);
                    }
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    isLoadingPage = false;
                    updateLoadMoreButton();
                    statusView.setText("加载失败：" + error.getMessage());
                    addPostText("提示", "可以点“原站”用 WebView 打开。");
                });
            }
        });
    }

    private void renderTopic(TopicDetail detail) {
        titleView.setText(detail.title);
        postsContainer.removeAllViews();
        for (Post post : detail.posts) {
            addPostView(post);
        }
        loadedPage = detail.pageNumber;
        hasMorePages = detail.hasMore;
        updateLoadMoreButton();
        statusView.setText("已加载 " + detail.posts.size() + " 段内容。");
    }

    private void appendTopic(TopicDetail detail) {
        for (Post post : detail.posts) {
            addPostView(post);
        }
        loadedPage = detail.pageNumber;
        hasMorePages = detail.hasMore && !detail.posts.isEmpty();
        updateLoadMoreButton();
        statusView.setText("已加载到第 " + loadedPage + " 页，新增 " + detail.posts.size() + " 段内容。");
    }

    private void updateLoadMoreButton() {
        if (loadMoreButton == null) {
            return;
        }
        loadMoreButton.setVisibility(hasMorePages || isLoadingPage ? View.VISIBLE : View.GONE);
        loadMoreButton.setEnabled(hasMorePages && !isLoadingPage);
        loadMoreButton.setText(isLoadingPage ? "正在加载..." : "加载更多");
    }

    private void addPostText(String author, String content) {
        addPostView(new Post(author, content));
    }

    private void addPostView(Post post) {
        LinearLayout row = new LinearLayout(this);
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

        TextView authorView = new TextView(this);
        authorView.setText(post.author);
        authorView.setTextColor(Color.rgb(37, 108, 90));
        authorView.setTextSize(13);
        authorView.setPadding(0, 0, 0, dp(2));
        textColumn.addView(authorView);

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
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(160)
            );
            imageParams.topMargin = dp(8);
            textColumn.addView(postImageView, imageParams);
            loadRemoteImage(postImageView, imageUrl, contentImageWidth(), dp(420), true);
            postImageView.setOnClickListener(v -> showImagePreview(imageUrl));
        }

        row.addView(textColumn, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));
        postsContainer.addView(row);

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(216, 214, 207));
        postsContainer.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
        ));
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
        TextView loadingView = new TextView(this);
        loadingView.setText("正在加载图片...");
        loadingView.setTextColor(Color.WHITE);
        loadingView.setTextSize(14);
        loadingView.setGravity(Gravity.CENTER);
        frame.addView(loadingView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                Math.max(dp(240), screenHeight - dp(180))
        ));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(frame)
                .setPositiveButton("关闭", null)
                .create();
        dialog.setOnShowListener(d -> loadRemoteImage(
                imageView,
                imageUrl,
                Math.max(dp(240), screenWidth - dp(48)),
                Math.max(dp(240), screenHeight - dp(180)),
                false,
                loadingView
        ));
        dialog.show();
    }

    private CharSequence spannablePostContent(Post post, TextView contentView) {
        SpannableStringBuilder builder = new SpannableStringBuilder(post.content);
        int searchStart = 0;
        for (Post.InlineImage inlineImage : post.inlineImages) {
            if (!isHttpUrl(inlineImage.sourceUrl) || inlineImage.label.isEmpty()) {
                continue;
            }
            int start = builder.toString().indexOf(inlineImage.label, searchStart);
            if (start < 0) {
                continue;
            }
            int end = start + inlineImage.label.length();
            searchStart = end;
            loadInlineImage(contentView, builder, inlineImage.sourceUrl, start, end);
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
        imageExecutor.execute(() -> {
            Bitmap bitmap = fetchRemoteImage(imageUrl, dp(96), dp(96));
            if (bitmap == null) {
                return;
            }
            mainHandler.post(() -> {
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
            TextView stateView
    ) {
        imageView.setTag(imageUrl);
        imageExecutor.execute(() -> {
            Bitmap bitmap = fetchRemoteImage(imageUrl, targetWidth, maxHeight);
            if (bitmap == null) {
                mainHandler.post(() -> updateImageLoadState(imageView, imageUrl, stateView, false));
                return;
            }
            mainHandler.post(() -> {
                Object tag = imageView.getTag();
                if (!(tag instanceof String) || !imageUrl.equals(tag)) {
                    return;
                }
                if (resizeToBitmap) {
                    resizeImageView(imageView, bitmap, targetWidth, maxHeight);
                }
                imageView.setImageBitmap(bitmap);
                updateImageLoadState(imageView, imageUrl, stateView, true);
            });
        });
    }

    private void updateImageLoadState(
            ImageView imageView,
            String imageUrl,
            TextView stateView,
            boolean loaded
    ) {
        if (stateView == null) {
            return;
        }
        Object tag = imageView.getTag();
        if (!(tag instanceof String) || !imageUrl.equals(tag)) {
            return;
        }
        if (loaded) {
            stateView.setVisibility(View.GONE);
        } else {
            stateView.setText("图片加载失败，可点原站查看。");
            stateView.setVisibility(View.VISIBLE);
        }
    }

    private Bitmap fetchRemoteImage(String imageUrl, int targetWidth, int targetHeight) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(imageUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36 BBSFusion/0.1"
            );
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
            if (topicUrl != null && !topicUrl.isEmpty()) {
                connection.setRequestProperty("Referer", topicUrl);
            }
            String cookie = cookiesFor(imageUrl);
            if (!cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] data = readImageBytes(input);
                if (data == null || data.length == 0) {
                    return null;
                }
                return decodeSampledBitmap(data, targetWidth, targetHeight);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
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

    private String cookiesFor(String imageUrl) {
        CookieManager cookieManager = CookieManager.getInstance();
        StringBuilder builder = new StringBuilder();
        appendCookie(builder, cookieManager.getCookie(imageUrl));
        appendCookie(builder, cookieManager.getCookie("https://stage1st.com/2b/"));
        appendCookie(builder, cookieManager.getCookie("https://bbs.nga.cn/"));
        appendCookie(builder, cookieManager.getCookie("https://ngabbs.com/"));
        appendCookie(builder, cookieManager.getCookie("https://www.v2ex.com/"));
        appendCookie(builder, cookieManager.getCookie("https://linux.do/"));
        return builder.toString();
    }

    private void appendCookie(StringBuilder builder, String cookie) {
        if (cookie == null || cookie.trim().isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("; ");
        }
        builder.append(cookie.trim());
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
