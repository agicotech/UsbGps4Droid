/*
 * Copyright (C) 2016 Oliver Bell
 * Copyright (C) 2010, 2011, 2012 Herbert von Broeuschmeul
 * Copyright (C) 2010, 2011, 2012 BluetoothGPS4Droid Project
 * Copyright (C) 2011, 2012 UsbGPS4Droid Project
 * 
 * This file is part of UsbGPS4Droid.
 *
 * UsbGPS4Droid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * UsbGPS4Droid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 *  You should have received a copy of the GNU General Public License
 *  along with UsbGPS4Droid. If not, see <http://www.gnu.org/licenses/>.
 */

package org.broeuschmeul.android.gps.driver;

import java.util.Objects;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import androidx.preference.PreferenceManager;
import androidx.core.content.ContextCompat;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;

import org.broeuschmeul.android.gps.R;
import org.broeuschmeul.android.gps.ui.GpsInfoActivity;
import org.broeuschmeul.android.gps.ui.USBGpsSettingsFragment;

/**
 * A Service used to replace Android internal GPS with a USB GPS and/or write GPS NMEA data in a File.
 *
 * @author Herbert von Broeuschmeul &
 * @author Oliver Bell
 */
public class USBGpsProviderService extends Service implements USBGpsManager.NmeaListener, LocationListener {
    public static final String ACTION_START_GPS_PROVIDER =
            "org.broeuschmeul.android.gps.action.START_GPS_PROVIDER";
    public static final String ACTION_STOP_GPS_PROVIDER =
            "org.broeuschmeul.android.gps.action.STOP_GPS_PROVIDER";

    public static final String PREF_START_GPS_PROVIDER = "startGps";
    public static final String PREF_START_ON_BOOT = "startOnBoot";
    public static final String PREF_START_ON_DEVICE_CONNECT = "startOnDeviceConnect";

    public static final String PREF_GPS_LOCATION_PROVIDER = "gpsLocationProviderKey";
    public static final String PREF_REPLACE_STD_GPS = "replaceStdtGps";
    public static final String PREF_FORCE_ENABLE_PROVIDER = "forceEnableProvider";
    public static final String PREF_MOCK_GPS_NAME = "mockGpsName";
    public static final String PREF_CONNECTION_RETRIES = "connectionRetries";
    public static final String PREF_GPS_DEVICE = "usbDevice";
    public static final String PREF_GPS_DEVICE_VENDOR_ID = "usbDeviceVendorId";
    public static final String PREF_GPS_DEVICE_PRODUCT_ID = "usbDeviceProductId";
    public static final String PREF_GPS_DEVICE_SPEED = "gpsDeviceSpeed";
    public static final String PREF_TOAST_LOGGING = "showToasts";

    public static final String PREF_SET_TIME = "setTime";
    public static final String PREF_ABOUT = "about";
    /**
     * Tag used for log messages
     */
    private static final String LOG_TAG = USBGpsProviderService.class.getSimpleName();

    private static final String NOTIFICATION_CHANNEL_ID = "service_notification";

    private USBGpsManager gpsManager = null;

    private boolean debugToasts = false;

    private static Context appContext = null;

    private NotificationManager notificationManager;
    private static Boolean started = false;
    public static class BootReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            SharedPreferences sharedPreferences =
                    android.preference.PreferenceManager.getDefaultSharedPreferences(context);

