package org.broeuschmeul.android.gps.ui;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceFragmentCompat;
import android.view.MenuItem;

import org.broeuschmeul.android.gps.USBGpsApplication;
import org.broeuschmeul.android.gps.R;
import org.broeuschmeul.android.gps.driver.USBGpsProviderService;

/**
 * Created by freshollie on 15/05/17.
 *
 * Any activity in this app extends this activity.
 *
 * This Activity will show the stop dialogs and take care of permissions.
 *
 * It will also show the settings in a given layout ID and handle
 * the nested settings.
 */

public abstract class USBGpsBaseActivity extends AppCompatActivity implements
        USBGpsSettingsFragment.PreferenceScreenListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG_NESTED = "NESTED_PREFERENCE_SCREEN";

    private SharedPreferences sharedPreferences;
    private NotificationManager notificationManager;
    private ActivityManager activityManager;

    private boolean shouldInitialise = true;

    private int resSettingsHolder;
    private boolean tryingToStart;

    private static final int LOCATION_REQUEST = 238472383;

    private boolean homeAsUp = false;

    private boolean lastDaynightSetting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

        if (savedInstanceState != null) {
            shouldInitialise = false;
        }

        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
                !USBGpsApplication.wasLocationAsked()) {
            USBGpsApplication.setLocationAsked();
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_REQUEST);
        }

        lastDaynightSetting = getDaynightSetting();

    }

    private boolean getDaynightSetting() {
        return sharedPreferences.getBoolean(getString(R.string.pref_daynight_theme_key), false);
    }

    /**
     * Recreate the activity if we resume but the daynight setting has changed
     * @return
     */
    private void handleDaynightSettingChange() {
        boolean newDaynightSetting = getDaynightSetting();
        if (lastDaynightSetting != newDaynightSetting) {
            recreate();
        }
    }

    @Override
    public void onResume() {
        handleDaynightSettingChange();
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);
        // Basically check the service is really running
        if (!isServiceRunning()) {
            sharedPreferences
                    .edit()
                    .putBoolean(USBGpsProviderService.PREF_START_GPS_PROVIDER, false)
                    .apply();
        }
        super.onResume();
    }

    @Override
    public void onPause() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    /**
     * @param whereId where the fragment needs to be shown in.
     */
    public void showSettingsFragment(int whereId, boolean homeAsUp) {
        resSettingsHolder = whereId;
        // Opens the root fragment if its the first time opening
        if (shouldInitialise) {
            getSupportFragmentManager().beginTransaction()
                    .add(whereId, new USBGpsSettingsFragment())
                    .commit();
        }

        this.homeAsUp = homeAsUp;

    }

    private void clearStopNotification() {
        notificationManager.cancel(R.string.service_closed_because_connection_problem_notification_title);
    }

    private void showStopDialog() {
        int reason = sharedPreferences.getInt(getString(R.string.pref_disable_reason_key), 0);

        if (reason > 0) {
            if (reason == R.string.msg_mock_location_disabled) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.service_closed_because_connection_problem_notification_title)
                        .setMessage(
                                getString(
                                        R.string.service_closed_because_connection_problem_notification,
                                        getString(R.string.msg_mock_location_disabled)
                                )
                        )
                        .setPositiveButton(R.string.button_open_mock_location_settings,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        clearStopNotification();
                                        try {
                                            startActivity(
                                                    new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            );
                                        } catch (ActivityNotFoundException e) {
                                            new AlertDialog.Builder(USBGpsBaseActivity.this)
                                                    .setMessage(R.string.warning_no_developer_options)
                                                    .setPositiveButton(android.R.string.ok, null)
                                                    .show();
                                        }
                                    }
                                })
                        .show();
            } else {
                if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.service_closed_because_connection_problem_notification_title)
                            .setMessage(
                                    getString(
                                            R.string.service_closed_because_connection_problem_notification,
                                            getString(reason)
                                    )
                            )
                            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    clearStopNotification();
                                }
                            })
                            .show();
                }
            }
        }
    }

    /**
     * Checks if the applications has the given runtime permission
     * @param perm
     * @return
     */
    private boolean hasPermission(String perm) {
        return (
                PackageManager.PERMISSION_GRANTED ==
                        ContextCompat.checkSelfPermission(this, perm)
        );
    }

    /**
     * Android 6.0 requires permissions to be accepted at runtime
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST) {
            if (hasPermission(permissions[0])) {
                if (tryingToStart) {
                    tryingToStart = false;

                    Intent serviceIntent = new Intent(this, USBGpsProviderService.class);
                    serviceIntent.setAction(USBGpsProviderService.ACTION_START_GPS_PROVIDER);
                    startService(serviceIntent);
                }

            } else {
                tryingToStart = false;

                sharedPreferences.edit()
                        .putBoolean(USBGpsProviderService.PREF_START_GPS_PROVIDER, false)
                        .apply();

                new AlertDialog.Builder(this)
                        .setMessage(R.string.error_location_permission_required)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();

            }

        }
    }

    /**
     * If the service is killed then the shared preference for the service is never updated.
     * This checks if the service is running from the running preferences list
     */
    public boolean isServiceRunning() {
        for (ActivityManager.RunningServiceInfo service: activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (USBGpsProviderService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handles service attributes changing and requesting permissions
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals(USBGpsProviderService.PREF_START_GPS_PROVIDER)) {
            boolean val = sharedPreferences.getBoolean(key, false);

            if (val) {

                // If we have location permission then we can start the service
                if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    if (!isServiceRunning()) {
                        Intent serviceIntent = new Intent(this, USBGpsProviderService.class);
                        serviceIntent.setAction(USBGpsProviderService.ACTION_START_GPS_PROVIDER);
                        startService(serviceIntent);
                    }


                } else {
                    // Other wise we need to request for the permission
                    tryingToStart = true;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                            LOCATION_REQUEST);
                }

            } else {
                // Will show a stop dialog if needed
                showStopDialog();

                if (isServiceRunning()) {
                    Intent serviceIntent = new Intent(this, USBGpsProviderService.class);
                    serviceIntent.setAction(USBGpsProviderService.ACTION_STOP_GPS_PROVIDER);
                    startService(serviceIntent);
                }
            }
        }
    }

    /**
     * Called when a nested preference screen is clicked by the root preference screen
     *
     * Makes that fragment the now visible fragment
     */
    @Override
    public void onNestedScreenClicked(PreferenceFragmentCompat preferenceFragment) {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        getSupportFragmentManager().beginTransaction()
                .replace(resSettingsHolder, preferenceFragment, TAG_NESTED)
                .addToBackStack(TAG_NESTED)
                .commit();
    }


    @Override
    public void onBackPressed() {
        // this if statement is necessary to navigate through nested and main fragments
        if (getFragmentManager().getBackStackEntryCount() == 0) {
            super.onBackPressed();
        } else {
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.setDisplayHomeAsUpEnabled(homeAsUp);
            }
            getFragmentManager().popBackStack();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (menuItem.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return (super.onOptionsItemSelected(menuItem));
    }

    @Override
    public void onDestroy() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }
}
