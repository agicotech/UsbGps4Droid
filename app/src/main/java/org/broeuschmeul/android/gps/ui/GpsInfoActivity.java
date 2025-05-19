package org.broeuschmeul.android.gps.ui;

import static org.broeuschmeul.android.gps.driver.USBGpsProviderService.PREF_GPS_DEVICE_PRODUCT_ID;
import static org.broeuschmeul.android.gps.driver.USBGpsProviderService.PREF_GPS_DEVICE_VENDOR_ID;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import androidx.appcompat.widget.SwitchCompat;

import android.os.Handler;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.TextView;

import org.broeuschmeul.android.gps.driver.USBGpsManager;
import org.broeuschmeul.android.gps.nmea.util.NmeaParser;
import org.broeuschmeul.android.gps.R;
import org.broeuschmeul.android.gps.USBGpsApplication;
import org.broeuschmeul.android.gps.driver.USBGpsProviderService;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import android.os.Looper;

import android.widget.Toast;

/**
 * Created by Oliver Bell 5/12/15
 *
 * This activity displays a log, as well as the GPS info. If the users device is
 * large enough and in landscape, the settings fragment will be shown alongside
 */

public class GpsInfoActivity extends USBGpsBaseActivity implements
        USBGpsApplication.ServiceDataListener {

    private SharedPreferences sharedPreferences;
    private static final String TAG = GpsInfoActivity.class.getSimpleName();

    private USBGpsApplication application;

    private SwitchCompat startSwitch;
    private TextView numSatellites;
    private TextView accuracyText;
    private TextView locationText;
    private TextView elevationText;
    private TextView logText;
    private TextView timeText;
    private ScrollView logTextScroller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (isDoublePanel()) {
            savedInstanceState = null;
        }
        super.onCreate(savedInstanceState);

        if (isDoublePanel()) {
            setContentView(R.layout.activity_info_double);
        } else {
            setContentView(R.layout.activity_info);
        }

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        application = (USBGpsApplication) getApplication();

        setupUI();

        if (isDoublePanel()) {
            showSettingsFragment(R.id.settings_holder, false);
        }

        Intent intent = getIntent();
        onNewIntent(intent);

    }

    private void setupUI() {
        if (!isDoublePanel()) {
            startSwitch = (SwitchCompat) findViewById(R.id.service_start_switch);
            startSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    sharedPreferences
                            .edit()
                            .putBoolean(USBGpsProviderService.PREF_START_GPS_PROVIDER, isChecked)
                            .apply();
                }
            });
        }

        numSatellites = (TextView) findViewById(R.id.num_satellites_text);
        accuracyText = (TextView) findViewById(R.id.accuracy_text);
        locationText = (TextView) findViewById(R.id.location_text);
        elevationText = (TextView) findViewById(R.id.elevation_text);
        timeText = (TextView) findViewById(R.id.gps_time_text);

        logText = (TextView) findViewById(R.id.log_box);
        logTextScroller = (ScrollView) findViewById(R.id.log_box_scroller);
    }

    private boolean isDoublePanel() {
        return (getResources().getConfiguration().screenLayout
                & Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE &&
                getResources()
                        .getConfiguration()
                        .orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void updateData() {
        boolean running =
                sharedPreferences.getBoolean(USBGpsProviderService.PREF_START_GPS_PROVIDER, false);

        if (!isDoublePanel()) {
            startSwitch.setChecked(
                    running
            );
        }

        String accuracyValue = "N/A";
        String numSatellitesValue = "N/A";
        String lat = "N/A";
        String lon = "N/A";
        String elevation = "N/A";
        String gpsTime = "N/A";
        String systemTime = "N/A";

        Location location = application.getLastLocation();
        if (!running) {
            location = null;
        }

        if (location != null) {
            accuracyValue = String.valueOf(location.getAccuracy());
            if (location.getExtras() != null) {
                numSatellitesValue = String.valueOf(location.getExtras().getInt(NmeaParser.SATELLITE_KEY));
            }
            DecimalFormat df = new DecimalFormat("#.#####");
            lat = df.format(location.getLatitude());
            lon = df.format(location.getLongitude());
            elevation = String.valueOf(location.getAltitude());

            gpsTime = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
                    .format(new Date(location.getTime()));

            systemTime = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
                    .format(new Date(location.getExtras().getLong(NmeaParser.SYSTEM_TIME_FIX)));
        }

        numSatellites.setText(
                getString(R.string.number_of_satellites_placeholder, numSatellitesValue)
        );
        accuracyText.setText(getString(R.string.accuracy_placeholder, accuracyValue));
        locationText.setText(getString(R.string.location_placeholder, lat, lon));
        elevationText.setText(getString(R.string.elevation_placeholder, elevation));
        timeText.setText(getString(R.string.gps_time_placeholder, gpsTime, systemTime));
        updateLog();
    }

    public void updateLog() {

        boolean atBottom = (
                logText.getBottom() - (
                        logTextScroller.getHeight() +
                                logTextScroller.getScrollY()
                )
        ) == 0;

        logText.setText(TextUtils.join("\n", application.getLogLines()));

        if (atBottom) {
            logText.post(() -> logTextScroller.fullScroll(View.FOCUS_DOWN));
        }
    }

    @Override
    public void onResume() {
        updateData();
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);
        ((USBGpsApplication) getApplication()).registerServiceDataListener(this);
        super.onResume();

        Intent intent = getIntent();
        onNewIntent(intent);

    }

    void startServiceAndSelectDevice(Intent intent) {
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

        sharedPreferences.edit()
                .putInt(USBGpsProviderService.PREF_GPS_DEVICE_VENDOR_ID, device.getVendorId())
                .putInt(USBGpsProviderService.PREF_GPS_DEVICE_PRODUCT_ID, device.getProductId())
                .putBoolean(USBGpsProviderService.PREF_START_GPS_PROVIDER, true).apply();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (Objects.equals(intent.getAction(), UsbManager.ACTION_USB_DEVICE_ATTACHED)){
            boolean needToStart = sharedPreferences.getBoolean(USBGpsProviderService.PREF_START_ON_DEVICE_CONNECT, true);
            if (!needToStart) return;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    startServiceAndSelectDevice(intent);
                }
            }, 70);

            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    // Проверяем, не была ли Activity уже закрыта или уничтожена
                    if (!isFinishing() && !isDestroyed()) {
                        finish();
                    }
                }
            }, 200);
        }
        //Toast.makeText(this, intent.getAction(), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onPause() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        ((USBGpsApplication) getApplication()).unregisterServiceDataListener(this);
        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (!isDoublePanel()) {
            MenuInflater inflater = getMenuInflater();
            inflater.inflate(R.menu.menu_main, menu);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onNewSentence(String sentence) {
        updateLog();
    }

    @Override
    public void onLocationNotified(Location location) {
        updateData();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        assert key != null;
        if (key.equals(USBGpsProviderService.PREF_START_GPS_PROVIDER)) {
            updateData();
        }

        super.onSharedPreferenceChanged(sharedPreferences, key);
    }
}
