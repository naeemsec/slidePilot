package com.presakey.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
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
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import com.presakey.app.databinding.UpdateAppDialogBinding;

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
            "https://github.com/naeemsec/presaKey";

    private static final int MAX_API_BYTES = 1024 * 1024;
    private static final long MAX_APK_BYTES = 200L * 1024L * 1024L;

    private static final String CHANNEL_ID = "presaKey_updates";
    private static final int NOTIFICATION_ID = 1001;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private volatile boolean closed;
    private volatile boolean isPaused;
    private boolean resumed;
    private boolean checking;
    private boolean downloading;
    private boolean installPromptShowing;

    private int lastProgress = 0;
    private Result currentResult;
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

        String latest = release.optString("tag_name", "").trim();
        java.util.regex.Matcher tag = java.util.regex.Pattern
                .compile("[0-9]+(?:\\.[0-9]+){0,3}").matcher(latest);
        if (!tag.find()) throw new IllegalArgumentException("Release tag has no version number");
        latest = tag.group();

        if (compareVersions(latest, installed) <= 0) {
            String version = installed.replaceFirst("^[vV]", "");
            return Result.message(manual,
                    "✓ You're using the latest version\n" + "PresaKey v" + version);
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
            result.title = "PresaKey " + latest;
        }

        result.notes = release.optString("body", "").trim();

        // Optional: Check if a custom update.json exists on the raw repository main branch
        try {
            JSONObject customJson = getJson("https://raw.githubusercontent.com/" + path + "/main/update.json");
            if (customJson != null && customJson.has("notes")) {
                Object notesObj = customJson.get("notes");
                if (notesObj instanceof JSONArray) {
                    result.notes = ((JSONArray) notesObj).toString();
                } else if (notesObj instanceof String) {
                    result.notes = (String) notesObj;
                }
            }
        } catch (Exception ignored) {
            // Fall back to release body if update.json is not present
        }

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
                    "User-Agent", "PresaKey-Android");

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
        currentResult = result;

        UpdateAppDialogBinding binding = UpdateAppDialogBinding.inflate(LayoutInflater.from(activity));
        activeBinding = binding;

        binding.updateCurrentVersion.setText(result.installed);
        binding.updateVersion.setText(result.latest);
        binding.updateReleaseName.setText(result.title);
        binding.updateReleaseNotes.setText(formatReleaseNotes(result.notes));

        if (downloadedApk != null && downloadedApk.exists()) {
            showCompleteState(binding);
        } else if (downloading) {
            showDownloadingState(binding, lastProgress);
        } else if (isPaused) {
            showPausedState(binding, lastProgress);
        } else {
            showIdleState(binding);
        }

        AlertDialog created = new AlertDialog.Builder(activity)
                .setView(binding.getRoot())
                .create();

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

    private void showIdleState(UpdateAppDialogBinding binding) {
        if (binding == null) return;
        binding.updateProgressContainer.setVisibility(View.GONE);
        binding.updateRemind.setText("Remind me Later");
        binding.updateRemind.setEnabled(true);
        binding.updateRemind.setOnClickListener(v -> {
            if (dialog != null) dialog.dismiss();
        });

        binding.updateNowBtn.setText("Update Now");
        binding.updateNowBtn.setEnabled(true);
        binding.updateNowBtn.setOnClickListener(v -> {
            if (downloading) return;
            downloading = true;
            isPaused = false;
            downloadingVersion = currentResult != null ? currentResult.latest : "";
            showDownloadingState(binding, lastProgress);
            startDownload(currentResult);
        });
    }

    private void showDownloadingState(UpdateAppDialogBinding binding, int progress) {
        if (binding == null) return;
        binding.updateProgressContainer.setVisibility(View.VISIBLE);
        binding.updateProgressStatus.setText("Downloading update...");
        updateProgressViews(binding, progress);

        // Simple button = Cancel
        binding.updateRemind.setText("Cancel");
        binding.updateRemind.setEnabled(true);
        binding.updateRemind.setOnClickListener(v -> {
            cancelDownloadAndReset();
        });

        // Blue Primary button = Pause
        binding.updateNowBtn.setText("Pause");
        binding.updateNowBtn.setEnabled(true);
        binding.updateNowBtn.setOnClickListener(v -> {
            isPaused = true;
            showPausedState(binding, lastProgress);
            showDownloadNotification(lastProgress, true);
        });
    }

    private void showPausedState(UpdateAppDialogBinding binding, int progress) {
        if (binding == null) return;
        binding.updateProgressContainer.setVisibility(View.VISIBLE);
        binding.updateProgressStatus.setText("Download paused (" + Math.max(0, progress) + "%)");
        updateProgressViews(binding, progress);

        // Simple button = Cancel
        binding.updateRemind.setText("Cancel");
        binding.updateRemind.setEnabled(true);
        binding.updateRemind.setOnClickListener(v -> {
            cancelDownloadAndReset();
        });

        // Blue Primary button = Resume
        binding.updateNowBtn.setText("Resume");
        binding.updateNowBtn.setEnabled(true);
        binding.updateNowBtn.setOnClickListener(v -> {
            if (downloading) return;
            downloading = true;
            isPaused = false;
            showDownloadingState(binding, lastProgress);
            startDownload(currentResult);
        });
    }

    private void showCompleteState(UpdateAppDialogBinding binding) {
        if (binding == null) return;
        binding.updateProgressContainer.setVisibility(View.VISIBLE);
        binding.updateProgressStatus.setText("✓ Download complete. Ready to install!");
        binding.updateProgressBar.setIndeterminate(false);
        binding.updateProgressBar.setProgress(100);
        binding.updateProgressPercent.setText("100%");

        binding.updateRemind.setText("Later");
        binding.updateRemind.setEnabled(true);
        binding.updateRemind.setOnClickListener(v -> {
            if (dialog != null) dialog.dismiss();
        });

        binding.updateNowBtn.setText("Install Now");
        binding.updateNowBtn.setEnabled(true);
        binding.updateNowBtn.setOnClickListener(v -> {
            requestInstallation();
        });
    }

    private void updateProgressViews(UpdateAppDialogBinding binding, int progress) {
        if (binding == null) return;
        if (progress >= 0) {
            binding.updateProgressBar.setIndeterminate(false);
            binding.updateProgressBar.setProgress(progress);
            binding.updateProgressPercent.setText(progress + "%");
        } else {
            binding.updateProgressBar.setIndeterminate(true);
            binding.updateProgressPercent.setText("Downloading...");
        }
    }

    private void cancelDownloadAndReset() {
        downloading = false;
        isPaused = false;
        lastProgress = 0;
        cancelNotification();
        try {
            File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (directory != null) {
                File temporary = new File(directory, "presakey-update.apk.part");
                if (temporary.exists()) temporary.delete();
            }
        } catch (Exception ignored) {}
        if (activeBinding != null) {
            showIdleState(activeBinding);
        }
    }

    private void startDownload(Result result) {
        if (result == null) return;
        createNotificationChannel();
        showDownloadNotification(lastProgress, false);

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

                temporary = new File(directory, "presakey-update.apk.part");
                File destination = new File(directory, "presakey-update.apk");

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
                    throw new IOException("APK does not belong to PresaKey");
                }

                if (compareVersions(apkInfo.versionName, result.latest) != 0) {
                    destination.delete();
                    throw new IOException("APK version does not match the release");
                }

                main.post(() -> {
                    if (closed) return;

                    downloading = false;
                    isPaused = false;
                    downloadedApk = destination;

                    showCompleteNotification();

                    if (activeBinding != null) {
                        showCompleteState(activeBinding);
                    } else {
                        showInstallPrompt();
                    }
                });

            } catch (DownloadPausedException e) {
                main.post(() -> {
                    if (closed) return;
                    downloading = false;
                    if (activeBinding != null) {
                        showPausedState(activeBinding, lastProgress);
                    }
                    showDownloadNotification(lastProgress, true);
                });
            } catch (Exception e) {
                if (temporary != null && !isPaused) temporary.delete();

                main.post(() -> {
                    if (closed) return;

                    downloading = false;
                    isPaused = false;

                    if (activeBinding != null) {
                        activeBinding.updateProgressStatus.setText(
                                "Download failed: " + e.getMessage());
                        activeBinding.updateNowBtn.setEnabled(true);
                        activeBinding.updateNowBtn.setText("Retry");
                        activeBinding.updateNowBtn.setOnClickListener(v -> {
                            downloading = true;
                            showDownloadingState(activeBinding, lastProgress);
                            startDownload(result);
                        });
                        activeBinding.updateRemind.setEnabled(true);
                        activeBinding.updateRemind.setText("Dismiss");
                        activeBinding.updateRemind.setOnClickListener(v -> {
                            if (dialog != null) dialog.dismiss();
                        });
                    }

                    cancelNotification();
                    toast("Update download failed: " + e.getMessage());
                });
            }
        });
    }

    private void downloadFile(Result result, File destination)
            throws Exception {

        HttpURLConnection connection = null;

        try {
            long existingSize = destination.exists() ? destination.length() : 0;
            String currentUrl = result.apkUrl;
            int redirects = 0;
            boolean append = false;

            while (redirects < 5) {
                connection = (HttpURLConnection) new URL(currentUrl).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent", "PresaKey-Android");

                if (existingSize > 0) {
                    connection.setRequestProperty("Range", "bytes=" + existingSize + "-");
                }

                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location != null && !location.isEmpty()) {
                        currentUrl = location;
                        connection.disconnect();
                        redirects++;
                        continue;
                    }
                }

                if (status == HttpURLConnection.HTTP_PARTIAL) {
                    append = true;
                } else if (status == HttpURLConnection.HTTP_OK) {
                    existingSize = 0;
                } else {
                    throw new IOException("Download server HTTP " + status);
                }
                break;
            }

            long total = connection.getContentLengthLong();
            if (append) {
                total = existingSize + (total > 0 ? total : 0);
            } else {
                if (total <= 0) total = result.apkSize;
            }

            if (total > MAX_APK_BYTES) {
                throw new IOException("Unexpected APK size");
            }

            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(destination, append)) {

                byte[] buffer = new byte[16384];
                long downloaded = existingSize;
                int count;

                while ((count = input.read(buffer)) != -1) {
                    if (closed || Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download cancelled");
                    }

                    if (isPaused) {
                        throw new DownloadPausedException();
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
                            final int val = progress;

                            main.post(() -> {
                                updateProgress(val, "Downloading update...");
                                showDownloadNotification(val, false);
                            });
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
        updateProgressViews(activeBinding, progress);
        activeBinding.updateProgressStatus.setText(status);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "App Updates",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Notifications for PresaKey app updates");
            NotificationManager nm = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void showDownloadNotification(int progress, boolean paused) {
        try {
            NotificationManager nm = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
            }

            Intent intent = new Intent(activity, HelpActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    activity, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
            );

            String title = paused ? "PresaKey Download Paused" : "Downloading PresaKey " + downloadingVersion;
            String text = paused ? (progress >= 0 ? progress + "% • Tap to resume or open" : "Download paused")
                    : (progress >= 0 ? progress + "% downloaded" : "Downloading...");

            NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setOngoing(!paused)
                    .setAutoCancel(paused)
                    .setContentIntent(pendingIntent);

            if (progress >= 0) {
                builder.setProgress(100, progress, false);
            } else {
                builder.setProgress(0, 0, true);
            }

            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception ignored) {}
    }

    private void showCompleteNotification() {
        try {
            NotificationManager nm = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
            }

            // Cancel any progress notification so OS rebuilds a clean, swipable complete notification
            nm.cancel(NOTIFICATION_ID);

            Intent installIntent = getInstallIntent();
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    activity, 0, installIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("PresaKey " + downloadingVersion + " Downloaded")
                    .setContentText("Tap to install update now")
                    .setProgress(0, 0, false)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setContentIntent(pendingIntent);

            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception ignored) {}
    }

    private void cancelNotification() {
        try {
            NotificationManager nm = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID);
            }
        } catch (Exception ignored) {}
    }

    private Intent getInstallIntent() {
        if (downloadedApk == null || !downloadedApk.exists()) {
            return new Intent(activity, HelpActivity.class);
        }
        Uri apkUri = FileProvider.getUriForFile(
                activity,
                activity.getPackageName() + ".fileprovider",
                downloadedApk);

        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(apkUri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return install;
    }

    private CharSequence formatReleaseNotes(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "• General performance improvements and bug fixes.";
        }

        String text = raw.trim();

        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                JSONArray array = new JSONArray(text);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < array.length(); i++) {
                    if (i > 0) sb.append("<br/><br/>");
                    sb.append("• ").append(array.getString(i));
                }
                String html = sb.toString();
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        ? Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
                        : Html.fromHtml(html);
            } catch (Exception ignored) {}
        }

        text = text.replaceAll("(?i)###?\\s*Installation[\\s\\S]*?(?=###?|\\z)", "");
        text = text.replaceAll("(?i)###?\\s*Version[\\s\\S]*?(?=###?|\\z)", "");

        String[] lines = text.split("\n");
        StringBuilder cleanNotes = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("---") || trimmed.startsWith("===")) {
                continue;
            }

            trimmed = trimmed.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");
            trimmed = trimmed.replaceAll("`([^`]+)`", "$1");

            if (trimmed.matches("^[\\*\\-\\•]\\s+.*")) {
                trimmed = "• " + trimmed.replaceFirst("^[\\*\\-\\•]\\s+", "");
            } else if (trimmed.matches("^[0-9]+\\.\\s+.*")) {
                trimmed = "• " + trimmed.replaceFirst("^[0-9]+\\.\\s+", "");
            } else {
                trimmed = "• " + trimmed;
            }

            if (cleanNotes.length() > 0) {
                cleanNotes.append("<br/><br/>");
            }
            cleanNotes.append(trimmed);
        }

        if (cleanNotes.length() == 0) {
            return "• General performance improvements and bug fixes.";
        }

        String html = cleanNotes.toString();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
        } else {
            return Html.fromHtml(html);
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
                .setMessage("PresaKey " + downloadingVersion
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
                        .setMessage("Allow PresaKey to install this "
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
        result.title = "PresaKey v1.1.0";
        result.notes = "[\"Improved Bluetooth connection stability\", \"Faster slide controls & responsiveness\", \"Fixed minor UI issues\"]";

        result.apkUrl = "";
        pending = result;
        presentPending();
    }

    public void onResume() {
        if (closed) return;
        resumed = true;

        if (downloadedApk != null && downloadedApk.exists()) {
            if (dialog == null) {
                presentPending();
            } else {
                showInstallPrompt();
            }
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
        cancelNotification();
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

    private static final class DownloadPausedException extends IOException {
        DownloadPausedException() {
            super("Download paused by user");
        }
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
