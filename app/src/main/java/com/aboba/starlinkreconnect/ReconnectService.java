package com.aboba.starlinkreconnect;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.text.DateFormat;
import java.util.Date;

/**
 * Foreground "watchdog" service. Every few seconds (and on every Wi-Fi state / scan broadcast)
 * it checks whether the phone is connected to the target SSID; if the network is in range and
 * the phone is not on it, it forces a (re)connection. On an observed not-connected -> connected
 * transition it sends the Starlink reboot command over the local gRPC API (after a delay, with
 * retries, a cooldown and a back-off so that a router reboot, which drops the Wi-Fi, cannot
 * turn into an endless reboot loop).
 *
 * It also holds a {@link NetworkRequest} for TRANSPORT_WIFI so that Android keeps the Wi-Fi
 * link up even when it decides the network has "no internet" (typical for Starlink while the
 * dish is booting / searching for satellites).
 */
public class ReconnectService extends Service {
    private static final String TAG = "ReconnectService";

    public static final String ACTION_START = "com.aboba.starlinkreconnect.START";
    public static final String ACTION_STOP = "com.aboba.starlinkreconnect.STOP";
    public static final String ACTION_RECONNECT_NOW = "com.aboba.starlinkreconnect.RECONNECT_NOW";
    public static final String ACTION_REBOOT_STARLINK_NOW = "com.aboba.starlinkreconnect.REBOOT_STARLINK_NOW";
    static final String EXTRA_FROM_BACKGROUND = "from_background";
    static final String EXTRA_AT_BOOT = "at_boot";

    static final String CHANNEL_SERVICE = "watchdog";
    static final String CHANNEL_EVENTS = "events";
    private static final int NOTIF_ID = 1;
    private static final int NOTIF_CONNECTED_ID = 2;
    private static final int NOTIF_REBOOT_ID = 3;

    private static final long TICK_MS = 15_000L;
    private static final long ATTEMPT_MIN_INTERVAL_MS = 20_000L;
    private static final int ATTEMPT_BACKOFF_MAX_SHIFT = 4; // 20 s .. 320 s between attempts
    private static final long SCAN_MIN_INTERVAL_MS = 60_000L;
    private static final long SUGGESTION_REFRESH_MS = 5 * 60_000L;
    /** Automatic reboot attempts are never closer than this (measured from the end of the last one). */
    private static final long MIN_REBOOT_ATTEMPT_GAP_MS = 2 * 60_000L;
    /** After this many failed automatic attempts in a row, pause for the full cooldown. */
    private static final int REBOOT_FAILURE_BACKOFF_AFTER = 3;
    /**
     * When started at phone boot, a connection established this soon after boot is the system
     * auto-joining a network the phone was already in range of, not the network appearing.
     */
    private static final long BOOT_BASELINE_WINDOW_MS = 3 * 60_000L;
    private static final int REBOOT_WAIT_NETWORK_TRIES = 10; // x 3 s

    /** Whether any instance is alive; for the activity's display logic only. */
    public static volatile boolean running = false;

