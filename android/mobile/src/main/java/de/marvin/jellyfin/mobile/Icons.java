package de.marvin.jellyfin.mobile;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.core.graphics.PathParser;

import java.util.HashMap;
import java.util.Map;

/** Vektor-Symbole (24x24, Material-Stil) als Zeichnung, damit die App ohne Emojis auskommt. */
final class Icons {
    private static final Map<String, String> P = new HashMap<>();

    /** Liest die gemeinsame Symboldatei (shared/icons.properties, als Asset eingebunden). */
    static synchronized void init(android.content.Context c) {
        if (!P.isEmpty()) return;
        try (java.io.InputStream in = c.getAssets().open("icons.properties")) {
            java.util.Properties p = new java.util.Properties();
            p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            for (String k : p.stringPropertyNames()) P.put(k, p.getProperty(k));
        } catch (Exception e) {
            throw new IllegalStateException("icons.properties fehlt", e);
        }
    }

    private Icons() {}

    static boolean has(String name) { return P.containsKey(name); }

    /** Einfarbiges Vektor-Symbol; skaliert auf die Groesse der Zeichenflaeche. */
    static Drawable drawable(String name, int color) {
        String d = P.get(name);
        if (d == null) throw new IllegalArgumentException("Unbekanntes Symbol: " + name);
        return new PathDrawable(PathParser.createPathFromPathData(d), color);
    }

    private static final class PathDrawable extends Drawable {
        private final Path src, scaled = new Path();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Matrix m = new android.graphics.Matrix();
        private int w = -1, h = -1;

        PathDrawable(Path p, int color) {
            src = p;
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            if (b.width() != w || b.height() != h) {
                w = b.width(); h = b.height();
                m.setRectToRect(new RectF(0, 0, 24, 24), new RectF(b), android.graphics.Matrix.ScaleToFit.CENTER);
                src.transform(m, scaled);
            }
            c.drawPath(scaled, paint);
        }

        @Override public int getIntrinsicWidth() { return -1; }
        @Override public int getIntrinsicHeight() { return -1; }
        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override protected void onBoundsChange(android.graphics.Rect r) { w = -1; }
    }
}
