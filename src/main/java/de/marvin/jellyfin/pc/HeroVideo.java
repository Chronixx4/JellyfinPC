package de.marvin.jellyfin.pc;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.util.Duration;
import uk.co.caprica.vlcj.javafx.videosurface.ImageViewVideoSurface;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Stummes Vorschau-Video im Banner der Startseite (nutzt denselben eingebauten libVLC-Kern). */
final class HeroVideo {
    private static final ExecutorService EX = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hero-video");
        t.setDaemon(true);
        return t;
    });
    private static EmbeddedMediaPlayer mp;     // nur im Hintergrund-Thread anfassen
    private static ImageView current;
    private static long generation;

    private HeroVideo() { }

    static void start(ImageView view, String url, long startSec) {
        long my;
        synchronized (HeroVideo.class) { my = ++generation; }
        current = view;
        EX.submit(() -> {
            try {
                releaseNow();
                if (my != generation) return;
                EmbeddedMediaPlayer p = Player.sharedFactory(s -> { }).mediaPlayers().newEmbeddedMediaPlayer();
                p.videoSurface().set(new ImageViewVideoSurface(view));
                p.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                    @Override public void playing(MediaPlayer m) {
                        Platform.runLater(() -> {
                            PauseTransition w = new PauseTransition(Duration.millis(700)); // erste Bilder abwarten
                            w.setOnFinished(e -> {
                                if (my != generation) return;
                                FadeTransition ft = new FadeTransition(Duration.millis(900), view);
                                ft.setToValue(1);
                                ft.play();
                            });
                            w.play();
                        });
                    }
                });
                mp = p;
                if (my != generation) { releaseNow(); return; }
                p.media().play(url, ":start-time=" + startSec, ":no-audio", ":no-sub-autodetect-file");
            } catch (Throwable t) {
                Log.e("Banner-Video", t);
            }
        });
    }

    /** Video ausblenden und im Hintergrund freigeben. */
    static void stop() {
        ImageView v;
        synchronized (HeroVideo.class) { generation++; v = current; current = null; }
        if (v != null) {
            ImageView vv = v;
            Platform.runLater(() -> {
                FadeTransition ft = new FadeTransition(Duration.millis(300), vv);
                ft.setToValue(0);
                ft.play();
            });
        }
        EX.submit(HeroVideo::releaseNow);
    }

    private static void releaseNow() {
        EmbeddedMediaPlayer p = mp;
        mp = null;
        if (p == null) return;
        try { p.controls().stop(); } catch (Throwable ignored) { }
        try { p.release(); } catch (Throwable ignored) { }
    }
}
