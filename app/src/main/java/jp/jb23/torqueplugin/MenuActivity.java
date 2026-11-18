package jp.jb23.torqueplugin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MenuActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView title = new TextView(this);
        title.setText("JM23 Torque Bridge");
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(38)));
        String[] names = {"Fuel Management", "Diagnostics", "Settings", "", "", ""};
        String[] modes = {"fuel", "diagnostics", "settings", "", "", ""};
        for (int row = 0; row < 3; row++) {
            LinearLayout line = new LinearLayout(this);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, 0, 1f);
            root.addView(line, rowParams);
            for (int col = 0; col < 2; col++) {
                int index = row * 2 + col;
                Button tile = new Button(this);
                tile.setAllCaps(false);
                tile.setText(names[index].isEmpty() ? " " : names[index]);
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, -1, 1f);
                tileParams.setMargins(dp(3), dp(3), dp(3), dp(3));
                line.addView(tile, tileParams);
                if (modes[index].isEmpty()) {
                    tile.setEnabled(false);
                    tile.setContentDescription("Reserved");
                } else {
                    final String mode = modes[index];
                    tile.setOnClickListener(v -> {
                        Intent intent = new Intent(this, MainActivity.class);
                        intent.putExtra("jm23_page", mode);
                        startActivity(intent);
                    });
                }
            }
        }
        setContentView(root);
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
