package de.marvin.jellyfin.pc;

import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.util.Duration;

/** Gemeinsame Optik-Bausteine: Logo, Startanimation, Platzhalter. */
final class Fx {
    private Fx() {}

    static LinearGradient grad(Color a, Color b) {
        return new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE, new Stop(0, a), new Stop(1, b));
    }

    /** Quallen-Logo: Kuppel mit Play-Dreieck und wehenden Fangarmen auf einer Farbkachel. */
    static Node logo(double size, String a1, String a2) {
        Color c1 = Color.web(a1), c2 = Color.web(a2);
        double u = size / 64.0;
        Rectangle tile = new Rectangle(size, size);
        tile.setArcWidth(size * 0.34);
        tile.setArcHeight(size * 0.34);
        tile.setFill(grad(c1, c2));
        SVGPath dome = new SVGPath();
        dome.setContent("M14 34 C14 20 22 12 32 12 C42 12 50 20 50 34 Z");
        dome.setFill(Color.WHITE);
        SVGPath play = new SVGPath();
        play.setContent("M28 20 L40 28 L28 36 Z");
        play.setFill(c1.deriveColor(0, 1, 0.85, 1));
        Group arms = new Group();
        double[] xs = {19, 27, 35, 43};
        for (int i = 0; i < xs.length; i++) {
            SVGPath arm = new SVGPath();
            double x = xs[i], s = i % 2 == 0 ? 1 : -1;
            arm.setContent("M" + x + " 36 C" + (x + 4 * s) + " 42 " + (x - 4 * s) + " 48 " + x + " 54");
            arm.setStroke(Color.web("#ffffffcc"));
            arm.setStrokeWidth(3.2);
            arm.setStrokeLineCap(StrokeLineCap.ROUND);
            arm.setFill(null);
            arms.getChildren().add(arm);
        }
        Group art = new Group(dome, play, arms);
        art.getTransforms().add(new javafx.scene.transform.Scale(u, u, 0, 0));
        Pane box = new Pane(tile, art);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setEffect(new DropShadow(size * 0.35, Color.web(a1, 0.55)));
        return box;
    }

    /** Startanimation: Logo poppt auf, Name erscheint Buchstabe fuer Buchstabe, Balken laeuft, dann Aufblenden der App. */
    static void splash(StackPane host, String a1, String a2, Runnable done) {
        StackPane veil = new StackPane();
        veil.setStyle("-fx-background-color: radial-gradient(center 50% 45%, radius 70%, #1b1450 0%, #0a0d12 70%);");
        Node logo = logo(112, a1, a2);
        logo.setOpacity(0);
        logo.setScaleX(0.3);
        logo.setScaleY(0.3);
        logo.setRotate(-14);
        HBox word = new HBox(1);
        word.setAlignment(Pos.CENTER);
        String name = "Jellyfin";
        Text[] ts = new Text[name.length()];
        for (int i = 0; i < ts.length; i++) {
            Text t = new Text(String.valueOf(name.charAt(i)));
            t.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 46));
            t.setFill(Color.WHITE);
            t.setOpacity(0);
            t.setTranslateY(16);
            ts[i] = t;
            word.getChildren().add(t);
        }
        Region track = new Region();
        track.setMaxSize(220, 4);
        track.setMinSize(220, 4);
        track.setStyle("-fx-background-color: rgba(255,255,255,0.12); -fx-background-radius: 2;");
        Region bar = new Region();
        bar.setMaxSize(0, 4);
        bar.setMinSize(0, 4);
        bar.setStyle("-fx-background-radius: 2; -fx-background-color: linear-gradient(to right," + a1 + "," + a2 + ");");
        StackPane prog = new StackPane(track, bar);
        StackPane.setAlignment(bar, Pos.CENTER_LEFT);
        prog.setMaxSize(220, 4);
        prog.setOpacity(0);
        VBox col = new VBox(22, logo, word, prog);
        col.setAlignment(Pos.CENTER);
        col.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        veil.getChildren().add(col);
        host.getChildren().add(veil);

        Timeline tl = new Timeline();
        tl.getKeyFrames().add(new KeyFrame(Duration.millis(480),
                new KeyValue(logo.opacityProperty(), 1, Interpolator.EASE_OUT),
                new KeyValue(logo.scaleXProperty(), 1.14, Interpolator.EASE_OUT),
                new KeyValue(logo.scaleYProperty(), 1.14, Interpolator.EASE_OUT),
                new KeyValue(logo.rotateProperty(), 4, Interpolator.EASE_OUT)));
        tl.getKeyFrames().add(new KeyFrame(Duration.millis(720),
                new KeyValue(logo.scaleXProperty(), 1, Interpolator.EASE_BOTH),
                new KeyValue(logo.scaleYProperty(), 1, Interpolator.EASE_BOTH),
                new KeyValue(logo.rotateProperty(), 0, Interpolator.EASE_BOTH)));
        for (int i = 0; i < ts.length; i++) {
            int at = 450 + i * 55;
            tl.getKeyFrames().add(new KeyFrame(Duration.millis(at), new KeyValue(ts[i].opacityProperty(), 0), new KeyValue(ts[i].translateYProperty(), 16)));
            tl.getKeyFrames().add(new KeyFrame(Duration.millis(at + 380),
                    new KeyValue(ts[i].opacityProperty(), 1, Interpolator.EASE_OUT),
                    new KeyValue(ts[i].translateYProperty(), 0, Interpolator.EASE_OUT)));
        }
        tl.getKeyFrames().add(new KeyFrame(Duration.millis(600), new KeyValue(prog.opacityProperty(), 0)));
        tl.getKeyFrames().add(new KeyFrame(Duration.millis(800), new KeyValue(prog.opacityProperty(), 1)));
        tl.getKeyFrames().add(new KeyFrame(Duration.millis(800), new KeyValue(bar.maxWidthProperty(), 0), new KeyValue(bar.minWidthProperty(), 0)));
        tl.getKeyFrames().add(new KeyFrame(Duration.millis(1700),
                new KeyValue(bar.maxWidthProperty(), 220, Interpolator.EASE_BOTH),
                new KeyValue(bar.minWidthProperty(), 220, Interpolator.EASE_BOTH)));
        // sanftes Schweben des Logos
        TranslateTransition bob = new TranslateTransition(Duration.millis(900), logo);
        bob.setFromY(0);
        bob.setToY(-6);
        bob.setAutoReverse(true);
        bob.setCycleCount(2);
        bob.setDelay(Duration.millis(650));
        bob.play();
        tl.setOnFinished(e -> {
            ScaleTransition zoom = new ScaleTransition(Duration.millis(520), col);
            zoom.setToX(1.25);
            zoom.setToY(1.25);
            FadeTransition fade = new FadeTransition(Duration.millis(520), veil);
            fade.setToValue(0);
            ParallelTransition out = new ParallelTransition(zoom, fade);
            out.setOnFinished(x -> { host.getChildren().remove(veil); if (done != null) done.run(); });
            out.play();
        });
        tl.play();
    }

    /** Schimmernde Platzhalter statt Ladekreis. */
    static Node skeleton() {
        HBox row = new HBox(18);
        row.setPadding(new Insets(24, 48, 24, 48));
        for (int i = 0; i < 8; i++) {
            Region img = new Region();
            img.setMinSize(172, 258);
            img.setMaxSize(172, 258);
            img.setStyle("-fx-background-color: rgba(255,255,255,0.07); -fx-background-radius: 16;");
            Region l1 = new Region();
            l1.setMaxSize(120, 12);
            l1.setMinSize(120, 12);
            l1.setStyle("-fx-background-color: rgba(255,255,255,0.07); -fx-background-radius: 6;");
            Region l2 = new Region();
            l2.setMaxSize(70, 10);
            l2.setMinSize(70, 10);
            l2.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 6;");
            VBox c = new VBox(10, img, l1, l2);
            row.getChildren().add(c);
        }
        row.setMouseTransparent(true);
        if (!App.REDUCE) {
            FadeTransition pulse = new FadeTransition(Duration.millis(900), row);
            pulse.setFromValue(0.35);
            pulse.setToValue(1);
            pulse.setAutoReverse(true);
            pulse.setCycleCount(Animation.INDEFINITE);
            pulse.play();
            row.sceneProperty().addListener((o, a, b) -> { if (b == null) pulse.stop(); });
        }
        return row;
    }

    /** Eigene Vektor-Symbole (24x24), einheitlich statt Emojis. */
    static Node icon(String name, double size, String color) {
        SVGPath p = new SVGPath();
        boolean stroke = false;
        switch (name) {
            case "play" -> p.setContent("M8 5v14l11-7z");
            case "info" -> { p.setContent("M12 3a9 9 0 100 18 9 9 0 000-18zM12 11v5M12 8v.5"); stroke = true; }
            case "heart", "heartOn" -> {
                p.setContent("M12 20.5s-7.5-4.6-9.3-9.4C1.5 7.8 3.6 4.8 6.7 4.8c2 0 3.7 1 5.3 3 1.6-2 3.3-3 5.3-3 3.100 0 5.200 3 4 6.300-1.800 4.800-9.300 9.400-9.300 9.400z");
                stroke = !name.equals("heartOn");
            }
            case "download" -> { p.setContent("M12 4v11M7 11l5 5 5-5M5 20h14"); stroke = true; }
            case "check" -> { p.setContent("M5 12.500l4.500 4.500L19 7"); stroke = true; }
            case "circle" -> { p.setContent("M12 4a8 8 0 100 16 8 8 0 000-16z"); stroke = true; }
            case "pause" -> p.setContent("M7 5h4v14H7zM13 5h4v14h-4z");
            default -> p.setContent("M8 5v14l11-7z");
        }
        String fill = name.equals("heartOn") ? "#ff4d6d" : color;
        if (stroke) {
            p.setFill(null);
            p.setStroke(Color.web(color));
            p.setStrokeWidth(2.2);
            p.setStrokeLineCap(StrokeLineCap.ROUND);
            p.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        } else {
            p.setFill(Color.web(fill));
        }
        double k = size / 24.0;
        p.getTransforms().add(new javafx.scene.transform.Scale(k, k, 0, 0));
        Group g = new Group(p);
        StackPane box = new StackPane(g);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
        return box;
    }

    /** Kurzes "Pop" (gross und zurueck) fuer Herz, Haken usw. */
    static void pop(Node n) {
        if (App.REDUCE || n == null) return;
        ScaleTransition a = new ScaleTransition(Duration.millis(140), n);
        a.setToX(1.6);
        a.setToY(1.6);
        ScaleTransition b = new ScaleTransition(Duration.millis(220), n);
        b.setToX(1);
        b.setToY(1);
        b.setInterpolator(Interpolator.EASE_OUT);
        new SequentialTransition(a, b).play();
    }

    /** Menue weich einblenden. */
    static void animateMenu(javafx.scene.control.ContextMenu m) {
        m.setOnShown(e -> {
            if (App.REDUCE || m.getSkin() == null) return;
            Node n = m.getSkin().getNode();
            n.setOpacity(0);
            n.setTranslateY(-10);
            n.setScaleX(0.96);
            n.setScaleY(0.94);
            FadeTransition f = new FadeTransition(Duration.millis(160), n);
            f.setToValue(1);
            TranslateTransition t = new TranslateTransition(Duration.millis(200), n);
            t.setToY(0);
            t.setInterpolator(Interpolator.EASE_OUT);
            ScaleTransition sc = new ScaleTransition(Duration.millis(200), n);
            sc.setToX(1);
            sc.setToY(1);
            new ParallelTransition(f, t, sc).play();
        });
    }

    /** Fortschrittsring (0..1) fuer Karten. */
    static Node ring(double frac, String a1, String a2) {
        double r = 15;
        javafx.scene.shape.Circle bgc = new javafx.scene.shape.Circle(r, Color.web("#000000aa"));
        javafx.scene.shape.Arc arc = new javafx.scene.shape.Arc(0, 0, r - 4, r - 4, 90, -360 * frac);
        arc.setType(javafx.scene.shape.ArcType.OPEN);
        arc.setFill(null);
        arc.setStroke(Color.web(a2));
        arc.setStrokeWidth(3);
        arc.setStrokeLineCap(StrokeLineCap.ROUND);
        javafx.scene.shape.Circle track = new javafx.scene.shape.Circle(r - 4);
        track.setFill(null);
        track.setStroke(Color.web("#ffffff33"));
        track.setStrokeWidth(3);
        StackPane sp = new StackPane(bgc, track, arc);
        sp.setMaxSize(r * 2, r * 2);
        sp.setMouseTransparent(true);
        return sp;
    }
}
