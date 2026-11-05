package de.marvin.jellyfin.tv;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Selbst-Update ueber GitHub: liest version.json (Eintrag "tv"), laedt die APK und oeffnet den
 * Android-Installationsdialog. Die Build-Nummer ist der Vergleichswert (= versionCode der APK).
 */
final class Updater {
    static final String URL = "https://raw.githubusercontent.com/Chronixx4/JellyfinPC/main/version.json";

    private Updater() {}

    static int currentBuild(MainActivity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode;
        } catch (Exception e) { return 0; }
    }

    static String currentVersion(MainActivity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Exception e) { return "?"; }
    }

    /** manual = vom Nutzer ausgeloest: meldet auch "bereits aktuell" und Fehler. */
    static void check(MainActivity a, boolean manual) {
        a.bg(() -> {
            HttpURLConnection c = (HttpURLConnection) new URL(URL + "?t=" + System.currentTimeMillis()).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return new JSONObject(bo.toString("UTF-8")).getJSONObject("tv");
            } finally {
                c.disconnect();
            }
        }, m -> {
            if (m == null) return; // Fehler wurde schon als Hinweis gezeigt
            int build = m.optInt("build", 0);
            String url = m.optString("url", "");
            if (build <= currentBuild(a) || url.isEmpty()) {
                if (manual) a.toast("Du hast die neueste Version (" + currentVersion(a) + ")");
                return;
            }
            String notes = m.optString("notes", "");
            new AlertDialog.Builder(a)
                    .setTitle("Update auf " + m.optString("version") + " verfügbar")
                    .setMessage((notes.isEmpty() ? "" : notes + "\n\n") + "Jetzt herunterladen und installieren?")
                    .setPositiveButton("Installieren", (d, w) -> download(a, url, m.optString("md5", "")))
                    .setNegativeButton("Später", null)
                    .show();
        }, !manual);
    }

    private static void download(MainActivity a, String url, String md5) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            a.toast("Bitte erlaube „Apps installieren“ für Jellyfin und starte das Update erneut");
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        ProgressDialog pd = new ProgressDialog(a);
        pd.setMessage("Update wird geladen …");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setCancelable(false);
        pd.show();
        a.bg(() -> {
            File dir = new File(a.getCacheDir(), "updates");
            dir.mkdirs();
            File out = new File(dir, "JellyfinTV.apk");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            int len = c.getContentLength();
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    fo.write(buf, 0, n);
                    md.update(buf, 0, n);
                    total += n;
                    if (len > 0) { int p = (int) (total * 100 / len); a.runOnUiThread(() -> pd.setProgress(p)); }
                }
            } finally {
                c.disconnect();
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            if (!md5.isEmpty() && !hex.toString().equalsIgnoreCase(md5)) {
                out.delete();
                throw new Exception("Prüfsumme der Datei stimmt nicht");
            }
            return out;
        }, f -> {
            pd.dismiss();
            if (f == null) return;
            Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        });
    }
}
