package com.petrobot.hume;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Continue Watching list. Android's own Watch Next list is reserved for system
 * launchers, so instead this listens to the media sessions apps publish while they play (title,
 * artwork, position) and remembers the latest per title. Requires notification-listener access:
 *   adb shell cmd notification allow_listener com.petrobot.hume/com.petrobot.hume.MediaWatchService
 */
public class MediaWatchService extends NotificationListenerService {

    MediaSessionManager msm;
    final Map<MediaSession.Token, MediaController.Callback> tracked = new HashMap<>();
    final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::onSessions;

    /** Live instance while the listener is connected (Control Center uses it for Do Not Disturb). */
    static MediaWatchService instance;

    @Override
    public void onListenerConnected() {
        instance = this;
        msm = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, MediaWatchService.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessions, me);
            onSessions(msm.getActiveSessions(me));
        } catch (SecurityException e) {
            android.util.Log.w("Hume", "media sessions: " + e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions);
        tracked.clear();
    }

    void onSessions(List<MediaController> list) {
        if (list == null) return;
        for (MediaController mc : list) {
            if (tracked.containsKey(mc.getSessionToken())) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata m) {
                    Store.record(MediaWatchService.this, mc.getPackageName(), m, mc.getPlaybackState());
                }

                @Override public void onPlaybackStateChanged(PlaybackState s) {
                    Store.record(MediaWatchService.this, mc.getPackageName(), mc.getMetadata(), s);
                }

                @Override public void onSessionDestroyed() {
                    tracked.remove(mc.getSessionToken());
                }
            };
            mc.registerCallback(cb);
            tracked.put(mc.getSessionToken(), cb);
            Store.record(this, mc.getPackageName(), mc.getMetadata(), mc.getPlaybackState());
        }
    }

    static boolean granted(Context c) {
        android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(new ComponentName(c, MediaWatchService.class));
    }

    /**
     * A media item's own id, when the session publishes one: a bare id, {@code vnd.youtube:ID}, or a
     * watch URL with {@code v=}. Anything else returns empty, which means "launch the app, don't
     * pretend to resume".
     */
    static String videoId(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("vnd.youtube:")) s = s.substring("vnd.youtube:".length());
        try {
            String v = android.net.Uri.parse(s).getQueryParameter("v");
            if (v != null && !v.isEmpty()) return v;
        } catch (Exception ignored) {
        }
        return s.matches("[A-Za-z0-9_-]{11}") ? s : "";
    }

    /** The Continue Watching list on disk (files/continue.json + artwork in files/cw/). */
    static final class Store {
        static final int MAX = 20;
        static final String K_HIDDEN = "cw_hidden";

        static File file(Context c) {
            return new File(c.getFilesDir(), Profiles.continueFile(Profiles.current(c).id));
        }

        static synchronized JSONArray read(Context c) {
            try {
                return new JSONArray(new String(Suggestions.readAll(new FileInputStream(file(c))), StandardCharsets.UTF_8));
            } catch (Exception e) {
                return new JSONArray();
            }
        }

        static synchronized void write(Context c, JSONArray a) {
            try (OutputStream os = new FileOutputStream(file(c))) {
                os.write(a.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }

        static String text(MediaMetadata m, String... keys) {
            for (String k : keys) {
                CharSequence s = m.getText(k);
                if (s != null && s.length() > 0) return s.toString();
            }
            return "";
        }

        static synchronized void record(Context c, String pkg, MediaMetadata m, PlaybackState s) {
            if (m == null || pkg == null || pkg.equals(c.getPackageName())) return;
            String title = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            if (title.isEmpty()) return;
            String sub = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM);
            long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long pos = -1;
            if (s != null) {
                pos = s.getPosition();
                if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
                }
            }
            String key = pkg + "|" + title;
            JSONArray old = read(c);
            JSONArray out = new JSONArray();
            JSONObject prev = null;
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o == null) continue;
                if (key.equals(o.optString("key"))) prev = o;
                else out.put(o);
            }
            // Finished (last 3%): drop it from the list.
            if (dur > 0 && pos > dur * 0.97) {
                write(c, out);
                return;
            }
            // What to reopen, not just what to call it: SmartTube and the YouTube apps publish the
            // video's own id, which is the only thing a deep link can resume from.
            String mid = videoId(text(m, MediaMetadata.METADATA_KEY_MEDIA_ID,
                    MediaMetadata.METADATA_KEY_MEDIA_URI));
            try {
                String art = prev != null ? prev.optString("art") : "";
                Bitmap bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                if (bmp != null && bmp.getWidth() >= 160) {
                    File dir = new File(c.getFilesDir(), "cw");
                    dir.mkdirs();
                    File f = new File(dir, Integer.toHexString(key.hashCode()) + ".jpg");
                    float sc = Math.min(1f, 960f / bmp.getWidth());
                    Bitmap small = sc < 1f ? Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * sc),
                            Math.round(bmp.getHeight() * sc), true) : bmp;
                    try (OutputStream os = new FileOutputStream(f)) {
                        small.compress(Bitmap.CompressFormat.JPEG, 88, os);
                    }
                    art = f.getAbsolutePath();
                } else if (art.isEmpty()) {
                    String uri = text(m, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
                    if (uri.startsWith("http")) art = uri;
                }
                JSONObject e = new JSONObject().put("key", key).put("pkg", pkg).put("title", title).put("sub", sub)
                        .put("art", art).put("pos", pos).put("dur", dur).put("mid", mid)
                        .put("ts", System.currentTimeMillis());
                JSONArray res = new JSONArray().put(e);
                for (int i = 0; i < out.length() && res.length() < MAX; i++) res.put(out.get(i));
                write(c, res);
            } catch (Exception ignored) {
            }
        }

        /** Drops entries last played more than 7 days ago (and their saved artwork). */
        static synchronized JSONArray pruneExpired(Context c) {
            JSONArray a = read(c), keep = new JSONArray();
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if (now - o.optLong("ts", now) > EXPIRE_MS) {
                    changed = true;
                    String art = o.optString("art");
                    if (art.startsWith("/")) new File(art).delete();
                } else {
                    keep.put(o);
                }
            }
            if (changed) write(c, keep);
            return keep;
        }

        static synchronized void remove(Context c, String key) {
            JSONArray old = read(c), out = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null && !key.equals(o.optString("key"))) out.put(o);
            }
            write(c, out);
        }

        static synchronized void clear(Context c) {
            write(c, new JSONArray());
        }

        /** Apps this profile has chosen not to see on the carousel. */
        static java.util.Set<String> hidden(Context c) {
            return new java.util.HashSet<>(Profiles.homePrefs(c).getStringSet(K_HIDDEN, new java.util.HashSet<>()));
        }

        static void setHidden(Context c, java.util.Set<String> pkgs) {
            Profiles.homePrefs(c).edit().putStringSet(K_HIDDEN, new java.util.HashSet<>(pkgs)).apply();
        }

        static void show(Context c, String pkg, boolean show) {
            java.util.Set<String> h = hidden(c);
            if (show) h.remove(pkg);
            else h.add(pkg);
            setHidden(c, h);
        }

        /**
         * The apps Continue Watching is for: video apps, not every package on the TV. That is the
         * streaming services and players the launcher already knows (it opens titles in them), plus
         * any app that has a title stored — so a service outside the known list still appears once it
         * has played something, and nothing that can have a card is missing. A file manager, a
         * downloader or a settings app has nothing to resume, so it is not offered. One entry per
         * app: SmartTube's two channels count as one.
         */
        static List<String> apps(Context c) {
            List<String> out = new ArrayList<>();
            JSONArray a = read(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String family = Suggestions.family(o.optString("pkg"));
                // An app that is gone has no cards left either, so it needs no row.
                if (!Suggestions.target(c, family).isEmpty()) add(out, family);
            }
            for (String pkg : launchable(c)) if (mediaApp(pkg)) add(out, Suggestions.family(pkg));
            out.sort((x, y) -> Suggestions.label(c, x).compareToIgnoreCase(Suggestions.label(c, y)));
            return out;
        }

        /**
         * Video apps the launcher knows by name, so they are offered before they have played
         * anything: YouTube, Plex, Jellyfin and Kodi. The streaming services come from Suggestions,
         * as do Stremio, Nuvio and Wholphin; SmartTube is handled separately.
         */
        static final String[] VIDEO_APPS = {
                "com.google.android.youtube.tv", "com.google.android.youtube",
                "com.google.android.youtube.tvunplugged", "com.plexapp.android",
                "org.jellyfin.androidtv", "org.xbmc.kodi",
                // Nuvio's older builds shipped under this package; still a Nuvio install.
                "com.nuvio.tv"};

        /** Whether this is an app Continue Watching can read a media session from. */
        static boolean mediaApp(String pkg) {
            if (Suggestions.isSmartTube(pkg)) return true;
            for (Suggestions.Provider p : Suggestions.PROVIDERS) if (p.pkg.equals(pkg)) return true;
            if (pkg.equals(Suggestions.STREMIO.pkg) || pkg.equals(Suggestions.NUVIO.pkg)
                    || pkg.equals(Suggestions.WHOLPHIN.pkg)) return true;
            for (String p : VIDEO_APPS) if (p.equals(pkg)) return true;
            return false;
        }

        static void add(List<String> list, String family) {
            if (!family.isEmpty() && !list.contains(family)) list.add(family);
        }

        /** Apps with an icon on this TV's launcher, in either launcher flavour. */
        static List<String> launchable(Context c) {
            List<String> out = new ArrayList<>();
            android.content.pm.PackageManager pm = c.getPackageManager();
            String[] cats = {android.content.Intent.CATEGORY_LEANBACK_LAUNCHER,
                    android.content.Intent.CATEGORY_LAUNCHER};
            for (String cat : cats) {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(cat);
                for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                    if (r.activityInfo == null) continue;
                    String pkg = r.activityInfo.packageName;
                    // Ourselves, and the TV's own settings/launcher app: they play nothing.
                    if (pkg.equals(c.getPackageName()) || pkg.equals("com.android.tv.settings")
                            || pkg.equals("com.android.settings")) continue;
                    if (!out.contains(pkg)) out.add(pkg);
                }
            }
            return out;
        }

        static String appLabel(Context c, String pkg) {
            android.content.pm.PackageManager pm = c.getPackageManager();
            try {
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception ignored) {
                // Android 11+ hides packages the manifest does not <query>; a launcher query is
                // allowed and covers every app on the grid.
            }
            android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(main, 0)) {
                if (r.activityInfo != null && pkg.equals(r.activityInfo.packageName)) {
                    CharSequence label = r.loadLabel(pm);
                    if (label != null && label.length() > 0) return label.toString();
                }
            }
            String tail = pkg.substring(pkg.lastIndexOf('.') + 1);
            return tail.isEmpty() ? pkg : Character.toUpperCase(tail.charAt(0)) + tail.substring(1);
        }

        /** Newest first, as carousel items (pkg set, progress 0..1 or -1). */
        /** Titles not played again for this long drop off Continue Watching. */
        static final long EXPIRE_MS = 7L * 24 * 60 * 60 * 1000;

        static List<Suggestions.Item> list(Context c) {
            JSONArray a = pruneExpired(c);
            java.util.Set<String> hidden = hidden(c);
            List<Suggestions.Item> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                // The channel that recorded it may have been replaced by the other one: open
                // whichever build is newest, and skip the title only when the app is gone.
                String pkg = Suggestions.target(c, o.optString("pkg"));
                if (pkg.isEmpty() || hidden.contains(Suggestions.family(pkg))) continue;
                Suggestions.Item it = new Suggestions.Item();
                it.id = o.optString("key").hashCode();
                it.type = "continue";
                it.pkg = pkg;
                it.key = o.optString("key");
                it.title = o.optString("title");
                it.overview = o.optString("sub");
                it.backdrop = o.optString("art");
                it.year = "";
                it.genres = "";
                it.mid = o.optString("mid");
                long pos = o.optLong("pos", -1), dur = o.optLong("dur", 0);
                it.progress = dur > 0 && pos >= 0 ? Math.min(1f, pos / (float) dur) : -1f;
                it.remainingMin = dur > 0 && pos >= 0 ? (int) Math.max(1, (dur - pos) / 60000) : -1;
                it.resumeSec = pos > 0 ? (int) (pos / 1000) : -1;
                out.add(it);
            }
            return out;
        }
    }
}
