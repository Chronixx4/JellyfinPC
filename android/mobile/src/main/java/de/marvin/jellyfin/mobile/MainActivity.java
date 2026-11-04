package de.marvin.jellyfin.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
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
        jf = Jf.get(this);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        JfItem.hideShort = prefs.getBoolean("hideShort", true);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        setContentView(root);
        if (jf.loggedIn()) push(new HomeScreen(this)); else push(new LoginScreen(this));
        if (prefs.getBoolean("updateAuto", true)) Updater.check(this, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Screen top = stack.peek();
        if (top != null && stack.size() > 0) top.refresh();
    }

    // ------------------------------------------------------------ Navigation

    void push(Screen s) {
        Screen old = stack.peek();
        if (old != null) old.view.setVisibility(View.GONE);
        stack.push(s);
        s.view = s.build();
        root.addView(s.view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void pop() {
        if (stack.size() <= 1) { finish(); return; }
        Screen s = stack.pop();
        root.removeView(s.view);
        Screen top = stack.peek();
        top.view.setVisibility(View.VISIBLE);
        top.refresh();
    }

    /** Alles schliessen und mit einer neuen Startseite beginnen (nach An-/Abmelden). */
    void reset(Screen s) {
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
        if (it.collectionType != null && !it.collectionType.isEmpty() || "CollectionFolder".equals(it.type)
                || "UserView".equals(it.type)) push(new LibraryScreen(this, it));
        else push(new DetailScreen(this, it));
    }

    void play(JfItem it, long startTicks) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("id", it.id);
        i.putExtra("start", startTicks);
        startActivity(i);
    }
}
