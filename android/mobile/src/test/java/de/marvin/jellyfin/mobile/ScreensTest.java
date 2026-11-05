package de.marvin.jellyfin.mobile;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.net.ServerSocket;
import java.net.Socket;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Startet die echten Seiten gegen einen kleinen Mock-Jellyfin-Server und prueft, dass sie sich aufbauen. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h851dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class ScreensTest {
    private ServerSocket srv;
    private volatile boolean running = true;

    private static final String MOVIE = "{\"Id\":\"m1\",\"Name\":\"Testfilm\",\"Type\":\"Movie\",\"ProductionYear\":2020,"
            + "\"RunTimeTicks\":72000000000,\"CommunityRating\":7.5,\"OfficialRating\":\"FSK 12\","
            + "\"Overview\":\"Eine Beschreibung.\",\"BackdropImageTags\":[\"b\"],\"Genres\":[\"Action\"],\"ImageTags\":{\"Primary\":\"x\"},"
            + "\"UserData\":{\"PlaybackPositionTicks\":6000000000,\"Played\":false,\"IsFavorite\":false},"
            + "\"People\":[{\"Id\":\"p1\",\"Name\":\"Max Muster\",\"Role\":\"Held\",\"Type\":\"Actor\"}],"
            + "\"Chapters\":[{\"Name\":\"Intro\",\"StartPositionTicks\":0},{\"Name\":\"Mitte\",\"StartPositionTicks\":6000000000}],"
            + "\"MediaSources\":[{\"Container\":\"mkv\",\"Size\":4000000000,\"MediaStreams\":["
            + "{\"Type\":\"Video\",\"Codec\":\"hevc\",\"Width\":1920,\"Height\":1080},"
            + "{\"Type\":\"Audio\",\"Codec\":\"eac3\",\"Channels\":6,\"Language\":\"ger\"}]}]}";
    private static final String SERIES = "{\"Id\":\"s1\",\"Name\":\"Testserie\",\"Type\":\"Series\",\"ProductionYear\":2019}";
    private static final String SEASON = "{\"Id\":\"se1\",\"Name\":\"Staffel 1\",\"Type\":\"Season\"}";
    private static final String EP = "{\"Id\":\"e1\",\"Name\":\"Pilot\",\"Type\":\"Episode\",\"SeriesId\":\"s1\",\"SeasonId\":\"se1\","
            + "\"SeriesName\":\"Testserie\",\"ParentIndexNumber\":1,\"IndexNumber\":1,\"RunTimeTicks\":18000000000}";

    @Before
    public void setUp() throws Exception {
        srv = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        Thread th = new Thread(() -> {
            while (running) {
                try {
                    Socket s = srv.accept();
                    new Thread(() -> serve(s)).start();
                } catch (Exception e) { return; }
            }
        });
        th.setDaemon(true);
        th.start();
        Context c = RuntimeEnvironment.getApplication();
        c.getSharedPreferences("jellyfin", Context.MODE_PRIVATE).edit()
                .putString("server", "http://127.0.0.1:" + srv.getLocalPort())
                .putString("token", "t").putString("userId", "u1").putString("userName", "Tester").apply();
    }

    @After
    public void tearDown() throws Exception { running = false; srv.close(); }

    private static String route(String p) {
        if (p.startsWith("/Items/Latest")) return "[" + MOVIE + "]";
        if (p.equals("/UserViews")) return "{\"Items\":[{\"Id\":\"lib1\",\"Name\":\"Filme\",\"Type\":\"CollectionFolder\",\"CollectionType\":\"movies\"}]}";
        if (p.endsWith("/Items/m1")) return MOVIE;
        if (p.endsWith("/Items/s1")) return SERIES;
        if (p.endsWith("/Items/e1")) return EP;
        if (p.equals("/Shows/s1/Seasons")) return "{\"Items\":[" + SEASON + "]}";
        if (p.equals("/Shows/s1/Episodes")) return "{\"Items\":[" + EP + "]}";
        if (p.equals("/Shows/NextUp")) return "{\"Items\":[" + EP + "]}";
        if (p.contains("/Resume")) return "{\"Items\":[" + MOVIE + "]}";
        if (p.equals("/Items")) return "{\"Items\":[" + MOVIE + "],\"TotalRecordCount\":1}";
        if (p.startsWith("/Items/") && p.endsWith("/Similar")) return "{\"Items\":[" + MOVIE + "]}";
        if (p.startsWith("/Genres")) return "{\"Items\":[{\"Name\":\"Action\"}]}";
        if (p.startsWith("/MediaSegments")) return "{\"Items\":[{\"Type\":\"Intro\",\"StartTicks\":0,\"EndTicks\":300000000}]}";
        if (p.equals("/SyncPlay/List")) return "[{\"GroupId\":\"g1\",\"GroupName\":\"Filmabend\",\"Participants\":[\"Anna\"]}]";
        if (p.contains("/Images/")) return null;
        return "{\"Items\":[]}";
    }

    private static void serve(Socket s) {
        try (Socket sock = s) {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(sock.getInputStream()));
            String line = r.readLine();
            String l;
            while ((l = r.readLine()) != null && !l.isEmpty()) { }
            String path = line.split(" ")[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            String body = route(path);
            byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            String head = (body == null ? "HTTP/1.1 404 Not Found" : "HTTP/1.1 200 OK") + "\r\nContent-Type: application/json\r\nContent-Length: "
                    + b.length + "\r\nConnection: close\r\n\r\n";
            sock.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));
            sock.getOutputStream().write(b);
            sock.getOutputStream().flush();
        } catch (Exception ignored) { }
    }

    private static void texts(View v, StringBuilder out) {
        if (v instanceof TextView) out.append(((TextView) v).getText()).append('\n');
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) texts(((ViewGroup) v).getChildAt(i), out);
    }

    private static boolean hasView(View v, Class<?> c) {
        if (c.isInstance(v)) return true;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) if (hasView(((ViewGroup) v).getChildAt(i), c)) return true;
        return false;
    }

    private static String screenText(Activity a) {
        StringBuilder b = new StringBuilder();
        texts(a.getWindow().getDecorView(), b);
        return b.toString();
    }

    private static void waitFor(BooleanSupplier c, String what) throws Exception {
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle();
            if (c.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Zeitueberschreitung: " + what);
    }

    @Test
    public void loginScreenBuilds() {
        RuntimeEnvironment.getApplication().getSharedPreferences("jellyfin", Context.MODE_PRIVATE).edit().remove("token").apply();
        // Jf ist ein Singleton und kennt den Login noch nicht -> frischer Zustand ueber Reflection
        resetJf();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertTrue(screenText(a).contains("Anmelden"));
        assertTrue(screenText(a).contains("Quick Connect"));
    }

    private static void resetJf() {
        try {
            java.lang.reflect.Field f = de.marvin.jellyfin.core.Jf.class.getDeclaredField("instance");
            f.setAccessible(true);
            f.set(null, null);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    public void homeLibraryDetailAndSeries() throws Exception {
        resetJf();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        waitFor(() -> screenText(a).contains("Weiter schauen") && screenText(a).contains("Neu in Filme"), "Startseite");
        assertTrue(screenText(a).contains("Testfilm"));

        // Film-Details
        a.openItem(de.marvin.jellyfin.core.JfItem.from(new org.json.JSONObject(MOVIE)));
        waitFor(() -> screenText(a).contains("Max Muster") && screenText(a).contains("Fortsetzen"), "Filmdetails");
        assertTrue(screenText(a).contains("Eine Beschreibung."));
        assertTrue(screenText(a).contains("Ähnliche Titel"));
        a.pop();

        // Bibliothek
        a.openItem(de.marvin.jellyfin.core.JfItem.from(new org.json.JSONObject(
                "{\"Id\":\"lib1\",\"Name\":\"Filme\",\"Type\":\"CollectionFolder\",\"CollectionType\":\"movies\"}")));
        waitFor(() -> screenText(a).contains("Testfilm") && screenText(a).contains("Ungesehen"), "Bibliothek");
        a.pop();

        // Serie
        a.openItem(de.marvin.jellyfin.core.JfItem.from(new org.json.JSONObject(SERIES)));
        waitFor(() -> screenText(a).contains("Staffel 1") && screenText(a).contains("Pilot"), "Serie mit Folgen");
        a.pop();

        // Suche + Konto
        a.push(new SearchScreen(a));
        waitFor(() -> screenText(a).contains("Suche"), "Suche");
        a.pop();
        a.push(new StatsScreen(a));
        waitFor(() -> screenText(a).contains("Deine Statistik"), "Statistik");
        a.pop();
        a.push(new Lists.ListsScreen(a));
        waitFor(() -> screenText(a).contains("Wiedergabelisten") && screenText(a).contains("Sammlungen"), "Listen");
        a.pop();
        a.push(new DownloadsScreen(a));
        waitFor(() -> screenText(a).contains("Noch nichts geladen"), "Downloads");
        a.pop();
        a.push(new AccountScreen(a));
        waitFor(() -> screenText(a).contains("Abmelden"), "Konto");
    }

    private static void shot(Activity a, String name) throws Exception {
        View v = a.getWindow().getDecorView();
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, dm.widthPixels, dm.heightPixels);
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, android.graphics.Bitmap.Config.ARGB_8888);
        v.draw(new android.graphics.Canvas(bm));
        String dir = System.getenv("SHOT_DIR");
        if (dir == null) return;
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(dir + "/" + name + ".png")) {
            bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o);
        }
    }

    @Test
    public void screenshots() throws Exception {
        resetJf();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        waitFor(() -> screenText(a).contains("Neu in Filme"), "Start");
        shot(a, "home");
        a.openItem(de.marvin.jellyfin.core.JfItem.from(new org.json.JSONObject(MOVIE)));
        waitFor(() -> screenText(a).contains("Max Muster"), "Details");
        shot(a, "detail");
        a.pop();
        a.openItem(de.marvin.jellyfin.core.JfItem.from(new org.json.JSONObject(
                "{\"Id\":\"lib1\",\"Name\":\"Filme\",\"Type\":\"CollectionFolder\",\"CollectionType\":\"movies\"}")));
        waitFor(() -> screenText(a).contains("Ungesehen"), "Bibliothek");
        shot(a, "library");
        a.pop();
        a.push(new AccountScreen(a));
        shot(a, "account");
        Intent i = new Intent(RuntimeEnvironment.getApplication(), PlayerActivity.class).putExtra("id", "m1").putExtra("start", 0L);
        PlayerActivity p = Robolectric.buildActivity(PlayerActivity.class, i).setup().get();
        waitFor(() -> screenText(p).contains("Testfilm"), "Player");
        shot(p, "player");
    }

    @Test
    public void themesSyncScreensaver() throws Exception {
        android.animation.ValueAnimator.class.getMethod("setDurationScale", float.class).invoke(null, 0f);   // Animationen sofort fertig, sonst laeuft der Testtakt endlos
        resetJf();
        Context ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean("heroVideo", false).apply();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        waitFor(() -> screenText(a).contains("Neu in Filme"), "Start");

        // Farbdesign: Einstellungen zeigen alle Designs, Auswahl aendert die Akzentfarbe und merkt sie sich
        a.push(new AccountScreen(a));
        waitFor(() -> screenText(a).contains("Farbdesign") && screenText(a).contains("Sonnenuntergang")
                && screenText(a).contains("Gemeinsam schauen") && screenText(a).contains("Bildschirmschoner"), "Konto mit Farbdesign");
        int before = Ui.ACCENT;
        Ui.setTheme("Sonnenuntergang");
        assertTrue(Ui.ACCENT != before);
        Ui.setTheme("Violett");
        assertTrue(Ui.ACCENT == before);

        // Gemeinsam schauen: Gruppenliste vom Server
        SyncScreen ss = new SyncScreen(a);
        a.push(ss);
        waitFor(() -> screenText(a).contains("Neue Gruppe") && screenText(a).contains("Filmabend"), "Gruppenliste");
        shot(a, "sync");

        // Nachrichten des Servers: Gruppe beigetreten -> Anzeige; "Abspielen" geht dann an die Gruppe statt direkt zu starten
        SyncPlay sp = SyncPlay.get(a);
        sp.handle(new org.json.JSONObject("{\"MessageType\":\"SyncPlayGroupUpdate\",\"Data\":{\"Type\":\"GroupJoined\","
                + "\"Data\":{\"GroupId\":\"g1\",\"GroupName\":\"Filmabend\",\"Participants\":[\"Anna\"]}}}"));
        ss.rebuild();   // in der App macht das der 0,6-s-Takt der Seite
        waitFor(() -> screenText(a).contains("Gruppe verlassen") && screenText(a).contains("Anna"), "Gruppenansicht");
        shot(a, "sync-group");
        a.play(de.marvin.jellyfin.core.JfItem.from(new org.json.JSONObject(MOVIE)), 0);
        assertTrue(shadowOf(a).getNextStartedActivity() == null);   // kein lokaler Start in der Gruppe
        // Server startet den Titel: Player oeffnet sich im Gruppenmodus
        sp.handle(new org.json.JSONObject("{\"MessageType\":\"SyncPlayGroupUpdate\",\"Data\":{\"Type\":\"PlayQueue\","
                + "\"Data\":{\"Reason\":\"NewPlaylist\",\"PlayingItemIndex\":0,\"StartPositionTicks\":0,"
                + "\"Playlist\":[{\"ItemId\":\"m1\",\"PlaylistItemId\":\"pl1\"}]}}}"));
        shadowOf(Looper.getMainLooper()).idle();
        Intent started = shadowOf(a).getNextStartedActivity();
        assertTrue(started != null && started.getBooleanExtra("sync", false) && "m1".equals(started.getStringExtra("id")));
        sp.handle(new org.json.JSONObject("{\"MessageType\":\"SyncPlayGroupUpdate\",\"Data\":{\"Type\":\"GroupLeft\"}}"));
        assertTrue(!sp.inGroup());
        a.pop();
        a.pop();

        // Bildschirmschoner: nach Ruhezeit auf der Startseite
        a.startSaver();   // die Zeitschaltung selbst (touchIdle) braucht echte Zeit, hier direkt ausloesen
        waitFor(() -> hasView(a.getWindow().getDecorView(), android.widget.TextClock.class), "Bildschirmschoner (Uhr)");
        shot(a, "saver");
        // Tippen beendet ihn
        a.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 10, 10, 0));
        waitFor(() -> !hasView(a.getWindow().getDecorView(), android.widget.TextClock.class), "Startseite nach Bildschirmschoner");
        // Controller: A-Taste wird zur OK-Taste, Steuerkreuz bewegt den Fokus (kein Absturz)
        a.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BUTTON_A));
        a.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN));
    }

    @Test
    public void playerControllerKeys() throws Exception {
        resetJf();
        Intent i = new Intent(RuntimeEnvironment.getApplication(), PlayerActivity.class).putExtra("id", "m1").putExtra("start", 0L);
        PlayerActivity p = Robolectric.buildActivity(PlayerActivity.class, i).setup().get();
        waitFor(() -> screenText(p).contains("Testfilm"), "Player-Titel");
        assertTrue(p.onKeyDown(android.view.KeyEvent.KEYCODE_BUTTON_A, new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BUTTON_A)));
        assertTrue(p.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)));
        p.finish();
    }

    @Test
    public void playerBuildsAndLoadsItem() throws Exception {
        resetJf();
        Intent i = new Intent(RuntimeEnvironment.getApplication(), PlayerActivity.class).putExtra("id", "m1").putExtra("start", 0L);
        PlayerActivity p = Robolectric.buildActivity(PlayerActivity.class, i).setup().get();
        waitFor(() -> screenText(p).contains("Testfilm"), "Player-Titel");
        p.finish();
    }
}
