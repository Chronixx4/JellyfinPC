package de.marvin.jellyfin.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import de.marvin.jellyfin.core.Jf;
import de.marvin.jellyfin.core.JfItem;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Einzige Haupt-Activity: verwaltet die Seiten (Start, Bibliothek, Details ...) als Stapel. */
public class MainActivity extends Activity {
    private final ArrayDeque<Screen> stack = new ArrayDeque<>();
    private FrameLayout root;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    Jf jf;
    SharedPreferences prefs;

    interface Work<T> { T run() throws Exception; }
    interface Done<T> { void done(T result); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Icons.init(this);
        jf = Jf.get(this);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        Ui.setTheme(prefs.getString("theme", "Violett"));
        JfItem.hideShort = prefs.getBoolean("hideShort", true);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        setContentView(root);
        if (jf.loggedIn()) push(new HomeScreen(this)); else push(new LoginScreen(this));
        if (prefs.getBoolean("updateAuto", true)) Updater.check(this, false);
        SyncPlay.ui = new SyncPlay.Ui() {
            @Override public void start(String itemId, long startTicks) { syncStart(itemId, startTicks); }
            @Override public void toast(String s) { MainActivity.this.toast(s); }
        };
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        SyncPlay.ui = null;
        idle.removeCallbacksAndMessages(null);
        pool.shutdown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopSaver(false);
        idle.removeCallbacksAndMessages(null);
        Screen top = stack.peek();
        if (top != null) top.onHidden();
    }

    @Override
    protected void onResume() {
        super.onResume();
        touchIdle();
        Screen top = stack.peek();
        if (top != null && stack.size() > 0) top.refresh();
    }

    // ------------------------------------------------------------ Navigation

