package app.minimouse;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Start page: status and three buttons. */
public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, 0, 0, pad);
        box.addView(status);

        Button apps = new Button(this);
        apps.setText("Pausade appar");
        apps.setOnClickListener(v -> startActivity(new Intent(this, AppsActivity.class)));
        box.addView(apps);

        Button keys = new Button(this);
        keys.setText("Knappar");
        keys.setOnClickListener(v -> startActivity(new Intent(this, KeysActivity.class)));
        box.addView(keys);

        Button open = new Button(this);
        open.setText("Tillgänglighet (slå på/av)");
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        box.addView(open);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean on = enabled != null && enabled.contains(getPackageName());
        status.setText(on ? "MiniMouse är PÅ. Håll in * i valfri app."
                : "MiniMouse är AV. Slå på den under Tillgänglighet.");
    }
}