    private Handler handler;
    private WifiReconnector reconnector;
    private ConnectivityManager cm;
    private NotificationManager nm;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private BroadcastReceiver wifiReceiver;
    private volatile Network wifiNetwork;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            tick();
        }
    };
    private final Runnable rebootRunnable = new Runnable() {
        @Override
        public void run() {
            runScheduledReboot();
        }
    };

    private volatile boolean destroyed;
    private boolean seeded;
    private boolean wasConnected;
    private boolean rebootPending;
    private boolean rebootInFlight;
    private boolean legacyBlocked;
    private boolean startedFromBackground;
    private boolean startedAtBoot;
    private boolean bootBaselineDone;
    private boolean rebootManual;
    private int rebootWaitNetworkTries;
    private int consecutiveConnectFailures;
    private long lastAttempt;
    private long lastScan;
    private long lastSuggestionRefresh;
    private int attempts;
    private String lastNotificationText;

    // ------------------------------------------------------------------ static helpers

    public static void start(Context context) {
        send(context, ACTION_START, false);
    }

    /** Start from a receiver (package replaced): no visible activity is involved. */
    public static void startFromBackground(Context context) {
        send(context, ACTION_START, true);
    }

    /** Start from BOOT_COMPLETED. */
    public static void startAtBoot(Context context) {
        Context app = context.getApplicationContext();
        Intent i = new Intent(app, ReconnectService.class).setAction(ACTION_START)
                .putExtra(EXTRA_FROM_BACKGROUND, true)
                .putExtra(EXTRA_AT_BOOT, true);
        try {
            app.startForegroundService(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "startForegroundService failed", e);
        }
    }

    public static void stop(Context context) {
        Context app = context.getApplicationContext();
        app.stopService(new Intent(app, ReconnectService.class));
    }

    public static void reconnectNow(Context context) {
        send(context, ACTION_RECONNECT_NOW, false);
    }

    public static void rebootStarlinkNow(Context context) {
        send(context, ACTION_REBOOT_STARLINK_NOW, false);
    }

    private static void send(Context context, String action, boolean fromBackground) {
        Context app = context.getApplicationContext();
        Intent i = new Intent(app, ReconnectService.class).setAction(action)
                .putExtra(EXTRA_FROM_BACKGROUND, fromBackground);
        try {
            app.startForegroundService(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "startForegroundService failed", e);
        }
    }

    public static void createChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel service = new NotificationChannel(CHANNEL_SERVICE,
                context.getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW);
        service.setShowBadge(false);
        nm.createNotificationChannel(service);
        NotificationChannel events = new NotificationChannel(CHANNEL_EVENTS,
                context.getString(R.string.notif_channel_events), NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(events);
    }

    /** Intent that opens the Starlink app, or its Play Store page when it is not installed. */
    public static Intent starlinkLaunchIntent(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent launch = pm.getLaunchIntentForPackage(MainActivity.STARLINK_PACKAGE);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return launch;
        }
        Intent market = new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + MainActivity.STARLINK_PACKAGE));
        market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return market;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        reconnector = new WifiReconnector(this);
        cm = getSystemService(ConnectivityManager.class);
        nm = getSystemService(NotificationManager.class);

        createChannels(this);
        lastNotificationText = getString(R.string.status_starting);
        startForeground(NOTIF_ID, buildNotification(lastNotificationText));
        running = true;

        registerWifiReceiver();
        requestWifiNetwork();
        scheduleTick(0);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every start goes through startForegroundService(); re-asserting the foreground
        // state here keeps the "did not call startForeground()" watchdog happy whatever
        // the action is (including an immediate STOP).
        startForeground(NOTIF_ID, buildNotification(
                lastNotificationText != null ? lastNotificationText : getString(R.string.status_starting)));
        String action = intent == null ? null : intent.getAction();
        if (intent != null && intent.getBooleanExtra(EXTRA_AT_BOOT, false)) {
            startedAtBoot = true;
        }
        if (intent != null && intent.hasExtra(EXTRA_FROM_BACKGROUND)) {
            boolean background = intent.getBooleanExtra(EXTRA_FROM_BACKGROUND, false);
            if (background) {
                // Without background location, an FGS started from a receiver gets no location
                // access on Android 11+, i.e. no scan results. A later start from the activity
                // (which always re-sends START) repairs that, so remember to hint the user.
                startedFromBackground = !hasBackgroundLocation();
            } else {
                startedFromBackground = false;
            }
        }
        if (ACTION_STOP.equals(action)) {
            Prefs.setEnabled(this, false);
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RECONNECT_NOW.equals(action)) {
            lastAttempt = 0;
            attempts = 0;
            consecutiveConnectFailures = 0;
            String ssid = Prefs.ssid(this);
            reconnector.connect(ssid, true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                lastSuggestionRefresh = SystemClock.elapsedRealtime();
                reconnector.refreshSuggestion(ssid);
            }
            reconnector.startScan();
            scheduleTick(2_000);
            return START_STICKY;
        }
        if (ACTION_REBOOT_STARLINK_NOW.equals(action)) {
            cancelPendingReboot();
            startReboot(true);
            return START_STICKY;
        }
        scheduleTick(0);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (wifiReceiver != null) {
            try {
                unregisterReceiver(wifiReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            wifiReceiver = null;
        }
        if (wifiCallback != null && cm != null) {
            try {
                cm.unregisterNetworkCallback(wifiCallback);
            } catch (RuntimeException ignored) {
            }
            wifiCallback = null;
        }
        wifiNetwork = null;
        StatusBus.post(getString(R.string.status_idle));
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ wiring

    private void registerWifiReceiver() {
        wifiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                scheduleTick(500);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(wifiReceiver, f);
        }
    }

    private void requestWifiNetwork() {
        if (cm == null) return;
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                wifiNetwork = network;
                scheduleTick(500);
            }

            @Override
            public void onLost(Network network) {
                if (network.equals(wifiNetwork)) wifiNetwork = null;
                scheduleTick(500);
            }
        };
        try {
            cm.requestNetwork(req, wifiCallback);
        } catch (RuntimeException e) {
            Log.w(TAG, "requestNetwork failed", e);
            wifiCallback = null;
        }
    }

    private void scheduleTick(long delayMs) {
        if (handler == null || destroyed) return;
        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, delayMs);
    }

    /** The Wi-Fi Network object (from the callback, else looked up), or null while not up. */
    private Network currentWifiNetwork() {
        Network n = wifiNetwork;
        if (n != null && StarlinkCommands.isWifi(cm, n)) return n;
        return StarlinkCommands.findWifiNetwork(this);
    }

    // ------------------------------------------------------------------ core loop

    private void tick() {
        if (destroyed) return;
        String ssid = Prefs.ssid(this);

        if (!hasLocationPermission()) {
            update(getString(R.string.status_no_location));
            scheduleTick(TICK_MS);
            return;
        }

        if (!reconnector.isWifiEnabled()) {
            reconnector.enableWifi();
            update(getString(R.string.status_wifi_off));
            scheduleTick(5_000);
            return;
        }

        String current = reconnector.currentSsid();
        boolean associated = ssid.equals(current);
        boolean linkUp = associated && currentWifiNetwork() != null;

        if (!seeded) {
            // First evaluation after a (re)start: take the current state as the baseline instead
            // of treating "already connected" as a fresh connection (which would reboot the dish
            // on every app update, phone reboot or process restart).
            seeded = true;
            wasConnected = linkUp;
            Log.i(TAG, "seeded: ssid=" + current + " linkUp=" + linkUp + " (no reboot for the initial state)");
            if (linkUp) {
                update(getString(R.string.status_connected, ssid));
                scheduleTick(TICK_MS);
                return;
            }
        }

        if (linkUp) {
            if (!wasConnected) {
                wasConnected = true;
                attempts = 0;
                consecutiveConnectFailures = 0;
                boolean bootAutoJoin = startedAtBoot && !bootBaselineDone
                        && SystemClock.elapsedRealtime() < BOOT_BASELINE_WINDOW_MS;
                bootBaselineDone = true;
                if (bootAutoJoin) {
                    // The phone just booted inside the network's range: same as an app restart
                    // while connected, this is not "the network appeared".
                    Log.i(TAG, "connected to " + ssid + " right after phone boot: baseline, no reboot");
                } else {
                    Log.i(TAG, "connected to " + ssid + " (transition)");
                    onConnected(ssid);
                }
            }
            if (rebootInFlight) {
                update(getString(R.string.status_reboot_sending));
            } else if (!rebootPending) {
                update(getString(R.string.status_connected, ssid));
            }
            scheduleTick(TICK_MS);
            return;
        }

        if (associated) {
            // L2 is up, IP provisioning still pending: not connected yet, but no need to push.
            update(getString(R.string.status_associating, ssid));
            scheduleTick(2_000);
            return;
        }

        if (wasConnected) {
            Log.i(TAG, "disconnected from " + ssid + " (now: " + current + ")");
            wasConnected = false;
            // Lost the network before a delayed reboot fired: do not send it blindly.
            cancelPendingReboot();
        }

        long now = SystemClock.elapsedRealtime();
        ScanResult visible = reconnector.findVisible(ssid);

        if (visible != null) {
            if (!WifiReconnector.isOpen(visible)) {
                update(getString(R.string.status_secured, ssid));
                scheduleTick(TICK_MS);
                return;
            }
            boolean onOtherWifi = current != null;
            if (onOtherWifi && !Prefs.forceSwitch(this)) {
                update(getString(R.string.status_staying, ssid, current));
                scheduleTick(TICK_MS);
                return;
            }
            long interval = ATTEMPT_MIN_INTERVAL_MS
                    << Math.min(consecutiveConnectFailures, ATTEMPT_BACKOFF_MAX_SHIFT);
            if (now - lastAttempt >= interval) {
                lastAttempt = now;
                attempts++;
                // Drop the current association first when sitting on another network (but stop
                // kicking the user off a working network after repeated failures) and on every
                // third attempt so the supplicant really re-associates.
                boolean disconnectFirst = onOtherWifi
                        ? consecutiveConnectFailures < REBOOT_FAILURE_BACKOFF_AFTER
                        : (attempts % 3 == 0);
                int r = reconnector.connect(ssid, disconnectFirst);
                Log.i(TAG, "connect attempt #" + attempts + " -> " + r);
                legacyBlocked = r == WifiReconnector.RESULT_LEGACY_BLOCKED;
                consecutiveConnectFailures++;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        && now - lastSuggestionRefresh >= SUGGESTION_REFRESH_MS) {
                    lastSuggestionRefresh = now;
                    reconnector.refreshSuggestion(ssid);
                }
            }
            update(legacyBlocked
                    ? getString(R.string.status_legacy_blocked, ssid)
                    : getString(R.string.status_connecting, ssid));
            scheduleTick(5_000);
            return;
        }

        if (now - lastScan >= SCAN_MIN_INTERVAL_MS) {
            lastScan = now;
            reconnector.startScan();
        }
        if (!isLocationEnabled()) {
            update(getString(R.string.status_location_off));
        } else if (startedFromBackground && current == null) {
            update(getString(R.string.status_needs_foreground));
        } else {
            String cur = current != null ? current : getString(R.string.status_none);
            update(getString(R.string.status_not_visible, ssid, cur));
        }
        scheduleTick(TICK_MS);
    }

    private void onConnected(String ssid) {
        if (Prefs.notifyOnConnect(this) && nm != null) {
            PendingIntent pi = PendingIntent.getActivity(this, 1, starlinkLaunchIntent(this),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CHANNEL_EVENTS)
                    .setSmallIcon(R.drawable.ic_stat_wifi)
                    .setContentTitle(getString(R.string.notif_connected_title, ssid))
                    .setContentText(getString(R.string.notif_connected_text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIF_CONNECTED_ID, n);
        }
        scheduleRebootAfterConnect(ssid);
    }

    // ------------------------------------------------------------------ Starlink reboot

    private void scheduleRebootAfterConnect(String ssid) {
        if (!Prefs.rebootEnabled(this)) {
            StatusBus.postReboot(getString(R.string.status_reboot_disabled));
            return;
        }
        if (rebootPending || rebootInFlight) return;

        long cooldownMs = Prefs.rebootCooldownMin(this) * 60_000L;
        long sinceLast = Prefs.sinceLastReboot(this);
        if (sinceLast < cooldownMs) {
            long minutesLeft = (cooldownMs - sinceLast + 59_999L) / 60_000L;
            StatusBus.postReboot(getString(R.string.status_reboot_cooldown, minutesLeft));
            Log.i(TAG, "reboot skipped: cooldown, " + minutesLeft + " min left");
            return;
        }

        long delayMs = Prefs.rebootDelaySec(this) * 1000L;
        long sinceAttempt = Prefs.sinceLastRebootAttempt(this);
        if (sinceAttempt < MIN_REBOOT_ATTEMPT_GAP_MS) {
            // Defer instead of skipping, so a flap right after an attempt does not lose the reboot.
            delayMs = Math.max(delayMs, MIN_REBOOT_ATTEMPT_GAP_MS - sinceAttempt);
            update(getString(R.string.status_reboot_deferred, ssid, delayMs / 1000L));
        } else {
            update(getString(R.string.status_reboot_scheduled, ssid, delayMs / 1000L));
        }
        rebootPending = true;
        rebootWaitNetworkTries = 0;
        Log.i(TAG, "reboot scheduled in " + delayMs + " ms");
        handler.removeCallbacks(rebootRunnable);
        handler.postDelayed(rebootRunnable, delayMs);
    }

    private void cancelPendingReboot() {
        handler.removeCallbacks(rebootRunnable);
        rebootPending = false;
        rebootWaitNetworkTries = 0;
    }

    private void runScheduledReboot() {
        if (destroyed) return;
        rebootPending = false;
        String ssid = Prefs.ssid(this);
        if (!ssid.equals(reconnector.currentSsid())) {
            Log.i(TAG, "reboot cancelled: no longer on " + ssid);
            scheduleTick(0);
            return;
        }
        if (currentWifiNetwork() == null) {
            // Associated but the Wi-Fi Network (IP) is not up yet: wait a little, bounded.
            if (++rebootWaitNetworkTries <= REBOOT_WAIT_NETWORK_TRIES) {
                rebootPending = true;
                update(getString(R.string.status_reboot_waiting_network, ssid));
                handler.postDelayed(rebootRunnable, 3_000L);
                return;
            }
            rebootWaitNetworkTries = 0;
            onRebootFinished(false, false, getString(R.string.check_no_wifi_network));
            return;
        }
        rebootWaitNetworkTries = 0;
        startReboot(false);
    }

    private void startReboot(boolean manual) {
        if (rebootInFlight) return;
        rebootInFlight = true;
        rebootManual = manual;
        update(getString(R.string.status_reboot_sending));
        StatusBus.postReboot(getString(R.string.status_reboot_sending));
        StarlinkCommands.reboot(this, currentWifiNetwork(), new StarlinkCommands.Callback() {
            @Override
            public void onResult(boolean anyOk, boolean allOk, String text) {
                onRebootFinished(anyOk, allOk, text);
            }
        });
        if (manual) consecutiveConnectFailures = 0;
    }

    private void onRebootFinished(boolean anyOk, boolean allOk, String text) {
        Log.i(TAG, "reboot finished: anyOk=" + anyOk + " allOk=" + allOk + " " + text.replace("\n", " | "));
        // Bookkeeping must happen even if this instance was destroyed meanwhile (the result is
        // real); UI and scheduling must not.
        Prefs.markRebootAttempt(this);
        int failures = anyOk ? 0 : Prefs.consecutiveRebootFailures(this) + 1;
        Prefs.setConsecutiveRebootFailures(this, failures);
        String stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()) + "\n" + text;
        boolean backedOff = false;
        if (anyOk) {
            Prefs.markRebootSent(this);
        } else if (failures >= REBOOT_FAILURE_BACKOFF_AFTER) {
            // Keep failing attempts from turning into a loop: pause for the full cooldown.
            Prefs.markRebootSent(this);
            Prefs.setConsecutiveRebootFailures(this, 0);
            stamp += "\n" + getString(R.string.status_reboot_backoff, Prefs.rebootCooldownMin(this));
            backedOff = true;
        }
        Prefs.setLastRebootResult(this, stamp);
        StatusBus.postReboot(stamp);
        if (destroyed) return;
        rebootInFlight = false;
        notifyRebootResult(anyOk, text);
        if (!anyOk && !backedOff && !rebootManual && wasConnected && !rebootPending) {
            // Still on the network but the dish did not take the command (e.g. still booting):
            // try again later instead of silently giving up until the next reconnect.
            rebootPending = true;
            rebootWaitNetworkTries = 0;
            Log.i(TAG, "reboot failed (" + failures + " in a row), retrying in "
                    + MIN_REBOOT_ATTEMPT_GAP_MS + " ms");
            update(getString(R.string.status_reboot_retry, MIN_REBOOT_ATTEMPT_GAP_MS / 1000L));
            handler.removeCallbacks(rebootRunnable);
            handler.postDelayed(rebootRunnable, MIN_REBOOT_ATTEMPT_GAP_MS);
        }
        scheduleTick(0);
    }

    private void notifyRebootResult(boolean ok, String text) {
        if (nm == null) return;
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 4, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_stat_wifi)
                .setContentTitle(getString(ok ? R.string.notif_reboot_ok_title : R.string.notif_reboot_fail_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIF_REBOOT_ID, n);
    }

    // ------------------------------------------------------------------ notifications

    private void update(String text) {
        if (destroyed) return;
        if (text != null && !text.equals(lastNotificationText)) {
            lastNotificationText = text;
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        }
        StatusBus.post(text);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent reconnectPi = PendingIntent.getForegroundService(this, 2,
                new Intent(this, ReconnectService.class).setAction(ACTION_RECONNECT_NOW),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent rebootPi = PendingIntent.getForegroundService(this, 5,
                new Intent(this, ReconnectService.class).setAction(ACTION_REBOOT_STARLINK_NOW),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopPi = PendingIntent.getForegroundService(this, 3,
                new Intent(this, ReconnectService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Icon icon = Icon.createWithResource(this, R.drawable.ic_stat_wifi);
        return new Notification.Builder(this, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_wifi)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(icon,
                        getString(R.string.notif_action_reconnect), reconnectPi).build())
                .addAction(new Notification.Action.Builder(icon,
                        getString(R.string.notif_action_reboot), rebootPi).build())
                .addAction(new Notification.Action.Builder(icon,
                        getString(R.string.notif_action_stop), stopPi).build())
                .build();
    }

    // ------------------------------------------------------------------ checks

    private boolean hasLocationPermission() {
        // A targetSdk 28 app may scan with either precise or approximate location.
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return hasLocationPermission();
        return checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private boolean isLocationEnabled() {
        LocationManager lm = getSystemService(LocationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return lm != null && lm.isLocationEnabled();
        }
        int mode = Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF);
        return mode != Settings.Secure.LOCATION_MODE_OFF;
    }
}
