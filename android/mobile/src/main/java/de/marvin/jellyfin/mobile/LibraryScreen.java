package de.marvin.jellyfin.mobile;

import android.app.AlertDialog;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.marvin.jellyfin.core.JfItem;

import java.util.ArrayList;

/** Bibliothek mit Sortierung, Filter und Genre-Auswahl. */
final class LibraryScreen extends GridScreen {
    private static final String[] SORT_LABEL = {"Name", "Neu", "Bewertung", "Jahr"};
    private static final String[] SORT_KEY = {"SortName", "DateCreated", "CommunityRating", "ProductionYear"};
    private static final String[] FILTER_LABEL = {"Alle", "Ungesehen", "Favoriten"};
    private static final String[] FILTER_KEY = {"", "IsUnplayed", "IsFavorite"};

    private final JfItem lib;
    private int sort = 0, filter = 0;
    private boolean desc;
    private String genre;
    private LinearLayout chips;

    LibraryScreen(MainActivity a, JfItem lib) {
        super(a, lib.name);
        this.lib = lib;
        // Neuste zuerst ist bei "Neu" sinnvoll; gemerkte Einstellung pro App-Start reicht
    }

    private String types() {
        String ct = lib.collectionType == null ? "" : lib.collectionType;
        if (ct.equals("movies")) return "Movie";
        if (ct.equals("tvshows")) return "Series";
        return null;
    }

    @Override
    View header() {
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(a);
        chips.setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 12), Ui.dp(a, 8));
        hs.addView(chips);
        return hs;
    }

    private void chipRow() {
        chips.removeAllViews();
        for (int i = 0; i < SORT_LABEL.length; i++) {
            final int idx = i;
            TextView c = Ui.chip(a, SORT_LABEL[i], sort == i);
            if (sort == i) {
                c.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
                android.graphics.drawable.Drawable d = Icons.drawable(desc ? "down" : "up", android.graphics.Color.WHITE);
                d.setBounds(0, 0, Ui.dp(a, 14), Ui.dp(a, 14));
                c.setCompoundDrawablesRelative(null, null, d, null);
                c.setCompoundDrawablePadding(Ui.dp(a, 4));
            }
            c.setOnClickListener(v -> {
                if (sort == idx) desc = !desc; else { sort = idx; desc = idx != 0; }
                run();
            });
            addChip(c);
        }
        for (int i = 0; i < FILTER_LABEL.length; i++) {
            final int idx = i;
            TextView c = Ui.chip(a, FILTER_LABEL[i], filter == i);
            c.setOnClickListener(v -> { filter = idx; run(); });
            addChip(c);
        }
        TextView g = Ui.chip(a, genre == null ? "Genre" : genre, genre != null);
        g.setOnClickListener(v -> pickGenre());
        addChip(g);
    }

    private void addChip(TextView c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(a, 8);
        chips.addView(c, lp);
    }

    private void pickGenre() {
        a.bg(() -> a.jf.genres(lib.id), list -> {
            if (list == null || list.isEmpty()) return;
            ArrayList<String> items = new ArrayList<>();
            items.add("Alle Genres");
            items.addAll(list);
            new AlertDialog.Builder(a).setTitle("Genre")
                    .setItems(items.toArray(new String[0]), (d, i) -> { genre = i == 0 ? null : items.get(i); run(); })
                    .show();
        });
    }

    private void run() {
        chipRow();
        final String sk = SORT_KEY[sort], order = desc ? "Descending" : "Ascending", fk = FILTER_KEY[filter], gn = genre;
        setPager((jf, s) -> jf.children(lib.id, s, PAGE, sk, order, fk, gn, types()));
    }

    @Override
    View build() {
        View v = super.build();
        run();
        return v;
    }
}
