package de.marvin.jellyfin.mobile;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import de.marvin.jellyfin.core.JfItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** "Deine Statistik": gesehene Zeit, Filme/Folgen, letzte 14 Tage, Lieblingsserien und -genres. */
final class StatsScreen extends Screen {
    private LinearLayout col;

    StatsScreen(MainActivity a) { super(a); }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), Ui.dp(a, 14));
        l.setBackground(Ui.outlined(a, Ui.CARD, 0x14FFFFFF, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(a, 12);
        l.setLayoutParams(lp);
        return l;
    }

    @Override
    View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 4));
        android.widget.ImageView back = Ui.iconButton(a, "back", 44, false);
        back.setOnClickListener(v -> a.pop());
        bar.addView(back);
        bar.addView(Ui.text(a, "Deine Statistik", 22, Ui.TEXT, true));
        root.addView(bar);
        ScrollView sv = new ScrollView(a);
        col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), Ui.dp(a, 24));
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        col.addView(Ui.text(a, "Lädt …", 15, Ui.MUTED, false));

        a.bg(() -> a.jf.playedItems(), list -> {
            col.removeAllViews();
            if (list == null) return;
            long totalMin = 0;
            int movies = 0, eps = 0;
            Map<String, Integer> series = new HashMap<>();
            Map<String, Long> genres = new HashMap<>();
            long[] perDay = new long[14];
            java.time.LocalDate today = java.time.LocalDate.now();
            for (JfItem i : list) {
                long min = i.runTicks / 600000000L;
                totalMin += min;
                if ("Movie".equals(i.type)) movies++;
                else { eps++; if (!i.seriesName.isEmpty()) series.merge(i.seriesName, 1, Integer::sum); }
                if (!i.genres.isEmpty()) for (String g : i.genres.split(" · ")) genres.merge(g.trim(), min, Long::sum);
                try {
                    java.time.LocalDate d = java.time.OffsetDateTime.parse(i.lastPlayed)
                            .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate();
                    long ago = java.time.temporal.ChronoUnit.DAYS.between(d, today);
                    if (ago >= 0 && ago < 14) perDay[(int) (13 - ago)] += min;
                } catch (Exception ignored) { }
            }
            tiles(new String[][]{
                    {totalMin / 60 + " Std " + totalMin % 60 + " Min", "gesehene Zeit"},
                    {String.valueOf(movies), "Filme gesehen"},
                    {String.valueOf(eps), "Folgen gesehen"},
                    {String.valueOf(series.size()), "Serien angefangen"}});
            LinearLayout c = card();
            c.addView(Ui.text(a, "Letzte 14 Tage (Minuten pro Tag)", 16, Ui.TEXT, true));
            c.addView(new Chart(perDay), new LinearLayout.LayoutParams(-1, Ui.dp(a, 170)));
            col.addView(c);
            ArrayList<String[]> ts = new ArrayList<>(), tg = new ArrayList<>();
            series.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(6)
                    .forEach(e -> ts.add(new String[]{e.getKey(), String.valueOf(e.getValue()), e.getValue() + " Folgen"}));
            genres.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(6)
                    .forEach(e -> tg.add(new String[]{e.getKey(), String.valueOf(e.getValue()), e.getValue() / 60 + " Std " + e.getValue() % 60 + " Min"}));
            bars("Meistgesehene Serien", ts);
            bars("Lieblings-Genres", tg);
            if (list.isEmpty()) col.addView(Ui.text(a, "Sobald du etwas gesehen hast, erscheinen hier deine Zahlen.", 15, Ui.MUTED, false));
        });
        return root;
    }

    private void tiles(String[][] t) {
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(a);
            for (int c = 0; c < 2; c++) {
                String[] v = t[r * 2 + c];
                LinearLayout tile = new LinearLayout(a);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), Ui.dp(a, 14));
                tile.setBackground(Ui.outlined(a, Ui.CARD, 0x14FFFFFF, 16));
                TextView big = Ui.text(a, v[0], 24, Ui.ACCENT2, true);
                tile.addView(big);
                tile.addView(Ui.text(a, v[1], 13, Ui.MUTED, false));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                lp.topMargin = Ui.dp(a, 12);
                lp.leftMargin = c == 1 ? Ui.dp(a, 6) : 0;
                lp.rightMargin = c == 0 ? Ui.dp(a, 6) : 0;
                row.addView(tile, lp);
            }
            col.addView(row);
        }
    }

    private void bars(String title, ArrayList<String[]> rows) {
        LinearLayout c = card();
        c.addView(Ui.text(a, title, 16, Ui.TEXT, true));
        double max = 1;
        for (String[] r : rows) max = Math.max(max, Double.parseDouble(r[1]));
        for (String[] r : rows) {
            TextView n = Ui.text(a, r[0], 13, Ui.TEXT, false);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            n.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 3));
            c.addView(n);
            LinearLayout line = new LinearLayout(a);
            line.setGravity(Gravity.CENTER_VERTICAL);
            View bar = new View(a);
            bar.setBackground(Ui.gradient(a, 4));
            final double frac = Double.parseDouble(r[1]) / max;
            line.addView(bar, new LinearLayout.LayoutParams(0, Ui.dp(a, 8), (float) Math.max(0.04, frac)));
            line.addView(new View(a), new LinearLayout.LayoutParams(0, 1, (float) Math.max(0.0001, 1 - frac)));
            c.addView(line);
            c.addView(Ui.text(a, r[2], 12, Ui.MUTED, false));
        }
        if (rows.isEmpty()) c.addView(Ui.text(a, "Noch keine Daten.", 13, Ui.MUTED, false));
        col.addView(c);
    }

    /** Balkendiagramm der letzten 14 Tage. */
    private final class Chart extends View {
        private final long[] v;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);

        Chart(long[] v) {
            super(a);
            this.v = v;
            t.setColor(Ui.MUTED);
            t.setTextAlign(Paint.Align.CENTER);
            t.setTextSize(Ui.dp(a, 10));
        }

        @Override protected void onDraw(Canvas c) {
            long max = 1;
            for (long x : v) max = Math.max(max, x);
            float w = getWidth() / 14f, h = getHeight() - Ui.dp(a, 18);
            java.time.LocalDate today = java.time.LocalDate.now();
            for (int i = 0; i < 14; i++) {
                float bh = v[i] == 0 ? Ui.dp(a, 3) : Math.max(Ui.dp(a, 8), h * 0.9f * v[i] / max);
                float l = i * w + w * 0.18f, r = (i + 1) * w - w * 0.18f, top = h - bh;
                p.setShader(new LinearGradient(0, top, 0, h, Ui.ACCENT2, Ui.ACCENT, Shader.TileMode.CLAMP));
                c.drawRoundRect(new RectF(l, top, r, h), Ui.dp(a, 4), Ui.dp(a, 4), p);
                c.drawText(String.valueOf(today.minusDays(13 - i).getDayOfMonth()), (l + r) / 2, getHeight() - Ui.dp(a, 3), t);
            }
        }
    }
}
