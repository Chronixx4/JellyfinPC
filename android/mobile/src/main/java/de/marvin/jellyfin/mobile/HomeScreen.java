package de.marvin.jellyfin.mobile;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import de.marvin.jellyfin.core.JfItem;

import java.util.ArrayList;

/** Startseite: Weiter schauen, Als Naechstes, Bibliotheken und Neuzugaenge je Bibliothek. */
final class HomeScreen extends Screen {
    private LinearLayout content;
    private SwipeRefreshLayout swipe;
    private int generation;

    HomeScreen(MainActivity a) { super(a); }

    @Override
    View build() {
        LinearLayout page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 4));
        TextView title = Ui.text(a, "Jellyfin", 24, Ui.ACCENT, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(iconButton("🔍", () -> a.push(new SearchScreen(a))));
        bar.addView(iconButton("⚙", () -> a.push(new AccountScreen(a))));
        page.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        swipe = new SwipeRefreshLayout(a);
        swipe.setOnRefreshListener(this::load);
        ScrollView sv = new ScrollView(a);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, Ui.dp(a, 24));
        sv.addView(content, new ScrollView.LayoutParams(-1, -2));
        swipe.addView(sv);
        page.addView(swipe, new LinearLayout.LayoutParams(-1, 0, 1));
        load();
        return page;
    }

    private TextView iconButton(String s, Runnable r) {
        TextView t = Ui.text(a, s, 22, Ui.TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(a, 48));
        t.setMinHeight(Ui.dp(a, 48));
        t.setOnClickListener(v -> r.run());
        return t;
    }

    @Override void refresh() { if (view != null) load(); }

    private TextView heading(String s, Runnable more) {
        TextView t = Ui.text(a, s, 18, Ui.TEXT, true);
        t.setPadding(Ui.dp(a, 16), Ui.dp(a, 18), Ui.dp(a, 16), Ui.dp(a, 6));
        if (more != null) { t.setText(s + "  ›"); t.setOnClickListener(v -> more.run()); }
        return t;
    }

    private void addRow(String head, ArrayList<JfItem> items, boolean landscape, Runnable more) {
        if (items == null || items.isEmpty()) return;
        content.addView(heading(head, more));
        RecyclerView rv = new RecyclerView(a);
        rv.setLayoutManager(new LinearLayoutManager(a, LinearLayoutManager.HORIZONTAL, false));
        rv.setPadding(Ui.dp(a, 11), 0, Ui.dp(a, 11), 0);
        rv.setClipToPadding(false);
        Cards.Adapter ad = new Cards.Adapter(a, landscape, landscape ? 230 : 120);
        ad.onChanged = this::load;
        ad.set(items);
        rv.setAdapter(ad);
        content.addView(rv, new LinearLayout.LayoutParams(-1, -2));
    }

    private void load() {
        final int gen = ++generation;
        a.bg(() -> {
            Object[] r = new Object[4];
            r[0] = a.jf.resume();
            r[1] = a.jf.nextUp();
            ArrayList<JfItem> views = a.jf.views();
            r[2] = views;
            ArrayList<ArrayList<JfItem>> latest = new ArrayList<>();
            for (JfItem v : views) {
                try { latest.add(a.jf.latest(v.id)); } catch (Exception e) { latest.add(new ArrayList<>()); }
            }
            r[3] = latest;
            return r;
        }, r -> {
            swipe.setRefreshing(false);
            if (r == null || gen != generation) return;
            content.removeAllViews();
            @SuppressWarnings("unchecked") ArrayList<JfItem> resume = (ArrayList<JfItem>) r[0];
            @SuppressWarnings("unchecked") ArrayList<JfItem> next = (ArrayList<JfItem>) r[1];
            @SuppressWarnings("unchecked") ArrayList<JfItem> views = (ArrayList<JfItem>) r[2];
            @SuppressWarnings("unchecked") ArrayList<ArrayList<JfItem>> latest = (ArrayList<ArrayList<JfItem>>) r[3];

            addRow("Weiter schauen", resume, true, null);
            addRow("Als Nächstes", next, true, null);
            addRow("Bibliotheken", views, true, null);

            LinearLayout quick = new LinearLayout(a);
            quick.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), 0);
            TextView fav = Ui.button(a, "♥ Merkliste", false);
            fav.setOnClickListener(v -> a.push(new ListScreen(a, "Merkliste", ListScreen.FAVORITES)));
            TextView rnd = Ui.button(a, "🎲 Zufallsfilm", false);
            rnd.setOnClickListener(v -> a.bg(() -> a.jf.randomMovie(), it -> { if (it != null) a.openItem(it); }));
            LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1);
            l1.rightMargin = Ui.dp(a, 6);
            LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, -2, 1);
            l2.leftMargin = Ui.dp(a, 6);
            quick.addView(fav, l1);
            quick.addView(rnd, l2);
            content.addView(quick);

            for (int i = 0; i < views.size(); i++) {
                JfItem v = views.get(i);
                addRow("Neu in " + v.name, latest.get(i), false, () -> a.openItem(v));
            }
            if (resume.isEmpty() && views.isEmpty()) {
                TextView t = Ui.text(a, "Keine Inhalte gefunden.", 15, Ui.MUTED, false);
                t.setPadding(Ui.dp(a, 16), Ui.dp(a, 24), 0, 0);
                content.addView(t);
            }
        });
    }
}
