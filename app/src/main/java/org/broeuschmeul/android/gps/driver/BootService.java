package org.broeuschmeul.android.gps.driver;

/* loaded from: classes.dex */
public class BootService extends android.accessibilityservice.AccessibilityService {
    @Override // android.accessibilityservice.AccessibilityService
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent accessibilityEvent) {
    }

    @Override // android.accessibilityservice.AccessibilityService
    public void onInterrupt() {
    }

    @Override // android.accessibilityservice.AccessibilityService
    protected void onServiceConnected() {
        android.util.Log.i("BootService", "Accessibility service connected");
        getApplicationContext().startService(new android.content.Intent(getApplicationContext(), org.broeuschmeul.android.gps.driver.USBGpsProviderService.class));
    }

    @Override // android.app.Service
    public boolean onUnbind(android.content.Intent intent) {
        android.util.Log.i("BootService", "Accessibility service disconnected");
        return false;
    }
}