            if ((Objects.equals(intent.getAction(), Intent.ACTION_BOOT_COMPLETED) || Objects.equals(intent.getAction(), Intent.ACTION_LOCKED_BOOT_COMPLETED)) &&
                    sharedPreferences.getBoolean(PREF_START_ON_BOOT, false) && !started) {
                started = true;
                new Handler(context.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if(appContext != null) {
                            appContext.startService(
                                    new Intent(appContext, USBGpsProviderService.class)
                                            .setAction(ACTION_START_GPS_PROVIDER)
                            );
                        }
                    }
                }, 2000);
            }
        }
    }


    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = sharedPreferences.edit();

        debugToasts = sharedPreferences.getBoolean(PREF_TOAST_LOGGING, false);

        int vendorId = sharedPreferences.getInt(PREF_GPS_DEVICE_VENDOR_ID,
                USBGpsSettingsFragment.DEFAULT_GPS_VENDOR_ID);
        int productId = sharedPreferences.getInt(PREF_GPS_DEVICE_PRODUCT_ID,
                USBGpsSettingsFragment.DEFAULT_GPS_PRODUCT_ID);

        int maxConRetries = Integer.parseInt(
                Objects.requireNonNull(sharedPreferences.getString(
                        PREF_CONNECTION_RETRIES,
                        this.getString(R.string.defaultConnectionRetries)
                ))
        );

        log("prefs device addr: " + vendorId + " - " + productId);

        if (ACTION_START_GPS_PROVIDER.equals(intent.getAction())) {
            if (gpsManager == null) {
                String mockProvider = LocationManager.GPS_PROVIDER;
                if (!sharedPreferences.getBoolean(PREF_REPLACE_STD_GPS, true)) {
                    mockProvider = sharedPreferences.getString(PREF_MOCK_GPS_NAME, getString(R.string.defaultMockGpsName));
                }

                gpsManager = new USBGpsManager(this, vendorId, productId, maxConRetries);
                boolean enabled = gpsManager.enable();

                if (sharedPreferences.getBoolean(PREF_START_GPS_PROVIDER, false) != enabled) {
                    edit.putBoolean(PREF_START_GPS_PROVIDER, enabled);
                    edit.apply();
                }

                if (enabled) {
                    gpsManager.enableMockLocationProvider(mockProvider);

                    if (sharedPreferences.getInt(getString(R.string.pref_disable_reason_key), 0) != 0) {
                        edit.putInt(getString(R.string.pref_disable_reason_key), 0);
                        edit.apply();
                    }

                    PendingIntent launchIntent =
                            PendingIntent.getActivity(
                                    this,
                                    0,
                                    new Intent(this, GpsInfoActivity.class),
                                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            );



                    NotificationCompat.Builder builder;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        notificationManager.createNotificationChannel(
                                new NotificationChannel(
                                        NOTIFICATION_CHANNEL_ID,
                                        getString(R.string.app_name),
                                        NotificationManager.IMPORTANCE_HIGH
                                )
                        );
                        builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
                    } else {
                        builder = new NotificationCompat.Builder(this, "");
                    }

                    Notification notification = builder
                            .setContentIntent(launchIntent)
                            .setSmallIcon(R.drawable.ic_stat_notify)
                            .setAutoCancel(true)
                            .setContentTitle(getString(R.string.foreground_service_started_notification_title))
                            .setContentText(getString(R.string.foreground_gps_provider_started_notification))
                            .build();

                    startForeground(R.string.foreground_gps_provider_started_notification, notification);

                    showToast(R.string.msg_gps_provider_started);

                } else {
                    stopSelf();
                }

            } else {
                // We received a start intent even though it's already running so restart
                stopSelf();
                startService(new Intent(this, USBGpsProviderService.class)
                        .setAction(intent.getAction()));
            }
        } else if (ACTION_STOP_GPS_PROVIDER.equals(intent.getAction())) {
            if (sharedPreferences.getBoolean(PREF_START_GPS_PROVIDER, true)) {
                edit.putBoolean(PREF_START_GPS_PROVIDER, false);
                edit.commit();
            }
            stopSelf();

        }
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        USBGpsManager manager = gpsManager;
        gpsManager = null;
        if (manager != null) {
            if (manager.getDisableReason() != 0) {
                showToast(getString(R.string.msg_gps_provider_stopped_by_problem, getString(manager.getDisableReason())));
            } else {
                showToast(R.string.msg_gps_provider_stopped);
            }
            manager.removeNmeaListener(this);
            manager.disableMockLocationProvider();
            manager.disable();
        }
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = sharedPreferences.edit();

        if (sharedPreferences.getBoolean(PREF_START_GPS_PROVIDER, true)) {
            edit.putBoolean(PREF_START_GPS_PROVIDER, false);
            edit.apply();
        }

        super.onDestroy();
    }

    /**
     * Checks if the applications has the given runtime permission
     */
    private boolean hasPermission(String perm) {
        return (
                PackageManager.PERMISSION_GRANTED ==
                        ContextCompat.checkSelfPermission(this, perm)
        );
    }

    private void showToast(int messageId) {
        if (debugToasts) {
            Toast.makeText(this, messageId, Toast.LENGTH_SHORT).show();
        }
    }

    private void showToast(String message) {
        if (debugToasts) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }


    private void addNMEAString(String data) {

    }

    /* (non-Javadoc)
     * @see android.app.Service#onBind(android.content.Intent)
     */
    @Override
    public IBinder onBind(Intent intent) {
        log("trying access IBinder");
        return null;
    }

    @Override
    public void onLocationChanged(Location location) {

    }

    @Override
    public void onProviderDisabled(String provider) {
        log("The GPS has been disabled.....stopping the NMEA tracker service.");
        stopSelf();
    }

    @Override
    public void onProviderEnabled(String provider) {

    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onNmeaReceived(long timestamp, String data) {
        addNMEAString(data);
    }

    private void log(String message) {
        //if (BuildConfig.DEBUG) Log.d(LOG_TAG, message);
    }
}
