package de.marvin.jellyfin.mobile;

import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import de.marvin.jellyfin.core.JfItem;

import java.util.ArrayList;

/** Bildschirmschoner: grosse Titelbilder aus der Bibliothek, langsam eingeblendet und gezoomt, dazu Titel und Uhr. */
final class Screensaver {
    final FrameLayout view;
    private final MainActivity a;
    private final ImageView[] pics = new ImageView[2];
    private final TextView title, sub;
    private final Handler h = new Handler(Looper.getMainLooper());
    private ArrayList<JfItem> items = new ArrayList<>();
    private int next, front;
    private boolean dead;

    Screensaver(MainActivity a) {
        this.a = a;
        view = new FrameLayout(a);
        view.setBackgroundColor(0xFF000000);
        view.setClickable(true);
        for (int i = 0; i < 2; i++) {
            pics[i] = new ImageView(a);
            pics[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            pics[i].setAlpha(0f);
            view.addView(pics[i], new FrameLayout.LayoutParams(-1, -1));
        }
        View shade = new View(a);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000, 0x66000000}));
        view.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        TextClock clock = new TextClock(a);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Ui.TEXT);
        clock.setTextSize(44);
        clock.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        clock.setShadowLayer(10, 0, 2, 0xCC000000);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cl.setMargins(0, Ui.dp(a, 36), Ui.dp(a, 24), 0);
        view.addView(clock, cl);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        title = Ui.text(a, "", 28, Ui.TEXT, true);
        title.setShadowLayer(8, 0, 2, 0xCC000000);
        sub = Ui.text(a, "", 14, Ui.MUTED, false);
        col.addView(title);
        col.addView(sub);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        ll.setMargins(Ui.dp(a, 24), 0, Ui.dp(a, 24), Ui.dp(a, 40));
        view.addView(col, ll);

        a.bg(() -> a.jf.randomBackdrops(14), l -> {
            if (l == null || dead || l.isEmpty()) return;
            l.removeIf(i -> a.jf.backdrop(i) == null);
            if (l.isEmpty()) return;
            items = l;
            show();
        }, true);
    }

    private void show() {
        if (dead || items.isEmpty()) return;
        JfItem it = items.get(next++ % items.size());
        String url = a.jf.backdrop(it);
        ImageView in = pics[front ^ 1], out = pics[front];
        front ^= 1;
        Ui.load(in, url);
        in.setScaleX(1f);
        in.setScaleY(1f);
        in.animate().alpha(1f).setDuration(2500).start();
        for (android.util.Property<View, Float> pr : new android.util.Property[]{View.SCALE_X, View.SCALE_Y}) {
            android.animation.ObjectAnimator z = android.animation.ObjectAnimator.ofFloat(in, pr, 1f, 1.15f);
            z.setDuration(16000);
            z.setInterpolator(new android.view.animation.LinearInterpolator());
            z.start();
        }
        out.animate().alpha(0f).setDuration(2500).start();
        boolean ep = "Episode".equals(it.type) && !it.seriesName.isEmpty();
        title.setText(ep ? it.seriesName : it.name);
        sub.setText(it.year > 0 ? String.valueOf(it.year) : "");
        h.postDelayed(this::show, 10000);
    }

    void stop() {
        dead = true;
        h.removeCallbacksAndMessages(null);
    }
}
