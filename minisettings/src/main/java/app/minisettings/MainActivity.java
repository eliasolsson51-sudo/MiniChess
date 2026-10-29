package app.minisettings;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * A dark, D-pad friendly settings menu. Quick toggles act directly; everything else opens
 * the matching page in the phone's own Settings app.
 *
 *   Up/Down     move
 *   OK          toggle / open
 *   Left/Right  adjust (brightness, volume)
 */
public class MainActivity extends Activity {

    /** One row in the menu. */
    private abstract static class Item {
        final String title;

        Item(String title) {
            this.title = title;
        }

        /** Current value shown under the title, or null. */
        String state() {
            return null;
        }

        void ok() {
        }

        /** -1 = left, +1 = right. Returns true if the row handles left/right. */
        boolean adjust(int dir) {
            return false;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private ListView list;
    private BaseAdapter adapter;

    private WifiManager wifi;
    private AudioManager audio;
    private CameraManager camera;
    private String torchId;
    private boolean torchOn = false;

    private float dp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        camera = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        findTorch();

        buildItems();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        TextView header = new TextView(this);
        header.setText("Inställningar");
        header.setTextColor(Color.WHITE);
        header.setTextSize(22);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (8 * dp));
        root.addView(header);

        list = new ListView(this);
        list.setBackgroundColor(Color.BLACK);
        list.setCacheColorHint(Color.BLACK);
        list.setDivider(new ColorDrawable(Color.rgb(40, 40, 40)));
        list.setDividerHeight(1);
        list.setSelector(new ColorDrawable(Color.rgb(30, 70, 140)));
        list.setDrawSelectorOnTop(false);
        list.setItemsCanFocus(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            items.get(position).ok();
            adapter.notifyDataSetChanged();
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        list.requestFocus();
        list.setSelection(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int pos = list.getSelectedItemPosition();
            if (pos >= 0 && pos < items.size()) {
                int dir = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (items.get(pos).adjust(dir)) {
                    adapter.notifyDataSetChanged();
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ menu

    private void buildItems() {
        items.add(new Item("Wifi") {
            @Override
            String state() {
                return wifi != null && wifi.isWifiEnabled() ? "På" : "Av";
            }

            @Override
            @SuppressWarnings("deprecation")
            void ok() {
                if (wifi == null || !wifi.setWifiEnabled(!wifi.isWifiEnabled())) {
                    open(Settings.ACTION_WIFI_SETTINGS);
                }
            }
        });
        items.add(new Item("Bluetooth") {
            @Override
            @SuppressWarnings("MissingPermission")
            String state() {
                BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
                if (bt == null) return "Saknas";
                try {
                    return bt.isEnabled() ? "På" : "Av";
                } catch (SecurityException e) {
                    return "?";
                }
            }

            @Override
            @SuppressWarnings({"deprecation", "MissingPermission"})
            void ok() {
                BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
                boolean done = false;
                try {
                    if (bt != null) done = bt.isEnabled() ? bt.disable() : bt.enable();
                } catch (SecurityException ignored) {
                }
                if (!done) open(Settings.ACTION_BLUETOOTH_SETTINGS);
            }
        });
        items.add(new Item("Ficklampa") {
            @Override
            String state() {
                return torchId == null ? "Saknas" : (torchOn ? "På" : "Av");
            }

            @Override
            void ok() {
                if (torchId == null) return;
                try {
                    camera.setTorchMode(torchId, !torchOn);
                    torchOn = !torchOn;
                } catch (CameraAccessException | IllegalArgumentException e) {
                    toast("Ficklampan är upptagen");
                }
            }
        });
        items.add(new Item("Ljud") {
            @Override
            String state() {
                switch (audio.getRingerMode()) {
                    case AudioManager.RINGER_MODE_VIBRATE:
                        return "Vibration";
                    case AudioManager.RINGER_MODE_SILENT:
                        return "Tyst";
                    default:
                        return "Ljud på";
                }
            }

            @Override
            void ok() {
                try {
                    audio.setRingerMode(audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL
                            ? AudioManager.RINGER_MODE_VIBRATE
                            : AudioManager.RINGER_MODE_NORMAL);
                } catch (SecurityException e) {
                    open(Settings.ACTION_SOUND_SETTINGS);
                }
            }
        });
        items.add(new Item("Ljusstyrka") {
            @Override
            String state() {
                try {
                    int auto = Settings.System.getInt(getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS_MODE);
                    int v = Settings.System.getInt(getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS);
                    String pct = Math.round(v * 100f / 255f) + " %";
                    return (auto == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC ? "Auto, " : "")
                            + pct + "   ◀ ▶";
                } catch (Settings.SettingNotFoundException e) {
                    return "◀ ▶";
                }
            }

            @Override
            void ok() {
                open(Settings.ACTION_DISPLAY_SETTINGS);
            }

            @Override
            boolean adjust(int dir) {
                if (!Settings.System.canWrite(MainActivity.this)) {
                    toast("Kör först: adb shell appops set app.minisettings WRITE_SETTINGS allow");
                    return true;
                }
                try {
                    int v = Settings.System.getInt(getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS, 128);
                    v = Math.max(5, Math.min(255, v + dir * 25));
                    Settings.System.putInt(getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    Settings.System.putInt(getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS, v);
                    // Apply right away in this window too.
                    android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = v / 255f;
                    getWindow().setAttributes(lp);
                } catch (SecurityException e) {
                    toast("Saknar behörighet för ljusstyrka");
                }
                return true;
            }
        });
        items.add(new Item("Mediavolym") {
            @Override
            String state() {
                int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                return v + " / " + max + "   ◀ ▶";
            }

            @Override
            void ok() {
                open(Settings.ACTION_SOUND_SETTINGS);
            }

            @Override
            boolean adjust(int dir) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                return true;
            }
        });

        items.add(page("Wifi-nätverk", Settings.ACTION_WIFI_SETTINGS));
        items.add(page("Bluetooth-enheter", Settings.ACTION_BLUETOOTH_SETTINGS));
        items.add(page("Mobilnät", Settings.ACTION_DATA_ROAMING_SETTINGS));
        items.add(page("Aviseringar", "android.settings.NOTIFICATION_SETTINGS"));
        items.add(page("Appar", Settings.ACTION_APPLICATION_SETTINGS));
        items.add(page("Batteri", Intent.ACTION_POWER_USAGE_SUMMARY));
        items.add(page("Ljud och vibration", Settings.ACTION_SOUND_SETTINGS));
        items.add(page("Skärm", Settings.ACTION_DISPLAY_SETTINGS));
        items.add(page("Tillgänglighet", Settings.ACTION_ACCESSIBILITY_SETTINGS));
        items.add(page("Utvecklaralternativ", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        items.add(page("Alla inställningar", Settings.ACTION_SETTINGS));
    }

    private Item page(String title, String action) {
        return new Item(title) {
            @Override
            String state() {
                return null;
            }

            @Override
            void ok() {
                open(action);
            }
        };
    }

    private void open(String action) {
        try {
            startActivity(new Intent(action));
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                toast("Kunde inte öppna sidan");
            }
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void findTorch() {
        try {
            for (String id : camera.getCameraIdList()) {
                Boolean flash = camera.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) {
                    torchId = id;
                    break;
                }
            }
            if (torchId != null) {
                camera.registerTorchCallback(new CameraManager.TorchCallback() {
                    @Override
                    public void onTorchModeChanged(String cameraId, boolean enabled) {
                        if (cameraId.equals(torchId)) {
                            torchOn = enabled;
                            if (adapter != null) adapter.notifyDataSetChanged();
                        }
                    }
                }, null);
            }
        } catch (Exception ignored) {
            torchId = null;
        }
    }

    // ------------------------------------------------------------------ rows

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView title, state;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                title = (TextView) row.getChildAt(0);
                state = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));
                title = new TextView(MainActivity.this);
                title.setTextColor(Color.WHITE);
                title.setTextSize(19);
                state = new TextView(MainActivity.this);
                state.setTextColor(Color.rgb(200, 200, 200));
                state.setTextSize(15);
                row.addView(title);
                row.addView(state);
            }
            Item item = items.get(position);
            title.setText(item.title);
            String s = item.state();
            state.setText(s == null ? "" : s);
            state.setVisibility(s == null ? View.GONE : View.VISIBLE);
            return row;
        }
    }
}
