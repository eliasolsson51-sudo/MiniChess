package app.minimouse;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/** Full-screen list of the keys. */
public class KeysActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Knappar");
        int pad = (int) (12 * getResources().getDisplayMetrics().density);

        TextView t = new TextView(this);
        t.setTextSize(15);
        t.setPadding(pad, pad, pad, pad);
        t.setFocusable(true);
        t.setText("Håll *: musen på/av\n\n"
                + "D-pad: ett litet steg\n"
                + "Håll D-pad: glid (bromsar lite över knappar)\n"
                + "Mot över-/nederkanten: scrolla\n\n"
                + "OK: tryck (håll = långt tryck)\n"
                + "5: långt tryck\n"
                + "0: dubbeltryck\n\n"
                + "1: dra på/av (markera text)\n"
                + "7: klistra in\n\n"
                + "2 / 8: föregående / nästa inlägg\n"
                + "4 / 6: scrolla vänster / höger\n"
                + "Håll 4 / 6: svep in från kanten\n"
                + "3 / 9: zooma in / ut (håll = fortsätt)\n\n"
                + "Bakåt: tillbaka i appen\n\n"
                + "Musen pausar sig själv på låsskärmen, medan du skriver och i "
                + "pausade appar. Den göms medan sidan scrollar och efter några "
                + "sekunder utan rörelse.");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(t);
        setContentView(scroll);
    }
}
