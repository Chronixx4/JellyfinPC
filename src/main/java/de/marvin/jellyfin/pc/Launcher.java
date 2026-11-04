package de.marvin.jellyfin.pc;

/** Einstieg fuer die Fat-JAR (JavaFX-Application darf dort nicht direkt Main-Klasse sein). */
public final class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}
