package com.aboba.starlinkreconnect;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.aboba.starlinkreconnect.grpc.StarlinkClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.SocketFactory;

/**
 * Runs Starlink gRPC commands on a background thread, pinned to the Wi-Fi {@link Network} so
 * that Android does not route them over mobile data when the Starlink link has "no internet".
 */
public final class StarlinkCommands {
    private static final String TAG = "StarlinkCommands";
    private static final int CALL_TIMEOUT_MS = 8_000;
    private static final int REBOOT_ATTEMPTS = 3;
    private static final long RETRY_GAP_MS = 4_000L;

    public interface Callback {
        void onResult(boolean ok, String text);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "starlink-grpc");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private StarlinkCommands() {}

    /** The current Wi-Fi network, or null when there is none. */
    @SuppressWarnings("deprecation")
    public static Network findWifiNetwork(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "getAllNetworks failed", e);
        }
        return null;
    }

    /**
     * Sends the reboot command to the configured target(s), retrying a few times per target.
     * The callback runs on the main thread with a human-readable summary.
     */
    public static void reboot(Context context, Network preferred, Callback cb) {
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            PowerManager.WakeLock wl = acquireWakeLock(app);
            try {
                Network net = preferred != null ? preferred : findWifiNetwork(app);
                SocketFactory sf = net != null ? net.getSocketFactory() : null;
                List<String[]> targets = targets(app);
                if (targets.isEmpty()) {
                    MAIN.post(() -> cb.onResult(false, "no reboot target configured"));
                    return;
                }
                StringBuilder summary = new StringBuilder();
                boolean allOk = true;
                for (String[] t : targets) {
                    StarlinkClient client = new StarlinkClient(sf, Prefs.hostOf(t[1]), Prefs.portOf(t[1]), CALL_TIMEOUT_MS);
                    StarlinkClient.Result last = null;
                    for (int attempt = 1; attempt <= REBOOT_ATTEMPTS; attempt++) {
                        last = client.reboot();
                        Log.i(TAG, t[0] + " reboot attempt " + attempt + ": " + last);
                        if (last.ok) break;
                        if (attempt < REBOOT_ATTEMPTS) sleep(RETRY_GAP_MS);
                    }
                    allOk &= last != null && last.ok;
                    if (summary.length() > 0) summary.append('\n');
                    summary.append(t[0]).append(": ").append(last == null ? "?" : last.toString());
                }
                final boolean ok = allOk;
                final String text = summary.toString();
                MAIN.post(() -> cb.onResult(ok, text));
            } catch (Throwable t) {
                Log.e(TAG, "reboot task failed", t);
                final String text = "internal error: " + t;
                MAIN.post(() -> cb.onResult(false, text));
            } finally {
                release(wl);
            }
        });
    }

    /** Queries device info from the dish and the router (whichever answers) for a connectivity check. */
    public static void check(Context context, Network preferred, Callback cb) {
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            PowerManager.WakeLock wl = acquireWakeLock(app);
            try {
                Network net = preferred != null ? preferred : findWifiNetwork(app);
                SocketFactory sf = net != null ? net.getSocketFactory() : null;
                String dishAddr = Prefs.dishAddr(app);
                String routerAddr = Prefs.routerAddr(app);
                StarlinkClient.Result dish = new StarlinkClient(sf, Prefs.hostOf(dishAddr), Prefs.portOf(dishAddr), CALL_TIMEOUT_MS).getDeviceInfo();
                StarlinkClient.Result router = new StarlinkClient(sf, Prefs.hostOf(routerAddr), Prefs.portOf(routerAddr), CALL_TIMEOUT_MS).getDeviceInfo();
                final boolean ok = dish.ok || router.ok;
                final String text = app.getString(R.string.check_result, dish.toString(), router.toString())
                        + (net == null ? "\n" + app.getString(R.string.check_no_wifi_network) : "");
                MAIN.post(() -> cb.onResult(ok, text));
            } catch (Throwable t) {
                Log.e(TAG, "check task failed", t);
                final String text = "internal error: " + t;
                MAIN.post(() -> cb.onResult(false, text));
            } finally {
                release(wl);
            }
        });
    }

    private static List<String[]> targets(Context app) {
        List<String[]> list = new ArrayList<>();
        String target = Prefs.rebootTarget(app);
        if (Prefs.TARGET_DISH.equals(target) || Prefs.TARGET_BOTH.equals(target)) {
            list.add(new String[] {app.getString(R.string.target_name_dish), Prefs.dishAddr(app)});
        }
        if (Prefs.TARGET_ROUTER.equals(target) || Prefs.TARGET_BOTH.equals(target)) {
            list.add(new String[] {app.getString(R.string.target_name_router), Prefs.routerAddr(app)});
        }
        return list;
    }

    private static PowerManager.WakeLock acquireWakeLock(Context app) {
        try {
            PowerManager pm = app.getSystemService(PowerManager.class);
            if (pm == null) return null;
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StarlinkReconnect:grpc");
            wl.acquire(60_000L);
            return wl;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void release(PowerManager.WakeLock wl) {
        if (wl != null && wl.isHeld()) {
            try {
                wl.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
