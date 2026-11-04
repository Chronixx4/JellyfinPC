package de.marvin.jellyfin.mobile;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import de.marvin.jellyfin.core.JfItem;

import java.util.ArrayList;

/** Detailseite fuer Filme, Serien und Folgen. */
final class DetailScreen extends Screen {
    private JfItem item;
    private LinearLayout col;
    private LinearLayout episodes;
    private String seasonId;
    private ArrayList<JfItem> seasons = new ArrayList<>();
    private JfItem nextUp;

    DetailScreen(MainActivity a, JfItem it) {
        super(a);
        this.item = it;
    }

    @Override
    View build() {
        FrameLayout frame = new FrameLayout(a);
        ScrollView sv = new ScrollView(a);
        col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, Ui.dp(a, 32));
        sv.addView(col, new ScrollView.LayoutParams(-1, -2));
        frame.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        TextView back = Ui.text(a, "←", 24, Ui.TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.round(0x99000000, 24, a));
        back.setOnClickListener(v -> a.pop());
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44), Gravity.TOP | Gravity.START);
        bl.setMargins(Ui.dp(a, 10), Ui.dp(a, 10), 0, 0);
        frame.addView(back, bl);

        render();
        a.bg(() -> {
            JfItem full = a.jf.item(item.id);
            JfItem nu = null;
            ArrayList<JfItem> sea = new ArrayList<>(), sim = new ArrayList<>();
            if ("Series".equals(full.type)) {
                sea = a.jf.seasons(full.id);
                nu = a.jf.nextUpFor(full.id);
            }
            try { sim = a.jf.similar(full.id); } catch (Exception ignored) { }
            return new Object[]{full, sea, nu, sim};
        }, r -> {
            if (r == null) return;
            item = (JfItem) r[0];
            @SuppressWarnings("unchecked") ArrayList<JfItem> sea = (ArrayList<JfItem>) r[1];
            seasons = sea;
            nextUp = (JfItem) r[2];
            @SuppressWarnings("unchecked") ArrayList<JfItem> sim = (ArrayList<JfItem>) r[3];
            if (!seasons.isEmpty()) {
                // gleich die Staffel der naechsten Folge (oder die erste) zeigen
                seasonId = nextUp != null && nextUp.seasonId != null ? nextUp.seasonId : seasons.get(0).id;
            }
            render();
            if (seasonId != null) showSeason(seasonId);
            addSimilar(sim);
        });
        return frame;
    }

    @Override
    void refresh() {
        if (view == null) return;
        a.bg(() -> {
            JfItem full = a.jf.item(item.id);
            JfItem nu = "Series".equals(full.type) ? a.jf.nextUpFor(full.id) : null;
            return new Object[]{full, nu};
        }, r -> {
            if (r == null) return;
            item = (JfItem) r[0];
            nextUp = (JfItem) r[1];
            render();
            if (!seasons.isEmpty()) showSeason(seasonId != null ? seasonId : seasons.get(0).id);
        }, true);
    }

    private TextView pill(String s) {
        TextView t = Ui.text(a, s, 12, Ui.TEXT, true);
        t.setPadding(Ui.dp(a, 8), Ui.dp(a, 3), Ui.dp(a, 8), Ui.dp(a, 3));
        t.setBackground(Ui.round(Ui.CARD2, 6, a));
        return t;
    }

    private void render() {
        col.removeAllViews();
        // Hintergrundbild mit Verlauf nach unten
        FrameLayout hero = new FrameLayout(a);
        ImageView bd = new ImageView(a);
        bd.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String url = a.jf.backdrop(item);
        if (url == null) url = a.jf.landscape(item);
        Ui.load(bd, url);
        hero.addView(bd, new FrameLayout.LayoutParams(-1, -1));
        View fade = new View(a);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, Ui.BG}));
        hero.addView(fade, new FrameLayout.LayoutParams(-1, -1));
        col.addView(hero, new LinearLayout.LayoutParams(-1, Ui.dp(a, 230)));

        int pad = Ui.dp(a, 16);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, 0, pad, 0);
        col.addView(body, new LinearLayout.LayoutParams(-1, -2));

        String head = "Episode".equals(item.type) && !item.seriesName.isEmpty() ? item.seriesName : item.name;
        TextView title = Ui.text(a, head, 26, Ui.TEXT, true);
        body.addView(title);
        if ("Episode".equals(item.type)) body.addView(Ui.text(a, item.title(), 15, Ui.MUTED, false));

        StringBuilder meta = new StringBuilder();
        if (item.year > 0) meta.append(item.year);
        if (!item.officialRating.isEmpty()) meta.append(meta.length() > 0 ? "  ·  " : "").append(item.officialRating);
        if (item.runTicks > 0) meta.append(meta.length() > 0 ? "  ·  " : "").append(item.runtime());
        if (item.rating > 0) meta.append(meta.length() > 0 ? "  ·  " : "").append("★ ").append(String.format(java.util.Locale.GERMANY, "%.1f", item.rating));
        if (meta.length() > 0) {
            TextView m = Ui.text(a, meta.toString(), 14, Ui.MUTED, false);
            m.setPadding(0, Ui.dp(a, 4), 0, 0);
            body.addView(m);
        }
        if (!item.genres.isEmpty()) {
            TextView g = Ui.text(a, item.genres, 14, Ui.MUTED, false);
            g.setPadding(0, Ui.dp(a, 2), 0, 0);
            body.addView(g);
        }
        ArrayList<String> badges = item.badges();
        if (!badges.isEmpty()) {
            LinearLayout row = new LinearLayout(a);
            row.setPadding(0, Ui.dp(a, 10), 0, 0);
            for (String b : badges) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = Ui.dp(a, 6);
                row.addView(pill(b), lp);
            }
            body.addView(row);
        }

        // Knoepfe
        JfItem target = "Series".equals(item.type) ? nextUp : item;
        LinearLayout btns = new LinearLayout(a);
        btns.setPadding(0, Ui.dp(a, 16), 0, 0);
        if (item.isPlayable() || target != null) {
            JfItem t = target != null ? target : item;
            boolean resume = t.resumeTicks > 0;
            String label = resume ? "▶ Fortsetzen" : "▶ Abspielen";
            if ("Series".equals(item.type)) label += "  " + t.title();
            TextView play = Ui.button(a, label, true);
            play.setSingleLine(true);
            play.setEllipsize(android.text.TextUtils.TruncateAt.END);
            play.setOnClickListener(v -> a.play(t, t.resumeTicks));
            btns.addView(play, new LinearLayout.LayoutParams(0, -2, 1));
            if (resume) {
                TextView again = Ui.button(a, "⟲", false);
                again.setOnClickListener(v -> a.play(t, 0));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 52), -2);
                lp.leftMargin = Ui.dp(a, 8);
                btns.addView(again, lp);
            }
        } else if ("Series".equals(item.type)) {
            // noch keine Folge geladen
        }
        TextView fav = Ui.button(a, item.favorite ? "♥" : "♡", false);
        fav.setOnClickListener(v -> a.bg(() -> { a.jf.setFavorite(item.id, !item.favorite); return true; }, ok -> { if (ok != null) refresh(); }));
        TextView seen = Ui.button(a, item.played ? "✓" : "○", false);
        seen.setOnClickListener(v -> a.bg(() -> { a.jf.setPlayed(item.id, !item.played); return true; }, ok -> { if (ok != null) refresh(); }));
        for (TextView t : new TextView[]{fav, seen}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 52), -2);
            lp.leftMargin = Ui.dp(a, 8);
            btns.addView(t, lp);
        }
        body.addView(btns);

        if (!item.tagline.isEmpty()) {
            TextView tl = Ui.text(a, item.tagline, 15, Ui.ACCENT, false);
            tl.setTypeface(null, android.graphics.Typeface.ITALIC);
            tl.setPadding(0, Ui.dp(a, 16), 0, 0);
            body.addView(tl);
        }
        if (!item.overview.isEmpty()) {
            TextView ov = Ui.text(a, item.overview, 15, 0xFFDDDDDD, false);
            ov.setLineSpacing(0, 1.15f);
            ov.setMaxLines(5);
            ov.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ov.setPadding(0, Ui.dp(a, 12), 0, 0);
            ov.setOnClickListener(v -> {
                boolean collapsed = ov.getMaxLines() == 5;
                ov.setMaxLines(collapsed ? 100 : 5);
            });
            body.addView(ov);
        }
        if (!item.audioLangs.isEmpty() || !item.subLangs.isEmpty()) {
            TextView l = Ui.text(a, (item.audioLangs.isEmpty() ? "" : "Ton: " + item.audioLangs)
                    + (!item.audioLangs.isEmpty() && !item.subLangs.isEmpty() ? "\n" : "")
                    + (item.subLangs.isEmpty() ? "" : "Untertitel: " + item.subLangs), 13, Ui.MUTED, false);
            l.setPadding(0, Ui.dp(a, 12), 0, 0);
            body.addView(l);
        }

        // Staffeln + Folgen (werden spaeter gefuellt)
        if (!seasons.isEmpty()) {
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(a);
            chips.setPadding(0, Ui.dp(a, 20), 0, Ui.dp(a, 8));
            chipViews.clear();
            for (JfItem s : seasons) {
                TextView c = Ui.chip(a, s.name, s.id.equals(seasonId));
                c.setTag(s.id);
                chipViews.add(c);
                c.setOnClickListener(v -> showSeason(s.id));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = Ui.dp(a, 8);
                chips.addView(c, lp);
            }
            hs.addView(chips);
            body.addView(hs);
            episodes = new LinearLayout(a);
            episodes.setOrientation(LinearLayout.VERTICAL);
            body.addView(episodes, new LinearLayout.LayoutParams(-1, -2));
        } else {
            episodes = null;
        }

        // Besetzung
        if (!item.people.isEmpty()) {
            TextView h = Ui.text(a, "Besetzung", 18, Ui.TEXT, true);
            h.setPadding(0, Ui.dp(a, 22), 0, Ui.dp(a, 8));
            body.addView(h);
            LinearLayout row = new LinearLayout(a);
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            int n = 0;
            for (JfItem.Person p : item.people) {
                if (n++ >= 20) break;
                LinearLayout c = new LinearLayout(a);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setGravity(Gravity.CENTER_HORIZONTAL);
                ImageView im = new ImageView(a);
                im.setScaleType(ImageView.ScaleType.CENTER_CROP);
                im.setBackground(Ui.round(Ui.CARD, 40, a));
                im.setClipToOutline(true);
                Ui.load(im, a.jf.personImage(p));
                c.addView(im, new LinearLayout.LayoutParams(Ui.dp(a, 80), Ui.dp(a, 80)));
                TextView nm = Ui.text(a, p.name, 12, Ui.TEXT, true);
                nm.setGravity(Gravity.CENTER);
                nm.setMaxLines(2);
                nm.setPadding(0, Ui.dp(a, 4), 0, 0);
                c.addView(nm, new LinearLayout.LayoutParams(Ui.dp(a, 90), -2));
                if (!p.role.isEmpty()) {
                    TextView ro = Ui.text(a, p.role, 11, Ui.MUTED, false);
                    ro.setGravity(Gravity.CENTER);
                    ro.setMaxLines(1);
                    ro.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    c.addView(ro, new LinearLayout.LayoutParams(Ui.dp(a, 90), -2));
                }
                c.setOnClickListener(v -> a.push(new ListScreen(a, p.name,
                        (jf, s) -> jf.byPerson(p.id, s, PAGE_SIZE))));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = Ui.dp(a, 8);
                row.addView(c, lp);
            }
            hs.addView(row);
            body.addView(hs);
        }
        similarHolder = new LinearLayout(a);
        similarHolder.setOrientation(LinearLayout.VERTICAL);
        body.addView(similarHolder);
        if (lastSimilar != null) addSimilar(lastSimilar);
    }

    private static final int PAGE_SIZE = GridScreen.PAGE;
    private final ArrayList<TextView> chipViews = new ArrayList<>();
    private LinearLayout similarHolder;
    private ArrayList<JfItem> lastSimilar;

    private void addSimilar(ArrayList<JfItem> sim) {
        lastSimilar = sim;
        if (similarHolder == null || sim == null || sim.isEmpty()) return;
        similarHolder.removeAllViews();
        TextView h = Ui.text(a, "Ähnliche Titel", 18, Ui.TEXT, true);
        h.setPadding(0, Ui.dp(a, 22), 0, Ui.dp(a, 4));
        similarHolder.addView(h);
        RecyclerView rv = new RecyclerView(a);
        rv.setLayoutManager(new LinearLayoutManager(a, LinearLayoutManager.HORIZONTAL, false));
        Cards.Adapter ad = new Cards.Adapter(a, false, 120);
        ad.set(sim);
        rv.setAdapter(ad);
        similarHolder.addView(rv, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showSeason(String sid) {
        seasonId = sid;
        for (TextView c : chipViews) {
            boolean sel = sid.equals(c.getTag());
            c.setBackground(Ui.round(sel ? Ui.ACCENT : Ui.CARD2, 20, a));
            c.setTextColor(sel ? android.graphics.Color.BLACK : Ui.TEXT);
        }
        if (episodes == null) return;
        a.bg(() -> a.jf.episodes(item.id, sid), list -> {
            if (list == null || episodes == null || !sid.equals(seasonId)) return;
            episodes.removeAllViews();
            for (JfItem ep : list) episodes.addView(episodeRow(ep));
        });
    }

    private View episodeRow(JfItem ep) {
        LinearLayout row = new LinearLayout(a);
        row.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8));
        FrameLayout th = new FrameLayout(a);
        th.setBackground(Ui.round(Ui.CARD, 8, a));
        th.setClipToOutline(true);
        ImageView im = new ImageView(a);
        im.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.load(im, a.jf.landscape(ep));
        th.addView(im, new FrameLayout.LayoutParams(-1, -1));
        if (ep.progress() > 0) {
            ProgressBar pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(1000);
            pb.setProgress((int) (ep.progress() * 1000));
            pb.getProgressDrawable().setTint(Ui.ACCENT);
            th.addView(pb, new FrameLayout.LayoutParams(-1, Ui.dp(a, 4), Gravity.BOTTOM));
        }
        row.addView(th, new LinearLayout.LayoutParams(Ui.dp(a, 130), Ui.dp(a, 74)));

        LinearLayout txt = new LinearLayout(a);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(Ui.dp(a, 12), 0, 0, 0);
        TextView t = Ui.text(a, (ep.episodeNo > 0 ? ep.episodeNo + ". " : "") + ep.name + (ep.played ? "  ✓" : ""), 14, Ui.TEXT, true);
        t.setMaxLines(2);
        txt.addView(t);
        TextView r = Ui.text(a, ep.runtime(), 12, Ui.MUTED, false);
        txt.addView(r);
        if (!ep.overview.isEmpty()) {
            TextView o = Ui.text(a, ep.overview, 12, Ui.MUTED, false);
            o.setMaxLines(2);
            o.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txt.addView(o);
        }
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> a.play(ep, ep.resumeTicks));
        row.setOnLongClickListener(v -> { Cards.menu(a, ep, () -> showSeason(seasonId)); return true; });
        return row;
    }
}
