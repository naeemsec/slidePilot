package com.slidepilot.app;

import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayDeque;

/** Bounded, in-memory, local-only log. No host names, addresses or presentation content. */
public final class Diagnostics {
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private Diagnostics() {}
    public static synchronized void add(String event) {
        String line = SystemClock.elapsedRealtime() + "ms  " + event;
        if (LINES.size() >= 120) LINES.removeFirst();
        LINES.addLast(line);
        Log.i("SlidePilot", event);
    }
    public static synchronized void clear() { LINES.clear(); }
    public static synchronized String snapshot() {
        StringBuilder out = new StringBuilder("SlidePilot 1.0.1\n");
        out.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" / Android ").append(Build.VERSION.RELEASE)
            .append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        for (String line : LINES) out.append(line).append('\n');
        return out.toString();
    }
}
