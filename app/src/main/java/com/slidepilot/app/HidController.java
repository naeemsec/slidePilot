package com.slidepilot.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;

/** Main-thread confined HID session. One instance per visible Activity session. */
@SuppressLint("MissingPermission") // Each entry checks permission; binder calls also catch revocation.
public final class HidController {
    public enum State {
        PERMISSION_REQUIRED, BLUETOOTH_OFF, UNSUPPORTED, CHECKING, NOT_PAIRED,
        READY, CONNECTING, CONNECTED, DISCONNECTED, CONNECTION_FAILED
    }
    public interface Listener { void onState(State state, int message); }
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler callbackHandler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice selected, host;
    private boolean active = true, registered, registering, binding, blocked, busy;
    // restartAfterCleanup: re-register HID after a deliberate reset.
    // pendingConnect: press Connect automatically once the re-registration is done.
    private boolean restartAfterCleanup, pendingConnect;
    // All controllers and callbacks run on main, including Activity recreation.
    private static int cleanupsInFlight;
    private static long nextRegistrationAt;
    private static boolean cleanupUncertain;
    private static long cleanupUncertainAt;
    private static int radioGeneration;
    private int cleanupGeneration;
    public static void onBluetoothOff() {
        radioGeneration++;
        cleanupUncertain = false;
        nextRegistrationAt = 0;
    }
    private boolean closing, unregisterRequested;
    private final Runnable cleanupTimeout = () -> {
        Diagnostics.add("Unregister callback timeout; Bluetooth reset required");
        cleanupUncertain = cleanupGeneration == radioGeneration;
        if (cleanupUncertain) cleanupUncertainAt = SystemClock.uptimeMillis();
        finishCleanup();
    };
    private byte[] input = KeyboardReport.release();
    private byte leds;
    private long lastPress = -1000;
    private State state = State.DISCONNECTED;
    private int releaseRetries;
    private final java.util.ArrayDeque<KeyboardReport.Command> queue = new java.util.ArrayDeque<>();
    private boolean sequenceRunning;
    private final Runnable sequenceTask = this::sequenceStep;
    private final Runnable releaseTask = () -> releaseAttempt(releaseRetries);
    private final Runnable unblockTask = () -> blocked = false;
    private int lastMessage;
    private int disconnectWaits;
    // Continues a Connect that is waiting for the stack to leave DISCONNECTING.
    private final Runnable waitTask = () -> attemptConnect(false);
    private final Runnable registrationTimeout = () -> {
        fail(State.CONNECTION_FAILED, R.string.registration_timeout);
        if (closing) requestUnregister();
    };
    private final Runnable bindingTimeout = () -> fail(State.UNSUPPORTED, R.string.profile_timeout);
    private final Runnable connectionTimeout = () -> {
        if (state != State.CONNECTING) return;
        Diagnostics.add("CONNECT timeout (20s)");
        try {
            if (hid != null && selected != null) hid.disconnect(selected);
        } catch (RuntimeException error) {
            logError(error);
        }
        host = null;
        busy = false;
        input = KeyboardReport.release();
        // Keep registration available for another connection attempt.
        emit(State.CONNECTION_FAILED, R.string.connection_timeout);
    };

    // ---- Reconnect cooldown (change this number to wait longer or shorter) ----
    private static final long RECONNECT_COOLDOWN_MS = 6000;
    private long cooldownUntil;
    private int cooldownToken, cooldownExtensions;

