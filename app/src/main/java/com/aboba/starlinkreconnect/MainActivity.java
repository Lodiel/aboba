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
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.CompoundButton;
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

        editSsid.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    saveSsid();
                    return true;
                }
                return false;
            }
        });
        editSsid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) saveSsid();
            }
        });

        switchEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                if (suppressSwitchListener) return;
                saveAll();
                Prefs.setEnabled(MainActivity.this, checked);
                if (checked) {
                    if (!hasLocationPermission()) requestLocationPermission();
                    ReconnectService.start(MainActivity.this);
                } else {
                    ReconnectService.stop(MainActivity.this);
                }
            }
        });

        checkForceSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                Prefs.setForceSwitch(MainActivity.this, checked);
            }
        });
        checkNotify.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                Prefs.setNotifyOnConnect(MainActivity.this, checked);
            }
        });
        checkReboot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                Prefs.setRebootEnabled(MainActivity.this, checked);
            }
        });
        radioTarget.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int id) {
                Prefs.setRebootTarget(MainActivity.this, targetFromRadio(id));
            }
        });

        Button btnReconnect = findViewById(R.id.btn_reconnect_now);
        btnReconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAll();
                if (!hasLocationPermission()) {
                    Toast.makeText(MainActivity.this, R.string.toast_need_permissions, Toast.LENGTH_SHORT).show();
                    requestLocationPermission();
                    return;
                }
                if (ReconnectService.running) {
                    ReconnectService.reconnectNow(MainActivity.this);
                } else {
                    reconnector.connect(Prefs.ssid(MainActivity.this), true);
                    reconnector.startScan();
                }
                Toast.makeText(MainActivity.this, R.string.toast_reconnect_sent, Toast.LENGTH_SHORT).show();
            }
        });

        Button btnOpenStarlink = findViewById(R.id.btn_open_starlink);
        btnOpenStarlink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openStarlink();
            }
        });

        Button btnRebootNow = findViewById(R.id.btn_reboot_starlink_now);
        btnRebootNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAll();
                Toast.makeText(MainActivity.this, R.string.toast_reboot_started, Toast.LENGTH_SHORT).show();
                textRebootStatus.setText(R.string.status_reboot_sending);
                if (ReconnectService.running) {
                    ReconnectService.rebootStarlinkNow(MainActivity.this);
                } else {
                    StarlinkCommands.reboot(MainActivity.this, null, new StarlinkCommands.Callback() {
                        @Override
                        public void onResult(boolean anyOk, boolean allOk, String text) {
                            Prefs.markRebootAttempt(MainActivity.this);
                            if (anyOk) Prefs.markRebootSent(MainActivity.this);
                            String stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()) + "\n" + text;
                            Prefs.setLastRebootResult(MainActivity.this, stamp);
                            StatusBus.postReboot(stamp);
                        }
                    });
                }
            }
        });

        Button btnCheck = findViewById(R.id.btn_check);
        btnCheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAll();
                Toast.makeText(MainActivity.this, R.string.toast_check_started, Toast.LENGTH_SHORT).show();
                textRebootStatus.setText(R.string.toast_check_started);
                StarlinkCommands.check(MainActivity.this, null, new StarlinkCommands.Callback() {
                    @Override
                    public void onResult(boolean anyOk, boolean allOk, String text) {
                        if (!isFinishing()) textRebootStatus.setText(text);
                    }
                });
            }
        });

        Button btnPermissions = findViewById(R.id.btn_permissions);
        btnPermissions.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestLocationPermission();
            }
        });

        Button btnBackgroundLocation = findViewById(R.id.btn_background_location);
        btnBackgroundLocation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestBackgroundLocation();
            }
        });

        Button btnBattery = findViewById(R.id.btn_battery);
        btnBattery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestIgnoreBatteryOptimizations();
            }
        });

        Button btnWifiSettings = findViewById(R.id.btn_wifi_settings);
        btnWifiSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWifiSettings();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        suppressSwitchListener = true;
        switchEnabled.setChecked(Prefs.enabled(this));
        suppressSwitchListener = false;
        StatusBus.register(this);
        // (Re)start from the visible activity whenever enabled: this revives a dead service and,
        // on Android 11+, upgrades a service that was started from BOOT_COMPLETED to
        // "while-in-use" location access, which it needs to see Wi-Fi scan results.
        if (Prefs.enabled(this)) {
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
        // A targetSdk 28 app may scan with either precise or approximate location.
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
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
