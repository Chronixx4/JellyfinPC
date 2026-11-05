package de.marvin.jellyfin.mobile;

import android.app.DownloadManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;

import de.marvin.jellyfin.core.JfItem;

/** Offline-Downloads ueber den Android-Download-Manager (laeuft auch bei geschlossener App weiter). */
final class Downloads {
    private Downloads() {}

    static final class Entry {
        String id, name, json, path;
        long dm;
        int status = DownloadManager.STATUS_PENDING;
        long done, total;

        boolean ready() { return status == DownloadManager.STATUS_SUCCESSFUL; }
        boolean failed() { return status == DownloadManager.STATUS_FAILED; }
    }

    private static JSONArray read(MainActivity a) {
        try { return new JSONArray(a.prefs.getString("downloads", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static boolean has(MainActivity a, String id) {
        JSONArray l = read(a);
        for (int i = 0; i < l.length(); i++) if (id.equals(l.optJSONObject(i).optString("id"))) return true;
        return false;
    }

    static void start(MainActivity a, JfItem it) {
        if (has(a, it.id)) { a.toast("Schon in den Downloads"); return; }
        try {
            String ext = it.container == null || it.container.isEmpty() ? "mkv" : it.container.toLowerCase();
            File dir = a.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            File f = new File(dir, it.id + "." + ext);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(a.jf.directUrl(it)))
                    .setTitle(it.title())
                    .setDestinationUri(Uri.fromFile(f))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            long dm = ((DownloadManager) a.getSystemService(android.content.Context.DOWNLOAD_SERVICE)).enqueue(r);
            JSONArray l = read(a);
            l.put(new JSONObject().put("id", it.id).put("name", it.title()).put("json", it.raw.toString())
                    .put("path", f.getAbsolutePath()).put("dm", dm));
            a.prefs.edit().putString("downloads", l.toString()).apply();
            a.toast("Download gestartet: " + it.name);
        } catch (Exception e) {
            a.toast("Download nicht möglich: " + e.getMessage());
        }
    }

    static ArrayList<Entry> list(MainActivity a) {
        ArrayList<Entry> out = new ArrayList<>();
        JSONArray l = read(a);
        DownloadManager dm = (DownloadManager) a.getSystemService(android.content.Context.DOWNLOAD_SERVICE);
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            Entry e = new Entry();
            e.id = o.optString("id");
            e.name = o.optString("name");
            e.json = o.optString("json");
            e.path = o.optString("path");
            e.dm = o.optLong("dm");
            try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(e.dm))) {
                if (c != null && c.moveToFirst()) {
                    e.status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    e.done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    e.total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                } else if (new File(e.path).exists()) {
                    e.status = DownloadManager.STATUS_SUCCESSFUL;
                } else {
                    e.status = DownloadManager.STATUS_FAILED;
                }
            } catch (Exception ex) { /* Status unbekannt */ }
            out.add(e);
        }
        return out;
    }

    static void delete(MainActivity a, Entry e) {
        try { ((DownloadManager) a.getSystemService(android.content.Context.DOWNLOAD_SERVICE)).remove(e.dm); } catch (Exception ignored) { }
        new File(e.path).delete();
        JSONArray l = read(a), n = new JSONArray();
        for (int i = 0; i < l.length(); i++) if (!e.id.equals(l.optJSONObject(i).optString("id"))) n.put(l.opt(i));
        a.prefs.edit().putString("downloads", n.toString()).apply();
    }

    /** Alle ungesehenen Folgen einer Serie laden. */
    static void unseenEpisodes(MainActivity a, JfItem series) {
        a.bg(() -> {
            ArrayList<JfItem> out = new ArrayList<>();
            for (JfItem s : a.jf.seasons(series.id)) for (JfItem e : a.jf.episodes(series.id, s.id)) if (!e.played) out.add(a.jf.item(e.id));
            return out;
        }, eps -> {
            if (eps == null) return;
            int n = 0;
            for (JfItem e : eps) if (!has(a, e.id) && n < 30) { start(a, e); n++; }
            a.toast(n == 0 ? "Nichts Neues zu laden" : n + " Folgen werden geladen");
        });
    }
}
