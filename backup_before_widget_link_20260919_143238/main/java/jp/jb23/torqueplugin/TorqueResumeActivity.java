package jp.jb23.torqueplugin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class TorqueResumeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            Intent intent = getPackageManager()
                    .getLaunchIntentForPackage("org.prowl.torque");

            if (intent != null) {
                intent.setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                );

                startActivity(intent);
            }
        } catch (Throwable ignored) {
        }

        finish();
    }
}
