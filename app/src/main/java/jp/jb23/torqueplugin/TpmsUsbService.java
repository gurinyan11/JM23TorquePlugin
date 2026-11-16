package jp.jb23.torqueplugin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import org.prowl.torque.remote.ITorqueService;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Opt-in USB TPMS receiver. It runs separately from BridgeService's ECU 2100
 * polling and does not start at boot. Only the APK-confirmed CH340 VID/PID is
 * enabled until the actual S8MS USB receiver is identified.
 */
public final class TpmsUsbService extends Service {
    public static final String ACTION_START = "jp.jb23.torqueplugin.TPMS_START";
    public static final String ACTION_STOP = "jp.jb23.torqueplugin.TPMS_STOP";
    private static final String ACTION_PERMISSION = "jp.jb23.torqueplugin.TPMS_USB_PERMISSION";
    private static final String CHANNEL = "jm23_tpms_usb";
    private static final String TAG = "JM23-TPMS";
    private static final int NOTIFICATION_ID = 24;
    private static final int CH340_VID = 0x1A86;
    private static final int CH340_PID = 0x7523;

    private UsbManager usbManager;
    private SharedPreferences prefs;
    private final TpmsFrameDecoder decoder = new TpmsFrameDecoder();
    private volatile boolean enabled;
    private volatile UsbSerialPort openedPort;
    private volatile ITorqueService torque;
    private volatile UsbDevice activeDevice;
    private Thread readerThread;
    private boolean torqueBound;

