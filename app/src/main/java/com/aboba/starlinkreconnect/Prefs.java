package com.aboba.starlinkreconnect;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny wrapper around SharedPreferences for the handful of settings the app has. */
public final class Prefs {
    private static final String FILE = "starlink_reconnect";
    private static final String KEY_SSID = "ssid";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_FORCE_SWITCH = "force_switch";
    private static final String KEY_NOTIFY = "notify_on_connect";

    public static final String DEFAULT_SSID = "Starlink";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

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
}
