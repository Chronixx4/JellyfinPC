package de.marvin.jellyfin.pc;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.UUID;
import java.util.prefs.Preferences;

/** Kleiner Jellyfin-Client (REST). Alle Aufrufe blockieren und gehoeren in einen Hintergrund-Thread. */
final class Jf {
    static final String DEFAULT_SERVER = "http://192.168.178.80:8097"; // anpassen
    private static Jf instance;

    private final Preferences prefs;
    String server, token, userId, userName;
    private final String deviceId;

    static synchronized Jf get() {
        if (instance == null) instance = new Jf();
        return instance;
    }

    private Jf() {
        prefs = Preferences.userRoot().node("de/marvin/jellyfin/pc");
        server = prefs.get("server", DEFAULT_SERVER);
        token = prefs.get("token", null);
        userId = prefs.get("userId", null);
        userName = prefs.get("userName", "");
        String d = prefs.get("deviceId", null);
        if (d == null) {
            d = UUID.randomUUID().toString();
            prefs.put("deviceId", d);
        }
        deviceId = d;
    }

    boolean loggedIn() {
        return token != null && userId != null;
    }

    void setServer(String s) {
        s = s.trim();
        if (!s.startsWith("http")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        server = s;
        prefs.put("server", s);
    }

    void logout() {
        token = null;
        userId = null;
        prefs.remove("token"); prefs.remove("userId");
    }

    private void saveLogin(JSONObject auth) throws Exception {
        token = auth.getString("AccessToken");
        JSONObject u = auth.getJSONObject("User");
        userId = u.getString("Id");
        userName = u.optString("Name", "");
        prefs.put("token", token); prefs.put("userId", userId); prefs.put("userName", userName);
        saveProfile();
    }

    // ------------------------------------------------------------ Profile (mehrere Benutzer)

    static final class Profile {
        String name, userId, token, server, pinHash = "";
    }

    java.util.List<Profile> profiles() {
        java.util.List<Profile> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.get("profiles", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Profile p = new Profile();
                p.name = o.optString("name");
                p.userId = o.optString("userId");
                p.token = o.optString("token");
                p.server = o.optString("server");
                p.pinHash = o.optString("pin", "");
                out.add(p);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void storeProfiles(java.util.List<Profile> l) {
        JSONArray a = new JSONArray();
        for (Profile p : l) {
            a.put(new JSONObject().put("name", p.name).put("userId", p.userId).put("token", p.token)
                    .put("server", p.server).put("pin", p.pinHash));
        }
        prefs.put("profiles", a.toString());
    }

    /** Aktuellen Login als Profil merken (neu oder aktualisiert). */
    void saveProfile() {
        if (userId == null || token == null) return;
        java.util.List<Profile> l = profiles();
        Profile cur = null;
        for (Profile p : l) if (p.userId.equals(userId) && p.server.equals(server)) cur = p;
        if (cur == null) { cur = new Profile(); l.add(cur); }
        cur.name = userName; cur.userId = userId; cur.token = token; cur.server = server;
        storeProfiles(l);
    }

    Profile currentProfile() {
        for (Profile p : profiles()) if (p.userId.equals(userId) && p.server.equals(server)) return p;
        return null;
    }

    void useProfile(Profile p) {
        server = p.server; token = p.token; userId = p.userId; userName = p.name;
        prefs.put("server", server); prefs.put("token", token); prefs.put("userId", userId); prefs.put("userName", userName);
    }

    void removeProfile(Profile p) {
        java.util.List<Profile> l = profiles();
        l.removeIf(x -> x.userId.equals(p.userId) && x.server.equals(p.server));
        storeProfiles(l);
    }

    private static String hashPin(String userId, String pin) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(("jf-pc:" + userId + ":" + pin).getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { return ""; }
    }

    /** PIN setzen; leer oder null entfernt sie. */
    void setPin(Profile p, String pin) {
        java.util.List<Profile> l = profiles();
        for (Profile x : l) if (x.userId.equals(p.userId) && x.server.equals(p.server))
            x.pinHash = (pin == null || pin.isEmpty()) ? "" : hashPin(x.userId, pin);
        storeProfiles(l);
    }

    boolean checkPin(Profile p, String pin) {
        return p.pinHash.isEmpty() || p.pinHash.equals(hashPin(p.userId, pin));
    }

    // ------------------------------------------------------------ HTTP

    private String authHeader() {
        return "MediaBrowser Client=\"Jellyfin PC\", Device=\"Windows PC\", DeviceId=\"" + deviceId
                + "\", Version=\"1.0\"" + (token != null ? ", Token=\"" + token + "\"" : "");
    }

    private String request(String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", authHeader());
        c.setRequestProperty("Accept", "application/json");
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                OutputStream o = c.getOutputStream();
                o.write(body.getBytes("UTF-8"));
                o.close();
            }
            int code = c.getResponseCode();
            if (code == 401) throw new AuthException();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (code >= 400) throw new Exception("HTTP " + code);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** SyncPlay: POST an /SyncPlay/<aktion>. */
    void syncPost(String action, JSONObject body) throws Exception {
        request("POST", "/SyncPlay/" + action, body == null ? "" : body.toString());
    }

    org.json.JSONArray syncList() throws Exception {
        return new org.json.JSONArray(request("GET", "/SyncPlay/List", null));
    }

    String socketUrl() {
        return server.replaceFirst("^http", "ws") + "/socket?api_key=" + token + "&deviceId=" + deviceId;
    }

    /** Meldet beim Server, dass dieser Client ferngesteuert werden kann (Handy-App sieht ihn dann als Ziel). */
    void registerCapabilities() throws Exception {
        JSONObject o = new JSONObject();
        o.put("PlayableMediaTypes", new org.json.JSONArray().put("Video").put("Audio"));
        o.put("SupportedCommands", new org.json.JSONArray().put("VolumeUp").put("VolumeDown").put("Mute").put("Unmute")
                .put("ToggleMute").put("SetVolume").put("DisplayMessage"));
        o.put("SupportsMediaControl", true);
        o.put("SupportsPersistentIdentifier", true);
        request("POST", "/Sessions/Capabilities/Full", o.toString());
    }

    volatile int volumeLevel = 80;
    volatile boolean isMuted;

    static final class AuthException extends Exception {}

    private JSONObject getJson(String path) throws Exception {
        return new JSONObject(request("GET", path, null));
    }

    // ------------------------------------------------------------ Login

    /** Startet Quick Connect. Liefert {Code, Secret}. */
    String[] quickConnectStart() throws Exception {
        JSONObject o = new JSONObject(request("POST", "/QuickConnect/Initiate", ""));
        return new String[]{o.getString("Code"), o.getString("Secret")};
    }

    /** true, sobald der Code im Jellyfin-Konto bestaetigt wurde (dann ist man eingeloggt). */
    boolean quickConnectPoll(String secret) throws Exception {
        JSONObject o = getJson("/QuickConnect/Connect?secret=" + secret);
        if (!o.optBoolean("Authenticated", false)) return false;
        JSONObject a = new JSONObject(request("POST", "/Users/AuthenticateWithQuickConnect",
                new JSONObject().put("Secret", secret).toString()));
        saveLogin(a);
        return true;
    }

    void loginPassword(String user, String pw) throws Exception {
        JSONObject a = new JSONObject(request("POST", "/Users/AuthenticateByName",
                new JSONObject().put("Username", user).put("Pw", pw).toString()));
        saveLogin(a);
    }

    // ------------------------------------------------------------ Daten

    private static final String FIELDS = "Overview,Genres,PrimaryImageAspectRatio,ParentBackdropItemId,"
            + "SeriesPrimaryImageTag,ParentThumbImageTag,MediaStreams,DateCreated,Path";
    private static final String DETAIL_FIELDS = FIELDS + ",MediaSources,People,Chapters,Studios,Taglines";

    /** Gesamtzahl der Treffer der letzten Listen-Abfrage (fuer "256 Titel"). */
    volatile int lastTotal;
    /** Anzahl der vom Server gelieferten Eintraege der letzten Abfrage (vor dem Entfernen von Samples). */
    volatile int lastRaw;

    private ArrayList<JfItem> items(JSONObject o) {
        ArrayList<JfItem> out = new ArrayList<>();
        lastTotal = o.optInt("TotalRecordCount", 0);
        JSONArray a = o.optJSONArray("Items");
        lastRaw = a == null ? 0 : a.length();
        for (int i = 0; a != null && i < a.length(); i++) {
            JfItem it = JfItem.from(a.optJSONObject(i));
            if (!it.isSample() && !"Folder".equals(it.type)) out.add(it); // lose Ordner nie anzeigen
        }
        return out;
    }

    ArrayList<JfItem> resume() throws Exception {
        return items(getJson("/Users/" + userId + "/Items/Resume?Limit=16&MediaTypes=Video&Fields=" + FIELDS
                + "&EnableImageTypes=Primary,Backdrop,Thumb"));
    }

    ArrayList<JfItem> nextUp() throws Exception {
        return items(getJson("/Shows/NextUp?UserId=" + userId + "&Limit=16&Fields=" + FIELDS
                + "&EnableImageTypes=Primary,Backdrop,Thumb"));
    }

    ArrayList<JfItem> views() throws Exception {
        return items(getJson("/UserViews?userId=" + userId));
    }

    ArrayList<JfItem> latest(String parentId) throws Exception {
        JSONArray a = new JSONArray(request("GET", "/Items/Latest?UserId=" + userId + "&ParentId=" + parentId
                + "&Limit=20&IncludeItemTypes=Movie,Series,Episode&Fields=" + FIELDS, null));
        ArrayList<JfItem> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) { JfItem it = JfItem.from(a.getJSONObject(i)); if (!it.isSample()) out.add(it); }
        return out;
    }

    ArrayList<JfItem> children(String parentId, int start, int limit, String sortBy, String order) throws Exception {
        return children(parentId, start, limit, sortBy, order, "", null);
    }

    /** Wie children, mit Filter (z. B. IsUnplayed, IsFavorite) und optionalem Genre. */
    ArrayList<JfItem> children(String parentId, int start, int limit, String sortBy, String order, String filters,
                               String genre) throws Exception {
        return children(parentId, start, limit, sortBy, order, filters, genre, null);
    }

    /**
     * Inhalt einer Bibliothek. Mit types (z. B. "Movie") wird rekursiv gesucht: so erscheinen nur echte Filme/Serien,
     * keine Ordner und keine losen Dateien wie "Sample" oder "Proof" neben dem Film.
     */
    ArrayList<JfItem> children(String parentId, int start, int limit, String sortBy, String order, String filters,
                               String genre, String types) throws Exception {
        return items(getJson("/Items?UserId=" + userId + "&ParentId=" + parentId
                + (types != null ? "&Recursive=true&IncludeItemTypes=" + types : "")
                + "&SortBy=" + sortBy + "&SortOrder=" + order + "&StartIndex=" + start + "&Limit=" + limit
                + (filters != null && filters.length() > 0 ? "&Filters=" + filters : "")
                + (genre != null ? "&Genres=" + java.net.URLEncoder.encode(genre, "UTF-8") : "")
                + "&Fields=" + FIELDS));
    }

    /** Zufaelliger abspielbarer Titel (Film/Folge) einer Bibliothek, z. B. fuer die Vorschau-Szenen. */
    ArrayList<JfItem> randomPlayable(String parentId, int limit) throws Exception {
        ArrayList<JfItem> all = items(getJson("/Items?UserId=" + userId + "&ParentId=" + parentId
                + "&Recursive=true&IncludeItemTypes=Movie,Episode&SortBy=Random&Limit=" + limit + "&Fields=" + FIELDS));
        ArrayList<JfItem> out = new ArrayList<>();
        for (JfItem i : all) if (i.runTicks > 0) out.add(i);
        return out;
    }

    /** Lokale Trailer eines Titels (Dateien auf dem Server), sonst leer. */
    ArrayList<JfItem> localTrailers(String id) {
        try {
            JSONArray a = new JSONArray(request("GET", "/Users/" + userId + "/Items/" + id + "/LocalTrailers", null));
            ArrayList<JfItem> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(JfItem.from(a.getJSONObject(i)));
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Ein zufaelliger Film (ersatzweise Serie) aus allen Bibliotheken. */
    JfItem randomMovie() throws Exception {
        for (String types : new String[]{"Movie", "Series"}) {
            ArrayList<JfItem> l = items(getJson("/Items?UserId=" + userId + "&Recursive=true&IncludeItemTypes=" + types
                    + "&SortBy=Random&Limit=1&Fields=" + FIELDS));
            if (!l.isEmpty()) return l.get(0);
        }
        return null;
    }

    /** Genres einer Bibliothek. */
    ArrayList<String> genres(String parentId) throws Exception {
        JSONObject o = getJson("/Genres?UserId=" + userId + "&ParentId=" + parentId + "&SortBy=SortName");
        JSONArray a = o.optJSONArray("Items");
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            String n = a.getJSONObject(i).optString("Name", "");
            if (n.length() > 0) out.add(n);
        }
        return out;
    }

    /**
     * Ende des Vorspanns in Ticks oder 0. Erst Media-Segmente des Servers (Jellyfin 10.10+, z. B. Intro Skipper),
     * sonst Kapitel mit Namen wie "Intro", "Opening" oder "OP".
     */
    long introEnd(JfItem it) {
        if (it == null) return 0;
        try {
            JSONObject o = getJson("/MediaSegments/" + it.id + "?includeSegmentTypes=Intro");
            JSONArray a = o.optJSONArray("Items");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject sg = a.getJSONObject(i);
                if ("Intro".equals(sg.optString("Type")) && sg.optLong("EndTicks", 0) > 0) return sg.optLong("EndTicks");
            }
        } catch (Exception ignored) {
            // Server ohne Segmente: auf Kapitel zurueckfallen
        }
        for (int i = 0; i + 1 < it.chapters.size(); i++) {
            String n = it.chapters.get(i).name.toLowerCase(java.util.Locale.ROOT).trim();
            if (n.contains("intro") || n.contains("opening") || n.equals("op") || n.contains("vorspann")
                    || n.startsWith("op ") || n.contains("title sequence")) {
                long end = it.chapters.get(i + 1).start;
                if (end > 0 && end < 5 * 60 * 10000000L) return end;
            }
        }
        return 0;
    }

    /** Abspann {Start, Ende} in Ticks oder null: Media-Segment "Outro", sonst Kapitel wie "Credits". */
    long[] outro(JfItem it) {
        if (it == null) return null;
        try {
            JSONObject o = getJson("/MediaSegments/" + it.id + "?includeSegmentTypes=Outro");
            JSONArray a = o.optJSONArray("Items");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject sg = a.getJSONObject(i);
                if ("Outro".equals(sg.optString("Type")) && sg.optLong("EndTicks", 0) > sg.optLong("StartTicks", 0)) {
                    return new long[]{sg.optLong("StartTicks"), sg.optLong("EndTicks")};
                }
            }
        } catch (Exception ignored) {
            // Server ohne Segmente
        }
        for (int i = 0; i < it.chapters.size(); i++) {
            String n = it.chapters.get(i).name.toLowerCase(java.util.Locale.ROOT).trim();
            if (n.contains("credit") || n.contains("outro") || n.contains("abspann") || n.equals("ed")
                    || n.contains("ending")) {
                long start = it.chapters.get(i).start;
                long end = i + 1 < it.chapters.size() ? it.chapters.get(i + 1).start : it.runTicks;
                if (it.runTicks > 0 && start > it.runTicks / 2 && end > start) return new long[]{start, end};
            }
        }
        return null;
    }

    /** Sammlungen (BoxSet) oder Wiedergabelisten (Playlist). */
    ArrayList<JfItem> collections(String type) throws Exception {
        ArrayList<JfItem> l = items(getJson("/Items?UserId=" + userId + "&Recursive=true&IncludeItemTypes=" + type
                + "&SortBy=SortName&Limit=40&Fields=" + FIELDS + ",ChildCount&EnableImageTypes=Primary,Backdrop,Thumb"));
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).childCount == 0) l.remove(i); // leere Listen ausblenden
        return l;
    }

    ArrayList<JfItem> playlistItems(String id, int start, int limit) throws Exception {
        return items(getJson("/Playlists/" + id + "/Items?UserId=" + userId + "&StartIndex=" + start + "&Limit="
                + limit + "&Fields=" + FIELDS));
    }

    String trickplayUrl(JfItem it, int sheet) {
        return server + "/Videos/" + it.id + "/Trickplay/" + it.tpWidth + "/" + sheet + ".jpg?MediaSourceId="
                + (it.tpSource != null ? it.tpSource : it.id) + "&api_key=" + token;
    }

    ArrayList<JfItem> byPerson(String personId, int start, int limit) throws Exception {
        return items(getJson("/Items?UserId=" + userId + "&PersonIds=" + personId
                + "&Recursive=true&IncludeItemTypes=Movie,Series&SortBy=SortName&StartIndex=" + start
                + "&Limit=" + limit + "&Fields=" + FIELDS));
    }

    /** Zufaellige Filme/Serien mit Hintergrundbild (fuer den Bildschirmschoner). */
    ArrayList<JfItem> randomBackdrops(int n) throws Exception {
        return items(getJson("/Items?UserId=" + userId + "&Recursive=true&IncludeItemTypes=Movie,Series"
                + "&ImageTypes=Backdrop&SortBy=Random&Limit=" + n + "&Fields=" + FIELDS
                + "&EnableImageTypes=Backdrop"));
    }

    ArrayList<JfItem> search(String q) throws Exception {
        return items(getJson("/Items?UserId=" + userId + "&SearchTerm=" + java.net.URLEncoder.encode(q, "UTF-8")
                + "&Recursive=true&IncludeItemTypes=Movie,Series&Limit=48&Fields=" + FIELDS));
    }

    /** Gespeicherte Position loeschen: der Titel verschwindet aus "Weiter schauen". */
    void clearProgress(String id) throws Exception {
        request("POST", "/Users/" + userId + "/Items/" + id + "/UserData", "{\"PlaybackPositionTicks\":0}");
    }

    /** Alle gesehenen Filme und Folgen (fuer die Statistik). */
    ArrayList<JfItem> playedItems() throws Exception {
        return items(getJson("/Items?UserId=" + userId + "&Recursive=true&Filters=IsPlayed&IncludeItemTypes=Movie,Episode"
                + "&SortBy=DatePlayed&SortOrder=Descending&Limit=3000&Fields=Genres,RunTimeTicks"));
    }

    // ---- Wiedergabelisten und Sammlungen

    ArrayList<JfItem> playlists() throws Exception {
        return items(getJson("/Items?UserId=" + userId + "&Recursive=true&IncludeItemTypes=Playlist&SortBy=SortName&Fields=ChildCount&Limit=200"));
    }

    ArrayList<JfItem> boxsets() throws Exception {
        return items(getJson("/Items?UserId=" + userId + "&Recursive=true&IncludeItemTypes=BoxSet&SortBy=SortName&Fields=ChildCount&Limit=200"));
    }

    void createPlaylist(String name, String firstId) throws Exception {
        JSONObject b = new JSONObject().put("Name", name).put("UserId", userId).put("MediaType", "Video");
        if (firstId != null) b.put("Ids", new JSONArray().put(firstId));
        request("POST", "/Playlists", b.toString());
    }

    void addToPlaylist(String playlistId, String itemId) throws Exception {
        request("POST", "/Playlists/" + playlistId + "/Items?UserId=" + userId + "&Ids=" + itemId, "");
    }

    void removeFromPlaylist(String playlistId, String entryId) throws Exception {
        request("DELETE", "/Playlists/" + playlistId + "/Items?EntryIds=" + entryId, "");
    }

    void createBoxset(String name, String firstId) throws Exception {
        request("POST", "/Collections?Name=" + java.net.URLEncoder.encode(name, "UTF-8")
                + (firstId != null ? "&Ids=" + firstId : ""), "");
    }

    void addToBoxset(String setId, String itemId) throws Exception {
        request("POST", "/Collections/" + setId + "/Items?Ids=" + itemId, "");
    }

    void removeFromBoxset(String setId, String itemId) throws Exception {
        request("DELETE", "/Collections/" + setId + "/Items?Ids=" + itemId, "");
    }

    void deleteItem(String id) throws Exception {
        request("DELETE", "/Items/" + id, "");
    }

    void setFavorite(String id, boolean on) throws Exception {
        request(on ? "POST" : "DELETE", "/Users/" + userId + "/FavoriteItems/" + id, "");
    }

    void setPlayed(String id, boolean on) throws Exception {
        request(on ? "POST" : "DELETE", "/Users/" + userId + "/PlayedItems/" + id, "");
    }

    /** Merkliste: alle Favoriten. */
    ArrayList<JfItem> favorites() throws Exception {
        return items(getJson("/Items?UserId=" + userId + "&Recursive=true&Filters=IsFavorite"
                + "&IncludeItemTypes=Movie,Series,Episode&SortBy=SortName&Limit=200&Fields=" + FIELDS));
    }

    /** Kurze Trefferliste fuer die Such-Vorschlaege. */
    ArrayList<JfItem> suggest(String q) throws Exception {
        return items(getJson("/Items?UserId=" + userId + "&SearchTerm=" + java.net.URLEncoder.encode(q, "UTF-8")
                + "&Recursive=true&IncludeItemTypes=Movie,Series&Limit=6&Fields=" + FIELDS));
    }

    JfItem item(String id) throws Exception {
        return JfItem.from(getJson("/Users/" + userId + "/Items/" + id + "?Fields=" + DETAIL_FIELDS));
    }

    ArrayList<JfItem> seasons(String seriesId) throws Exception {
        return items(getJson("/Shows/" + seriesId + "/Seasons?UserId=" + userId + "&Fields=" + FIELDS));
    }

    ArrayList<JfItem> episodes(String seriesId, String seasonId) throws Exception {
        return items(getJson("/Shows/" + seriesId + "/Episodes?UserId=" + userId + "&SeasonId=" + seasonId
                + "&Fields=" + FIELDS));
    }

    /** Die Folge direkt nach cur (auch ueber Staffelgrenzen) oder null. */
    JfItem nextEpisode(JfItem cur) throws Exception {
        ArrayList<JfItem> l = items(getJson("/Shows/" + cur.seriesId + "/Episodes?UserId=" + userId
                + "&StartItemId=" + cur.id + "&Limit=2&Fields=" + FIELDS));
        for (JfItem i : l) if (!i.id.equals(cur.id)) return i;
        return null;
    }

    /** Naechste ungesehene Folge einer Serie oder null. */
    JfItem nextUpFor(String seriesId) throws Exception {
        ArrayList<JfItem> l = items(getJson("/Shows/NextUp?UserId=" + userId + "&SeriesId=" + seriesId
                + "&Limit=1&Fields=" + FIELDS));
        return l.isEmpty() ? null : l.get(0);
    }

    // ------------------------------------------------------------ Bilder & Streams

    private String img(String id, String type, String tag, int maxW) {
        return server + "/Items/" + id + "/Images/" + type + "?maxWidth=" + maxW + "&quality=85"
                + (tag != null ? "&tag=" + tag : "");
    }

    String poster(JfItem i) {
        if (i.primaryTag != null) return img(i.id, "Primary", i.primaryTag, 360);
        if ("Episode".equals(i.type) && i.seriesId != null && i.seriesPrimaryTag != null)
            return img(i.seriesId, "Primary", i.seriesPrimaryTag, 360);
        return null;
    }

    /** 16:9-Bild (Weiter schauen, Folgen). */
    String landscape(JfItem i) {
        if ("Episode".equals(i.type) && i.primaryTag != null) return img(i.id, "Primary", i.primaryTag, 520);
        if (i.thumbTag != null) return img(i.id, "Thumb", i.thumbTag, 520);
        if (i.backdropTag != null) return img(i.id, "Backdrop/0", i.backdropTag, 520);
        if (i.backdropItemId != null) return img(i.backdropItemId, "Backdrop/0", null, 520);
        if (i.seriesId != null && i.seriesThumbTag != null) return img(i.seriesId, "Thumb", i.seriesThumbTag, 520);
        return poster(i);
    }

    /** Serien-Cover (auch fuer Folgen), sonst das eigene Poster. */
    String seriesPoster(JfItem i) {
        if ("Episode".equals(i.type) && i.seriesId != null && i.seriesPrimaryTag != null)
            return img(i.seriesId, "Primary", i.seriesPrimaryTag, 480);
        return i.primaryTag != null && !"Episode".equals(i.type) ? img(i.id, "Primary", i.primaryTag, 480) : null;
    }

    /** Aehnliche Titel. */
    ArrayList<JfItem> similar(String id) throws Exception {
        return items(getJson("/Items/" + id + "/Similar?userId=" + userId + "&limit=16&Fields=" + FIELDS));
    }

    String backdrop(JfItem i) {
        if (i.backdropTag != null) return img(i.id, "Backdrop/0", i.backdropTag, 1280);
        if (i.backdropItemId != null) return img(i.backdropItemId, "Backdrop/0", null, 1280);
        if (i.primaryTag != null && "Episode".equals(i.type)) return img(i.id, "Primary", i.primaryTag, 1280);
        return null;
    }

    String personImage(JfItem.Person p) {
        return p.tag == null ? null : img(p.id, "Primary", p.tag, 240);
    }

    String chapterImage(JfItem it, JfItem.Chapter c) {
        return c.tag == null ? null : server + "/Items/" + it.id + "/Images/Chapter/" + c.index + "?maxWidth=420&tag=" + c.tag;
    }

    String libraryImage(JfItem i) {
        return i.primaryTag != null ? img(i.id, "Primary", i.primaryTag, 520) : null;
    }

    String playUrl(JfItem i) {
        return server + "/Videos/" + i.id + "/master.m3u8?MediaSourceId=" + i.id
                + "&VideoCodec=h264&AudioCodec=aac&MaxAudioChannels=2&TranscodingMaxAudioChannels=2"
                + "&MaxWidth=1920&MaxHeight=1080&VideoBitrate=18000000&AudioBitrate=192000"
                + "&SegmentContainer=ts&MinSegments=2&BreakOnNonKeyFrames=true&TranscodeReasons=ContainerNotSupported"
                + "&DeviceId=" + deviceId + "&PlaySessionId=" + playSession(i.id) + "&api_key=" + token;
    }

    String directUrl(JfItem i) {
        return server + "/Videos/" + i.id + "/stream?static=true&MediaSourceId=" + i.id + "&api_key=" + token;
    }

    private String playSession(String id) {
        return UUID.nameUUIDFromBytes((id + deviceId).getBytes()).toString().replace("-", "");
    }

    // ------------------------------------------------------------ Wiedergabe-Meldungen

    void reportStart(JfItem i, long ticks) {
        if (i.externalUrl != null) return;
        report("/Sessions/Playing", i, ticks, false);
    }

    void reportProgress(JfItem i, long ticks, boolean paused) {
        if (i.externalUrl != null) return;
        report("/Sessions/Playing/Progress", i, ticks, paused);
    }

    void reportStop(JfItem i, long ticks) {
        if (i.externalUrl != null) return;
        report("/Sessions/Playing/Stopped", i, ticks, false);
    }

    private void report(String path, JfItem i, long ticks, boolean paused) {
        try {
            JSONObject o = new JSONObject();
            o.put("ItemId", i.id);
            o.put("MediaSourceId", i.id);
            o.put("PositionTicks", ticks);
            o.put("IsPaused", paused);
            o.put("CanSeek", true);
            o.put("VolumeLevel", volumeLevel);
            o.put("IsMuted", isMuted);
            o.put("PlayMethod", "DirectPlay");
            o.put("PlaySessionId", playSession(i.id));
            request("POST", path, o.toString());
        } catch (Throwable ignored) {}
    }
}
