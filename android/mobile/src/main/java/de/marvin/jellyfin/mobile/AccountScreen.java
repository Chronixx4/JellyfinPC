package de.marvin.jellyfin.mobile;

import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import de.marvin.jellyfin.core.JfItem;

/** Konto und Einstellungen. */
final class AccountScreen extends Screen {
    AccountScreen(MainActivity a) { super(a); }

    private Switch toggle(String label, boolean on, CompoundButton.OnCheckedChangeListener l) {
        Switch s = new Switch(a);
        s.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        s.setTrackTintList(android.content.res.ColorStateList.valueOf(Ui.CARD2));
        s.setText(label);
        s.setTextColor(Ui.TEXT);
        s.setTextSize(16);
        s.setChecked(on);
        s.setMinHeight(Ui.dp(a, 56));
        s.setOnCheckedChangeListener(l);
        return s;
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 20);
        col.setPadding(pad, 0, pad, pad);

        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 4));
        android.widget.ImageView back = Ui.iconButton(a, "back", 44, false);
        back.setOnClickListener(v -> a.pop());
        bar.addView(back);
        bar.addView(Ui.text(a, "Konto", 22, Ui.TEXT, true));
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(bar);

        col.addView(Ui.text(a, a.jf.userName, 22, Ui.TEXT, true));
        col.addView(Ui.text(a, a.jf.server, 14, Ui.MUTED, false));
        col.addView(Ui.space(a, 16));

        col.addView(toggle("Kurze Filme (< 5 Min) ausblenden", JfItem.hideShort, (b, on) -> {
            JfItem.hideShort = on;
            a.prefs.edit().putBoolean("hideShort", on).apply();
        }));
        col.addView(toggle("Immer vom Server umwandeln (spart Daten, bei Wiedergabeproblemen)",
                a.prefs.getBoolean("alwaysTranscode", false),
                (b, on) -> a.prefs.edit().putBoolean("alwaysTranscode", on).apply()));
        col.addView(toggle("Beim Start nach Updates suchen", a.prefs.getBoolean("updateAuto", true),
                (b, on) -> a.prefs.edit().putBoolean("updateAuto", on).apply()));
        col.addView(Ui.space(a, 16));

        Ui.Btn stats = Ui.button(a, "stats", "Statistik", false);
        stats.setOnClickListener(v -> a.push(new StatsScreen(a)));
        col.addView(stats, gap());
        Ui.Btn lists = Ui.button(a, "viewList", "Wiedergabelisten & Sammlungen", false);
        lists.setOnClickListener(v -> a.push(new Lists.ListsScreen(a)));
        col.addView(lists, gap());
        Ui.Btn dl = Ui.button(a, "download", "Downloads", false);
        dl.setOnClickListener(v -> a.push(new DownloadsScreen(a)));
        col.addView(dl, gap());
        Ui.Btn fav = Ui.button(a, "heart", "Merkliste", false);
        fav.setOnClickListener(v -> a.push(new ListScreen(a, "Merkliste", ListScreen.FAVORITES)));
        col.addView(fav, gap());
        Ui.Btn upd = Ui.button(a, "update", "Nach Updates suchen (Version " + Updater.currentVersion(a) + ")", false);
        upd.setOnClickListener(v -> Updater.check(a, true));
        col.addView(upd, gap());
        Ui.Btn pin = Ui.button(a, "lock", "PIN für dieses Profil …", false);
        pin.setOnClickListener(v -> {
            de.marvin.jellyfin.core.Jf.Profile p = a.jf.currentProfile();
            if (p == null) { a.toast("Kein Profil"); return; }
            android.widget.EditText e = new android.widget.EditText(a);
            e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            e.setHint("PIN (leer = entfernen)");
            new android.app.AlertDialog.Builder(a).setTitle("PIN für " + p.name).setView(e)
                    .setPositiveButton("Speichern", (d, w) -> { a.jf.setPin(p, e.getText().toString().trim()); a.toast("PIN gespeichert"); })
                    .setNegativeButton("Abbrechen", null).show();
        });
        col.addView(pin, gap());
        Ui.Btn sw = Ui.button(a, "person", "Profil wechseln", false);
        sw.setOnClickListener(v -> { a.reset(new LoginScreen(a)); });
        col.addView(sw, gap());
        Ui.Btn out = Ui.button(a, "logout", "Abmelden", false);
        out.setOnClickListener(v -> { a.jf.logout(); a.reset(new LoginScreen(a)); });
        col.addView(out, gap());

        ScrollView sv = new ScrollView(a);
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(a, 10);
        return lp;
    }
}
