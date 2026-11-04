package de.marvin.jellyfin.pc;

import javafx.animation.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.geometry.Bounds;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Jellyfin fuer den PC: Anmeldung, Start, Bibliotheken, Suche, Details, Player. */
public final class App extends Application {
    private static final String BG = "#0a0d12", SURFACE = "#141a23", SURFACE2 = "#1c2431";
    private final Jf jf = Jf.get();
    private Stage stage;
    static String A1 = "#8b6cff", A2 = "#19a8ff";
    static boolean REDUCE;
    private boolean heroVideoOn = true;
    private StackPane shell;
    private Pane layer;           // Ebene fuer Vorschau-Karte und Seitenuebergang
    private StackPane previewNode;
    private PauseTransition previewDelay, previewHide;
    private final java.util.prefs.Preferences settings = java.util.prefs.Preferences.userRoot().node("de/marvin/jellyfin/pc/ui");
    private static final String[][] THEMES = {
            {"Violett", "#8b6cff", "#19a8ff"}, {"Ozean", "#19a8ff", "#19e0c0"}, {"Sonnenuntergang", "#ff6b5e", "#ffb347"},
            {"Rose", "#ff5fa2", "#a06bff"}, {"Wald", "#2ecc71", "#19c1c8"}, {"Gold", "#f5b942", "#ff7a45"}};

