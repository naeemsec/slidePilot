
package com.slidepilot.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.slidepilot.app.databinding.UpdateAppDialogBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdateChecker {

    private static final String REPOSITORY_URL =
            "https://github.com/naeemsec/slidePilot";

    private static final int MAX_API_BYTES = 1024 * 1024;
    private static final long MAX_APK_BYTES = 200L * 1024L * 1024L;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private volatile boolean closed;
    private boolean resumed;
    private boolean checking;
    private boolean downloading;
    private boolean installPromptShowing;

    private Result pending;
    private AlertDialog dialog;
    private UpdateAppDialogBinding activeBinding;
    private File downloadedApk;
    private String downloadingVersion;

    public AppUpdateChecker(Activity activity) {
        this.activity = activity;
    }

    public void check(boolean manual) {
        if (closed) return;

        if (checking || downloading) {
            if (manual) toast("An update operation is already in progress.");
            return;
        }

        if (dialog != null) return;

        String repository = REPOSITORY_URL.replaceAll("/+$", "")
                .replaceAll("\\.git$", "");

        if (!repository.matches(
                "https://github\\.com/[A-Za-z0-9-]+/[A-Za-z0-9_.-]+")) {
            complete(Result.message(manual,
                    "The update repository is not configured."));
            return;
        }

        final String installed;

        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            installed = info.versionName;
            versionParts(installed);
        } catch (Exception e) {
            complete(Result.message(manual,
                    "Could not read the installed app version."));
            return;
        }

        checking = true;

        if (manual) toast("Checking for updates...");

        executor.execute(() -> {
            Result result;

            try {
                result = fetchRelease(repository, installed, manual);
            } catch (Exception e) {
                Diagnostics.add("UPDATE failed: " + e.getClass().getSimpleName()
                        + " " + e.getMessage());
                result = Result.message(manual, friendlyError(e));
            }

            Result finalResult = result;
            main.post(() -> {
                if (!closed) complete(finalResult);
            });
        });
    }
    private static String friendlyError(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.startsWith("GitHub HTTP 403") || m.startsWith("GitHub HTTP 429"))
            return "GitHub temporarily refused the update check. Please try again later.";
        if (m.startsWith("GitHub HTTP 404"))
            return "No public release was found for this app.";
        if (e instanceof IllegalArgumentException)
            return "The latest release tag is not a version number like v1.1.0.";
        return "Couldn't check for updates. Check your internet connection and try again.";
    }
    private Result fetchRelease(String repository, String installed, boolean manual) throws Exception {
        String path = repository.substring("https://github.com/".length());
        JSONObject release = getJson(
                "https://api.github.com/repos/" + path + "/releases/latest");

        if (release.optBoolean("draft")
                || release.optBoolean("prerelease")) {
            return Result.message(manual,
                    "No supported stable release was found.");
        }

//        String latest = release.optString("tag_name", "").trim();
        String latest = release.optString("tag_name", "").trim();
        java.util.regex.Matcher tag = java.util.regex.Pattern
                .compile("[0-9]+(?:\\.[0-9]+){0,3}").matcher(latest);
        if (!tag.find()) throw new IllegalArgumentException("Release tag has no version number");
        latest = tag.group();

        if (compareVersions(latest, installed) <= 0) {
            String version = installed.replaceFirst("^[vV]", "");
            return Result.message(manual,
                    "✓ You're using the latest version\n"
                            + "SlidePilot v" + version);
        }

        String apkUrl = null;
        JSONArray assets = release.optJSONArray("assets");

        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.optString("name", "");

                if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")
                        && "uploaded".equals(asset.optString("state"))) {
                    apkUrl = asset.optString("browser_download_url", "");
                    break;
                }
            }
        }

        if (apkUrl == null || apkUrl.isEmpty()) {
            return Result.message(manual,
                    "An update was found, but its APK file is missing "
                            + "from the GitHub release.");
        }

        // Only download APK assets hosted by the expected GitHub repository.
        Uri parsed = Uri.parse(apkUrl);
        String expectedPrefix = repository + "/releases/download/";

        if (!"https".equalsIgnoreCase(parsed.getScheme())
                || !"github.com".equalsIgnoreCase(parsed.getHost())
                || !parsed.getPath().startsWith(
                Uri.parse(expectedPrefix).getPath())) {
            return Result.message(manual,
                    "The release APK URL is not valid.");
        }

        Result result = new Result();
        result.update = true;
        result.manual = manual;
        result.installed = installed;
        result.latest = latest;
        result.title = release.optString("name", "").trim();

        if (result.title.isEmpty()) {
            result.title = "SlidePilot " + latest;
        }

        result.notes = release.optString("body", "").trim();

        if (result.notes.isEmpty()) {
            result.notes = "No release notes were provided.";
        }

        result.apkUrl = apkUrl;
        result.apkSize = 0;

        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (apkUrl.equals(
                        asset.optString("browser_download_url"))) {
                    result.apkSize = asset.optLong("size", 0);
                    break;
                }
            }
        }

        return result;
    }

    private JSONObject getJson(String address) throws Exception {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection)
                    new URL(address).openConnection();

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setUseCaches(false);
            connection.setRequestProperty(
                    "Accept", "application/vnd.github+json");
            connection.setRequestProperty(
                    "User-Agent", "SlidePilot-Android");

            int status = connection.getResponseCode();
            Diagnostics.add("UPDATE HTTP=" + status);

            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub HTTP " + status);
            }

            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output =
                         new ByteArrayOutputStream()) {

                byte[] buffer = new byte[4096];
                int count;

                while ((count = input.read(buffer)) != -1) {
                    if (closed) throw new IOException("Cancelled");

                    if (output.size() + count > MAX_API_BYTES) {
                        throw new IOException("API response too large");
                    }

                    output.write(buffer, 0, count);
                }

                return new JSONObject(new String(
                        output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void complete(Result result) {
        checking = false;
        if (closed) return;

        pending = result;
        presentPending();
    }

    private void presentPending() {
        if (closed || !resumed || pending == null || dialog != null
                || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        Result result = pending;

        if (!result.update) {
            pending = null;
            if (result.manual) toast(result.message);
            return;
        }

        pending = null;

        UpdateAppDialogBinding binding = UpdateAppDialogBinding.inflate(LayoutInflater.from(activity));

        activeBinding = binding;

        binding.updateCurrentVersion.setText(result.installed);
        binding.updateVersion.setText(result.latest);
        binding.updateReleaseName.setText(result.title);
        binding.updateReleaseNotes.setText(result.notes);

        if (binding.updateProgressContainer != null) {
            binding.updateProgressContainer.setVisibility(View.GONE);
        }
        binding.updateProgressBar.setProgress(0);
        binding.updateProgressPercent.setText("0%");
        binding.updateProgressStatus.setText("Preparing download...");

        AlertDialog created = new AlertDialog.Builder(activity)
                .setView(binding.getRoot())
                .create();

        binding.updateRemind.setOnClickListener(v -> {
            created.dismiss();
        });

        binding.updateNowBtn.setOnClickListener(v -> {
            if (downloading) return;

            downloading = true;
            downloadingVersion = result.latest;

            binding.updateRemind.setEnabled(false);
            binding.updateNowBtn.setEnabled(false);
            binding.updateNowBtn.setText("Downloading...");
            binding.updateProgressContainer.setVisibility(View.VISIBLE);
            binding.updateProgressStatus.setText("Connecting to GitHub...");

            startDownload(result);
        });

        created.setCanceledOnTouchOutside(false);
        created.setOnDismissListener(v -> {
            if (dialog == created) dialog = null;
            if (activeBinding == binding) activeBinding = null;
        });

        dialog = created;
        created.show();

        if (created.getWindow() != null) {
            created.getWindow().setBackgroundDrawable(
                    new ColorDrawable(Color.TRANSPARENT));
        }
    }

    private void startDownload(Result result) {
        executor.execute(() -> {
            File temporary = null;

            try {
                File directory = activity.getExternalFilesDir(
                        Environment.DIRECTORY_DOWNLOADS);

                if (directory == null) {
                    throw new IOException("Download storage unavailable");
                }

                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IOException("Cannot create download directory");
                }

                temporary = new File(directory, "slidepilot-update.apk.part");
                File destination = new File(directory, "slidepilot-update.apk");

                downloadFile(result, temporary);

                if (closed) throw new IOException("Download cancelled");

                if (!temporary.renameTo(destination)) {
                    try (InputStream input = new FileInputStream(temporary);
                         FileOutputStream output =
                                 new FileOutputStream(destination)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }
                    }

                    if (!temporary.delete()) {
                        temporary.deleteOnExit();
                    }
                }


                if (!isZipFile(destination)) {
                    destination.delete();
                    throw new IOException("Downloaded file is not a valid APK archive");
                }

                PackageManager pm = activity.getPackageManager();

                PackageInfo apkInfo = pm.getPackageArchiveInfo(
                        destination.getAbsolutePath(), 0);

                if (apkInfo == null
                        || !activity.getPackageName().equals(apkInfo.packageName)) {
                    destination.delete();
                    throw new IOException("APK does not belong to SlidePilot");
                }

                if (compareVersions(apkInfo.versionName, result.latest) != 0) {
                    destination.delete();
                    throw new IOException("APK version does not match the release");
                }


                main.post(() -> {
                    if (closed) return;

                    downloading = false;
                    downloadedApk = destination;

                    if (activeBinding != null) {
                        activeBinding.updateProgressBar.setProgress(100);
                        activeBinding.updateProgressPercent.setText("100%");
                        activeBinding.updateProgressStatus.setText(
                                "Download complete. Preparing installation...");
                    }

                    if (dialog != null) {
                        dialog.dismiss();
                    }

                    showInstallPrompt();
                });

            } catch (Exception e) {
                if (temporary != null) temporary.delete();

                main.post(() -> {
                    if (closed) return;

                    downloading = false;

                    if (activeBinding != null) {
                        activeBinding.updateProgressStatus.setText(
                                "Download failed. Please try again.");
                        activeBinding.updateNowBtn.setEnabled(true);
                        activeBinding.updateNowBtn.setText("Retry");
                        activeBinding.updateRemind.setEnabled(true);
                    }

                    toast("Update download failed: " + e.getMessage());
                });
            }
        });
    }

    private void downloadFile(Result result, File destination)
            throws Exception {

        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection)
                    new URL(result.apkUrl).openConnection();

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty(
                    "User-Agent", "SlidePilot-Android");

            int status = connection.getResponseCode();

            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Download server HTTP " + status);
            }

            long total = connection.getContentLengthLong();

            if (total > MAX_APK_BYTES
                    || (result.apkSize > 0 && total > 0
                    && total != result.apkSize)) {
                throw new IOException("Unexpected APK size");
            }

            if (total <= 0) total = result.apkSize;

            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(destination)) {

                byte[] buffer = new byte[16384];
                long downloaded = 0;
                int count;
                int lastProgress = -1;

                while ((count = input.read(buffer)) != -1) {
                    if (closed || Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download cancelled");
                    }

                    downloaded += count;

                    if (downloaded > MAX_APK_BYTES
                            || (total > 0 && downloaded > total)) {
                        throw new IOException("Invalid download size");
                    }

                    output.write(buffer, 0, count);

                    if (total > 0) {
                        int progress = (int) Math.min(
                                99, downloaded * 100L / total);

                        if (progress != lastProgress) {
                            lastProgress = progress;
                            final int value = progress;

                            main.post(() -> updateProgress(
                                    value, "Downloading update..."));
                        }
                    } else {
                        main.post(() -> updateProgress(
                                -1, "Downloading update..."));
                    }
                }

                output.getFD().sync();

                if (downloaded == 0
                        || (total > 0 && downloaded != total)) {
                    throw new IOException("Download was incomplete");
                }
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void updateProgress(int progress, String status) {
        if (closed || activeBinding == null) return;

        activeBinding.updateProgressStatus.setText(status);

        if (progress >= 0) {
            activeBinding.updateProgressBar.setIndeterminate(false);
            activeBinding.updateProgressBar.setProgress(progress);
            activeBinding.updateProgressPercent.setText(progress + "%");
        } else {
            activeBinding.updateProgressBar.setIndeterminate(true);
            activeBinding.updateProgressPercent.setText("Downloading...");
        }
    }

    private boolean isZipFile(File file) throws IOException {
        if (!file.isFile() || file.length() < 4
                || file.length() > MAX_APK_BYTES) {
            return false;
        }

        try (FileInputStream input = new FileInputStream(file)) {
            int a = input.read();
            int b = input.read();
            return a == 0x50 && b == 0x4B;
        }
    }

    private void showInstallPrompt() {
        if (closed || !resumed || downloadedApk == null
                || installPromptShowing
                || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        installPromptShowing = true;

        new AlertDialog.Builder(activity)
                .setTitle("Download complete")
                .setMessage("SlidePilot " + downloadingVersion
                        + " has been downloaded. Continue to Android's "
                        + "installer to install the update?")
                .setPositiveButton("Install", (d, which) ->
                        requestInstallation())
                .setNegativeButton("Later", (d, which) -> {
                    installPromptShowing = false;
                    toast("Update downloaded. You can install it later.");
                })
                .setOnDismissListener(d -> installPromptShowing = false)
                .show();
    }

    private void requestInstallation() {
        if (closed || downloadedApk == null) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !activity.getPackageManager()
                    .canRequestPackageInstalls()) {

                new AlertDialog.Builder(activity)
                        .setTitle("Allow installation")
                        .setMessage("Allow SlidePilot to install this "
                                + "downloaded update in Android settings. "
                                + "You can return here afterwards.")
                        .setNegativeButton("Cancel", (d, w) -> { })
                        .setPositiveButton("Open settings", (d, w) -> {
                            Intent settings = new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivity(settings);
                        })
                        .show();
                return;
            }

            Uri apkUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    downloadedApk);

            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(
                    apkUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            activity.startActivity(install);

        } catch (Exception e) {
            toast("Couldn't open Android installer. " + e.getMessage());
        }
    }

    public void previewUpdateDialog() {
        if (closed || activity.isFinishing() || activity.isDestroyed()
                || dialog != null) return;

        Result result = new Result();
        result.update = true;
        result.manual = true;
        result.installed = "1.0.0";
        result.latest = "1.1.0";
        result.title = "SlidePilot v1.1.0";
        result.notes = "• Improved Bluetooth connection\n"
                + "• Faster slide controls\n"
                + "• Fixed minor issues";

        // Preview mode does not download anything.
        result.apkUrl = "";
        pending = result;
        presentPending();
    }

    public void onResume() {
        if (closed) return;
        resumed = true;

        if (downloadedApk != null && downloadedApk.exists()) {
            showInstallPrompt();
        } else {
            presentPending();
        }
    }

    public void onPause() {
        resumed = false;

        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    public void close() {
        closed = true;
        resumed = false;
        pending = null;
        main.removeCallbacksAndMessages(null);
        executor.shutdownNow();

        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }

        activeBinding = null;
    }

    private void toast(String message) {
        if (!closed && !activity.isFinishing()
                && !activity.isDestroyed()) {
            main.post(() -> {
                if (!closed && !activity.isFinishing()
                        && !activity.isDestroyed()) {
                    Toast.makeText(activity, message,
                            Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private static String[] versionParts(String version) {
        if (version == null) {
            throw new IllegalArgumentException("Missing version");
        }

        String value = version.trim().replaceFirst("^[vV]", "");

        if (value.length() > 80
                || !value.matches("[0-9]+(?:\\.[0-9]+){0,3}")) {
            throw new IllegalArgumentException(
                    "Use numeric versions such as 1.0.2");
        }

        return value.split("\\.");
    }

    private static int compareVersions(String remote, String installed) {
        String[] first = versionParts(remote);
        String[] second = versionParts(installed);
        int length = Math.max(first.length, second.length);

        for (int i = 0; i < length; i++) {
            BigInteger a = i < first.length
                    ? new BigInteger(first[i]) : BigInteger.ZERO;
            BigInteger b = i < second.length
                    ? new BigInteger(second[i]) : BigInteger.ZERO;

            int comparison = a.compareTo(b);
            if (comparison != 0) return comparison;
        }

        return 0;
    }

    private static final class Result {
        boolean update;
        boolean manual;
        String message;
        String installed;
        String latest;
        String title;
        String notes;
        String apkUrl;
        long apkSize;

        static Result message(boolean manual, String message) {
            Result result = new Result();
            result.manual = manual;
            result.message = message;
            return result;
        }
    }
}