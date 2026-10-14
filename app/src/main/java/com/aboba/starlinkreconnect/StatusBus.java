package com.aboba.starlinkreconnect;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** In-process status channel from the service to the UI (both live in the same process). */
public final class StatusBus {
    public interface Listener {
        void onStatus(String text);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile String last = "";

    private StatusBus() {}

    public static void register(Listener l) {
        LISTENERS.addIfAbsent(l);
        final String snapshot = last;
        MAIN.post(() -> l.onStatus(snapshot));
    }

    public static void unregister(Listener l) {
        LISTENERS.remove(l);
    }

    public static String last() {
        return last;
    }

    public static void post(final String text) {
        last = text;
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onStatus(text);
        });
    }
}