    /** Whole seconds left before Connect may be used again. 0 means ready. */
    public int cooldownSeconds() {
        long left = cooldownUntil - SystemClock.uptimeMillis();
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    private void startCooldown() {
        cooldownUntil = SystemClock.uptimeMillis() + RECONNECT_COOLDOWN_MS;
        cooldownExtensions = 0;
        scheduleCooldownEnd(RECONNECT_COOLDOWN_MS);
    }

    private void scheduleCooldownEnd(long delay) {
        final int token = ++cooldownToken;
        handler.postDelayed(() -> finishCooldown(token), delay);
    }

    private void finishCooldown(int token) {
        if (token != cooldownToken || !active || closing || hid == null) return;
        try {
            // The laptop reconnected by itself during the cooldown: nothing to do.
            if (host != null) { cooldownUntil = 0; return; }
            if (selected != null
                    && hid.getConnectionState(selected) == BluetoothProfile.STATE_DISCONNECTING) {
                if (cooldownExtensions < 1) {
                    cooldownExtensions++;
                    cooldownUntil = SystemClock.uptimeMillis() + 1000;
                    scheduleCooldownEnd(1000);
                    return;
                }
                // Android never finished the disconnect. Reset the HID registration now,
                // so the next Connect starts from a clean stack.
                cooldownUntil = 0;
                blocked = false;
                resetRegistration();
                return;
            }
        } catch (RuntimeException error) { logError(error); }
        cooldownUntil = 0;
        blocked = false;
        if (registered && host == null && state != State.CONNECTING) idleState();
    }

    /** Unregister + register the HID app again. Clears a stack stuck in DISCONNECTING. */
    private void resetRegistration() {
        if (closing || hid == null) return;
        Diagnostics.add("Stack stuck in DISCONNECTING; re-registering HID");
        restartAfterCleanup = true;
        teardown();
        emit(State.CHECKING, R.string.checking_detail);
    }

    private static String profileStateName(int value) {
        switch (value) {
            case BluetoothProfile.STATE_DISCONNECTED: return "DISCONNECTED";
            case BluetoothProfile.STATE_CONNECTING: return "CONNECTING";
            case BluetoothProfile.STATE_CONNECTED: return "CONNECTED";
            case BluetoothProfile.STATE_DISCONNECTING: return "DISCONNECTING";
            default: return "UNKNOWN(" + value + ")";
        }
    }

    public HidController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }
    public boolean hasPermission() {
        return Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }
    public boolean isRegistered() { return active && !closing && registered; }
    public boolean isActive() { return active; }
    public State state() { return state; }
    public BluetoothDevice selected() { return selected; }
    public boolean connected() { return active && registered && host != null && state == State.CONNECTED; }
    public void start() {
        if (!active || closing || binding || registering || registered) return;
        if (!hasPermission()) { emit(State.PERMISSION_REQUIRED, R.string.permission_detail); return; }
        if (adapter == null) { emit(State.UNSUPPORTED, R.string.no_bluetooth); return; }
        try {
            if (!adapter.isEnabled()) {
                cleanupUncertain = false;
                pendingConnect = false;
                emit(State.BLUETOOTH_OFF, R.string.bluetooth_off_detail);
                return;
            }
            if (cleanupsInFlight > 0 || SystemClock.uptimeMillis() < nextRegistrationAt) {
                if (state != State.CHECKING) emit(State.CHECKING, R.string.cleanup_wait);
                handler.postDelayed(this::start, 100);
                return;
            }
            if (cleanupUncertain && SystemClock.uptimeMillis() - cleanupUncertainAt > 8000) {
                Diagnostics.add("Cleanup unconfirmed after 8s; trying fresh registration");
                cleanupUncertain = false;
            }
            if (cleanupUncertain) {
                pendingConnect = false;
                emit(State.CONNECTION_FAILED, R.string.cleanup_uncertain);
                return;
            }
            if (state != State.CHECKING) emit(State.CHECKING, R.string.checking_detail);
            unregisterRequested = false;
            binding = true;
            if (!adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)) {
                fail(State.UNSUPPORTED, R.string.profile_unavailable);
            } else handler.postDelayed(bindingTimeout, 10000);
        } catch (RuntimeException error) { apiFailure(error); }
    }
    private final BluetoothProfile.ServiceListener serviceListener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            // Service callbacks are delivered on main by Android; explicitly serialize anyway.
            handler.post(() -> {
                if (!active || !binding) {
                    try { adapter.closeProfileProxy(profile, proxy); }
                    catch (RuntimeException e) { logError(e); }
                    return;
                }
                binding = false;
                handler.removeCallbacks(bindingTimeout);
                hid = (BluetoothHidDevice) proxy;
                try {
                    registering = true;
                    BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                            "SlidePilot", "Offline presentation keyboard", "SlidePilot",
                            BluetoothHidDevice.SUBCLASS1_KEYBOARD, KeyboardReport.descriptor());
                    // The return value is not registration state. Some firmware returns false
                    // while a native registration and its callback are still pending.
                    // Do not unregister or close the proxy before that callback arrives.
                    hid.registerApp(sdp, null, null, callbackHandler::post, callback);
                    handler.postDelayed(registrationTimeout, 10000);
                } catch (RuntimeException error) { apiFailure(error); }
            });
        }
        @Override public void onServiceDisconnected(int profile) {
            handler.post(() -> {
                if (active && hid != null) fail(State.DISCONNECTED, R.string.service_lost);
            });
        }
    };
    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean value) {
            if (closing) {
                registering = false;
                registered = value;
                if (value) requestUnregister();
                else finishCleanup();
                return;
            }
            if (!active || hid == null) {
                if (!value && unregisterRequested && cleanupGeneration == radioGeneration) {
                    cleanupUncertain = false;
                    Diagnostics.add("Late callback confirmed HID cleanup; Retry is available");
                }
                return;
            }
            handler.removeCallbacks(registrationTimeout);
            registering = false;
            registered = value;
            if (!value) {
                // Android has already confirmed unregistration; do not unregister twice.
                fail(State.CONNECTION_FAILED, R.string.registration_lost);
                return;
            }
            idleState();
            // A virtual cable alone is NOT proof of an HID connection.
            try {
                if (pluggedDevice != null && hid.getConnectionState(pluggedDevice) == BluetoothProfile.STATE_CONNECTED)
                    acceptHost(pluggedDevice);
            } catch (RuntimeException error) { apiFailure(error); }
            // Registration was reset because the stack was stuck: continue the user's Connect.
            if (pendingConnect) {
                pendingConnect = false;
                connect();
            }
        }
        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int value) {
            if (!active || closing || hid == null || !registered || device == null) return;

            boolean matchesSelected = device.equals(selected);
            boolean matchesHost = device.equals(host);

            Diagnostics.add("HID callback: " + profileStateName(value));

            if (value == BluetoothProfile.STATE_CONNECTED) {
                acceptHost(device);
                return;
            }

            if (!matchesSelected && !matchesHost) return;

            if (value == BluetoothProfile.STATE_CONNECTING) {
                if (blocked) return;
                // Repeated CONNECTING callbacks must not restart our deadline.
                if (state != State.CONNECTING) {
                    emit(State.CONNECTING, R.string.connecting_detail);
                    handler.removeCallbacks(connectionTimeout);
                    handler.postDelayed(connectionTimeout, 20000);
                }
                return;
            }

            // A connection attempt is not complete until DISCONNECTED arrives.
            if (value == BluetoothProfile.STATE_DISCONNECTING && state == State.CONNECTING) return;

            if (value == BluetoothProfile.STATE_DISCONNECTED
                    || value == BluetoothProfile.STATE_DISCONNECTING) {

                boolean attemptFailed = state == State.CONNECTING;
                boolean preserveFailure = state == State.CONNECTION_FAILED;

                handler.removeCallbacks(connectionTimeout);
                handler.removeCallbacks(releaseTask);

                host = null;
                busy = false;
                input = KeyboardReport.release();

                if (preserveFailure) return;

                if (attemptFailed) emit(State.CONNECTION_FAILED, R.string.connection_rejected);
                else emit(State.DISCONNECTED, R.string.disconnected_detail);
            }
        }
        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (!usableCallback()) return;
            try {
                if (id != 0) { hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID); return; }
                byte[] data;
                if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) data = input.clone();
                else if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT) data = new byte[] {leds};
                else { hid.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ); return; }
                // Fixed-length reports only. Never allocate host-controlled buffer sizes.
                if (bufferSize > data.length) {
                    hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_PARAM);
                    return;
                }
                hid.replyReport(device, type, id, data);
            } catch (RuntimeException error) { apiFailure(error); }
        }
        @Override public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            if (!usableCallback()) return;
            try {
                byte result;
                if (id != 0) result = BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID;
                else if (type != BluetoothHidDevice.REPORT_TYPE_OUTPUT) result = BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ;
                else if (data == null || data.length != 1) result = BluetoothHidDevice.ERROR_RSP_INVALID_PARAM;
                else { leds = (byte) (data[0] & 0x1f); result = BluetoothHidDevice.ERROR_RSP_SUCCESS; }
                hid.reportError(device, result);
            } catch (RuntimeException error) { apiFailure(error); }
        }
        @Override public void onInterruptData(BluetoothDevice device, byte id, byte[] data) {
            if (usableCallback() && id == 0 && data != null && data.length == 1) leds = (byte) (data[0] & 0x1f);
        }
        @Override public void onSetProtocol(BluetoothDevice device, byte protocol) {
            if (!usableCallback()) return;
            // Descriptor deliberately has the same report layout in boot and report modes.
            if (protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE || protocol == BluetoothHidDevice.PROTOCOL_REPORT_MODE)
                releaseKeys();
            else {
                try { hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_PARAM); }
                catch (RuntimeException error) { apiFailure(error); }
            }
        }
        @Override public void onVirtualCableUnplug(BluetoothDevice device) {
            if (!active) return;
            if (device.equals(selected) || device.equals(host)) {
                Diagnostics.add("Virtual cable unplugged by host");
                blocked = true;
                host = null;
                input = KeyboardReport.release();
                busy = false;
                pendingConnect = false;
                handler.removeCallbacks(waitTask);
                handler.removeCallbacks(releaseTask);
                handler.removeCallbacks(connectionTimeout);
                emit(State.DISCONNECTED, R.string.unplugged_detail);
            }
        }
    };
    private boolean usableCallback() { return active && !closing && hid != null && registered && hasPermission(); }
    private void acceptHost(BluetoothDevice device) {
        try {
            if (blocked || (selected != null && !selected.equals(device))
                    || device.getBondState() != BluetoothDevice.BOND_BONDED) {
                hid.disconnect(device);
                Diagnostics.add("Rejected host (blocked=" + blocked + ")");
                return;
            }
            if (hid.getConnectionState(device) != BluetoothProfile.STATE_CONNECTED) return;
            selected = device;
            host = device;
            handler.removeCallbacks(waitTask);
            handler.removeCallbacks(connectionTimeout);
            // Clear any host-side modifier/key state before allowing a first command.
            input = KeyboardReport.release();
            if (!hid.sendReport(host, 0, input)) { fail(State.CONNECTION_FAILED, R.string.report_failed); return; }
            busy = false;
            leds = 0;
            emit(State.CONNECTED, R.string.connected_detail);
        } catch (RuntimeException error) { apiFailure(error); }
    }
    public List<BluetoothDevice> pairedDevices() {
        if (adapter == null || !hasPermission()) return new ArrayList<>();
        try { return new ArrayList<>(adapter.getBondedDevices()); }
        catch (RuntimeException error) { apiFailure(error); return new ArrayList<>(); }
    }
    public void refreshBonds() {
        try {
            if (selected != null && selected.getBondState() != BluetoothDevice.BOND_BONDED) {
                fail(State.NOT_PAIRED, R.string.not_paired_detail);
                return;
            }
            if (registered && host == null && state != State.CONNECTING) idleState();
        } catch (RuntimeException error) { apiFailure(error); }
    }
    private void idleState() {
        List<BluetoothDevice> paired = pairedDevices();
        if (!registered) return;
        emit(paired.isEmpty() ? State.NOT_PAIRED : State.READY,
                paired.isEmpty() ? R.string.not_paired_detail : R.string.ready_detail);
    }
    public void select(BluetoothDevice device) {
        if (host != null || state == State.CONNECTING) disconnect();
        selected = device;
        blocked = false;
        if (registered) idleState();
    }

    /** Connect button. */
    public void connect() {
        disconnectWaits = 0;
        attemptConnect(true);
    }

    /**
     * fresh=true: new request from the user. fresh=false: continuation of a Connect
     * that is waiting for the stack to leave DISCONNECTING (ignored if cancelled meanwhile).
     */
    private void attemptConnect(boolean fresh) {
        if (fresh) {
            if (cooldownSeconds() > 0) return;
            if (state == State.CONNECTING) return;
        } else if (state != State.CONNECTING) {
            return;
        }
        if (!usableCallback() || selected == null || connected()) return;

        try {
            int bond = selected.getBondState();
            int profileState = hid.getConnectionState(selected);

            if (fresh) Diagnostics.add("CONNECT bond=" + bond + " profile=" + profileStateName(profileState));

            if (bond != BluetoothDevice.BOND_BONDED) {
                emit(State.NOT_PAIRED, R.string.not_paired_detail);
                return;
            }

            if (profileState == BluetoothProfile.STATE_DISCONNECTING) {
                // Show "Connecting..." on the button right away.
                if (state != State.CONNECTING) emit(State.CONNECTING, R.string.connecting_detail);
                if (disconnectWaits++ < 3) {
                    handler.postDelayed(waitTask, 800);
                    return;
                }
                // Android never finished the previous disconnect: reset HID, then connect again.
                disconnectWaits = 0;
                pendingConnect = true;
                resetRegistration();
                return;
            }
            disconnectWaits = 0;

            blocked = false;
            handler.removeCallbacks(connectionTimeout);

            if (state != State.CONNECTING) emit(State.CONNECTING, R.string.connecting_detail);

            if (profileState == BluetoothProfile.STATE_CONNECTED) {
                acceptHost(selected);
                return;
            }

            // Await the existing attempt instead of sending connect twice.
            if (profileState == BluetoothProfile.STATE_CONNECTING) {
                handler.postDelayed(connectionTimeout, 20000);
                return;
            }

            if (!hid.connect(selected)) {
                Diagnostics.add("hid.connect() returned false");
                emit(State.CONNECTION_FAILED, R.string.connection_rejected);
                return;
            }

            handler.postDelayed(connectionTimeout, 20000);
        } catch (RuntimeException error) {
            apiFailure(error);
        }
    }

    public void disconnect() {
        Diagnostics.add("DISCONNECT by user");
        blocked = true;
        pendingConnect = false;
        disconnectWaits = 0;
        // Ignore the laptop's auto-reconnect only briefly, so you can reconnect
        // from the laptop afterwards without pressing Connect in the app first.
        handler.removeCallbacks(unblockTask);
        handler.postDelayed(unblockTask, 4000);

        handler.removeCallbacks(waitTask);
        handler.removeCallbacks(connectionTimeout);
        handler.removeCallbacks(releaseTask);
        handler.removeCallbacks(sequenceTask);
        queue.clear();
        sequenceRunning = false;

        BluetoothDevice target = host != null
                ? host
                : (state == State.CONNECTING ? selected : null);

        input = KeyboardReport.release();

        try {
            if (hid != null && registered && target != null) {
                if (host != null) hid.sendReport(target, 0, input);
                hid.disconnect(target);
            }
        } catch (RuntimeException error) {
            logError(error);
        }

        host = null;
        busy = false;

        // Only wait if a real link was closed or cancelled.
        if (target != null) startCooldown();

        // Connect can reuse the existing registration.
        emit(State.DISCONNECTED, R.string.disconnected_detail);
    }
    public boolean send(KeyboardReport.Command command) {
        if (!connected() || !hasPermission() || busy || sequenceRunning || SystemClock.uptimeMillis() - lastPress < 220) return false;
        try {
            if (host.getBondState() != BluetoothDevice.BOND_BONDED
                    || hid.getConnectionState(host) != BluetoothProfile.STATE_CONNECTED) {
                fail(State.DISCONNECTED, R.string.disconnected_detail); return false;
            }
            lastPress = SystemClock.uptimeMillis();
            busy = true;
            input = KeyboardReport.press(command);
            if (!hid.sendReport(host, 0, input)) {
                fail(State.CONNECTION_FAILED, R.string.report_failed); return false;
            }
            releaseRetries = 0;
            handler.postDelayed(releaseTask, 45);
            return true;
        } catch (RuntimeException error) { apiFailure(error); return false; }
    }
    /** Sends several keys one after another (digits then Enter). Returns false if it could not start. */
    public boolean sendSequence(List<KeyboardReport.Command> commands) {
        if (!connected() || !hasPermission() || busy || sequenceRunning || commands.isEmpty()) return false;
        queue.clear();
        queue.addAll(commands);
        sequenceRunning = true;
        sequenceStep();
        return true;
    }
    private void sequenceStep() {
        if (queue.isEmpty() || !connected()) { queue.clear(); sequenceRunning = false; return; }
        if (busy) { handler.postDelayed(sequenceTask, 20); return; }
        try {
            if (host.getBondState() != BluetoothDevice.BOND_BONDED
                    || hid.getConnectionState(host) != BluetoothProfile.STATE_CONNECTED) {
                queue.clear(); sequenceRunning = false;
                fail(State.DISCONNECTED, R.string.disconnected_detail);
                return;
            }
            lastPress = SystemClock.uptimeMillis();
            busy = true;
            input = KeyboardReport.press(queue.poll());
            if (!hid.sendReport(host, 0, input)) {
                queue.clear(); sequenceRunning = false;
                fail(State.CONNECTION_FAILED, R.string.report_failed);
                return;
            }
            releaseRetries = 0;
            handler.postDelayed(releaseTask, 45);
            handler.postDelayed(sequenceTask, 110);
        } catch (RuntimeException error) {
            queue.clear(); sequenceRunning = false;
            apiFailure(error);
        }
    }
    public void releaseKeys() {
        handler.removeCallbacks(releaseTask);
        if (host != null && registered) { busy = true; releaseRetries = 0; releaseAttempt(0); }
        else { input = KeyboardReport.release(); busy = false; }
    }
    private void releaseAttempt(int attempt) {
        input = KeyboardReport.release();
        if (!active || host == null || hid == null || !registered) { busy = false; return; }
        try {
            if (hid.sendReport(host, 0, input)) { busy = false; return; }
            if (attempt < 2) {
                releaseRetries = attempt + 1;
                handler.postDelayed(releaseTask, 35);
            }
            else fail(State.CONNECTION_FAILED, R.string.release_failed);
        } catch (RuntimeException error) { apiFailure(error); }
    }
    private void apiFailure(RuntimeException error) {
        logError(error);
        fail(error instanceof SecurityException ? State.PERMISSION_REQUIRED : State.CONNECTION_FAILED,
                error instanceof SecurityException ? R.string.permission_detail : R.string.api_failed);
    }
    private void logError(RuntimeException error) { Diagnostics.add("Bluetooth exception: " + error.getClass().getSimpleName()); }
    private void emit(State next, int message) {
        State previous = state;
        state = next;
        lastMessage = message;
        if (previous != next) Diagnostics.add("STATE " + previous + " -> " + next);
        listener.onState(next, message);
    }
    /** Re-sends the current state to the Activity (used when it returns from Help). */
    public void republish() { listener.onState(state, lastMessage); }
    private void fail(State next, int message) {
        pendingConnect = false;
        teardown();
        emit(next, message);
    }
    private void teardown() {
        if (closing) return;
        handler.removeCallbacksAndMessages(null);
        binding = false;
        busy = false;
        input = KeyboardReport.release();
        queue.clear();
        sequenceRunning = false;
        if (hid == null) { host = null; registered = registering = false; return; }
        closing = true;
        cleanupGeneration = radioGeneration;
        cleanupsInFlight++;
        if (host != null) {
            try { hid.sendReport(host, 0, input); }
            catch (RuntimeException error) { logError(error); }
            // Never disconnect a merely selected device with no established HID session.
            try { hid.disconnect(host); }
            catch (RuntimeException error) { logError(error); }
        }
        host = null;
        if (registering) {
            // Avoid racing unregister against a registration that has not completed.
            handler.postDelayed(this::requestUnregister, 10000);
        } else if (registered) requestUnregister();
        else finishCleanup();
    }
    private void requestUnregister() {
        if (!closing || hid == null || unregisterRequested) return;
        unregisterRequested = true;
        handler.removeCallbacksAndMessages(null);
        try { hid.unregisterApp(); }
        catch (RuntimeException error) { logError(error); }
        handler.postDelayed(cleanupTimeout, 2500);
    }
    private void finishCleanup() {
        if (!closing) return;
        handler.removeCallbacksAndMessages(null);
        BluetoothHidDevice old = hid;
        hid = null;
        closing = false;
        registered = registering = false;
        cleanupsInFlight--;
        nextRegistrationAt = SystemClock.uptimeMillis() + 350;
        if (old != null) {
            try { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, old); }
            catch (RuntimeException error) { logError(error); }
        }
        // Deliberate reset: register the HID app again.
        if (restartAfterCleanup) {
            restartAfterCleanup = false;
            if (active) handler.postDelayed(this::start, 150);
        }
    }
    public void close() {
        active = false;
        pendingConnect = false;
        teardown();
    }
}