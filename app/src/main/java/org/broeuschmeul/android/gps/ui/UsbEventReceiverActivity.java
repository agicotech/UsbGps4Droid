package org.broeuschmeul.android.gps.ui;

import android.app.Activity;

import org.broeuschmeul.android.gps.driver.USBGpsProviderService;


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