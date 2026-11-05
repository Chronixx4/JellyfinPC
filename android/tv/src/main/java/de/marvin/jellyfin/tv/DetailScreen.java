package de.marvin.jellyfin.tv;

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
    private Ui.Btn playButton;

    DetailScreen(MainActivity a, JfItem it) {
        super(a);
        this.item = it;
    }

    @Override
    View build() {
        FrameLayout frame = new FrameLayout(a);
        // Hintergrundbild fest hinter der Seite, unten in die Hintergrundfarbe ausgeblendet
        ImageView bd = new ImageView(a);
        bd.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String url = a.jf.backdrop(item);
        if (url == null) url = a.jf.landscape(item);
        Ui.load(bd, url);
        frame.addView(bd, new FrameLayout.LayoutParams(-1, Ui.dp(a, 340)));
        View fade = new View(a);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x990A0D12, 0xCC0A0D12, Ui.BG}));
        frame.addView(fade, new FrameLayout.LayoutParams(-1, Ui.dp(a, 340)));
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, Ui.dp(a, 32));
        sv.addView(col, new ScrollView.LayoutParams(-1, -2));
        frame.addView(sv, new FrameLayout.LayoutParams(-1, -1));

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

    private void addMeta(LinearLayout row, View v) {
        LinearLayout.LayoutParams lp = v instanceof ImageView && v.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) v.getLayoutParams() : new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(a, 8);
        row.addView(v, lp);
    }

    private TextView pill(String s) {
        TextView t = Ui.text(a, s, 12, Ui.TEXT, true);
        t.setPadding(Ui.dp(a, 8), Ui.dp(a, 3), Ui.dp(a, 8), Ui.dp(a, 3));
        t.setBackground(Ui.round(Ui.CARD2, 6, a));
        return t;
    }

    private void render() {
        col.removeAllViews();
        int side = Ui.dp(a, Ui.PAD);

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(side, Ui.dp(a, 26), side, 0);
        col.addView(body, new LinearLayout.LayoutParams(-1, -2));

        // Oben: Poster links, Infos rechts (Hintergrundbild liegt fest hinter der ganzen Seite)
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(top, new LinearLayout.LayoutParams(-1, -2));
        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(Ui.outlined(a, Ui.CARD, 0x22FFFFFF, 10));
        poster.setClipToOutline(true);
        Ui.load(poster, "Episode".equals(item.type) ? a.jf.landscape(item) : a.jf.poster(item));
        boolean wide = "Episode".equals(item.type);
        top.addView(poster, new LinearLayout.LayoutParams(Ui.dp(a, wide ? 230 : 140), Ui.dp(a, wide ? 130 : 210)));

        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(a, 24), 0, 0, 0);
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        String head = "Episode".equals(item.type) && !item.seriesName.isEmpty() ? item.seriesName : item.name;
        TextView title = Ui.text(a, head, 28, Ui.TEXT, true);
        title.setMaxLines(2);
        title.setShadowLayer(8, 0, 2, 0xCC000000);
        info.addView(title);
        if ("Episode".equals(item.type)) info.addView(Ui.text(a, item.title(), 15, Ui.MUTED, false));

        LinearLayout meta = new LinearLayout(a);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(0, Ui.dp(a, 6), 0, 0);
        if (item.year > 0) addMeta(meta, Ui.badge(a, String.valueOf(item.year), null));
        if (item.rating > 0) addMeta(meta, Ui.badge(a, String.format(java.util.Locale.GERMANY, "%.1f", item.rating), "star"));
        if (!item.officialRating.isEmpty()) addMeta(meta, Ui.badge(a, item.officialRating, null));
        if (item.runTicks > 0) addMeta(meta, Ui.badge(a, item.runtime(), null));
        for (String b : item.badges()) addMeta(meta, Ui.badge(a, b, null));
        if (meta.getChildCount() > 0) info.addView(meta);
        if (!item.genres.isEmpty()) {
            TextView g = Ui.text(a, item.genres, 13, Ui.MUTED, false);
            g.setPadding(0, Ui.dp(a, 6), 0, 0);
            info.addView(g);
        }
        if (!item.tagline.isEmpty()) {
            TextView tl = Ui.text(a, item.tagline, 13, Ui.ACCENT2, false);
            tl.setTypeface(null, android.graphics.Typeface.ITALIC);
            tl.setPadding(0, Ui.dp(a, 6), 0, 0);
            info.addView(tl);
        }
        if (!item.overview.isEmpty()) {
            TextView ov = Ui.text(a, item.overview, 13, 0xFFDDDDDD, false);
            ov.setLineSpacing(0, 1.1f);
            ov.setMaxLines(4);
            ov.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ov.setPadding(0, Ui.dp(a, 8), 0, 0);
            ov.setOnClickListener(v -> {
                boolean collapsed = ov.getMaxLines() == 4;
                ov.setMaxLines(collapsed ? 100 : 4);
            });
            info.addView(ov);
        }
        if (!item.audioLangs.isEmpty() || !item.subLangs.isEmpty()) {
            TextView l = Ui.text(a, (item.audioLangs.isEmpty() ? "" : "Ton: " + item.audioLangs)
                    + (!item.audioLangs.isEmpty() && !item.subLangs.isEmpty() ? "   ·   " : "")
                    + (item.subLangs.isEmpty() ? "" : "Untertitel: " + item.subLangs), 12, Ui.MUTED, false);
            l.setPadding(0, Ui.dp(a, 8), 0, 0);
            info.addView(l);
        }

        // Knoepfe in einer Reihe: Abspielen, Neustart, Merkliste, Gesehen, Trailer, Listen
        JfItem target = "Series".equals(item.type) ? nextUp : item;
        LinearLayout btns = new LinearLayout(a);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        btns.setPadding(0, Ui.dp(a, 14), 0, 0);
        if (item.isPlayable() || target != null) {
            JfItem t = target != null ? target : item;
            boolean resume = t.resumeTicks > 0;
            String label = resume ? "Fortsetzen" : "Abspielen";
            if ("Series".equals(item.type)) label += "  " + t.title();
            Ui.Btn play = Ui.button(a, "play", label, true);
            play.setOnClickListener(v -> a.play(t, t.resumeTicks));
            addMeta(btns, play);
            playButton = play;
            if (resume) {
                ImageView again = Ui.iconButton(a, "restart", Ui.ICON, true);
                again.setOnClickListener(v -> a.play(t, 0));
                addMeta(btns, again);
            }
        }
        ImageView fav = Ui.iconButton(a, item.favorite ? "heartOn" : "heart", Ui.ICON, true);
        fav.setOnClickListener(v -> a.bg(() -> { a.jf.setFavorite(item.id, !item.favorite); return true; }, ok -> { if (ok != null) refresh(); }));
        addMeta(btns, fav);
        ImageView seen = Ui.iconButton(a, item.played ? "checkCircle" : "circle", Ui.ICON, true);
        seen.setOnClickListener(v -> a.bg(() -> { a.jf.setPlayed(item.id, !item.played); return true; }, ok -> { if (ok != null) refresh(); }));
        addMeta(btns, seen);
        if (item.trailerUrl != null && !item.trailerUrl.isEmpty()) {
            Ui.Btn tr = Ui.button(a, "movie", "Trailer", false);
            tr.setOnClickListener(v -> {
                try { a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(item.trailerUrl))); }
                catch (Exception e) { a.toast("Kein Programm für den Trailer gefunden"); }
            });
            addMeta(btns, tr);
        }
        if (item.isPlayable() || "Series".equals(item.type)) {
            Ui.Btn pl = Ui.button(a, "viewList", "Liste", false);
            pl.setOnClickListener(v -> Lists.addTo(a, item, true));
            addMeta(btns, pl);
            Ui.Btn bs = Ui.button(a, "add", "Sammlung", false);
            bs.setOnClickListener(v -> Lists.addTo(a, item, false));
            addMeta(btns, bs);
        }
        info.addView(btns);
        if (playButton != null) playButton.post(playButton::requestFocus);

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
                im.setBackground(Ui.round(Ui.CARD, 32, a));
                im.setClipToOutline(true);
                Ui.load(im, a.jf.personImage(p));
                c.addView(im, new LinearLayout.LayoutParams(Ui.dp(a, 64), Ui.dp(a, 64)));
                TextView nm = Ui.text(a, p.name, 12, Ui.TEXT, true);
                nm.setGravity(Gravity.CENTER);
                nm.setMaxLines(2);
                nm.setPadding(0, Ui.dp(a, 4), 0, 0);
                c.addView(nm, new LinearLayout.LayoutParams(Ui.dp(a, 80), -2));
                if (!p.role.isEmpty()) {
                    TextView ro = Ui.text(a, p.role, 11, Ui.MUTED, false);
                    ro.setGravity(Gravity.CENTER);
                    ro.setMaxLines(1);
                    ro.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    c.addView(ro, new LinearLayout.LayoutParams(Ui.dp(a, 80), -2));
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
        Cards.Adapter ad = new Cards.Adapter(a, false, 112);
        ad.set(sim);
        rv.setAdapter(ad);
        rv.setClipToPadding(false);
        similarHolder.addView(rv, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showSeason(String sid) {
        seasonId = sid;
        for (TextView c : chipViews) {
            boolean sel = sid.equals(c.getTag());
            Ui.restyleChip(c, sel);
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
        th.setBackground(Ui.outlined(a, Ui.CARD, 0x14FFFFFF, 10));
        th.setClipToOutline(true);
        ImageView im = new ImageView(a);
        im.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.load(im, a.jf.landscape(ep));
        th.addView(im, new FrameLayout.LayoutParams(-1, -1));
        if (ep.progress() > 0) {
            ProgressBar pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(1000);
            pb.setProgress((int) (ep.progress() * 1000));
            pb.getProgressDrawable().setTint(Ui.ACCENT2);
            th.addView(pb, new FrameLayout.LayoutParams(-1, Ui.dp(a, 4), Gravity.BOTTOM));
        }
        row.addView(th, new LinearLayout.LayoutParams(Ui.dp(a, 150), Ui.dp(a, 84)));

        LinearLayout txt = new LinearLayout(a);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(Ui.dp(a, 12), 0, 0, 0);
        TextView t = Ui.text(a, (ep.episodeNo > 0 ? ep.episodeNo + ". " : "") + ep.name, 14, Ui.TEXT, true);
        t.setMaxLines(2);
        if (ep.played) {
            android.graphics.drawable.Drawable d = Icons.drawable("checkCircle", Ui.ACCENT2);
            d.setBounds(0, 0, Ui.dp(a, 16), Ui.dp(a, 16));
            t.setCompoundDrawablesRelative(null, null, d, null);
            t.setCompoundDrawablePadding(Ui.dp(a, 6));
        }
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
