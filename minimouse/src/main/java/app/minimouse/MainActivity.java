package app.minimouse;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
        box.addView(status);

        Button open = new Button(this);
        open.setText("Open accessibility settings");
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        box.addView(open);

        TextView help = new TextView(this);
        help.setTextSize(14);
        help.setPadding(0, pad, 0, 0);
        help.setText("Hold *: cursor on/off\n"
                + "D-pad: move (hold to speed up)\n"
                + "Push against the top/bottom edge: scroll\n"
                + "OK: tap (hold = long press, like a finger)\n"
                + "5: long press\n"
                + "1: drag mode on/off (select text, sliders, maps)\n"
                + "2 / 8: previous / next post\n"
                + "4 / 6: scroll left / right\n"
                + "3 / 9: zoom in / out\n"
                + "0: double tap\n"
                + "7: paste into the text field\n"
                + "Back: back in the app as normal\n\n"
                + "While the keyboard is open the cursor pauses itself, so TT9 works as normal. It comes back when you're done typing.");
        box.addView(help);

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
        status.setText(on ? "MiniMouse is ON. Hold * anywhere."
                : "MiniMouse is OFF. Turn it on under Accessibility.");
    }
}
