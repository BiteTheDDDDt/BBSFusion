package dev.bbsfusion;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import dev.bbsfusion.core.ForumConnector;
import dev.bbsfusion.core.NgaLoginPolicy;
import dev.bbsfusion.ui.WindowInsetsHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class OriginalWebActivity extends Activity {
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_LOGIN_SITE = "login_site";
    private static final String WEB_STATE = "web_state";
    private static final int CHOOSE_FILE = 41;

    private WebView webView;
    private TextView titleView;
    private boolean ngaLogin;
    private int navigationGeneration;
    private int bridgedGeneration = -1;
    private WebMessagePort loginPort;
    private ValueCallback<Uri[]> fileCallback;
    private OnBackInvokedCallback backCallback;
    private boolean writingLoginCookies;

    public static void open(Context context, String url, String title) {
        Intent intent = new Intent(context, OriginalWebActivity.class);
        intent.putExtra(EXTRA_URL, url);
        intent.putExtra(EXTRA_TITLE, title);
        context.startActivity(intent);
    }

    public static void openLogin(Context context, ForumConnector connector) {
        Intent intent = new Intent(context, OriginalWebActivity.class);
        intent.putExtra(EXTRA_URL, connector.loginUrl());
        intent.putExtra(EXTRA_TITLE, connector.name() + " 登录");
        intent.putExtra(EXTRA_LOGIN_SITE, connector.id());
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        ngaLogin = "nga".equals(getIntent().getStringExtra(EXTRA_LOGIN_SITE))
                && NgaLoginPolicy.isTrustedLoginUrl(url);
        setTitle(title == null ? "原站" : title);

        CookieManager.getInstance().setAcceptCookie(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 247, 244));
        WindowInsetsHelper.apply(root);

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
        titleView.setEllipsize(TextUtils.TruncateAt.END);

        Button doneButton = makeButton("完成");
        doneButton.setOnClickListener(v -> finishWithCookies());

        toolbar.addView(backButton, new LinearLayout.LayoutParams(dp(88), dp(44)));
        toolbar.addView(titleView, new LinearLayout.LayoutParams(0, dp(44), 1));
        toolbar.addView(doneButton, new LinearLayout.LayoutParams(dp(88), dp(44)));
        root.addView(toolbar);

        Button browserButton = makeButton("在浏览器打开");
        browserButton.setOnClickListener(v -> openInBrowser());
        root.addView(browserButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        webView = new WebView(this);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(false);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                // Console output can originate in any frame. Never use it as authentication.
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                cancelFileSelection();
                fileCallback = callback;
                try {
                    Intent picker = params.createIntent();
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(picker, CHOOSE_FILE);
                } catch (ActivityNotFoundException error) {
                    cancelFileSelection();
                    Toast.makeText(OriginalWebActivity.this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                navigationGeneration++;
                closeLoginPort();
                cancelFileSelection();
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                // Login controls can be usable while unrelated page resources are still loading.
                installLoginBridge(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                if (view.getTitle() != null && !view.getTitle().trim().isEmpty()) {
                    titleView.setText(view.getTitle());
                }
                installLoginBridge(url);
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::goBackOrFinish;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }

        Bundle restoredWebState = savedInstanceState == null ? null : savedInstanceState.getBundle(WEB_STATE);
        if (restoredWebState != null && webView.restoreState(restoredWebState) != null) {
            // Do not navigate back to the initial URL after configuration changes.
        } else if (url != null && !url.isEmpty()) {
            webView.loadUrl(url);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        Bundle webState = new Bundle();
        webView.saveState(webState);
        state.putBundle(WEB_STATE, webState);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        navigationGeneration++;
        closeLoginPort();
        cancelFileSelection();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(new WebViewClient());
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        CookieManager.getInstance().flush();
        super.onDestroy();
    }

    @Override
    @SuppressLint("GestureBackNavigation") // API 26-32 fallback; API 33+ uses the registered dispatcher above.
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

    private void installLoginBridge(String url) {
        if (!ngaLogin || webView == null || !NgaLoginPolicy.isTrustedLoginUrl(url)
                || !url.equals(webView.getUrl()) || bridgedGeneration == navigationGeneration) {
            return;
        }
        final int generation = navigationGeneration;
        final String nonce = UUID.randomUUID().toString();
        final String origin = NgaLoginPolicy.origin(url);
        bridgedGeneration = generation;
        // evaluateJavascript executes in the main frame. The transferred port is delivered only
        // to that frame and this exact HTTPS origin; iframe console messages never enter it.
        String script = "(function(){var nonce=" + JSONObject.quote(nonce) + ";var port=null,pending=null;"
                + "var listener=function(e){if(e.data!==nonce||!e.ports||!e.ports[0])return;"
                + "window.removeEventListener('message',listener);port=e.ports[0];"
                + "if(pending!==null){port.postMessage(pending);pending=null;}};"
                + "window.addEventListener('message',listener);"
                + "['log','info','warn','error','debug'].forEach(function(method){"
                + "var original=console[method];if(typeof original!=='function')return;"
                + "console[method]=function(){try{"
                + "var a=Array.prototype.slice.call(arguments);"
                + "var s=a.map(function(v){return typeof v==='object'?JSON.stringify(v):String(v);}).join(' ');"
                + "if(s.indexOf('loginSuccess : ')===0&&s.length<=8192){"
                + "if(port)port.postMessage(s);else pending=s;}"
                + "}catch(ignore){}return original.apply(console,arguments);};});return true;})()";
        webView.evaluateJavascript(script, result -> {
            if (!validLoginPage(generation, url)) {
                return;
            }
            if (!"true".equals(result)) {
                bridgedGeneration = -1;
                return;
            }
            try {
                WebMessagePort[] ports = webView.createWebMessageChannel();
                loginPort = ports[0];
                loginPort.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
                    @Override
                    public void onMessage(WebMessagePort port, WebMessage message) {
                        if (port == loginPort && validLoginPage(generation, url)) {
                            handleNgaLoginMessage(message.getData());
                        }
                    }
                });
                webView.postWebMessage(new WebMessage(nonce, new WebMessagePort[] {ports[1]}), Uri.parse(origin));
            } catch (IllegalStateException ignored) {
                closeLoginPort();
            }
        });
    }

    private boolean validLoginPage(int generation, String url) {
        return !isFinishing() && !isDestroyed() && ngaLogin && webView != null
                && NgaLoginPolicy.isCurrentLoginPage(generation, navigationGeneration, url, webView.getUrl());
    }

    private void closeLoginPort() {
        if (loginPort != null) {
            loginPort.close();
            loginPort = null;
        }
        bridgedGeneration = -1;
    }

    private void handleNgaLoginMessage(String message) {
        if (writingLoginCookies || message == null || message.length() > 8192
                || !message.startsWith("loginSuccess : ")) {
            return;
        }

        try {
            String json = message.substring("loginSuccess : ".length()).trim();
            JSONObject payload = new JSONObject(json);
            String uid = payload.optString("uid", "");
            String token = payload.optString("token", "");
            if (!NgaLoginPolicy.validCredentials(uid, token)) {
                return;
            }

            CookieManager cookieManager = CookieManager.getInstance();
            final int generation = navigationGeneration;
            final String loginUrl = webView.getUrl();
            writingLoginCookies = true;
            String[] domains = {"https://bbs.nga.cn", "https://ngabbs.com", "https://www.nga.cn"};
            int[] pending = {domains.length * 2};
            boolean[] succeeded = {true};
            ValueCallback<Boolean> callback = success -> {
                succeeded[0] &= Boolean.TRUE.equals(success);
                if (--pending[0] == 0) {
                    cookieManager.flush();
                    writingLoginCookies = false;
                    if (validLoginPage(generation, loginUrl)) {
                        Toast.makeText(this, succeeded[0] ? "NGA 登录完成" : "会话保存未完成，请重试", Toast.LENGTH_SHORT).show();
                        if (succeeded[0]) {
                            finish();
                        }
                    }
                }
            };
            for (String domain : domains) {
                cookieManager.setCookie(domain, "ngaPassportUid=" + uid + "; Path=/; Secure; HttpOnly; SameSite=Lax", callback);
                cookieManager.setCookie(domain, "ngaPassportCid=" + token + "; Path=/; Secure; HttpOnly; SameSite=Lax", callback);
            }
        } catch (Exception ignored) {
            writingLoginCookies = false;
            // Keep the login WebView usable if NGA changes the callback payload.
        }
    }

    private void cancelFileSelection() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CHOOSE_FILE || fileCallback == null) {
            return;
        }
        ValueCallback<Uri[]> callback = fileCallback;
        fileCallback = null;
        Uri[] selected = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        List<Uri> readable = new ArrayList<>();
        if (selected != null) {
            for (Uri uri : selected) {
                if (uri != null && "content".equals(uri.getScheme()) && readable.size() < 32) {
                    readable.add(uri);
                }
            }
        }
        callback.onReceiveValue(readable.isEmpty() ? null : readable.toArray(new Uri[0]));
    }

    private void openInBrowser() {
        String url = webView == null ? null : webView.getUrl();
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
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
