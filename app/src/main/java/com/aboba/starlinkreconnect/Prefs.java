package com.aboba.starlinkreconnect;

import android.content.Context;
import android.content.SharedPreferences;

import com.aboba.starlinkreconnect.grpc.StarlinkClient;

/** Tiny wrapper around SharedPreferences for the handful of settings the app has. */
public final class Prefs {
    private static final String FILE = "starlink_reconnect";
    private static final String KEY_SSID = "ssid";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_FORCE_SWITCH = "force_switch";
    private static final String KEY_NOTIFY = "notify_on_connect";

    private static final String KEY_REBOOT_ENABLED = "reboot_enabled";
    private static final String KEY_REBOOT_TARGET = "reboot_target";
    private static final String KEY_REBOOT_COOLDOWN_MIN = "reboot_cooldown_min";
    private static final String KEY_REBOOT_DELAY_SEC = "reboot_delay_sec";
    private static final String KEY_DISH_ADDR = "dish_addr";
    private static final String KEY_ROUTER_ADDR = "router_addr";
    private static final String KEY_LAST_REBOOT_AT = "last_reboot_at";
    private static final String KEY_LAST_REBOOT_ATTEMPT_AT = "last_reboot_attempt_at";
    private static final String KEY_LAST_REBOOT_RESULT = "last_reboot_result";

    public static final String DEFAULT_SSID = "Starlink";

    public static final String TARGET_DISH = "dish";
    public static final String TARGET_ROUTER = "router";
    public static final String TARGET_BOTH = "both";

    public static final int DEFAULT_COOLDOWN_MIN = 10;
    public static final int DEFAULT_DELAY_SEC = 5;
    public static final String DEFAULT_DISH_ADDR = StarlinkClient.DISH_HOST + ":" + StarlinkClient.DISH_PORT;
    public static final String DEFAULT_ROUTER_ADDR = StarlinkClient.ROUTER_HOST + ":" + StarlinkClient.ROUTER_PORT;

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ Wi-Fi

    public static String ssid(Context c) {
        String s = sp(c).getString(KEY_SSID, DEFAULT_SSID);
        if (s == null || s.trim().isEmpty()) return DEFAULT_SSID;
        return s.trim();
    }

    public static void setSsid(Context c, String ssid) {
        sp(c).edit().putString(KEY_SSID, ssid == null ? DEFAULT_SSID : ssid.trim()).apply();
    }

    public static boolean enabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean enabled) {
        sp(c).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static boolean forceSwitch(Context c) {
        return sp(c).getBoolean(KEY_FORCE_SWITCH, true);
    }

    public static void setForceSwitch(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_FORCE_SWITCH, v).apply();
    }

    public static boolean notifyOnConnect(Context c) {
        return sp(c).getBoolean(KEY_NOTIFY, true);
    }

    public static void setNotifyOnConnect(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_NOTIFY, v).apply();
    }

    // ------------------------------------------------------------------ reboot

    public static boolean rebootEnabled(Context c) {
        return sp(c).getBoolean(KEY_REBOOT_ENABLED, true);
    }

    public static void setRebootEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_REBOOT_ENABLED, v).apply();
    }

    /** One of TARGET_DISH, TARGET_ROUTER, TARGET_BOTH. */
    public static String rebootTarget(Context c) {
        String t = sp(c).getString(KEY_REBOOT_TARGET, TARGET_DISH);
        if (TARGET_ROUTER.equals(t) || TARGET_BOTH.equals(t)) return t;
        return TARGET_DISH;
    }

    public static void setRebootTarget(Context c, String target) {
        sp(c).edit().putString(KEY_REBOOT_TARGET, target).apply();
    }

    public static int rebootCooldownMin(Context c) {
        return Math.max(0, sp(c).getInt(KEY_REBOOT_COOLDOWN_MIN, DEFAULT_COOLDOWN_MIN));
    }

    public static void setRebootCooldownMin(Context c, int minutes) {
        sp(c).edit().putInt(KEY_REBOOT_COOLDOWN_MIN, Math.max(0, minutes)).apply();
    }

    public static int rebootDelaySec(Context c) {
        return Math.max(0, sp(c).getInt(KEY_REBOOT_DELAY_SEC, DEFAULT_DELAY_SEC));
    }

    public static void setRebootDelaySec(Context c, int seconds) {
        sp(c).edit().putInt(KEY_REBOOT_DELAY_SEC, Math.max(0, seconds)).apply();
    }

    public static String dishAddr(Context c) {
        return normalizeAddr(sp(c).getString(KEY_DISH_ADDR, DEFAULT_DISH_ADDR), DEFAULT_DISH_ADDR);
    }

    public static void setDishAddr(Context c, String addr) {
        sp(c).edit().putString(KEY_DISH_ADDR, normalizeAddr(addr, DEFAULT_DISH_ADDR)).apply();
    }

    public static String routerAddr(Context c) {
        return normalizeAddr(sp(c).getString(KEY_ROUTER_ADDR, DEFAULT_ROUTER_ADDR), DEFAULT_ROUTER_ADDR);
    }

    public static void setRouterAddr(Context c, String addr) {
        sp(c).edit().putString(KEY_ROUTER_ADDR, normalizeAddr(addr, DEFAULT_ROUTER_ADDR)).apply();
    }

    public static long lastRebootAt(Context c) {
        return sp(c).getLong(KEY_LAST_REBOOT_AT, 0L);
    }

    public static void setLastRebootAt(Context c, long millis) {
        sp(c).edit().putLong(KEY_LAST_REBOOT_AT, millis).apply();
    }

    public static long lastRebootAttemptAt(Context c) {
        return sp(c).getLong(KEY_LAST_REBOOT_ATTEMPT_AT, 0L);
    }

    public static void setLastRebootAttemptAt(Context c, long millis) {
        sp(c).edit().putLong(KEY_LAST_REBOOT_ATTEMPT_AT, millis).apply();
    }

    public static String lastRebootResult(Context c) {
        return sp(c).getString(KEY_LAST_REBOOT_RESULT, "");
    }

    public static void setLastRebootResult(Context c, String text) {
        sp(c).edit().putString(KEY_LAST_REBOOT_RESULT, text == null ? "" : text).apply();
    }

    // ------------------------------------------------------------------ helpers

    /** Accepts "host" or "host:port"; falls back to the default when empty or malformed. */
    static String normalizeAddr(String addr, String fallback) {
        if (addr == null) return fallback;
        String a = addr.trim();
        if (a.isEmpty()) return fallback;
        int colon = a.lastIndexOf(':');
        if (colon < 0) {
            int defaultPort = Integer.parseInt(fallback.substring(fallback.lastIndexOf(':') + 1));
            return a + ":" + defaultPort;
        }
        String host = a.substring(0, colon).trim();
        String port = a.substring(colon + 1).trim();
        if (host.isEmpty()) return fallback;
        try {
            int p = Integer.parseInt(port);
            if (p < 1 || p > 65535) return fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
        return host + ":" + port;
    }

    public static String hostOf(String addr) {
        return addr.substring(0, addr.lastIndexOf(':'));
    }

    public static int portOf(String addr) {
        return Integer.parseInt(addr.substring(addr.lastIndexOf(':') + 1));
    }
}
