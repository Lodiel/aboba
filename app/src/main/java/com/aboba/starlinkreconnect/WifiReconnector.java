package com.aboba.starlinkreconnect;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * All Wi-Fi plumbing in one place.
 *
 * Two mechanisms are used together:
 *  1. Legacy {@link WifiManager} APIs (addNetwork / enableNetwork / reconnect). They are
 *     deprecated but still functional on Android 10+ for apps targeting API 28, which is why
 *     the app deliberately targets 28. This is the only public way to actively force a
 *     connection to a specific SSID from the background.
 *  2. {@link WifiNetworkSuggestion} (Android 10+). The OS itself auto-joins suggested
 *     networks when they are in range, even when the app process is dead.
 */
public final class WifiReconnector {
    private static final String TAG = "WifiReconnector";

    public static final int RESULT_OK = 0;
    public static final int RESULT_WIFI_OFF = 1;
    public static final int RESULT_LEGACY_FAILED = 2;

    private final Context ctx;
    private final WifiManager wm;

    public WifiReconnector(Context context) {
        this.ctx = context.getApplicationContext();
        this.wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
    }

    public boolean isWifiEnabled() {
        return wm != null && wm.isWifiEnabled();
    }

    @SuppressWarnings("deprecation")
    public boolean enableWifi() {
        if (wm == null) return false;
        if (wm.isWifiEnabled()) return true;
        try {
            return wm.setWifiEnabled(true);
        } catch (SecurityException e) {
            Log.w(TAG, "setWifiEnabled denied", e);
            return false;
        }
    }

    /** SSID of the network Wi-Fi is currently associated with, or null if none / unknown. */
    @SuppressWarnings("deprecation")
    public String currentSsid() {
        if (wm == null) return null;
        try {
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            if (info.getNetworkId() == -1 && info.getSupplicantState() != SupplicantState.COMPLETED) {
                return null;
            }
            String ssid = unquote(info.getSSID());
            if (ssid == null || ssid.isEmpty() || "<unknown ssid>".equals(ssid) || "0x".equals(ssid)) {
                return null;
            }
            return ssid;
        } catch (SecurityException e) {
            Log.w(TAG, "getConnectionInfo denied", e);
            return null;
        }
    }

    /** The strongest scan result matching the SSID, or null if it is not in range (or scan denied). */
    @SuppressWarnings("deprecation")
    public ScanResult findVisible(String ssid) {
        if (wm == null) return null;
        List<ScanResult> results;
        try {
            results = wm.getScanResults();
        } catch (SecurityException e) {
            Log.w(TAG, "getScanResults denied", e);
            return null;
        }
        if (results == null) return null;
        ScanResult best = null;
        for (ScanResult r : results) {
            if (r == null || r.SSID == null) continue;
            if (!ssid.equals(r.SSID)) continue;
            if (best == null || r.level > best.level) best = r;
        }
        return best;
    }

    public static boolean isOpen(ScanResult r) {
        if (r == null || r.capabilities == null) return true;
        String c = r.capabilities.toUpperCase();
        return !(c.contains("WPA") || c.contains("WEP") || c.contains("SAE")
                || c.contains("EAP") || c.contains("PSK") || c.contains("OWE"));
    }

    @SuppressWarnings("deprecation")
    public boolean startScan() {
        if (wm == null) return false;
        try {
            return wm.startScan();
        } catch (SecurityException e) {
            Log.w(TAG, "startScan denied", e);
            return false;
        }
    }

    /**
     * Actively (re)connect to the open network with the given SSID. Returns one of RESULT_*.
     */
    public int connect(String ssid, boolean disconnectFirst) {
        if (!enableWifi()) return RESULT_WIFI_OFF;

        boolean legacyOk = legacyConnect(ssid, disconnectFirst);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ensureSuggestion(ssid);
        }
        return legacyOk ? RESULT_OK : RESULT_LEGACY_FAILED;
    }

    // ---------------------------------------------------------------- legacy API (target 28)

    @SuppressWarnings("deprecation")
    private boolean legacyConnect(String ssid, boolean disconnectFirst) {
        if (wm == null) return false;
        try {
            int id = findConfiguredId(ssid);
            if (id < 0) {
                WifiConfiguration cfg = new WifiConfiguration();
                cfg.SSID = quote(ssid);
                cfg.allowedKeyManagement.clear();
                cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                cfg.priority = 999999;
                id = wm.addNetwork(cfg);
                Log.i(TAG, "addNetwork(" + ssid + ") -> " + id);
            }
            if (id < 0) return false;

            if (disconnectFirst) {
                wm.disconnect();
            }
            boolean enabled = wm.enableNetwork(id, true);
            boolean reconnect = wm.reconnect();
            Log.i(TAG, "enableNetwork=" + enabled + " reconnect=" + reconnect);
            return enabled;
        } catch (SecurityException e) {
            Log.w(TAG, "legacy connect denied", e);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private int findConfiguredId(String ssid) {
        List<WifiConfiguration> list;
        try {
            list = wm.getConfiguredNetworks();
        } catch (SecurityException e) {
            return -1;
        }
        if (list == null) return -1;
        String quoted = quote(ssid);
        for (WifiConfiguration c : list) {
            if (c != null && quoted.equals(c.SSID)) return c.networkId;
        }
        return -1;
    }

    // ---------------------------------------------------------------- suggestions (API 29+)

    /** Registers (or re-registers) the network as a suggestion so the OS auto-joins it on its own. */
    public int ensureSuggestion(String ssid) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || wm == null) return -1;
        try {
            List<WifiNetworkSuggestion> list = Collections.singletonList(buildSuggestion(ssid));
            int status = wm.addNetworkSuggestions(list);
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE) {
                status = WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
            }
            Log.i(TAG, "addNetworkSuggestions(" + ssid + ") -> " + status);
            return status;
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "addNetworkSuggestions failed", e);
            return -1;
        }
    }

    /** Drops every suggestion of this app and re-adds the current one (nudges the OS to re-evaluate). */
    public int refreshSuggestion(String ssid) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || wm == null) return -1;
        try {
            wm.removeNetworkSuggestions(new ArrayList<>());
        } catch (SecurityException e) {
            Log.w(TAG, "removeNetworkSuggestions failed", e);
        }
        return ensureSuggestion(ssid);
    }

    public WifiNetworkSuggestion buildSuggestion(String ssid) {
        WifiNetworkSuggestion.Builder b = new WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setIsAppInteractionRequired(false)
                .setPriority(Integer.MAX_VALUE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setCredentialSharedWithUser(true);
            b.setIsInitialAutojoinEnabled(true);
        }
        return b.build();
    }

    // ---------------------------------------------------------------- helpers

    static String quote(String s) {
        return "\"" + s + "\"";
    }

    static String unquote(String s) {
        if (s == null) return null;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
