package de.marvin.jellyfin.pc;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Lebendiger Hintergrund hinter den Reihen der Startseite: langsam wandernde Farbwolken in den Akzentfarben
 * und darunter verschwommene, ineinander uebergehende Titelbilder (winzig geladen und hochskaliert = guenstiger Weichzeichner).
 */
final class Ambient extends Pane {
    private final List<Animation> anims = new ArrayList<>();
    private final ImageView[] pics = {new ImageView(), new ImageView()};
    private final Region feather = new Region();
    private final String bg;
    private List<String> urls = new ArrayList<>();
    private int next;
    private int front;
    private Timeline cycle;

    Ambient(String a1, String a2, String bg, boolean animate) {
        this.bg = bg;
        setMouseTransparent(true);
        setManaged(true);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        for (ImageView v : pics) {
            v.setPreserveRatio(false);
            v.setSmooth(true);
            v.setOpacity(0);
            v.fitWidthProperty().bind(widthProperty());
            v.fitHeightProperty().bind(heightProperty());
            getChildren().add(v);
        }
        blob(a1, 0.78, 0.30, 520, 0.30, 190, 17);
        blob(a2, 0.15, 0.55, 460, 0.26, 170, 23);
        blob("#ff4fa3", 0.55, 0.92, 430, 0.16, 210, 29);
        blob(a1, 0.05, 1.05, 400, 0.18, 150, 21);

        feather.setMouseTransparent(true);
        feather.setPrefHeight(110);
        feather.setMinHeight(110);
        feather.setMaxHeight(110);
        feather.prefWidthProperty().bind(widthProperty());
        feather.setStyle("-fx-background-color: linear-gradient(to bottom, " + bg + " 0%, rgba(10,13,18,0) 100%);");
        feather.setVisible(false);
        getChildren().add(feather);

        sceneProperty().addListener((o, a, b) -> setRunning(b != null && animate));
        if (!animate) anims.clear();
    }

    /** Weicher oberer Rand, damit der Uebergang vom Banner nahtlos ist. */
    void setFeather(boolean on) { feather.setVisible(on); }

    private void blob(String col, double fx, double fy, double r, double alpha, double drift, double secs) {
        Color c = Color.web(col);
        Circle ci = new Circle(r);
        ci.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(c.getRed(), c.getGreen(), c.getBlue(), alpha)),
                new Stop(1, Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0))));
        ci.centerXProperty().bind(widthProperty().multiply(fx));
        ci.centerYProperty().bind(heightProperty().multiply(fy));
        ci.setMouseTransparent(true);
        getChildren().add(ci);
        TranslateTransition tt = new TranslateTransition(Duration.seconds(secs), ci);
        tt.setByX(drift * (fx > 0.5 ? -1 : 1));
        tt.setByY(drift * 0.6 * (fy > 0.5 ? -1 : 1));
        tt.setAutoReverse(true);
        tt.setCycleCount(Animation.INDEFINITE);
        tt.setInterpolator(Interpolator.EASE_BOTH);
        ScaleTransition st = new ScaleTransition(Duration.seconds(secs * 0.8), ci);
        st.setToX(1.25);
        st.setToY(1.25);
        st.setAutoReverse(true);
        st.setCycleCount(Animation.INDEFINITE);
        st.setInterpolator(Interpolator.EASE_BOTH);
        anims.add(new ParallelTransition(tt, st));
    }

    /** Titelbilder (URLs), die verschwommen und sehr dezent ineinander ueberblendet werden. */
    void setBackdrops(List<String> list) {
        urls = new ArrayList<>(list);
        next = 0;
        if (cycle != null) cycle.stop();
        if (urls.isEmpty()) return;
        showNext();
        if (urls.size() > 1) {
            cycle = new Timeline(new KeyFrame(Duration.seconds(11), e -> showNext()));
            cycle.setCycleCount(Animation.INDEFINITE);
            if (getScene() != null) cycle.play();
        }
    }

    private void showNext() {
        if (urls.isEmpty()) return;
        String u = urls.get(next++ % urls.size());
        ImageView in = pics[front ^ 1], out = pics[front];
        front ^= 1;
        try {
            in.setImage(new Image(u, 96, 0, true, true, true)); // winzig laden = weichgezeichnet
        } catch (Exception ex) {
            return;
        }
        in.setScaleX(1);
        in.setScaleY(1);
        FadeTransition fi = new FadeTransition(Duration.seconds(2.5), in);
        fi.setToValue(0.17);
        FadeTransition fo = new FadeTransition(Duration.seconds(2.5), out);
        fo.setToValue(0);
        ScaleTransition zoom = new ScaleTransition(Duration.seconds(16), in);
        zoom.setToX(1.18);
        zoom.setToY(1.18);
        zoom.setInterpolator(Interpolator.LINEAR);
        fi.play();
        fo.play();
        zoom.play();
    }

    private void setRunning(boolean run) {
        for (Animation a : anims) { if (run) a.play(); else a.pause(); }
        if (cycle != null) { if (run || getScene() != null) cycle.play(); else cycle.pause(); }
    }
}
