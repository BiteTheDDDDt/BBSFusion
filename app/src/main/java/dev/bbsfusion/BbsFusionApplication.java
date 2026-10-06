package dev.bbsfusion;

import android.app.Application;
import android.webkit.CookieManager;

import dev.bbsfusion.core.S1LegacyCookieMigration;

public final class BbsFusionApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Repair local upgrade data before any screen starts a request or opens the original site.
        try {
            repairLegacyS1Cookie();
        } catch (RuntimeException ignored) {
            // An unavailable cookie store must not stop the app from opening its local screens.
        }
    }

    private void repairLegacyS1Cookie() {
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        boolean repaired = S1LegacyCookieMigration.repair(new S1LegacyCookieMigration.CookieStore() {
            @Override
            public String get(String url) {
                return cookies.getCookie(url);
            }

            @Override
            public void set(String url, String header) {
                cookies.setCookie(url, header);
            }
        });
        if (repaired) { cookies.flush(); }
    }
}
