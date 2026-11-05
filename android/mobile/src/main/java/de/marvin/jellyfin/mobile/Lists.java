package de.marvin.jellyfin.mobile;

import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;

import de.marvin.jellyfin.core.JfItem;

import java.util.ArrayList;
import java.util.function.Consumer;

/** Wiedergabelisten und Sammlungen: anzeigen, neu anlegen, Titel hinzufuegen (wie in der PC-App). */
final class Lists {
    private Lists() {}

    static void askName(MainActivity a, String title, Consumer<String> done) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setSingleLine(true);
        e.setHint("Name");
        new AlertDialog.Builder(a).setTitle(title).setView(e)
                .setPositiveButton("Anlegen", (d, w) -> { String s = e.getText().toString().trim(); if (!s.isEmpty()) done.accept(s); })
                .setNegativeButton("Abbrechen", null).show();
    }

    /** Dialog "Zur Wiedergabeliste / Sammlung hinzufuegen". */
    static void addTo(MainActivity a, JfItem it, boolean playlist) {
        a.bg(() -> playlist ? a.jf.playlists() : a.jf.boxsets(), lists -> {
            if (lists == null) return;
            ArrayList<String> names = new ArrayList<>();
            names.add(playlist ? "Neue Wiedergabeliste …" : "Neue Sammlung …");
            for (JfItem l : lists) names.add(l.name);
            new AlertDialog.Builder(a).setTitle(playlist ? "Zur Wiedergabeliste" : "Zur Sammlung")
                    .setItems(names.toArray(new String[0]), (d, i) -> {
                        if (i == 0) {
                            askName(a, playlist ? "Neue Wiedergabeliste" : "Neue Sammlung", n -> a.bg(() -> {
                                if (playlist) a.jf.createPlaylist(n, it.id); else a.jf.createBoxset(n, it.id);
                                return true;
                            }, ok -> { if (ok != null) a.toast("Hinzugefügt"); }));
                        } else {
                            JfItem l = lists.get(i - 1);
                            a.bg(() -> {
                                if (playlist) a.jf.addToPlaylist(l.id, it.id); else a.jf.addToBoxset(l.id, it.id);
                                return true;
                            }, ok -> { if (ok != null) a.toast("Zu „" + l.name + "“ hinzugefügt"); });
                        }
                    }).show();
        });
    }

    /** Seite "Listen": Wiedergabelisten und Sammlungen als Raster, oben umschaltbar. */
    static final class ListsScreen extends GridScreen {
        private boolean playlists = true;
        private android.widget.LinearLayout chips;

        ListsScreen(MainActivity a) { super(a, "Listen"); }

        @Override
        android.view.View header() {
            chips = new android.widget.LinearLayout(a);
            chips.setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 12), Ui.dp(a, 8));
            return chips;
        }

        private void run() {
            chips.removeAllViews();
            String[] labels = {"Wiedergabelisten", "Sammlungen"};
            for (int i = 0; i < 2; i++) {
                final boolean pl = i == 0;
                android.widget.TextView c = Ui.chip(a, labels[i], pl == playlists);
                c.setOnClickListener(v -> { playlists = pl; run(); });
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = Ui.dp(a, 8);
                chips.addView(c, lp);
            }
            Ui.Btn add = Ui.button(a, "add", "Neu", false);
            add.setMinimumHeight(Ui.dp(a, 40));
            add.setOnClickListener(v -> askName(a, playlists ? "Neue Wiedergabeliste" : "Neue Sammlung", n -> a.bg(() -> {
                if (playlists) a.jf.createPlaylist(n, null); else a.jf.createBoxset(n, null);
                return true;
            }, ok -> { if (ok != null) run(); })));
            chips.addView(add);
            final boolean pl = playlists;
            setPager((jf, s) -> s > 0 ? new ArrayList<>() : pl ? jf.playlists() : jf.boxsets());
        }

        @Override
        android.view.View build() {
            android.view.View v = super.build();
            run();
            return v;
        }
    }
}
