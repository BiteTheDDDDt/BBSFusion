package dev.bbsfusion;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Toast;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

public final class OriginalWebActivity extends Activity {
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_TITLE = "title";

    private WebView webView;
    private TextView titleView;

    public static void open(Context context, String url, String title) {
        Intent intent = new Intent(context, OriginalWebActivity.class);
        intent.putExtra(EXTRA_URL, url);
        intent.putExtra(EXTRA_TITLE, title);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        setTitle(title == null ? "原站" : title);

        CookieManager.getInstance().setAcceptCookie(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 247, 244));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(8), dp(8), dp(8));
        toolbar.setBackgroundColor(Color.rgb(247, 247, 244));

        Button backButton = makeButton("返回");
        backButton.setOnClickListener(v -> goBackOrFinish());

        titleView = new TextView(this);
        titleView.setText(title == null ? "原站" : title);
        titleView.setTextColor(Color.rgb(32, 33, 36));
        titleView.setTextSize(16);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(1);

        Button doneButton = makeButton("完成");
        doneButton.setOnClickListener(v -> finishWithCookies());

        toolbar.addView(backButton, new LinearLayout.LayoutParams(dp(88), dp(44)));
        toolbar.addView(titleView, new LinearLayout.LayoutParams(0, dp(44), 1));
        toolbar.addView(doneButton, new LinearLayout.LayoutParams(dp(88), dp(44)));
        root.addView(toolbar);

        webView = new WebView(this);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                handleNgaLoginMessage(consoleMessage.message());
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                if (view.getTitle() != null && !view.getTitle().trim().isEmpty()) {
                    titleView.setText(view.getTitle());
                }
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));
        setContentView(root);

        if (url != null && !url.isEmpty()) {
            webView.loadUrl(url);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        CookieManager.getInstance().flush();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        goBackOrFinish();
    }

    private void goBackOrFinish() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finishWithCookies();
        }
    }

    private void finishWithCookies() {
        CookieManager.getInstance().flush();
        finish();
    }

    private void handleNgaLoginMessage(String message) {
        if (message == null || !message.startsWith("loginSuccess : ")) {
            return;
        }

        try {
            String json = message.substring("loginSuccess : ".length()).trim();
            JSONObject payload = new JSONObject(json);
            String uid = payload.optString("uid", "");
            String token = payload.optString("token", "");
            if (uid.isEmpty() || token.isEmpty()) {
                return;
            }

            CookieManager cookieManager = CookieManager.getInstance();
            setCookie(cookieManager, "https://bbs.nga.cn", uid, token);
            setCookie(cookieManager, "https://ngabbs.com", uid, token);
            setCookie(cookieManager, "https://www.nga.cn", uid, token);
            cookieManager.flush();

            Toast.makeText(this, "NGA 登录完成", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception ignored) {
            // Keep the login WebView usable if NGA changes the callback payload.
        }
    }

    private void setCookie(CookieManager cookieManager, String url, String uid, String cid) {
        cookieManager.setCookie(url, "ngaPassportUid=" + uid + "; Path=/");
        cookieManager.setCookie(url, "ngaPassportCid=" + cid + "; Path=/");
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(32, 33, 36));
        button.setBackgroundColor(Color.rgb(236, 235, 230));
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
