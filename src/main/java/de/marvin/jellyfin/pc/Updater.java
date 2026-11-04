package de.marvin.jellyfin.pc;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Auto-Update: liest die version.json aus dem GitHub-Repo (Raw-URL); die EXE liegt als Release-Asset.
 * Format (plattformuebergreifend, auch fuer die Android-Apps):
 * {"windows":{"build":17,"version":"1.7","url":"JellyfinPC.exe","md5":"...","notes":"..."},"tv":{...},"mobile":{...}}
 * "url" darf relativ zur version.json sein (z. B. ein Release-Asset: https://github.com/Chronixx4/JellyfinPC/releases/download/v1.7/JellyfinPC.exe).
 */
final class Updater {
    private Updater() {}

    static final String DEFAULT_URL = "https://raw.githubusercontent.com/Chronixx4/JellyfinPC/main/version.json";
    static final String PLATFORM = "windows";

    static final class Info {
        int build;
        String version = "", url = "", md5 = "", notes = "";
    }

    static int currentBuild() { return Integer.parseInt(props().getProperty("build", "0")); }

    static String currentVersion() { return props().getProperty("version", "?"); }

    private static Properties props;

    private static synchronized Properties props() {
        if (props == null) {
            props = new Properties();
            try (InputStream in = Updater.class.getResourceAsStream("/app.properties")) {
                if (in != null) props.load(in);
            } catch (Exception ignored) { }
        }
        return props;
    }

    /** Fragt den Server; liefert null, wenn es nichts Neues gibt. Wirft bei Verbindungsfehlern. */
    static Info check(String versionUrl) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(versionUrl).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(6000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "JellyfinPC-Updater");
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        String body;
        try (InputStream in = c.getInputStream()) { body = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        JSONObject o = new JSONObject(body).optJSONObject(PLATFORM);
        if (o == null) return null;
        Info i = new Info();
        i.build = o.optInt("build", 0);
        i.version = o.optString("version", "");
        i.md5 = o.optString("md5", "").toLowerCase();
        i.notes = o.optString("notes", "");
        i.url = URI.create(versionUrl).resolve(o.optString("url", "JellyfinPC.exe")).toString();
        return i.build > currentBuild() ? i : null;
    }

    /** Laedt die neue EXE neben die alte (Datei .new), prueft die Pruefsumme. */
    static File download(Info i, Consumer<Double> progress) throws Exception {
        File dir = exeDir();
        File out = new File(dir, "JellyfinPC.update");
        HttpURLConnection c = (HttpURLConnection) new URL(i.url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "JellyfinPC-Updater");
        if (c.getResponseCode() != 200) throw new java.io.IOException("Download: HTTP " + c.getResponseCode());
        long total = c.getContentLengthLong(), done = 0;
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                if (total > 0) progress.accept((double) done / total);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format("%02x", b));
        if (!i.md5.isEmpty() && !i.md5.equals(hex.toString())) {
            out.delete();
            throw new java.io.IOException("Pruefsumme stimmt nicht - Update abgebrochen");
        }
        return out;
    }

    static File exeDir() {
        String d = System.getProperty("launch4j.exedir");
        if (d != null && new File(d).isDirectory()) return new File(d);
        return new File(System.getProperty("user.dir", "."));
    }

    static boolean canSelfReplace() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                && new File(exeDir(), "JellyfinPC.exe").isFile();
    }

    /** Raeumt die beim letzten Update zur Seite gelegte alte EXE weg. */
    static void cleanup() {
        try { new File(exeDir(), "JellyfinPC.exe.old").delete(); } catch (Exception ignored) { }
    }

    /**
     * Tauscht die EXE aus und startet sie neu, ohne Terminal-Fenster: Windows erlaubt es, eine laufende EXE umzubenennen.
     * Also: alte EXE -> .old, neue EXE an ihre Stelle, neue EXE starten. Nur wenn das nicht klappt, springt ein
     * unsichtbares Hilfsskript (wscript) ein.
     */
    static void applyAndRestart(File update) throws Exception {
        File dir = exeDir();
        File exe = new File(dir, "JellyfinPC.exe");
        File old = new File(dir, "JellyfinPC.exe.old");
        old.delete();
        if (exe.renameTo(old)) {
            if (update.renameTo(exe)) {
                new ProcessBuilder(exe.getAbsolutePath()).directory(dir).start();
                return;
            }
            old.renameTo(exe); // zurueck, wenn das Verschieben scheitert
        }
        File bat = new File(System.getProperty("java.io.tmpdir"), "jellyfinpc_update.bat");
        File vbs = new File(System.getProperty("java.io.tmpdir"), "jellyfinpc_update.vbs");
        String script = "@echo off\r\n"
                + "set /a n=0\r\n"
                + ":wait\r\n"
                + "set /a n+=1\r\n"
                + "if %n% gtr 60 goto fail\r\n"
                + "ping -n 2 127.0.0.1 >nul\r\n"
                + "move /y \"" + update.getAbsolutePath() + "\" \"" + exe.getAbsolutePath() + "\" >nul 2>&1\r\n"
                + "if errorlevel 1 goto wait\r\n"
                + "start \"\" \"" + exe.getAbsolutePath() + "\"\r\n"
                + "goto end\r\n"
                + ":fail\r\n"
                + "echo Update fehlgeschlagen > \"" + new File(dir, "update-fehler.txt").getAbsolutePath() + "\"\r\n"
                + ":end\r\n"
                + "del \"%~f0\"\r\n";
        Files.write(bat.toPath(), script.getBytes(StandardCharsets.ISO_8859_1));
        String v = "CreateObject(\"WScript.Shell\").Run \"cmd /c \"\"" + bat.getAbsolutePath() + "\"\"\", 0, False\r\n";
        Files.write(vbs.toPath(), v.getBytes(StandardCharsets.ISO_8859_1));
        new ProcessBuilder("wscript.exe", "//B", vbs.getAbsolutePath()).start();
    }
}
