package de.marvin.jellyfin.pc;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;

/** Einfaches Protokoll (JellyfinPC.log neben der EXE bzw. im Benutzerordner) zur Fehlersuche. */
final class Log {
    private static File file;

    private Log() {}

    static synchronized File file() {
        if (file != null) return file;
        String[] cands = {System.getProperty("launch4j.exedir"), System.getProperty("app.dir"),
                System.getProperty("user.dir"), System.getProperty("java.io.tmpdir")};
        for (String c : cands) {
            if (c == null) continue;
            File d = new File(c);
            if (d.isDirectory() && d.canWrite()) {
                file = new File(d, "JellyfinPC.log");
                return file;
            }
        }
        file = new File("JellyfinPC.log");
        return file;
    }

    static synchronized void i(String msg) {
        try (FileWriter w = new FileWriter(file(), true)) {
            w.write(LocalTime.now().withNano(0) + "  " + msg + System.lineSeparator());
        } catch (Exception ignored) {}
    }

    static void e(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        i(msg + "\n" + sw);
    }
}
