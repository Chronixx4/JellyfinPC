package de.marvin.jellyfin.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import de.marvin.jellyfin.core.Jf;
import de.marvin.jellyfin.core.JfItem;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Gemeinsam schauen ueber Jellyfin SyncPlay (WebSocket fuer Meldungen, REST fuer Aktionen).
 * Gleiche Ablaufregeln wie in der PC-App: Eingaben gehen an die Gruppe, ausgefuehrt wird, was der Server befiehlt.
 */
final class SyncPlay {
    /** Wird von MainActivity gesetzt: Gruppe startet einen Titel / Hinweis anzeigen. */
    interface Ui { void start(String itemId, long startTicks); void toast(String s); }
    /** Wird vom Player gesetzt: Befehl der Gruppe (Pause, Unpause, Seek, Stop). */
    interface Cmd { void command(String cmd, long posMs, long delayMs); }

    private static SyncPlay instance;
    static synchronized SyncPlay get(Context c) {
        if (instance == null) instance = new SyncPlay(Jf.get(c.getApplicationContext()));
        return instance;
    }

    static volatile Ui ui;
    static volatile Cmd cmd;

    private final Jf jf;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS).build();
    private WebSocket ws;

    volatile String groupId = "", groupName = "";
    volatile List<String> participants = new ArrayList<>();
    volatile String playlistItemId = "";
    volatile int version;      // zaehlt Aenderungen hoch (fuer die Oberflaeche)

    private SyncPlay(Jf jf) { this.jf = jf; }

    boolean inGroup() { return !groupId.isEmpty(); }

    private void changed() { version++; }

    private void say(String s) {
        Ui u = ui;
        if (u != null) main.post(() -> u.toast(s));
    }

    // ------------------------------------------------------------ Verbindung

    synchronized void connect() {
        if (ws != null) return;
        ws = client.newWebSocket(new Request.Builder().url(jf.socketUrl()).build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket w, String text) {
                try { handle(new JSONObject(text)); } catch (Exception ignored) { }
            }
            @Override public void onClosed(WebSocket w, int code, String reason) { drop(w); }
            @Override public void onFailure(WebSocket w, Throwable t, Response r) { drop(w); }
        });
    }

    private synchronized void drop(WebSocket w) {
        if (ws != w) return;
        ws = null;
        if (inGroup()) { groupId = ""; groupName = ""; participants = new ArrayList<>(); }
        changed();
    }

    synchronized void shutdown() {
        WebSocket w = ws;
        ws = null;
        if (w != null) w.close(1000, "bye");
        groupId = "";
        groupName = "";
        participants = new ArrayList<>();
        changed();
    }

    // ------------------------------------------------------------ Aktionen (im Hintergrund aufrufen)

    JSONArray list() throws Exception {
        connect();
        return jf.syncList();
    }

    void create(String name) throws Exception {
        connect();
        jf.syncPost("New", new JSONObject().put("GroupName", name));
    }

    void join(String id) throws Exception {
        connect();
        jf.syncPost("Join", new JSONObject().put("GroupId", id));
    }

    void leave() throws Exception {
        jf.syncPost("Leave", null);
        groupId = "";
        groupName = "";
        participants = new ArrayList<>();
        changed();
    }

    void setQueue(JfItem it, long startTicks) throws Exception {
        jf.syncPost("SetNewQueue", new JSONObject().put("PlayingQueue", new JSONArray().put(it.id))
                .put("PlayingItemPosition", 0).put("StartPositionTicks", Math.max(0, startTicks)));
    }

    void pause() { post("Pause", null); }

    void unpause() { post("Unpause", null); }

    void seek(long ms) {
        try { post("Seek", new JSONObject().put("PositionTicks", ms * 10000)); } catch (Exception ignored) { }
    }

    /** Meldet "bereit" (nach dem Laden oder Springen); der Server startet danach alle gemeinsam. */
    void ready(long posMs, boolean playing) {
        try {
            post("Ready", new JSONObject().put("When", Instant.now().toString()).put("PositionTicks", posMs * 10000)
                    .put("IsPlaying", playing).put("PlaylistItemId", playlistItemId));
        } catch (Exception ignored) { }
    }

    private void post(String action, JSONObject body) {
        if (!inGroup()) return;
        Thread t = new Thread(() -> {
            try { jf.syncPost(action, body); } catch (Exception ignored) { }
        }, "syncplay-post");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ Eingehende Meldungen

    void handle(JSONObject m) {
        String type = m.optString("MessageType");
        Object data = m.opt("Data");
        switch (type) {
            case "ForceKeepAlive": {
                WebSocket w = ws;
                if (w != null) w.send("{\"MessageType\":\"KeepAlive\"}");
                break;
            }
            case "SyncPlayGroupUpdate":
                if (data instanceof JSONObject) groupUpdate((JSONObject) data);
                break;
            case "SyncPlayCommand":
                if (data instanceof JSONObject) command((JSONObject) data);
                break;
            default:
        }
    }

    private void groupUpdate(JSONObject d) {
        String t = d.optString("Type");
        Object inner = d.opt("Data");
        switch (t) {
            case "GroupJoined":
                if (inner instanceof JSONObject) {
                    JSONObject g = (JSONObject) inner;
                    groupId = g.optString("GroupId");
                    groupName = g.optString("GroupName");
                    List<String> ps = new ArrayList<>();
                    JSONArray a = g.optJSONArray("Participants");
                    if (a != null) for (int i = 0; i < a.length(); i++) ps.add(a.optString(i));
                    participants = ps;
                    say("Gruppe „" + groupName + "“ beigetreten");
                }
                break;
            case "UserJoined":
            case "UserLeft": {
                String u = String.valueOf(inner);
                List<String> ps = new ArrayList<>(participants);
                if (t.equals("UserJoined")) { if (!ps.contains(u)) ps.add(u); } else ps.remove(u);
                participants = ps;
                say(u + (t.equals("UserJoined") ? " ist beigetreten" : " hat die Gruppe verlassen"));
                break;
            }
            case "GroupLeft":
            case "NotInGroup":
                groupId = "";
                groupName = "";
                participants = new ArrayList<>();
                break;
            case "GroupDoesNotExist": say("Diese Gruppe gibt es nicht mehr"); break;
            case "LibraryAccessDenied": say("Kein Zugriff auf diese Bibliothek"); break;
            case "PlayQueue":
                if (inner instanceof JSONObject) playQueue((JSONObject) inner);
                break;
            default:
        }
        changed();
    }

    private void playQueue(JSONObject q) {
        String reason = q.optString("Reason");
        if (!reason.equals("NewPlaylist") && !reason.equals("SetCurrentItem")) return;
        JSONArray pl = q.optJSONArray("Playlist");
        int idx = q.optInt("PlayingItemIndex", 0);
        if (pl == null || idx < 0 || idx >= pl.length()) return;
        JSONObject e = pl.optJSONObject(idx);
        if (e == null) return;
        playlistItemId = e.optString("PlaylistItemId");
        String itemId = e.optString("ItemId");
        long start = q.optLong("StartPositionTicks", 0);
        Ui u = ui;
        if (u != null) main.post(() -> u.start(itemId, start));
    }

    private void command(JSONObject c) {
        String cmdName = c.optString("Command");
        long pos = c.optLong("PositionTicks", 0) / 10000;
        long delay = 0;
        try {
            long w = Instant.parse(c.optString("When")).toEpochMilli() - System.currentTimeMillis();
            delay = Math.max(0, Math.min(3000, w));
        } catch (Exception ignored) { }
        long d = delay;
        Cmd cm = cmd;
        if (cm != null) main.post(() -> cm.command(cmdName, pos, d));
    }
}
