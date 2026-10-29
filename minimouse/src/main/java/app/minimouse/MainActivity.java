package app.minimouse;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Status, key help, and the list of apps where the cursor pauses itself. */
public class MainActivity extends Activity {

    private TextView status;
    private ListView list;
    private final List<String> packages = new ArrayList<>();
    private Set<String> excluded = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, 0);

        status = new TextView(this);
        status.setTextSize(16);
        root.addView(status);

        Button open = new Button(this);
        open.setText("Open accessibility settings");
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(open);

        TextView help = new TextView(this);
        help.setTextSize(13);
        help.setPadding(0, pad / 2, 0, pad / 2);
        help.setText("Hold *: cursor on/off\n"
                + "D-pad: short press jumps to the next button/link, hold glides freely\n"
                + "Against the top/bottom edge: scroll\n"
                + "OK: tap (hold = long press)  ·  5: long press  ·  0: double tap\n"
                + "1: drag on/off (select text)  ·  7: paste\n"
                + "2 / 8: previous / next post  ·  3 / 9: zoom in / out\n"
                + "4 / 6: scroll left / right, hold = swipe in from the edge\n"
                + "Back: back in the app\n\n"
                + "The cursor pauses while you type and in the ticked apps below, "
                + "and hides itself after a few seconds without movement.\n\n"
                + "Pause the cursor in these apps:");
        root.addView(help);

        list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        list.setOnItemClickListener((parent, view, position, id) -> {
            String pkg = packages.get(position);
            if (list.isItemChecked(position)) excluded.add(pkg);
            else excluded.remove(pkg);
            Prefs.setExcluded(this, excluded);
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        loadApps();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        excluded = Prefs.excluded(this);
        Map<String, String> byLabel = new LinkedHashMap<>();
        List<ResolveInfo> apps = Prefs.launcherApps(this);
        apps.sort((a, b) -> String.valueOf(a.loadLabel(pm))
                .compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
        for (ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || byLabel.containsKey(pkg)) continue;
            byLabel.put(pkg, String.valueOf(ri.loadLabel(pm)));
        }
        // Settings has no launcher entry on some phones: make sure it's listed.
        if (!byLabel.containsKey("com.android.settings")) {
            byLabel.put("com.android.settings", "Settings");
        }
        packages.clear();
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, String> e : byLabel.entrySet()) {
            packages.add(e.getKey());
            labels.add(e.getValue());
        }
        list.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_multiple_choice, labels));
        for (int i = 0; i < packages.size(); i++) {
            list.setItemChecked(i, excluded.contains(packages.get(i)));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean on = enabled != null && enabled.contains(getPackageName());
        status.setText(on ? "MiniMouse is ON. Hold * in any app."
                : "MiniMouse is OFF. Turn it on under Accessibility.");
        status.setVisibility(View.VISIBLE);
    }
}
