package com.aboba.starlinkreconnect;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;

public class MainActivity extends Activity implements StatusBus.Listener {
    public static final String STARLINK_PACKAGE = "com.starlink.mobile";

    private static final int REQ_LOCATION = 1;
    private static final int REQ_BACKGROUND_LOCATION = 2;

    private EditText editSsid;
    private Switch switchEnabled;
    private CheckBox checkForceSwitch;
    private CheckBox checkNotify;
    private TextView textStatus;

    private CheckBox checkReboot;
    private RadioGroup radioTarget;
    private EditText editDelay;
    private EditText editCooldown;
    private EditText editDishAddr;
    private EditText editRouterAddr;
    private TextView textRebootStatus;

    private WifiReconnector reconnector;
    private boolean suppressSwitchListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        reconnector = new WifiReconnector(this);
        // Creating the channels from a visible activity makes Android 13 show the
        // notification-permission prompt right away.
        ReconnectService.createChannels(this);

        editSsid = findViewById(R.id.edit_ssid);
        switchEnabled = findViewById(R.id.switch_enabled);
        checkForceSwitch = findViewById(R.id.check_force_switch);
        checkNotify = findViewById(R.id.check_notify);
        textStatus = findViewById(R.id.text_status);

        checkReboot = findViewById(R.id.check_reboot);
        radioTarget = findViewById(R.id.radio_target);
        editDelay = findViewById(R.id.edit_delay);
        editCooldown = findViewById(R.id.edit_cooldown);
        editDishAddr = findViewById(R.id.edit_dish_addr);
        editRouterAddr = findViewById(R.id.edit_router_addr);
        textRebootStatus = findViewById(R.id.text_reboot_status);

        editSsid.setText(Prefs.ssid(this));
        checkForceSwitch.setChecked(Prefs.forceSwitch(this));
        checkNotify.setChecked(Prefs.notifyOnConnect(this));
        loadRebootSettings();

