package app.minimouse;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Full-screen list: tick the apps where the cursor pauses itself. */
public class AppsActivity extends Activity {

    private final List<String> packages = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Pausa musen i dessa appar");

        PackageManager pm = getPackageManager();
        Set<String> excluded = Prefs.excluded(this);

        Map<String, String> byPkg = new LinkedHashMap<>();
        List<ResolveInfo> apps = Prefs.launcherApps(this);
        apps.sort((a, b) -> String.valueOf(a.loadLabel(pm))
                .compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
        for (ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || byPkg.containsKey(pkg)) continue;
            byPkg.put(pkg, String.valueOf(ri.loadLabel(pm)));
        }
        if (!byPkg.containsKey("com.android.settings")) {
            byPkg.put("com.android.settings", "Inställningar");
        }
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, String> e : byPkg.entrySet()) {
            packages.add(e.getKey());
            labels.add(e.getValue());
        }

        ListView list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        list.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_multiple_choice, labels));
        for (int i = 0; i < packages.size(); i++) {
            list.setItemChecked(i, excluded.contains(packages.get(i)));
        }
        list.setOnItemClickListener((parent, view, position, id) -> {
            Set<String> now = Prefs.excluded(this);
            String pkg = packages.get(position);
            if (list.isItemChecked(position)) now.add(pkg);
            else now.remove(pkg);
            Prefs.setExcluded(this, now);
        });
        setContentView(list);
        list.requestFocus();
    }
}
