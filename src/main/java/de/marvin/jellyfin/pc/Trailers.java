package de.marvin.jellyfin.pc;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Trailer im eigenen Player: yt-dlp (wird beim ersten Mal einmalig nach %LOCALAPPDATA%\JellyfinPC geladen)
 * ermittelt die Stream-Adresse eines YouTube-Trailers, libVLC spielt sie ab.
 */
final class Trailers {
    private Trailers() { }

    static final String DOWNLOAD = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";

    private static File dir() {
        String d = System.getenv("LOCALAPPDATA");
        File f = new File(d != null ? d : System.getProperty("user.home"), "JellyfinPC");
        f.mkdirs();
        return f;
    }

    static File exe() { return new File(dir(), "yt-dlp.exe"); }

    private static void download(Consumer<String> status) throws Exception {
        status.accept("Trailer-Helfer wird einmalig geladen …");
        File tmp = new File(dir(), "yt-dlp.exe.part");
        HttpURLConnection c = (HttpURLConnection) new URL(DOWNLOAD).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "JellyfinPC");
        if (c.getResponseCode() != 200) throw new java.io.IOException("Download: HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream(); OutputStream os = new java.io.FileOutputStream(tmp)) {
            in.transferTo(os);
        }
        File exe = exe();
        exe.delete();
        if (!tmp.renameTo(exe)) throw new java.io.IOException("yt-dlp konnte nicht gespeichert werden");
    }

    /**
     * Laedt den Trailer in eine lokale Datei (spulbar und ohne Ruckeln) und liefert {Video} oder {Video, Ton} als Pfade.
     * target = YouTube-Adresse oder ytsearch1:-Suche.
     */
    static List<String> fetch(String target, Consumer<String> status) throws Exception {
        if (!exe().isFile() || exe().length() < 1_000_000) download(status);
        File dir = new File(dir(), "trailers");
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        status.accept("Trailer wird gesucht und geladen …");
        try {
            return grab(target, dir);
        } catch (Exception first) {
            Log.i("yt-dlp: " + first.getMessage() + " - lade neueste Version und versuche es noch einmal");
            download(status);
            status.accept("Trailer wird gesucht und geladen …");
            return grab(target, dir);
        }
    }

    private static List<String> grab(String target, File dir) throws Exception {
        List<String> out = new ArrayList<>();
        try {
            String v = file(target, dir, "bv[height<=1080][ext=mp4]/bv[height<=1080]", "v");
            String a = file(target, dir, "ba[ext=m4a]/ba", "a");
            out.add(v);
            out.add(a);
        } catch (Exception split) {
            Log.i("Getrennte Spuren nicht moeglich (" + split.getMessage() + "), nehme Einzeldatei");
            for (File f : dir.listFiles()) f.delete();
            out.clear();
            out.add(file(target, dir, "b[ext=mp4]/b", "s"));
        }
        return out;
    }

    private static String file(String target, File dir, String fmt, String tag) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(exe().getAbsolutePath(), "-q", "--no-warnings", "--no-playlist", "--no-simulate",
                "--print", "after_move:filepath", "-f", fmt, "-o", new File(dir, "t_" + tag + "_%(id)s.%(ext)s").getAbsolutePath(), target);
        Process p = pb.start();
        // stderr nebenbei leeren, damit der Prozess nicht blockiert
        StringBuilder err = new StringBuilder();
        Thread et = new Thread(() -> {
            try { err.append(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)); } catch (Exception ignored) { }
        });
        et.setDaemon(true);
        et.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(240, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new java.io.IOException("Zeitüberschreitung"); }
        et.join(2000);
        String last = "";
        for (String l : out.split("\\R")) if (!l.trim().isEmpty()) last = l.trim();
        if (p.exitValue() != 0 || last.isEmpty() || !new File(last).isFile()) {
            String m = err.toString().trim();
            throw new java.io.IOException(m.isEmpty() ? "kein Trailer gefunden" : m.substring(Math.max(0, m.length() - 200)));
        }
        return last;
    }
}
