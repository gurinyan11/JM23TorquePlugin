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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
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
        Button start = findViewById(R.id.start);
        Button stop = findViewById(R.id.stop);
        Button clear = findViewById(R.id.clear);

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

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