    /** Eintrag im Windows-Autostart (Benutzerebene) setzen oder entfernen. */
    private boolean setAutostart(boolean on) {
        try {
            String dir = System.getProperty("launch4j.exedir");
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return false;
            ProcessBuilder pb;
            if (on) {
                if (dir == null || dir.isEmpty()) return false;
                java.io.File exe = new java.io.File(dir, "JellyfinPC.exe");
                if (!exe.exists()) return false;
                pb = new ProcessBuilder("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                        "/v", "JellyfinPC", "/t", "REG_SZ", "/d", "\"" + exe.getAbsolutePath() + "\"", "/f");
            } else {
                pb = new ProcessBuilder("reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                        "/v", "JellyfinPC", "/f");
            }
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            pr.getInputStream().readAllBytes();
            return pr.waitFor() == 0 || !on;
        } catch (Exception ex) {
            Log.e("Autostart", ex);
            return false;
        }
    }

    private void applyTheme(String name, boolean rerender) {
        for (String[] t : THEMES) if (t[0].equals(name)) { A1 = t[1]; A2 = t[2]; }
        settings.put("theme", name);
        if (shell != null) shell.setStyle("-accent: " + A1 + "; -accent2: " + A2 + ";");
        if (rerender && player == null && current != null) current.run();
    }

    private BorderPane root;
    private final Label toast = new Label();
    private Timeline toastTl;

    /** Kurze Meldung unten im Bild. */
    void toast(String msg) {
        toast.setText(msg);
        if (toastTl != null) toastTl.stop();
        toastTl = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(toast.opacityProperty(), toast.getOpacity())),
                new KeyFrame(Duration.millis(180), new KeyValue(toast.opacityProperty(), 1)),
                new KeyFrame(Duration.millis(2300), new KeyValue(toast.opacityProperty(), 1)),
                new KeyFrame(Duration.millis(2700), new KeyValue(toast.opacityProperty(), 0)));
        toastTl.play();
    }

    /** Uebersicht aller Tastenkuerzel. */
    void showShortcuts() {
        String[][] k = {
                {"Leertaste", "Pause / Weiter"}, {"← / →", "10 s zurück / 30 s vor"}, {"↑ / ↓", "Lautstärke"},
                {"M", "Ton aus / an"}, {"F oder F11", "Vollbild"}, {"S", "Intro / Abspann überspringen"},
                {"N", "Nächste Folge"}, {"0 – 9", "Zu 0 % – 90 % springen"}, {"+ / −", "Tempo ändern"},
                {"Esc", "Zurück / Vollbild beenden"}, {"F1 oder ?", "Diese Übersicht"},
                {"Rechtsklick", "Menü auf Titeln: Merkliste, gesehen …"}};
        GridPane g = new GridPane();
        g.setHgap(26);
        g.setVgap(10);
        int r = 0;
        for (String[] x : k) {
            Label key = text(x[0], 14, true);
            key.setStyle(key.getStyle() + "-fx-background-color:rgba(255,255,255,0.10);-fx-background-radius:8;-fx-padding:3 10 3 10;");
            g.add(key, 0, r);
            g.add(muted(x[1], 14), 1, r++);
        }
        Dialog<Void> d = new Dialog<>();
        d.initOwner(stage);
        d.setTitle("Tastenkürzel");
        d.setHeaderText("Tastenkürzel");
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.getDialogPane().getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        d.getDialogPane().setStyle("-fx-background-color:#121722;");
        d.show();
    }
    private final Deque<Runnable> history = new ArrayDeque<>();
    private Runnable current;
    private TextField searchField;
    private Player player;
    private ScrollPane pageScroll;
    private Timeline heroTimer;
    private StackPane heroLayer;
    private Node heroInfo;
    private ImageView heroVid;

    public static void main(String[] args) {
        launch(args);
    }

    Stage stage() {
        return stage;
    }

    @Override
    public void start(Stage st) {
        Player.warmUp();
        stage = st;
        for (int n : new int[]{32, 64, 128, 256}) {
            try { st.getIcons().add(new Image(getClass().getResourceAsStream("/icon-" + n + ".png"))); } catch (Exception ignored) { }
        }
        Log.i("Start Jellyfin PC, Java " + System.getProperty("java.version"));
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> Log.e("Unbehandelter Fehler in " + t.getName(), e));
        root = new BorderPane();
        toast.setMouseTransparent(true);
        toast.setOpacity(0);
        toast.getStyleClass().add("toast");
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 44, 0));
        layer = new Pane();
        layer.setPickOnBounds(false);
        shell = new StackPane(root, layer, toast);
        REDUCE = settings.getBoolean("reduce", false);
        JfItem.hideShort = settings.getBoolean("hideShort", true);
        heroVideoOn = settings.getBoolean("heroVideo", true);
        applyTheme(settings.get("theme", "Violett"), false);
        Scene sc = new Scene(shell, 1360, 840);
        sc.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> kbdMode(false));
        sc.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (player == null && sc.getFocusOwner() instanceof ComboBox) return;
            KeyCode kc = e.getCode();
            if ((kc == KeyCode.UP || kc == KeyCode.DOWN || kc == KeyCode.LEFT || kc == KeyCode.RIGHT
                    || kc == KeyCode.BACK_SPACE || kc == KeyCode.ESCAPE) && !e.isControlDown() && !e.isAltDown()) {
                boolean popup = false;
                for (javafx.stage.Window w : javafx.stage.Window.getWindows()) if (w != stage && w.isShowing()) popup = true;
                if (!popup && handleNavKey(kc, false)) { e.consume(); return; }
            }
            boolean typing = sc.getFocusOwner() instanceof TextInputControl;
            if (player == null && searchField != null && !typing
                    && (("/".equals(e.getText())) || (e.getCode() == KeyCode.F && e.isControlDown()))) {
                searchField.requestFocus();
                e.consume();
            }
        });
        sc.setOnKeyPressed(e -> {
            if (player == null && (e.getCode() == KeyCode.F1 || (e.getCode() == KeyCode.SLASH && e.isShiftDown())
                    || e.getText().equals("?")) && !(sc.getFocusOwner() instanceof TextInputControl)) {
                showShortcuts();
                e.consume();
            }
        });
        sc.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        st.setScene(sc);
        st.setTitle("Jellyfin");
        st.setMinWidth(980);
        st.setMinHeight(640);
        st.setFullScreenExitHint("");
        boolean wasMax = settings.getBoolean("winMax", true);
        double ww = settings.getDouble("winW", 0), wh = settings.getDouble("winH", 0);
        if (!wasMax && ww >= 980 && wh >= 640) {
            st.setWidth(ww);
            st.setHeight(wh);
            double wx = settings.getDouble("winX", Double.NaN), wy = settings.getDouble("winY", Double.NaN);
            if (!Double.isNaN(wx) && !Double.isNaN(wy)
                    && !javafx.stage.Screen.getScreensForRectangle(wx, wy, 50, 50).isEmpty()) { st.setX(wx); st.setY(wy); }
        } else st.setMaximized(true);
        st.setOnHiding(e -> {
            if (st.isAlwaysOnTop()) return;
            settings.putBoolean("winMax", st.isMaximized());
            if (!st.isMaximized() && !st.isFullScreen() && st.getWidth() >= 980) {
                settings.putDouble("winW", st.getWidth());
                settings.putDouble("winH", st.getHeight());
                settings.putDouble("winX", st.getX());
                settings.putDouble("winY", st.getY());
            }
        });
        st.show();
        gamepad = new Gamepad(k -> {
            if (k == KeyCode.ESCAPE && player == null && layerHasOverlay()) return;
            handleNavKey(k, true);
        });
        gamepad.start();
        if (!REDUCE && System.getProperty("jf.nosplash") == null) Fx.splash(shell, A1, A2, null);
        if (settings.getBoolean("updateAuto", true) && System.getProperty("jf.noupdate") == null) {
            PauseTransition ud = new PauseTransition(Duration.seconds(REDUCE ? 2 : 6));
            ud.setOnFinished(e -> checkUpdate(true));
            ud.play();
        }
        if (jf.loggedIn()) {
            jf.saveProfile(); // bestehenden Login als Profil merken
            if (System.getProperty("jf.fakeprofile") != null) { // nur zum Testen
                Jf.Profile f = new Jf.Profile();
                f.name = System.getProperty("jf.fakeprofile"); f.userId = "fake"; f.token = "x"; f.server = jf.server;
                java.util.List<Jf.Profile> l = jf.profiles();
                if (l.stream().noneMatch(x -> x.userId.equals("fake"))) { jf.useProfile(f); jf.saveProfile(); jf.useProfile(l.get(0)); }
            }
            boolean multi = jf.profiles().size() > 1;
            Jf.Profile cp = jf.currentProfile();
            boolean pinned = cp != null && !cp.pinHash.isEmpty();
            if ((multi || pinned) && System.getProperty("jf.open") == null && System.getProperty("jf.stats") == null
                    && System.getProperty("jf.lists") == null && System.getProperty("jf.autoplay") == null) profilePicker();
            else home();
            if (System.getProperty("jf.stats") != null) go(this::stats); // nur zum Testen
            if (System.getProperty("jf.dl") != null) { // nur zum Testen
                bg(() -> jf.item(System.getProperty("jf.dl")), full -> Downloads.start(jf, full));
                go(this::downloadsPage);
            }
            if (System.getProperty("jf.sync") != null) go(this::syncPage); // nur zum Testen
            if (System.getProperty("jf.downloads") != null) go(this::downloadsPage);
            if (System.getProperty("jf.lists") != null) go(this::listsPage); // nur zum Testen
            String open = System.getProperty("jf.open"); // nur zum Testen: Detailseite direkt oeffnen
            if (open != null) go(() -> detail(open));
            String auto = System.getProperty("jf.autoplay"); // nur zum Testen
            if (auto != null) bg(() -> jf.item(auto), this::play);
        } else login();
    }

    // ------------------------------------------------------------ Hilfen

    private <T> void bg(Callable<T> work, Consumer<T> ui) {
        Thread t = new Thread(() -> {
            try {
                T r = work.call();
                Platform.runLater(() -> ui.accept(r));
            } catch (Jf.AuthException e) {
                Platform.runLater(() -> { jf.logout(); login(); });
            } catch (Exception e) {
                Platform.runLater(() -> error(e));
            }
        }, "net");
        t.setDaemon(true);
        t.start();
    }

    private StackPane errorCard;
    private Gamepad gamepad;

    private boolean layerHasOverlay() { return errorCard != null; }
    private JfItem currentList;   // geoeffnete Wiedergabeliste bzw. Sammlung (fuer "Aus Liste entfernen")

    /** Fehler als Karte im Programm zeigen, mit "Erneut versuchen". */
    private void error(Exception e) {
        Log.e("Fehler", e);
        releaseVeil(null);
        if (errorCard != null) return;
        String m = String.valueOf(e.getMessage());
        String ml = m.toLowerCase();
        boolean net = e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.UnknownHostException || ml.contains("connection") || ml.contains("timed out");
        Label t = text(net ? "Server nicht erreichbar" : "Da ist etwas schiefgegangen", 20, true);
        Label d = muted(net ? "Läuft dein Jellyfin-Server, und bist du im richtigen Netzwerk bzw. VPN?" : m, 14);
        d.setWrapText(true);
        d.setMaxWidth(420);
        Button retry = button("Erneut versuchen", "btn-primary");
        Button close = button("Schließen", "btn-ghost");
        HBox btns = new HBox(12, retry, close);
        if (net && !Downloads.list().isEmpty()) {
            Button off = button("⬇  Downloads (offline)", "btn-ghost");
            off.setOnAction(x -> { layer.getChildren().remove(errorCard); errorCard = null; go(this::downloadsPage); });
            btns.getChildren().add(1, off);
        }
        VBox box = new VBox(14, t, d, btns);
        box.setPadding(new Insets(26, 30, 26, 30));
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.getStyleClass().add("glass");
        StackPane wrap = new StackPane(box);
        wrap.setStyle("-fx-background-color: rgba(5,7,10,0.55);");
        wrap.setManaged(false);
        wrap.resize(layer.getWidth(), layer.getHeight());
        layer.widthProperty().addListener((o, x, y) -> wrap.resize(y.doubleValue(), layer.getHeight()));
        layer.heightProperty().addListener((o, x, y) -> wrap.resize(layer.getWidth(), y.doubleValue()));
        Runnable dismiss = () -> { layer.getChildren().remove(wrap); errorCard = null; };
        retry.setOnAction(x -> { dismiss.run(); if (current != null) current.run(); else home(); });
        close.setOnAction(x -> dismiss.run());
        errorCard = wrap;
        layer.getChildren().add(wrap);
        FadeTransition ft = new FadeTransition(Duration.millis(200), wrap);
        wrap.setOpacity(0);
        ft.setToValue(1);
        ft.play();
    }

    private static Label label(String t, String styleClass) {
        Label l = new Label(t);
        l.getStyleClass().add(styleClass);
        l.setWrapText(true);
        return l;
    }

    private static Label text(String t, double size, boolean bold) {
        Label l = new Label(t);
        l.setStyle("-fx-font-size:" + size + "px;" + (bold ? "-fx-font-weight:700;" : ""));
        l.setWrapText(true);
        return l;
    }

    private static Label muted(String t, double size) {
        Label l = text(t, size, false);
        l.getStyleClass().add("muted");
        return l;
    }

    private static Button iconBtn(String icon, String text, String cls) {
        Button b = button(text, cls);
        b.setGraphic(Fx.icon(icon, 18, "#ffffff"));
        b.setGraphicTextGap(10);
        return b;
    }

    private static Button button(String t, String cls) {
        Button b = new Button(t);
        b.getStyleClass().add(cls);
        pressable(b);
        return b;
    }

    /** Knopf wird beim Ueberfahren groesser und beim Klicken kurz kleiner. */
    private static void pressable(Node b) {
        ScaleTransition st = new ScaleTransition(Duration.millis(120), b);
        st.setInterpolator(Interpolator.EASE_OUT);
        b.setOnMouseEntered(e -> { st.stop(); st.setToX(1.06); st.setToY(1.06); st.play(); });
        b.setOnMouseExited(e -> { st.stop(); st.setToX(1); st.setToY(1); st.play(); });
        b.setOnMousePressed(e -> { st.stop(); st.setToX(0.95); st.setToY(0.95); st.play(); });
        b.setOnMouseReleased(e -> { st.stop(); st.setToX(b.isHover() ? 1.06 : 1); st.setToY(b.isHover() ? 1.06 : 1); st.play(); });
    }

    /** Einblenden mit leichtem Hochgleiten, zeitversetzt nach Position. */
    private static void reveal(Node n, int index) {
        if (REDUCE) return;
        n.setOpacity(0);
        n.setTranslateY(24);
        Duration delay = Duration.millis(Math.min(index, 14) * 55);
        FadeTransition f = new FadeTransition(Duration.millis(460), n);
        f.setToValue(1);
        f.setDelay(delay);
        TranslateTransition t = new TranslateTransition(Duration.millis(520), n);
        t.setToY(0);
        t.setInterpolator(Interpolator.EASE_OUT);
        t.setDelay(delay);
        new ParallelTransition(f, t).play();
    }

    private static Node loader() {
        return Fx.skeleton();
    }

    /** Neue Ansicht anzeigen und die vorherige auf den Verlauf legen. */
    private void go(Runnable screen) {
        if (current != null) history.push(current);
        current = screen;
        navDir = 1;
        screen.run();
        navDir = 0;
    }

    void back() {
        if (player != null) { player = null; stage.setFullScreen(false); }
        if (!history.isEmpty()) {
            current = history.pop();
            navDir = -1;
            current.run();
            navDir = 0;
        } else {
            history.clear();
            current = null;
            home();
        }
    }

    private int navDir;

    /** Wird vom Player aufgerufen, wenn er sich selbst beendet hat (z. B. vor der naechsten Folge). */
    void playerEnded() {
        invalidateCaches();
        player = null;
        if (!history.isEmpty()) current = history.pop();
    }

    private void setContent(Node n) {
        HeroVideo.stop();
        hidePreviewNow();
        if (REDUCE || veil != null) { root.setCenter(n); return; }
        int dir = navDir;
        navDir = 0;
        n.setOpacity(0);
        if (dir != 0) n.setTranslateX(dir * 46); else n.setTranslateY(18);
        root.setCenter(n);
        FadeTransition ft = new FadeTransition(Duration.millis(300), n);
        ft.setToValue(1);
        TranslateTransition tt = new TranslateTransition(Duration.millis(380), n);
        if (dir != 0) tt.setToX(0); else tt.setToY(0);
        tt.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(ft, tt).play();
    }

    private ScrollPane scroll(Node n) {
        ScrollPane sp = new ScrollPane(n);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // Leiste immer reservieren: so aendert sich die Inhaltsbreite nie, wenn die Seite waechst (kein Zusammenschieben)
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        Timeline[] anim = {null};
        double[] target = {0};
        sp.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (Math.abs(e.getDeltaY()) <= Math.abs(e.getDeltaX())) return;
            double h = sp.getContent().getBoundsInLocal().getHeight() - sp.getViewportBounds().getHeight();
            if (h > 0) {
                if (anim[0] == null || anim[0].getStatus() != Animation.Status.RUNNING) target[0] = sp.getVvalue();
                target[0] = Math.max(0, Math.min(1, target[0] - e.getDeltaY() * 2.6 / h));
                if (anim[0] != null) anim[0].stop();
                anim[0] = new Timeline(new KeyFrame(Duration.millis(260),
                        new KeyValue(sp.vvalueProperty(), target[0], Interpolator.EASE_OUT)));
                anim[0].play();
            }
            e.consume();
        });
        sp.vvalueProperty().addListener((o, x, y) -> hidePreviewNow());
        pageScroll = sp;
        return sp;
    }

    // ------------------------------------------------------------ Bilder

    /** Waehrend Karten fuer lange Listen gebaut werden: Bilder erst laden, wenn sie in die Naehe des Sichtbereichs kommen. */
    private java.util.List<StackPane> lazyActive;
    private final java.util.ArrayList<StackPane> lazyPending = new java.util.ArrayList<>();

    private void lazyCheck(ScrollPane sp) {
        if (lazyPending.isEmpty() || sp == null || sp.getScene() == null) return;
        javafx.geometry.Bounds vp = sp.localToScene(sp.getBoundsInLocal());
        java.util.Iterator<StackPane> it = lazyPending.iterator();
        while (it.hasNext()) {
            StackPane b = it.next();
            if (b.getScene() == null) { it.remove(); continue; }
            javafx.geometry.Bounds bb = b.localToScene(b.getBoundsInLocal());
            if (bb.getMaxY() > vp.getMinY() - 1000 && bb.getMinY() < vp.getMaxY() + 1000) {
                it.remove();
                Object r = b.getProperties().remove("lazyLoad");
                if (r instanceof Runnable) ((Runnable) r).run();
            }
        }
    }

    /** Bilder-Zwischenspeicher: beim Zurueckwechseln erscheinen Poster sofort, ohne neu vom Server zu laden. */
    private static final java.util.Map<String, Image> IMG_CACHE = new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Image> e) { return size() > 140; }
    };

    /** Bild mit abgerundeten Ecken, zugeschnitten auf w x h, mit Einblenden. */
    private StackPane picture(String url, double w, double h, double radius, double cropY) {
        StackPane box = new StackPane();
        box.setMinSize(w, h);
        box.setPrefSize(w, h);
        box.setMaxSize(w, h);
        box.setStyle("-fx-background-color: linear-gradient(to bottom right, " + SURFACE2 + ", " + SURFACE + ");");
        Rectangle clip = new Rectangle(w, h);
        clip.setArcWidth(radius * 2);
        clip.setArcHeight(radius * 2);
        box.setClip(clip);
        if (url == null) return box;
        Region shimmer = new Region();
        shimmer.setStyle("-fx-background-color: linear-gradient(to right, rgba(255,255,255,0), rgba(255,255,255,0.10), rgba(255,255,255,0));");
        shimmer.setOpacity(0.4);
        FadeTransition pulse = new FadeTransition(Duration.millis(900), shimmer);
        pulse.setFromValue(0.15);
        pulse.setToValue(1);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);
        // nur laufen lassen, solange die Karte wirklich im Fenster haengt (sonst bleibt die Animation ewig am Leben)
        box.sceneProperty().addListener((o, a, b) -> { if (b == null) pulse.stop(); else if (shimmer.getParent() != null) pulse.play(); });
        box.getChildren().add(shimmer);
        Runnable startLoad = () -> {
        double reqW = Math.min(w * 2, 1600);
        String ckey = url + "@" + (int) reqW;
        Image cached = IMG_CACHE.get(ckey);
        if (cached == null || cached.isError()) {
            cached = new Image(url, reqW, 0, true, true, true);
            IMG_CACHE.put(ckey, cached);
        }
        Image img = cached;
        img.errorProperty().addListener((o, a, b) -> { if (b) { pulse.stop(); box.getChildren().remove(shimmer); } });
        ImageView iv = new ImageView();
        iv.setOpacity(0);
        iv.setSmooth(true);
        iv.setPreserveRatio(false);
        iv.setFitWidth(w);
        iv.setFitHeight(h);
        box.getChildren().add(iv);
        Runnable ready = () -> {
            if (img.isError() || img.getWidth() <= 0) return;
            double ir = img.getWidth() / img.getHeight(), tr = w / h;
            if (ir > tr) {
                double vw = img.getHeight() * tr;
                iv.setViewport(new Rectangle2D((img.getWidth() - vw) / 2, 0, vw, img.getHeight()));
            } else {
                double vh = img.getWidth() / tr;
                iv.setViewport(new Rectangle2D(0, (img.getHeight() - vh) * cropY, img.getWidth(), vh));
            }
            iv.setImage(img);
            pulse.stop();
            box.getChildren().remove(shimmer);
            iv.setScaleX(REDUCE ? 1 : 1.08);
            iv.setScaleY(REDUCE ? 1 : 1.08);
            FadeTransition ft = new FadeTransition(Duration.millis(450), iv);
            ft.setToValue(1);
            ScaleTransition zoom = new ScaleTransition(Duration.millis(650), iv);
            zoom.setToX(1);
            zoom.setToY(1);
            zoom.setInterpolator(Interpolator.EASE_OUT);
            new ParallelTransition(ft, zoom).play();
        };
        if (img.getProgress() >= 1) ready.run();
        else img.progressProperty().addListener((o, a, b) -> { if (b.doubleValue() >= 1) ready.run(); });
        };
        if (lazyActive != null) { box.getProperties().put("lazyLoad", startLoad); lazyActive.add(box); }
        else startLoad.run();
        return box;
    }

    /** Verpackt n in einen festen Rahmen und vergroessert n beim Ueberfahren (Rahmen und Layout bleiben unveraendert). */
    private static StackPane hoverScale(Node n, double to) {
        StackPane holder = new StackPane(n);
        ScaleTransition in = new ScaleTransition(Duration.millis(160), n);
        in.setToX(to);
        in.setToY(to);
        ScaleTransition out = new ScaleTransition(Duration.millis(160), n);
        out.setToX(1);
        out.setToY(1);
        holder.setOnMouseEntered(e -> { out.stop(); in.playFromStart(); });
        holder.setOnMouseExited(e -> { in.stop(); out.playFromStart(); });
        return holder;
    }

    // ------------------------------------------------------------ Kopfleiste

    private Button navButton(String t, boolean active) {
        Button b = new Button(t);
        pressable(b);
        b.getStyleClass().add("pill");
        if (active) b.getStyleClass().add("pill-active");
        return b;
    }

    private Node topBar(String active) {
        Button back = button("‹", "icon-btn");
        back.setStyle("-fx-font-size:20px;");
        back.setOnAction(e -> back());
        Label logo = new Label("Jellyfin");
        logo.getStyleClass().add("brand");
        Button home = navButton("Start", "home".equals(active));
        home.setOnAction(e -> { history.clear(); current = null; home(); });
        Button libs = navButton("Bibliotheken", "libs".equals(active));
        libs.setOnAction(e -> go(this::libraries));
        Button lists = navButton("Listen", "lists".equals(active));
        lists.setOnAction(e -> go(this::listsPage));
        Button fav = navButton("♥ Merkliste", "fav".equals(active));
        fav.setOnAction(e -> go(this::favorites));
        searchField = new TextField();
        searchField.getStyleClass().add("search");
        searchField.setPromptText("🔍  Titel, Serien, Filme suchen …");
        searchField.setPrefWidth(300);
        searchField.focusedProperty().addListener((o, was, now) -> new Timeline(new KeyFrame(Duration.millis(220),
                new KeyValue(searchField.prefWidthProperty(), now ? 420 : 300, Interpolator.EASE_BOTH))).play());
        ContextMenu sugg = new ContextMenu();
        PauseTransition debounce = new PauseTransition(Duration.millis(280));
        searchField.textProperty().addListener((o, was, now) -> {
            String q = now.trim();
            if (q.length() < 2 || !searchField.isFocused()) { sugg.hide(); return; }
            debounce.setOnFinished(ev -> bg(() -> jf.suggest(q), l -> {
                if (!q.equals(searchField.getText().trim()) || l.isEmpty()) { sugg.hide(); return; }
                sugg.getItems().clear();
                for (JfItem it : l) {
                    MenuItem mi = new MenuItem((it.type.equals("Series") ? "📺  " : "🎬  ") + it.name
                            + (it.year > 0 ? "   (" + it.year + ")" : ""));
                    mi.setOnAction(x -> open(it));
                    sugg.getItems().add(mi);
                }
                if (!sugg.isShowing()) sugg.show(searchField, javafx.geometry.Side.BOTTOM, 0, 4);
            }));
            debounce.playFromStart();
        });
        searchField.setOnAction(e -> {
            sugg.hide();
            String q = searchField.getText().trim();
            if (!q.isEmpty()) go(() -> search(q));
        });
        MenuButton user = new MenuButton("👤  " + (jf.userName == null || jf.userName.isEmpty() ? "Konto" : jf.userName));
        MenuItem statsItem = new MenuItem("📊  Statistik");
        statsItem.setOnAction(e -> go(this::stats));
        MenuItem syncItem = new MenuItem("👥  Gemeinsam schauen");
        syncItem.setOnAction(e -> go(this::syncPage));
        MenuItem updItem = new MenuItem("🔄  Nach Updates suchen (" + Updater.currentVersion() + ")");
        updItem.setOnAction(e -> checkUpdate(false));
        MenuItem updUrl = new MenuItem("⚙  Update-Adresse ändern …");
        updUrl.setOnAction(e -> askName("Update-Adresse", "Adresse der version.json:", u -> {
            String v = u.trim();
            settings.put("updateUrl", v.isEmpty() ? Updater.DEFAULT_URL : v);
            toast("Update-Adresse gespeichert");
        }));
        CheckMenuItem shortItem = new CheckMenuItem("Samples und Kurzfilme (< 5 Min) ausblenden");
        shortItem.setSelected(JfItem.hideShort);
        shortItem.setOnAction(e -> {
            JfItem.hideShort = shortItem.isSelected();
            settings.putBoolean("hideShort", JfItem.hideShort);
            invalidateCaches();
            toast("Gilt ab dem nächsten Öffnen einer Seite");
        });
        CheckMenuItem updAuto = new CheckMenuItem("Beim Start nach Updates suchen");
        updAuto.setSelected(settings.getBoolean("updateAuto", true));
        updAuto.setOnAction(e -> settings.putBoolean("updateAuto", updAuto.isSelected()));
        MenuItem dlItem = new MenuItem("⬇  Downloads");
        dlItem.setOnAction(e -> go(this::downloadsPage));
        MenuItem reload = new MenuItem("Neu laden");
        reload.setOnAction(e -> { if (current != null) current.run(); });
        MenuItem switchP = new MenuItem("👥  Profil wechseln");
        switchP.setOnAction(e -> { syncReset(); history.clear(); current = null; profilePicker(); });
        MenuItem addP = new MenuItem("➕  Weiteres Profil hinzufügen");
        addP.setOnAction(e -> { history.clear(); current = null; login(); });
        MenuItem pinP = new MenuItem("🔒  PIN für dieses Profil …");
        pinP.setOnAction(e -> {
            Jf.Profile cp = jf.currentProfile();
            if (cp == null) return;
            askName("PIN für „" + cp.name + "“", "Neue PIN (leer = ohne PIN):", pin -> {
                jf.setPin(cp, pin);
                toast("PIN gesetzt");
            });
        });
        MenuItem server = new MenuItem("Server ändern …");
        server.setOnAction(e -> { syncReset(); jf.logout(); history.clear(); current = null; login(); });
        MenuItem out = new MenuItem("Abmelden");
        out.setOnAction(e -> {
            Jf.Profile cp = jf.currentProfile();
            if (cp != null) jf.removeProfile(cp);
            jf.logout(); history.clear(); current = null; login();
        });
        Menu themeMenu = new Menu("🎨  Farbdesign");
        ToggleGroup tg = new ToggleGroup();
        for (String[] t : THEMES) {
            RadioMenuItem r = new RadioMenuItem(t[0]);
            r.setToggleGroup(tg);
            r.setSelected(t[1].equals(A1));
            r.setOnAction(e -> applyTheme(t[0], true));
            themeMenu.getItems().add(r);
        }
        CheckMenuItem reduce = new CheckMenuItem("Animationen reduzieren");
        reduce.setSelected(REDUCE);
        reduce.setOnAction(e -> { REDUCE = reduce.isSelected(); settings.putBoolean("reduce", REDUCE); });
        CheckMenuItem hv = new CheckMenuItem("Vorschau-Video im Banner");
        hv.setSelected(heroVideoOn);
        hv.setOnAction(e -> {
            heroVideoOn = hv.isSelected();
            settings.putBoolean("heroVideo", heroVideoOn);
            if (!heroVideoOn) HeroVideo.stop();
        });
        CheckMenuItem auto = new CheckMenuItem("Mit Windows starten");
        auto.setSelected(settings.getBoolean("autostart", false));
        auto.setOnAction(e -> {
            boolean on = auto.isSelected();
            if (setAutostart(on)) { settings.putBoolean("autostart", on); toast(on ? "Startet künftig mit Windows" : "Autostart aus"); }
            else { auto.setSelected(!on); toast("Autostart ging nicht (nur mit der EXE möglich)"); }
        });
        user.getItems().addAll(statsItem, dlItem, syncItem, new SeparatorMenuItem(), switchP, addP, pinP, new SeparatorMenuItem(), themeMenu, reduce, hv, auto, shortItem, new SeparatorMenuItem(), updItem, updAuto, updUrl, new SeparatorMenuItem(), reload, server, new SeparatorMenuItem(), out);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Node badge = Fx.logo(32, A1, A2);
        Button surprise = navButton("🎲 Überrasch mich", false);
        surprise.setOnAction(e -> surprise());
        HBox bar = new HBox(8, back, badge, logo, spacer(14), home, libs, lists, fav, surprise, sp, searchField, user);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 22, 10, 18));
        bar.getStyleClass().add("topbar");
        return bar;
    }

    private static Region spacer(double w) {
        Region r = new Region();
        r.setMinWidth(w);
        return r;
    }

    private void chrome(String active) {
        currentList = null;
        root.setTop(topBar(active));
    }

    // ------------------------------------------------------------ Anmeldung

    private static final String[] AVATAR = {"#8b6cff", "#19a8ff", "#ff6b5e", "#2ecc71", "#f5b942", "#ff5fa2", "#19c1c8"};

    /** "Wer schaut?" - Auswahl der gespeicherten Profile, mit PIN falls gesetzt. */
    private void profilePicker() {
        invalidateCaches();
        HeroVideo.stop();
        hidePreviewNow();
        root.setTop(null);
        VBox page = new VBox(34);
        page.setAlignment(Pos.CENTER);
        page.setStyle("-fx-background-color: radial-gradient(center 50% 30%, radius 80%, rgba(139,108,255,0.14), " + BG + ");");
        Label title = text("Wer schaut?", 38, true);
        title.setStyle(title.getStyle() + "-fx-font-weight:800;");
        FlowPane row = new FlowPane(26, 26);
        row.setAlignment(Pos.CENTER);
        row.setMaxWidth(900);
        int k = 0;
        for (Jf.Profile p : jf.profiles()) {
            String col = AVATAR[Math.abs(p.name.hashCode()) % AVATAR.length];
            Label ini = text(p.name.isEmpty() ? "?" : p.name.substring(0, 1).toUpperCase(), 46, true);
            StackPane av = new StackPane(ini);
            av.setMinSize(120, 120);
            av.setMaxSize(120, 120);
            av.setStyle("-fx-background-radius:60;-fx-background-color: linear-gradient(to bottom right, " + col + ", derive(" + col + ", -35%));");
            Label nm = text(p.name, 16, true);
            Label sub = muted(p.pinHash.isEmpty() ? "" : "🔒", 13);
            StackPane avH = hoverScale(av, 1.08);
            VBox card = new VBox(10, avH, nm, sub);
            card.setAlignment(Pos.CENTER);
            card.setCursor(Cursor.HAND);
            card.setMinWidth(150);
            ContextMenu cm = new ContextMenu();
            MenuItem rm = new MenuItem("Profil entfernen");
            rm.setOnAction(e -> { jf.removeProfile(p); profilePicker(); });
            cm.getItems().add(rm);
            card.setOnContextMenuRequested(e -> cm.show(card, e.getScreenX(), e.getScreenY()));
            card.setOnMouseClicked(e -> {
                if (e.getButton() != MouseButton.PRIMARY) return;
                if (p.pinHash.isEmpty()) { chooseProfile(p); return; }
                PasswordDialogHelper.ask(stage, "PIN für „" + p.name + "“", pin -> {
                    if (jf.checkPin(p, pin)) chooseProfile(p); else toast("Falsche PIN");
                });
            });
            reveal(card, k++);
            row.getChildren().add(card);
        }
        Button add = button("＋ Profil hinzufügen", "btn-ghost");
        add.setOnAction(e -> login());
        page.getChildren().addAll(title, row, add);
        root.setCenter(page);
    }

    private void chooseProfile(Jf.Profile p) {
        jf.useProfile(p);
        history.clear();
        current = null;
        home();
    }

    private void login() {
        invalidateCaches();
        root.setTop(null);
        if (heroTimer != null) heroTimer.stop();
        TextField server = new TextField(jf.server);
        TextField user = new TextField(jf.userName);
        PasswordField pw = new PasswordField();
        for (TextField f : new TextField[]{server, user, pw}) f.getStyleClass().add("field");
        server.setPromptText("http://192.168.178.80:8096");
        user.setPromptText("Benutzername");
        pw.setPromptText("Passwort");
        Label msg = muted("", 13);
        msg.setTextAlignment(TextAlignment.CENTER);
        Button ok = button("Anmelden", "btn-primary");
        ok.setDefaultButton(true);
        Button qc = button("Mit Quick Connect anmelden", "btn-ghost");
        ok.setMaxWidth(Double.MAX_VALUE);
        qc.setMaxWidth(Double.MAX_VALUE);
        ok.setOnAction(e -> {
            msg.setText("Anmelden …");
            jf.setServer(server.getText());
            Thread t = new Thread(() -> {
                try {
                    jf.loginPassword(user.getText().trim(), pw.getText());
                    Platform.runLater(this::home);
                } catch (Exception ex) {
                    Platform.runLater(() -> msg.setText("Anmeldung fehlgeschlagen: " + ex.getMessage()));
                }
            }, "login");
            t.setDaemon(true);
            t.start();
        });
        qc.setOnAction(e -> {
            jf.setServer(server.getText());
            msg.setText("Verbinde …");
            Thread t = new Thread(() -> {
                try {
                    String[] cs = jf.quickConnectStart();
                    Platform.runLater(() -> msg.setText("Code im Jellyfin-Konto bestätigen\n(Einstellungen → Quick Connect):  " + cs[0]));
                    for (int i = 0; i < 120; i++) {
                        Thread.sleep(2000);
                        if (jf.quickConnectPoll(cs[1])) {
                            Platform.runLater(this::home);
                            return;
                        }
                    }
                } catch (Exception ex) {
                    Platform.runLater(() -> msg.setText("Quick Connect fehlgeschlagen: " + ex.getMessage()));
                }
            }, "qc");
            t.setDaemon(true);
            t.start();
        });
        Label logoText = new Label("Jellyfin");
        logoText.getStyleClass().add("brand");
        logoText.setStyle("-fx-font-size:40px;");
        HBox logo = new HBox(14, Fx.logo(56, A1, A2), logoText);
        logo.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, logo, muted("Melde dich bei deinem Server an", 14), spacer(0),
                muted("Server", 12), server, muted("Benutzer", 12), user, muted("Passwort", 12), pw,
                spacer(0), ok, qc, msg);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxWidth(400);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        box.setPadding(new Insets(36));
        box.getStyleClass().add("glass");
        StackPane c = new StackPane();
        c.setStyle("-fx-background-color: radial-gradient(center 25% 0%, radius 90%, #2a1a6e 0%, #0e1230 38%, " + BG + " 80%);");
        Circle b1 = new Circle(230, Color.web("#7a5cff", 0.40));
        Circle b2 = new Circle(200, Color.web("" + A2 + "", 0.30));
        b1.setEffect(new javafx.scene.effect.GaussianBlur(120));
        b2.setEffect(new javafx.scene.effect.GaussianBlur(120));
        b1.setTranslateX(-300);
        b1.setTranslateY(-120);
        b2.setTranslateX(320);
        b2.setTranslateY(150);
        for (Circle bl : new Circle[]{b1, b2}) {
            TranslateTransition tx = new TranslateTransition(Duration.seconds(11), bl);
            tx.setByX(bl == b1 ? 220 : -220);
            tx.setByY(bl == b1 ? 140 : -130);
            tx.setAutoReverse(true);
            tx.setCycleCount(Animation.INDEFINITE);
            tx.setInterpolator(Interpolator.EASE_BOTH);
            tx.play();
        }
        c.getChildren().addAll(b1, b2, box);
        box.setOpacity(0);
        box.setTranslateY(30);
        FadeTransition bf = new FadeTransition(Duration.millis(700), box);
        bf.setToValue(1);
        TranslateTransition bt = new TranslateTransition(Duration.millis(750), box);
        bt.setToY(0);
        bt.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(bf, bt).play();
        setContent(c);
    }

    // ------------------------------------------------------------ Start

    private void home() {
        chrome("home");
        if (current == null) current = this::home;
        if (heroTimer != null) heroTimer.stop();
        VBox page = new VBox(8);
        page.setPadding(new Insets(0, 0, 40, 0));
        page.getChildren().add(loader());
        Ambient ambient = new Ambient(A1, A2, BG, !REDUCE);
        StackPane homeHolder = new StackPane(ambient, scroll(page));
        setContent(homeHolder);
        Callable<Object[]> homeWork = () -> {
            List<Object[]> rows = new ArrayList<>();
            ArrayList<JfItem> views = jf.views();
            ArrayList<JfItem> resume = jf.resume();
            ArrayList<JfItem> next = new ArrayList<>();
            // "Als naechstes": nur Serien, die nicht gerade angefangen sind (die stehen schon unter "Weiter schauen")
            java.util.HashSet<String> busy = new java.util.HashSet<>();
            for (JfItem r : resume) { busy.add(r.id); if (r.seriesId != null) busy.add(r.seriesId); }
            for (JfItem n : jf.nextUp()) {
                if (busy.contains(n.id) || (n.seriesId != null && busy.contains(n.seriesId)) || n.resumeTicks > 0) continue;
                next.add(n);
            }
            ArrayList<JfItem> hero = new ArrayList<>();
            for (JfItem i : resume) if (jf.backdrop(i) != null && hero.size() < 5) hero.add(i);
            for (JfItem i : next) if (jf.backdrop(i) != null && hero.size() < 5 && hero.stream().noneMatch(h -> h.id.equals(i.id))) hero.add(i);
            rows.add(new Object[]{"Meine Bibliotheken", views, true});
            if (!resume.isEmpty()) rows.add(new Object[]{"Weiter schauen", resume, true});
            if (!next.isEmpty()) rows.add(new Object[]{"Als nächstes", next, true});
            for (JfItem v : views) {
                try {
                    ArrayList<JfItem> l = jf.latest(v.id);
                    if (!l.isEmpty()) rows.add(new Object[]{"Neu in " + v.name, l, false});
                } catch (Jf.AuthException a) {
                    throw a;
                } catch (Exception ignored) {}
            }
            {
                ArrayList<JfItem> pool = new ArrayList<>();
                for (Object[] r : rows) {
                    String rt = (String) r[0];
                    if (!rt.startsWith("Neu in")) continue;
                    @SuppressWarnings("unchecked") ArrayList<JfItem> l = (ArrayList<JfItem>) r[1];
                    for (JfItem i : l) if (i.rating > 0 && pool.stream().noneMatch(x -> x.id.equals(i.id))) pool.add(i);
                }
                pool.sort((a, b) -> Float.compare(b.rating, a.rating));
                if (pool.size() >= 5) rows.add(Math.min(rows.size(), 3), new Object[]{"Top 10 – Bestbewertet", new ArrayList<>(pool.subList(0, Math.min(10, pool.size()))), false});
            }
            if (hero.isEmpty()) {
                for (Object[] r : rows) {
                    @SuppressWarnings("unchecked") ArrayList<JfItem> l = (ArrayList<JfItem>) r[1];
                    for (JfItem i : l) if (hero.size() < 5 && jf.backdrop(i) != null && i.isPlayable() || (hero.size() < 5 && "Series".equals(i.type) && jf.backdrop(i) != null)) hero.add(i);
                }
            }
            return new Object[]{rows, hero};
        };
        Consumer<Object[]> homeRender = res -> {
            if (root.getCenter() != homeHolder) return; // Nutzer ist schon woanders: nichts Schweres mehr aufbauen
            @SuppressWarnings("unchecked") List<Object[]> rows = (List<Object[]>) res[0];
            @SuppressWarnings("unchecked") List<JfItem> hero = (List<JfItem>) res[1];
            page.getChildren().clear();
            {
                ArrayList<String> bgUrls = new ArrayList<>();
                for (Object[] r : rows) {
                    @SuppressWarnings("unchecked") ArrayList<JfItem> l = (ArrayList<JfItem>) r[1];
                    for (JfItem i : l) { String u = jf.backdrop(i); if (u != null && !bgUrls.contains(u) && bgUrls.size() < 8) bgUrls.add(u); }
                }
                java.util.Collections.shuffle(bgUrls);
                ambient.setBackdrops(bgUrls);
            }
            if (!hero.isEmpty()) {
                ambient.setFeather(true);
                ambient.setTranslateY(500);
                page.getChildren().add(hero(hero));
                // Parallax: Bild wandert langsamer als der Inhalt, Text blendet beim Scrollen aus
                ScrollPane psp = pageScroll;
                psp.vvalueProperty().addListener((o, a, b) -> {
                    double y = b.doubleValue() * Math.max(0, psp.getContent().getBoundsInLocal().getHeight() - psp.getViewportBounds().getHeight());
                    if (heroLayer != null) heroLayer.setTranslateY(Math.min(y, 500) * 0.35);
                    if (heroVid != null) heroVid.setTranslateY(Math.min(y, 500) * 0.35);
                    if (heroInfo != null) heroInfo.setOpacity(Math.max(0, 1 - y / 320));
                    ambient.setTranslateY(Math.max(0, 500 - y)); // Hintergrund beginnt unter dem Banner
                });
            }
            for (Object[] r : rows) {
                @SuppressWarnings("unchecked") ArrayList<JfItem> l = (ArrayList<JfItem>) r[1];
                page.getChildren().add(row((String) r[0], l, (Boolean) r[2]));
            }
        };
        long now = System.currentTimeMillis();
        Object[] hc = homeCache;
        if (hc != null && now - homeCacheAt < 60_000) homeRender.accept(hc);
        else bg(homeWork, r -> { homeCache = r; homeCacheAt = System.currentTimeMillis(); homeRender.accept(r); });
    }

    // Zwischenspeicher fuer Startseite und Detailseiten (kurz gueltig): schnelles Hin- und Herwechseln
    private Object[] homeCache;
    private long homeCacheAt;
    private final java.util.HashMap<String, Object[]> itemCache = new java.util.HashMap<>();

    private void invalidateCaches() {
        homeCache = null;
        itemCache.clear();
    }

    private JfItem itemCached(String id) throws Exception {
        Object[] c;
        synchronized (itemCache) { c = itemCache.get(id); }
        if (c != null && System.currentTimeMillis() - (Long) c[1] < 45_000) return (JfItem) c[0];
        JfItem it = jf.item(id);
        synchronized (itemCache) { if (itemCache.size() > 60) itemCache.clear(); itemCache.put(id, new Object[]{it, System.currentTimeMillis()}); }
        return it;
    }

    /** Grosser Banner mit wechselnden Titeln. */
    private static Label heroMeta(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-text-fill:#dfe6ee;-fx-font-size:14px;-fx-font-weight:600;");
        return l;
    }

    private Node hero(List<JfItem> items) {
        StackPane hero = new StackPane();
        hero.setMinHeight(500);
        hero.setPrefHeight(500);
        hero.setMaxHeight(500);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(hero.widthProperty());
        clip.heightProperty().bind(hero.heightProperty());
        hero.setClip(clip);
        StackPane imgLayer = new StackPane();
        imgLayer.setAlignment(Pos.TOP_CENTER);
        heroLayer = imgLayer;
        Region fadeSide = new Region();
        fadeSide.setStyle("-fx-background-color: linear-gradient(to right, rgba(10,13,18,0.97) 0%, rgba(10,13,18,0.78) 34%, rgba(10,13,18,0.10) 74%);");
        Region fadeTop = new Region();
        fadeTop.setMouseTransparent(true);
        fadeTop.setMaxHeight(110);
        StackPane.setAlignment(fadeTop, Pos.TOP_CENTER);
        fadeTop.setStyle("-fx-background-color: linear-gradient(to bottom, rgba(10,13,18,0.75), rgba(10,13,18,0));");
        Region fadeBottom = new Region();
        fadeBottom.setStyle("-fx-background-color: linear-gradient(to top, " + BG + " 0%, rgba(10,13,18,0) 45%);");
        VBox info = new VBox(12);
        info.setMaxWidth(680);
        info.setAlignment(Pos.BOTTOM_LEFT);
        info.setPadding(new Insets(0, 0, 56, 48));
        StackPane.setAlignment(info, Pos.BOTTOM_LEFT);
        HBox dots = new HBox(7);
        dots.setAlignment(Pos.BOTTOM_RIGHT);
        dots.setPadding(new Insets(0, 36, 22, 0));
        dots.setPickOnBounds(false);
        StackPane.setAlignment(dots, Pos.BOTTOM_RIGHT);
        ImageView vid = new ImageView();
        vid.setOpacity(0);
        vid.setMouseTransparent(true);
        vid.setPreserveRatio(true);
        vid.fitWidthProperty().bind(hero.widthProperty());
        heroVid = vid;
        Region glow = new Region();
        glow.setMouseTransparent(true);
        glow.setStyle("-fx-background-color: radial-gradient(center 0% 100%, radius 55%, " + css(Color.web(A1), 0.30) + ", rgba(0,0,0,0));");
        hero.getChildren().addAll(imgLayer, vid, fadeSide, glow, fadeBottom, fadeTop, info, dots);
        heroInfo = info;
        int[] idx = {-1};
        Runnable[] show = new Runnable[1];
        show[0] = () -> {
            idx[0] = (idx[0] + 1) % items.size();
            JfItem it = items.get(idx[0]);
            double w = Math.max(900, hero.getWidth() > 0 ? hero.getWidth() : root.getWidth());
            HeroVideo.stop();
            if (heroVideoOn && it.isPlayable() && it.runTicks > 0) {
                int token = idx[0];
                long startSec = it.resumeTicks > 0 ? it.resumeTicks / 10000000L : (long) (it.runTicks / 10000000L * 0.2);
                PauseTransition wait = new PauseTransition(Duration.seconds(2));
                wait.setOnFinished(ev -> {
                    if (hero.getScene() != null && player == null && idx[0] == token && heroVideoOn)
                        HeroVideo.start(vid, jf.directUrl(it), startSec);
                });
                wait.play();
            }
            StackPane pic = picture(jf.backdrop(it), w, 500, 0, 0.15);
            pic.setClip(null);
            pic.setStyle("-fx-background-color: " + BG + ";");
            pic.setOpacity(0);
            imgLayer.getChildren().add(pic);
            ScaleTransition kb = new ScaleTransition(Duration.seconds(16), pic);
            kb.setFromX(1.0);
            kb.setFromY(1.0);
            kb.setToX(1.09);
            kb.setToY(1.09);
            kb.setInterpolator(Interpolator.LINEAR);
            if (!REDUCE) kb.play();
            FadeTransition ft = new FadeTransition(Duration.millis(700), pic);
            ft.setToValue(1);
            ft.setOnFinished(e -> { while (imgLayer.getChildren().size() > 1) imgLayer.getChildren().remove(0); });
            ft.play();
            info.getChildren().clear();
            String kind = it.resumeTicks > 0 ? "WEITER SCHAUEN" : "EMPFOHLEN";
            Label k = text(kind, 11, true);
            k.setStyle(k.getStyle() + "-fx-text-fill:white;-fx-background-color:linear-gradient(to right," + A1 + "," + A2 + ");-fx-background-radius:999;-fx-padding:4 12 4 12;");
            k.setMaxWidth(Region.USE_PREF_SIZE);
            Label title = text("Episode".equals(it.type) && !it.seriesName.isEmpty() ? it.seriesName : it.name, 54, true);
            title.setStyle(title.getStyle() + "-fx-font-weight:900;");
            title.setEffect(new DropShadow(22, Color.rgb(0, 0, 0, 0.75)));
            HBox m = new HBox(10);
            m.setAlignment(Pos.CENTER_LEFT);
            if (it.rating > 0) { Label c = new Label("★ " + String.format("%.1f", it.rating)); c.getStyleClass().add("chip-accent"); m.getChildren().add(c); }
            if ("Episode".equals(it.type)) m.getChildren().add(heroMeta(it.title()));
            if (it.year > 0) m.getChildren().add(heroMeta(String.valueOf(it.year)));
            if (!it.runtime().isEmpty()) m.getChildren().add(heroMeta(it.runtime()));
            if (!it.officialRating.isEmpty()) { Label c = new Label(it.officialRating); c.getStyleClass().add("chip"); m.getChildren().add(c); }
            Label ov = muted(it.overview, 15);
            ov.setMaxHeight(66);
            ov.setStyle(ov.getStyle() + "-fx-text-fill:#d3dbe4;-fx-line-spacing:3;");
            ov.setEffect(new DropShadow(8, Color.rgb(0, 0, 0, 0.6)));
            HBox btns = new HBox(12);
            btns.setPadding(new Insets(6, 0, 0, 0));
            if (it.isPlayable()) {
                Button play = iconBtn("play", it.resumeTicks > 0 ? "Fortsetzen" : "Abspielen", "btn-primary");
                play.getStyleClass().add("btn-big");
                play.setOnAction(e -> play(it));
                btns.getChildren().add(play);
            }
            Button det = iconBtn("info", "Details", "btn-ghost");
            det.getStyleClass().add("btn-big");
            det.setOnAction(e -> open(it));
            btns.getChildren().add(det);
            info.getChildren().addAll(k, title, m, ov, btns);
            for (int ri = 0; ri < info.getChildren().size(); ri++) {
                Node ch = info.getChildren().get(ri);
                ch.setOpacity(0);
                ch.setTranslateX(-28);
                FadeTransition rf = new FadeTransition(Duration.millis(520), ch);
                rf.setToValue(1);
                rf.setDelay(Duration.millis(120 + ri * 90));
                TranslateTransition rt = new TranslateTransition(Duration.millis(620), ch);
                rt.setToX(0);
                rt.setInterpolator(Interpolator.EASE_OUT);
                rt.setDelay(Duration.millis(120 + ri * 90));
                new ParallelTransition(rf, rt).play();
            }
            dots.getChildren().clear();
            for (int d = 0; d < items.size() && items.size() > 1; d++) {
                Region c = new Region();
                boolean on = d == idx[0];
                c.setMinSize(on ? 26 : 8, 8);
                c.setPrefSize(on ? 26 : 8, 8);
                c.setMaxSize(on ? 26 : 8, 8);
                c.setStyle("-fx-background-radius:4;-fx-background-color:" + (on ? "linear-gradient(to right," + A1 + "," + A2 + ")" : "rgba(255,255,255,0.35)") + ";");
                int target = d;
                c.setCursor(Cursor.HAND);
                c.setOnMouseClicked(e -> { idx[0] = target - 1; show[0].run(); if (heroTimer != null) heroTimer.playFromStart(); });
                dots.getChildren().add(c);
            }
        };
        hero.sceneProperty().addListener((o, a, b) -> { if (b != null && idx[0] < 0) show[0].run(); });
        if (items.size() > 1) {
            heroTimer = new Timeline(new KeyFrame(Duration.seconds(9), e -> {
                if (hero.getScene() == null) heroTimer.stop(); else show[0].run();
            }));
            heroTimer.setCycleCount(Animation.INDEFINITE);
            heroTimer.play();
        }
        Platform.runLater(() -> { if (idx[0] < 0) show[0].run(); });
        return hero;
    }

    private Node row(String title, List<JfItem> items, boolean landscape) {
        HBox cards = new HBox(18);
        cards.setPadding(new Insets(14, 48, 18, 48));
        int ci = 0;
        boolean ranked = title.startsWith("Top 10");
        for (JfItem i : items) {
            Node c = card(i, landscape);
            if (ranked && c instanceof StackPane) {
                Label rk = new Label(String.valueOf(ci + 1));
                rk.setStyle("-fx-font-size:64px;-fx-font-weight:900;-fx-text-fill:white;-fx-effect:dropshadow(gaussian," + A1 + ",18,0.4,0,0);");
                rk.setMouseTransparent(true);
                StackPane.setAlignment(rk, Pos.TOP_LEFT);
                StackPane.setMargin(rk, new Insets(168, 0, 0, 8));
                ((StackPane) c).getChildren().add(rk);
            }
            reveal(c, ci++);
            cards.getChildren().add(c);
        }
        ScrollPane sp = new ScrollPane(cards);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.hvalueProperty().addListener((o, x, y) -> hidePreviewNow());
        sp.setFitToHeight(true);
        double rowH = (landscape ? 169 : 258) + 8 + 40 + 36; // feste Hoehe: nichts verschiebt sich beim Laden
        sp.setMinHeight(rowH);
        sp.setPrefHeight(rowH);
        sp.setMaxHeight(rowH);
        Button left = button("‹", "icon-btn"), right = button("›", "icon-btn");
        left.setStyle("-fx-font-size:20px;");
        right.setStyle("-fx-font-size:20px;");
        left.setOnAction(e -> scrollRow(sp, -1));
        right.setOnAction(e -> scrollRow(sp, 1));
        Label t = text(title, 24, true);
        t.setStyle(t.getStyle() + "-fx-font-weight:800;");
        Region accent = new Region();
        accent.setMinSize(4, 26);
        accent.setPrefSize(4, 26);
        accent.setMaxSize(4, 26);
        accent.setStyle("-fx-background-radius: 3; -fx-background-color: linear-gradient(to bottom, " + A1 + ", " + A2 + "); -fx-effect: dropshadow(gaussian, " + A1 + ", 10, 0.3, 0, 0);");
        Region fill = new Region();
        HBox.setHgrow(fill, Priority.ALWAYS);
        HBox head = new HBox(10, accent, t, fill, left, right);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(14, 48, 0, 48));
        return new VBox(head, sp);
    }

    private void scrollRow(ScrollPane sp, int dir) {
        double content = sp.getContent().getBoundsInLocal().getWidth(), view = sp.getViewportBounds().getWidth();
        if (content <= view) return;
        double step = view * 0.8 / (content - view);
        double to = Math.max(0, Math.min(1, sp.getHvalue() + dir * step));
        new Timeline(new KeyFrame(Duration.millis(300), new KeyValue(sp.hvalueProperty(), to, Interpolator.EASE_BOTH))).play();
    }

    /** Kurze stumme Szenen auf einer Bibliotheks-Kachel; wechselt alle paar Sekunden zum naechsten Titel. */
    private final java.util.HashMap<String, ArrayList<JfItem>> sceneCache = new java.util.HashMap<>();

    private final class LibScene {
        private final JfItem lib;
        private final StackPane img;
        private final ImageView view = new ImageView();
        private final PauseTransition delay = new PauseTransition(Duration.millis(450));
        private final Timeline next;
        private int token, idx;
        private boolean added;

        LibScene(JfItem lib, StackPane img, double w, double h) {
            this.lib = lib;
            this.img = img;
            view.setFitWidth(w);
            view.setFitHeight(h);
            view.setPreserveRatio(false);
            view.setOpacity(0);
            view.setMouseTransparent(true);
            delay.setOnFinished(e -> play());
            next = new Timeline(new KeyFrame(Duration.seconds(6), e -> play()));
            next.setCycleCount(Animation.INDEFINITE);
        }

        void start() {
            if (player != null) return;
            token++;
            delay.playFromStart();
        }

        void stop() {
            token++;
            delay.stop();
            next.stop();
            if (added) HeroVideo.stop();
        }

        private void play() {
            int my = token;
            ArrayList<JfItem> pool = sceneCache.get(lib.id);
            if (pool != null) { show(pool, my); return; }
            bg(() -> jf.randomPlayable(lib.id, 12), l -> {
                sceneCache.put(lib.id, l);
                show(l, my);
            });
        }

        private void show(ArrayList<JfItem> pool, int my) {
            if (my != token || pool.isEmpty() || player != null || !img.isHover() && !img.getParent().isHover()) return;
            JfItem it = pool.get(idx++ % pool.size());
            long total = it.runTicks / 10000000L;
            long start = (long) (total * (0.12 + 0.6 * Math.random()));
            if (!added) { img.getChildren().add(1, view); added = true; }
            view.setOpacity(0);
            HeroVideo.start(view, jf.directUrl(it), start);
            if (next.getStatus() != Animation.Status.RUNNING) next.playFromStart();
        }
    }

    private Node card(JfItem i, boolean landscape) {
        boolean isLib = "CollectionFolder".equals(i.type) || "UserView".equals(i.type);
        double w = (landscape ? 300 : 172) * cardScale, h = (landscape ? 169 : 258) * cardScale;
        String url = landscape ? (isLib ? jf.libraryImage(i) : jf.landscape(i)) : jf.poster(i);
        StackPane img = picture(url, w, h, 16, 0.2);
        if (url == null) img.getChildren().add(text(i.name.isEmpty() ? "?" : i.name.substring(0, 1), 44, true));
        if (i.progress() > 0) {
            Region track = new Region();
            track.setMaxHeight(4);
            track.setStyle("-fx-background-color: rgba(255,255,255,0.28);");
            Region fill = new Region();
            fill.setMaxHeight(4);
            fill.setMaxWidth(w * i.progress());
            fill.setStyle("-fx-background-color: linear-gradient(to right, " + A1 + ", " + A2 + ");");
            StackPane bar = new StackPane(track, fill);
            StackPane.setAlignment(fill, Pos.CENTER_LEFT);
            bar.setMaxHeight(4);
            StackPane.setAlignment(bar, Pos.BOTTOM_CENTER);
            img.getChildren().add(bar);
        }
        if (i.played) {
            Label chk = text("✓", 12, true);
            chk.setStyle(chk.getStyle() + "-fx-background-color: linear-gradient(to bottom right," + A1 + "," + A2 + ");-fx-background-radius:12;-fx-min-width:22;-fx-min-height:22;-fx-alignment:center;");
            StackPane.setAlignment(chk, Pos.TOP_RIGHT);
            StackPane.setMargin(chk, new Insets(8));
            img.getChildren().add(chk);
        }
        if (i.unplayed > 0 && !i.played) {
            Label cnt = text(String.valueOf(i.unplayed), 12, true);
            cnt.setStyle(cnt.getStyle() + "-fx-background-color: linear-gradient(to bottom right," + A1 + "," + A2 + ");-fx-background-radius:12;-fx-min-width:22;-fx-min-height:22;-fx-alignment:center;-fx-padding:0 6 0 6;");
            StackPane.setAlignment(cnt, Pos.TOP_RIGHT);
            StackPane.setMargin(cnt, new Insets(8));
            img.getChildren().add(cnt);
        }
        if (!i.played && !isLib && isNew(i)) {
            Label nw = text("NEU", 10, true);
            nw.setStyle(nw.getStyle() + "-fx-text-fill:white;-fx-background-color:linear-gradient(to right," + A1 + "," + A2 + ");-fx-background-radius:6;-fx-padding:3 8 3 8;");
            nw.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            StackPane.setAlignment(nw, Pos.TOP_LEFT);
            StackPane.setMargin(nw, new Insets(8));
            img.getChildren().add(nw);
        }
        // Bibliotheks-Kacheln: beim Ueberfahren laufen kurze stumme Szenen aus dem Inhalt
        LibScene libScene = (isLib && landscape && !i.id.isEmpty() && heroVideoOn) ? new LibScene(i, img, w, h) : null;
        // Glanzstreifen beim Ueberfahren
        Region shine = new Region();
        shine.setMinSize(46, h * 1.8);
        shine.setMaxSize(46, h * 1.8);
        shine.setRotate(18);
        shine.setMouseTransparent(true);
        shine.setStyle("-fx-background-color: linear-gradient(to right, rgba(255,255,255,0), rgba(255,255,255,0.28), rgba(255,255,255,0));");
        shine.setTranslateX(-w);
        StackPane.setAlignment(shine, Pos.CENTER_LEFT);
        img.getChildren().add(shine);
        TranslateTransition shineTt = new TranslateTransition(Duration.millis(650), shine);
        shineTt.setFromX(-60);
        shineTt.setToX(w + 40);
        shineTt.setInterpolator(Interpolator.EASE_BOTH);
        // Abdunkelung + Play-Symbol beim Ueberfahren
        Region shade = new Region();
        shade.setStyle("-fx-background-color: rgba(0,0,0,0.38);");
        Label playIcon = text("▶", 20, true);
        playIcon.setStyle(playIcon.getStyle() + "-fx-background-color: linear-gradient(to bottom right," + A1 + "," + A2 + ");-fx-background-radius:30;-fx-min-width:56;-fx-min-height:56;-fx-alignment:center;-fx-padding:0 0 0 3;-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.5),18,0.1,0,4);");
        playIcon.setTextFill(Color.WHITE);
        StackPane over = new StackPane(shade, playIcon);
        over.setOpacity(0);
        over.setPickOnBounds(false);
        shade.setMouseTransparent(true);
        if (i.isPlayable() || "Series".equals(i.type)) {
            playIcon.setCursor(Cursor.HAND);
            playIcon.setOnMouseClicked(e -> { e.consume(); playOrNext(i); });
        } else {
            over.setMouseTransparent(true);
        }
        img.getChildren().add(over);

        Label t = text("Episode".equals(i.type) && !i.seriesName.isEmpty() ? i.seriesName : i.name, 15, true);
        t.setMaxWidth(w);
        t.setWrapText(false);
        t.setTextOverrun(OverrunStyle.ELLIPSIS);
        String sub = "Episode".equals(i.type) ? i.title() : (i.year > 0 ? String.valueOf(i.year) : isLib ? "" : i.genres);
        Label s = muted(sub, 12);
        s.setMaxWidth(w);
        s.setWrapText(false);
        s.setTextOverrun(OverrunStyle.ELLIPSIS);
        VBox c = new VBox(8, img, new VBox(2, t, s));
        c.setPrefWidth(w);
        c.setMaxWidth(w);
        StackPane holder = new StackPane(c);
        holder.setCursor(Cursor.HAND);
        holder.setMaxWidth(w);
        holder.setOnMouseClicked(e -> { if (e.getButton() == MouseButton.PRIMARY) openFrom(holder, i); });
        holder.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> { if (!REDUCE && e.getButton() == MouseButton.PRIMARY) { c.setScaleX(0.97); c.setScaleY(0.97); } });
        holder.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> { if (!REDUCE) { double k = holder.isHover() ? 1.05 : 1; c.setScaleX(k); c.setScaleY(k); } });
        holder.setOnContextMenuRequested(e -> { itemMenu(i).show(holder, e.getScreenX(), e.getScreenY()); e.consume(); });
        ScaleTransition sin = new ScaleTransition(Duration.millis(170), c);
        sin.setToX(1.05);
        sin.setToY(1.05);
        TranslateTransition lin = new TranslateTransition(Duration.millis(170), c);
        lin.setToY(-6);
        ScaleTransition sout = new ScaleTransition(Duration.millis(170), c);
        sout.setToX(1);
        sout.setToY(1);
        TranslateTransition lout = new TranslateTransition(Duration.millis(170), c);
        lout.setToY(0);
        ParallelTransition in = new ParallelTransition(sin, lin);
        ParallelTransition out = new ParallelTransition(sout, lout);
        FadeTransition fin = new FadeTransition(Duration.millis(170), over);
        fin.setToValue(1);
        FadeTransition fout = new FadeTransition(Duration.millis(170), over);
        fout.setToValue(0);
        DropShadow glow = new DropShadow(26, isLib ? Color.web(Genres.color(i.name), 0.65) : Color.rgb(120, 100, 255, 0.55));
        // Handler am festen Rahmen: so aendert die Vergroesserung nichts an Treffern und Reihenfolge
        holder.setOnMouseEntered(e -> {
            out.stop(); fout.stop();
            if (!REDUCE) { in.playFromStart(); shineTt.playFromStart(); }
            fin.playFromStart(); img.setEffect(glow);
            if (!isLib) schedulePreview(holder, i);
            else if (libScene != null) libScene.start();
        });
        holder.setOnMouseMoved(e -> { if (!REDUCE) c.setRotate((e.getX() / w - 0.5) * 3.2); });
        holder.setOnMouseExited(e -> {
            Timeline untilt = new Timeline(new KeyFrame(Duration.millis(180), new KeyValue(c.rotateProperty(), 0, Interpolator.EASE_OUT)));
            untilt.play();
            in.stop(); fin.stop(); out.playFromStart(); fout.playFromStart(); img.setEffect(null);
            cancelPreviewSoon();
            if (libScene != null) libScene.stop();
        });
        // Tastatur / Controller: Karte ist anwaehlbar, zeigt dieselbe Hervorhebung wie beim Ueberfahren
        holder.setFocusTraversable(true);
        holder.focusedProperty().addListener((o, was, now) -> {
            if (now) {
                out.stop(); fout.stop();
                if (!REDUCE) in.playFromStart();
                fin.playFromStart(); img.setEffect(glow);
                ensureVisible(holder);
            } else if (!holder.isHover()) {
                in.stop(); fin.stop(); out.playFromStart(); fout.playFromStart(); img.setEffect(null);
            }
        });
        holder.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) { openFrom(holder, i); e.consume(); }
            else if (e.getCode() == KeyCode.P) { playOrNext(i); e.consume(); }
            else if (e.getCode() == KeyCode.F) { toggleFavorite(i, null); e.consume(); }
        });
        return holder;
    }

    private void playOrNext(JfItem i) {
        hidePreviewNow();
        if (i.isPlayable()) play(i);
        else bg(() -> jf.nextUpFor(i.id), n -> { if (n != null) play(n); else open(i); });
    }

    // ------------------------------------------------------------ Vorschau-Karte und Seitenuebergang

    private void schedulePreview(StackPane holder, JfItem i) {
        if (previewHide != null) previewHide.stop();
        if (previewDelay != null) previewDelay.stop();
        if (previewNode != null && previewNode.getUserData() == i) return;
        previewDelay = new PauseTransition(Duration.millis(420));
        previewDelay.setOnFinished(e -> { if (player == null && holder.isHover()) showPreview(holder, i); });
        previewDelay.playFromStart();
    }

    private void cancelPreviewSoon() {
        if (previewDelay != null) previewDelay.stop();
        if (previewNode == null) return;
        if (previewHide != null) previewHide.stop();
        previewHide = new PauseTransition(Duration.millis(70));
        previewHide.setOnFinished(e -> { if (previewNode != null && !previewNode.isHover()) hidePreview(); });
        previewHide.playFromStart();
    }

    void hidePreviewNow() {
        if (previewDelay != null) previewDelay.stop();
        if (previewHide != null) previewHide.stop();
        if (previewNode != null && layer != null) layer.getChildren().remove(previewNode);
        previewNode = null;
    }

    private void hidePreview() {
        StackPane n = previewNode;
        if (n == null) return;
        previewNode = null;
        n.setMouseTransparent(true); // blockiert beim Ausblenden keine Nachbarkarten mehr
        FadeTransition ft = new FadeTransition(Duration.millis(100), n);
        ft.setToValue(0);
        ft.setOnFinished(e -> layer.getChildren().remove(n));
        ft.play();
    }

    private void showPreview(StackPane holder, JfItem i) {
        hidePreviewNow();
        double pw = 390, imgH = 219;
        StackPane pic = picture(jf.landscape(i), pw, imgH, 0, 0.2);
        pic.setClip(new Rectangle(pw, imgH));
        Rectangle topRound = new Rectangle(pw, imgH + 20);
        topRound.setArcWidth(32);
        topRound.setArcHeight(32);
        pic.setClip(topRound);
        pic.setMaxHeight(imgH);
        Button play = button("▶", "btn-primary");
        play.setStyle("-fx-padding: 8 16 8 18; -fx-font-size: 14px;");
        play.setOnAction(e -> playOrNext(i));
        Button fav = button(i.favorite ? "♥" : "♡", "icon-btn");
        fav.setOnAction(e -> { toggleFavorite(i, null); fav.setText(i.favorite ? "♡" : "♥"); });
        Button det = button("ⓘ", "icon-btn");
        det.setOnAction(e -> openFrom(holder, i));
        HBox btns = new HBox(10, play, fav, det);
        btns.setAlignment(Pos.CENTER_LEFT);
        Label t = text("Episode".equals(i.type) && !i.seriesName.isEmpty() ? i.seriesName : i.name, 17, true);
        StringBuilder meta = new StringBuilder();
        if ("Episode".equals(i.type)) meta.append(i.title()).append("   ");
        if (i.year > 0) meta.append(i.year).append("   ");
        if (!i.runtime().isEmpty()) meta.append(i.runtime()).append("   ");
        if (i.rating > 0) meta.append("★ ").append(String.format("%.1f", i.rating));
        Label m = muted(meta.toString().trim(), 12);
        Label ov = muted(i.overview, 12);
        ov.setWrapText(true);
        ov.setMaxWidth(pw - 32);
        ov.setMaxHeight(54);
        ov.setStyle(ov.getStyle() + "-fx-text-fill:#c3ccd6;");
        VBox body = new VBox(8, btns, t, m);
        if (!i.overview.isEmpty()) body.getChildren().add(ov);
        body.setPadding(new Insets(14, 16, 16, 16));
        VBox box = new VBox(pic, body);
        StackPane card = new StackPane(box);
        card.setStyle("-fx-background-color: " + SURFACE + "; -fx-background-radius: 16;");
        card.setEffect(new DropShadow(44, Color.rgb(0, 0, 0, 0.8)));
        card.setUserData(i);
        card.setMaxSize(pw, Region.USE_PREF_SIZE);
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(e -> { if (e.getTarget() == pic || e.getTarget() == card || e.getTarget() instanceof ImageView) openFrom(card, i); });
        card.setOnMouseEntered(e -> { if (previewHide != null) previewHide.stop(); });
        card.setOnMouseExited(e -> cancelPreviewSoon());
        javafx.geometry.Bounds b = holder.localToScene(holder.getBoundsInLocal());
        double estH = imgH + 150;
        double x = b.getMinX() + b.getWidth() / 2 - pw / 2;
        double y = b.getMinY() - 18;
        x = Math.max(14, Math.min(x, layer.getWidth() - pw - 14));
        y = Math.max(64, Math.min(y, layer.getHeight() - estH - 14));
        card.setLayoutX(x);
        card.setLayoutY(y);
        card.setManaged(false);
        layer.getChildren().add(card);
        card.applyCss();
        card.autosize();
        previewNode = card;
        card.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(170), card);
        ft.setToValue(1);
        if (REDUCE) { ft.play(); return; }
        card.setScaleX(0.86);
        card.setScaleY(0.86);
        ScaleTransition st = new ScaleTransition(Duration.millis(190), card);
        st.setToX(1);
        st.setToY(1);
        st.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(ft, st).play();
    }

    /** Oeffnet einen Titel; die angeklickte Karte waechst dabei zur Seite auf. */
    private static boolean isNew(JfItem i) {
        try {
            return java.time.Duration.between(java.time.OffsetDateTime.parse(i.created), java.time.OffsetDateTime.now()).toDays() < 10;
        } catch (Exception e) { return false; }
    }

    private ImageView veil;

    /** Blendet das Uebergangsbild aus; die fertige Seite (falls angegeben) blendet gleichzeitig ein. */
    private void releaseVeil(Node page) {
        ImageView v = veil;
        veil = null;
        if (page != null && v != null && !REDUCE) {
            page.setOpacity(0);
            FadeTransition pf = new FadeTransition(Duration.millis(420), page);
            pf.setToValue(1);
            pf.play();
        }
        if (v == null) return;
        FadeTransition f = new FadeTransition(Duration.millis(REDUCE ? 1 : 420), v);
        f.setToValue(0);
        f.setOnFinished(e -> layer.getChildren().remove(v));
        f.play();
    }

    private void openFrom(Node src, JfItem i) {
        hidePreviewNow();
        boolean detailType = java.util.Set.of("Movie", "Series", "Episode", "Video", "MusicVideo", "Season").contains(i.type);
        if (REDUCE || !detailType || src.getScene() == null || layer.getWidth() < 100) { open(i); return; }
        try {
            javafx.geometry.Bounds b = src.localToScene(src.getBoundsInLocal());
            javafx.scene.SnapshotParameters sp0 = new javafx.scene.SnapshotParameters();
            sp0.setFill(Color.TRANSPARENT);
            javafx.scene.image.WritableImage wi = src.snapshot(sp0, null);
            ImageView iv = new ImageView(wi);
            iv.setPreserveRatio(false);
            iv.setMouseTransparent(true);
            iv.setManaged(false);
            iv.setFitWidth(b.getWidth());
            iv.setFitHeight(b.getHeight());
            iv.setLayoutX(b.getMinX());
            iv.setLayoutY(b.getMinY());
            releaseVeil(null);
            layer.getChildren().add(iv);
            veil = iv;
            // aufziehen und stehen bleiben, bis die neue Seite fertig ist (dann weich ueberblenden)
            Timeline tl = new Timeline(new KeyFrame(Duration.millis(420),
                    new KeyValue(iv.layoutXProperty(), 0, Interpolator.EASE_OUT),
                    new KeyValue(iv.layoutYProperty(), 0, Interpolator.EASE_OUT),
                    new KeyValue(iv.fitWidthProperty(), layer.getWidth(), Interpolator.EASE_OUT),
                    new KeyValue(iv.fitHeightProperty(), layer.getHeight(), Interpolator.EASE_OUT),
                    new KeyValue(iv.opacityProperty(), 0.55, Interpolator.EASE_OUT)));
            tl.play();
            PauseTransition safety = new PauseTransition(Duration.seconds(5));
            safety.setOnFinished(e -> releaseVeil(null));
            safety.play();
        } catch (Throwable t) {
            Log.e("Seitenuebergang", t);
        }
        open(i);
    }

    // ------------------------------------------------------------ Tastatur- und Controller-Steuerung

    private void kbdMode(boolean on) {
        if (shell == null) return;
        if (on) { if (!shell.getStyleClass().contains("kbd")) shell.getStyleClass().add("kbd"); }
        else shell.getStyleClass().remove("kbd");
    }

    private void collectFocusable(Node n, List<Node> out) {
        if (!n.isVisible() || n.isDisabled() || n.getOpacity() < 0.05) return;
        if (n.isFocusTraversable() && !(n instanceof ScrollPane) && n.getBoundsInLocal().getWidth() > 0) out.add(n);
        if (n instanceof Parent) for (Node c : ((Parent) n).getChildrenUnmodifiable()) collectFocusable(c, out);
    }

    /** Raeumliche Navigation: naechstes bedienbares Element in Richtung (dx, dy). */
    private void navMove(int dx, int dy) {
        Scene sc = stage.getScene();
        List<Node> all = new ArrayList<>();
        collectFocusable(root, all);
        if (all.isEmpty()) return;
        Node cur = sc.getFocusOwner();
        if (cur == null || !all.contains(cur)) {
            // noch nichts ausgewaehlt: erstes Element unterhalb der Kopfleiste nehmen
            Node best = null;
            double by = Double.MAX_VALUE;
            for (Node n : all) {
                Bounds b = n.localToScene(n.getBoundsInLocal());
                if (b.getMinY() < 70) continue;
                double s = b.getMinY() * 3 + b.getMinX();
                if (s < by) { by = s; best = n; }
            }
            if (best == null) best = all.get(0);
            best.requestFocus();
            return;
        }
        Bounds cb = cur.localToScene(cur.getBoundsInLocal());
        double cx = (cb.getMinX() + cb.getMaxX()) / 2, cy = (cb.getMinY() + cb.getMaxY()) / 2;
        Node best = null;
        double bestScore = Double.MAX_VALUE;
        for (Node n : all) {
            if (n == cur) continue;
            Bounds b = n.localToScene(n.getBoundsInLocal());
            double nx = (b.getMinX() + b.getMaxX()) / 2, ny = (b.getMinY() + b.getMaxY()) / 2;
            double ddx = nx - cx, ddy = ny - cy;
            double main = dx != 0 ? ddx * dx : ddy * dy;
            double side = dx != 0 ? Math.abs(ddy) : Math.abs(ddx);
            if (main <= 4) continue;
            // gleiche Zeile bzw. Spalte bevorzugen, Seitenabstand zaehlt doppelt
            double score = main + side * (dx != 0 ? 3.0 : 2.0);
            if (score < bestScore) { bestScore = score; best = n; }
        }
        if (best != null) best.requestFocus();
    }

    /** Scrollt alle umgebenden ScrollPanes so, dass n sichtbar ist. */
    private void ensureVisible(Node n) {
        for (Node p = n.getParent(); p != null; p = p.getParent()) {
            if (!(p instanceof ScrollPane)) continue;
            ScrollPane sp = (ScrollPane) p;
            Node c = sp.getContent();
            if (c == null) continue;
            Bounds nb = c.sceneToLocal(n.localToScene(n.getBoundsInLocal()));
            if (nb == null) continue;
            Bounds vb = sp.getViewportBounds();
            double hMax = c.getBoundsInLocal().getHeight() - vb.getHeight();
            if (hMax > 1) {
                double top = sp.getVvalue() * hMax, pad = 70;
                double t = top;
                if (nb.getMinY() < top + pad) t = nb.getMinY() - pad;
                else if (nb.getMaxY() > top + vb.getHeight() - pad) t = nb.getMaxY() - vb.getHeight() + pad;
                t = Math.max(0, Math.min(hMax, t));
                if (Math.abs(t - top) > 1) new Timeline(new KeyFrame(Duration.millis(REDUCE ? 1 : 220),
                        new KeyValue(sp.vvalueProperty(), t / hMax, Interpolator.EASE_OUT))).play();
            }
            double wMax = c.getBoundsInLocal().getWidth() - vb.getWidth();
            if (wMax > 1) {
                double left = sp.getHvalue() * wMax, pad = 60;
                double t = left;
                if (nb.getMinX() < left + pad) t = nb.getMinX() - pad;
                else if (nb.getMaxX() > left + vb.getWidth() - pad) t = nb.getMaxX() - vb.getWidth() + pad;
                t = Math.max(0, Math.min(wMax, t));
                if (Math.abs(t - left) > 1) new Timeline(new KeyFrame(Duration.millis(REDUCE ? 1 : 220),
                        new KeyValue(sp.hvalueProperty(), t / wMax, Interpolator.EASE_OUT))).play();
            }
        }
    }

    /** Taste vom Controller oder von der Tastatur in der Oberflaeche verarbeiten. Gibt true zurueck, wenn sie verbraucht wurde. */
    private boolean handleNavKey(KeyCode k, boolean fromPad) {
        Scene sc = stage.getScene();
        Node f = sc.getFocusOwner();
        boolean typing = f instanceof TextInputControl;
        if (player != null) {
            if (fromPad) {
                javafx.event.Event.fireEvent(player, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", k,
                        false, false, false, false));
                return true;
            }
            return false;
        }
        if (typing && !fromPad) return false;
        switch (k) {
            case UP: kbdMode(true); navMove(0, -1); return true;
            case DOWN: kbdMode(true); navMove(0, 1); return true;
            case LEFT: kbdMode(true); navMove(-1, 0); return true;
            case RIGHT: kbdMode(true); navMove(1, 0); return true;
            case ENTER:
                if (fromPad || f != null) {
                    kbdMode(true);
                    if (f instanceof ButtonBase) { ((ButtonBase) f).fire(); return true; }
                    if (f instanceof MenuButton) { ((MenuButton) f).show(); return true; }
                    if (f != null && f.getOnKeyPressed() != null) {
                        f.getOnKeyPressed().handle(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                                false, false, false, false));
                        return true;
                    }
                    if (f == null && fromPad) { navMove(0, 1); return true; }
                }
                return false;
            case ESCAPE:
            case BACK_SPACE:
                if (typing && !fromPad) return false;
                if (f instanceof TextInputControl) root.requestFocus();
                if (!history.isEmpty() || current != null) { back(); return true; }
                return false;
            default:
                return false;
        }
    }

    /** Rechtsklick-Menue fuer Titel. */
    private ContextMenu itemMenu(JfItem i) {
        ContextMenu m = new ContextMenu();
        Fx.animateMenu(m);
        if (i.isPlayable()) {
            MenuItem p = new MenuItem(i.resumeTicks > 0 ? "▶  Fortsetzen" : "▶  Abspielen");
            p.setOnAction(e -> play(i));
            m.getItems().add(p);
        }
        MenuItem d = new MenuItem("ℹ  Details");
        d.setOnAction(e -> open(i));
        MenuItem f = new MenuItem(i.favorite ? "♡  Aus Merkliste entfernen" : "♥  Zur Merkliste");
        f.setOnAction(e -> toggleFavorite(i, null));
        MenuItem w = new MenuItem(i.played ? "○  Als ungesehen markieren" : "✓  Als gesehen markieren");
        w.setOnAction(e -> togglePlayed(i, null));
        m.getItems().addAll(d, new SeparatorMenuItem(), f, w);
        if (i.isPlayable()) {
            MenuItem dl = new MenuItem(Downloads.has(i.id) ? "✓  Offline verfügbar" : "⬇  Herunterladen");
            dl.setOnAction(e -> { if (Downloads.has(i.id)) go(this::downloadsPage); else download(i); });
            m.getItems().add(dl);
        } else if ("Series".equals(i.type)) {
            MenuItem dl = new MenuItem("⬇  Ungesehene Folgen laden");
            dl.setOnAction(e -> downloadSeries(i));
            m.getItems().add(dl);
        }
        boolean isList = "Playlist".equals(i.type) || "BoxSet".equals(i.type) || "CollectionFolder".equals(i.type)
                || "UserView".equals(i.type) || "Folder".equals(i.type);
        if (!isList && (i.isPlayable() || "Series".equals(i.type))) {
            m.getItems().addAll(new SeparatorMenuItem(), addToMenu(i, true), addToMenu(i, false));
        }
        if (currentList != null && !isList) {
            JfItem cl = currentList;
            boolean pl = "Playlist".equals(cl.type);
            MenuItem rmL = new MenuItem("✕  Aus „" + cl.name + "“ entfernen");
            rmL.setOnAction(e -> bg(() -> {
                if (pl) jf.removeFromPlaylist(cl.id, i.playlistItemId); else jf.removeFromBoxset(cl.id, i.id);
                return true;
            }, r -> { toast("Entfernt"); if (current != null) current.run(); }));
            m.getItems().add(rmL);
        }
        if (i.resumeTicks > 0) {
            MenuItem rm = new MenuItem("✕  Aus „Weiter schauen“ entfernen");
            rm.setOnAction(e -> bg(() -> { jf.clearProgress(i.id); return true; }, r -> {
                i.resumeTicks = 0;
                toast("Aus „Weiter schauen“ entfernt");
                if (current != null) current.run();
            }));
            m.getItems().add(rm);
        }
        return m;
    }

    private void toggleFavorite(JfItem i, Button btn) {
        invalidateCaches();
        boolean on = !i.favorite;
        bg(() -> { jf.setFavorite(i.id, on); return on; }, r -> {
            i.favorite = on;
            if (btn != null) {
                btn.setText(on ? "In Merkliste" : "Merkliste");
                btn.setGraphic(Fx.icon(on ? "heartOn" : "heart", 18, "#ffffff"));
                Fx.pop(btn.getGraphic());
            }
            toast(on ? "„" + i.name + "“ zur Merkliste hinzugefügt" : "„" + i.name + "“ aus der Merkliste entfernt");
        });
    }

    private void togglePlayed(JfItem i, Button btn) {
        invalidateCaches();
        boolean on = !i.played;
        bg(() -> { jf.setPlayed(i.id, on); return on; }, r -> {
            i.played = on;
            if (on) i.resumeTicks = 0;
            if (btn != null) {
                btn.setText(on ? "Gesehen" : "Ungesehen");
                btn.setGraphic(Fx.icon(on ? "check" : "circle", 18, "#ffffff"));
                Fx.pop(btn.getGraphic());
            }
            toast(on ? "Als gesehen markiert" : "Als ungesehen markiert");
        });
    }

    private void open(JfItem i) {
        switch (i.type) {
            case "Movie": case "Series": case "Episode": case "Video": case "MusicVideo":
                go(() -> detail(i.id));
                break;
            case "Season":
                go(() -> detail(i.seriesId != null ? i.seriesId : i.id));
                break;
            default:
                go(() -> listing(i));
        }
    }

    // ------------------------------------------------------------ Bibliotheken / Listen

    private void libraries() {
        chrome("libs");
        FlowPane flow = new FlowPane(20, 24);
        flow.setPadding(new Insets(24, 48, 40, 48));
        VBox page = new VBox(text("Bibliotheken", 30, true), flow);
        page.setPadding(new Insets(28, 0, 0, 48));
        flow.setPadding(new Insets(18, 48, 40, 0));
        setContent(scroll(page));
        bg(jf::views, l -> { int k = 0; for (JfItem v : l) { Node c = card(v, true); reveal(c, k++); flow.getChildren().add(c); } });
    }

    private double cardScale = 1;
    private static final double[] SIZE_SCALE = {0.75, 1.0, 1.3};
    private static final String[] SIZE_NAMES = {"Klein", "Mittel", "Groß"};
    /** Welche Titel eine Bibliothek zeigt (rekursiv, nur Filme/Serien); null = normale Ordneransicht. */
    private static String libTypes(JfItem parent) {
        boolean lib = "CollectionFolder".equals(parent.type) || "UserView".equals(parent.type) || !parent.collectionType.isEmpty();
        if (!lib) return null;
        switch (parent.collectionType) {
            case "movies": return "Movie";
            case "tvshows": return "Series";
            case "boxsets": return "BoxSet";
            case "music": case "musicvideos": case "books": case "photos": case "playlists": case "livetv": return null;
            default: return "Movie,Series";
        }
    }

    private static final String[] LIB_VIEWS = {"Poster", "Info", "Liste", "Quer"};

    private String metaLine(JfItem i) {
        StringBuilder m = new StringBuilder();
        if (i.year > 0) m.append(i.year).append("   ");
        if (!i.runtime().isEmpty()) m.append(i.runtime()).append("   ");
        if (i.rating > 0) m.append("★ ").append(String.format("%.1f", i.rating));
        return m.toString().trim();
    }

    private HBox genreChips(JfItem i, int max) {
        HBox box = new HBox(6);
        box.setAlignment(Pos.CENTER_LEFT);
        if (i.genres.isEmpty()) return box;
        int n = 0;
        for (String g : i.genres.split(" · ")) {
            if (n++ >= max) break;
            String gc = Genres.color(g.trim());
            Label gl = text(g.trim(), 11, true);
            gl.setWrapText(false);
            gl.setStyle(gl.getStyle() + "-fx-text-fill:" + gc + ";-fx-background-color:" + css(Color.web(gc), 0.16)
                    + ";-fx-background-radius:999;-fx-padding:2 9 2 9;");
            box.getChildren().add(gl);
        }
        return box;
    }

    /** Info-Karte: Poster links, rechts Titel, Daten, Genres, Kurzbeschreibung und Knoepfe. */
    private HBox infoCard(JfItem i) {
        double pw = 118, ph = 177;
        StackPane pic = picture(jf.poster(i), pw, ph, 12, 0.2);
        Label t = text("Episode".equals(i.type) && !i.seriesName.isEmpty() ? i.seriesName : i.name, 17, true);
        t.setWrapText(false);
        t.setTextOverrun(OverrunStyle.ELLIPSIS);
        Label m = muted(metaLine(i), 13);
        Label ov = muted(i.overview, 13);
        ov.setWrapText(true);
        ov.setMaxHeight(58);
        ov.setStyle(ov.getStyle() + "-fx-text-fill:#aab6c3;");
        Button play = button("▶", "btn-primary");
        play.setStyle("-fx-padding: 6 14 6 16; -fx-font-size: 13px;");
        play.setOnAction(e -> playOrNext(i));
        play.setVisible(i.isPlayable() || "Series".equals(i.type));
        Button fav = button(i.favorite ? "♥" : "♡", "icon-btn");
        fav.setOnAction(e -> { toggleFavorite(i, null); fav.setText(i.favorite ? "♡" : "♥"); });
        HBox btns = new HBox(8, play, fav);
        btns.setAlignment(Pos.CENTER_LEFT);
        VBox txt = new VBox(7, t, m, genreChips(i, 3), ov, btns);
        txt.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(txt, Priority.ALWAYS);
        HBox card = new HBox(16, pic, txt);
        card.setPadding(new Insets(12));
        card.setPrefWidth(520);
        card.setMinWidth(520);
        card.setMaxWidth(520);
        String base = "-fx-background-radius:16;-fx-background-color:";
        card.setStyle(base + SURFACE + ";");
        card.setCursor(Cursor.HAND);
        card.setOnMouseEntered(e -> card.setStyle(base + SURFACE2 + ";-fx-effect:dropshadow(gaussian," + css(Color.web(A1), 0.35) + ",18,0.1,0,2);"));
        card.setOnMouseExited(e -> card.setStyle(base + SURFACE + ";"));
        card.setOnMouseClicked(e -> { if (e.getButton() == MouseButton.PRIMARY && !(e.getTarget() instanceof Button)
                && !(e.getTarget() instanceof Label && ((Label) e.getTarget()).getParent() instanceof Button)) openFrom(card, i); });
        card.setOnContextMenuRequested(e -> { itemMenu(i).show(card, e.getScreenX(), e.getScreenY()); e.consume(); });
        return card;
    }

    /** Listenzeile: kleines Poster, Titel, Jahr, Laufzeit, Wertung, Genres. */
    private HBox listRow(JfItem i) {
        StackPane pic = picture(jf.poster(i), 44, 66, 8, 0.2);
        Label t = text("Episode".equals(i.type) && !i.seriesName.isEmpty() ? i.seriesName : i.name, 15, true);
        t.setWrapText(false);
        t.setTextOverrun(OverrunStyle.ELLIPSIS);
        t.setPrefWidth(340);
        t.setMinWidth(340);
        Label y = muted(i.year > 0 ? String.valueOf(i.year) : "", 13);
        y.setMinWidth(54);
        Label rt = muted(i.runtime(), 13);
        rt.setMinWidth(80);
        Label r = muted(i.rating > 0 ? "★ " + String.format("%.1f", i.rating) : "", 13);
        r.setMinWidth(54);
        Region fill = new Region();
        HBox.setHgrow(fill, Priority.ALWAYS);
        Button fav = button(i.favorite ? "♥" : "♡", "icon-btn");
        fav.setOnAction(e -> { toggleFavorite(i, null); fav.setText(i.favorite ? "♡" : "♥"); });
        HBox row = new HBox(16, pic, t, y, rt, r, genreChips(i, 3), fill, fav);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 14, 6, 10));
        String base = "-fx-background-radius:12;-fx-background-color:";
        row.setStyle(base + "rgba(255,255,255,0.04);");
        row.setCursor(Cursor.HAND);
        row.setOnMouseEntered(e -> row.setStyle(base + "rgba(255,255,255,0.10);"));
        row.setOnMouseExited(e -> row.setStyle(base + "rgba(255,255,255,0.04);"));
        row.setOnMouseClicked(e -> { if (e.getButton() == MouseButton.PRIMARY && !(e.getTarget() instanceof Button)
                && !(e.getTarget() instanceof Label && ((Label) e.getTarget()).getParent() instanceof Button)) openFrom(row, i); });
        row.setOnContextMenuRequested(e -> { itemMenu(i).show(row, e.getScreenX(), e.getScreenY()); e.consume(); });
        return row;
    }

    private void listing(JfItem parent) {
        chrome("libs");
        currentList = ("Playlist".equals(parent.type) || "BoxSet".equals(parent.type)) ? parent : null;
        boolean playlist = "Playlist".equals(parent.type);
        boolean library = "CollectionFolder".equals(parent.type) || "UserView".equals(parent.type)
                || !parent.collectionType.isEmpty();
        FlowPane flow = new FlowPane(20, 26);
        flow.setPadding(new Insets(10, 48, 10, 48));
        Label title = text(parent.name, 30, true);
        Label count = muted("", 14);
        ComboBox<String> sort = new ComboBox<>();
        sort.getItems().addAll("Name", "Neu hinzugefügt", "Erscheinungsjahr", "Bewertung", "Zufällig");
        sort.getSelectionModel().select(0);
        ToggleButton unseen = new ToggleButton("Ungesehen"), fav = new ToggleButton("♥ Favoriten");
        unseen.getStyleClass().add("toggle-pill");
        fav.getStyleClass().add("toggle-pill");
        ComboBox<String> genre = new ComboBox<>();
        genre.setPromptText("Genre");
        genre.setVisibleRowCount(14);
        Button rnd = button("🎲 Zufall", "toggle-pill");
        rnd.setOnAction(e -> bg(() -> {
            ArrayList<JfItem> l = jf.children(parent.id, 0, 1, "Random", "Ascending", "", null, libTypes(parent));
            return l.isEmpty() ? null : l.get(0);
        }, r -> { if (r != null) open(r); }));
        int[] start = {0};
        int[] gen = {0};
        int[] pg = {0};
        int[] shown = {0};
        int[] hidden = {0};
        boolean[] mute = {false};
        ArrayList<JfItem> all = new ArrayList<>();
        int[] view = {Math.max(0, java.util.Arrays.asList(LIB_VIEWS).indexOf(settings.get("libView", LIB_VIEWS[0])))};
        ScrollPane[] spRef = new ScrollPane[1];
        lazyPending.clear();
        Runnable[] pump = new Runnable[1];
        Runnable[] loadRef = new Runnable[1];
        int[] size = {Math.max(0, Math.min(2, settings.getInt("libSize", 1)))};
        ComboBox<String> sizeBox = new ComboBox<>();
        sizeBox.getItems().addAll(SIZE_NAMES);
        sizeBox.getSelectionModel().select(size[0]);
        Runnable applyGaps = () -> {
            flow.setHgap(view[0] == 2 ? 0 : 20);
            flow.setVgap(view[0] == 2 ? 6 : view[0] == 1 ? 18 : 26);
        };
        applyGaps.run();
        pump[0] = () -> {
            int my = pg[0];
            int end = Math.min(all.size(), shown[0] + 30);
            lazyActive = lazyPending;
            cardScale = (view[0] == 0 || view[0] == 3) ? SIZE_SCALE[size[0]] : 1;
            try {
                for (; shown[0] < end; shown[0]++) {
                    JfItem i = all.get(shown[0]);
                    Node c;
                    switch (view[0]) {
                        case 1: c = infoCard(i); break;
                        case 2: { HBox r = listRow(i); r.prefWidthProperty().bind(flow.widthProperty().subtract(96)); c = r; break; }
                        case 3: c = card(i, true); break;
                        default: c = card(i, false);
                    }
                    if (shown[0] < 24) reveal(c, shown[0]);
                    flow.getChildren().add(c);
                }
            } finally {
                lazyActive = null;
                cardScale = 1;
            }
            Platform.runLater(() -> { flow.applyCss(); flow.layout(); lazyCheck(spRef[0]); });
            if (shown[0] < all.size()) Platform.runLater(() -> { if (my == pg[0]) pump[0].run(); });
        };
        Runnable load = () -> {
            int idx = Math.max(0, sort.getSelectionModel().getSelectedIndex());
            String sb = new String[]{"SortName", "DateCreated", "ProductionYear,SortName", "CommunityRating", "Random"}[idx];
            String order = idx == 0 || idx == 4 ? "Ascending" : "Descending";
            String filters = (unseen.isSelected() ? "IsUnplayed," : "") + (fav.isSelected() ? "IsFavorite," : "");
            if (filters.endsWith(",")) filters = filters.substring(0, filters.length() - 1);
            String f = filters, g = genre.getValue();
            int st = start[0], myGen = gen[0];
            bg(() -> {
                ArrayList<JfItem> l = playlist ? jf.playlistItems(parent.id, st, 100)
                        : jf.children(parent.id, st, 100, sb, order, f, g, libTypes(parent));
                return new Object[]{l, jf.lastTotal, jf.lastRaw};
            }, r -> {
                if (myGen != gen[0]) return; // veraltete Antwort nach Filterwechsel
                @SuppressWarnings("unchecked") ArrayList<JfItem> l = (ArrayList<JfItem>) r[0];
                all.addAll(l);
                int raw = (Integer) r[2]; // Seitenschritt zaehlt auch ausgeblendete Samples mit
                start[0] += raw;
                hidden[0] += raw - l.size();
                int total = (Integer) r[1];
                count.setText((total - hidden[0]) + " Titel");
                pump[0].run();
                // alles automatisch nachladen, Seite fuer Seite
                if (start[0] < total && raw > 0 && root.getCenter() != null && flow.getScene() != null) loadRef[0].run();
            });
        };
        loadRef[0] = load;
        Runnable reset = () -> {
            if (mute[0]) return;
            gen[0]++;
            pg[0]++;
            flow.getChildren().clear();
            lazyPending.clear();
            all.clear();
            shown[0] = 0;
            start[0] = 0;
            hidden[0] = 0;
            load.run();
        };
        ToggleGroup vg = new ToggleGroup();
        HBox viewBox = new HBox(6);
        String[] vlabels = {"▦", "▤", "☰", "▭"};
        String[] vtips = {"Poster", "Info-Karten", "Liste", "Querformat"};
        for (int vi = 0; vi < vlabels.length; vi++) {
            ToggleButton tb = new ToggleButton(vlabels[vi]);
            tb.getStyleClass().add("toggle-pill");
            tb.setTooltip(new Tooltip(vtips[vi]));
            tb.setToggleGroup(vg);
            int vv = vi;
            tb.setSelected(vi == view[0]);
            tb.setOnAction(e -> {
                if (!tb.isSelected()) { tb.setSelected(true); return; }
                view[0] = vv;
                sizeBox.setVisible(vv == 0 || vv == 3);
                sizeBox.setManaged(vv == 0 || vv == 3);
                settings.put("libView", LIB_VIEWS[vv]);
                pg[0]++;
                flow.getChildren().clear();
                lazyPending.clear();
                shown[0] = 0;
                applyGaps.run();
                pump[0].run();
            });
            viewBox.getChildren().add(tb);
        }
        sort.setOnAction(e -> reset.run());
        unseen.setOnAction(e -> reset.run());
        fav.setOnAction(e -> reset.run());
        Region fill = new Region();
        HBox.setHgrow(fill, Priority.ALWAYS);
        sizeBox.setOnAction(e -> {
            int ix = Math.max(0, sizeBox.getSelectionModel().getSelectedIndex());
            if (ix == size[0]) return;
            size[0] = ix;
            settings.putInt("libSize", ix);
            pg[0]++;
            flow.getChildren().clear();
            lazyPending.clear();
            shown[0] = 0;
            pump[0].run();
        });
        sizeBox.setVisible(view[0] == 0 || view[0] == 3);
        sizeBox.setManaged(view[0] == 0 || view[0] == 3);
        HBox bar = new HBox(12, title, count, fill, viewBox, sizeBox, sort);
        if (!playlist) bar.getChildren().addAll(unseen, fav);
        if (currentList != null) {
            Button del = button("🗑 Liste löschen", "toggle-pill");
            del.setOnAction(e -> {
                Alert al = new Alert(Alert.AlertType.CONFIRMATION, "„" + parent.name + "“ wirklich löschen? Die Filme selbst bleiben erhalten.", ButtonType.YES, ButtonType.NO);
                al.initOwner(stage);
                al.showAndWait().ifPresent(bt -> {
                    if (bt == ButtonType.YES) bg(() -> { jf.deleteItem(parent.id); return true; }, r -> { toast("Gelöscht"); back(); });
                });
            });
            bar.getChildren().add(del);
        }
        if (library) bar.getChildren().add(rnd);
        if (library) {
            bar.getChildren().add(genre);
            bg(() -> jf.genres(parent.id), g -> { genre.getItems().add("Alle Genres"); genre.getItems().addAll(g); });
            genre.setOnAction(e -> {
                if (mute[0]) return;
                if ("Alle Genres".equals(genre.getValue())) {
                    mute[0] = true;
                    genre.setValue(null);
                    mute[0] = false;
                }
                reset.run();
            });
        }
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(24, 48, 10, 48));
        flow.setPadding(new Insets(10, 48, 40, 48));
        ScrollPane lsp = scroll(new VBox(flow));
        spRef[0] = lsp;
        PauseTransition lazyDebounce = new PauseTransition(Duration.millis(120));
        lazyDebounce.setOnFinished(e -> lazyCheck(lsp));
        lsp.vvalueProperty().addListener((o, x, y) -> lazyDebounce.playFromStart());
        lsp.heightProperty().addListener((o, x, y) -> lazyDebounce.playFromStart());
        setContent(new BorderPane(lsp, bar, null, null, null));
        load.run();
    }

    // ------------------------------------------------------------ Gemeinsam schauen (SyncPlay)

    private SyncPlay sync;
    private boolean syncStarting;

    SyncPlay sync() { return sync; }

    private void syncReset() {
        if (sync != null) { sync.shutdown(); sync = null; }
    }

    /** Die Gruppe startet einen Titel: bei allen oeffnen, pausiert, bis alle bereit sind. */
    void syncStart(String itemId, long startTicks) {
        bg(() -> jf.item(itemId), it -> {
            it.resumeTicks = startTicks;
            Runnable go = () -> { syncStarting = true; try { play(it); } finally { syncStarting = false; } };
            if (player != null) { player.close(); Platform.runLater(go); } else go.run();
        });
    }

    void syncCommand(String cmd, long posMs, long delayMs) {
        if (player != null) player.syncCommand(cmd, posMs, delayMs);
    }

    private void syncPage() {
        chrome("");
        if (sync == null) sync = new SyncPlay(jf, this);
        VBox page = new VBox(18);
        page.setPadding(new Insets(28, 48, 50, 48));
        VBox body = new VBox(14);
        page.getChildren().addAll(text("Gemeinsam schauen", 30, true),
                muted("Alle in einer Gruppe sehen denselben Titel zur selben Zeit. Pause und Spulen gelten für alle.", 13), body);
        setContent(scroll(page));
        int[] shown = {-1};
        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            shown[0] = sync.version;
            body.getChildren().clear();
            if (sync.inGroup()) {
                Label g = text("👥  " + sync.groupName, 20, true);
                Label who = muted("Dabei: " + (sync.participants.isEmpty() ? "nur du" : String.join(", ", sync.participants)), 14);
                Label hint = muted("Wähle jetzt irgendeinen Film oder eine Folge und drücke Abspielen — es startet bei allen.", 14);
                Button leave = button("Gruppe verlassen", "btn-ghost");
                leave.setOnAction(e -> bg(() -> { sync.leave(); return true; }, r -> { toast("Gruppe verlassen"); rebuild[0].run(); }));
                VBox card = new VBox(10, g, who, hint, leave);
                card.setPadding(new Insets(20, 24, 20, 24));
                card.setStyle("-fx-background-color:" + SURFACE + ";-fx-background-radius:16;");
                body.getChildren().add(card);
            } else {
                Button create = button("➕  Neue Gruppe", "btn-primary");
                create.setOnAction(e -> askName("Neue Gruppe", "Name der Gruppe:", n -> {
                    String name = n.isBlank() ? (jf.userName + "s Gruppe") : n.trim();
                    bg(() -> { sync.create(name); return true; }, r -> { });
                }));
                Button refresh = button("↻  Aktualisieren", "btn-ghost");
                refresh.setOnAction(e -> rebuild[0].run());
                body.getChildren().add(new HBox(12, create, refresh));
                VBox groups = new VBox(10, loader());
                body.getChildren().add(groups);
                bg(sync::list, arr -> {
                    groups.getChildren().clear();
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject o = arr.getJSONObject(i);
                        String id = o.optString("GroupId");
                        org.json.JSONArray ps = o.optJSONArray("Participants");
                        Label n = text(o.optString("GroupName"), 17, true);
                        Label m = muted((ps == null ? "" : ps.length() + " Teilnehmer: " + ps.toList().toString().replaceAll("[\\[\\]]", "")), 12);
                        VBox mid = new VBox(4, n, m);
                        HBox.setHgrow(mid, Priority.ALWAYS);
                        Button j = button("Beitreten", "btn-primary");
                        j.setOnAction(e -> bg(() -> { sync.join(id); return true; }, r -> { }));
                        HBox row = new HBox(16, mid, j);
                        row.setAlignment(Pos.CENTER_LEFT);
                        row.setPadding(new Insets(14, 18, 14, 18));
                        row.setStyle("-fx-background-color:" + SURFACE + ";-fx-background-radius:14;");
                        groups.getChildren().add(row);
                    }
                    if (arr.length() == 0) groups.getChildren().add(muted("Noch keine Gruppen. Erstelle die erste und lade andere ein, ihr beizutreten.", 14));
                });
            }
        };
        rebuild[0].run();
        Timeline poll = new Timeline(new KeyFrame(Duration.millis(600), ev -> { if (shown[0] != sync.version) rebuild[0].run(); }));
        poll.setCycleCount(Animation.INDEFINITE);
        poll.play();
        page.sceneProperty().addListener((o, a, b) -> { if (b == null) poll.stop(); });
    }

    // ------------------------------------------------------------ Auto-Update

    private String updateUrl() {
        String u = settings.get("updateUrl", Updater.DEFAULT_URL);
        if (u.contains("192.168.178.80:8099")) u = Updater.DEFAULT_URL; // alter Heimserver-Weg, jetzt GitHub
        return System.getProperty("jf.updateurl", u);
    }

    /** Fragt den Update-Server; silent = bei "nichts Neues"/Fehler keine Meldung. */
    private void checkUpdate(boolean silent) {
        Thread t = new Thread(() -> {
            Updater.cleanup();
            try {
                Updater.Info i = Updater.check(updateUrl());
                Platform.runLater(() -> {
                    if (i == null) { if (!silent) toast("Du hast die neueste Version (" + Updater.currentVersion() + ")"); return; }
                    offerUpdate(i);
                });
            } catch (Exception e) {
                Log.i("Update-Pruefung: " + e);
                if (!silent) Platform.runLater(() -> toast("Update-Server nicht erreichbar"));
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private void playTrailer(JfItem it) {
        toast("Trailer wird gesucht …");
        Thread t = new Thread(() -> {
            try {
                ArrayList<JfItem> local = jf.localTrailers(it.id);
                if (!local.isEmpty()) { JfItem l = local.get(0); Platform.runLater(() -> play(l)); return; }
                String target = !it.trailerUrl.isEmpty() ? it.trailerUrl
                        : "ytsearch1:" + it.name + (it.year > 0 ? " (" + it.year + ")" : "") + " offizieller Trailer deutsch";
                java.util.List<String> urls = Trailers.fetch(target, m -> Platform.runLater(() -> toast(m)));
                JfItem tr = new JfItem();
                tr.id = "trailer-" + it.id;
                tr.name = it.name + " – Trailer";
                tr.type = "Movie";
                tr.externalUrl = urls.get(0);
                tr.externalAudio = urls.size() > 1 ? "file://" + new java.io.File(urls.get(1)).toURI().getRawPath() : null;
                Platform.runLater(() -> play(tr));
            } catch (Exception ex) {
                Log.e("Trailer", ex);
                Platform.runLater(() -> toast("Trailer nicht gefunden: " + ex.getMessage()));
            }
        }, "trailer");
        t.setDaemon(true);
        t.start();
    }

    /** "Überrasch mich": zufälliger Titel mit stummer Szenenvorschau. */
    private javafx.stage.Stage surpriseStage;

    private void surprise() {
        if (player != null) return;
        if (surpriseStage != null) { surpriseStage.toFront(); return; }
        javafx.stage.Stage dlg = new javafx.stage.Stage(javafx.stage.StageStyle.TRANSPARENT);
        surpriseStage = dlg;
        dlg.initOwner(stage);
        dlg.initModality(javafx.stage.Modality.WINDOW_MODAL);
        double w = 760, ih = 340;
        ImageView vid = new ImageView();
        vid.setFitWidth(w);
        vid.setFitHeight(ih);
        vid.setPreserveRatio(false);
        vid.setOpacity(0);
        StackPane picHolder = new StackPane();
        picHolder.setMinSize(w, ih);
        picHolder.setMaxSize(w, ih);
        Region fade = new Region();
        fade.setStyle("-fx-background-color:linear-gradient(to top," + SURFACE + " 0%,rgba(20,26,35,0) 65%);");
        Label dice = text("🎲", 34, true);
        StackPane media = new StackPane(picHolder, vid, fade);
        media.setMinSize(w, ih);
        media.setMaxSize(w, ih);
        Rectangle clip = new Rectangle(w, ih + 30);
        clip.setArcWidth(40);
        clip.setArcHeight(40);
        media.setClip(clip);
        Label title = text("", 26, true);
        Label meta = muted("", 14);
        Label ov = muted("", 14);
        ov.setWrapText(true);
        ov.setMaxWidth(w - 56);
        ov.setMaxHeight(64);
        FlowPane chips = new FlowPane(8, 6);
        Button play = iconBtn("play", "Abspielen", "btn-primary");
        Button info = button("Details", "btn-ghost");
        Button again = button("🎲 Nochmal", "btn-ghost");
        Button close = button("Schließen", "btn-ghost");
        HBox btns = new HBox(10, play, info, again, spacer(1), close);
        HBox.setHgrow(btns.getChildren().get(3), Priority.ALWAYS);
        btns.setAlignment(Pos.CENTER_LEFT);
        VBox body = new VBox(10, chips, title, meta, ov, btns);
        body.setPadding(new Insets(0, 28, 26, 28));
        VBox box = new VBox(media, body);
        VBox.setMargin(body, new Insets(-52, 0, 0, 0));
        box.setPrefWidth(w);
        box.setMaxWidth(w);
        box.setStyle("-fx-background-color:" + SURFACE + ";-fx-background-radius:22;-fx-border-color:rgba(255,255,255,0.10);-fx-border-radius:22;");
        box.setEffect(new DropShadow(36, Color.rgb(0, 0, 0, 0.75)));
        StackPane wrap = new StackPane(box);
        wrap.setPadding(new Insets(24));
        wrap.setStyle("-fx-background-color:transparent;");
        javafx.scene.Scene sc = new javafx.scene.Scene(wrap);
        sc.setFill(Color.TRANSPARENT);
        sc.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        dlg.setScene(sc);
        JfItem[] cur = {null};
        long[] gen = {0};
        Runnable[] roll = new Runnable[1];
        roll[0] = () -> {
            long my = ++gen[0];
            HeroVideo.stop();
            vid.setOpacity(0);
            title.setText("🎲 …");
            bg(() -> jf.randomMovie(), it -> {
                if (my != gen[0] || it == null) { if (it == null) title.setText("Nichts gefunden"); return; }
                cur[0] = it;
                title.setText(it.name);
                StringBuilder m = new StringBuilder();
                if (it.year > 0) m.append(it.year).append("   ");
                if (!it.runtime().isEmpty()) m.append(it.runtime()).append("   ");
                if (it.rating > 0) m.append("★ ").append(String.format("%.1f", it.rating));
                meta.setText(m.toString().trim());
                ov.setText(it.overview);
                chips.getChildren().clear();
                for (String g : it.genres.isEmpty() ? new String[0] : it.genres.split(" · ")) {
                    String gc = Genres.color(g.trim());
                    Label gl = text(g.trim(), 12, true);
                    gl.setStyle(gl.getStyle() + "-fx-text-fill:" + gc + ";-fx-background-color:" + css(Color.web(gc), 0.16)
                            + ";-fx-border-color:" + css(Color.web(gc), 0.55) + ";-fx-border-radius:999;-fx-background-radius:999;-fx-padding:3 11 3 11;");
                    chips.getChildren().add(gl);
                }
                picHolder.getChildren().setAll(picture(jf.backdrop(it), w, ih, 0, 0.2));
                if (heroVideoOn && it.isPlayable() && it.runTicks > 0) {
                    long start = (long) (it.runTicks / 10000000L * (0.15 + 0.5 * Math.random()));
                    PauseTransition wt = new PauseTransition(Duration.millis(600));
                    wt.setOnFinished(ev -> { if (my == gen[0] && dlg.isShowing()) HeroVideo.start(vid, jf.directUrl(it), start); });
                    wt.play();
                }
            });
        };
        play.setOnAction(e -> { JfItem it = cur[0]; if (it != null) { dlg.close(); playOrNext(it); } });
        info.setOnAction(e -> { JfItem it = cur[0]; if (it != null) { dlg.close(); go(() -> detail(it.id)); } });
        again.setOnAction(e -> roll[0].run());
        close.setOnAction(e -> dlg.close());
        sc.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) dlg.close(); });
        dlg.setOnHidden(e -> { gen[0]++; surpriseStage = null; HeroVideo.stop(); });
        dlg.show();
        roll[0].run();
    }

    private void offerUpdate(Updater.Info i) {
        if (player != null) { toast("Update " + i.version + " verfügbar (Menü → Nach Updates suchen)"); return; }
        javafx.stage.Stage dlg = new javafx.stage.Stage(javafx.stage.StageStyle.TRANSPARENT);
        dlg.initOwner(stage);
        dlg.initModality(javafx.stage.Modality.WINDOW_MODAL);
        dlg.setTitle("Update verfügbar");
        boolean self = Updater.canSelfReplace();
        Label icon = text("⬆", 26, true);
        icon.setTextFill(Color.WHITE);
        icon.setStyle(icon.getStyle() + "-fx-background-radius:16;-fx-min-width:52;-fx-min-height:52;-fx-alignment:center;-fx-background-color:linear-gradient(to bottom right," + A1 + "," + A2 + ");");
        Label title = text("Update verfügbar", 22, true);
        Label ver = muted("Version " + Updater.currentVersion() + "  →  " + i.version, 14);
        VBox head = new VBox(2, title, ver);
        HBox top = new HBox(16, icon, head);
        top.setAlignment(Pos.CENTER_LEFT);
        Label notes = muted(i.notes.isEmpty() ? "Eine neue Version steht bereit." : i.notes, 14);
        notes.setWrapText(true);
        notes.setMaxWidth(380);
        if (!self) notes.setText(notes.getText() + "\n\nHier nicht automatisch installierbar:\n" + i.url);
        javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setStyle("-fx-accent:" + A1 + ";");
        Label status = muted("", 13);
        VBox prog = new VBox(8, bar, status);
        prog.setVisible(false);
        prog.setManaged(false);
        Button later = button("Später", "btn-ghost");
        Button go = button(self ? "Jetzt aktualisieren" : "OK", "btn-primary");
        HBox btns = new HBox(10, later, go);
        btns.setAlignment(Pos.CENTER_RIGHT);
        VBox box = new VBox(18, top, notes, prog, btns);
        box.setPadding(new Insets(26));
        box.setPrefWidth(440);
        box.setStyle("-fx-background-color:linear-gradient(to bottom right," + SURFACE2 + "," + BG + ");-fx-background-radius:22;"
                + "-fx-border-color:rgba(255,255,255,0.10);-fx-border-radius:22;");
        StackPane wrap = new StackPane(box);
        wrap.setPadding(new Insets(22));
        wrap.setStyle("-fx-background-color:transparent;");
        box.setEffect(new DropShadow(30, Color.rgb(0, 0, 0, 0.7)));
        javafx.scene.Scene sc = new javafx.scene.Scene(wrap);
        sc.setFill(Color.TRANSPARENT);
        sc.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        dlg.setScene(sc);
        later.setOnAction(e -> dlg.close());
        sc.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE && later.isVisible()) dlg.close(); });
        go.setOnAction(e -> {
            if (!self) { dlg.close(); return; }
            later.setDisable(true);
            go.setDisable(true);
            prog.setVisible(true);
            prog.setManaged(true);
            status.setText("Wird geladen …");
            runUpdate(i, bar, status);
        });
        dlg.show();
    }

    private void runUpdate(Updater.Info i, javafx.scene.control.ProgressBar bar, Label status) {
        Thread t = new Thread(() -> {
            try {
                java.io.File f = Updater.download(i, p -> Platform.runLater(() -> {
                    bar.setProgress(p);
                    status.setText("Wird geladen … " + (int) (p * 100) + " %");
                }));
                Platform.runLater(() -> {
                    bar.setProgress(1);
                    status.setText("Fertig – App startet neu …");
                    PauseTransition w = new PauseTransition(Duration.millis(700));
                    w.setOnFinished(e -> {
                        try {
                            Updater.applyAndRestart(f);
                            Platform.exit();
                            System.exit(0);
                        } catch (Exception ex) {
                            Log.e("Update starten", ex);
                            status.setText("Update ging nicht: " + ex.getMessage());
                        }
                    });
                    w.play();
                });
            } catch (Exception e) {
                Log.e("Update laden", e);
                Platform.runLater(() -> status.setText("Update fehlgeschlagen: " + e.getMessage()));
            }
        }, "update-download");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ Downloads (offline)

    private void download(JfItem it) {
        if (Downloads.has(it.id)) { toast("Schon geladen"); return; }
        if (Downloads.busy(it.id)) { toast("Wird bereits geladen"); return; }
        bg(() -> jf.item(it.id), full -> {
            Downloads.start(jf, full);
            toast("⬇  Download gestartet: " + full.name);
        });
    }

    private void downloadSeries(JfItem series) {
        toast("Suche ungesehene Folgen …");
        bg(() -> {
            java.util.ArrayList<JfItem> todo = new java.util.ArrayList<>();
            for (JfItem se : jf.seasons(series.id))
                for (JfItem ep : jf.episodes(series.id, se.id)) if (!ep.played) todo.add(ep);
            return todo;
        }, todo -> {
            int n = 0;
            for (JfItem ep : todo) {
                if (Downloads.has(ep.id) || Downloads.busy(ep.id)) continue;
                n++;
                bg(() -> jf.item(ep.id), full -> Downloads.start(jf, full));
            }
            toast(n == 0 ? "Nichts Neues zu laden" : "⬇  " + n + " Folgen werden geladen");
        });
    }

    private Button dlButton(JfItem it) {
        Button b = iconBtn(Downloads.has(it.id) ? "check" : "download", Downloads.has(it.id) ? "Geladen" : Downloads.busy(it.id) ? "Lädt …" : "Laden", "btn-ghost");
        b.setOnAction(e -> {
            if (Downloads.has(it.id)) { go(this::downloadsPage); return; }
            download(it);
            b.setText("Lädt …");
        });
        return b;
    }

    private void downloadsPage() {
        chrome("");
        VBox page = new VBox(18);
        page.setPadding(new Insets(28, 48, 50, 48));
        VBox list = new VBox(12);
        page.getChildren().addAll(text("Downloads", 30, true),
                muted("Hier gespeicherte Titel laufen auch ohne Server. Ordner: " + Downloads.dir(), 13), list);
        setContent(scroll(page));
        long[] sig = {-1};
        Runnable refresh = () -> {
            java.util.List<Downloads.Entry> es = Downloads.list();
            long s = es.size() * 1000L + Downloads.active.size();
            for (Downloads.Job j : Downloads.active.values()) s += j.done / 1_000_000L;
            if (s == sig[0]) return;
            sig[0] = s;
            list.getChildren().clear();
            for (Downloads.Job j : Downloads.active.values()) list.getChildren().add(dlRow(j.item, null, j));
            for (Downloads.Entry e : es) list.getChildren().add(dlRow(e.item, e, null));
            if (list.getChildren().isEmpty()) list.getChildren().add(muted(
                    "Noch nichts geladen. Auf einer Detailseite „Laden“ klicken oder per Rechtsklick auf einen Titel.", 14));
        };
        refresh.run();
        Timeline poll = new Timeline(new KeyFrame(Duration.millis(700), ev -> refresh.run()));
        poll.setCycleCount(Animation.INDEFINITE);
        poll.play();
        page.sceneProperty().addListener((o, a, b) -> { if (b == null) poll.stop(); });
    }

    private Node dlRow(JfItem it, Downloads.Entry e, Downloads.Job job) {
        StackPane pic = null;
        if (e != null && e.poster != null) pic = picture(e.poster.toURI().toString(), 64, 96, 8, 0.2);
        else {
            pic = new StackPane(text("⬇", 20, true));
            pic.setMinSize(64, 96);
            pic.setMaxSize(64, 96);
            pic.setStyle("-fx-background-color:rgba(255,255,255,0.06);-fx-background-radius:8;");
        }
        String title = ("Episode".equals(it.type) ? it.seriesName + "  ·  S" + it.seasonNo + ":E" + it.episodeNo + "  ·  " : "") + it.name;
        Label t = text(title, 16, true);
        VBox mid = new VBox(6, t);
        HBox.setHgrow(mid, Priority.ALWAYS);
        HBox row = new HBox(16, pic, mid);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(12, 16, 12, 12));
        row.setStyle("-fx-background-color:" + SURFACE + ";-fx-background-radius:14;");
        if (job != null) {
            ProgressBar pb = new ProgressBar(job.total > 0 ? (double) job.done / job.total : -1);
            pb.setPrefWidth(320); pb.setMaxHeight(8);
            String st = job.error != null ? "Fehler: " + job.error
                    : Downloads.size(job.done) + (job.total > 0 ? " von " + Downloads.size(job.total) : "");
            mid.getChildren().addAll(pb, muted(st, 12));
            Button x = button("Abbrechen", "btn-ghost");
            x.setOnAction(ev -> Downloads.cancel(it.id));
            row.getChildren().add(x);
        } else {
            mid.getChildren().add(muted(Downloads.size(e.size) + (it.year > 0 ? "  ·  " + it.year : "")
                    + (it.runTicks > 0 ? "  ·  " + it.runTicks / 600000000L + " Min" : ""), 12));
            Button play = button("▶  Abspielen", "btn-primary");
            play.setOnAction(ev -> play(it));
            Button del = button("✕  Löschen", "btn-ghost");
            del.setOnAction(ev -> { Downloads.delete(it.id); toast("Gelöscht"); go(this::downloadsPage); });
            row.getChildren().addAll(play, del);
        }
        return row;
    }

    // ------------------------------------------------------------ Statistik

    private Node statTile(String big, String small, int idx) {
        Label b = text(big, 34, true);
        b.setStyle(b.getStyle() + "-fx-font-weight:800;-fx-text-fill:" + A1 + ";");
        VBox v = new VBox(4, b, muted(small, 13));
        v.setPadding(new Insets(18, 26, 18, 26));
        v.setMinWidth(190);
        v.setStyle("-fx-background-color:" + SURFACE + ";-fx-background-radius:16;");
        reveal(v, idx);
        return v;
    }

    private Node barList(String title, List<String[]> rows) {
        VBox box = new VBox(10);
        box.setPadding(new Insets(20, 24, 20, 24));
        box.setStyle("-fx-background-color:" + SURFACE + ";-fx-background-radius:16;");
        box.setMinWidth(380);
        box.setPrefWidth(420);
        box.getChildren().add(text(title, 17, true));
        double max = 1;
        for (String[] r : rows) max = Math.max(max, Double.parseDouble(r[1]));
        int k = 0;
        for (String[] r : rows) {
            Label n = text(r[0], 13, false);
            n.setMaxWidth(260);
            n.setTextOverrun(OverrunStyle.ELLIPSIS);
            Region bar = new Region();
            bar.setMinHeight(8);
            bar.setMaxHeight(8);
            bar.setPrefWidth(0);
            bar.setStyle("-fx-background-radius:4;-fx-background-color: linear-gradient(to right, " + A1 + ", " + A2 + ");");
            double w = 300 * Double.parseDouble(r[1]) / max;
            Timeline tl = new Timeline(new KeyFrame(Duration.millis(REDUCE ? 1 : 700 + k * 60),
                    new KeyValue(bar.prefWidthProperty(), w, Interpolator.EASE_OUT)));
            tl.play();
            Label v = muted(r[2], 12);
            v.setMinWidth(Region.USE_PREF_SIZE);
            v.setWrapText(false);
            HBox line = new HBox(10, bar, v);
            line.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(new VBox(3, n, line));
            k++;
        }
        if (rows.isEmpty()) box.getChildren().add(muted("Noch keine Daten.", 13));
        return box;
    }

    private void stats() {
        chrome("");
        VBox page = new VBox(22);
        page.setPadding(new Insets(28, 48, 50, 48));
        page.getChildren().add(text("Deine Statistik", 30, true));
        page.getChildren().add(loader());
        setContent(scroll(page));
        bg(jf::playedItems, list -> {
            page.getChildren().remove(1);
            long totalMin = 0;
            int movies = 0, eps = 0;
            java.util.Map<String, Integer> series = new java.util.HashMap<>();
            java.util.Map<String, Long> genres = new java.util.HashMap<>();
            java.time.LocalDate today = java.time.LocalDate.now();
            long[] perDay = new long[14];
            for (JfItem i : list) {
                long min = i.runTicks / 600000000L;
                totalMin += min;
                if ("Movie".equals(i.type)) movies++;
                else { eps++; if (!i.seriesName.isEmpty()) series.merge(i.seriesName, 1, Integer::sum); }
                if (!i.genres.isEmpty()) for (String g : i.genres.split(" · ")) genres.merge(g.trim(), min, Long::sum);
                try {
                    java.time.LocalDate d = java.time.OffsetDateTime.parse(i.lastPlayed)
                            .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate();
                    long ago = java.time.temporal.ChronoUnit.DAYS.between(d, today);
                    if (ago >= 0 && ago < 14) perDay[(int) (13 - ago)] += min;
                } catch (Exception ignored) { }
            }
            HBox tiles = new HBox(16,
                    statTile(totalMin / 60 + " Std " + totalMin % 60 + " Min", "gesehene Zeit insgesamt", 0),
                    statTile(String.valueOf(movies), "Filme gesehen", 1),
                    statTile(String.valueOf(eps), "Folgen gesehen", 2),
                    statTile(String.valueOf(series.size()), "Serien angefangen", 3));
            page.getChildren().add(tiles);

            // Balkendiagramm der letzten 14 Tage
            HBox chart = new HBox(10);
            chart.setAlignment(Pos.BOTTOM_LEFT);
            chart.setMinHeight(190);
            chart.setPrefHeight(190);
            long maxDay = 1;
            for (long v : perDay) maxDay = Math.max(maxDay, v);
            for (int d = 0; d < 14; d++) {
                java.time.LocalDate day = today.minusDays(13 - d);
                Region bar = new Region();
                bar.setMinWidth(30);
                bar.setPrefWidth(30);
                bar.setMaxWidth(30);
                bar.setMinHeight(0);
                bar.setPrefHeight(0);
                bar.setMaxHeight(Region.USE_PREF_SIZE);
                bar.setStyle("-fx-background-radius:6;-fx-background-color: linear-gradient(to top, " + A1 + ", " + A2 + ");");
                double h = perDay[d] == 0 ? 3 : 20 + 120.0 * perDay[d] / maxDay;
                new Timeline(new KeyFrame(Duration.millis(REDUCE ? 1 : 600 + d * 45),
                        new KeyValue(bar.prefHeightProperty(), h, Interpolator.EASE_OUT))).play();
                Tooltip.install(bar, new Tooltip(day.getDayOfMonth() + "." + day.getMonthValue() + ".: " + perDay[d] + " Min"));
                Label lab = muted(day.getDayOfMonth() + ".", 11);
                Label val = muted(perDay[d] > 0 ? perDay[d] + "'" : "", 10);
                VBox col = new VBox(4, val, bar, lab);
                col.setAlignment(Pos.BOTTOM_CENTER);
                chart.getChildren().add(col);
            }
            VBox chartBox = new VBox(14, text("Letzte 14 Tage (Minuten pro Tag)", 17, true), chart);
            chartBox.setPadding(new Insets(20, 24, 20, 24));
            chartBox.setStyle("-fx-background-color:" + SURFACE + ";-fx-background-radius:16;");
            chartBox.setMaxWidth(640);
            page.getChildren().add(chartBox);

            List<String[]> topS = new ArrayList<>();
            series.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(6)
                    .forEach(e -> topS.add(new String[]{e.getKey(), String.valueOf(e.getValue()), e.getValue() + " Folgen"}));
            List<String[]> topG = new ArrayList<>();
            genres.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(6)
                    .forEach(e -> topG.add(new String[]{e.getKey(), String.valueOf(e.getValue()), e.getValue() / 60 + " Std " + e.getValue() % 60 + " Min"}));
            HBox lists = new HBox(18, barList("Meistgesehene Serien", topS), barList("Lieblings-Genres", topG));
            page.getChildren().add(lists);
            if (list.isEmpty()) page.getChildren().add(muted("Sobald du etwas gesehen hast, erscheinen hier deine Zahlen.", 15));
        });
    }

    // ------------------------------------------------------------ Wiedergabelisten und Sammlungen

    private void askName(String title, String prompt, Consumer<String> done) {
        TextInputDialog d = new TextInputDialog();
        d.initOwner(stage);
        d.setTitle(title);
        d.setHeaderText(title);
        d.setContentText(prompt);
        d.getDialogPane().getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        d.getDialogPane().setStyle("-fx-background-color:#121722;");
        d.showAndWait().ifPresent(s -> { if (!s.trim().isEmpty()) done.accept(s.trim()); });
    }

    private void listsPage() {
        chrome("lists");
        VBox page = new VBox(20);
        page.setPadding(new Insets(28, 0, 40, 0));
        Button np = button("＋ Neue Wiedergabeliste", "btn-primary"), nc = button("＋ Neue Sammlung", "btn-ghost");
        np.setOnAction(e -> askName("Neue Wiedergabeliste", "Name:", n -> bg(() -> { jf.createPlaylist(n, null); return true; },
                r -> { toast("Wiedergabeliste „" + n + "“ angelegt"); current.run(); })));
        nc.setOnAction(e -> askName("Neue Sammlung", "Name:", n -> bg(() -> { jf.createBoxset(n, null); return true; },
                r -> { toast("Sammlung „" + n + "“ angelegt"); current.run(); })));
        Region fill = new Region();
        HBox.setHgrow(fill, Priority.ALWAYS);
        HBox head = new HBox(12, text("Listen und Sammlungen", 30, true), fill, np, nc);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(0, 48, 0, 48));
        page.getChildren().add(head);
        VBox holder = new VBox(10);
        page.getChildren().add(holder);
        page.getChildren().add(loader());
        setContent(scroll(page));
        bg(() -> new Object[]{jf.playlists(), jf.boxsets()}, r -> {
            page.getChildren().removeIf(n -> !(n == head || n == holder));
            @SuppressWarnings("unchecked") List<JfItem> pl = (List<JfItem>) r[0];
            @SuppressWarnings("unchecked") List<JfItem> bs = (List<JfItem>) r[1];
            if (!pl.isEmpty()) holder.getChildren().add(row("Wiedergabelisten", pl, false));
            if (!bs.isEmpty()) holder.getChildren().add(row("Sammlungen", bs, false));
            if (pl.isEmpty() && bs.isEmpty()) {
                Label hint = muted("Noch nichts angelegt. Rechtsklick auf einen Film oder eine Serie → „Zu Liste hinzufügen“.", 15);
                hint.setPadding(new Insets(10, 48, 0, 48));
                holder.getChildren().add(hint);
            }
        });
    }

    /** Untermenue "Zu Wiedergabeliste / Sammlung hinzufuegen", Listen werden beim Oeffnen geladen. */
    private Menu addToMenu(JfItem i, boolean playlist) {
        Menu m = new Menu(playlist ? "➕  Zur Wiedergabeliste" : "➕  Zur Sammlung");
        MenuItem loading = new MenuItem("Lädt …");
        loading.setDisable(true);
        m.getItems().add(loading);
        m.setOnShowing(e -> bg(() -> playlist ? jf.playlists() : jf.boxsets(), l -> {
            m.getItems().clear();
            MenuItem neu = new MenuItem("＋ Neue " + (playlist ? "Wiedergabeliste" : "Sammlung") + " …");
            neu.setOnAction(x -> askName(playlist ? "Neue Wiedergabeliste" : "Neue Sammlung", "Name:", n -> bg(() -> {
                if (playlist) jf.createPlaylist(n, i.id); else jf.createBoxset(n, i.id);
                return true;
            }, r -> toast("„" + i.name + "“ in „" + n + "“ gelegt"))));
            m.getItems().add(neu);
            if (!l.isEmpty()) m.getItems().add(new SeparatorMenuItem());
            for (JfItem t : l) {
                MenuItem mi = new MenuItem(t.name);
                mi.setOnAction(x -> bg(() -> {
                    if (playlist) jf.addToPlaylist(t.id, i.id); else jf.addToBoxset(t.id, i.id);
                    return true;
                }, r -> toast("„" + i.name + "“ zu „" + t.name + "“ hinzugefügt")));
                m.getItems().add(mi);
            }
        }));
        return m;
    }

    private void favorites() {
        chrome("fav");
        FlowPane flow = new FlowPane(20, 26);
        flow.setPadding(new Insets(18, 48, 40, 0));
        VBox page = new VBox(text("Meine Merkliste", 30, true), flow);
        page.setPadding(new Insets(28, 0, 0, 48));
        setContent(scroll(page));
        bg(jf::favorites, l -> {
            if (l.isEmpty()) flow.getChildren().add(muted("Noch nichts gemerkt. Rechtsklick auf einen Titel → „Zur Merkliste“.", 16));
            int k = 0;
            for (JfItem i : l) { Node c = card(i, false); reveal(c, k++); flow.getChildren().add(c); }
        });
    }

    private void search(String q) {
        chrome("");
        searchField.setText(q);
        FlowPane flow = new FlowPane(20, 26);
        flow.setPadding(new Insets(18, 48, 40, 0));
        VBox page = new VBox(text("Ergebnisse für „" + q + "“", 28, true), flow);
        page.setPadding(new Insets(28, 0, 0, 48));
        setContent(scroll(page));
        bg(() -> jf.search(q), l -> {
            if (l.isEmpty()) flow.getChildren().add(muted("Nichts gefunden.", 16));
            int k = 0;
            for (JfItem i : l) { Node c = card(i, false); reveal(c, k++); flow.getChildren().add(c); }
        });
    }

    // ------------------------------------------------------------ Details

    /** Haupt-Farbton eines Bildes (kraeftig, hell genug) ermitteln; null wenn nichts brauchbar. */
    private void posterColor(String url, Consumer<Color> done) {
        if (url == null) return;
        Image img = new Image(url, 48, 0, true, true, true);
        Runnable calc = () -> {
            try {
                if (img.isError() || img.getWidth() <= 0) return;
                javafx.scene.image.PixelReader pr = img.getPixelReader();
                double r = 0, g = 0, b = 0, wsum = 0;
                for (int y = 0; y < (int) img.getHeight(); y++)
                    for (int x = 0; x < (int) img.getWidth(); x++) {
                        Color c = pr.getColor(x, y);
                        double wgt = c.getSaturation() * c.getSaturation() * (0.25 + c.getBrightness());
                        r += c.getRed() * wgt; g += c.getGreen() * wgt; b += c.getBlue() * wgt; wsum += wgt;
                    }
                if (wsum < 0.5) return;
                Color c = Color.color(r / wsum, g / wsum, b / wsum);
                done.accept(Color.hsb(c.getHue(), Math.max(0.55, c.getSaturation()), Math.max(0.85, c.getBrightness())));
            } catch (Throwable t) {
                Log.e("Posterfarbe", t);
            }
        };
        if (img.getProgress() >= 1) calc.run();
        else img.progressProperty().addListener((o, x, y) -> { if (y.doubleValue() >= 1) calc.run(); });
    }

    private static String css(Color c, double alpha) {
        return String.format("rgba(%d,%d,%d,%.2f)", (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255), alpha);
    }

    private void detail(String id) {
        chrome("");
        VBox page = new VBox(18);
        page.setPadding(new Insets(0, 0, 50, 0));
        page.getChildren().add(loader());
        ScrollPane detailScroll = scroll(page);
        setContent(detailScroll);
        bg(() -> itemCached(id), it -> {
            if (root.getCenter() != detailScroll) return;
            page.getChildren().clear();
            Platform.runLater(() -> releaseVeil(page));
            StackPane stack = new StackPane();
            stack.setAlignment(Pos.TOP_LEFT);
            String bd = jf.backdrop(it);
            if (bd != null) {
                double w = Math.max(1000, root.getWidth());
                StackPane back = picture(bd, w, 520, 0, 0.1);
                back.setMaxHeight(520);
                back.setStyle("-fx-background-color: " + BG + ";"); // kein hellerer Rand unten (sonst sichtbare Linie)
                Region f1 = new Region();
                f1.setStyle("-fx-background-color: linear-gradient(to right, rgba(10,13,18,0.95), rgba(10,13,18,0.55) 55%, rgba(10,13,18,0.25));");
                Region f2 = new Region();
                f2.setStyle("-fx-background-color: linear-gradient(to top, " + BG + " 0%, " + BG + " 2%, rgba(10,13,18,0) 70%);");
                StackPane layer = new StackPane(back, f1, f2);
                layer.setMaxHeight(520);
                layer.setPrefHeight(520);
                layer.setMinHeight(520);
                Rectangle layerClip = new Rectangle();
                layerClip.widthProperty().bind(layer.widthProperty());
                layerClip.setHeight(520);
                layer.setClip(layerClip); // Zoom-Animation darf nicht ueber den unteren Rand hinausragen (das war der Balken)
                StackPane.setAlignment(layer, Pos.TOP_CENTER);
                stack.getChildren().add(layer);
            }
            Region tintGlow = new Region();
            tintGlow.setMouseTransparent(true);
            tintGlow.setMaxHeight(620);
            tintGlow.setOpacity(0);
            StackPane.setAlignment(tintGlow, Pos.TOP_LEFT);
            stack.getChildren().add(tintGlow);
            Button[] playRef = {null};
            VBox content = new VBox(22);
            content.setPadding(new Insets(60, 48, 0, 48));
            HBox head = new HBox(34);
            String poster = jf.seriesPoster(it) != null ? jf.seriesPoster(it) : jf.poster(it);
            if (poster != null) {
                StackPane pv = picture(poster, 240, 360, 16, 0.1);
                StackPane shadow = new StackPane(pv);
                shadow.setMaxSize(240, 360);
                shadow.setEffect(new DropShadow(36, Color.rgb(0, 0, 0, 0.75)));
                head.getChildren().add(shadow);
            }
            VBox info = new VBox(12);
            info.setMaxWidth(820);
            HBox.setHgrow(info, Priority.ALWAYS);
            Label title = text(it.name, 40, true);
            title.setStyle(title.getStyle() + "-fx-font-weight:800;");
            info.getChildren().add(title);
            if (!it.seriesName.isEmpty()) info.getChildren().add(muted(it.seriesName + "  ·  " + it.title(), 16));
            if (!it.tagline.isEmpty()) {
                Label tg = muted(it.tagline, 15);
                tg.setStyle(tg.getStyle() + "-fx-font-style:italic;");
                info.getChildren().add(tg);
            }
            HBox meta = new HBox(10);
            meta.setAlignment(Pos.CENTER_LEFT);
            if (it.rating > 0) {
                Label r = text("★ " + String.format("%.1f", it.rating), 14, true);
                r.setTextFill(Color.web("#ffc83d"));
                meta.getChildren().add(r);
            }
            if (it.year > 0) meta.getChildren().add(muted(String.valueOf(it.year), 14));
            if (!it.runtime().isEmpty()) meta.getChildren().add(muted(it.runtime(), 14));
            if (!it.officialRating.isEmpty()) {
                Label o = text(it.officialRating, 12, true);
                o.getStyleClass().add("chip");
                meta.getChildren().add(o);
            }
            info.getChildren().add(meta);
            HBox badges = new HBox(8);
            for (String b : it.badges()) {
                Label bl = text(b, 12, true);
                bl.getStyleClass().add(b.equals(it.hdrBadge) || b.equals(it.resBadge) ? "chip-accent" : "chip");
                badges.getChildren().add(bl);
            }
            if (!badges.getChildren().isEmpty()) info.getChildren().add(badges);
            if (!it.genres.isEmpty()) {
                HBox gchips = new HBox(8);
                for (String g : it.genres.split(" · ")) {
                    String gc = Genres.color(g.trim());
                    Label gl = text(g.trim(), 12, true);
                    gl.setStyle(gl.getStyle() + "-fx-text-fill:" + gc + ";-fx-background-color:" + css(Color.web(gc), 0.16)
                            + ";-fx-border-color:" + css(Color.web(gc), 0.55) + ";-fx-border-radius:999;-fx-background-radius:999;-fx-padding:3 11 3 11;");
                    gchips.getChildren().add(gl);
                }
                info.getChildren().add(gchips);
            }
            Label ov = text(it.overview, 15, false);
            ov.setStyle(ov.getStyle() + "-fx-text-fill:#cfd8e1;-fx-line-spacing:3;");
            info.getChildren().add(ov);
            if (!it.audioLangs.isEmpty()) info.getChildren().add(muted("Ton: " + it.audioLangs, 13));
            if (!it.subLangs.isEmpty()) info.getChildren().add(muted("Untertitel: " + it.subLangs, 13));
            HBox btns = new HBox(12);
            btns.setPadding(new Insets(8, 0, 0, 0));
            if (it.isPlayable()) {
                Button play = iconBtn("play", it.resumeTicks > 0 ? "Fortsetzen" : "Abspielen", "btn-primary");
                play.setOnAction(e -> play(it));
                playRef[0] = play;
                btns.getChildren().add(play);
                if (it.resumeTicks > 0) {
                    Button fromStart = button("Von vorn", "btn-ghost");
                    fromStart.setOnAction(e -> { it.resumeTicks = 0; play(it); });
                    btns.getChildren().add(fromStart);
                }
            }
            if ("Episode".equals(it.type) && it.seriesId != null) {
                Button toSeries = button("Zur Serie", "btn-ghost");
                toSeries.setOnAction(e -> go(() -> detail(it.seriesId)));
                btns.getChildren().add(toSeries);
            }
            if ("Series".equals(it.type)) {
                Button sp = iconBtn("play", "Abspielen", "btn-primary");
                sp.setDisable(true);
                playRef[0] = sp;
                btns.getChildren().add(0, sp);
                bg(() -> jf.nextUpFor(it.id), n -> {
                    if (n == null) { btns.getChildren().remove(sp); playRef[0] = null; return; }
                    String ep = "S" + n.seasonNo + ":E" + n.episodeNo;
                    sp.setText((n.resumeTicks > 0 ? "Fortsetzen  " : "Abspielen  ") + ep);
                    sp.setOnAction(e -> play(n));
                    sp.setDisable(false);
                });
            }
            Button favB = iconBtn(it.favorite ? "heartOn" : "heart", it.favorite ? "In Merkliste" : "Merkliste", "btn-ghost");
            favB.setOnAction(e -> toggleFavorite(it, favB));
            btns.getChildren().add(favB);
            if ("Movie".equals(it.type) || "Series".equals(it.type)) {
                Button tr = iconBtn("play", "Trailer", "btn-ghost");
                tr.setOnAction(e -> playTrailer(it));
                btns.getChildren().add(tr);
            }
            if (it.isPlayable()) btns.getChildren().add(dlButton(it));
            if ("Series".equals(it.type)) {
                Button dls = button("⬇  Ungesehene laden", "btn-ghost");
                dls.setOnAction(e -> downloadSeries(it));
                btns.getChildren().add(dls);
            }
            if (it.isPlayable()) {
                Button seenB = iconBtn(it.played ? "check" : "circle", it.played ? "Gesehen" : "Ungesehen", "btn-ghost");
                seenB.setOnAction(e -> togglePlayed(it, seenB));
                btns.getChildren().add(seenB);
            }
            if (!btns.getChildren().isEmpty()) info.getChildren().add(btns);
            head.getChildren().add(info);
            if (!REDUCE) {
                for (int ri = 0; ri < info.getChildren().size(); ri++) {
                    Node ch = info.getChildren().get(ri);
                    ch.setOpacity(0);
                    ch.setTranslateY(16);
                    PauseTransition d = new PauseTransition(Duration.millis(260 + ri * 70));
                    FadeTransition rf = new FadeTransition(Duration.millis(420), ch);
                    rf.setToValue(1);
                    TranslateTransition rt = new TranslateTransition(Duration.millis(480), ch);
                    rt.setToY(0);
                    rt.setInterpolator(Interpolator.EASE_OUT);
                    new SequentialTransition(d, new ParallelTransition(rf, rt)).play();
                }
                if (head.getChildren().size() > 1) {
                    Node ph = head.getChildren().get(0);
                    ph.setScaleX(0.92);
                    ph.setScaleY(0.92);
                    ScaleTransition sc = new ScaleTransition(Duration.millis(520), ph);
                    sc.setToX(1);
                    sc.setToY(1);
                    sc.setInterpolator(Interpolator.EASE_OUT);
                    sc.play();
                }
            }
            content.getChildren().add(head);
            posterColor(poster != null ? poster : bd, col -> {
                tintGlow.setStyle("-fx-background-color: radial-gradient(center 18% 22%, radius 75%, " + css(col, 0.34) + ", rgba(0,0,0,0));");
                if (playRef[0] != null) {
                    Color c2 = col.deriveColor(28, 1, 1, 1);
                    playRef[0].setStyle("-fx-background-color: linear-gradient(to right, " + css(col, 1) + ", " + css(c2, 1)
                            + "); -fx-effect: dropshadow(gaussian, " + css(col, 0.5) + ", 20, 0.1, 0, 4);");
                }
                FadeTransition gt = new FadeTransition(Duration.millis(REDUCE ? 100 : 1100), tintGlow);
                gt.setToValue(1);
                gt.play();
            });
            int ri = 0;
            for (Node ch : info.getChildren()) reveal(ch, ri++);
            if (!head.getChildren().isEmpty() && head.getChildren().get(0) != info) {
                Node pn = head.getChildren().get(0);
                pn.setTranslateX(-40);
                pn.setOpacity(0);
                FadeTransition pf = new FadeTransition(Duration.millis(600), pn);
                pf.setToValue(1);
                TranslateTransition pt = new TranslateTransition(Duration.millis(650), pn);
                pt.setToX(0);
                pt.setInterpolator(Interpolator.EASE_OUT);
                new ParallelTransition(pf, pt).play();
            }
            if ("Series".equals(it.type)) seriesSection(content, it);
            if ("Episode".equals(it.type) && it.seriesId != null && it.seasonId != null) {
                VBox more = new VBox();
                content.getChildren().add(more);
                bg(() -> jf.episodes(it.seriesId, it.seasonId), l -> {
                    if (!l.isEmpty()) more.getChildren().add(wide(row("Weitere Folgen dieser Staffel", l, true)));
                });
            }
            if (!it.chapters.isEmpty()) content.getChildren().add(wide(chapterRow(it)));
            if (!it.people.isEmpty()) {
                HBox cast = new HBox(18);
                cast.setPadding(new Insets(6, 0, 10, 0));
                for (JfItem.Person p : it.people) cast.getChildren().add(personCard(p));
                ScrollPane sp = new ScrollPane(cast);
                sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                sp.setFitToHeight(true);
                content.getChildren().addAll(text("Besetzung", 21, true), sp);
            }
            // Fakten zur Datei
            StringBuilder facts = new StringBuilder();
            if (!it.studios.isEmpty()) facts.append("Studio: ").append(it.studios).append("     ");
            if (!it.container.isEmpty()) facts.append("Format: ").append(it.container).append("     ");
            if (!it.sizeText.isEmpty()) facts.append("Größe: ").append(it.sizeText).append("     ");
            if (it.bitrateKbps > 0) facts.append("Bitrate: ").append(String.format("%.1f", it.bitrateKbps / 1000.0)).append(" Mbit/s");
            if (facts.length() > 0) content.getChildren().add(muted(facts.toString().trim(), 13));
            if (!"Series".equals(it.type) || true) {
                VBox sim = new VBox();
                content.getChildren().add(sim);
                String simId = "Episode".equals(it.type) && it.seriesId != null ? it.seriesId : it.id;
                bg(() -> jf.similar(simId), l -> {
                    if (!l.isEmpty()) sim.getChildren().add(wide(row("Ähnliche Titel", l, false)));
                });
            }
            stack.getChildren().add(content);
            page.getChildren().add(stack);
        });
    }

    /** Reihe so verbreitern, dass sie die Seitenraender des Inhaltsbereichs ausgleicht. */
    private static Node wide(Node n) {
        VBox.setMargin(n, new Insets(0, -48, 0, -48));
        return n;
    }

    private Node chapterRow(JfItem it) {
        HBox cards = new HBox(16);
        cards.setPadding(new Insets(12, 48, 16, 48));
        for (JfItem.Chapter c : it.chapters) {
            StackPane pic = picture(jf.chapterImage(it, c), 240, 135, 12, 0.2);
            if (c.tag == null) pic.getChildren().add(text("▶", 22, true));
            long sec = c.start / 10000000L;
            Label n = text(c.name.isEmpty() ? "Kapitel " + (c.index + 1) : c.name, 13, true);
            n.setMaxWidth(240);
            n.setWrapText(false);
            n.setTextOverrun(OverrunStyle.ELLIPSIS);
            Label t = muted(sec >= 3600 ? String.format("%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
                    : String.format("%d:%02d", sec / 60, sec % 60), 12);
            VBox v = new VBox(6, pic, n, t);
            v.setMaxWidth(240);
            StackPane h = hoverScale(v, 1.04);
            h.setCursor(Cursor.HAND);
            h.setOnMouseClicked(e -> { it.resumeTicks = c.start; play(it); });
            cards.getChildren().add(h);
        }
        ScrollPane sp = new ScrollPane(cards);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setFitToHeight(true);
        Label head = text("Kapitel", 21, true);
        VBox.setMargin(head, new Insets(0, 0, 0, 48));
        return new VBox(head, sp);
    }

    private Node personCard(JfItem.Person p) {
        StackPane img = picture(jf.personImage(p), 104, 104, 52, 0.15);
        if (p.tag == null) img.getChildren().add(text(p.name.isEmpty() ? "?" : p.name.substring(0, 1), 30, true));
        Label n = text(p.name, 13, true);
        n.setMaxWidth(116);
        n.setTextAlignment(TextAlignment.CENTER);
        n.setAlignment(Pos.CENTER);
        Label r = muted(p.role, 11);
        r.setMaxWidth(116);
        r.setTextAlignment(TextAlignment.CENTER);
        r.setAlignment(Pos.CENTER);
        r.setWrapText(false);
        r.setTextOverrun(OverrunStyle.ELLIPSIS);
        VBox v = new VBox(6, img, n, r);
        v.setPrefWidth(116);
        v.setAlignment(Pos.TOP_CENTER);
        StackPane pholder = hoverScale(v, 1.06);
        pholder.setCursor(Cursor.HAND);
        pholder.setOnMouseClicked(e -> {
            JfItem pseudo = new JfItem();
            pseudo.id = p.id;
            pseudo.name = p.name;
            pseudo.type = "Person";
            go(() -> personItems(pseudo));
        });
        return pholder;
    }

    private void personItems(JfItem p) {
        chrome("");
        FlowPane flow = new FlowPane(20, 26);
        flow.setPadding(new Insets(18, 48, 40, 0));
        VBox page = new VBox(text(p.name, 30, true), flow);
        page.setPadding(new Insets(28, 0, 0, 48));
        setContent(scroll(page));
        bg(() -> jf.byPerson(p.id, 0, 100), l -> { for (JfItem i : l) flow.getChildren().add(card(i, false)); });
    }

    private void seriesSection(VBox page, JfItem series) {
        FlowPane seasons = new FlowPane(8, 8);
        VBox eps = new VBox(12);
        ToggleGroup group = new ToggleGroup();
        page.getChildren().addAll(text("Staffeln", 21, true), seasons, eps);
        int[] gen = {0};
        group.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) { if (a != null) a.setSelected(true); return; } // eine Staffel bleibt immer gewaehlt
            JfItem s = (JfItem) b.getUserData();
            int my = ++gen[0];
            eps.getChildren().setAll(loader());
            bg(() -> jf.episodes(series.id, s.id), el -> {
                if (my != gen[0]) return;
                eps.getChildren().clear();
                int k = 0;
                for (JfItem ep : el) { Node r = episodeRow(ep); reveal(r, k++); eps.getChildren().add(r); }
            });
        });
        bg(() -> jf.seasons(series.id), l -> {
            for (JfItem s : l) {
                ToggleButton tb = new ToggleButton(s.name);
                tb.getStyleClass().add("toggle-pill");
                tb.setToggleGroup(group);
                tb.setUserData(s);
                seasons.getChildren().add(tb);
            }
            if (!group.getToggles().isEmpty()) {
                // erste Staffel mit ungesehenen Folgen, sonst die erste
                Toggle pick = group.getToggles().get(0);
                for (Toggle t : group.getToggles()) {
                    if (((JfItem) t.getUserData()).unplayed > 0) { pick = t; break; }
                }
                group.selectToggle(pick);
            }
        });
    }

    private Node episodeRow(JfItem ep) {
        StackPane pic = picture(jf.landscape(ep), 240, 135, 12, 0.2);
        if (ep.progress() > 0) {
            Region fill = new Region();
            fill.setMaxHeight(4);
            fill.setMaxWidth(240 * ep.progress());
            fill.setStyle("-fx-background-color: linear-gradient(to right, " + A1 + ", " + A2 + ");");
            StackPane.setAlignment(fill, Pos.BOTTOM_LEFT);
            pic.getChildren().add(fill);
        }
        Label nr = text(ep.title() + (ep.played ? "   ✓" : ""), 16, true);
        Label rt = muted(ep.runtime(), 13);
        Label ov = muted(ep.overview, 13);
        ov.setMaxHeight(54);
        ov.setStyle(ov.getStyle() + "-fx-text-fill:#aab6c3;");
        VBox txt = new VBox(6, nr, rt, ov);
        txt.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(txt, Priority.ALWAYS);
        Button pl = button("▶", "icon-btn");
        pl.setOnAction(e -> play(ep));
        HBox r = new HBox(18, pic, txt, pl);
        r.setAlignment(Pos.CENTER_LEFT);
        r.setPadding(new Insets(12));
        r.setMaxWidth(1000);
        String base = "-fx-background-radius:18;-fx-background-color:";
        r.setStyle(base + "rgba(255,255,255,0.045);");
        r.setCursor(Cursor.HAND);
        TranslateTransition slideIn = new TranslateTransition(Duration.millis(180), pic);
        slideIn.setToX(6);
        TranslateTransition slideOut = new TranslateTransition(Duration.millis(180), pic);
        slideOut.setToX(0);
        r.setOnMouseEntered(e -> { r.setStyle(base + "rgba(255,255,255,0.10);"); slideOut.stop(); slideIn.playFromStart(); });
        r.setOnMouseExited(e -> { r.setStyle(base + "rgba(255,255,255,0.045);"); slideIn.stop(); slideOut.playFromStart(); });
        r.setOnMouseClicked(e -> { if (e.getTarget() != pl) go(() -> detail(ep.id)); });
        return r;
    }

    // ------------------------------------------------------------ Wiedergabe

    void play(JfItem it) {
        if (player != null) return;
        if (sync != null && sync.inGroup() && !syncStarting) {
            bg(() -> { sync.setQueue(it); return true; }, r -> { });
            return;
        }
        HeroVideo.stop();
        hidePreviewNow();
        if (heroTimer != null) heroTimer.stop();
        root.setTop(null);
        if (current != null) history.push(current);
        try {
            player = new Player(this, it);
        } catch (Throwable t) {
            Log.e("Player-Fenster", t);
            player = null;
            if (!history.isEmpty()) current = history.pop();
            chrome("");
            Alert a = new Alert(Alert.AlertType.ERROR,
                    "Der Player konnte nicht gestartet werden.\n\n" + t);
            a.initOwner(stage);
            a.show();
            return;
        }
        root.setCenter(player);
        player.requestFocus();
    }
}
