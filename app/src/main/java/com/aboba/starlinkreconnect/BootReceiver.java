package com.aboba.starlinkreconnect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the watchdog after a reboot or an app update when it was enabled before. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (Prefs.enabled(context)) {
            ReconnectService.startFromBackground(context);
        }
    }
}
