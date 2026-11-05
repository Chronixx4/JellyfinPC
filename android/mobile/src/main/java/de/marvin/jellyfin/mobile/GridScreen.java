package de.marvin.jellyfin.mobile;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import de.marvin.jellyfin.core.Jf;
import de.marvin.jellyfin.core.JfItem;

import java.util.ArrayList;

/** Raster mit Titelzeile, Zurueck-Knopf und endlosem Nachladen. */
abstract class GridScreen extends Screen {
    static final int PAGE = 60;

    interface Pager { ArrayList<JfItem> load(Jf jf, int start) throws Exception; }

    protected final String title;
    protected LinearLayout page;
    protected Cards.Adapter adapter;
    private TextView empty;
    private boolean loading, more = true;
    private int start, gen;
    private Pager pager;

    GridScreen(MainActivity a, String title) { super(a); this.title = title; }

    /** Zusatzbedienelemente unter der Titelzeile (Sortierung, Filter ...) oder null. */
    View header() { return null; }

    @Override
    View build() {
        page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 4));
        android.widget.ImageView back = Ui.iconButton(a, "back", 44, false);
        back.setOnClickListener(v -> a.pop());
        bar.addView(back);
        TextView t = Ui.text(a, title, 22, Ui.TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        View h = header();
        if (h != null) page.addView(h, new LinearLayout.LayoutParams(-1, -2));

        int wdp = (int) (a.getResources().getDisplayMetrics().widthPixels / a.getResources().getDisplayMetrics().density);
        int cols = Math.max(3, wdp / 125);
        RecyclerView rv = new RecyclerView(a);
        GridLayoutManager lm = new GridLayoutManager(a, cols);
        rv.setLayoutManager(lm);
        rv.setPadding(Ui.dp(a, 7), 0, Ui.dp(a, 7), Ui.dp(a, 16));
        rv.setClipToPadding(false);
        adapter = new Cards.Adapter(a, false, 0);
        adapter.onChanged = this::reload;
        rv.setAdapter(adapter);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView r, int dx, int dy) {
                if (dy > 0 && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 12) loadMore();
            }
        });
        page.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        empty = Ui.text(a, "Nichts gefunden.", 15, Ui.MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        page.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        return page;
    }

    /** Neue Abfrage starten (z. B. nach Sortier-/Filterwechsel). */
    protected void setPager(Pager p) {
        pager = p;
        reload();
    }

    protected void reload() {
        gen++;
        start = 0;
        more = true;
        loading = false;
        adapter.set(null);
        loadMore();
    }

    private void loadMore() {
        if (loading || !more || pager == null) return;
        loading = true;
        final int g = gen, s = start;
        a.bg(() -> {
            ArrayList<JfItem> l = pager.load(a.jf, s);
            return new Object[]{l, a.jf.lastRaw};
        }, r -> {
            if (g != gen) return;
            loading = false;
            if (r == null) { more = false; return; }
            @SuppressWarnings("unchecked") ArrayList<JfItem> l = (ArrayList<JfItem>) r[0];
            int raw = (Integer) r[1];
            adapter.add(l);
            start += Math.max(raw, l.size());
            more = raw >= PAGE;
            empty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
            if (more && adapter.getItemCount() < 24) loadMore();
        });
    }

    @Override void refresh() { if (view != null && pager != null && adapter.getItemCount() > 0) { /* Karten behalten Zustand bis zum Neuladen */ adapter.notifyDataSetChanged(); } }
}
