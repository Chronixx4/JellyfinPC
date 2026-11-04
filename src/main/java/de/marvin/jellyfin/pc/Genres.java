package de.marvin.jellyfin.pc;

import java.util.Locale;

/** Farben fuer Genres und Bibliotheken (Chips, Kachel-Leuchten). */
final class Genres {
    private Genres() { }

    private static final String[][] MAP = {
            {"action", "#ff5d4d"}, {"abenteuer", "#ff9a3d"}, {"adventure", "#ff9a3d"},
            {"anime", "#ff4fa3"}, {"animation", "#ff6fb5"}, {"zeichentrick", "#ff6fb5"},
            {"komödie", "#ffc83d"}, {"komoedie", "#ffc83d"}, {"comedy", "#ffc83d"},
            {"horror", "#b3001b"}, {"thriller", "#e8731f"}, {"krimi", "#c9a227"}, {"crime", "#c9a227"},
            {"mystery", "#7a5cff"}, {"fantasy", "#a76bff"}, {"science", "#19c6e8"}, {"sci-fi", "#19c6e8"},
            {"drama", "#6f8cff"}, {"romanti", "#ff6b8b"}, {"romance", "#ff6b8b"}, {"liebe", "#ff6b8b"},
            {"dokumentar", "#3fcf8e"}, {"documentary", "#3fcf8e"}, {"familie", "#7bd88f"}, {"family", "#7bd88f"},
            {"kinder", "#7bd88f"}, {"krieg", "#8a9a5b"}, {"war", "#8a9a5b"}, {"western", "#c98a4b"},
            {"musik", "#d65bff"}, {"music", "#d65bff"}, {"historie", "#b89b6e"}, {"history", "#b89b6e"},
            {"serie", "#19a8ff"}, {"series", "#19a8ff"}, {"film", "#ff7a59"}, {"movie", "#ff7a59"},
    };

    /** Farbe fuer ein Genre oder einen Bibliotheksnamen; Standard ist der Akzent. */
    static String color(String name) {
        if (name == null) return App.A1;
        String n = name.toLowerCase(Locale.ROOT);
        for (String[] m : MAP) if (n.contains(m[0])) return m[1];
        return App.A1;
    }
}
