package jp.jb23.torqueplugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class TorquePidReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();

        if (!"org.prowl.torque.PID_QUERY".equals(action)
                && !"org.prowl.torque.PID_PLUGIN".equals(action)
                && !"org.prowl.torque.APP_LAUNCHED".equals(action)) {
            return;
        }

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
                    .putString(
                            "pid_query",
                            "PID_QUERY_START_FAILED: "
                                    + t.getClass().getSimpleName()
                                    + ": "
                                    + String.valueOf(t.getMessage())
                    )
                    .apply();
        }
    }
}
