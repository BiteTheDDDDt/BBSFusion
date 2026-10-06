package dev.bbsfusion;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.os.Bundle;
import android.os.Build;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inspector.WindowInspector;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import dev.bbsfusion.core.Post;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Local network fixtures exercise parsing, downloading and the actual rendered post list. */
@SuppressWarnings("deprecation")
public final class TopicBodyImageTest extends InstrumentationTestCase {
    private static final int IMAGE_COLOR = Color.rgb(55, 166, 112);
    private static final String FIRST_IMAGE = "/body-0.png";
    private TopicActivity activity;
    private ListView list;
    private ProxySelector originalProxySelector;

    @Override protected void setUp() throws Exception {
        super.setUp();
        originalProxySelector = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                if ("127.0.0.1".equals(uri.getHost()) || originalProxySelector == null) {
                    return Collections.singletonList(Proxy.NO_PROXY);
                }
                return originalProxySelector.select(uri);
            }
            @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                if (originalProxySelector != null) {
                    originalProxySelector.connectFailed(uri, address, failure);
                }
            }
        });
    }

    public void testBodyImageSurvivesRelayoutAndRecycling() throws Exception {
        try (FixtureServer server = new FixtureServer(false)) {
            openTopic(server);
            String firstImage = server.url(FIRST_IMAGE);
            assertImageDrawn(firstImage);
            onMain(() -> {
                list.setPadding(list.getPaddingLeft(), list.getPaddingTop() + 1,
                        list.getPaddingRight(), list.getPaddingBottom());
                list.requestLayout();
                return null;
            });
            assertImageDrawn(firstImage);
            onMain(() -> {
                ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
                return null;
            });
            assertImageDrawn(firstImage);
            selectPost(18);
            assertImageDrawn(server.url("/body-18.png"));
            selectPost(0);
            assertImageDrawn(firstImage);
            int beforeIdle = server.totalImageRequests();
            SystemClock.sleep(400);
            assertEquals("Idle rendering must not repeatedly download images",
                    beforeIdle, server.totalImageRequests());
            assertEquals("Rebinding and recycling should reuse the first image",
                    1, server.imageRequests(FIRST_IMAGE));
        }
    }

    public void testBodyImageHttpFailureCanBeRetried() throws Exception {
        try (FixtureServer server = new FixtureServer(true)) {
            openTopic(server);
            await("An HTTP failure should show its status beside the body image", 10000,
                    () -> onMain(() -> isVisible(findText(list, "图片请求失败（HTTP 403）"))));
            assertTrue("Failed body images should offer browser viewing",
                    onMain(() -> isVisible(findText(list, "浏览器查看"))));
            assertTrue("Failed body images should offer an explicit retry",
                    onMain(() -> isVisible(findText(list, "重试"))));
            SystemClock.sleep(400);
            assertEquals("A failed image must wait for an explicit retry", 1,
                    server.imageRequests(FIRST_IMAGE));
            captureScreenshot("body-image-http403.png");
            onMain(() -> {
                findText(list, "重试").performClick();
                return null;
            });
            assertImageDrawn(server.url(FIRST_IMAGE));
            assertEquals("Tapping retry should make exactly one fresh request", 2,
                    server.imageRequests(FIRST_IMAGE));
            assertFalse("The error should disappear when the image succeeds",
                    onMain(() -> isVisible(findText(list, "图片请求失败（HTTP 403）"))));
            captureScreenshot("body-image-retry-success.png");
        }
    }

    /** Opt-in live verification; no remote request is made unless both arguments are present. */
    public void testLiveTopicImages() throws Exception {
        Bundle arguments = ((InstrumentationTestRunner) getInstrumentation()).getArguments();
        String topicUrl = arguments.getString("liveTopicUrl", "").trim();
        String images = arguments.getString("liveImageUrls", "").trim();
        if (topicUrl.isEmpty() || images.isEmpty()) { return; }
        openTopic(topicUrl);
        await("The live topic did not load any posts", 45000,
                () -> onMain(() -> list.getAdapter().getCount()) > 0);
        String[] imageUrls = images.split(",");
        for (int i = 0; i < imageUrls.length; i++) {
            String imageUrl = imageUrls[i].trim();
            assertFalse("Live image URLs must not be empty", imageUrl.isEmpty());
            int position = onMain(() -> {
                for (int index = 0; index < list.getAdapter().getCount(); index++) {
                    Post post = (Post) list.getAdapter().getItem(index);
                    if (post.imageUrls.contains(imageUrl)) { return index; }
                }
                return -1;
            });
            assertTrue("An expected live image was not parsed into the topic", position >= 0);
            selectPost(position);
            await("The live image did not decode into a bitmap", 30000,
                    () -> onMain(() -> hasBitmap(findImage(imageUrl), false)));
            onMain(() -> {
                ImageView image = findImage(imageUrl);
                View row = list.getChildAt(position - list.getFirstVisiblePosition());
                int[] rowLocation = new int[2];
                int[] imageLocation = new int[2];
                row.getLocationOnScreen(rowLocation);
                image.getLocationOnScreen(imageLocation);
                list.setSelectionFromTop(position, rowLocation[1] - imageLocation[1] + 20);
                return null;
            });
            assertImageDrawn(imageUrl, false);
            captureScreenshot("live-body-image-" + (i + 1) + ".png");
        }
        if (Build.VERSION.SDK_INT >= 29) {
            String lastImage = imageUrls[imageUrls.length - 1].trim();
            onMain(() -> { findImage(lastImage).performClick(); return null; });
            await("The full-size image preview did not display a bitmap", 30000,
                    () -> onMain(() -> {
                        for (View window : WindowInspector.getGlobalWindowViews()) {
                            if (findText(window, "关闭") == null) { continue; }
                            ImageView image = (ImageView) findView(window,
                                    view -> view instanceof ImageView && lastImage.equals(view.getTag()));
                            if (isVisible(image) && hasBitmap(image, false)) { return true; }
                        }
                        return false;
                    }));
            captureScreenshot("live-image-preview.png");
        }
    }

    private void openTopic(FixtureServer server) throws Exception {
        openTopic(server.url("/thread-1-1-1.html"));
        await("The fixture posts were not loaded", 10000,
                () -> onMain(() -> list.getAdapter().getCount()) == 24);
    }

    private void openTopic(String url) {
        Intent intent = new Intent(getInstrumentation().getTargetContext(), TopicActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(TopicActivity.EXTRA_SITE_ID, "s1");
        intent.putExtra(TopicActivity.EXTRA_TITLE, "Body image regression test");
        intent.putExtra(TopicActivity.EXTRA_URL, url);
        activity = (TopicActivity) getInstrumentation().startActivitySync(intent);
        list = onMain(() -> (ListView) findView(activity.getWindow().getDecorView(),
                view -> view instanceof ListView));
        assertNotNull("TopicActivity must show a post list", list);
    }

    private void selectPost(int position) {
        onMain(() -> { list.setSelectionFromTop(position, 0); return null; });
    }

    private void assertImageDrawn(String imageUrl) throws Exception {
        assertImageDrawn(imageUrl, true);
    }

    private void assertImageDrawn(String imageUrl, boolean fixture) throws Exception {
        AtomicInteger consecutiveFrames = new AtomicInteger();
        ViewTreeObserver.OnPreDrawListener probe = () -> {
            ImageView image = findImage(imageUrl);
            boolean ready = isVisible(image) && hasBitmap(image, fixture);
            if (ready) { consecutiveFrames.incrementAndGet(); }
            else { consecutiveFrames.set(0); }
            return true;
        };
        onMain(() -> { list.getViewTreeObserver().addOnPreDrawListener(probe); return null; });
        try {
            await("Body bitmap must remain visible across three consecutive rendered frames", 15000, () -> {
                onMain(() -> { list.invalidate(); return null; });
                return consecutiveFrames.get() >= 3;
            });
        } finally {
            onMain(() -> { list.getViewTreeObserver().removeOnPreDrawListener(probe); return null; });
        }
    }

    private ImageView findImage(String imageUrl) {
        return (ImageView) findView(list,
                view -> view instanceof ImageView && imageUrl.equals(view.getTag()));
    }

    private static boolean hasBitmap(ImageView image, boolean fixture) {
        if (image == null || !(image.getDrawable() instanceof BitmapDrawable)) { return false; }
        Bitmap bitmap = ((BitmapDrawable) image.getDrawable()).getBitmap();
        return bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() > 0 && bitmap.getHeight() > 0
                && (!fixture || bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2) == IMAGE_COLOR);
    }

    private static boolean isVisible(View view) {
        return view != null && view.isShown() && view.getGlobalVisibleRect(new Rect());
    }

    private static TextView findText(View root, String text) {
        return (TextView) findView(root,
                view -> view instanceof TextView && text.contentEquals(((TextView) view).getText()));
    }

    private static View findView(View view, Predicate<View> predicate) {
        if (predicate.test(view)) { return view; }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findView(group.getChildAt(i), predicate);
                if (found != null) { return found; }
            }
        }
        return null;
    }

    private <T> T onMain(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }

    /** Opt-in device QA artifacts: -e captureScreenshots true. */
    private void captureScreenshot(String name) throws Exception {
        String enabled = ((InstrumentationTestRunner) getInstrumentation())
                .getArguments().getString("captureScreenshots", "false");
        if (!Boolean.parseBoolean(enabled)) { return; }
        File directory = new File(activity.getExternalFilesDir(null), "qa_s1_images");
        assertTrue("Screenshot directory should be writable", directory.isDirectory() || directory.mkdirs());
        Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("The device should provide a screenshot", screenshot);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue("The screenshot should encode as PNG", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            screenshot.recycle();
        }
    }

    private static void await(String message, long timeoutMillis, BooleanSupplier condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.getAsBoolean()) { return; }
            Thread.sleep(100);
        }
        fail(message);
    }

    @Override protected void tearDown() throws Exception {
        try {
            boolean leaveOpen = Boolean.parseBoolean(((InstrumentationTestRunner) getInstrumentation())
                    .getArguments().getString("leaveOpen", "false"));
            if (activity != null && !leaveOpen) { onMain(() -> { activity.finish(); return null; }); }
        } finally {
            ProxySelector.setDefault(originalProxySelector);
            super.tearDown();
        }
    }

    private static final class FixtureServer implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final Map<String, AtomicInteger> imageRequests = new ConcurrentHashMap<>();
        private final byte[] png;
        private final byte[] html;
        private final boolean failFirstImage;
        private volatile boolean closed;

        FixtureServer(boolean failFirstImage) throws Exception {
            this.failFirstImage = failFirstImage;
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            Bitmap bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(IMAGE_COLOR);
            ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, imageBytes);
            bitmap.recycle();
            png = imageBytes.toByteArray();
            StringBuilder page = new StringBuilder("<html><head><title>Body images</title></head><body>");
            for (int i = 0; i < 24; i++) {
                page.append("<div class='plc cl' id='pid").append(i + 1)
                        .append("'><div class='authi'><a>Reader ").append(i)
                        .append("</a></div><div class='message'>Body image ").append(i)
                        .append("<img file='/body-").append(i)
                        .append(".png' src='static/image/common/none.gif'></div></div>");
            }
            html = page.append("</body></html>").toString().getBytes(StandardCharsets.UTF_8);
            workers.execute(() -> {
                while (!closed) {
                    try {
                        Socket socket = server.accept();
                        workers.execute(() -> serve(socket));
                    } catch (Exception error) {
                        if (!closed) { throw new IllegalStateException("Fixture server stopped", error); }
                    }
                }
            });
        }

        String url(String path) { return "http://127.0.0.1:" + server.getLocalPort() + path; }
        int imageRequests(String path) {
            AtomicInteger count = imageRequests.get(path);
            return count == null ? 0 : count.get();
        }
        int totalImageRequests() {
            int total = 0;
            for (AtomicInteger count : imageRequests.values()) { total += count.get(); }
            return total;
        }

        private void serve(Socket socket) {
            try (Socket connection = socket) {
                connection.setSoTimeout(3000);
                BufferedReader input = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.US_ASCII));
                String request = input.readLine();
                if (request == null) { return; }
                String path = request.split(" ")[1];
                for (String line; (line = input.readLine()) != null && !line.isEmpty();) { }
                boolean image = path.endsWith(".png");
                boolean forbidden = false;
                if (image) {
                    int count = imageRequests.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
                    forbidden = failFirstImage && FIRST_IMAGE.equals(path) && count == 1;
                    Thread.sleep(150); // Complete asynchronously after the initial row layout.
                }
                byte[] body = forbidden ? new byte[0] : image ? png : html;
                String type = image ? "image/png" : "text/html; charset=utf-8";
                OutputStream output = connection.getOutputStream();
                output.write(("HTTP/1.1 " + (forbidden ? "403 Forbidden" : "200 OK")
                        + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(body);
                output.flush();
            } catch (Exception ignored) {
                // Row recycling and Activity destruction can cancel a pending image connection.
            }
        }

        @Override public void close() throws Exception {
            closed = true;
            server.close();
            workers.shutdownNow();
        }
    }
}
