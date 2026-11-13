package dev.bbsfusion;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ListView;
import android.widget.TextView;

import dev.bbsfusion.core.Post;

import java.io.BufferedReader;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("deprecation")
public final class TopicNavigationDeviceTest extends InstrumentationTestCase {
    private TopicActivity activity;
    private ListView posts;
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

    public void testPollAndJumpToFloorAndLastPage() throws Exception {
        try (TopicServer server = new TopicServer()) {
            openTopic(server.topicUrl());
            awaitText("选项甲", 10000);
            awaitText("选项乙\n6 票", 10000);
            assertEquals(Collections.singletonList(1), server.requests());

            openJump();
            enterFloor("0");
            tap("跳转");
            await("Invalid input should keep the jump dialog open", 5000,
                    () -> dialogNode(node -> "android.widget.EditText".equals(String.valueOf(node.getClassName()))
                            && node.getError() != null) != null);
            assertEquals(Collections.singletonList(1), server.requests());

            enterFloor("45");
            tap("跳转");
            awaitText("已跳到第 45 楼。", 10000);
            awaitText("45楼", 5000);
            assertEquals(Arrays.asList(1, 2), server.requests());

            openJump();
            enterFloor("42");
            tap("跳转");
            await("Missing floor should be reported", 10000,
                    () -> visibleText(text -> text.contains("未找到第 42 楼")));
            awaitText("45楼", 5000);
            assertFalse("Missing floor must not replace the reading page", visibleText("1楼"::equals));

            openJump();
            tap("主楼");
            awaitText("已跳到第 1 楼。", 10000);
            awaitText("选项甲", 5000);
            assertEquals(Arrays.asList(1, 2, 1), server.requests());

            openJump();
            tap("最后一楼");
            awaitText("已跳到最后一楼。", 10000);
            screenshot("fixture-last.png");
            awaitText("95楼", 5000);
            assertEquals("Last-floor jump must skip page two", Arrays.asList(1, 2, 1, 3),
                    server.requests());
        }
    }

    /** A real-topic check runs only when all three live arguments are supplied explicitly. */
    public void testLivePollAndJump() throws Exception {
        Bundle arguments = arguments();
        String url = arguments.getString("liveTopicUrl", "").trim();
        String option = arguments.getString("livePollOption", "").trim();
        int floor = Integer.parseInt(arguments.getString("liveJumpFloor", "0"));
        if (url.isEmpty() || option.isEmpty() || floor <= 0) { return; }

        openTopic(url);
        await("The real poll option should be visible", 45000,
                () -> visibleText(text -> text.equals(option) || text.startsWith(option + "\n")));
        screenshot("poll.png");

        openJump();
        enterFloor(String.valueOf(floor));
        tap("跳转");
        awaitText("已跳到第 " + floor + " 楼。", 45000);
        awaitText(floor + "楼", 5000);
        screenshot("floor.png");

        openJump();
        tap("最后一楼");
        awaitText("已跳到最后一楼。", 45000);
        int lastFloor = onMain(() -> ((Post) posts.getAdapter()
                .getItem(posts.getAdapter().getCount() - 1)).floorNumber);
        assertTrue("The real last reply should have an original floor number", lastFloor >= floor);
        awaitText(lastFloor + "楼", 5000);
        screenshot("last.png");
    }

