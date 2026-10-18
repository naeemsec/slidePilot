package com.slidepilot.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Keeps the HID session alive while connecting/connected and shows the status notification. */
@SuppressLint("MissingPermission")
public final class SlideService extends Service {
    static final String ACTION_DISCONNECT = "com.slidepilot.app.DISCONNECT";
    private static final String CHANNEL = "slidepilot_session", CHANNEL_ALERT = "slidepilot_alert";
    private static final int ONGOING_ID = 41, ALERT_ID = 42;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Controller that outlives the Activity while a session is connecting/connected. */
    static HidController current;
    private static HidController.Listener screen;   // visible Activity, null while stopped
    private static Context app;
    private static boolean visible, serviceUp, serviceRequested, wasActive, quiet;
    private static HidController.State lastState = HidController.State.DISCONNECTED;

    private static final Runnable STOP = () -> {
        if (app != null && !isActive(lastState)) {
            app.stopService(new Intent(app, SlideService.class));
            serviceRequested = false;
        }
    };
    private static final Runnable CLOSE_IF_IDLE = () -> {
        if (!visible && !isActive(lastState) && current != null) { current.close(); current = null; }
    };
    /** Every controller reports here; this forwards to the screen and updates the notification. */
    static final HidController.Listener RELAY = (state, message) -> {
        if (screen != null) screen.onState(state, message);
        onState(state);
    };

    static void attach(Context context) {
        app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                    app.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW));
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ALERT,
                    app.getString(R.string.notification_channel_alert), NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    static void setScreen(HidController.Listener listener, boolean isVisible) {
        screen = listener;
        visible = isVisible;
        if (isVisible && app != null) app.getSystemService(NotificationManager.class).cancel(ALERT_ID);
    }

    private static boolean isActive(HidController.State s) {
        return s == HidController.State.CONNECTING || s == HidController.State.CONNECTED;
    }

    private static void onState(HidController.State state) {
        if (app == null) return;
        lastState = state;
        boolean active = isActive(state);
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (active) {
            MAIN.removeCallbacks(STOP);
            if (serviceRequested) manager.notify(ONGOING_ID, build(app, state));
            else {
                try {
                    Intent intent = new Intent(app, SlideService.class);
                    if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent);
                    else app.startService(intent);
                    serviceRequested = true;
                } catch (RuntimeException error) {
                    Diagnostics.add("Foreground service not started: " + error.getClass().getSimpleName());
                }
            }
        } else {
            // Wait a moment if the service is still starting, so Android never sees start+stop at once.
            if (serviceRequested) MAIN.postDelayed(STOP, serviceUp ? 0 : 1200);
            if (wasActive && !visible) {
                if (!quiet && (state == HidController.State.DISCONNECTED
                        || state == HidController.State.CONNECTION_FAILED))
                    manager.notify(ALERT_ID, buildAlert(app, state));
                MAIN.post(CLOSE_IF_IDLE);
            }
            quiet = false;
        }
        wasActive = active;
    }

    private static String title(Context c, HidController.State s) {
        switch (s) {
            case CONNECTED: return c.getString(R.string.state_CONNECTED);
            case CONNECTING: return c.getString(R.string.state_CONNECTING);
            case CONNECTION_FAILED: return c.getString(R.string.state_CONNECTION_FAILED);
            default: return c.getString(R.string.state_DISCONNECTED);
        }
    }

    private static String hostText(Context c) {
        try {
            if (current != null && current.selected() != null) {
                String name = current.selected().getName();
                if (name != null) return name;
            }
        } catch (SecurityException ignored) { }
        return c.getString(R.string.unnamed_device);
    }

    private static PendingIntent openApp(Context c) {
        Intent intent = new Intent(c, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Notification.Builder builder(Context c, String channel) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
    }

    static Notification build(Context c, HidController.State s) {
        Intent off = new Intent(c, SlideService.class).setAction(ACTION_DISCONNECT);
        PendingIntent disconnect = PendingIntent.getService(c, 1, off,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title(c, s)).setContentText(hostText(c))
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(openApp(c))
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(c, android.R.drawable.ic_menu_close_clear_cancel),
                        c.getString(R.string.disconnect), disconnect).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private static Notification buildAlert(Context c, HidController.State s) {
        return builder(c, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title(c, s)).setContentText(hostText(c))
                .setAutoCancel(true).setContentIntent(openApp(c)).build();
    }

    @Override public void onCreate() {
        super.onCreate();
        serviceUp = true;
        serviceRequested = true;
        show();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            if (current != null && current.isActive()) { quiet = true; current.disconnect(); }
            else stopSelf();
            return START_NOT_STICKY;
        }
        show();
        return START_NOT_STICKY;
    }

    private void show() {
        try {
            Notification n = build(this, lastState);
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(ONGOING_ID, n);
        } catch (RuntimeException error) {
            Diagnostics.add("startForeground failed: " + error.getClass().getSimpleName());
            stopSelf();
        }
    }

    @Override public void onDestroy() {
        serviceUp = false;
        serviceRequested = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}