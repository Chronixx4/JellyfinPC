package de.marvin.jellyfin.mobile;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Offline-Downloads: ansehen, abspielen, loeschen. */
final class DownloadsScreen extends Screen {
    private LinearLayout col;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean alive = true;

    DownloadsScreen(MainActivity a) { super(a); }

    @Override
    View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 4));
        ImageView back = Ui.iconButton(a, "back", 44, false);
        back.setOnClickListener(v -> { alive = false; a.pop(); });
        bar.addView(back);
        bar.addView(Ui.text(a, "Downloads", 22, Ui.TEXT, true));
        root.addView(bar);
        ScrollView sv = new ScrollView(a);
        col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), Ui.dp(a, 24));
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        tick();
        return root;
    }

    @Override boolean onBack() { alive = false; return false; }

    private void tick() {
        if (!alive) return;
        render();
        h.postDelayed(this::tick, 2000);
    }

    private void render() {
        col.removeAllViews();
        java.util.ArrayList<Downloads.Entry> l = Downloads.list(a);
        if (l.isEmpty()) {
            TextView t = Ui.text(a, "Noch nichts geladen. Auf der Detailseite eines Titels das Download-Symbol antippen.", 15, Ui.MUTED, false);
            t.setPadding(0, Ui.dp(a, 16), 0, 0);
            col.addView(t);
            return;
        }
        for (Downloads.Entry e : l) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 10));
            row.setBackground(Ui.outlined(a, Ui.CARD, 0x14FFFFFF, 14));
            LinearLayout txt = new LinearLayout(a);
            txt.setOrientation(LinearLayout.VERTICAL);
            TextView n = Ui.text(a, e.name, 15, Ui.TEXT, true);
            n.setMaxLines(2);
            txt.addView(n);
            String st = e.ready() ? "Offline verfügbar" : e.failed() ? "Fehlgeschlagen"
                    : e.total > 0 ? String.format(Locale.GERMANY, "Lädt … %d %%", e.done * 100 / e.total) : "Wartet …";
            txt.addView(Ui.text(a, st, 12, e.failed() ? 0xFFFF6B6B : Ui.MUTED, false));
            row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
            if (e.ready()) {
                ImageView play = Ui.iconButton(a, "play", 44, true);
                play.setOnClickListener(v -> {
                    android.content.Intent i = new android.content.Intent(a, PlayerActivity.class);
                    i.putExtra("file", e.path).putExtra("json", e.json).putExtra("id", e.id);
                    a.startActivity(i);
                });
                row.addView(play);
            }
            ImageView del = Ui.iconButton(a, "delete", 44, false);
            del.setOnClickListener(v -> { Downloads.delete(a, e); render(); });
            row.addView(del);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(a, 10);
            col.addView(row, lp);
        }
    }
}