    private void openTopic(String url) {
        Intent intent = new Intent(getInstrumentation().getTargetContext(), TopicActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(TopicActivity.EXTRA_SITE_ID, "s1");
        intent.putExtra(TopicActivity.EXTRA_TITLE, "投票与楼层跳转");
        intent.putExtra(TopicActivity.EXTRA_URL, url);
        activity = (TopicActivity) getInstrumentation().startActivitySync(intent);
        posts = onMain(() -> (ListView) findView(activity.getWindow().getDecorView(),
                view -> view instanceof ListView));
        assertNotNull(posts);
    }

    private void openJump() throws Exception {
        onMain(() -> {
            View jump = findView(activity.getWindow().getDecorView(), view ->
                    view instanceof TextView && "跳转".contentEquals(((TextView) view).getText()));
            assertNotNull("The topic toolbar should have a jump button", jump);
            assertTrue(jump.isEnabled() && jump.performClick());
            return null;
        });
        await("Jump dialog should open", 5000,
                () -> dialogNode(node -> "跳转楼层".equals(String.valueOf(node.getText()))) != null);
    }

    private void enterFloor(String floor) {
        AccessibilityNodeInfo input = dialogNode(
                node -> "android.widget.EditText".equals(String.valueOf(node.getClassName())));
        assertNotNull("The jump dialog should have a floor input", input);
        Bundle value = new Bundle();
        value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, floor);
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value));
    }

    private void tap(String label) {
        AccessibilityNodeInfo button = dialogNode(node ->
                node.isClickable() && label.equals(String.valueOf(node.getText())));
        assertNotNull("The jump dialog should show " + label, button);
        assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }

    private AccessibilityNodeInfo dialogNode(Predicate<AccessibilityNodeInfo> predicate) {
        return findNode(getInstrumentation().getUiAutomation().getRootInActiveWindow(), predicate);
    }

    private static AccessibilityNodeInfo findNode(AccessibilityNodeInfo node,
            Predicate<AccessibilityNodeInfo> predicate) {
        if (node == null || predicate.test(node)) { return node; }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findNode(node.getChild(i), predicate);
            if (found != null) { return found; }
        }
        return null;
    }

    private void awaitText(String text, long timeoutMillis) throws Exception {
        await("Expected visible text: " + text, timeoutMillis, () -> visibleText(text::equals));
    }

    private boolean visibleText(Predicate<String> text) {
        return onMain(() -> findView(activity.getWindow().getDecorView(), view ->
                view instanceof TextView && view.isShown() && view.getGlobalVisibleRect(new Rect())
                        && text.test(((TextView) view).getText().toString())) != null);
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

    private <T> T onMain(Supplier<T> operation) {
        AtomicReference<T> result = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> result.set(operation.get()));
        return result.get();
    }

    private Bundle arguments() {
        return ((InstrumentationTestRunner) getInstrumentation()).getArguments();
    }

    private void screenshot(String fileName) throws IOException {
        if (!Boolean.parseBoolean(arguments().getString("captureScreenshots", "false"))) { return; }
        getInstrumentation().waitForIdleSync();
        try {
            getInstrumentation().getUiAutomation().waitForIdle(400, 5000);
        } catch (java.util.concurrent.TimeoutException ignored) {
            // Ongoing image loads may keep the accessibility tree busy.
        }
        File directory = new File(activity.getExternalFilesDir(null), "qa_topic_navigation");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap image = getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream output = new FileOutputStream(new File(directory, fileName))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            image.recycle();
        }
    }

    private static void await(String message, long timeoutMillis, BooleanSupplier ready) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        while (SystemClock.uptimeMillis() < deadline) {
            if (ready.getAsBoolean()) { return; }
            Thread.sleep(75);
        }
        fail(message);
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (activity != null) { onMain(() -> { activity.finish(); return null; }); }
        } finally {
            ProxySelector.setDefault(originalProxySelector);
            super.tearDown();
        }
    }

    private static final class TopicServer implements AutoCloseable {
        private static final Pattern PAGE_PATH = Pattern.compile("/thread-1-([1-3])-1\\.html");
        private final ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final List<Integer> requestedPages = new CopyOnWriteArrayList<>();

        TopicServer() throws IOException {
            worker.execute(() -> {
                while (!server.isClosed()) {
                    try (Socket connection = server.accept()) {
                        respond(connection);
                    } catch (IOException error) {
                        if (!server.isClosed()) { throw new IllegalStateException(error); }
                    }
                }
            });
        }

        String topicUrl() {
            return "http://127.0.0.1:" + server.getLocalPort() + "/thread-1-1-1.html";
        }

        List<Integer> requests() {
            return new ArrayList<>(requestedPages);
        }

        private void respond(Socket connection) throws IOException {
            connection.setSoTimeout(3000);
            BufferedReader input = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.US_ASCII));
            String request = input.readLine();
            if (request == null) { return; }
            for (String line; (line = input.readLine()) != null && !line.isEmpty();) { }
            Matcher path = PAGE_PATH.matcher(URI.create(request.split(" ")[1]).getPath());
            boolean found = path.matches();
            int page = found ? Integer.parseInt(path.group(1)) : 0;
            if (found) { requestedPages.add(page); }
            byte[] body = (found ? pageHtml(page) : "").getBytes(StandardCharsets.UTF_8);
            OutputStream output = connection.getOutputStream();
            output.write(("HTTP/1.1 " + (found ? "200 OK" : "404 Not Found")
                    + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(body);
        }

        private static String pageHtml(int page) {
            StringBuilder html = new StringBuilder("<html><head><title>投票测试</title></head><body>");
            for (int floor = 40 * (page - 1) + 1; floor <= Math.min(40 * page, 95); floor++) {
                if (floor == 42) { continue; }
                html.append("<div class='plc cl' id='pid").append(floor)
                        .append("'><div class='display pi'><ul class='authi'><li class='mtit'>")
                        .append("<span class='y'>").append(floor == 1 ? "楼主" : floor + "<sup>#</sup>")
                        .append("</span><span class='z'><a>作者</a></span></li></ul>")
                        .append("<div class='message'>楼层正文</div>");
                if (floor == 1) {
                    html.append("<div class='bt'><form id='poll' name='poll'><div class='poll_box'>")
                            .append("<p><label for='option_1'>1.选项甲</label></p>")
                            .append("<p><label for='option_2'>2.选项乙</label><span>6 票</span></p>")
                            .append("</div></form></div>");
                }
                html.append("</div></div>");
            }
            html.append("<div class='pg'><strong>").append(page)
                    .append("</strong><label><span title='共 3 页'> / 3 页</span></label>");
            if (page < 3) {
                html.append("<a href='thread-1-").append(page + 1)
                        .append("-1.html' class='nxt'>下一页</a>");
            }
            return html.append("</div></body></html>").toString();
        }

        @Override public void close() throws IOException {
            try {
                server.close();
            } finally {
                worker.shutdownNow();
            }
        }
    }
}
