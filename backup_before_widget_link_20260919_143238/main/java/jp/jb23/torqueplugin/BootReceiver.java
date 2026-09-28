package jp.jb23.torqueplugin;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

public class BootReceiver extends BroadcastReceiver {

    private static final String ACTION_RETRY =
            "jp.jb23.torqueplugin.ACTION_BOOT_RETRY";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {

            context.getSharedPreferences("debug", Context.MODE_PRIVATE)
                    .edit()
                    .putString("log", "BOOT_RECEIVER_FIRED")
                    .apply();

            startBridge(context);

            scheduleRetry(context, 1, 10000L);
            scheduleRetry(context, 2, 30000L);

        } else if (ACTION_RETRY.equals(action)) {

            startBridge(context);
        }
    }

    private void startBridge(Context context) {
        try {
            Intent service = new Intent(context, BridgeService.class);

            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Throwable t) {
            context.getSharedPreferences("debug", Context.MODE_PRIVATE)
                    .edit()
                    .putString("log",
                            "BOOT_START_FAILED: "
                                    + t.getClass().getSimpleName()
                                    + ": "
                                    + String.valueOf(t.getMessage()))
                    .apply();
        }
    }

    private void scheduleRetry(Context context, int requestCode, long delayMs) {
        try {
            Intent retry = new Intent(context, BootReceiver.class);
            retry.setAction(ACTION_RETRY);

            PendingIntent pi = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    retry,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | PendingIntent.FLAG_IMMUTABLE
            );

            AlarmManager am =
                    (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

            if (am != null) {
                am.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delayMs,
                        pi
                );
            }
        } catch (Throwable ignored) {
        }
    }
}
