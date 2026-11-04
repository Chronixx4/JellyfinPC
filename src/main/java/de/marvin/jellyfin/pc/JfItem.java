package de.marvin.jellyfin.pc;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/** Ein Eintrag aus Jellyfin (Film, Serie, Folge, Bibliothek ...). */
final class JfItem {
    static final class Person {
        String id, name, role, type, tag;
    }

    static final class Chapter {
        String name, tag;
        long start;
        int index;
    }

    String seasonId;
    JSONObject raw;
    String id, name, type, overview = "", officialRating = "", seriesName = "", seriesId, collectionType = "";
    String tagline = "", studios = "";
    int year, seasonNo, episodeNo;
    float rating;
    long runTicks, resumeTicks;
    boolean played;
    boolean favorite;
    String lastPlayed = "";
    String created = "";
    String playlistItemId = "";
    int unplayed;
    String primaryTag, thumbTag, backdropTag, backdropItemId, seriesPrimaryTag, seriesThumbTag, genres = "";
    float ratio = 0.667f;

    // Medien-Infos (nur wenn MediaStreams mitgeliefert wurden)
    String resBadge = "", codecBadge = "", hdrBadge = "", audioBadge = "", audioLangs = "", subLangs = "";
    String container = "", sizeText = "";
    int bitrateKbps;
    ArrayList<Person> people = new ArrayList<>();
    ArrayList<Chapter> chapters = new ArrayList<>();
    // Trickplay (Vorschaubilder beim Spulen): Breite/Hoehe eines Bildes, Kacheln pro Blatt, Abstand in ms
    int tpWidth, tpHeight, tpTileW, tpTileH, tpCount, tpInterval;
    String tpSource;
    int childCount = -1;

