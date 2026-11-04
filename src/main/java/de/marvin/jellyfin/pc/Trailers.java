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

    /** Liefert {videoUrl} oder {videoUrl, audioUrl}. target = YouTube-Adresse oder ytsearch1:-Suche. */
    static List<String> resolve(String target, Consumer<String> status) throws Exception {
        if (!exe().isFile() || exe().length() < 1_000_000) download(status);
        status.accept("Trailer wird gesucht …");
        try {
            return run(target);
        } catch (Exception first) {
            Log.i("yt-dlp: " + first.getMessage() + " - lade neueste Version und versuche es noch einmal");
            download(status);
            status.accept("Trailer wird gesucht …");
            return run(target);
        }
    }

    private static List<String> run(String target) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(exe().getAbsolutePath(), "-g", "--no-playlist", "--no-warnings",
                "-f", "bv*[height<=1080]+ba/b[height<=1080]/b", target);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(90, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new java.io.IOException("Zeitüberschreitung"); }
        List<String> urls = new ArrayList<>();
        for (String l : out.split("\\R")) if (l.trim().startsWith("http")) urls.add(l.trim());
        if (p.exitValue() != 0 || urls.isEmpty()) {
            String m = err.trim();
            throw new java.io.IOException(m.isEmpty() ? "kein Trailer gefunden" : m.substring(Math.max(0, m.length() - 200)));
        }
        return urls;
    }
}
