package dev.bbsfusion.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Button;
import android.widget.Toast;

import dev.bbsfusion.R;
import dev.bbsfusion.core.GitHubReleases;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdateDialog {
    private AppUpdateDialog() {}

    public static AlertDialog show(Activity activity) {
        String version = currentVersion(activity);
        String installed = activity.getString(R.string.update_installed,
                version.isEmpty() ? activity.getString(R.string.update_unknown_version) : version);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_entry)
                .setMessage(installed)
                .setNeutralButton(R.string.update_close, null)
                .setNegativeButton(R.string.update_github, null)
                .setPositiveButton(R.string.update_check, null)
                .create();
        dialog.setOnDismissListener(ignored -> executor.shutdownNow());
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> openReleases(activity));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setAllCaps(false);
            Button check = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            check.setOnClickListener(v -> {
                check.setEnabled(false);
                dialog.setMessage(installed + "\n\n" + activity.getString(R.string.update_checking));
                executor.execute(() -> {
                    String result;
                    try {
                        String latest = GitHubReleases.fetchLatestVersion();
                        result = describeResult(activity, version, latest);
                    } catch (GitHubReleases.RateLimitedException error) {
                        result = activity.getString(R.string.update_rate_limited);
                    } catch (IOException error) {
                        result = activity.getString(R.string.update_failed);
                    }
                    String message = installed + "\n\n" + result;
                    activity.runOnUiThread(() -> {
                        if (dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed()) {
                            dialog.setMessage(message);
                            check.setEnabled(true);
                        }
                    });
                });
            });
        });
        dialog.show();
        return dialog;
    }

    private static String currentVersion(Activity activity) {
        try {
            String version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
            return version == null ? "" : version;
        } catch (PackageManager.NameNotFoundException error) {
            return "";
        }
    }

    private static String describeResult(Activity activity, String installed, String latest) {
        try {
            int comparison = GitHubReleases.compareVersions(installed, latest);
            if (comparison < 0) {
                return activity.getString(R.string.update_available, latest);
            }
            if (comparison == 0) {
                return activity.getString(R.string.update_current);
            }
            return activity.getString(R.string.update_ahead, latest);
        } catch (IllegalArgumentException error) {
            return activity.getString(R.string.update_latest, latest);
        }
    }

    private static void openReleases(Activity activity) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GitHubReleases.RELEASES_URL))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(activity, R.string.update_no_browser, Toast.LENGTH_SHORT).show();
        }
    }
}
