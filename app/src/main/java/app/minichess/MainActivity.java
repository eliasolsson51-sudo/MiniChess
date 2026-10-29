package app.minichess;

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
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * dp);

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

        Button chess = new Button(this);
        chess.setText("Open chess.com");
        chess.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage(ChessPadService.CHESS_PKG);
            if (i != null) startActivity(i);
        });
        box.addView(chess);

        TextView help = new TextView(this);
        help.setTextSize(14);
        help.setPadding(0, pad, 0, 0);
        help.setText(
                "MiniChess draws a marker over the board in the chess.com app.\n\n"
                + "In a game:\n"
                + "  D-pad: move the marker\n"
                + "  OK or 5: tap the square (piece first, then target)\n"
                + "  0: clear the yellow highlight\n"
                + "  #: pause / resume (menus work as normal when paused)\n"
                + "  *: calibrate the board\n\n"
                + "Calibrate once: open a game, press *, move the pink cross to the "
                + "board's top-left corner, OK, then bottom-right corner, OK.");
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
        status.setText(on
                ? "MiniChess is ON. Open chess.com and start a game."
                : "MiniChess is OFF. Turn it on under Accessibility.");
    }
}
