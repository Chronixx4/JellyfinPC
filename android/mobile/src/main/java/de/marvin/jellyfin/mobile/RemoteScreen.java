package de.marvin.jellyfin.mobile;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import de.marvin.jellyfin.core.Jf;

import java.util.ArrayList;

/** Handy als Fernbedienung: steuert die Wiedergabe auf PC, TV oder anderen Jellyfin-Geraeten. */
final class RemoteScreen extends Screen {
    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout devices, panel;
    private TextView empty, title, sub, time;
    private ImageView art, playPause, muteBtn;
    private SeekBar seek, volume;
    private boolean running, dragging, volDragging, polling;
    private ArrayList<Jf.RemoteSession> sessions = new ArrayList<>();
    private String selectedDevice;
    private Jf.RemoteSession cur;
    private long baseTicks, baseAt;
    private String shownItem = "";

    RemoteScreen(MainActivity a) {
        super(a);
        selectedDevice = a.prefs.getString("remoteDevice", null);
    }

    /** Ziel-Auswahl fuer "Auf Gerät abspielen": zeigt eine Liste und startet den Titel dort. */
    static void pickAndPlay(MainActivity a, de.marvin.jellyfin.core.JfItem it, long startTicks) {
        a.bg(() -> a.jf.remoteSessions(), list -> {
            if (list == null) return;
            if (list.isEmpty()) {
                a.toast("Kein Gerät gefunden. Die PC-App (ab 3.1) muss geöffnet sein.");
                return;
            }
            String[] names = new String[list.size()];
            for (int i = 0; i < names.length; i++) names[i] = list.get(i).label();
            new android.app.AlertDialog.Builder(a).setTitle("Abspielen auf …").setItems(names, (d, w) -> {
                Jf.RemoteSession s = list.get(w);
                a.prefs.edit().putString("remoteDevice", s.deviceName).apply();
                a.bg(() -> { a.jf.remotePlay(s.id, it.id, startTicks); return true; }, ok -> {
                    if (ok != null) { a.toast("Gestartet auf " + s.deviceName); a.push(new RemoteScreen(a)); }
                });
            }).setNegativeButton("Abbrechen", null).show();
        });
    }

