package jp.jb23.tpmsplugin;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView usbStatus;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText(getSharedPreferences("torque_link", MODE_PRIVATE)
                    .getString("state", "Not tested"));
            handler.postDelayed(this, 1000L);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        usbStatus = findViewById(R.id.usb_status);
        findViewById(R.id.scan_usb).setOnClickListener(v -> scanUsbDevices());
        findViewById(R.id.start).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 105);
            }
            Intent intent = new Intent(this, TorqueLinkService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        });
        findViewById(R.id.stop).setOnClickListener(v ->
                stopService(new Intent(this, TorqueLinkService.class)));
    }

    /** List visible USB device IDs only. No USB permission request, open or read. */
    private void scanUsbDevices() {
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        if (manager == null) {
            usbStatus.setText("USB host manager unavailable");
            return;
        }
        Map<String, UsbDevice> devices = manager.getDeviceList();
        if (devices == null || devices.isEmpty()) {
            usbStatus.setText("USB scan: no connected devices visible");
            return;
        }
        StringBuilder report = new StringBuilder("USB scan: ")
                .append(devices.size()).append(" device(s)");
        for (UsbDevice device : devices.values()) {
            String id = String.format(Locale.US, "%04X:%04X",
                    device.getVendorId(), device.getProductId());
            report.append("\nVID:PID ").append(id)
                    .append("  interfaces=").append(device.getInterfaceCount());
            if (device.getVendorId() == 0x1A86 && device.getProductId() == 0x7523) {
                report.append("  [CH340 candidate]");
            }
        }
        usbStatus.setText(report.toString());
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
