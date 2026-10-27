package com.petrobot.hume;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The top shelf's own library: Continue Watching from a Jellyfin server, opened in
 * <a href="https://github.com/damontecres/Wholphin">Wholphin</a>.
 *
 * <p>Jellyfin is one REST API and one token: the launcher signs in with the account's own username
 * and password ({@code /Users/AuthenticateByName}), keeps the token that comes back, and asks
 * {@code /Users/{id}/Items/Resume} which films and episodes the account has half-watched. Artwork
 * comes from the same server at carousel size, so the shelf shows the same pictures the app does.
 *
 * <p>Where the server is doesn't matter — a box in the room, or a seedbox on the other side of the
 * internet over https. A card is the show's own key art (its backdrop, with its logo) and starts
 * playing in Wholphin through its deep link ({@code wholphin://play} with {@code itemId}); it
 * needs no server details of its own here, because it is already signed in to the same one.
 */
final class Jellyfin {

    static final String PKG = "com.github.damontecres.wholphin";
    static final String ACTIVITY = PKG + ".MainActivity";
    static final String PREFS = "jellyfin";
    static final String K_URL = "url", K_TOKEN = "token", K_USER = "user", K_USER_NAME = "user_name";

    /** Resume rows the shelf asks for: enough to rotate through, few enough for one request. */
    static final int LIMIT = 12;

    private Jellyfin() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The server address as stored: absolute, no trailing slash, no web-UI path. */
    static String url(Context c) {
        return prefs(c).getString(K_URL, "");
    }

    static String token(Context c) {
        return prefs(c).getString(K_TOKEN, "");
    }

    static String user(Context c) {
        return prefs(c).getString(K_USER, "");
    }

    /** The account name, for the settings row ("leo on seedbox.example.com"). */
    static String account(Context c) {
        if (!connected(c)) return "";
        return prefs(c).getString(K_USER_NAME, "") + "  \u00b7  " + Uri.parse(url(c)).getHost();
    }

    static boolean connected(Context c) {
        return !url(c).isEmpty() && !token(c).isEmpty() && !user(c).isEmpty();
    }

    static void signOut(Context c) {
        prefs(c).edit().clear().apply();
        Suggestions.changed(c);
    }

    /**
     * {@code https://host/jellyfin} from whatever was typed: a bare host gets https, a pasted web
     * address loses its {@code /web} tail, and trailing slashes go. People copy the address out of
     * the browser, which is the address of the web player, not of the API.
     */
    static String clean(String typed) {
        String u = typed == null ? "" : typed.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        int web = u.indexOf("/web");
        if (web > 0) u = u.substring(0, web);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /**
     * Signs in and keeps the token. Returns null on success, or a sentence to show the user.
     * Blocking: call off the UI thread.
     */
    static String signIn(Context c, String typedUrl, String userName, String password) {
        String base = clean(typedUrl);
        if (base.isEmpty()) return "Enter the address of your Jellyfin server";
        if (userName == null || userName.trim().isEmpty()) return "Enter the Jellyfin username";
        try {
            JSONObject body = new JSONObject().put("Username", userName.trim()).put("Pw", password == null ? "" : password);
            JSONObject res = request(base + "/Users/AuthenticateByName", "POST", null, body.toString(), c);
            String token = res.optString("AccessToken");
            JSONObject user = res.optJSONObject("User");
            String id = user == null ? "" : user.optString("Id");
            if (token.isEmpty() || id.isEmpty()) return "The server signed in but sent no token";
            prefs(c).edit()
                    .putString(K_URL, base)
                    .putString(K_TOKEN, token)
                    .putString(K_USER, id)
                    .putString(K_USER_NAME, user.optString("Name", userName.trim()))
                    .apply();
            Suggestions.changed(c);
            return null;
        } catch (HttpStatus e) {
            if (e.code == 401 || e.code == 400) return "Wrong username or password";
            if (e.code == 404) return "No Jellyfin server answered at that address";
            return "The server answered " + e.code;
        } catch (Exception e) {
            android.util.Log.w("TvHome", "jellyfin sign-in failed: " + base + " \u2014 " + e);
            return "Can't reach the server: " + describe(e);
        }
    }

    /**
     * Continue Watching, one item per half-watched film or episode, newest first. Blocking: call
     * off the UI thread.
     */
    static List<Suggestions.Item> resume(Context c) throws Exception {
        if (!connected(c)) throw new IllegalStateException("Jellyfin isn't connected");
        String path = "/Users/" + user(c) + "/Items/Resume?MediaTypes=Video&Limit=" + LIMIT
                + "&EnableImageTypes=Primary,Backdrop&ImageTypeLimit=1"
                + "&Fields=PrimaryImageAspectRatio,ProductionYear,RunTimeTicks,Overview,Genres,CommunityRating";
        JSONObject res = request(url(c) + path, "GET", token(c), null, c);
        JSONArray items = res.optJSONArray("Items");
        Map<String, JSONObject> shows = shows(c, items);
        List<Suggestions.Item> out = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            Suggestions.Item it = item(items.getJSONObject(i), c, shows);
            if (it != null) out.add(it);
        }
        return out;
    }

    /**
     * The show behind each episode, in one request, for the card's artwork: an episode's own
     * picture is a 16:9 still from that one episode, while the show's backdrop and logo are the
     * key art a viewer recognises on a shelf.
     */
    private static Map<String, JSONObject> shows(Context c, JSONArray items) throws Exception {
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            String id = items.getJSONObject(i).optString("SeriesId");
            if (!id.isEmpty()) ids.add(id);
        }
        Map<String, JSONObject> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        try {
            JSONObject res = request(url(c) + "/Items?Ids=" + String.join(",", ids)
                    + "&Fields=ImageTags,BackdropImageTags", "GET", token(c), null, c);
            JSONArray found = res.optJSONArray("Items");
            for (int i = 0; found != null && i < found.length(); i++) {
                JSONObject show = found.getJSONObject(i);
                out.put(show.optString("Id"), show);
            }
        } catch (Exception e) {
            // Art is a nicety: a failed lookup falls back to the episode's own picture.
            android.util.Log.w("TvHome", "jellyfin show art: " + describe(e));
        }
        return out;
    }

    /** One Jellyfin item as a carousel card, or null when it has no picture to show. */
    private static Suggestions.Item item(JSONObject o, Context c, Map<String, JSONObject> shows) {
        String id = o.optString("Id"), type = o.optString("Type");
        if (id.isEmpty() || type.isEmpty()) return null;
        JSONObject show = shows.get(o.optString("SeriesId"));
        String backdrop = art(c, show, "Backdrop/0", 1920);
        if (backdrop.isEmpty()) backdrop = imageUrl(c, o, id, type);
        if (backdrop.isEmpty()) return null;

        Suggestions.Item it = new Suggestions.Item();
        it.id = id.hashCode();
        it.providerId = Suggestions.JELLYFIN_ID;
        it.type = type.equals("Movie") ? "movie" : "tv";
        it.jfId = id;
        it.jfType = type;
        String logo = art(c, show, "Logo", 800);
        if (logo.isEmpty() && show == null) logo = art(c, o, "Logo", 800);
        it.logo = logo.isEmpty() ? null : logo;  // the carousel draws the logo instead of the name
        it.backdrop = backdrop;
        it.overview = o.optString("Overview");
        int year = o.optInt("ProductionYear", 0);
        it.year = year > 0 ? String.valueOf(year) : "";
        it.rating = o.optDouble("CommunityRating", 0);

        String name = o.optString("Name");
        if (type.equals("Episode")) {
            // The series is what the viewer is watching; the episode is the detail line.
            String series = o.optString("SeriesName");
            it.title = series.isEmpty() ? name : series;
            int season = o.optInt("ParentIndexNumber", -1), episode = o.optInt("IndexNumber", -1);
            List<String> meta = new ArrayList<>();
            if (season >= 0 && episode >= 0) meta.add("S" + season + "E" + episode);
            if (!name.isEmpty()) meta.add(name);
            it.genres = String.join("  \u00b7  ", meta);
        } else {
            it.title = name;
            it.genres = firstGenres(o.optJSONArray("Genres"), 2);
        }

        JSONObject ud = o.optJSONObject("UserData");
        if (ud != null) {
            long runtime = o.optLong("RunTimeTicks", 0), position = ud.optLong("PlaybackPositionTicks", 0);
            // Position over runtime is the truth: PlayedPercentage is rounded and sometimes absent.
            // Jellyfin counts 100 ns ticks: 600000000 of them is a minute.
            if (runtime > 0 && position > 0) {
                it.progress = (float) Math.min(1, position / (double) runtime);
                it.remainingMin = (int) Math.max(1, (runtime - position) / 600000000L);
            } else {
                double pct = ud.optDouble("PlayedPercentage", 0);
                if (pct > 0) it.progress = (float) Math.min(1, pct / 100.0);
            }
        }
        return it;
    }

    /**
     * One image of an item, at carousel size, when it has that image; empty otherwise. The token
     * rides in the URL because the shelf's image fetcher is generic.
     */
    private static String art(Context c, JSONObject o, String kind, int width) {
        if (o == null) return "";
        String id = o.optString("Id");
        if (id.isEmpty()) return "";
        boolean has = kind.startsWith("Backdrop") ? hasTag(o, "BackdropImageTags")
                : o.optJSONObject("ImageTags") != null && o.optJSONObject("ImageTags").has(kind);
        if (!has) return "";
        return url(c) + "/Items/" + id + "/Images/" + kind + "?maxWidth=" + width + "&quality=90"
                + "&api_key=" + enc(token(c));
    }

    /**
     * The widest picture the item has: its own backdrop, then the series' backdrop (an episode has
     * none of its own), then its primary image — which for an episode is the 16:9 still.
     */
    private static String imageUrl(Context c, JSONObject o, String id, String type) {
        String token = token(c);
        if (hasTag(o, "BackdropImageTags")) return image(c, id, "Backdrop/0", token);
        String parent = o.optString("ParentBackdropItemId");
        if (!parent.isEmpty() && hasTag(o, "ParentBackdropImageTags")) return image(c, parent, "Backdrop/0", token);
        if (hasTag(o, "ImageTags")) return image(c, id, "Primary", token);
        return "";
    }

    /**
     * Whether the item has that image: backdrops come as a list, while {@code ImageTags} is a map
     * of image type to tag, so both shapes count.
     */
    private static boolean hasTag(JSONObject o, String key) {
        JSONArray list = o.optJSONArray(key);
        if (list != null) return list.length() > 0;
        JSONObject map = o.optJSONObject(key);
        return map != null && map.length() > 0;
    }

    /** A server image at carousel width. The token rides in the URL: the shelf's fetcher is generic. */
    private static String image(Context c, String id, String kind, String token) {
        return url(c) + "/Items/" + id + "/Images/" + kind + "?maxWidth=1920&quality=90"
                + "&api_key=" + enc(token);
    }

    static String firstGenres(JSONArray genres, int max) {
        List<String> names = new ArrayList<>();
        for (int i = 0; genres != null && i < genres.length() && names.size() < max; i++) {
            String g = genres.optString(i);
            if (!g.isEmpty()) names.add(g);
        }
        return String.join("  \u00b7  ", names);
    }

    /**
     * Starts the item in Wholphin: the player, not the item's page.
     *
     * <p>{@code wholphin://play?itemId=…} is the deep link that lands on the player. Two details
     * matter: the activity is named explicitly (the manifest's filter only matches a
     * {@code wholphin://} data URI, and the activity reads the id from there), and no action is
     * set — with {@code ACTION_VIEW}, or with an {@code itemType} extra, the app parses the intent
     * as "open this item's page" and the shelf's Resume turns back into browsing.
     *
     * <p>Wholphin resumes from the position the server has: a half-watched episode picks up where
     * the viewer left off, and an untouched one starts at the beginning.
     */
    static void open(Context c, Suggestions.Item it) {
        if (!Suggestions.installed(c, PKG)) {
            Suggestions.start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + PKG)));
            return;
        }
        Intent play = new Intent()
                .setClassName(PKG, ACTIVITY)
                .setData(Uri.parse("wholphin://play?itemId=" + it.jfId))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Suggestions.start(c, play)) return;
        // Older builds without the play link: the item's page is the next best thing.
        Intent page = new Intent(Intent.ACTION_VIEW)
                .setClassName(PKG, ACTIVITY)
                .putExtra("itemId", it.jfId)
                .putExtra("itemType", it.jfType)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Suggestions.start(c, page)) return;
        Intent launch = c.getPackageManager().getLeanbackLaunchIntentForPackage(PKG);
        if (launch == null) launch = c.getPackageManager().getLaunchIntentForPackage(PKG);
        if (launch != null) Suggestions.start(c, launch);
    }

    /**
     * Takes a half-watched title off Continue Watching: the saved position is cleared, which is
     * what Jellyfin's own "Remove from Continue Watching" does. The film or episode itself and its
     * watched history stay as they are. Blocking: call off the UI thread.
     */
    static void removeFromContinue(Context c, Suggestions.Item it) throws Exception {
        if (!connected(c) || it.jfId.isEmpty()) return;
        String body = new JSONObject().put("PlaybackPositionTicks", 0).toString();
        post(url(c) + "/Users/" + user(c) + "/Items/" + it.jfId + "/UserData", body, token(c), c);
    }

    /** A write that only needs to know it was accepted; the body, when there is one, is ignored. */
    private static void post(String url, String body, String token, Context c) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(6000);
        con.setReadTimeout(15000);
        con.setRequestMethod("POST");
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("X-Emby-Authorization", authHeader(c));
        if (token != null && !token.isEmpty()) con.setRequestProperty("X-Emby-Token", token);
        try {
            con.setDoOutput(true);
            try (OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = con.getResponseCode();
            if (code < 200 || code >= 300) throw new HttpStatus(code);
            Suggestions.readAll(con.getInputStream());
        } finally {
            con.disconnect();
        }
    }

    // ------------------------------------------------------------------ http

    /** A non-200 answer, kept apart from transport failures so the message can be specific. */
    private static final class HttpStatus extends Exception {
        final int code;

        HttpStatus(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    /**
     * One JSON call. Jellyfin wants the client named in {@code X-Emby-Authorization} on sign-in and
     * takes the token in {@code X-Emby-Token} afterwards.
     */
    private static JSONObject request(String url, String method, String token, String body, Context c) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(6000);
        con.setReadTimeout(15000);
        con.setRequestMethod(method);
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("X-Emby-Authorization", authHeader(c));
        if (token != null && !token.isEmpty()) con.setRequestProperty("X-Emby-Token", token);
        try {
            if (body != null) {
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = con.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = con.getResponseCode();
            if (code != 200) throw new HttpStatus(code);
            return new JSONObject(new String(Suggestions.readAll(con.getInputStream()), StandardCharsets.UTF_8));
        } finally {
            con.disconnect();
        }
    }

    /** A stable per-TV client identity, so the server lists the launcher as one device. */
    private static String authHeader(Context c) {
        String device = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        return "MediaBrowser Client=\"Hume\", Device=\"Android TV\", DeviceId=\"" + (device == null ? "hume" : device)
                + "\", Version=\"" + version(c) + "\"";
    }

    private static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** "Connection refused" and friends say nothing on a TV; the type is the honest summary. */
    private static String describe(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        return m.length() > 90 ? m.substring(0, 90) : m;
    }
}
