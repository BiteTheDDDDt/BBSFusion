package dev.bbsfusion;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import dev.bbsfusion.core.ConnectorRegistry;
import dev.bbsfusion.core.ForumConnector;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SessionActivity extends Activity {
    private final List<SessionRow> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("会话管理");
        CookieManager.getInstance().setAcceptCookie(true);
        setContentView(createContentView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        CookieManager.getInstance().flush();
        refreshRows();
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 247, 244));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(8), dp(8), dp(8));

        Button backButton = makeButton("返回");
        backButton.setOnClickListener(v -> finish());

        TextView title = new TextView(this);
        title.setText("会话管理");
        title.setTextColor(Color.rgb(32, 33, 36));
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);

        Button refreshButton = makeButton("刷新");
        refreshButton.setOnClickListener(v -> refreshRows());

        toolbar.addView(backButton, new LinearLayout.LayoutParams(dp(88), dp(44)));
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        toolbar.addView(refreshButton, new LinearLayout.LayoutParams(dp(88), dp(44)));
        root.addView(toolbar);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(4), dp(12), dp(16));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        rows.clear();
        for (ForumConnector connector : ConnectorRegistry.all()) {
            SessionRow row = new SessionRow(connector);
            rows.add(row);
            content.addView(createForumRow(row));
        }

        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));
        return root;
    }

    private View createForumRow(SessionRow row) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(12), dp(12), dp(12), dp(12));
        container.setBackgroundColor(Color.WHITE);

        TextView name = new TextView(this);
        name.setText(row.connector.name());
        name.setTextColor(Color.rgb(32, 33, 36));
        name.setTextSize(17);
        name.setGravity(Gravity.CENTER_VERTICAL);
        container.addView(name);

        row.status = new TextView(this);
        row.status.setTextColor(Color.rgb(95, 99, 104));
        row.status.setTextSize(13);
        row.status.setPadding(0, dp(4), 0, dp(10));
        container.addView(row.status);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        row.loginButton = makeButton("登录");
        row.loginButton.setOnClickListener(v -> OriginalWebActivity.open(
                this,
                row.connector.loginUrl(),
                row.connector.name() + " 登录"
        ));

        row.clearButton = makeButton("清除会话");
        row.clearButton.setOnClickListener(v -> confirmClear(row.connector));

        actions.addView(row.loginButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        clearParams.leftMargin = dp(8);
        actions.addView(row.clearButton, clearParams);
        container.addView(actions);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(10);
        container.setLayoutParams(params);
        return container;
    }

    private void confirmClear(ForumConnector connector) {
        new AlertDialog.Builder(this)
                .setTitle("清除 " + connector.name() + " 会话")
                .setMessage("会清除本机保存的该论坛 Cookie。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (dialog, which) -> {
                    int removed = clearCookies(connector);
                    refreshRows();
                    Toast.makeText(
                            this,
                            removed > 0 ? "已清除 " + connector.name() + " 会话" : "没有可清除的会话",
                            Toast.LENGTH_SHORT
                    ).show();
                })
                .show();
    }

    private void refreshRows() {
        CookieManager.getInstance().flush();
        for (SessionRow row : rows) {
            int count = cookieNames(row.connector).size();
            boolean hasCookies = count > 0;
            row.status.setText(hasCookies
                    ? "检测到本地 Cookie：" + count + " 项"
                    : "未检测到本地 Cookie");
            row.loginButton.setText(hasCookies ? "登录/切换" : "登录");
            row.clearButton.setEnabled(hasCookies);
        }
    }

    private int clearCookies(ForumConnector connector) {
        Set<String> names = cookieNames(connector);
        if (names.isEmpty()) {
            return 0;
        }

        CookieManager cookieManager = CookieManager.getInstance();
        for (String url : candidateUrls(connector)) {
            String domain = hostFromUrl(url);
            for (String name : names) {
                expireCookie(cookieManager, url, name, "");
                for (String candidate : domainCandidates(domain)) {
                    expireCookie(cookieManager, url, name, candidate);
                }
            }
        }
        cookieManager.flush();
        return names.size();
    }

    private static void expireCookie(
            CookieManager cookieManager,
            String url,
            String name,
            String domain
    ) {
        if (name.isEmpty()) {
            return;
        }
        String cookie = name + "=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT";
        if (!domain.isEmpty()) {
            cookie += "; Domain=" + domain;
        }
        cookieManager.setCookie(url, cookie);
    }

    private Set<String> cookieNames(ForumConnector connector) {
        Set<String> names = new LinkedHashSet<>();
        CookieManager cookieManager = CookieManager.getInstance();
        for (String url : candidateUrls(connector)) {
            String cookies = cookieManager.getCookie(url);
            if (cookies == null || cookies.trim().isEmpty()) {
                continue;
            }
            String[] parts = cookies.split(";");
            for (String part : parts) {
                int equals = part.indexOf('=');
                String name = equals >= 0 ? part.substring(0, equals) : part;
                name = name.trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static Set<String> domainCandidates(String host) {
        Set<String> domains = new LinkedHashSet<>();
        String value = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return domains;
        }

        addDomainCandidate(domains, value);
        if (value.startsWith("www.")) {
            addDomainCandidate(domains, value.substring(4));
        }

        String parent = parentDomain(value);
        if (!parent.isEmpty() && !parent.equals(value)) {
            addDomainCandidate(domains, parent);
        }
        return domains;
    }

    private static void addDomainCandidate(Set<String> domains, String domain) {
        if (domain == null || domain.isEmpty()) {
            return;
        }
        domains.add(domain);
        domains.add("." + domain);
    }

    private static String parentDomain(String host) {
        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return "";
        }
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private static List<String> candidateUrls(ForumConnector connector) {
        Set<String> urls = new LinkedHashSet<>();
        addUrl(urls, connector.homeUrl());
        addUrl(urls, connector.loginUrl());
        addHostRoot(urls, connector.homeUrl());
        addHostRoot(urls, connector.loginUrl());

        String id = connector.id().toLowerCase(Locale.ROOT);
        if ("s1".equals(id)) {
            addUrl(urls, "https://stage1st.com/");
            addUrl(urls, "https://www.stage1st.com/");
        } else if ("nga".equals(id)) {
            addUrl(urls, "https://bbs.nga.cn/");
            addUrl(urls, "https://ngabbs.com/");
            addUrl(urls, "https://www.nga.cn/");
            addUrl(urls, "https://nga.178.com/");
        } else if ("v2ex".equals(id)) {
            addUrl(urls, "https://v2ex.com/");
            addUrl(urls, "https://www.v2ex.com/");
        } else if ("linuxdo".equals(id)) {
            addUrl(urls, "https://linux.do/");
        }
        return new ArrayList<>(urls);
    }

    private static void addHostRoot(Set<String> urls, String url) {
        String host = hostFromUrl(url);
        if (!host.isEmpty()) {
            addUrl(urls, "https://" + host + "/");
        }
    }

    private static void addUrl(Set<String> urls, String url) {
        if (url != null && !url.trim().isEmpty()) {
            urls.add(url.trim());
        }
    }

    private static String hostFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "";
        }
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setTextColor(Color.rgb(32, 33, 36));
        button.setBackgroundColor(Color.rgb(236, 235, 230));
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static final class SessionRow {
        final ForumConnector connector;
        TextView status;
        Button loginButton;
        Button clearButton;

        SessionRow(ForumConnector connector) {
            this.connector = connector;
        }
    }
}