        editSsid.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveSsid();
                return true;
            }
            return false;
        });
        editSsid.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveSsid();
        });

        switchEnabled.setOnCheckedChangeListener((v, checked) -> {
            if (suppressSwitchListener) return;
            saveAll();
            Prefs.setEnabled(this, checked);
            if (checked) {
                if (!hasLocationPermission()) requestLocationPermission();
                ReconnectService.start(this);
            } else {
                ReconnectService.stop(this);
            }
        });

        checkForceSwitch.setOnCheckedChangeListener((v, checked) -> Prefs.setForceSwitch(this, checked));
        checkNotify.setOnCheckedChangeListener((v, checked) -> Prefs.setNotifyOnConnect(this, checked));
        checkReboot.setOnCheckedChangeListener((v, checked) -> Prefs.setRebootEnabled(this, checked));
        radioTarget.setOnCheckedChangeListener((group, id) -> Prefs.setRebootTarget(this, targetFromRadio(id)));

        Button btnReconnect = findViewById(R.id.btn_reconnect_now);
        btnReconnect.setOnClickListener(v -> {
            saveAll();
            if (!hasLocationPermission()) {
                Toast.makeText(this, R.string.toast_need_permissions, Toast.LENGTH_SHORT).show();
                requestLocationPermission();
                return;
            }
            if (ReconnectService.running) {
                ReconnectService.reconnectNow(this);
            } else {
                reconnector.connect(Prefs.ssid(this), true);
                reconnector.startScan();
            }
            Toast.makeText(this, R.string.toast_reconnect_sent, Toast.LENGTH_SHORT).show();
        });

        Button btnOpenStarlink = findViewById(R.id.btn_open_starlink);
        btnOpenStarlink.setOnClickListener(v -> openStarlink());

        Button btnRebootNow = findViewById(R.id.btn_reboot_starlink_now);
        btnRebootNow.setOnClickListener(v -> {
            saveAll();
            Toast.makeText(this, R.string.toast_reboot_started, Toast.LENGTH_SHORT).show();
            textRebootStatus.setText(R.string.status_reboot_sending);
            if (ReconnectService.running) {
                ReconnectService.rebootStarlinkNow(this);
            } else {
                Prefs.setLastRebootAttemptAt(this, System.currentTimeMillis());
                StarlinkCommands.reboot(this, null, (ok, text) -> {
                    if (ok) Prefs.setLastRebootAt(this, System.currentTimeMillis());
                    String stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()) + "\n" + text;
                    Prefs.setLastRebootResult(this, stamp);
                    StatusBus.postReboot(stamp);
                });
            }
        });

        Button btnCheck = findViewById(R.id.btn_check);
        btnCheck.setOnClickListener(v -> {
            saveAll();
            Toast.makeText(this, R.string.toast_check_started, Toast.LENGTH_SHORT).show();
            textRebootStatus.setText(R.string.toast_check_started);
            StarlinkCommands.check(this, null, (ok, text) -> {
                if (!isFinishing()) textRebootStatus.setText(text);
            });
        });

        Button btnPermissions = findViewById(R.id.btn_permissions);
        btnPermissions.setOnClickListener(v -> requestLocationPermission());

        Button btnBackgroundLocation = findViewById(R.id.btn_background_location);
        btnBackgroundLocation.setOnClickListener(v -> requestBackgroundLocation());

        Button btnBattery = findViewById(R.id.btn_battery);
        btnBattery.setOnClickListener(v -> requestIgnoreBatteryOptimizations());

        Button btnWifiSettings = findViewById(R.id.btn_wifi_settings);
        btnWifiSettings.setOnClickListener(v -> openWifiSettings());
    }

    @Override
    protected void onResume() {
        super.onResume();
        suppressSwitchListener = true;
        switchEnabled.setChecked(Prefs.enabled(this));
        suppressSwitchListener = false;
        StatusBus.register(this);
        // If the user enabled the watchdog but the service died (battery saver etc.), revive it.
        if (Prefs.enabled(this) && !ReconnectService.running) {
            ReconnectService.start(this);
        }
    }

    @Override
    protected void onPause() {
        StatusBus.unregister(this);
        saveAll();
        super.onPause();
    }

    @Override
    public void onStatus(String text) {
        if (!ReconnectService.running || text == null || text.isEmpty()) {
            textStatus.setText(R.string.status_idle);
        } else {
            textStatus.setText(text);
        }
    }

    @Override
    public void onRebootStatus(String text) {
        if (text == null || text.isEmpty()) {
            String last = Prefs.lastRebootResult(this);
            textRebootStatus.setText(last.isEmpty() ? getString(R.string.reboot_none) : last);
        } else {
            textRebootStatus.setText(text);
        }
    }

    // ------------------------------------------------------------------ settings

    private void loadRebootSettings() {
        checkReboot.setChecked(Prefs.rebootEnabled(this));
        String target = Prefs.rebootTarget(this);
        radioTarget.check(Prefs.TARGET_ROUTER.equals(target) ? R.id.radio_router
                : Prefs.TARGET_BOTH.equals(target) ? R.id.radio_both : R.id.radio_dish);
        editDelay.setText(String.valueOf(Prefs.rebootDelaySec(this)));
        editCooldown.setText(String.valueOf(Prefs.rebootCooldownMin(this)));
        editDishAddr.setText(Prefs.dishAddr(this));
        editRouterAddr.setText(Prefs.routerAddr(this));
    }

    private static String targetFromRadio(int id) {
        if (id == R.id.radio_router) return Prefs.TARGET_ROUTER;
        if (id == R.id.radio_both) return Prefs.TARGET_BOTH;
        return Prefs.TARGET_DISH;
    }

    private void saveAll() {
        saveSsid();
        saveRebootSettings();
    }

    private void saveRebootSettings() {
        Prefs.setRebootEnabled(this, checkReboot.isChecked());
        Prefs.setRebootTarget(this, targetFromRadio(radioTarget.getCheckedRadioButtonId()));
        Prefs.setRebootDelaySec(this, parseInt(editDelay, Prefs.DEFAULT_DELAY_SEC));
        Prefs.setRebootCooldownMin(this, parseInt(editCooldown, Prefs.DEFAULT_COOLDOWN_MIN));
        Prefs.setDishAddr(this, text(editDishAddr));
        Prefs.setRouterAddr(this, text(editRouterAddr));
        // Reflect normalisation (e.g. a bare host gets the default port) back into the fields.
        if (!text(editDishAddr).equals(Prefs.dishAddr(this))) editDishAddr.setText(Prefs.dishAddr(this));
        if (!text(editRouterAddr).equals(Prefs.routerAddr(this))) editRouterAddr.setText(Prefs.routerAddr(this));
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private static int parseInt(EditText e, int fallback) {
        try {
            return Integer.parseInt(text(e));
        } catch (NumberFormatException ex) {
            e.setText(String.valueOf(fallback));
            return fallback;
        }
    }

    private void saveSsid() {
        String typed = text(editSsid);
        if (typed.isEmpty()) typed = Prefs.DEFAULT_SSID;
        String old = Prefs.ssid(this);
        if (!typed.equals(old)) {
            Prefs.setSsid(this, typed);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                reconnector.refreshSuggestion(typed);
            }
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ actions

    private void openStarlink() {
        Intent i = ReconnectService.starlinkLaunchIntent(this);
        if (getPackageManager().getLaunchIntentForPackage(STARLINK_PACKAGE) == null) {
            Toast.makeText(this, R.string.toast_starlink_missing, Toast.LENGTH_SHORT).show();
        }
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + STARLINK_PACKAGE)));
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        requestPermissions(new String[] {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, REQ_LOCATION);
    }

    private void requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!hasLocationPermission()) {
                requestLocationPermission();
                return;
            }
            requestPermissions(new String[] { Manifest.permission.ACCESS_BACKGROUND_LOCATION },
                    REQ_BACKGROUND_LOCATION);
        } else {
            openAppSettings();
        }
    }

    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        PowerManager pm = getSystemService(PowerManager.class);
        Intent i;
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        } else {
            i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
        }
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            openAppSettings();
        }
    }

    private void openWifiSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (ActivityNotFoundException e) {
            openAppSettings();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && hasLocationPermission() && Prefs.enabled(this)) {
            ReconnectService.start(this);
        }
    }
}
