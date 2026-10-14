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
 * the phone is not on it, it forces a (re)connection. Once connected it sends the Starlink
 * reboot command over the local gRPC API (with a delay, retries and a cooldown so that a router
 * reboot, which drops the Wi-Fi, does not trigger an endless reboot loop).
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

    static final String CHANNEL_SERVICE = "watchdog";
    static final String CHANNEL_EVENTS = "events";
    private static final int NOTIF_ID = 1;
    private static final int NOTIF_CONNECTED_ID = 2;
    private static final int NOTIF_REBOOT_ID = 3;

    private static final long TICK_MS = 15_000L;
    private static final long ATTEMPT_MIN_INTERVAL_MS = 20_000L;
    private static final long SCAN_MIN_INTERVAL_MS = 60_000L;
    private static final long SUGGESTION_REFRESH_MS = 5 * 60_000L;
    /** Never fire two automatic reboot attempts closer than this, even when the first failed. */
    private static final long MIN_REBOOT_ATTEMPT_GAP_MS = 60_000L;

    public static volatile boolean running = false;

    private Handler handler;
    private WifiReconnector reconnector;
    private ConnectivityManager cm;
    private NotificationManager nm;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private BroadcastReceiver wifiReceiver;
    private volatile Network wifiNetwork;

    private final Runnable tickRunnable = this::tick;
    private final Runnable rebootRunnable = this::runScheduledReboot;

    private long lastAttempt;
    private long lastScan;
    private long lastSuggestionRefresh;
    private int attempts;
    private boolean wasConnected;
    private boolean rebootPending;
    private boolean rebootInFlight;
    private boolean legacyBlocked;
    private String lastNotificationText;

    // ------------------------------------------------------------------ static helpers

    public static void start(Context context) {
        send(context, ACTION_START);
    }

    public static void stop(Context context) {
        Context app = context.getApplicationContext();
        app.stopService(new Intent(app, ReconnectService.class));
    }

    public static void reconnectNow(Context context) {
        send(context, ACTION_RECONNECT_NOW);
    }

    public static void rebootStarlinkNow(Context context) {
        send(context, ACTION_REBOOT_STARLINK_NOW);
    }

    private static void send(Context context, String action) {
        Context app = context.getApplicationContext();
        Intent i = new Intent(app, ReconnectService.class).setAction(action);
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
        if (ACTION_STOP.equals(action)) {
            Prefs.setEnabled(this, false);
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RECONNECT_NOW.equals(action)) {
            lastAttempt = 0;
            attempts = 0;
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
            handler.removeCallbacks(rebootRunnable);
            rebootPending = false;
            startReboot();
            return START_STICKY;
        }
        scheduleTick(0);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
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
                scheduleTick(1_000);
            }

            @Override
            public void onLost(Network network) {
                if (network.equals(wifiNetwork)) wifiNetwork = null;
                scheduleTick(1_000);
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
        if (handler == null) return;
        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, delayMs);
    }

    // ------------------------------------------------------------------ core loop

    private void tick() {
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
        if (ssid.equals(current)) {
            if (!wasConnected) {
                wasConnected = true;
                attempts = 0;
                onConnected(ssid);
            }
            if (rebootInFlight) {
                update(getString(R.string.status_reboot_sending));
            } else if (!rebootPending) {
                update(getString(R.string.status_connected, ssid));
            }
            scheduleTick(TICK_MS);
            return;
        }
        if (wasConnected) {
            wasConnected = false;
            if (rebootPending) {
                // Lost the network before the delayed reboot fired: do not send it blindly.
                handler.removeCallbacks(rebootRunnable);
                rebootPending = false;
            }
        }

        long now = SystemClock.elapsedRealtime();
        ScanResult visible = reconnector.findVisible(ssid);

        if (visible != null) {
            boolean onOtherWifi = current != null;
            if (onOtherWifi && !Prefs.forceSwitch(this)) {
                update(getString(R.string.status_not_visible, ssid, current));
            } else {
                if (now - lastAttempt >= ATTEMPT_MIN_INTERVAL_MS) {
                    lastAttempt = now;
                    attempts++;
                    // Every third attempt (or when sitting on a different network) drop the
                    // current association first so the supplicant really re-associates.
                    boolean disconnectFirst = onOtherWifi || (attempts % 3 == 0);
                    int r = reconnector.connect(ssid, disconnectFirst);
                    Log.i(TAG, "connect attempt #" + attempts + " -> " + r);
                    legacyBlocked = r == WifiReconnector.RESULT_LEGACY_BLOCKED;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            && now - lastSuggestionRefresh >= SUGGESTION_REFRESH_MS) {
                        lastSuggestionRefresh = now;
                        reconnector.refreshSuggestion(ssid);
                    }
                }
                update(legacyBlocked
                        ? getString(R.string.status_legacy_blocked, ssid)
                        : getString(R.string.status_connecting, ssid));
            }
            scheduleTick(5_000);
            return;
        }

        if (now - lastScan >= SCAN_MIN_INTERVAL_MS) {
            lastScan = now;
            reconnector.startScan();
        }
        if (!isLocationEnabled()) {
            update(getString(R.string.status_location_off));
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

        long now = System.currentTimeMillis();
        long cooldownMs = Prefs.rebootCooldownMin(this) * 60_000L;
        long sinceLast = now - Prefs.lastRebootAt(this);
        if (sinceLast >= 0 && sinceLast < cooldownMs) {
            long minutesLeft = (cooldownMs - sinceLast + 59_999L) / 60_000L;
            StatusBus.postReboot(getString(R.string.status_reboot_cooldown, minutesLeft));
            Log.i(TAG, "reboot skipped: cooldown, " + minutesLeft + " min left");
            return;
        }
        long sinceAttempt = now - Prefs.lastRebootAttemptAt(this);
        if (sinceAttempt >= 0 && sinceAttempt < MIN_REBOOT_ATTEMPT_GAP_MS) {
            StatusBus.postReboot(getString(R.string.status_reboot_cooldown, 1L));
            Log.i(TAG, "reboot skipped: last attempt " + sinceAttempt + " ms ago");
            return;
        }

        int delaySec = Prefs.rebootDelaySec(this);
        rebootPending = true;
        update(getString(R.string.status_reboot_scheduled, ssid, delaySec));
        handler.removeCallbacks(rebootRunnable);
        handler.postDelayed(rebootRunnable, delaySec * 1000L);
    }

    private void runScheduledReboot() {
        rebootPending = false;
        String ssid = Prefs.ssid(this);
        if (!ssid.equals(reconnector.currentSsid())) {
            Log.i(TAG, "reboot cancelled: no longer on " + ssid);
            scheduleTick(0);
            return;
        }
        startReboot();
    }

    private void startReboot() {
        if (rebootInFlight) return;
        rebootInFlight = true;
        Prefs.setLastRebootAttemptAt(this, System.currentTimeMillis());
        update(getString(R.string.status_reboot_sending));
        StatusBus.postReboot(getString(R.string.status_reboot_sending));
        StarlinkCommands.reboot(this, wifiNetwork, (ok, text) -> {
            rebootInFlight = false;
            if (ok) Prefs.setLastRebootAt(this, System.currentTimeMillis());
            String stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()) + "\n" + text;
            Prefs.setLastRebootResult(this, stamp);
            StatusBus.postReboot(stamp);
            notifyRebootResult(ok, text);
            if (running) scheduleTick(0);
        });
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
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
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
