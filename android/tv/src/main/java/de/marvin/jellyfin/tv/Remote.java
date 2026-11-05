package de.marvin.jellyfin.tv;

import android.os.Handler;
import android.os.Looper;

import de.marvin.jellyfin.core.Jf;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/**
 * Macht die TV-App fuer andere Jellyfin-Clients (Handy-App, Jellyfin-Web) steuerbar: haelt eine WebSocket-Verbindung,
 * meldet die unterstuetzten Befehle an und fuehrt "Play", "Playstate" und "GeneralCommand" aus.
 * Laeuft, solange die App geoeffnet ist (auch waehrend der Wiedergabe).
 */
final class Remote {
    private static Remote instance;
    private final MainActivity a;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS).build();
    private WebSocket ws;
    private String connectedToken = "";
    private boolean stopped;

    private Remote(MainActivity a) { this.a = a; }

    static synchronized void start(MainActivity a) {
        if (instance != null) instance.stopped = true;
        instance = new Remote(a);
        instance.loop();
    }

    static synchronized void stop() {
        if (instance != null) { instance.stopped = true; instance.close(); instance = null; }
    }

    private void loop() {
        if (stopped) return;
        try {
            if (!a.jf.loggedIn()) close();
            else if (ws == null || !a.jf.token.equals(connectedToken)) { close(); connect(); }
        } catch (Throwable ignored) { }
        h.postDelayed(this::loop, ws == null ? 5000 : 3000);
    }

    private synchronized void close() {
        WebSocket w = ws;
        ws = null;
        connectedToken = "";
        if (w != null) try { w.close(1000, "bye"); } catch (Exception ignored) { }
    }

    private synchronized void drop(WebSocket s) {
        if (ws == s) { ws = null; connectedToken = ""; }
    }

    private synchronized void connect() {
        final String tok = a.jf.token;
        Request r = new Request.Builder().url(a.jf.socketUrl()).build();
        ws = http.newWebSocket(r, new WebSocketListener() {
            @Override public void onOpen(WebSocket s, Response resp) {
                new Thread(() -> {
                    try {
                        a.jf.registerCapabilities("VolumeUp", "VolumeDown", "Mute", "Unmute", "ToggleMute", "SetVolume", "DisplayMessage");
                    } catch (Exception ignored) { }
                }).start();
            }
            @Override public void onMessage(WebSocket s, String text) {
                try { handle(s, new JSONObject(text)); } catch (Exception ignored) { }
            }
            @Override public void onClosed(WebSocket s, int c, String reason) { drop(s); }
            @Override public void onFailure(WebSocket s, Throwable t, Response resp) { drop(s); }
        });
        connectedToken = tok;
    }

    private void handle(WebSocket s, JSONObject m) {
        String type = m.optString("MessageType");
        JSONObject d = m.optJSONObject("Data");
        switch (type) {
            case "ForceKeepAlive": s.send("{\"MessageType\":\"KeepAlive\"}"); break;
            case "Play": if (d != null) play(d); break;
            case "Playstate":
                if (d != null) h.post(() -> {
                    PlayerActivity p = PlayerActivity.current;
                    if (p != null) p.remote(d.optString("Command"), d.optLong("SeekPositionTicks", 0) / 10000);
                });
                break;
            case "GeneralCommand": if (d != null) general(d); break;
            default: break;
        }
    }

    private void play(JSONObject d) {
        JSONArray ids = d.optJSONArray("ItemIds");
        if (ids == null || ids.length() == 0) return;
        int idx = Math.max(0, Math.min(d.optInt("StartIndex", 0), ids.length() - 1));
        long ticks = d.optLong("StartPositionTicks", 0);
        String id = ids.optString(idx);
        h.post(() -> a.remotePlay(id, ticks));
    }

    private void general(JSONObject d) {
        String name = d.optString("Name");
        JSONObject args = d.optJSONObject("Arguments");
        h.post(() -> {
            switch (name) {
                case "SetVolume": if (args != null && args.optInt("Volume", -1) >= 0) a.remoteVolume(args.optInt("Volume"), 0); break;
                case "VolumeUp": a.remoteVolume(0, 10); break;
                case "VolumeDown": a.remoteVolume(0, -10); break;
                case "Mute": a.remoteMute(1); break;
                case "Unmute": a.remoteMute(0); break;
                case "ToggleMute": a.remoteMute(-1); break;
                case "DisplayMessage":
                    if (args != null) a.toast(args.optString("Text", args.optString("Header")));
                    break;
                default: break;
            }
        });
    }
}
