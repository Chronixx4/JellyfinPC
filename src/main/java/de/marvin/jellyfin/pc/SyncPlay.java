package de.marvin.jellyfin.pc;

import javafx.application.Platform;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Gemeinsam schauen ueber Jellyfin SyncPlay (WebSocket fuer Meldungen, REST fuer Aktionen). */
final class SyncPlay {
    private final Jf jf;
    private final App app;
    private WebSocket ws;
    private final StringBuilder partial = new StringBuilder();
    private Thread keepAlive;

    volatile String groupId = "", groupName = "";
    volatile List<String> participants = new ArrayList<>();
    volatile String playlistItemId = "";
    volatile int version;          // zaehlt Aenderungen hoch (fuer die Oberflaeche)
    volatile String lastEvent = "";

    SyncPlay(Jf jf, App app) {
        this.jf = jf;
        this.app = app;
    }

    boolean inGroup() { return !groupId.isEmpty(); }

    private void changed() { version++; }

    // ------------------------------------------------------------ Verbindung

    synchronized void connect() {
        if (ws != null) return;
        try {
            ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(jf.socketUrl()), new WebSocket.Listener() {
                        @Override public void onOpen(WebSocket w) {
                            Log.i("SyncPlay: Verbindung offen");
                            w.request(1);
                        }
                        @Override public CompletionStage<?> onText(WebSocket w, CharSequence d, boolean last) {
                            partial.append(d);
                            if (last) {
                                String m = partial.toString();
                                partial.setLength(0);
                                try { handle(new JSONObject(m)); } catch (Exception e) { Log.e("SyncPlay Nachricht", e); }
                            }
                            w.request(1);
                            return null;
                        }
                        @Override public CompletionStage<?> onClose(WebSocket w, int c, String r) {
                            Log.i("SyncPlay: Verbindung zu (" + c + ")");
                            drop();
                            return null;
                        }
                        @Override public void onError(WebSocket w, Throwable t) {
                            Log.e("SyncPlay Verbindung", t);
                            drop();
                        }
                    }).join();
            keepAlive = new Thread(() -> {
                try {
                    while (true) {
                        Thread.sleep(20000);
                        WebSocket w = ws;
                        if (w == null) return;
                        w.sendText("{\"MessageType\":\"KeepAlive\"}", true);
                    }
                } catch (Exception ignored) { }
            }, "syncplay-keepalive");
            keepAlive.setDaemon(true);
            keepAlive.start();
        } catch (Exception e) {
            ws = null;
            throw new RuntimeException("SyncPlay-Verbindung fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    private synchronized void drop() {
        ws = null;
        if (inGroup()) { groupId = ""; groupName = ""; participants = new ArrayList<>(); }
        changed();
    }

    void shutdown() {
        WebSocket w = ws;
        ws = null;
        if (w != null) try { w.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ Aktionen

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

    void setQueue(JfItem it) throws Exception {
        long ticks = Math.max(0, it.resumeTicks);
        jf.syncPost("SetNewQueue", new JSONObject().put("PlayingQueue", new JSONArray().put(it.id))
                .put("PlayingItemPosition", 0).put("StartPositionTicks", ticks));
    }

    void pause() { post("Pause", null); }

    void unpause() { post("Unpause", null); }

    void seek(long ms) { post("Seek", new JSONObject().put("PositionTicks", ms * 10000)); }

    /** Meldet "bereit" (nach dem Laden oder Springen); der Server startet danach alle gemeinsam. */
    void ready(long posMs, boolean playing) {
        post("Ready", new JSONObject().put("When", Instant.now().toString()).put("PositionTicks", posMs * 10000)
                .put("IsPlaying", playing).put("PlaylistItemId", playlistItemId));
    }

    private void post(String action, JSONObject body) {
        if (!inGroup()) return;
        Thread t = new Thread(() -> {
            try { jf.syncPost(action, body); } catch (Exception e) { Log.e("SyncPlay " + action, e); }
        }, "syncplay-post");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ Eingehende Meldungen

    private void handle(JSONObject m) {
        String type = m.optString("MessageType");
        Object data = m.opt("Data");
        switch (type) {
            case "ForceKeepAlive" -> {
                WebSocket w = ws;
                if (w != null) w.sendText("{\"MessageType\":\"KeepAlive\"}", true);
            }
            case "SyncPlayGroupUpdate" -> {
                if (data instanceof JSONObject d) groupUpdate(d);
            }
            case "SyncPlayCommand" -> {
                if (data instanceof JSONObject d) command(d);
            }
            default -> { }
        }
    }

    private void groupUpdate(JSONObject d) {
        String t = d.optString("Type");
        Object inner = d.opt("Data");
        switch (t) {
            case "GroupJoined" -> {
                if (inner instanceof JSONObject g) {
                    groupId = g.optString("GroupId");
                    groupName = g.optString("GroupName");
                    List<String> ps = new ArrayList<>();
                    JSONArray a = g.optJSONArray("Participants");
                    if (a != null) for (int i = 0; i < a.length(); i++) ps.add(a.optString(i));
                    participants = ps;
                    lastEvent = "Gruppe „" + groupName + "“ beigetreten";
                    Platform.runLater(() -> app.toast("👥  " + lastEvent));
                }
            }
            case "UserJoined", "UserLeft" -> {
                String u = String.valueOf(inner);
                List<String> ps = new ArrayList<>(participants);
                if (t.equals("UserJoined")) { if (!ps.contains(u)) ps.add(u); }
                else ps.remove(u);
                participants = ps;
                Platform.runLater(() -> app.toast("👥  " + u + (t.equals("UserJoined") ? " ist beigetreten" : " hat die Gruppe verlassen")));
            }
            case "GroupLeft", "NotInGroup" -> {
                groupId = "";
                groupName = "";
                participants = new ArrayList<>();
            }
            case "GroupDoesNotExist" -> Platform.runLater(() -> app.toast("Diese Gruppe gibt es nicht mehr"));
            case "LibraryAccessDenied" -> Platform.runLater(() -> app.toast("Kein Zugriff auf diese Bibliothek"));
            case "PlayQueue" -> {
                if (inner instanceof JSONObject q) playQueue(q);
            }
            default -> { }
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
        Platform.runLater(() -> app.syncStart(itemId, start));
    }

    private void command(JSONObject c) {
        String cmd = c.optString("Command");
        long pos = c.optLong("PositionTicks", 0) / 10000;
        long delay = 0;
        try {
            long w = Instant.parse(c.optString("When")).toEpochMilli() - System.currentTimeMillis();
            delay = Math.max(0, Math.min(3000, w));
        } catch (Exception ignored) { }
        long d = delay;
        Platform.runLater(() -> app.syncCommand(cmd, pos, d));
    }
}
