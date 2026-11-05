package de.marvin.jellyfin.pc;

import javafx.application.Platform;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;

/**
 * Macht die PC-App fuer andere Jellyfin-Clients (z. B. die Handy-App) steuerbar: haelt eine WebSocket-Verbindung,
 * meldet die unterstuetzten Befehle beim Server an und fuehrt "Play", "Playstate" und "GeneralCommand" aus.
 */
final class Remote {
    private final Jf jf;
    private final App app;
    private volatile WebSocket ws;
    private volatile String connectedToken = "";
    private final StringBuilder partial = new StringBuilder();
    private boolean started;

    Remote(Jf jf, App app) {
        this.jf = jf;
        this.app = app;
    }

    /** Startet die Hintergrundschleife (einmal); sie verbindet neu, wenn die Verbindung oder der Login wechselt. */
    synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> {
            long wait = 3000;
            while (true) {
                try {
                    if (!jf.loggedIn()) {
                        close();
                    } else if (ws == null || !jf.token.equals(connectedToken)) {
                        close();
                        connect();
                        wait = 3000;
                    }
                } catch (Throwable e) {
                    Log.i("Fernsteuerung: Verbindung fehlgeschlagen (" + e.getMessage() + ")");
                    wait = Math.min(wait * 2, 30000);
                }
                try { Thread.sleep(ws == null ? wait : 3000); } catch (InterruptedException e) { return; }
            }
        }, "remote-control");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void close() {
        WebSocket w = ws;
        ws = null;
        connectedToken = "";
        if (w != null) try { w.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); } catch (Exception ignored) { }
    }

    private void connect() throws Exception {
        String tok = jf.token;
        WebSocket w = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create(jf.socketUrl()), new WebSocket.Listener() {
                    @Override public void onOpen(WebSocket s) { s.request(1); }
                    @Override public CompletionStage<?> onText(WebSocket s, CharSequence d, boolean last) {
                        partial.append(d);
                        if (last) {
                            String m = partial.toString();
                            partial.setLength(0);
                            try { handle(s, new JSONObject(m)); } catch (Exception e) { Log.e("Fernsteuerung Nachricht", e); }
                        }
                        s.request(1);
                        return null;
                    }
                    @Override public CompletionStage<?> onClose(WebSocket s, int c, String r) { drop(s); return null; }
                    @Override public void onError(WebSocket s, Throwable t) { drop(s); }
                }).join();
        ws = w;
        connectedToken = tok;
        Thread ka = new Thread(() -> {
            try {
                while (ws == w) {
                    Thread.sleep(20000);
                    if (ws == w) w.sendText("{\"MessageType\":\"KeepAlive\"}", true);
                }
            } catch (Exception ignored) { }
        }, "remote-keepalive");
        ka.setDaemon(true);
        ka.start();
        jf.registerCapabilities();
        Log.i("Fernsteuerung bereit (Handy kann diesen PC steuern)");
    }

    private synchronized void drop(WebSocket s) {
        if (ws == s) { ws = null; connectedToken = ""; }
    }

    private void handle(WebSocket s, JSONObject m) {
        String type = m.optString("MessageType");
        JSONObject d = m.optJSONObject("Data");
        switch (type) {
            case "ForceKeepAlive" -> s.sendText("{\"MessageType\":\"KeepAlive\"}", true);
            case "Play" -> { if (d != null) play(d); }
            case "Playstate" -> { if (d != null) Platform.runLater(() -> app.remotePlaystate(d.optString("Command"), d.optLong("SeekPositionTicks", 0) / 10000)); }
            case "GeneralCommand" -> { if (d != null) general(d); }
            default -> { }
        }
    }

    private void play(JSONObject d) {
        JSONArray ids = d.optJSONArray("ItemIds");
        if (ids == null || ids.length() == 0) return;
        String cmd = d.optString("PlayCommand", "PlayNow");
        int idx = Math.max(0, Math.min(d.optInt("StartIndex", 0), ids.length() - 1));
        long ticks = d.optLong("StartPositionTicks", 0);
        String id = ids.optString(idx);
        Platform.runLater(() -> app.remotePlay(id, ticks, cmd));
    }

    private void general(JSONObject d) {
        String name = d.optString("Name");
        JSONObject a = d.optJSONObject("Arguments");
        switch (name) {
            case "SetVolume" -> { if (a != null) { int v = a.optInt("Volume", -1); if (v >= 0) Platform.runLater(() -> app.remoteVolume(v)); } }
            case "VolumeUp" -> Platform.runLater(() -> app.remoteVolumeDelta(10));
            case "VolumeDown" -> Platform.runLater(() -> app.remoteVolumeDelta(-10));
            case "Mute" -> Platform.runLater(() -> app.remoteMute(1));
            case "Unmute" -> Platform.runLater(() -> app.remoteMute(0));
            case "ToggleMute" -> Platform.runLater(() -> app.remoteMute(-1));
            case "DisplayMessage" -> {
                if (a != null) { String t = a.optString("Text", a.optString("Header")); Platform.runLater(() -> app.toast(t)); }
            }
            default -> { }
        }
    }
}
