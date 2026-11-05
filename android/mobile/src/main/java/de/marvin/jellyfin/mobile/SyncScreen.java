package de.marvin.jellyfin.mobile;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Gemeinsam schauen (SyncPlay): Gruppe erstellen oder beitreten, dann starten alle denselben Titel zur selben Zeit. */
final class SyncScreen extends Screen {
    private LinearLayout body;
    private final Handler poll = new Handler(Looper.getMainLooper());
    private int shown = -1;
    private boolean running;
    private SyncPlay sp;

    SyncScreen(MainActivity a) { super(a); }

    @Override
    View build() {
        sp = SyncPlay.get(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 4));
        android.widget.ImageView back = Ui.iconButton(a, "back", 44, false);
        back.setOnClickListener(v -> a.pop());
        bar.addView(back);
        bar.addView(Ui.text(a, "Gemeinsam schauen", 22, Ui.TEXT, true));
        root.addView(bar);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 24));
        col.addView(Ui.text(a, "Alle in einer Gruppe sehen denselben Titel zur selben Zeit. Pause und Spulen gelten für alle.", 14, Ui.MUTED, false));
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, Ui.dp(a, 16), 0, 0);
        col.addView(body);
        ScrollView sv = new ScrollView(a);
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        rebuild();
        running = true;
        tick();
        return root;
    }

    private void tick() {
        poll.postDelayed(() -> {
            if (!running) return;
            if (shown != sp.version) rebuild();
            tick();
        }, 600);
    }

    @Override void onHidden() { running = false; poll.removeCallbacksAndMessages(null); }

    @Override void refresh() { if (!running) { running = true; tick(); } }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(Ui.dp(a, 18), Ui.dp(a, 16), Ui.dp(a, 18), Ui.dp(a, 16));
        c.setBackground(Ui.round(Ui.CARD, 16, a));
        return c;
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(a, 10);
        return lp;
    }

    void rebuild() {
        shown = sp.version;
        body.removeAllViews();
        if (sp.inGroup()) {
            LinearLayout c = card();
            c.addView(Ui.text(a, sp.groupName, 20, Ui.TEXT, true));
            c.addView(Ui.text(a, "Dabei: " + (sp.participants.isEmpty() ? "nur du" : String.join(", ", sp.participants)), 14, Ui.MUTED, false));
            TextView hint = Ui.text(a, "Wähle jetzt irgendeinen Film oder eine Folge und tippe auf Abspielen — es startet bei allen.", 14, Ui.MUTED, false);
            hint.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 12));
            c.addView(hint);
            Ui.Btn leave = Ui.button(a, "logout", "Gruppe verlassen", false);
            leave.setOnClickListener(v -> a.bg(() -> { sp.leave(); return true; }, r -> { a.toast("Gruppe verlassen"); rebuild(); }));
            c.addView(leave);
            body.addView(c, new LinearLayout.LayoutParams(-1, -2));
        } else {
            LinearLayout row = new LinearLayout(a);
            Ui.Btn create = Ui.button(a, "add", "Neue Gruppe", true);
            create.setOnClickListener(v -> {
                EditText e = new EditText(a);
                e.setHint(a.jf.userName + "s Gruppe");
                new android.app.AlertDialog.Builder(a).setTitle("Neue Gruppe").setView(e)
                        .setPositiveButton("Erstellen", (d, w) -> {
                            String n = e.getText().toString().trim();
                            String name = n.isEmpty() ? a.jf.userName + "s Gruppe" : n;
                            a.bg(() -> { sp.create(name); return true; }, r -> { });
                        }).setNegativeButton("Abbrechen", null).show();
            });
            Ui.Btn refresh = Ui.button(a, "update", "Aktualisieren", false);
            refresh.setOnClickListener(v -> rebuild());
            row.addView(create);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
            rl.leftMargin = Ui.dp(a, 10);
            row.addView(refresh, rl);
            body.addView(row);
            LinearLayout groups = new LinearLayout(a);
            groups.setOrientation(LinearLayout.VERTICAL);
            body.addView(groups, gap());
            a.bg(sp::list, arr -> {
                groups.removeAllViews();
                if (arr == null) { groups.addView(Ui.text(a, "Gruppen konnten nicht geladen werden.", 14, Ui.MUTED, false)); return; }
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("GroupId");
                    JSONArray ps = o.optJSONArray("Participants");
                    StringBuilder who = new StringBuilder();
                    for (int k = 0; ps != null && k < ps.length(); k++) who.append(k > 0 ? ", " : "").append(ps.optString(k));
                    LinearLayout c = card();
                    c.addView(Ui.text(a, o.optString("GroupName"), 17, Ui.TEXT, true));
                    c.addView(Ui.text(a, (ps == null ? 0 : ps.length()) + " Teilnehmer" + (who.length() > 0 ? ": " + who : ""), 13, Ui.MUTED, false));
                    Ui.Btn j = Ui.button(a, "Beitreten", true);
                    j.setOnClickListener(v -> a.bg(() -> { sp.join(id); return true; }, r -> { }));
                    LinearLayout.LayoutParams jl = new LinearLayout.LayoutParams(-2, -2);
                    jl.topMargin = Ui.dp(a, 10);
                    c.addView(j, jl);
                    groups.addView(c, gap());
                }
                if (arr.length() == 0) groups.addView(Ui.text(a, "Noch keine Gruppen. Erstelle die erste und lade andere ein, ihr beizutreten.", 14, Ui.MUTED, false));
            });
        }
    }
}
