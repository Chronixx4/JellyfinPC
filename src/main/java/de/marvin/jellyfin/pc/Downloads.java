package de.marvin.jellyfin.pc;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Offline-Downloads: Dateien liegen als <id>.<ext> + <id>.json (+ <id>.jpg) in einem Ordner. */
final class Downloads {
    private Downloads() {}

    static final class Job {
        final JfItem item;
        volatile long done, total;
        volatile boolean cancel;
        volatile String error;
        Job(JfItem i) { item = i; }
    }

    static final class Entry {
        JfItem item;
        File file, poster;
        long size;
    }

    static final Map<String, Job> active = new ConcurrentHashMap<>();

    static File dir() {
        String custom = System.getProperty("jf.dldir");
        File d;
        if (custom != null) d = new File(custom);
        else {
            String home = System.getProperty("user.home", ".");
            File videos = new File(home, "Videos");
            d = new File(videos.isDirectory() ? videos : new File(home), "JellyfinPC-Downloads");
        }
        d.mkdirs();
        return d;
    }

    /** Fertig geladene Datei oder null. */
    static File file(String id) {
        File[] fs = dir().listFiles((dd, n) -> n.startsWith(id + ".") && !n.endsWith(".json") && !n.endsWith(".jpg")
                && !n.endsWith(".part"));
        return fs != null && fs.length > 0 ? fs[0] : null;
    }

    static boolean has(String id) { return file(id) != null; }

    static boolean busy(String id) { return active.containsKey(id); }

    static void start(Jf jf, JfItem it) {
        if (has(it.id) || busy(it.id)) return;
        Job job = new Job(it);
        active.put(it.id, job);
        Thread t = new Thread(() -> run(jf, job), "download");
        t.setDaemon(true);
        t.start();
    }

    private static void run(Jf jf, Job job) {
        JfItem it = job.item;
        File d = dir();
        File part = new File(d, it.id + ".part");
        try {
            String ext = it.container.isEmpty() ? "mkv" : it.container.split(",")[0].toLowerCase();
            if (!ext.matches("[a-z0-9]{2,5}")) ext = "mkv";
            HttpURLConnection c = (HttpURLConnection) new URL(jf.directUrl(it)).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            if (c.getResponseCode() >= 400) throw new java.io.IOException("Server antwortet mit " + c.getResponseCode());
            job.total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (job.cancel) throw new java.io.IOException("abgebrochen");
                    out.write(buf, 0, n);
                    job.done += n;
                }
            }
            // Poster und Metadaten
            String pu = jf.poster(it);
            if (pu == null) pu = jf.landscape(it);
            if (pu != null) {
                try (InputStream in = new URL(pu).openStream()) {
                    Files.copy(in, new File(d, it.id + ".jpg").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) { }
            }
            if (it.raw != null) Files.write(new File(d, it.id + ".json").toPath(),
                    it.raw.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(part.toPath(), new File(d, it.id + "." + ext).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Log.i("Download fertig: " + it.name);
        } catch (Exception e) {
            Log.e("Download " + it.name, e);
            job.error = job.cancel ? "abgebrochen" : String.valueOf(e.getMessage());
            part.delete();
        } finally {
            if (job.error == null) active.remove(it.id);
            else {
                Thread w = new Thread(() -> {
                    try { Thread.sleep(8000); } catch (InterruptedException ignored) { }
                    active.remove(it.id, job);
                });
                w.setDaemon(true);
                w.start();
            }
        }
    }

    static void cancel(String id) {
        Job j = active.get(id);
        if (j != null) j.cancel = true;
    }

    static void delete(String id) {
        File[] fs = dir().listFiles((dd, n) -> n.startsWith(id + "."));
        if (fs != null) for (File f : fs) f.delete();
    }

    static List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        File[] js = dir().listFiles((dd, n) -> n.endsWith(".json"));
        if (js == null) return out;
        for (File j : js) {
            try {
                String id = j.getName().substring(0, j.getName().length() - 5);
                File f = file(id);
                if (f == null) continue;
                Entry e = new Entry();
                e.item = JfItem.from(new JSONObject(new String(Files.readAllBytes(j.toPath()), StandardCharsets.UTF_8)));
                e.file = f;
                e.size = f.length();
                File p = new File(dir(), id + ".jpg");
                e.poster = p.isFile() ? p : null;
                out.add(e);
            } catch (Exception ignored) { }
        }
        out.sort((a, b) -> {
            int c = a.item.seriesName.compareToIgnoreCase(b.item.seriesName);
            if (c != 0) return c;
            c = Integer.compare(a.item.seasonNo, b.item.seasonNo);
            if (c != 0) return c;
            c = Integer.compare(a.item.episodeNo, b.item.episodeNo);
            return c != 0 ? c : a.item.name.compareToIgnoreCase(b.item.name);
        });
        return out;
    }

    static String size(long b) {
        return b >= 1e9 ? String.format(java.util.Locale.GERMANY, "%.2f GB", b / 1e9)
                : String.format(java.util.Locale.GERMANY, "%.0f MB", b / 1e6);
    }
}