    void push(Screen s) {
        Screen old = stack.peek();
        if (old != null) { old.onHidden(); old.view.setVisibility(View.GONE); }
        stack.push(s);
        s.view = s.build();
        root.addView(s.view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void pop() {
        if (stack.size() <= 1) { finish(); return; }
        Screen s = stack.pop();
        s.onHidden();
        root.removeView(s.view);
        Screen top = stack.peek();
        top.view.setVisibility(View.VISIBLE);
        top.refresh();
    }

    /** Alles schliessen und mit einer neuen Startseite beginnen (nach An-/Abmelden). */
    void reset(Screen s) {
        for (Screen o : stack) o.onHidden();
        stack.clear();
        root.removeAllViews();
        push(s);
    }

    @Override
    public void onBackPressed() {
        Screen top = stack.peek();
        if (top != null && top.onBack()) return;
        pop();
    }

    // ------------------------------------------------------------ Hilfen

    /** Netzwerkarbeit im Hintergrund, Ergebnis im UI-Thread. Bei abgelaufenem Login zurueck zur Anmeldung. */
    <T> void bg(Work<T> w, Done<T> d) { bg(w, d, false); }

    <T> void bg(Work<T> w, Done<T> d, boolean quiet) {
        pool.execute(() -> {
            try {
                T r = w.run();
                runOnUiThread(() -> { if (!isFinishing()) d.done(r); });
            } catch (Jf.AuthException e) {
                runOnUiThread(() -> {
                    jf.logout();
                    toast("Anmeldung abgelaufen");
                    reset(new LoginScreen(this));
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (!isFinishing()) { if (!quiet) toast("Fehler: " + e.getMessage()); d.done(null); } });
            }
        });
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    void openItem(JfItem it) {
        if (it == null) return;
        if ("Playlist".equals(it.type)) { push(new ListScreen(this, it.name, (j, s) -> j.playlistItems(it.id, s, GridScreen.PAGE))); return; }
        if ("BoxSet".equals(it.type)) { push(new ListScreen(this, it.name, (j, s) -> j.children(it.id, s, GridScreen.PAGE, "SortName", "Ascending"))); return; }
        if (it.collectionType != null && !it.collectionType.isEmpty() || "CollectionFolder".equals(it.type)
                || "UserView".equals(it.type)) push(new LibraryScreen(this, it));
        else push(new DetailScreen(this, it));
    }

    /** In einer Gemeinsam-schauen-Gruppe startet der Server den Titel bei allen, sonst direkt abspielen. */
    void play(JfItem it, long startTicks) {
        SyncPlay sp = SyncPlay.get(this);
        if (sp.inGroup()) {
            bg(() -> { sp.setQueue(it, startTicks); return true; }, r -> { });
            return;
        }
        playNow(it.id, startTicks, false);
    }

    /** Die Gruppe hat einen Titel gestartet: Player oeffnen (pausiert, bis alle bereit sind). */
    private void syncStart(String itemId, long startTicks) {
        if (PlayerActivity.current != null) PlayerActivity.current.finish();
        playNow(itemId, startTicks, true);
    }

    private void playNow(String id, long startTicks, boolean sync) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("id", id);
        i.putExtra("start", startTicks);
        i.putExtra("sync", sync);
        startActivity(i);
    }

    // ------------------------------------------------------------ Bildschirmschoner

    private final Handler idle = new Handler(Looper.getMainLooper());
    private Screensaver saver;

    void touchIdle() {
        idle.removeCallbacksAndMessages(null);
        if (!prefs.getBoolean("screensaver", true)) return;
        idle.postDelayed(this::startSaver, prefs.getInt("saverSec", 180) * 1000L);
    }

    void startSaver() {
        if (saver != null || isFinishing() || !(stack.peek() instanceof HomeScreen)) { touchIdle(); return; }
        saver = new Screensaver(this);
        root.addView(saver.view, new FrameLayout.LayoutParams(-1, -1));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Screen top = stack.peek();
        if (top != null) top.onHidden();
    }

    /** @return true, wenn der Bildschirmschoner lief und jetzt beendet wurde. */
    boolean stopSaver(boolean rearm) {
        if (saver == null) return false;
        saver.stop();
        root.removeView(saver.view);
        saver = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Screen top = stack.peek();
        if (top != null) top.refresh();
        if (rearm) touchIdle();
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (stopSaver(true)) return true;
            touchIdle();
        }
        if (saver != null) return true;
        return super.dispatchTouchEvent(e);
    }

    // ------------------------------------------------------------ Controller

    /** Controller-Tasten: A = OK, B = Zurueck, Y = Suche, Start = Konto. Pfeiltasten/Steuerkreuz bewegen den Fokus. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (stopSaver(true)) return true;
            touchIdle();
        }
        int map = -1;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A: map = KeyEvent.KEYCODE_DPAD_CENTER; break;
            case KeyEvent.KEYCODE_BUTTON_B: map = KeyEvent.KEYCODE_BACK; break;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && jf.loggedIn()) push(new SearchScreen(this));
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && jf.loggedIn()) push(new AccountScreen(this));
                return true;
            default:
        }
        if (map >= 0) {
            return super.dispatchKeyEvent(new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), map,
                    e.getRepeatCount(), e.getMetaState(), e.getDeviceId(), 0, e.getFlags(), e.getSource()));
        }
        return super.dispatchKeyEvent(e);
    }

    private long stickNext;
    private boolean stickHeld;

    /** Linker Analogstick bewegt den Fokus (wie das Steuerkreuz), mit Wiederholung beim Halten. */
    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0 && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
            int key = -1;
            if (Math.abs(x) > Math.abs(y)) { if (x > 0.6f) key = KeyEvent.KEYCODE_DPAD_RIGHT; else if (x < -0.6f) key = KeyEvent.KEYCODE_DPAD_LEFT; }
            else { if (y > 0.6f) key = KeyEvent.KEYCODE_DPAD_DOWN; else if (y < -0.6f) key = KeyEvent.KEYCODE_DPAD_UP; }
            if (key < 0) { stickHeld = false; return true; }
            long now = System.currentTimeMillis();
            if (now >= stickNext) {
                stickNext = now + (stickHeld ? 140 : 380);
                stickHeld = true;
                dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
                dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
            }
            return true;
        }
        return super.onGenericMotionEvent(e);
    }
}
