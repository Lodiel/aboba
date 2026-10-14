package com.aboba.starlinkreconnect;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** In-process status channel from the service to the UI (both live in the same process). */
public final class StatusBus {
    public interface Listener {
        void onStatus(String text);

        void onRebootStatus(String text);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile String last = "";
    private static volatile String lastReboot = "";

    private StatusBus() {}

    public static void register(final Listener l) {
        LISTENERS.addIfAbsent(l);
        final String snapshot = last;
        final String rebootSnapshot = lastReboot;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                l.onStatus(snapshot);
                l.onRebootStatus(rebootSnapshot);
            }
        });
    }

    public static void unregister(Listener l) {
        LISTENERS.remove(l);
    }

    public static void post(final String text) {
        last = text;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : LISTENERS) l.onStatus(text);
            }
        });
    }

    public static void postReboot(final String text) {
        lastReboot = text;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : LISTENERS) l.onRebootStatus(text);
            }
        });
    }
}
