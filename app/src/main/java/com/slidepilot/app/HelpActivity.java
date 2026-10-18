package com.slidepilot.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.TextView;

public final class HelpActivity extends Activity {

    private AppUpdateChecker updateChecker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        configureInsets();
        findViewById(R.id.helpBack).setOnClickListener(v -> finish());
        findViewById(R.id.helpDone).setOnClickListener(v -> finish());
        showVersion();
        updateChecker = new AppUpdateChecker(this);

        findViewById(R.id.checkForUpdates).setOnClickListener(
                view -> updateChecker.check(true));
//        findViewById(R.id.test).setOnClickListener(
//                view -> updateChecker.previewUpdateDialog()
//        );
    }

    @SuppressWarnings("deprecation")
    private void configureInsets() {
        View root = findViewById(R.id.helpRoot);
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : flags, flags);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                int left = insets.getSystemWindowInsetLeft();
                int top = insets.getSystemWindowInsetTop();
                int right = insets.getSystemWindowInsetRight();
                int bottom = insets.getSystemWindowInsetBottom();
                if (insets.getDisplayCutout() != null) {
                    left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
                    top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                    right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                    bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
                }
                view.setPadding(left, top, right, bottom);
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    @SuppressLint("DeprecatedSdkInt")
    private void showVersion() {
        String version = "—";
        try {
            @SuppressWarnings("deprecation")
            String packageVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (packageVersion != null) version = packageVersion;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            // Version is informational only; this does not affect presentation controls.
        }
        ((TextView) findViewById(R.id.helpVersion)).setText(getString(R.string.help_version_format, version));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (updateChecker != null) updateChecker.onResume();
    }

    @Override
    protected void onPause() {
        if (updateChecker != null) updateChecker.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (updateChecker != null) updateChecker.close();
        super.onDestroy();
    }
}
