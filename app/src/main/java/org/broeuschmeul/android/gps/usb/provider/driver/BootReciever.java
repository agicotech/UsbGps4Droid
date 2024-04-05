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

package org.broeuschmeul.android.gps.usb.provider.driver;

import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.broeuschmeul.android.gps.usb.provider.R;

import java.util.Objects;

/**
 * A Service used to replace Android internal GPS with a USB GPS and/or write GPS NMEA data in a File.
 *
 * @author Herbert von Broeuschmeul &
 * @author Oliver Bell
 */

    public class BootReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
   //         SharedPreferences sharedPreferences =
  //                  PreferenceManager.getDefaultSharedPreferences(context);
        //    if (BuildConfig.DEBUG) Log.d(LOG_TAG, Objects.requireNonNull(intent.getAction()));

   //         if ((Objects.equals(intent.getAction(), "com.my.action._COMPLETED") || Objects.equals(intent.getAction(), Intent.ACTION_BOOT_COMPLETED) || Objects.equals(intent.getAction(), Intent.ACTION_LOCKED_BOOT_COMPLETED)) &&
   //                 sharedPreferences.getBoolean(PREF_START_ON_BOOT, false) && !started)  {
    //            started = true;
                Toast.makeText(context, "BOOT_COMPLETED", Toast.LENGTH_SHORT).show();
/*                new Handler(context.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // if (BuildConfig.DEBUG) Log.d(LOG_TAG, "Boot start");
                        Toast.makeText(context, "startService", Toast.LENGTH_SHORT).show();
                        context.startService(
                                new Intent(context, USBGpsProviderService.class)
                                        .setAction(ACTION_START_GPS_PROVIDER)
                        );
                    }
                }, 5000);*/
                Toast.makeText(context, "startService", Toast.LENGTH_SHORT).show();
                context.startService(
                        new Intent(context, USBGpsProviderService.class)
                                .setAction(USBGpsProviderService.ACTION_START_GPS_PROVIDER)
                );
            }
        }

