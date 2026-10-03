package dev.bbsfusion.ui;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/** Keeps native controls clear of system bars, cutouts and the keyboard. */
public final class WindowInsetsHelper {
    private WindowInsetsHelper() {
    }

    public static void apply(View root) {
        int left = root.getPaddingLeft();
        int top = root.getPaddingTop();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(left + safe.left, top + safe.top,
                        right + safe.right, bottom + safe.bottom);
            } else {
                view.setPadding(left + insets.getSystemWindowInsetLeft(),
                        top + insets.getSystemWindowInsetTop(),
                        right + insets.getSystemWindowInsetRight(),
                        bottom + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
    }
}
