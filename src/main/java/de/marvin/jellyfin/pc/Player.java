package de.marvin.jellyfin.pc;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.javafx.videosurface.ImageViewVideoSurface;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.TrackDescription;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Eingebauter Player auf Basis von libVLC (spielt praktisch alle Formate: MKV, HEVC, DTS, TrueHD ...).
 * Wichtig: libVLC-Aufrufe laufen nie auf dem Oberflaechen-Thread und nie innerhalb von Ereignis-Rueckrufen
 * (sonst haengt das Programm). Der Zustand wird aus den Ereignissen zwischengespeichert.
 */
final class Player extends StackPane {
    private final Jf jf = Jf.get();
    private final App app;
    private final JfItem item;
    private final ExecutorService vlc = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vlc-commands");
        t.setDaemon(true);
        return t;
    });
    private volatile EmbeddedMediaPlayer mp;

    // libVLC wird nur einmal geladen und bleibt im Speicher: so startet jede Wiedergabe sofort
    private static MediaPlayerFactory shared;
    static synchronized MediaPlayerFactory sharedFactory(Consumer<String> status) {
        if (shared == null) {
            String[] base = {"--no-video-title-show", "--no-osd"};
            java.util.prefs.Preferences sp = java.util.prefs.Preferences.userRoot().node("de/marvin/jellyfin/pc/ui");
            String[] withSubs = {base[0], base[1],
                    "--freetype-rel-fontsize=" + sp.getInt("subSize", 16),
                    "--freetype-color=" + sp.getInt("subColor", 16777215),
                    "--sub-margin=" + sp.getInt("subMargin", 0)};
            try {
                shared = VlcSetup.factory(status, withSubs);
            } catch (Throwable t) {
                Log.e("Untertitel-Optionen nicht unterstuetzt, starte ohne", t);
                shared = VlcSetup.factory(status, base);
            }
        }
        return shared;
    }

    private final StackPane flashIcon = new StackPane();

    /** Grosses Pause-/Play-Symbol blitzt kurz in der Mitte auf. */
    private void flash(boolean play) {
        if (App.REDUCE || !started) return;
        flashIcon.getChildren().setAll(Fx.icon(play ? "play" : "pause", 44, "#ffffff"));
        flashIcon.setScaleX(0.7);
        flashIcon.setScaleY(0.7);
        flashIcon.setOpacity(1);
        ScaleTransition sc = new ScaleTransition(javafx.util.Duration.millis(420), flashIcon);
        sc.setToX(1.35);
        sc.setToY(1.35);
        FadeTransition fd = new FadeTransition(javafx.util.Duration.millis(420), flashIcon);
        fd.setFromValue(1);
        fd.setToValue(0);
        new ParallelTransition(sc, fd).play();
    }

    private String src() {
        java.io.File f = Downloads.file(item.id);
        return f != null ? f.getAbsolutePath() : jf.directUrl(item);
    }

    /** Untertitel-Stil geaendert: neuer libVLC-Kern fuer die naechste Wiedergabe (laeuft schon im Hintergrund an). */
    static void restyle() {
        synchronized (Player.class) { shared = null; }
        warmUp();
    }

    /** Beim Programmstart im Hintergrund aufrufen. */
    static void warmUp() {
        Thread t = new Thread(() -> {
            try { sharedFactory(s -> { }); Log.i("libVLC vorgeladen"); }
            catch (Throwable e) { Log.e("libVLC vorladen", e); }
        }, "vlc-warmup");
        t.setDaemon(true);
        t.start();
    }

    private final Slider seek = new Slider(0, 1, 0);
    private final Label time = new Label("0:00 / 0:00");
    private final Button playPause = new Button("⏸");
    private final Button skip = new Button();
    private final BorderPane overlay = new BorderPane();
    private final PauseTransition hide = new PauseTransition(Duration.seconds(3));
    private final StackPane loading = new StackPane();
    private final Label loadingText = new Label("Player wird gestartet …");
    private final MenuButton audio = new MenuButton("🔊  Ton"), subs = new MenuButton("💬  Untertitel");

    // Zwischengespeicherter Zustand (aus Ereignissen), damit die Oberflaeche nie libVLC fragen muss
    private volatile long curTime, curLen;
    private volatile boolean isPlaying, started;
    private volatile List<TrackDescription> audioTracks = new ArrayList<>(), subTracks = new ArrayList<>();
    private volatile int audioCur = -1, subCur = -1;
    private boolean seeking, closed, resumed, muted;
    private long lastReport;
    private long introEndMs, outroStartMs, outroEndMs;
    private double volume = java.util.prefs.Preferences.userRoot().node("de/marvin/jellyfin/pc/ui").getInt("volume", 80);
    private volatile JfItem nextEp;
    private boolean noAuto, nextShown, fetchedNext;
    private int rateIdx = 1;
    private static final double[] RATES = {0.75, 1.0, 1.25, 1.5, 2.0};
    private final java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(Player.class);
    private final Tooltip seekTip = new Tooltip();
    private final ImageView seekThumb = new ImageView();
    private volatile JfItem tpItem;
    private final java.util.Map<Integer, javafx.scene.image.Image> sheets = new java.util.HashMap<>();
    private final HBox nextBox = new HBox(12);
    private final Label nextLabel = new Label();
    private MenuButton speedBtn, chapBtn, sleepBtn;
    private PauseTransition sleepTimer;
    private volatile java.util.List<JfItem.Chapter> chapters = new ArrayList<>();
    private VBox controlsBox;

    Player(App app, JfItem item) {
        this.app = app;
        this.item = item;
        setStyle("-fx-background-color: black;");
        setFocusTraversable(true);
        ImageView video = new ImageView();
        video.setPreserveRatio(true);
        video.fitWidthProperty().bind(widthProperty());
        video.fitHeightProperty().bind(heightProperty());
        getChildren().add(video);

        ProgressIndicator pi = new ProgressIndicator();
        pi.setPrefSize(54, 54);
        pi.setMaxSize(54, 54);
        pi.setStyle("-fx-progress-color: " + App.A1 + ";");
        loadingText.setStyle("-fx-font-size:15px;");
        VBox lv = new VBox(16, pi, loadingText);
        lv.setAlignment(Pos.CENTER);
        loading.getChildren().add(lv);
        loading.setMouseTransparent(true);
        getChildren().add(loading);

        Button back = icon("‹");
        back.setStyle("-fx-font-size:22px;");
        back.setOnAction(e -> close());
        Label title = new Label(item.seriesName.isEmpty() ? item.title() : item.seriesName);
        title.setStyle("-fx-font-size:19px;-fx-font-weight:700;");
        Label sub = new Label(item.seriesName.isEmpty() ? "" : item.title());
        sub.getStyleClass().add("muted");
        VBox titles = new VBox(1, title, sub);
        HBox top = new HBox(14, back, titles);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(16, 24, 40, 20));
        top.getStyleClass().add("player-top");

        playPause.getStyleClass().add("icon-btn");
        playPause.setStyle("-fx-min-width:46;-fx-min-height:46;-fx-font-size:18px;");
        playPause.setOnAction(e -> userPause());
        Button back10 = icon("⏪"), fwd30 = icon("⏩");
        back10.setOnAction(e -> skipBy(-10000));
        fwd30.setOnAction(e -> skipBy(30000));
        Tooltip.install(back10, new Tooltip("10 s zurück"));
        Tooltip.install(fwd30, new Tooltip("30 s vor"));
        MenuButton style = new MenuButton("Aa");
        java.util.prefs.Preferences up = java.util.prefs.Preferences.userRoot().node("de/marvin/jellyfin/pc/ui");
        Menu sizeM = new Menu("Größe"), colorM = new Menu("Farbe"), posM = new Menu("Position");
        Object[][] sizes = {{"Klein", 18}, {"Normal", 16}, {"Groß", 12}, {"Sehr groß", 8}};
        Object[][] colors = {{"Weiß", 16777215}, {"Gelb", 16776960}, {"Hellblau", 8900346}, {"Grün", 8978312}};
        Object[][] poss = {{"Unten", 0}, {"Etwas höher", 40}, {"Deutlich höher", 90}};
        fillStyle(sizeM, sizes, "subSize", 16, up);
        fillStyle(colorM, colors, "subColor", 16777215, up);
        fillStyle(posM, poss, "subMargin", 0, up);
        style.getItems().addAll(sizeM, colorM, posM);
        styleBtn = style;
        MenuButton chap = new MenuButton("📑");
        chapBtn = chap;
        chap.setOnShowing(e -> {
            chap.getItems().clear();
            for (JfItem.Chapter c : chapters) {
                long sec = c.start / 10000000L;
                MenuItem mi = new MenuItem(fmt(sec * 1000) + "   " + (c.name == null || c.name.isEmpty() ? "Kapitel " + (c.index + 1) : c.name));
                mi.setOnAction(x -> userSeekMs(sec * 1000L));
                chap.getItems().add(mi);
            }
            if (chap.getItems().isEmpty()) {
                MenuItem none = new MenuItem("Keine Kapitel");
                none.setDisable(true);
                chap.getItems().add(none);
            }
        });
        MenuButton sleep = new MenuButton("😴");
        sleepBtn = sleep;
        int[] mins = {0, 15, 30, 45, 60, 90};
        for (int mn : mins) {
            MenuItem mi = new MenuItem(mn == 0 ? "Aus" : "Stopp nach " + mn + " Min");
            mi.setOnAction(x -> setSleep(mn));
            sleep.getItems().add(mi);
        }
        MenuItem endEp = new MenuItem("Nach dieser Folge stoppen");
        endEp.setOnAction(x -> { noAuto = true; nextBox.setVisible(false); app.toast("Stoppt nach dieser Folge"); });
        sleep.getItems().add(endEp);
        MenuButton speed = new MenuButton("⚡");
        speedBtn = speed;
        audio.setOnShowing(e -> { refreshTracks(); fillTracks(audio, audioTracks, audioCur, true); });
        subs.setOnShowing(e -> { refreshTracks(); fillTracks(subs, subTracks, subCur, false); });
        for (int ri = 0; ri < RATES.length; ri++) {
            double r = RATES[ri];
            int idx = ri;
            MenuItem mi = new MenuItem(r == 1.0 ? "Normal" : r + "x");
            mi.setOnAction(e -> { rateIdx = idx; cmd(m -> m.controls().setRate((float) r)); });
            speed.getItems().add(mi);
        }
        Button pin = icon("📌");
        Tooltip.install(pin, new Tooltip("Immer im Vordergrund (T)"));
        pin.setOnAction(e -> toggleOnTop());
        Button full = icon("⛶");
        full.setOnAction(e -> toggleFullscreen());
        Slider vol = new Slider(0, 100, volume);
        vol.setPrefWidth(90);
        vol.valueProperty().addListener((o, a, b) -> { volume = b.doubleValue(); java.util.prefs.Preferences.userRoot().node("de/marvin/jellyfin/pc/ui").putInt("volume", b.intValue()); cmd(m -> m.audio().setVolume(b.intValue())); });
        time.setStyle("-fx-font-size:13px;-fx-font-weight:600;");
        time.setMinWidth(130); // feste Breite: nichts verschiebt sich, wenn die Zeit weiterlaeuft
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Tooltip.install(chap, new Tooltip("Kapitel"));
        Tooltip.install(sleep, new Tooltip("Einschlaf-Timer"));
        Tooltip.install(style, new Tooltip("Untertitel-Stil"));
        Tooltip.install(speed, new Tooltip("Tempo"));
        HBox row = new HBox(10, playPause, back10, fwd30, time, sp, vol, audio, subs, chap, sleep, style, speed, pin, full);
        row.setAlignment(Pos.CENTER_LEFT);

        seek.setOnMousePressed(e -> seeking = true);
        // Ziehen selbst behandeln: der eingebaute Schieberegler wirft sonst einen Fehler, wenn man neben dem Knopf anfasst
        seek.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> {
            double f = Math.max(0, Math.min(1, (e.getX() - 8) / Math.max(1, seek.getWidth() - 16)));
            seeking = true;
            seek.setValue(f);
            e.consume();
        });
        seek.setOnMouseReleased(e -> {
            float pos = (float) seek.getValue();
            seeking = false;
            userSeekFrac(pos);
        });
        seek.setOnMouseMoved(e -> {
            long len = curLen;
            if (len <= 0) return;
            double f = Math.max(0, Math.min(1, (e.getX() - 8) / Math.max(1, seek.getWidth() - 16)));
            long ms = (long) (f * len);
            seekTip.setText(fmt(ms));
            boolean thumb = updateThumb(ms);
            double h = thumb ? tpItem.tpHeight + 44 : 50;
            double w = thumb ? tpItem.tpWidth / 2.0 : 22;
            if (seekTip.isShowing()) { seekTip.setAnchorX(e.getScreenX() - w); seekTip.setAnchorY(e.getScreenY() - h); }
            else seekTip.show(seek, e.getScreenX() - w, e.getScreenY() - h);
        });
        seek.setOnMouseExited(e -> seekTip.hide());
        seek.valueProperty().addListener((o, a, b) -> paintSeek());
        seek.skinProperty().addListener((o, a, b) -> Platform.runLater(this::paintSeek));
        VBox controls = new VBox(10, seek, row);
        controls.setPadding(new Insets(40, 26, 18, 26));
        controlsBox = controls;
        controls.getStyleClass().add("player-panel");

        skip.getStyleClass().add("skip-btn");
        skip.setVisible(false);
        skip.setOnAction(e -> doSkip());
        StackPane skipBox = new StackPane(skip);
        skipBox.setAlignment(Pos.BOTTOM_RIGHT);
        skipBox.setPadding(new Insets(0, 36, 130, 0));
        skipBox.setPickOnBounds(false);

        overlay.setTop(top);
        overlay.setBottom(controls);
        overlay.setPickOnBounds(false);
        top.setPickOnBounds(true);
        controls.setPickOnBounds(true);
        nextLabel.setStyle("-fx-font-size:15px;-fx-font-weight:700;");
        Button nowB = new Button("Jetzt ▶"), cancelB = new Button("Abbrechen");
        nowB.getStyleClass().add("btn-primary");
        cancelB.getStyleClass().add("btn-ghost");
        nowB.setOnAction(e -> onFinished());
        cancelB.setOnAction(e -> { noAuto = true; nextBox.setVisible(false); });
        nextBox.getChildren().addAll(nextLabel, nowB, cancelB);
        nextBox.setAlignment(Pos.CENTER_LEFT);
        nextBox.setPadding(new Insets(14, 18, 14, 22));
        nextBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        nextBox.setStyle("-fx-background-color: rgba(15,19,28,0.92); -fx-background-radius: 16;");
        nextBox.setVisible(false);
        StackPane nextWrap = new StackPane(nextBox);
        nextWrap.setAlignment(Pos.BOTTOM_RIGHT);
        nextWrap.setPadding(new Insets(0, 36, 36, 0));
        nextWrap.setPickOnBounds(false);
        flashIcon.setMouseTransparent(true);
        flashIcon.setOpacity(0);
        flashIcon.setStyle("-fx-background-color: rgba(0,0,0,0.55); -fx-background-radius: 999; -fx-min-width: 96; -fx-min-height: 96; -fx-max-width: 96; -fx-max-height: 96; -fx-alignment: center;");
        StackPane flashWrap = new StackPane(flashIcon);
        flashWrap.setMouseTransparent(true);
        getChildren().addAll(skipBox, nextWrap, flashWrap, overlay);

        hide.setOnFinished(e -> {
            boolean busy = audio.isShowing() || subs.isShowing() || styleBtnShowing()
                    || (chapBtn != null && chapBtn.isShowing()) || (sleepBtn != null && sleepBtn.isShowing()) || (speedBtn != null && speedBtn.isShowing())
                    || (controlsBox != null && controlsBox.isHover()) || seeking;
            if (busy) { hide.playFromStart(); return; } // nie ausblenden, solange du gerade etwas bedienst
            if (isPlaying) {
                fadeOverlay(false);
                setCursor(Cursor.NONE);
            }
        });
        setOnMouseMoved(e -> {
            fadeOverlay(true);
            setCursor(Cursor.DEFAULT);
            hide.playFromStart();
        });
        setOnMouseClicked(e -> {
            if (e.getTarget() instanceof ImageView || e.getTarget() == this) {
                if (e.getClickCount() == 2) toggleFullscreen();
                else if (e.getClickCount() == 1) userPause();
            }
        });
        setOnKeyPressed(e -> {
            KeyCode k = e.getCode();
            if ((k == KeyCode.SPACE || k == KeyCode.ENTER)) userPause();
            else if (k == KeyCode.LEFT) skipBy(-10000);
            else if (k == KeyCode.RIGHT) skipBy(30000);
            else if (k == KeyCode.UP) vol.setValue(Math.min(100, vol.getValue() + 5));
            else if (k == KeyCode.DOWN) vol.setValue(Math.max(0, vol.getValue() - 5));
            else if (k == KeyCode.M) { muted = !muted; boolean mu = muted; cmd(m -> m.audio().setMute(mu)); }
            else if (k == KeyCode.S) { if (skip.isVisible()) doSkip(); }
            else if (k == KeyCode.T) toggleOnTop();
            else if (k == KeyCode.F || k == KeyCode.F11) toggleFullscreen();
            else if (k == KeyCode.N || k == KeyCode.TRACK_NEXT) { if (nextEp != null) onFinished(); }
            else if (k == KeyCode.PLAY || k == KeyCode.PAUSE) userPause();
            else if (k == KeyCode.F1 || "?".equals(e.getText())) app.showShortcuts();
            else if (k == KeyCode.PLUS || k == KeyCode.ADD || "+".equals(e.getText())) changeRate(1);
            else if (k == KeyCode.MINUS || k == KeyCode.SUBTRACT || "-".equals(e.getText())) changeRate(-1);
            else if (e.getText().length() == 1 && Character.isDigit(e.getText().charAt(0))) {
                float pos = (e.getText().charAt(0) - '0') / 10f;
                userSeekFrac(pos);
            }
            else if (k == KeyCode.ESCAPE) {
                Stage st = app.stage();
                if (st.isFullScreen()) st.setFullScreen(false);
                else close();
            }
            fadeOverlay(true);
            hide.playFromStart();
            e.consume();
        });
        hide.playFromStart();
        Platform.runLater(this::requestFocus);

        // Intro/Abspann holen (Kapitel brauchen die Detaildaten)
        daemon(() -> {
            try {
                JfItem full2 = jf.item(item.id);
                chapters = full2.chapters;
                if (full2.tpWidth > 0 && full2.tpTileW > 0 && full2.tpTileH > 0) tpItem = full2;
                long in = jf.introEnd(full2);
                long[] out = jf.outro(full2);
                Platform.runLater(() -> {
                    introEndMs = in / 10000;
                    if (out != null) {
                        outroStartMs = out[0] / 10000;
                        outroEndMs = out[1] / 10000;
                    }
                });
            } catch (Exception ex) {
                Log.e("Segmente laden", ex);
            }
        }, "segments");
        daemon(() -> jf.reportStart(item, item.resumeTicks), "report");

        // Player im Hintergrund starten: darf die Oberflaeche nie blockieren
        Log.i("Starte Wiedergabe: " + item.name + "  " + src().replaceAll("api_key=[^&]*", "api_key=***"));
        Thread init = new Thread(() -> {
            try {
                Consumer<String> status = s -> Platform.runLater(() -> loadingText.setText(s));
                MediaPlayerFactory f = sharedFactory(status);
                EmbeddedMediaPlayer p = f.mediaPlayers().newEmbeddedMediaPlayer();
                p.videoSurface().set(new ImageViewVideoSurface(video));
                p.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                    // Nur Zustand merken und an die Oberflaeche melden, keine libVLC-Aufrufe hier!
                    @Override public void lengthChanged(MediaPlayer m, long length) { curLen = length; }
                    @Override public void timeChanged(MediaPlayer m, long t) {
                        curTime = t;
                        Platform.runLater(() -> tick(t, curLen));
                    }
                    @Override public void playing(MediaPlayer m) {
                        isPlaying = true;
                        Platform.runLater(() -> { playPause.setText("⏸"); if (started) flash(true); onStarted(); });
                    }
                    @Override public void paused(MediaPlayer m) { isPlaying = false; Platform.runLater(() -> { playPause.setText("▶"); flash(false); }); }
                    @Override public void finished(MediaPlayer m) { Platform.runLater(Player.this::onFinished); }
                    @Override public void error(MediaPlayer m) {
                        Log.i("VLC meldet Fehler beim Abspielen");
                        Platform.runLater(() -> fail("Die Wiedergabe ist fehlgeschlagen. Bitte prüfe Server und Datei.\nDetails: " + Log.file().getParent()));
                    }
                });
                mp = p;
                if (closed) { release(); return; }
                Log.i("Player bereit, starte Stream");
                if (item.resumeTicks > 0) {
                    resumed = true; // Startposition direkt beim Oeffnen setzen, kein Neustart bei 0:00
                    p.media().play(src(), ":start-time=" + (item.resumeTicks / 10000000L));
                } else {
                    p.media().play(src());
                }
            } catch (Throwable t) {
                Log.e("Player starten", t);
                Platform.runLater(() -> fail(t.getMessage()));
            }
        }, "vlc-init");
        init.setDaemon(true);
        init.start();
    }

    private static void daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    /** libVLC-Befehl ausserhalb des Oberflaechen-Threads ausfuehren (nach Start des Players). */
    private void cmd(Consumer<EmbeddedMediaPlayer> c) {
        vlc.submit(() -> {
            EmbeddedMediaPlayer p = mp;
            if (p == null || closed) return;
            try { c.accept(p); } catch (Throwable t) { Log.e("Player-Befehl", t); }
        });
    }

    private void fillStyle(Menu m, Object[][] opts, String key, int def, java.util.prefs.Preferences up) {
        ToggleGroup g = new ToggleGroup();
        for (Object[] o : opts) {
            RadioMenuItem r = new RadioMenuItem((String) o[0]);
            r.setToggleGroup(g);
            r.setSelected(up.getInt(key, def) == (Integer) o[1]);
            r.setOnAction(e -> {
                up.putInt(key, (Integer) o[1]);
                Player.restyle();
                app.toast("Untertitel-Stil gespeichert – gilt ab dem nächsten Video");
            });
            m.getItems().add(r);
        }
    }

    private boolean styleBtnShowing() { return styleBtn != null && styleBtn.isShowing(); }
    private MenuButton styleBtn;

    private void setSleep(int minutes) {
        if (sleepTimer != null) sleepTimer.stop();
        if (minutes <= 0) { app.toast("Timer aus"); return; }
        sleepTimer = new PauseTransition(Duration.minutes(minutes));
        sleepTimer.setOnFinished(e -> { app.toast("Einschlaf-Timer: Wiedergabe beendet"); close(); });
        sleepTimer.play();
        app.toast("Wiedergabe stoppt in " + minutes + " Minuten");
    }

    private void changeRate(int d) {
        rateIdx = Math.max(0, Math.min(RATES.length - 1, rateIdx + d));
        float r = (float) RATES[rateIdx];
        cmd(m -> m.controls().setRate(r));
        app.toast("Tempo " + (r == 1f ? "normal" : r + "x"));
    }

    private String langKey() {
        return "lang." + (item.seriesId != null ? item.seriesId : item.id);
    }

    /** Gemerkte Ton- und Untertitelsprache dieser Serie bzw. dieses Films anwenden. */
    private void applySaved() {
        cmd(m -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
            audioTracks = new ArrayList<>(m.audio().trackDescriptions());
            subTracks = new ArrayList<>(m.subpictures().trackDescriptions());
            String a = prefs.get(langKey() + ".a", null), s = prefs.get(langKey() + ".s", null);
            if (a != null) for (TrackDescription t : audioTracks) if (a.equals(t.description())) { m.audio().setTrack(t.id()); audioCur = t.id(); }
            if (s != null) {
                if (s.equals("off")) { m.subpictures().setTrack(-1); subCur = -1; }
                else for (TrackDescription t : subTracks) if (s.equals(t.description())) { m.subpictures().setTrack(t.id()); subCur = t.id(); }
            }
        });
    }

    /** Vorschaubild fuer die Zeit ms in die Tooltip-Anzeige setzen; false, wenn keins verfuegbar ist. */
    private boolean updateThumb(long ms) {
        JfItem t = tpItem;
        if (t == null || t.tpInterval <= 0) { seekTip.setGraphic(null); return false; }
        int per = t.tpTileW * t.tpTileH;
        int idx = (int) Math.min(Math.max(0, t.tpCount - 1), ms / t.tpInterval);
        int sheet = idx / per, inSheet = idx % per;
        javafx.scene.image.Image img = sheets.get(sheet);
        if (img == null) {
            img = new javafx.scene.image.Image(jf.trickplayUrl(t, sheet), true);
            sheets.put(sheet, img);
        }
        if (img.getProgress() < 1 || img.isError()) { seekTip.setGraphic(null); return false; }
        seekThumb.setImage(img);
        seekThumb.setViewport(new javafx.geometry.Rectangle2D((inSheet % t.tpTileW) * t.tpWidth,
                (inSheet / t.tpTileW) * t.tpHeight, t.tpWidth, t.tpHeight));
        seekTip.setGraphic(seekThumb);
        seekTip.setContentDisplay(ContentDisplay.TOP);
        return true;
    }

    private void skipBy(long ms) {
        SyncPlay sp = app.sync();
        if (sp != null && sp.inGroup()) userSeekMs(curTime + ms);
        else cmd(m -> m.controls().skipTime(ms));
    }

    // ---- Gemeinsam schauen (SyncPlay): Eingaben gehen an die Gruppe, ausgefuehrt wird, was der Server befiehlt

    private boolean grouped() {
        SyncPlay sp = app.sync();
        return sp != null && sp.inGroup();
    }

    private void userPause() {
        if (grouped()) { if (isPlaying) app.sync().pause(); else app.sync().unpause(); }
        else cmd(m -> m.controls().pause());
    }

    private void userSeekMs(long ms) {
        ms = Math.max(0, curLen > 0 ? Math.min(ms, curLen) : ms);
        if (grouped()) app.sync().seek(ms);
        else { long t = ms; cmd(m -> m.controls().setTime(t)); }
    }

    private void userSeekFrac(float f) {
        if (grouped()) userSeekMs((long) (f * curLen));
        else cmd(m -> m.controls().setPosition(f));
    }

    /** Befehl der Gruppe (Pause, Unpause, Seek, Stop). */
    void syncCommand(String c, long posMs, long delayMs) {
        switch (c) {
            case "Pause" -> cmd(m -> { m.controls().setPause(true); m.controls().setTime(posMs); });
            case "Unpause" -> {
                cmd(m -> m.controls().setTime(posMs));
                javafx.animation.PauseTransition w = new javafx.animation.PauseTransition(javafx.util.Duration.millis(Math.max(1, delayMs)));
                w.setOnFinished(e -> cmd(m -> m.controls().setPause(false)));
                w.play();
            }
            case "Seek" -> {
                cmd(m -> { m.controls().setPause(true); m.controls().setTime(posMs); });
                javafx.animation.PauseTransition w = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
                w.setOnFinished(e -> { if (app.sync() != null) app.sync().ready(posMs, false); });
                w.play();
            }
            case "Stop" -> close();
            default -> { }
        }
    }

    private void onStarted() {
        if (started) return;
        started = true;
        loading.setVisible(false);
        if (!resumed && item.resumeTicks > 0) {
            resumed = true;
            long ms = item.resumeTicks / 10000;
            cmd(m -> m.controls().setTime(ms));
        }
        cmd(m -> m.audio().setVolume((int) volume));
        if (grouped()) {
            long startMs = item.resumeTicks / 10000;
            cmd(m -> { m.controls().setPause(true); m.controls().setTime(startMs); });
            javafx.animation.PauseTransition w = new javafx.animation.PauseTransition(javafx.util.Duration.millis(600));
            w.setOnFinished(e -> app.sync().ready(startMs, false));
            w.play();
        }
        refreshTracks();
        applySaved();
    }

    private void fail(String msg) {
        if (closed) return;
        Alert a = new Alert(Alert.AlertType.ERROR, "Der Player konnte nicht starten.\n\n" + msg
                + "\n\nProtokoll: " + Log.file().getAbsolutePath());
        a.initOwner(app.stage());
        a.show();
        close();
    }

    private Button icon(String t) {
        Button b = new Button(t);
        b.getStyleClass().add("icon-btn");
        return b;
    }

    private FadeTransition overlayFade;
    private Boolean overlayTarget;

    private void fadeOverlay(boolean show) {
        if (overlayTarget != null && overlayTarget == show) return; // schon unterwegs bzw. erreicht: nicht neu starten (sonst Flackern)
        overlayTarget = show;
        if (overlayFade != null) overlayFade.stop();
        overlay.setVisible(true);
        overlayFade = new FadeTransition(Duration.millis(200), overlay);
        overlayFade.setToValue(show ? 1 : 0);
        overlayFade.setOnFinished(e -> overlay.setVisible(show));
        overlayFade.play();
    }

    /** Gefuellten Teil der Zeitleiste einfaerben (JavaFX hat dafuer keinen eigenen Stil). */
    private void paintSeek() {
        Node track = seek.lookup(".track");
        if (track == null) return;
        int p = (int) Math.round(seek.getValue() * 100);
        track.setStyle("-fx-background-color: linear-gradient(to right, " + App.A1 + " 0%, " + App.A2 + " " + p
                + "%, rgba(255,255,255,0.22) " + p + "%);");
    }

    /** Ton- und Untertitelspuren im Hintergrund lesen und zwischenspeichern. */
    private void refreshTracks() {
        cmd(m -> {
            audioTracks = new ArrayList<>(m.audio().trackDescriptions());
            subTracks = new ArrayList<>(m.subpictures().trackDescriptions());
            audioCur = m.audio().track();
            subCur = m.subpictures().track();
        });
    }

    private void fillTracks(MenuButton mb, List<TrackDescription> tracks, int current, boolean isAudio) {
        mb.getItems().clear();
        if (!isAudio) {
            MenuItem off = new MenuItem((current == -1 ? "✓  " : "      ") + "Aus");
            off.setOnAction(e -> { subCur = -1; prefs.put(langKey() + ".s", "off"); cmd(m -> m.subpictures().setTrack(-1)); });
            mb.getItems().add(off);
        }
        for (TrackDescription t : tracks) {
            if (t.id() == -1) continue;
            MenuItem mi = new MenuItem((t.id() == current ? "✓  " : "      ") + t.description());
            mi.setOnAction(e -> {
                if (isAudio) { audioCur = t.id(); prefs.put(langKey() + ".a", t.description()); cmd(m -> m.audio().setTrack(t.id())); }
                else { subCur = t.id(); prefs.put(langKey() + ".s", t.description()); cmd(m -> m.subpictures().setTrack(t.id())); }
            });
            mb.getItems().add(mi);
        }
        if (mb.getItems().isEmpty()) {
            MenuItem none = new MenuItem("Keine Spuren");
            none.setDisable(true);
            mb.getItems().add(none);
        }
    }

    private void doSkip() {
        long t = curTime;
        if (t < introEndMs) {
            long to = introEndMs;
            userSeekMs(to);
        } else if (outroEndMs > 0 && t >= outroStartMs) {
            if (outroEndMs >= curLen - 3000) { onFinished(); return; }
            long to = outroEndMs;
            userSeekMs(to);
        }
        skip.setVisible(false);
    }

    private void tick(long t, long len) {
        if (closed) return;
        if (len > 0 && !seeking) seek.setValue((double) t / len);
        time.setText(fmt(t) + " / " + fmt(len));
        if (introEndMs > 0 && t < introEndMs - 1500 && t > 1000) {
            skip.setText("Intro überspringen  ⏭");
            skip.setVisible(true);
        } else if (outroEndMs > 0 && t >= outroStartMs && t < outroEndMs - 1500) {
            skip.setText("Abspann überspringen  ⏭");
            skip.setVisible(true);
        } else {
            skip.setVisible(false);
        }
        if ("Episode".equals(item.type) && item.seriesId != null) {
            if (!fetchedNext) {
                fetchedNext = true;
                daemon(() -> { try { nextEp = jf.nextEpisode(item); } catch (Exception ignored) { } }, "next-prefetch");
            }
            long remain = len - t;
            boolean near = len > 0 && nextEp != null && !noAuto
                    && ((outroStartMs > 0 && t >= outroStartMs && remain <= 45000) || remain <= 15000);
            if (near) {
                nextLabel.setText("Nächste Folge in " + Math.max(0, remain / 1000) + " s:  " + nextEp.title());
                if (!nextShown) { nextShown = true; nextBox.setVisible(true); }
            } else if (nextShown) {
                nextShown = false;
                nextBox.setVisible(false);
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastReport > 10000) {
            lastReport = now;
            boolean paused = !isPlaying;
            daemon(() -> jf.reportProgress(item, t * 10000, paused), "report");
        }
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }

    private void toggleOnTop() {
        Stage st = app.stage();
        boolean on = !st.isAlwaysOnTop();
        st.setAlwaysOnTop(on);
        if (on && st.isFullScreen()) st.setFullScreen(false);
        if (on && st.isMaximized()) st.setMaximized(false);
        if (on) { st.setWidth(640); st.setHeight(380); }
        else st.setMaximized(true);
        app.toast(on ? "Mini-Fenster: bleibt im Vordergrund" : "Normales Fenster");
    }

    private void toggleFullscreen() {
        Stage st = app.stage();
        st.setFullScreen(!st.isFullScreen());
    }

    private void onFinished() {
        if (closed) return;
        if ("Episode".equals(item.type) && item.seriesId != null && !(noAuto && curLen - curTime < 3000)) {
            JfItem cur = item;
            daemon(() -> {
                JfItem next = null;
                try { next = jf.nextEpisode(cur); } catch (Exception ignored) {}
                JfItem n = next;
                Platform.runLater(() -> {
                    if (closed) return;
                    stop(cur.runTicks);
                    if (n != null) {
                        app.playerEnded();
                        app.play(n);
                    } else {
                        app.back();
                    }
                });
            }, "next");
        } else {
            stop(item.runTicks);
            app.back();
        }
    }

    /** Beendet die Wiedergabe, meldet die Position und kehrt zur letzten Ansicht zurueck. */
    void close() {
        if (closed) return;
        stop(Math.max(0, curTime) * 10000);
        app.back();
    }

    private void stop(long ticks) {
        if (closed) return;
        closed = true;
        if (app.stage().isAlwaysOnTop()) { app.stage().setAlwaysOnTop(false); app.stage().setMaximized(true); }
        if (sleepTimer != null) sleepTimer.stop();
        hide.stop();
        daemon(() -> jf.reportStop(item, ticks), "report");
        release();
    }

    /** libVLC im Hintergrund anhalten und freigeben (nie auf dem Oberflaechen-Thread). */
    private void release() {
        daemon(() -> {
            EmbeddedMediaPlayer p = mp;
            try { if (p != null) p.controls().stop(); } catch (Throwable ignored) {}
            try { if (p != null) p.release(); } catch (Throwable ignored) {}
            vlc.shutdown();
        }, "vlc-release");
    }
}
