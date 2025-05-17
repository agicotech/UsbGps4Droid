package org.broeuschmeul.android.gps.ui;

import android.app.Activity;

public class UsbEventReceiverActivity extends Activity {
    @Override
    public void onCreate(android.os.Bundle bundle) {
        super.onCreate(bundle);
    }

    @Override
    public void onResume() {
        super.onResume();
        finish();
    }
}