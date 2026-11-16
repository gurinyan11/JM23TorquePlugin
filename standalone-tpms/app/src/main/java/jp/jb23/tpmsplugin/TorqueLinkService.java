package jp.jb23.tpmsplugin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import org.prowl.torque.remote.ITorqueService;

/**
 * Phase 0: reuse the working JM23 Torque binding pattern in a different APK.
 * No ECU commands or dummy PIDs. No boot receiver until coexistence is tested.
 */
public final class TorqueLinkService extends Service {
    private static final String CHANNEL_ID = "jm23_tpms_phase0";
    private static final int NOTIFICATION_ID = 34;
    private boolean bound;
    private SharedPreferences prefs;
    private ITorqueService torque;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            try {
                String descriptor = binder.getInterfaceDescriptor();
                if (!ITorqueService.Stub.DESCRIPTOR.equals(descriptor)) {
                    save("WRONG_BINDER: " + descriptor);
                    return;
                }
                torque = ITorqueService.Stub.asInterface(binder);
                save("TORQUE_BOUND: " + name.flattenToShortString());
            } catch (RemoteException e) {
                save("DESCRIPTOR_ERROR: " + e.getMessage());
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            torque = null;
            save("TORQUE_DISCONNECTED");
        }
        @Override public void onBindingDied(ComponentName name) {
            torque = null;
            save("TORQUE_BINDING_DIED");
        }
        @Override public void onNullBinding(ComponentName name) {
            torque = null;
            save("TORQUE_NULL_BINDING");
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("torque_link", Context.MODE_PRIVATE);
        createChannel();
        startForeground(NOTIFICATION_ID, notification());
        save("TORQUE_BINDING...");
        try {
            Intent request = new Intent();
            request.setClassName("org.prowl.torque", "org.prowl.torque.remote.TorqueService");
            bound = bindService(request, connection, Context.BIND_AUTO_CREATE);
            if (!bound) save("TORQUE_SERVICE_NOT_FOUND");
        } catch (Exception e) {
            save("TORQUE_BIND_FAILED: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    private void save(String state) {
        if (prefs != null) prefs.edit().putString("state", state).apply();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (mgr != null) mgr.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "JM23 TPMS development", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("JM23 TPMS - Torque link test")
                .setContentText("Independent plugin - no sensor access")
                .setOngoing(true).build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (bound) {
            try { unbindService(connection); } catch (Exception ignored) { }
            bound = false;
        }
        torque = null;
        save("STOPPED");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
