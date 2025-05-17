package org.broeuschmeul.android.gps.ui;

import android.content.res.Configuration;
import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;

import org.broeuschmeul.android.gps.R;

import java.util.Objects;

public class SettingsActivity extends USBGpsBaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (isDoublePanelAvailable()) {
            finish();
            return;
        }

        setContentView(R.layout.activity_settings);
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);

        showSettingsFragment(R.id.settings_content, true);
    }

    private boolean isDoublePanelAvailable() {
        return (getResources().getConfiguration().screenLayout
                & Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE &&
                getResources()
                        .getConfiguration()
                        .orientation == Configuration.ORIENTATION_LANDSCAPE;
    }
}
