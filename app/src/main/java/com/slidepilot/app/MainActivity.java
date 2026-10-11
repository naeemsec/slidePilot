package com.slidepilot.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int CONNECT_PERMISSION = 10, ADVERTISE_PERMISSION = 11, DISCOVERABLE = 12;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<Integer> consumedVolume = new HashSet<>();
    private HidController controller;
    private SharedPreferences prefs;
    private boolean started, resumed, receiverRegistered, timerRunning, needsRestart;
    private long accumulated, timerAnchor;
    private TextView status, detail, hostName, timer;
    private Button connect, prepare, choose;
    private Switch volume;
    private View connectionFull, connectionCompact;
    private TextView compactHost;
    private boolean compactShown;
    private int lastWait;
    private final int[] remoteIds = {R.id.next, R.id.previous, R.id.start, R.id.current, R.id.black, R.id.end};
    private final Runnable ticker = new Runnable() {
        @Override public void run() { renderTimer(); renderCooldown(); if (started) ui.postDelayed(this, 250); }
    };
    private final BroadcastReceiver bluetoothEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!started) return;
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF) HidController.onBluetoothOff();
                if (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_OFF
                        || state == BluetoothAdapter.STATE_TURNING_OFF) restartController();
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction()) && controller != null) {
                controller.refreshBonds();
            }
        }
    };
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("slidepilot", MODE_PRIVATE);
        status = findViewById(R.id.status);
        detail = findViewById(R.id.statusDetail);
        hostName = findViewById(R.id.hostName);
        timer = findViewById(R.id.timer);
        connect = findViewById(R.id.connect);
        prepare = findViewById(R.id.prepare);
        choose = findViewById(R.id.chooseHost);
        volume = findViewById(R.id.volume);
        connectionFull = findViewById(R.id.connectionFull);
        connectionCompact = findViewById(R.id.connectionCompact);
        compactHost = findViewById(R.id.compactHost);
        findViewById(R.id.disconnectCompact).setOnClickListener(v -> { if (controller != null) controller.disconnect(); });
        configureInsets();
        if (savedInstanceState != null) {
            accumulated = savedInstanceState.getLong("elapsed");
            timerAnchor = savedInstanceState.getLong("anchor");
            timerRunning = savedInstanceState.getBoolean("running");
            if (timerAnchor > SystemClock.elapsedRealtime()) timerRunning = false;
        }
        volume.setChecked(prefs.getBoolean("volume", true));
        volume.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("volume", checked).apply());
        findViewById(R.id.getStarted).setOnClickListener(v -> {
            prefs.edit().putBoolean("onboarded", true).apply();
            showOnboarding(false);
            restartController();
            prepareBluetooth();
        });
        choose.setOnClickListener(v -> chooseHost());
        prepare.setOnClickListener(v -> prepareBluetooth());
        connect.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.connected() || controller.state() == HidController.State.CONNECTING) controller.disconnect();
            else controller.connect();
        });
        findViewById(R.id.pair).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.pair_title).setMessage(R.string.pair_body)
                .setPositiveButton(R.string.discoverable, (dialog, which) -> requestDiscoverable())
                .setNeutralButton(R.string.settings, (dialog, which) -> openIntent(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                .setNegativeButton(R.string.cancel, null).show());
        bindCommand(R.id.next, KeyboardReport.Command.NEXT);
        bindCommand(R.id.previous, KeyboardReport.Command.PREVIOUS);
        bindCommand(R.id.start, KeyboardReport.Command.START);
        bindCommand(R.id.current, KeyboardReport.Command.CURRENT);
        bindCommand(R.id.black, KeyboardReport.Command.BLACK);
        findViewById(R.id.end).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.end_title).setMessage(R.string.end_body)
                .setPositiveButton(R.string.end_confirm, (dialog, which) -> send(KeyboardReport.Command.END, v))
                .setNegativeButton(R.string.cancel, null).show());
        findViewById(R.id.timerToggle).setOnClickListener(v -> {
            if (timerRunning) { accumulated = elapsed(); timerRunning = false; }
            else { timerAnchor = SystemClock.elapsedRealtime(); timerRunning = true; }
            renderTimer();
        });
        findViewById(R.id.timerReset).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.timer_reset_title).setMessage(R.string.timer_reset_body)
                .setPositiveButton(R.string.reset, (dialog, which) -> {
                    accumulated = 0; timerRunning = false; renderTimer();
                }).setNegativeButton(R.string.cancel, null).show());
        findViewById(R.id.help).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.help_title).setMessage(R.string.help_body).setPositiveButton(R.string.close, null).show());
        findViewById(R.id.diagnostics).setOnClickListener(v -> showDiagnostics());
        findViewById(R.id.bluetoothSettings).setOnClickListener(v -> openIntent(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        findViewById(R.id.appSettings).setOnClickListener(v -> openIntent(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        showOnboarding(!prefs.getBoolean("onboarded", false));
    }
    @SuppressWarnings("deprecation")
    private void configureInsets() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        View root = findViewById(R.id.root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController bars = getWindow().getInsetsController();
            if (bars != null) {
                int flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                bars.setSystemBarsAppearance(dark ? 0 : flags, flags);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | (dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                int left = insets.getSystemWindowInsetLeft(), top = insets.getSystemWindowInsetTop();
                int right = insets.getSystemWindowInsetRight(), bottom = insets.getSystemWindowInsetBottom();
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
    private void showOnboarding(boolean show) {
        findViewById(R.id.onboarding).setVisibility(show ? View.VISIBLE : View.GONE);
        findViewById(R.id.workspace).setVisibility(show ? View.GONE : View.VISIBLE);
    }
    @Override protected void onStart() {
        super.onStart();
        started = true;
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        // Bluetooth broadcasts can originate in the privileged Bluetooth package, not just system UID.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothEvents, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(bluetoothEvents, filter);
        receiverRegistered = true;
        ui.post(ticker);
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (controller == null || needsRestart || !controller.hasPermission()) restartController();
    }
    @Override protected void onPause() {
        resumed = false;
        consumedVolume.clear();
        if (controller != null) controller.releaseKeys();
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && controller != null) controller.releaseKeys();
    }
    @Override protected void onStop() {
        started = false;
        ui.removeCallbacks(ticker);
        if (receiverRegistered) { unregisterReceiver(bluetoothEvents); receiverRegistered = false; }
        if (controller != null) { controller.close(); controller = null; }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putLong("elapsed", accumulated);
        out.putLong("anchor", timerAnchor);
        out.putBoolean("running", timerRunning);
        super.onSaveInstanceState(out);
    }
    private void restartController() {
        if (!started || !prefs.getBoolean("onboarded", false)) return;
        if (!resumed) { needsRestart = true; return; }
        needsRestart = false;
        if (controller != null) controller.close();
        controller = new HidController(this, this::renderState);
        restoreHost();
        controller.start();
    }
    @SuppressLint("MissingPermission")
    private void restoreHost() {
        String address = prefs.getString("host", "");
        for (BluetoothDevice device : controller.pairedDevices()) {
            if (device.getAddress().equals(address)) { controller.select(device); break; }
        }
    }
    private void prepareBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this).setTitle(R.string.permission_title).setMessage(R.string.permission_detail)
                    .setPositiveButton(R.string.continue_permission, (dialog, which) ->
                            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, CONNECT_PERMISSION))
                    .setNegativeButton(R.string.cancel, null).show();
        } else if (controller != null && controller.state() == HidController.State.BLUETOOTH_OFF) {
            openIntent(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } else if (controller != null && controller.isRegistered()) {
            // A healthy registration must not be destroyed by the preparation button.
            controller.refreshBonds();
        } else restartController();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CONNECT_PERMISSION && requestCode != ADVERTISE_PERMISSION) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == ADVERTISE_PERMISSION) {
            if (granted) requestDiscoverable(); else toast(R.string.discoverable_permission);
        } else if (granted) { restartController(); prepareBluetoothIfOff(); }
        else { restartController(); detail.setText(R.string.permission_denied); }
    }
    private void prepareBluetoothIfOff() {
        if (controller != null && controller.state() == HidController.State.BLUETOOTH_OFF)
            openIntent(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
    }
    @SuppressLint("MissingPermission")
    private void chooseHost() {
        if (controller == null || !controller.hasPermission()) { prepareBluetooth(); return; }
        List<BluetoothDevice> devices = controller.pairedDevices();
        if (devices.isEmpty()) { toast(R.string.no_paired_devices); return; }
        try {
            devices.sort(Comparator.comparing(d -> d.getName() == null ? "" : d.getName(), String.CASE_INSENSITIVE_ORDER));
            String[] labels = new String[devices.size()];
            for (int i = 0; i < labels.length; i++) {
                BluetoothDevice device = devices.get(i);
                labels[i] = getString(R.string.device_format, name(device), device.getAddress());
            }
            new AlertDialog.Builder(this).setTitle(R.string.choose_host).setItems(labels, (dialog, which) -> {
                if (controller == null) return;
                BluetoothDevice device = devices.get(which);
                prefs.edit().putString("host", device.getAddress()).apply();
                controller.select(device);
                hostName.setText(name(device));
                renderState(controller.state(), controller.isRegistered() ? R.string.ready_detail : R.string.disconnected_detail);
            }).setNegativeButton(R.string.cancel, null).show();
        } catch (SecurityException error) { restartController(); }
    }
    @SuppressLint("MissingPermission")
    private String name(BluetoothDevice device) {
        try { String name = device.getName(); return name == null ? getString(R.string.unnamed_device) : name; }
        catch (SecurityException error) { return getString(R.string.unnamed_device); }
    }
    private void requestDiscoverable() {
        if (controller == null || !controller.isRegistered()) { toast(R.string.not_ready); return; }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_ADVERTISE}, ADVERTISE_PERMISSION);
            return;
        }
        try {
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
            startActivityForResult(intent, DISCOVERABLE);
        } catch (RuntimeException error) { toast(R.string.intent_failed); }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DISCOVERABLE) toast(resultCode > 0 ? R.string.discoverable_on : R.string.discoverable_cancelled);
    }
    private void renderState(HidController.State state, int message) {
        if (!started) return;
        int label;
        switch (state) {
            case PERMISSION_REQUIRED: label = R.string.state_PERMISSION_REQUIRED; break;
            case BLUETOOTH_OFF: label = R.string.state_BLUETOOTH_OFF; break;
            case UNSUPPORTED: label = R.string.state_UNSUPPORTED; break;
            case CHECKING: label = R.string.state_CHECKING; break;
            case NOT_PAIRED: label = R.string.state_NOT_PAIRED; break;
            case READY: label = R.string.state_READY; break;
            case CONNECTING: label = R.string.state_CONNECTING; break;
            case CONNECTED: label = R.string.state_CONNECTED; break;
            case CONNECTION_FAILED: label = R.string.state_CONNECTION_FAILED; break;
            default: label = R.string.state_DISCONNECTED;
        }
        boolean connected = state == HidController.State.CONNECTED;
        boolean connecting = state == HidController.State.CONNECTING;
        status.setText(label);
        status.setTextColor(getColor(connected ? R.color.success : R.color.text));
        detail.setText(message);
        for (int id : remoteIds) { View view = findViewById(id); view.setEnabled(connected); view.setAlpha(connected ? 1f : .4f); }
        if (connected) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        choose.setEnabled(!connected && !connecting);
        choose.setVisibility(connected ? View.GONE : View.VISIBLE);
        findViewById(R.id.pair).setVisibility(connected ? View.GONE : View.VISIBLE);
        showCompactConnection(connected);
        renderConnectButton();
        prepare.setVisibility(connected || connecting ? View.GONE : View.VISIBLE);
        prepare.setEnabled(state != HidController.State.CHECKING);
        prepare.setVisibility(controller != null && controller.isRegistered() ? View.GONE : prepare.getVisibility());
        prepare.setText(state == HidController.State.PERMISSION_REQUIRED ? R.string.grant_permission
                : state == HidController.State.BLUETOOTH_OFF ? R.string.enable_bluetooth : R.string.retry);
        if (controller != null && controller.selected() != null) hostName.setText(name(controller.selected()));
        else hostName.setText(R.string.no_host);
        compactHost.setText(hostName.getText());
    }

    /** Animates between the big connection card and the small "Connected" card. */
    private void showCompactConnection(boolean connected) {
        if (connected == compactShown) return;
        compactShown = connected;
        AutoTransition transition = new AutoTransition();
        transition.setDuration(320);
        TransitionManager.beginDelayedTransition(findViewById(R.id.workspace), transition);
        connectionFull.setVisibility(connected ? View.GONE : View.VISIBLE);
        connectionCompact.setVisibility(connected ? View.VISIBLE : View.GONE);
    }

    /** Connect button text and enabled state, including the wait countdown. */
    private void renderConnectButton() {
        if (controller == null) return;
        HidController.State s = controller.state();
        boolean connected = s == HidController.State.CONNECTED;
        boolean connecting = s == HidController.State.CONNECTING;
        int wait = controller.cooldownSeconds();
        lastWait = wait;
        connect.setText(connected ? getString(R.string.disconnect)
                : connecting ? getString(R.string.cancel)
                : wait > 0 ? getString(R.string.connect_wait, wait)
                : getString(R.string.connect));
        connect.setEnabled(connected || connecting
                || (wait == 0 && controller.isRegistered() && controller.selected() != null));
    }

    private void renderCooldown() {
        if (controller == null) return;
        if (controller.cooldownSeconds() != lastWait) renderConnectButton();
    }
    private void bindCommand(int id, KeyboardReport.Command command) {
        findViewById(id).setOnClickListener(view -> send(command, view));
    }
    private void send(KeyboardReport.Command command, View view) {
        if (resumed && controller != null && controller.send(command))
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_UP && consumedVolume.remove(code)) return true;
        if (resumed && hasWindowFocus() && volume.isChecked() && controller != null && controller.connected()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                consumedVolume.add(code);
                if (event.getRepeatCount() == 0) send(code == KeyEvent.KEYCODE_VOLUME_UP
                        ? KeyboardReport.Command.NEXT : KeyboardReport.Command.PREVIOUS, findViewById(R.id.next));
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private long elapsed() { return accumulated + (timerRunning ? Math.max(0, SystemClock.elapsedRealtime() - timerAnchor) : 0); }
    private void renderTimer() {
        long seconds = elapsed() / 1000;
        timer.setText(seconds >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60));
        ((Button) findViewById(R.id.timerToggle)).setText(timerRunning ? R.string.timer_pause : R.string.timer_start);
    }
    private void showDiagnostics() {
        String report = Diagnostics.snapshot();
        new AlertDialog.Builder(this).setTitle(R.string.diagnostics_title)
                .setMessage(getString(R.string.diagnostics_note) + "\n\n" + report)
                .setPositiveButton(R.string.copy, (dialog, which) -> {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("SlidePilot diagnostics", report));
                    toast(R.string.copied);
                })
                .setNeutralButton(R.string.diagnostics_clear, (dialog, which) -> {
                    Diagnostics.clear();
                    toast(R.string.diagnostics_cleared);
                })
                .setNegativeButton(R.string.close, null).show();
    }
    private void openIntent(Intent intent) {
        try { startActivity(intent); } catch (RuntimeException error) { toast(R.string.intent_failed); }
    }
    private void toast(int message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
