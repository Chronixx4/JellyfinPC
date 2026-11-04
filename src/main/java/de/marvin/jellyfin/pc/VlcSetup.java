package de.marvin.jellyfin.pc;

import uk.co.caprica.vlcj.binding.lib.LibC;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.factory.discovery.strategy.BaseNativeDiscoveryStrategy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.ArrayList;
import java.util.List;

/** Findet den mitgelieferten VLC-Player (Ordner "vlc" neben der EXE); sonst einen installierten VLC. */
final class VlcSetup {
    private VlcSetup() {}

    /** Mitgelieferte Bibliothek: libvlc.dll, libvlccore.dll und plugins\ im Ordner. */
    private static final class Bundled extends BaseNativeDiscoveryStrategy {
        private final String dir;

        Bundled(String dir) {
            super(new String[]{"libvlc\\.dll", "libvlccore\\.dll"}, new String[]{"%s\\plugins"});
            this.dir = dir;
        }

        @Override public boolean supported() { return RuntimeUtil.isWindows(); }

        @Override protected List<String> discoveryDirectories() {
            List<String> l = new ArrayList<>();
            l.add(dir);
            return l;
        }

        @Override protected boolean setPluginPath(String path) {
            return LibC.INSTANCE._putenv(String.format("%s=%s", PLUGIN_ENV_NAME, path)) == 0;
        }
    }

    private static final String VERSION = "3.0.24";

    /** Im Programm eingebauter Player: wird beim ersten Start nach %LOCALAPPDATA% entpackt. */
    static File embeddedDir(Consumer<String> status) throws Exception {
        String local = System.getenv("LOCALAPPDATA");
        File base = new File(local != null ? local : new File(System.getProperty("user.home"), "AppData\\Local").getPath(),
                "JellyfinPC\\vlc-" + VERSION);
        File vlc = new File(base, "vlc");
        File ok = new File(base, ".ok");
        if (ok.isFile() && new File(vlc, "libvlc.dll").isFile() && new File(vlc, "libvlccore.dll").isFile()) return vlc;
        try (InputStream in = VlcSetup.class.getResourceAsStream("/vlc-win64.zip")) {
            if (in == null) return null;
            status.accept("Player wird eingerichtet … (nur beim ersten Start)");
            Log.i("Entpacke eingebauten Player nach " + base);
            deleteTree(base);
            base.mkdirs();
            try (ZipInputStream z = new ZipInputStream(in)) {
                ZipEntry e;
                byte[] buf = new byte[1 << 16];
                String root = base.getCanonicalPath() + File.separator;
                while ((e = z.getNextEntry()) != null) {
                    File out = new File(base, e.getName());
                    if (!out.getCanonicalPath().startsWith(root)) continue; // Zip-Slip verhindern
                    if (e.isDirectory()) { out.mkdirs(); continue; }
                    out.getParentFile().mkdirs();
                    try (OutputStream o = new FileOutputStream(out)) {
                        int n;
                        while ((n = z.read(buf)) > 0) o.write(buf, 0, n);
                    }
                }
            }
            new FileOutputStream(ok).close();
        }
        return vlc;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** Ordner mit dem Player: eingebaut, sonst neben der EXE; null wenn nichts da ist. */
    static File bundledDir() {
        List<File> bases = new ArrayList<>();
        String app = System.getProperty("launch4j.exedir");
        if (app != null) bases.add(new File(app));
        app = System.getProperty("app.dir");
        if (app != null) bases.add(new File(app));
        try {
            bases.add(new File(VlcSetup.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile());
        } catch (Exception ignored) {}
        bases.add(new File(System.getProperty("user.dir", ".")));
        bases.add(new File(System.getProperty("user.home", "."), "Documents\\JellyfinPC"));
        for (File b : bases) {
            if (b == null) continue;
            File v = new File(b, "vlc");
            if (new File(v, "libvlc.dll").isFile() && new File(v, "libvlccore.dll").isFile()) return v;
        }
        return null;
    }

    /** Erzeugt die Player-Fabrik: erst der eingebaute Player, dann ein Ordner neben der EXE, dann ein installierter VLC. */
    static MediaPlayerFactory factory(Consumer<String> status, String... args) {
        Throwable first = null;
        List<File> dirs = new ArrayList<>();
        try {
            File emb = embeddedDir(status);
            if (emb != null) dirs.add(emb);
        } catch (Throwable t) {
            first = t;
            Log.e("Entpacken fehlgeschlagen", t);
        }
        File side = bundledDir();
        if (side != null) dirs.add(side);
        String[] all = args; // keine Zusatzoptionen: unbekannte Optionen lassen libVLC komplett scheitern
        for (File dir : dirs) {
            try {
                Log.i("Lade Player aus " + dir);
                com.sun.jna.NativeLibrary.addSearchPath("libvlc", dir.getAbsolutePath());
                com.sun.jna.NativeLibrary.addSearchPath("libvlccore", dir.getAbsolutePath());
                return new MediaPlayerFactory(new NativeDiscovery(new Bundled(dir.getAbsolutePath())), all);
            } catch (Throwable t) {
                first = t;
                Log.e("Player aus " + dir + " nicht ladbar", t);
            }
        }
        try {
            Log.i("Versuche installierten VLC");
            return new MediaPlayerFactory(all);
        } catch (Throwable t) {
            Log.e("Kein Player gefunden", t);
            throw new IllegalStateException("Der Player konnte nicht gestartet werden." + (first != null ? "\n" + first : "")
                    + "\n" + t, t);
        }
    }
}
