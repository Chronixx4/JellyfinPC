package de.marvin.jellyfin.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Kleine Helfer fuer die rein programmatische Oberflaeche (Farben, Texte, Buttons, Bilder). */
final class Ui {
    static final int BG = Color.parseColor("#0E0F14");
    static final int CARD = Color.parseColor("#1A1C24");
    static final int CARD2 = Color.parseColor("#252833");
    static final int ACCENT = Color.parseColor("#00A4DC");
    static final int TEXT = Color.WHITE;
    static final int MUTED = Color.parseColor("#9AA0AE");

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static GradientDrawable round(int color, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** Gefuellter Button (mind. 48dp hoch, damit er auf dem Handy gut zu treffen ist). */
    static TextView button(Context c, String label, boolean primary) {
        TextView t = text(c, label, 15, primary ? Color.BLACK : TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 48));
        t.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        t.setBackground(round(primary ? ACCENT : CARD2, 24, c));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    static TextView chip(Context c, String label, boolean selected) {
        TextView t = text(c, label, 14, selected ? Color.BLACK : TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 40));
        t.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        t.setBackground(round(selected ? ACCENT : CARD2, 20, c));
        t.setClickable(true);
        return t;
    }

    static View space(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, h)));
        return v;
    }

    // ------------------------------------------------------------ Bilder

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 1024 / 6)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    static void load(ImageView v, String url) {
        v.setTag(url);
        v.setImageDrawable(null);
        if (url == null) return;
        Bitmap hit = CACHE.get(url);
        if (hit != null) { v.setImageBitmap(hit); return; }
        POOL.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(15000);
                try (InputStream in = c.getInputStream()) {
                    Bitmap b = BitmapFactory.decodeStream(in);
                    if (b == null) return;
                    CACHE.put(url, b);
                    v.post(() -> { if (url.equals(v.getTag())) v.setImageBitmap(b); });
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) { }
        });
    }
}
