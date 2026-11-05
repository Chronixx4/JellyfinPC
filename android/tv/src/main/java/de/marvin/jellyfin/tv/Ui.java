package de.marvin.jellyfin.tv;

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
import android.widget.FrameLayout;
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
    // Farben wie in der PC-App (style.css)
    static final int BG = Color.parseColor("#0A0D12");
    static final int CARD = Color.parseColor("#131820");
    static final int CARD2 = Color.parseColor("#1D232C");
    static final int GLASS = Color.parseColor("#E6121822");
    static final int LINE = Color.parseColor("#1AFFFFFF");
    static final int ACCENT = Color.parseColor("#8B6CFF");
    static final int ACCENT2 = Color.parseColor("#19A8FF");
    static final int TEXT = Color.parseColor("#EEF2F6");
    static final int MUTED = Color.parseColor("#8B98A8");

    private Ui() {}

    /**
     * Fernseher melden je nach Modell ganz verschiedene Dichten (Fire TV 1080p = 1280 dp breit, Android TV = 1440 dp).
     * Darum rechnet die TV-App nicht in dp, sondern in "Einheiten" einer festen Entwurfsbreite von 960: auf einem
     * 1080p-Bild ist eine Einheit 2 Pixel, auf 4K 4 Pixel. So sieht das Layout ueberall gleich gross aus.
     */
    /** Seitenrand (Overscan-sicher) und Standardgroesse runder Symbolknoepfe. */
    static final float PAD = 40, ICON = 36;

    static float unit(Context c) { return c.getResources().getDisplayMetrics().widthPixels / 960f; }

    static int dp(Context c, float v) { return Math.round(v * unit(c)); }

    /** Fokusrahmen fuer die Fernbedienung: heller Rand + leichtes Vergroessern, solange das Element den Fokus hat. */
    static void ring(View v, float radiusDp) {
        Context c = v.getContext();
        android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable();
        on.setColor(0x14FFFFFF);
        on.setCornerRadius(dp(c, radiusDp));
        on.setStroke(Math.max(2, dp(c, 2.5f)), Color.WHITE);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, on);
        sl.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        v.setForeground(sl);
        v.setFocusable(true);
        v.setOnFocusChangeListener((x, f) -> x.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(120).start());
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * unit(c));
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

    static GradientDrawable gradient(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT, ACCENT2});
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable outlined(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = round(fill, radiusDp, c);
        g.setStroke(Math.max(1, dp(c, 1)), stroke);
        return g;
    }

    /** Macht alle Elemente mit Klick-Aktion fuer die Fernbedienung fokussierbar. */
    static void makeFocusable(View v) {
        if (!v.isFocusable() && v.hasOnClickListeners()) ring(v, 12);
        if (v instanceof android.widget.ScrollView) ((android.widget.ScrollView) v).setVerticalScrollBarEnabled(false);
        if (v instanceof androidx.recyclerview.widget.RecyclerView) v.setVerticalScrollBarEnabled(false);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) makeFocusable(g.getChildAt(i));
        }
    }

    static ImageView icon(Context c, String name, float sizeDp, int color) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(Icons.drawable(name, color));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /** Pillen-Knopf wie in der PC-App: primaer = Farbverlauf, sonst Glas. Optional mit Symbol vor dem Text. */
    static final class Btn extends LinearLayout {
        final TextView label;
        ImageView icon;

        Btn(Context c, String iconName, String text, boolean primary) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setMinimumHeight(dp(c, 36));
            setPadding(dp(c, 16), 0, dp(c, 16), 0);
            setBackground(primary ? gradient(c, 18) : outlined(c, 0x1FFFFFFF, LINE, 18));
            setClickable(true);
            ring(this, 18);
            if (primary) setElevation(dp(c, 3));
            if (iconName != null) {
                icon = Ui.icon(c, iconName, 17, Color.WHITE);
                addView(icon);
            }
            label = text(c, text == null ? "" : text, 13, Color.WHITE, true);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (text != null && !text.isEmpty()) {
                LayoutParams lp = new LayoutParams(-2, -2);
                lp.leftMargin = iconName != null ? dp(c, 6) : 0;
                addView(label, lp);
            }
        }

        void setIcon(String name) { icon.setImageDrawable(Icons.drawable(name, Color.WHITE)); }
    }

    static Btn button(Context c, String label, boolean primary) { return new Btn(c, null, label, primary); }

    static Btn button(Context c, String iconName, String label, boolean primary) { return new Btn(c, iconName, label, primary); }

    /** Runder Symbol-Knopf (wie .icon-btn der PC-App). */
    static ImageView iconButton(Context c, String name, float sizeDp, boolean glass) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(Icons.drawable(name, TEXT));
        int pad = dp(c, sizeDp * 0.27f);
        v.setPadding(pad, pad, pad, pad);
        v.setBackground(glass ? outlined(c, 0x14FFFFFF, LINE, sizeDp / 2) : null);
        v.setClickable(true);
        ring(v, sizeDp / 2);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    static void setIconButton(ImageView v, String name) { v.setImageDrawable(Icons.drawable(name, TEXT)); }

    /** Umschalt-Pille (.toggle-pill): ausgewaehlt = Farbverlauf. */
    static TextView chip(Context c, String label, boolean selected) {
        TextView t = text(c, label, 13, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 30));
        t.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        t.setClickable(true);
        ring(t, 15);
        restyleChip(t, selected);
        return t;
    }

    static void restyleChip(TextView t, boolean selected) {
        Context c = t.getContext();
        t.setBackground(selected ? gradient(c, 15) : round(0x14FFFFFF, 15, c));
        t.setTextColor(selected ? Color.WHITE : Color.parseColor("#C8D2DC"));
    }

    /** Kleines Etikett (.chip der PC-App), z. B. "4K" oder "2020". */
    static View badge(Context c, String text, String iconName) {
        LinearLayout l = new LinearLayout(c);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        l.setBackground(outlined(c, 0x24FFFFFF, 0x2EFFFFFF, 7));
        if (iconName != null) {
            ImageView i = icon(c, iconName, 13, Color.parseColor("#FFC94D"));
            ((LinearLayout.LayoutParams) i.getLayoutParams()).rightMargin = dp(c, 4);
            l.addView(i);
        }
        l.addView(text(c, text, 12, TEXT, true));
        return l;
    }

    /** Text mit Farbverlauf (Markenname). */
    static TextView brand(Context c, String s, float sp) {
        TextView t = text(c, s, sp, TEXT, true);
        t.post(() -> {
            float tw = t.getPaint().measureText(s);
            t.getPaint().setShader(new android.graphics.LinearGradient(0, 0, tw, 0, ACCENT, ACCENT2, android.graphics.Shader.TileMode.CLAMP));
            t.invalidate();
        });
        return t;
    }

    /** Rundes Bild-Etikett "gesehen" fuer Karten. */
    static View checkBadge(Context c) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(gradient(c, 11));
        ImageView i = icon(c, "check", 14, Color.WHITE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(c, 14), dp(c, 14), Gravity.CENTER);
        f.addView(i, lp);
        return f;
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
