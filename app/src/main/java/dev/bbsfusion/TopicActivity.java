package dev.bbsfusion;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.view.Gravity;
import android.view.View;
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

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TopicActivity extends Activity {
    public static final String EXTRA_SITE_ID = "site_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_URL = "url";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LinearLayout postsContainer;
    private TextView titleView;
    private TextView statusView;

    private ForumConnector connector;
    private String topicUrl;
    private String initialTitle;

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

        return root;
    }

    private void loadTopic() {
        statusView.setText("正在加载 " + connector.name() + " 帖子...");
        executor.execute(() -> {
            try {
                TopicDetail detail = connector.fetchTopic(topicUrl);
                mainHandler.post(() -> renderTopic(detail));
            } catch (Exception error) {
                mainHandler.post(() -> {
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
        statusView.setText("已加载 " + detail.posts.size() + " 段内容。");
    }

    private void addPostText(String author, String content) {
        addPostView(new Post(author, content));
    }

    private void addPostView(Post post) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(12));

        FrameLayout avatarFrame = makeAvatarFrame(post.author);
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

        TextView contentView = new TextView(this);
        contentView.setText(post.content);
        contentView.setTextColor(Color.rgb(32, 33, 36));
        contentView.setTextSize(16);
        contentView.setLineSpacing(0, 1.15f);
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

    private FrameLayout makeAvatarFrame(String author) {
        FrameLayout frame = new FrameLayout(this);
        ImageView placeholder = new ImageView(this);
        placeholder.setImageResource(R.drawable.ic_default_avatar);
        placeholder.setBackgroundColor(Color.rgb(224, 224, 218));
        placeholder.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(placeholder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        return frame;
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
        imageView.setTag(imageUrl);
        imageExecutor.execute(() -> {
            Bitmap bitmap = fetchRemoteImage(imageUrl);
            if (bitmap == null) {
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
            });
        });
    }

    private Bitmap fetchRemoteImage(String imageUrl) {
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
                return BitmapFactory.decodeStream(input);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(targetWidth, height);
        params.topMargin = dp(8);
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
        if (value == null) {
            return true;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.isEmpty()
                || normalized.startsWith("data:image/svg+xml")
                || normalized.equals("https://avatar.stage1st.com/noavatar.svg")
                || normalized.equals("http://avatar.stage1st.com/noavatar.svg")
                || normalized.endsWith("/noavatar.svg");
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
