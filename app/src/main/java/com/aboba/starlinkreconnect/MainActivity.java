package com.aboba.starlinkreconnect;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity implements StatusBus.Listener {
    public static final String STARLINK_PACKAGE = "com.starlink.mobile";

    private static final int REQ_LOCATION = 1;
    private static final int REQ_BACKGROUND_LOCATION = 2;
    private static final int REQ_SAVE_NETWORK = 3;

    private EditText editSsid;
    private Switch switchEnabled;
    private CheckBox checkForceSwitch;
    private CheckBox checkNotify;
    private TextView textStatus;

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

        editSsid.setText(Prefs.ssid(this));
        checkForceSwitch.setChecked(Prefs.forceSwitch(this));
        checkNotify.setChecked(Prefs.notifyOnConnect(this));

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
            saveSsid();
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

        Button btnReconnect = findViewById(R.id.btn_reconnect_now);
        btnReconnect.setOnClickListener(v -> {
            saveSsid();
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

        Button btnPermissions = findViewById(R.id.btn_permissions);
        btnPermissions.setOnClickListener(v -> requestLocationPermission());

        Button btnBackgroundLocation = findViewById(R.id.btn_background_location);
        btnBackgroundLocation.setOnClickListener(v -> requestBackgroundLocation());

        Button btnBattery = findViewById(R.id.btn_battery);
        btnBattery.setOnClickListener(v -> requestIgnoreBatteryOptimizations());

        Button btnSaveNetwork = findViewById(R.id.btn_save_network);
        btnSaveNetwork.setOnClickListener(v -> saveNetworkInSystem());
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
        saveSsid();
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

    // ------------------------------------------------------------------ actions

    private void saveSsid() {
        String typed = editSsid.getText() == null ? "" : editSsid.getText().toString().trim();
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

    private void saveNetworkInSystem() {
        saveSsid();
        String ssid = Prefs.ssid(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ArrayList<WifiNetworkSuggestion> list = new ArrayList<>();
            list.add(reconnector.buildSuggestion(ssid));
            Intent i = new Intent(Settings.ACTION_WIFI_ADD_NETWORKS);
            i.putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, list);
            try {
                startActivityForResult(i, REQ_SAVE_NETWORK);
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        if (!hasLocationPermission()) {
            requestLocationPermission();
            return;
        }
        int r = reconnector.connect(ssid, false);
        int status = reconnector.ensureSuggestion(ssid);
        Toast.makeText(this, getString(R.string.toast_suggestion_result, status >= 0 ? status : r),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAVE_NETWORK) {
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
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