    static JfItem from(JSONObject o) {
        JfItem i = new JfItem();
        i.raw = o;
        i.id = o.optString("Id");
        i.playlistItemId = o.optString("PlaylistItemId", "");
        i.created = o.optString("DateCreated", "");
        i.name = o.optString("Name");
        i.type = o.optString("Type");
        i.overview = o.optString("Overview", "").replace('\n', ' ');
        i.officialRating = o.optString("OfficialRating", "");
        i.seriesName = o.optString("SeriesName", "");
        i.seriesId = o.optString("SeriesId", null);
        i.seasonId = o.optString("SeasonId", null);
        i.collectionType = o.optString("CollectionType", "");
        i.childCount = o.has("ChildCount") ? o.optInt("ChildCount") : -1;
        i.year = o.optInt("ProductionYear", 0);
        i.seasonNo = o.optInt("ParentIndexNumber", 0);
        i.episodeNo = o.optInt("IndexNumber", 0);
        i.rating = (float) o.optDouble("CommunityRating", 0);
        i.runTicks = o.optLong("RunTimeTicks", 0);
        i.ratio = (float) o.optDouble("PrimaryImageAspectRatio", 0.667);
        JSONArray tl = o.optJSONArray("Taglines");
        if (tl != null && tl.length() > 0) i.tagline = tl.optString(0, "");
        JSONArray st = o.optJSONArray("Studios");
        if (st != null) {
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < st.length() && k < 2; k++) {
                JSONObject s = st.optJSONObject(k);
                if (s == null) continue;
                if (b.length() > 0) b.append(", ");
                b.append(s.optString("Name"));
            }
            i.studios = b.toString();
        }
        JSONObject ud = o.optJSONObject("UserData");
        if (ud != null) {
            i.resumeTicks = ud.optLong("PlaybackPositionTicks", 0);
            i.played = ud.optBoolean("Played", false);
            i.favorite = ud.optBoolean("IsFavorite", false);
            i.lastPlayed = ud.optString("LastPlayedDate", "");
            i.unplayed = ud.optInt("UnplayedItemCount", 0);
        }
        JSONObject tags = o.optJSONObject("ImageTags");
        if (tags != null) {
            i.primaryTag = tags.optString("Primary", null);
            i.thumbTag = tags.optString("Thumb", null);
        }
        JSONArray bt = o.optJSONArray("BackdropImageTags");
        if (bt != null && bt.length() > 0) i.backdropTag = bt.optString(0, null);
        i.backdropItemId = o.optString("ParentBackdropItemId", null);
        i.seriesPrimaryTag = o.optString("SeriesPrimaryImageTag", null);
        i.seriesThumbTag = o.optString("ParentThumbImageTag", null);
        JSONArray g = o.optJSONArray("Genres");
        if (g != null) {
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < g.length() && k < 3; k++) {
                if (k > 0) b.append(" · ");
                b.append(g.optString(k));
            }
            i.genres = b.toString();
        }
        JSONArray ppl = o.optJSONArray("People");
        for (int k = 0; ppl != null && k < ppl.length() && k < 40; k++) {
            JSONObject p = ppl.optJSONObject(k);
            if (p == null) continue;
            Person pe = new Person();
            pe.id = p.optString("Id");
            pe.name = p.optString("Name");
            pe.role = p.optString("Role", "");
            pe.type = p.optString("Type", "");
            pe.tag = p.optString("PrimaryImageTag", null);
            i.people.add(pe);
        }
        JSONArray ch = o.optJSONArray("Chapters");
        for (int k = 0; ch != null && k < ch.length(); k++) {
            JSONObject c = ch.optJSONObject(k);
            if (c == null) continue;
            Chapter cp = new Chapter();
            cp.index = k;
            cp.name = c.optString("Name", "");
            cp.start = c.optLong("StartPositionTicks", 0);
            cp.tag = c.optString("ImageTag", null);
            i.chapters.add(cp);
        }
        JSONObject tpo = o.optJSONObject("Trickplay");
        if (tpo != null && tpo.length() > 0) {
            String srcKey = tpo.keys().next();
            JSONObject src = tpo.optJSONObject(srcKey);
            if (src != null) {
                int bestDiff = Integer.MAX_VALUE;
                java.util.Iterator<String> ks = src.keys();
                while (ks.hasNext()) {
                    JSONObject e = src.optJSONObject(ks.next());
                    if (e == null || e.optInt("Width") <= 0 || e.optInt("TileWidth") <= 0) continue;
                    int diff = Math.abs(e.optInt("Width") - 320);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        i.tpSource = srcKey;
                        i.tpWidth = e.optInt("Width");
                        i.tpHeight = e.optInt("Height");
                        i.tpTileW = e.optInt("TileWidth");
                        i.tpTileH = e.optInt("TileHeight");
                        i.tpCount = e.optInt("ThumbnailCount");
                        i.tpInterval = e.optInt("Interval", 10000);
                    }
                }
            }
        }
        i.name = cleanTitle(i.name, i);
        i.parseMedia(o);
        return i;
    }

    /** Dateinamen wie "72.Hours.2026.GERMAN.1080p.x264" werden zu "72 Hours" (Jahr wird uebernommen). */
    static String cleanTitle(String n, JfItem it) {
        if (n == null || n.indexOf(' ') >= 0) return n;
        int dots = 0;
        for (int k = 0; k < n.length(); k++) if (n.charAt(k) == '.' || n.charAt(k) == '_') dots++;
        if (dots < 2) return n;
        String[] parts = n.split("[._]+");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (p.matches("(19|20)\\d\\d")) {
                if (b.length() > 0) {
                    if (it.year == 0) it.year = Integer.parseInt(p);
                    break;
                }
            } else if (p.matches("(?i)(2160p|1080p|1080i|720p|480p|4k|uhd|bluray|bdrip|brrip|web-?dl|webrip|hdtv|dvdrip|"
                    + "remux|german|ger|ge|dl|dubbed|multi|ml|x264|x265|h264|h265|hevc|avc|dts(-hd)?|ac3|eac3|aac|"
                    + "truehd|atmos|hdr|hdr10|dv|10bit|proper|repack|extended|uncut|internal|nf|amzn|dsnp)")) {
                if (b.length() > 0) break;
                continue;
            }
            if (b.length() > 0) b.append(' ');
            b.append(p);
        }
        String r = b.toString().trim();
        return r.isEmpty() ? n : r;
    }

    private void parseMedia(JSONObject o) {
        JSONArray streams = o.optJSONArray("MediaStreams");
        JSONArray sources = o.optJSONArray("MediaSources");
        JSONObject src = sources != null && sources.length() > 0 ? sources.optJSONObject(0) : null;
        if (streams == null && src != null) streams = src.optJSONArray("MediaStreams");
        if (src != null) {
            container = src.optString("Container", "").toUpperCase(Locale.ROOT);
            long size = src.optLong("Size", 0);
            if (size > 0) sizeText = String.format(Locale.GERMANY, "%.1f GB", size / 1e9);
            bitrateKbps = src.optInt("Bitrate", 0) / 1000;
        }
        if (streams == null) return;
        JSONObject video = null, audio = null;
        StringBuilder al = new StringBuilder(), sl = new StringBuilder();
        java.util.HashSet<String> seenA = new java.util.HashSet<>(), seenS = new java.util.HashSet<>();
        for (int k = 0; k < streams.length(); k++) {
            JSONObject s = streams.optJSONObject(k);
            if (s == null) continue;
            String t = s.optString("Type");
            if ("Video".equals(t) && video == null && !s.optBoolean("IsExternal", false)
                    && !"Mjpeg".equalsIgnoreCase(s.optString("Codec")) && !"png".equalsIgnoreCase(s.optString("Codec")))
                video = s;
            if ("Audio".equals(t)) {
                if (audio == null || (s.optBoolean("IsDefault", false) && !audio.optBoolean("IsDefault", false))) audio = s;
                String l = lang(s.optString("Language", ""));
                if (l.length() > 0 && seenA.add(l)) { if (al.length() > 0) al.append(", "); al.append(l); }
            }
            if ("Subtitle".equals(t)) {
                String l = lang(s.optString("Language", ""));
                if (l.length() > 0 && seenS.add(l)) { if (sl.length() > 0) sl.append(", "); sl.append(l); }
            }
        }
        audioLangs = al.toString();
        subLangs = sl.toString();
        if (video != null) {
            int w = video.optInt("Width", 0), h = video.optInt("Height", 0);
            resBadge = w >= 3200 || h >= 2000 ? "4K" : w >= 1800 || h >= 1000 ? "1080p" : w >= 1200 || h >= 700 ? "720p"
                    : w > 0 ? "SD" : "";
            String c = video.optString("Codec", "").toLowerCase(Locale.ROOT);
            codecBadge = c.equals("hevc") ? "HEVC" : c.equals("h264") ? "H.264" : c.equals("av1") ? "AV1"
                    : c.equals("vp9") ? "VP9" : c.toUpperCase(Locale.ROOT);
            String r = video.optString("VideoRangeType", video.optString("VideoRange", ""));
            if (r.startsWith("DOVI")) hdrBadge = "Dolby Vision";
            else if (r.equals("HDR10Plus") || r.contains("HDR10Plus")) hdrBadge = "HDR10+";
            else if (r.startsWith("HDR10")) hdrBadge = "HDR10";
            else if (r.equals("HLG")) hdrBadge = "HLG";
        }
        if (audio != null) {
            String c = audio.optString("Codec", "").toLowerCase(Locale.ROOT);
            String prof = audio.optString("Profile", "") + " " + audio.optString("DisplayTitle", "");
            boolean atmos = prof.toLowerCase(Locale.ROOT).contains("atmos");
            String name;
            switch (c) {
                case "ac3": name = "Dolby Digital"; break;
                case "eac3": name = atmos ? "Dolby Atmos" : "Dolby Digital Plus"; break;
                case "truehd": name = atmos ? "Dolby Atmos" : "Dolby TrueHD"; break;
                case "dts": name = prof.contains("MA") ? "DTS-HD MA" : prof.contains("X") ? "DTS:X" : "DTS"; break;
                case "aac": name = "AAC"; break;
                case "flac": name = "FLAC"; break;
                case "opus": name = "Opus"; break;
                case "mp3": name = "MP3"; break;
                default: name = c.toUpperCase(Locale.ROOT);
            }
            int ch = audio.optInt("Channels", 0);
            String chs = ch >= 8 ? "7.1" : ch >= 6 ? "5.1" : ch == 2 ? "Stereo" : ch == 1 ? "Mono" : ch > 0 ? ch + "ch" : "";
            audioBadge = chs.isEmpty() ? name : name + " " + chs;
        }
    }

    private static String lang(String code) {
        switch (code.toLowerCase(Locale.ROOT)) {
            case "ger": case "deu": case "de": return "Deutsch";
            case "eng": case "en": return "Englisch";
            case "jpn": case "ja": return "Japanisch";
            case "fre": case "fra": case "fr": return "Französisch";
            case "spa": case "es": return "Spanisch";
            case "ita": case "it": return "Italienisch";
            case "rus": case "ru": return "Russisch";
            case "kor": case "ko": return "Koreanisch";
            case "chi": case "zho": case "zh": return "Chinesisch";
            case "por": case "pt": return "Portugiesisch";
            case "tur": case "tr": return "Türkisch";
            case "pol": case "pl": return "Polnisch";
            case "und": case "": return "";
            default: return code.toUpperCase(Locale.ROOT);
        }
    }

    boolean isPlayable() {
        return "Movie".equals(type) || "Episode".equals(type) || "Video".equals(type) || "MusicVideo".equals(type);
    }

    /** Titel fuer Folgen: "S1:E3 Name". */
    String title() {
        if ("Episode".equals(type)) return (seasonNo > 0 ? "S" + seasonNo + ":" : "") + "E" + episodeNo + "  " + name;
        return name;
    }

    float progress() {
        return runTicks > 0 && resumeTicks > 0 ? Math.min(1f, (float) resumeTicks / runTicks) : 0f;
    }

    String runtime() {
        if (runTicks <= 0) return "";
        long min = runTicks / 600000000L;
        return min >= 60 ? (min / 60) + " Std " + (min % 60) + " Min" : min + " Min";
    }

    /** Kurze Badges fuer Hero/Detail (Aufloesung, HDR, Ton). */
    ArrayList<String> badges() {
        ArrayList<String> b = new ArrayList<>();
        if (!resBadge.isEmpty()) b.add(resBadge);
        if (!hdrBadge.isEmpty()) b.add(hdrBadge);
        if (!codecBadge.isEmpty()) b.add(codecBadge);
        if (!audioBadge.isEmpty()) b.add(audioBadge);
        return b;
    }
}
