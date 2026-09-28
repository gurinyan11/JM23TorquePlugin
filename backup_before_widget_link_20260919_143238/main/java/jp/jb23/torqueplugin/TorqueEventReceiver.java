package jp.jb23.torqueplugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class TorqueEventReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();

        if ("org.prowl.torque.OBD_CONNECTED".equals(action)) {

            context.getSharedPreferences("debug", Context.MODE_PRIVATE)
                    .edit()
                    .putString("torque_event", "TORQUE_OBD_CONNECTED")
                    .apply();

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
                                "torque_event",
                                "TORQUE_EVENT_START_FAILED: "
                                        + t.getClass().getSimpleName()
                                        + ": "
                                        + String.valueOf(t.getMessage())
                        )
                        .apply();
            }
        }
    }
}