    @Override
    View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 4));
        ImageView back = Ui.iconButton(a, "back", 44, false);
        back.setOnClickListener(v -> a.pop());
        bar.addView(back);
        bar.addView(Ui.text(a, "Fernbedienung", 22, Ui.TEXT, true));
        root.addView(bar);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 20);
        col.setPadding(pad, 0, pad, pad);

        col.addView(Ui.text(a, "Geräte", 14, Ui.MUTED, true));
        devices = new LinearLayout(a);
        devices.setOrientation(LinearLayout.VERTICAL);
        col.addView(devices);
        empty = Ui.text(a, "Suche Geräte …", 15, Ui.MUTED, false);
        empty.setPadding(0, Ui.dp(a, 8), 0, 0);
        col.addView(empty);

        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16));
        panel.setBackground(Ui.round(Ui.CARD, 18, a));
        panel.setVisibility(View.GONE);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
        pl.topMargin = Ui.dp(a, 18);
        col.addView(panel, pl);

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        art = new ImageView(a);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.round(Ui.CARD2, 10, a));
        top.addView(art, new LinearLayout.LayoutParams(Ui.dp(a, 64), Ui.dp(a, 96)));
        LinearLayout tx = new LinearLayout(a);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(Ui.dp(a, 14), 0, 0, 0);
        title = Ui.text(a, "", 18, Ui.TEXT, true);
        sub = Ui.text(a, "", 14, Ui.MUTED, false);
        tx.addView(title);
        tx.addView(sub);
        top.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(top);

        seek = new SeekBar(a);
        seek.setMax(1000);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && cur != null) time.setText(fmt(cur.runTicks / 1000 * p) + " / " + fmt(cur.runTicks));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (cur == null || cur.runTicks <= 0) return;
                long t = cur.runTicks / 1000 * s.getProgress();
                baseTicks = t;
                baseAt = System.currentTimeMillis();
                send("Seek", t);
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = Ui.dp(a, 14);
        panel.addView(seek, sl);
        time = Ui.text(a, "0:00 / 0:00", 13, Ui.MUTED, false);
        time.setGravity(Gravity.END);
        panel.addView(time);

        LinearLayout ctl = new LinearLayout(a);
        ctl.setGravity(Gravity.CENTER);
        ImageView back10 = Ui.iconButton(a, "rewind", 52, true);
        back10.setOnClickListener(v -> skip(-10));
        playPause = Ui.iconButton(a, "pause", 64, true);
        playPause.setOnClickListener(v -> {
            if (cur == null) return;
            send("PlayPause", -1);
            cur.paused = !cur.paused;
            Ui.setIconButton(playPause, cur.paused ? "play" : "pause");
        });
        ImageView fwd = Ui.iconButton(a, "forward", 52, true);
        fwd.setOnClickListener(v -> skip(30));
        ImageView next = Ui.iconButton(a, "next", 52, true);
        next.setOnClickListener(v -> send("NextTrack", -1));
        ImageView stop = Ui.iconButton(a, "close", 52, true);
        stop.setOnClickListener(v -> send("Stop", -1));
        for (View v : new View[]{back10, playPause, fwd, next, stop}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(v.getLayoutParams().width, v.getLayoutParams().height);
            lp.leftMargin = lp.rightMargin = Ui.dp(a, 5);
            ctl.addView(v, lp);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = Ui.dp(a, 10);
        panel.addView(ctl, cl);

        LinearLayout vr = new LinearLayout(a);
        vr.setGravity(Gravity.CENTER_VERTICAL);
        muteBtn = Ui.iconButton(a, "volume", 44, false);
        muteBtn.setOnClickListener(v -> {
            if (cur == null) return;
            cur.muted = !cur.muted;
            muteBtn.setAlpha(cur.muted ? 0.4f : 1f);
            command("ToggleMute", null, null);
        });
        vr.addView(muteBtn);
        volume = new SeekBar(a);
        volume.setMax(100);
        volume.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT2));
        volume.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT2));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { }
            @Override public void onStartTrackingTouch(SeekBar s) { volDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                volDragging = false;
                command("SetVolume", "Volume", String.valueOf(s.getProgress()));
            }
        });
        vr.addView(volume, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-1, -2);
        vl.topMargin = Ui.dp(a, 8);
        panel.addView(vr, vl);

        ScrollView sv = new ScrollView(a);
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        running = true;
        h.post(this::poll);
        h.post(this::tick);
        return root;
    }

    @Override void refresh() {
        if (!running) { running = true; h.post(this::poll); h.post(this::tick); }
    }

    private boolean visible() { return view != null && view.isAttachedToWindow() && view.getVisibility() == View.VISIBLE; }

    private void poll() {
        if (!visible()) { running = false; return; }
        if (!polling) {
            polling = true;
            a.bg(() -> a.jf.remoteSessions(), list -> {
                polling = false;
                if (list != null) { sessions = list; render(); }
            }, true);
        }
        h.postDelayed(this::poll, 2000);
    }

    /** Jede Sekunde den Fortschritt anhand der letzten bekannten Position weiterlaufen lassen. */
    private void tick() {
        if (!visible()) return;
        if (cur != null && cur.playing && cur.runTicks > 0 && !dragging) {
            long t = baseTicks + (cur.paused ? 0 : (System.currentTimeMillis() - baseAt) * 10000);
            t = Math.max(0, Math.min(t, cur.runTicks));
            seek.setProgress((int) (t * 1000 / cur.runTicks));
            time.setText(fmt(t) + " / " + fmt(cur.runTicks));
        }
        h.postDelayed(this::tick, 1000);
    }

    private void render() {
        devices.removeAllViews();
        empty.setVisibility(sessions.isEmpty() ? View.VISIBLE : View.GONE);
        if (sessions.isEmpty()) {
            empty.setText("Kein Gerät gefunden. Öffne die PC-App (ab Version 3.1) auf dem PC; sie erscheint dann hier automatisch.");
            panel.setVisibility(View.GONE);
            cur = null;
            return;
        }
        Jf.RemoteSession sel = null;
        for (Jf.RemoteSession s : sessions) if (s.deviceName.equals(selectedDevice)) sel = s;
        if (sel == null) sel = sessions.get(0);
        selectedDevice = sel.deviceName;
        for (Jf.RemoteSession s : sessions) {
            boolean on = s == sel;
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
            row.setBackground(on ? Ui.gradient(a, 14) : Ui.outlined(a, 0x14FFFFFF, Ui.LINE, 14));
            row.addView(Ui.icon(a, "tv", 24, android.graphics.Color.WHITE));
            LinearLayout tx = new LinearLayout(a);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.setPadding(Ui.dp(a, 12), 0, 0, 0);
            tx.addView(Ui.text(a, s.deviceName, 16, android.graphics.Color.WHITE, true));
            tx.addView(Ui.text(a, s.playing ? "Spielt: " + s.itemName : "Bereit", 13, 0xCCFFFFFF, false));
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(v -> {
                selectedDevice = s.deviceName;
                a.prefs.edit().putString("remoteDevice", s.deviceName).apply();
                render();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(a, 8);
            devices.addView(row, lp);
        }
        show(sel);
    }

    private void show(Jf.RemoteSession s) {
        cur = s;
        panel.setVisibility(View.VISIBLE);
        if (!s.playing) {
            title.setText("Nichts wird abgespielt");
            sub.setText("Wähle auf der Detailseite eines Titels „Auf Gerät abspielen“.");
            time.setText("");
            seek.setProgress(0);
            seek.setEnabled(false);
            art.setImageDrawable(null);
            shownItem = "";
            return;
        }
        seek.setEnabled(s.runTicks > 0);
        title.setText(s.itemName);
        sub.setText(s.subtitle == null || s.subtitle.isEmpty() ? (s.paused ? "Pausiert" : "Läuft") : s.subtitle + (s.paused ? "  ·  Pausiert" : ""));
        if (!s.itemId.equals(shownItem)) { shownItem = s.itemId; Ui.load(art, a.jf.itemImage(s.itemId)); }
        Ui.setIconButton(playPause, s.paused ? "play" : "pause");
        // Nur uebernehmen, wenn der Nutzer gerade nichts verstellt
        if (!dragging) { baseTicks = s.positionTicks; baseAt = System.currentTimeMillis(); }
        if (!volDragging && s.volume >= 0) volume.setProgress(s.volume);
        muteBtn.setAlpha(s.muted ? 0.4f : 1f);
    }

    private void skip(int seconds) {
        if (cur == null || !cur.playing) return;
        long t = baseTicks + (cur.paused ? 0 : (System.currentTimeMillis() - baseAt) * 10000) + seconds * 10000000L;
        t = Math.max(0, cur.runTicks > 0 ? Math.min(t, cur.runTicks) : t);
        baseTicks = t;
        baseAt = System.currentTimeMillis();
        send("Seek", t);
    }

    private void send(String cmd, long ticks) {
        if (cur == null) return;
        String id = cur.id;
        a.bg(() -> { a.jf.remotePlaystate(id, cmd, ticks); return true; }, ok -> h.postDelayed(this::pollNow, 700));
    }

    private void command(String name, String k, String v) {
        if (cur == null) return;
        String id = cur.id;
        a.bg(() -> { a.jf.remoteCommand(id, name, k, v); return true; }, ok -> { });
    }

    private void pollNow() {
        if (!visible() || polling) return;
        polling = true;
        a.bg(() -> a.jf.remoteSessions(), list -> {
            polling = false;
            if (list != null) { sessions = list; render(); }
        }, true);
    }

    private static String fmt(long ticks) {
        long s = Math.max(0, ticks / 10000000L);
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }
}
