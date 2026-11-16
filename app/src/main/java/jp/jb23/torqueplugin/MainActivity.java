package jp.jb23.torqueplugin;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;
    private TextView fuelStatus;
    private TextView tpmsStatus;
    private Button fuelBaseline;
    private Button fuelFill;
    private Button fuelFullTank;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            android.content.SharedPreferences fuel =
                    getSharedPreferences("jm23_fuel", MODE_PRIVATE);
            int fullTankCount = fuel.getInt("full_tank_count", 0);
            boolean baselineSet = fuel.getBoolean("full_tank_baseline_set", false);
            double tankCapacity = Double.longBitsToDouble(fuel.getLong("tank_capacity_liters_bits", Double.doubleToRawLongBits(40.0)));
            double coefficient = Double.longBitsToDouble(fuel.getLong("liters_per_open_minute_bits", Double.doubleToRawLongBits(0.0)));
            double lastLiters = Double.longBitsToDouble(fuel.getLong("calibration_last_liters_bits", Double.doubleToRawLongBits(Double.NaN)));
            double fuelUsed = Double.longBitsToDouble(fuel.getLong("fuel_used_liters_bits", Double.doubleToRawLongBits(Double.NaN)));
            double fuelRemaining = Double.longBitsToDouble(fuel.getLong("fuel_remaining_liters_bits", Double.doubleToRawLongBits(Double.NaN)));
            double fuelPercent = Double.longBitsToDouble(fuel.getLong("fuel_remaining_percent_bits", Double.doubleToRawLongBits(Double.NaN)));

            String fuelCountText = fullTankCount + "/5";
            String fuelLitersText = Double.isNaN(lastLiters)
                    ? "null"
                    : String.format(java.util.Locale.US, "%.2f L", lastLiters);
            String coefficientText = coefficient > 0.0
                    ? String.format(java.util.Locale.US, "%.6f L/min", coefficient)
                    : "null";
            fuelBaseline.setEnabled(true);
            fuelFill.setEnabled(baselineSet && fullTankCount < 5);
            fuelFullTank.setEnabled(baselineSet && fullTankCount >= 5);
            String calibrationState = !baselineSet
                    ? "NOT STARTED"
                    : (fullTankCount >= 5 ? "COMPLETE" : "CALIBRATING");
            String usedText = Double.isNaN(fuelUsed) ? "--" : String.format(java.util.Locale.US, "%.2f L", fuelUsed);
            String remainingText = Double.isNaN(fuelRemaining) ? "--" : String.format(java.util.Locale.US, "%.2f L", fuelRemaining);
            String percentText = Double.isNaN(fuelPercent) ? "--" : String.format(java.util.Locale.US, "%.1f%%", fuelPercent);

            fuelStatus.setText(
                    "Fuel CAL " + fuelCountText + " " + calibrationState
                    + "  |  Last " + fuelLitersText
                    + "  |  Active " + coefficientText
                    + "  |  Tank " + String.format(java.util.Locale.US, "%.1f L", tankCapacity)
                    + "  |  Used " + usedText
                    + "  |  Remain " + remainingText
                    + "  |  " + percentText);

            android.content.SharedPreferences tpms = getSharedPreferences("jm23_tpms_usb", MODE_PRIVATE);
            StringBuilder tpmsView = new StringBuilder("TPMS: " + tpms.getString("state", "not started"));
            tpmsView.append("\\nUSB IDs: ").append(tpms.getString("usb_devices", "(not scanned)"));
            tpmsView.append("\\nTorque: ").append(tpms.getString("torque_status", "not connected"));
            tpmsView.append("\\nRaw reads: ").append(tpms.getLong("raw_reads", 0));
            tpmsView.append("  Frames: ").append(tpms.getLong("decoded_frames", 0));
            for (String wheel : new String[]{"fl","fr","rl","rr"}) {
                long last = tpms.getLong(wheel + "_last_received", 0);
                if (last != 0) tpmsView.append("\\n").append(wheel.toUpperCase(java.util.Locale.US))
                    .append(" ").append(tpms.getFloat(wheel + "_bar", 0f)).append("bar ")
                    .append(tpms.getInt(wheel + "_temp_c", 0)).append("°C")
                    .append(tpms.getBoolean(wheel + "_battery_low", false) ? " LOW BATTERY" : "")
                    .append(tpms.getBoolean(wheel + "_no_signal", false) ? " NO SIGNAL" : "");
            }
            tpmsView.append("\\n").append(tpms.getString("raw_hex", ""));
            tpmsStatus.setText(tpmsView.toString());
            String log = getSharedPreferences("debug", MODE_PRIVATE).getString("log", "");

            android.content.SharedPreferences rx =
                    getSharedPreferences("jm23_rx_test", MODE_PRIVATE);

            long rxCount = rx.getLong("rx_count", 0L);
            long lastRx = rx.getLong("last_rx_time", 0L);

            String rxInfo =
                    "ECU RX Count: " + rxCount + "\n" +
                    "Last RX: " + (lastRx == 0L
                            ? "none"
                            : new java.text.SimpleDateFormat(
                                    "HH:mm:ss",
                                    java.util.Locale.getDefault()
                              ).format(new java.util.Date(lastRx))) +
                    "\n\n";

            status.setText(rxInfo +
                    (log.isEmpty() ? "No debug data yet. Press START." : log));
            handler.postDelayed(this, 300);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        fuelStatus = findViewById(R.id.fuel_status);
        tpmsStatus = findViewById(R.id.tpms_usb_status);
        findViewById(R.id.tpms_usb_start).setOnClickListener(v -> startTpmsUsb());
        findViewById(R.id.tpms_usb_stop).setOnClickListener(v -> stopTpmsUsb());
        fuelBaseline = findViewById(R.id.fuel_baseline);
        android.widget.EditText fuelLitersInput = findViewById(R.id.fuel_liters_input);
        fuelFill = findViewById(R.id.fuel_fill);
        fuelFullTank = findViewById(R.id.fuel_full_tank);
        Button fuelTank39 = findViewById(R.id.fuel_tank_39);
        Button fuelTank40 = findViewById(R.id.fuel_tank_40);
        Button fuelTank41 = findViewById(R.id.fuel_tank_41);

        Button start = findViewById(R.id.start);
        Button stop = findViewById(R.id.stop);
        Button clear = findViewById(R.id.clear);

        fuelFill.setOnClickListener(v -> {
            String text = fuelLitersInput.getText().toString().trim();
            if (text.isEmpty()) return;
            try {
                double liters = Double.parseDouble(text);
                if (liters <= 0.0) return;
                Intent i = new Intent(this, BridgeService.class);
                i.setAction("jp.jb23.torqueplugin.ACTION_FUEL_FILL");
                i.putExtra("liters", liters);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            } catch (NumberFormatException ignored) {
            }
        });

        fuelBaseline.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Fuel calibration")
                    .setMessage("Start / restart calibration? Existing calibration data will be reset.")
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("RESET / START", (dialog, which) -> {
                        Intent i = new Intent(this, BridgeService.class);
                        i.setAction("jp.jb23.torqueplugin.ACTION_FUEL_BASELINE");
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
                    })
                    .show();
        });


        fuelTank39.setOnClickListener(v -> sendFuelTankCapacity(39.0));
        fuelTank40.setOnClickListener(v -> sendFuelTankCapacity(40.0));
        fuelTank41.setOnClickListener(v -> sendFuelTankCapacity(41.0));

        fuelFullTank.setOnClickListener(v -> {
            Intent i = new Intent(this, BridgeService.class);
            i.setAction("jp.jb23.torqueplugin.ACTION_FUEL_FULL_TANK");
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        });

        start.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
            getSharedPreferences("debug", MODE_PRIVATE).edit().putString("log", "").apply();
            Intent i = new Intent(this, BridgeService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        });

        stop.setOnClickListener(v -> stopService(new Intent(this, BridgeService.class)));
        clear.setOnClickListener(v -> {
            getSharedPreferences("debug", MODE_PRIVATE).edit().clear().apply();
            getSharedPreferences("jm23_rx_test", MODE_PRIVATE).edit().clear().apply();
        });
    }

    private void startTpmsUsb() {
        Intent i = new Intent(this, TpmsUsbService.class).setAction(TpmsUsbService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void stopTpmsUsb() {
        Intent i = new Intent(this, TpmsUsbService.class).setAction(TpmsUsbService.ACTION_STOP);
        stopService(i);
    }

    private void sendFuelTankCapacity(double capacity) {
        Intent i = new Intent(this, BridgeService.class);
        i.setAction("jp.jb23.torqueplugin.ACTION_FUEL_TANK_CAPACITY");
        i.putExtra("capacity", capacity);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
