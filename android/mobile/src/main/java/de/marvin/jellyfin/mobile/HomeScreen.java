package de.marvin.jellyfin.mobile;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import de.marvin.jellyfin.core.JfItem;

import java.util.ArrayList;

/** Startseite im Stil der PC-App: Kopfleiste mit Reitern, grosses Banner, danach die Reihen. */
final class HomeScreen extends Screen {
    private LinearLayout content, nav;
    private SwipeRefreshLayout swipe;
    private int generation;
    private HeroPreview preview;
    private final android.os.Handler heroHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private java.util.List<JfItem> heroItems = new ArrayList<>();
    private int heroIdx;
    private FrameLayout heroView;

    HomeScreen(MainActivity a) { super(a); }

    @Override
    View build() {
        LinearLayout page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);

        // Kopfleiste: Marke, Suche, Einstellungen
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 16), Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 2));
        bar.addView(Ui.brand(a, "Jellyfin", 24), new LinearLayout.LayoutParams(0, -2, 1));
        ImageView search = Ui.iconButton(a, "search", 44, true);
        search.setOnClickListener(v -> a.push(new SearchScreen(a)));
        ImageView settings = Ui.iconButton(a, "settings", 44, true);
        settings.setOnClickListener(v -> a.push(new AccountScreen(a)));
        bar.addView(search);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44));
        sl.leftMargin = Ui.dp(a, 8);
        sl.rightMargin = Ui.dp(a, 8);
        bar.addView(settings, sl);
        page.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        // Reiter wie in der PC-Kopfleiste: Start + Bibliotheken
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        nav = new LinearLayout(a);
        nav.setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 12), Ui.dp(a, 8));
        hs.addView(nav);
        page.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        swipe = new SwipeRefreshLayout(a);
        swipe.setProgressBackgroundColorSchemeColor(Ui.CARD2);
        swipe.setColorSchemeColors(Ui.ACCENT);
        swipe.setOnRefreshListener(this::load);
        ScrollView sv = new ScrollView(a);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, Ui.dp(a, 28));
        sv.addView(content, new ScrollView.LayoutParams(-1, -2));
        swipe.addView(sv);
        page.addView(swipe, new LinearLayout.LayoutParams(-1, 0, 1));
        load();
        return page;
    }

    @Override void refresh() { if (view != null) load(); }

    private TextView heading(String s, Runnable more) {
        TextView t = Ui.text(a, s, 20, Ui.TEXT, true);
        t.setPadding(Ui.dp(a, 16), Ui.dp(a, 20), Ui.dp(a, 16), Ui.dp(a, 6));
        if (more != null) {
            t.setCompoundDrawablesRelative(null, null, arrow(), null);
            t.setCompoundDrawablePadding(Ui.dp(a, 4));
            t.setOnClickListener(v -> more.run());
        }
        return t;
    }

    private android.graphics.drawable.Drawable arrow() {
        android.graphics.drawable.Drawable d = Icons.drawable("forward", Ui.MUTED);
        d.setBounds(0, 0, Ui.dp(a, 16), Ui.dp(a, 16));
        return d;
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

    /** Grosses Banner mit Hintergrundbild, Titel, Kurzinfos und Knoepfen (wie das Banner der PC-Startseite). */
    private View hero(JfItem it) {
        FrameLayout f = new FrameLayout(a);
        ImageView bd = new ImageView(a);
        bd.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String url = a.jf.backdrop(it);
        if (url == null) url = a.jf.landscape(it);
        Ui.load(bd, url);
        f.addView(bd, new FrameLayout.LayoutParams(-1, -1));
        if (preview != null) { preview.stop(); preview = null; }
        if (a.prefs.getBoolean("heroVideo", true) && it.isPlayable() && it.runTicks > 0)
            preview = new HeroPreview(a, f, 1, a.jf, it, 2000);
        View fade = new View(a);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x330A0D12, 0x990A0D12, Ui.BG}));
        f.addView(fade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), Ui.dp(a, 14));
        boolean ep = "Episode".equals(it.type);
        TextView title = Ui.text(a, ep && !it.seriesName.isEmpty() ? it.seriesName : it.name, 28, Ui.TEXT, true);
        title.setMaxLines(2);
        title.setShadowLayer(8, 0, 2, 0xCC000000);
        col.addView(title);
        if (ep) col.addView(Ui.text(a, it.title(), 15, Ui.MUTED, false));

        LinearLayout meta = new LinearLayout(a);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(0, Ui.dp(a, 8), 0, 0);
        if (it.year > 0) addMeta(meta, Ui.badge(a, String.valueOf(it.year), null));
        if (it.rating > 0) addMeta(meta, Ui.badge(a, String.format(java.util.Locale.GERMANY, "%.1f", it.rating), "star"));
        if (!it.officialRating.isEmpty()) addMeta(meta, Ui.badge(a, it.officialRating, null));
        if (it.runTicks > 0) addMeta(meta, Ui.badge(a, it.runtime(), null));
        col.addView(meta);

        if (!it.overview.isEmpty()) {
            TextView ov = Ui.text(a, it.overview, 14, 0xFFD6DEE6, false);
            ov.setMaxLines(2);
            ov.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ov.setPadding(0, Ui.dp(a, 8), 0, 0);
            col.addView(ov);
        }

        LinearLayout btns = new LinearLayout(a);
        btns.setPadding(0, Ui.dp(a, 12), 0, 0);
        Ui.Btn play = Ui.button(a, "play", it.resumeTicks > 0 ? "Fortsetzen" : "Abspielen", true);
        play.setOnClickListener(v -> a.play(it, it.resumeTicks));
        Ui.Btn info = Ui.button(a, "info", "Info", false);
        info.setOnClickListener(v -> a.openItem(it));
        btns.addView(play);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-2, -2);
        il.leftMargin = Ui.dp(a, 10);
        btns.addView(info, il);
        col.addView(btns);

        f.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return f;
    }

    private void addMeta(LinearLayout row, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(a, 6);
        row.addView(v, lp);
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

            // Reiter
            nav.removeAllViews();
            TextView start = Ui.chip(a, "Start", true);
            nav.addView(start, navLp());
            for (JfItem v : views) {
                TextView c = Ui.chip(a, v.name, false);
                c.setOnClickListener(x -> a.openItem(v));
                nav.addView(c, navLp());
            }
            TextView lists = Ui.chip(a, "Listen", false);
            lists.setOnClickListener(x -> a.push(new Lists.ListsScreen(a)));
            nav.addView(lists, navLp());
            TextView fav = Ui.chip(a, "Merkliste", false);
            fav.setOnClickListener(x -> a.push(new ListScreen(a, "Merkliste", ListScreen.FAVORITES)));
            nav.addView(fav, navLp());

            // Banner: erster Titel aus "Weiter schauen", sonst "Als Naechstes", sonst Neuzugang
            heroHandler.removeCallbacksAndMessages(null);
            heroItems = new ArrayList<>();
            for (JfItem i : resume) if (heroItems.size() < 5 && a.jf.backdrop(i) != null) heroItems.add(i);
            for (JfItem i : next) if (heroItems.size() < 5 && a.jf.backdrop(i) != null && !has(i)) heroItems.add(i);
            for (ArrayList<JfItem> l : latest) for (JfItem i : l) if (heroItems.size() < 5 && a.jf.backdrop(i) != null && !has(i)) heroItems.add(i);
            if (heroItems.isEmpty()) {
                JfItem any = !resume.isEmpty() ? resume.get(0) : !next.isEmpty() ? next.get(0) : null;
                if (any != null) heroItems.add(any);
            }
            heroIdx = 0;
            heroView = null;
            if (!heroItems.isEmpty()) {
                int h = Math.min(Ui.dp(a, 360), (int) (a.getResources().getDisplayMetrics().widthPixels * 1.0f));
                heroView = (FrameLayout) hero(heroItems.get(0));
                content.addView(heroView, new LinearLayout.LayoutParams(-1, h));
                if (heroItems.size() > 1) scheduleHero();
            }

            addRow("Weiter schauen", resume, true, null);
            addRow("Als Nächstes", next, true, null);
            addRow("Bibliotheken", views, true, null);

            Ui.Btn rnd = Ui.button(a, "shuffle", "Überrasch mich", false);
            rnd.setOnClickListener(v -> a.bg(() -> a.jf.randomMovie(), it -> { if (it != null) a.openItem(it); }));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
            rl.setMargins(Ui.dp(a, 16), Ui.dp(a, 18), 0, 0);
            content.addView(rnd, rl);

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

    private boolean has(JfItem i) {
        for (JfItem h : heroItems) if (h.id.equals(i.id)) return true;
        return false;
    }

    /** Banner wechselt wie in der PC-App alle paar Sekunden zum naechsten Titel. */
    private void scheduleHero() {
        heroHandler.postDelayed(() -> {
            if (heroView == null || heroView.getParent() != content || view.getVisibility() != View.VISIBLE) return;
            heroIdx = (heroIdx + 1) % heroItems.size();
            int pos = content.indexOfChild(heroView);
            int h = heroView.getLayoutParams().height;
            content.removeViewAt(pos);
            heroView = (FrameLayout) hero(heroItems.get(heroIdx));
            content.addView(heroView, pos, new LinearLayout.LayoutParams(-1, h));
            scheduleHero();
        }, 16000);
    }

    @Override void onHidden() {
        heroHandler.removeCallbacksAndMessages(null);
        if (preview != null) { preview.stop(); preview = null; }
    }

    private LinearLayout.LayoutParams navLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(a, 8);
        return lp;
    }
}