    private final ServiceConnection torqueConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            torque = ITorqueService.Stub.asInterface(binder);
            put("torque_status", "Connected");
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            torque = null;
            put("torque_status", "Disconnected");
        }
        @Override public void onBindingDied(ComponentName name) {
            torque = null;
            put("torque_status", "Binding died");
        }
        @Override public void onNullBinding(ComponentName name) {
            torque = null;
            put("torque_status", "Null binding");
        }
    };

    private final BroadcastReceiver usbEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (ACTION_PERMISSION.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (!enabled) return;
                if (!granted) {
                    setState("USB permission denied");
                    return;
                }
                if (device != null && isExpectedReceiver(device)) openMatchingReceiver();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && device.equals(activeDevice)) {
                    closeReader();
                    setState("USB receiver detached; reconnect then press START");
                }
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("jm23_tpms_usb", MODE_PRIVATE);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification("TPMS idle"));
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbEvents, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbEvents, filter);
        }
        Intent torqueIntent = new Intent();
        torqueIntent.setClassName("org.prowl.torque", "org.prowl.torque.remote.TorqueService");
        try {
            torqueBound = bindService(torqueIntent, torqueConnection, BIND_AUTO_CREATE);
            put("torque_status", torqueBound ? "Connecting" : "Torque not found");
        } catch (Exception e) {
            put("torque_status", "Bind failed: " + e.getClass().getSimpleName());
        }
        setState("Idle; no automatic USB scanning");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            enabled = false;
            closeReader();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;
        enabled = true;
        openMatchingReceiver();
        return START_NOT_STICKY;
    }

    private static boolean isExpectedReceiver(UsbDevice d) {
        return d.getVendorId() == CH340_VID && d.getProductId() == CH340_PID;
    }

    private synchronized void openMatchingReceiver() {
        if (!enabled || (readerThread != null && readerThread.isAlive())) return;
        if (usbManager == null) {
            setState("Android USB host unavailable");
            return;
        }
        StringBuilder all = new StringBuilder();
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        for (UsbDevice d : devices.values()) {
            if (all.length() > 0) all.append("  ");
            all.append(String.format(Locale.US, "%04X:%04X", d.getVendorId(), d.getProductId()));
        }
        put("usb_devices", all.length() == 0 ? "(none)" : all.toString());

        UsbSerialDriver match = null;
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        for (UsbSerialDriver driver : drivers) {
            if (isExpectedReceiver(driver.getDevice())) {
                match = driver;
                break;
            }
        }
        if (match == null) {
            setState("CH340 1A86:7523 not detected; see USB IDs");
            return;
        }
        UsbDevice device = match.getDevice();
        if (!usbManager.hasPermission(device)) {
            Intent request = new Intent(ACTION_PERMISSION).setPackage(getPackageName());
            int options = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) options |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, request, options);
            setState("Requesting USB permission");
            usbManager.requestPermission(device, pi);
            return;
        }
        final UsbSerialDriver selected = match;
        decoder.reset();
        readerThread = new Thread(() -> readLoop(selected), "JM23-TPMS-USB");
        readerThread.start();
    }

    private void readLoop(UsbSerialDriver driver) {
        UsbDeviceConnection connection = null;
        UsbSerialPort port = null;
        try {
            connection = usbManager.openDevice(driver.getDevice());
            if (connection == null) throw new IOException("openDevice returned null");
            if (driver.getPorts().isEmpty()) throw new IOException("No USB serial ports");
            port = driver.getPorts().get(0);
            port.open(connection);
            openedPort = port;
            activeDevice = driver.getDevice();
            port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            setState("USB 19200/8N1 connected; awaiting TPMS frames");
            byte[] buffer = new byte[128];
            while (enabled && !Thread.currentThread().isInterrupted()) {
                int count = port.read(buffer, 1000);
                if (count <= 0) continue;
                logRaw(buffer, count);
                List<TpmsFrameDecoder.Reading> reports =
                        decoder.feed(buffer, count, System.currentTimeMillis());
                for (TpmsFrameDecoder.Reading r : reports) {
                    logReading(r);
                    ITorqueService t = torque;
                    if (t != null) {
                        try {
                            TpmsPidPublisher.publish(t, r);
                        } catch (Exception e) {
                            put("torque_status", "Publish error: " + e.getClass().getSimpleName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (enabled) setState("USB read failed: " + e.getClass().getSimpleName()
                    + " " + String.valueOf(e.getMessage()));
            Log.w(TAG, "USB session ended", e);
        } finally {
            if (port != null) try { port.close(); } catch (Exception ignored) { }
            if (connection != null) try { connection.close(); } catch (Exception ignored) { }
            openedPort = null;
            activeDevice = null;
            decoder.reset();
        }
    }

    /** Keep small, bounded excerpts even before any sensors are installed. */
    private void logRaw(byte[] bytes, int count) {
        long total = prefs.getLong("raw_reads", 0) + 1;
        SharedPreferences.Editor e = prefs.edit().putLong("raw_reads", total);
        if (total <= 12) {
            StringBuilder hex = new StringBuilder();
            int limit = Math.min(count, 64);
            for (int i = 0; i < limit; ++i)
                hex.append(String.format(Locale.US, "%02X ", bytes[i] & 0xFF));
            String past = prefs.getString("raw_hex", "");
            e.putString("raw_hex", past + "\n#" + total + " " + hex.toString().trim());
        }
        e.apply();
    }

    private void logReading(TpmsFrameDecoder.Reading r) {
        String prefix = r.wheel.name().toLowerCase(Locale.US);
        long total = prefs.getLong("decoded_frames", 0) + 1;
        prefs.edit()
                .putLong("decoded_frames", total)
                .putFloat(prefix + "_bar", (float) r.pressureBar())
                .putInt(prefix + "_temp_c", r.temperatureC)
                .putBoolean(prefix + "_battery_low", r.lowBattery)
                .putBoolean(prefix + "_air_leak", r.airLeak)
                .putBoolean(prefix + "_no_signal", r.noSignal)
                .putLong(prefix + "_last_received", r.receivedAtMillis)
                .apply();
        setState("Receiving verified TPMS frames: " + total);
    }

    private void closeReader() {
        Thread t = readerThread;
        if (t != null) t.interrupt();
        UsbSerialPort p = openedPort;
        if (p != null) {
            try { p.close(); } catch (Exception ignored) { }
        }
    }

    private void setState(String state) {
        put("state", state);
        Log.i(TAG, state);
    }

    private void put(String key, String value) {
        if (prefs != null) prefs.edit().putString(key, value).apply();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager n = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (n != null)
                n.createNotificationChannel(new NotificationChannel(CHANNEL,
                        "JM23 USB TPMS", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification notification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ?
                new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.jm23_plugin_icon)
                .setContentTitle("JM23 TPMS USB test")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        enabled = false;
        closeReader();
        if (torqueBound) {
            try { unbindService(torqueConnection); } catch (Exception ignored) { }
        }
        try { unregisterReceiver(usbEvents); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
