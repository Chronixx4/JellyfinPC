package de.marvin.jellyfin.tv;

import android.app.AlertDialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import de.marvin.jellyfin.core.JfItem;

import java.util.ArrayList;
import java.util.List;

/** Karten fuer Poster (hoch) und Vorschaubilder (quer) in Reihen und Rastern. */
final class Cards {
    private Cards() {}

    static final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final MainActivity a;
        final List<JfItem> items = new ArrayList<>();
        final boolean landscape;
        final int widthDp; // 0 = Rasterzelle, fuellt die Spalte
        Runnable onChanged;

        Adapter(MainActivity a, boolean landscape, int widthDp) {
            this.a = a;
            this.landscape = landscape;
            this.widthDp = widthDp;
        }

        void set(List<JfItem> l) {
            items.clear();
            if (l != null) items.addAll(l);
            notifyDataSetChanged();
        }

        void add(List<JfItem> l) {
            int s = items.size();
            items.addAll(l);
            notifyItemRangeInserted(s, l.size());
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int t) {
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            int w = widthDp > 0 ? Ui.dp(a, widthDp) : ViewGroup.LayoutParams.MATCH_PARENT;
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = Ui.dp(a, 5);
            lp.setMargins(m, m, m, m);
            col.setLayoutParams(lp);
            col.setClickable(true);
            Ui.ring(col, 12);
            col.setPadding(Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3));

            FrameLayout frame = new FrameLayout(a);
            frame.setBackground(Ui.outlined(a, Ui.CARD, 0x14FFFFFF, 12));
            frame.setClipToOutline(true);
            ImageView img = new ImageView(a);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(img, new FrameLayout.LayoutParams(-1, -1));
            View badge = Ui.checkBadge(a);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22), Gravity.TOP | Gravity.END);
            bl.setMargins(0, Ui.dp(a, 6), Ui.dp(a, 6), 0);
            frame.addView(badge, bl);
            View bar = new View(a);
            bar.setBackground(Ui.gradient(a, 2));
            frame.addView(bar, new FrameLayout.LayoutParams(0, Ui.dp(a, 4), Gravity.BOTTOM | Gravity.START));
            col.addView(frame, new LinearLayout.LayoutParams(-1, -2));

            TextView title = Ui.text(a, "", 13, Ui.TEXT, true);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(Ui.dp(a, 2), Ui.dp(a, 6), Ui.dp(a, 2), 0);
            col.addView(title);
            TextView sub = Ui.text(a, "", 12, Ui.MUTED, false);
            sub.setMaxLines(1);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setPadding(Ui.dp(a, 2), 0, Ui.dp(a, 2), 0);
            col.addView(sub);
            return new RecyclerView.ViewHolder(col) { };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
            JfItem it = items.get(pos);
            LinearLayout col = (LinearLayout) h.itemView;
            FrameLayout frame = (FrameLayout) col.getChildAt(0);
            ImageView img = (ImageView) frame.getChildAt(0);
            View badge = frame.getChildAt(1), bar = frame.getChildAt(2);
            TextView title = (TextView) col.getChildAt(1), sub = (TextView) col.getChildAt(2);

            // Hoehe der Bildflaeche aus der Spaltenbreite ableiten
            int wpx = widthDp > 0 ? Ui.dp(a, widthDp) - Ui.dp(a, 10) : 0;
            float ratio = landscape ? 16f / 9f : 2f / 3f;
            if (wpx > 0) {
                frame.getLayoutParams().height = Math.round(wpx / ratio);
            } else {
                frame.getLayoutParams().height = 0;
                frame.post(() -> {
                    int w = frame.getWidth();
                    if (w > 0 && frame.getLayoutParams().height != Math.round(w / ratio)) {
                        frame.getLayoutParams().height = Math.round(w / ratio);
                        frame.requestLayout();
                    }
                });
            }
            String url = landscape ? a.jf.landscape(it) : a.jf.poster(it);
            if (url == null) url = a.jf.libraryImage(it);
            Ui.load(img, url);

            boolean showTitleEp = landscape && "Episode".equals(it.type);
            title.setText(showTitleEp && !it.seriesName.isEmpty() ? it.seriesName : it.title());
            String s;
            if (showTitleEp) s = it.title();
            else if ("Episode".equals(it.type)) s = it.seriesName;
            else s = it.year > 0 ? String.valueOf(it.year) : "";
            sub.setText(s);
            badge.setVisibility(it.played ? View.VISIBLE : View.GONE);
            float pr = it.progress();
            bar.setVisibility(pr > 0 ? View.VISIBLE : View.GONE);
            if (pr > 0) {
                int bw = (wpx > 0 ? wpx : Ui.dp(a, 110)) ;
                bar.getLayoutParams().width = Math.round(bw * pr);
            }
            col.setOnClickListener(v -> a.openItem(it));
            col.setOnLongClickListener(v -> { menu(a, it, () -> { if (onChanged != null) onChanged.run(); }); return true; });
        }

        @Override public int getItemCount() { return items.size(); }
    }

    /** Lange druecken: Gesehen / Merkliste / aus "Weiter schauen" entfernen. */
    static void menu(MainActivity a, JfItem it, Runnable changed) {
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> acts = new ArrayList<>();
        if (it.isPlayable() || "Series".equals(it.type)) {
            labels.add(it.played ? "Als ungesehen markieren" : "Als gesehen markieren");
            acts.add(() -> a.bg(() -> { a.jf.setPlayed(it.id, !it.played); return true; }, ok -> { if (ok != null) changed.run(); }));
            labels.add(it.favorite ? "Von der Merkliste entfernen" : "Auf die Merkliste");
            acts.add(() -> a.bg(() -> { a.jf.setFavorite(it.id, !it.favorite); return true; }, ok -> { if (ok != null) changed.run(); }));
        }
        if (it.isPlayable() || "Series".equals(it.type)) {
            labels.add("Zur Wiedergabeliste …");
            acts.add(() -> Lists.addTo(a, it, true));
            labels.add("Zur Sammlung …");
            acts.add(() -> Lists.addTo(a, it, false));
        }
        if (it.resumeTicks > 0) {
            labels.add("Aus „Weiter schauen“ entfernen");
            acts.add(() -> a.bg(() -> { a.jf.clearProgress(it.id); return true; }, ok -> { if (ok != null) changed.run(); }));
        }
        if (labels.isEmpty()) return;
        new AlertDialog.Builder(a).setTitle(it.name)
                .setItems(labels.toArray(new String[0]), (d, i) -> acts.get(i).run()).show();
    }
}
