package com.petrobot.hume;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sharpest available rendition of a story's picture.
 *
 * <p>Feeds publish whatever size their CMS happens to generate, and often list the smallest copy
 * first: ABC's first image is 384 px wide, the BBC's is 240, and The Guardian signs its image URLs
 * so only the sizes it lists work at all. A 384 px photo behind a 1080p dock is exactly the
 * "1998" look to avoid, so each story carries every image its feed offered (see {@link News}) and
 * this class ranks them, asks each CDN for a bigger rendition where it allows one (WordPress and
 * Nexstar's {@code ?w=}, TEGNA's {@code _640x360}, BBC's {@code /240/}, ABC's {@code _384}),
 * downloads the candidate and measures it. The first one at least as wide as the screen wins.
 *
 * <p>When a feed only has small pictures (a local station's 800 px upload, a signed Guardian
 * thumbnail), the story page's own share image ({@code og:image}) is tried, which is usually the
 * full-width copy the site shows when the article is shared. Only then is the widest available
 * picture used \u2014 a story is never dropped because of its photo.
 *
 * <p>Answers are remembered in {@code hd.json}, so a story is resolved once and costs nothing on
 * later refreshes. Everything here downloads (through {@link Suggestions}' image cache) and runs
 * off the UI thread.
 */
final class Art {
    /** A 1080p dock, with room to spare so a picture is never upscaled to fit. */
    static final int TARGET = 1280;
    /** Candidates tried per story before settling for the widest of them. */
    private static final int MAX_TRIES = 4;
    private static final int MEMO_MAX = 400;
    /** Bumped when the rendition rules change, so answers from an older set are not reused. */
    private static final String MEMO_FILE = "art_v2.json";
    private static final String UA = "Mozilla/5.0 (Linux; Android TV) HumeLauncher";

    /** Story image -> the rendition to show ("" = the feed's own picture is already the best). */
    private static final Map<String, String> memo = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > MEMO_MAX;
        }
    };
    private static boolean restored;

    private static final Pattern QUERY_SIZE = Pattern.compile("([?&])(w|width|wid)=(\\d+)");
    private static final Pattern WP_SIZE = Pattern.compile("-(\\d{2,5})x(\\d{2,5})(\\.(?:jpe?g|png|webp))(?=$|[?&#])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ABC_SIZE = Pattern.compile("(?i)(_(?:16x9|4x3)t?)_\\d+(\\.(?:jpe?g|png|webp))");
    private static final Pattern BBC_SIZE = Pattern.compile("(ichef\\.bbci\\.co\\.uk/(?:ace/standard|ace/cps|ace/branded_news|news|img)/)\\d+");
    private static final Pattern TEGNA_SIZE = Pattern.compile("(?i)(_|-)preview\\.jpg|_\\d+x\\d+\\.jpg");
    private static final Pattern NBC_FP = Pattern.compile("t_nbcnews-fp-\\d+-\\d+");
    private static final Pattern NPR_SIZE = Pattern.compile("/resize/(\\d+)x(\\d+)!");
    private static final Pattern META_TAG = Pattern.compile("(?is)<meta\\b[^>]*>");
    private static final Pattern ATTR = Pattern.compile("(?is)\\b%s\\s*=\\s*[\"']([^\"']*)[\"']");

    private Art() {
    }

    /**
     * The URL to draw a story's picture from: at least {@code minWidth} pixels wide when the
     * publisher has such a copy, otherwise the widest one it does have (never nothing).
     */
    static String best(Context c, Suggestions.Item it, int minWidth) {
        if (it == null || it.backdrop == null || it.backdrop.isEmpty()) return null;
        if (minWidth <= 0) minWidth = TARGET;
        String key = it.backdrop;
        synchronized (memo) {
            restore(c);
            String hit = memo.get(key);
            if (hit != null) return hit.isEmpty() ? key : hit;
        }
        List<String> tries = new ArrayList<>();
        for (String u : ranked(it)) {
            for (String r : renditions(u)) if (tries.size() < MAX_TRIES && !tries.contains(r)) tries.add(r);
        }
        String best = null;
        int widest = 0;
        for (String u : tries) {
            int w = width(c, u);
            if (w > widest) {
                widest = w;
                best = u;
            }
            if (w >= minWidth) break;
        }
        if (widest < minWidth) {  // the feed only had thumbnails: ask the story page for its share image
            for (String r : renditions(share(c, it.link))) {
                int w = width(c, r);
                if (w > widest) {
                    widest = w;
                    best = r;
                }
                if (w >= minWidth) break;
            }
        }
        String out = best == null ? "" : best;
        synchronized (memo) {
            memo.put(key, out);
        }
        save(c);
        if (widest > 0) Log.i("TvHome", "art: " + widest + " px  " + out);
        return best == null ? key : best;
    }

    /** Every image the story offered, biggest first (the feed picture, then its alternates). */
    private static List<String> ranked(Suggestions.Item it) {
        List<String> out = new ArrayList<>();
        if (it.backdrop != null && !it.backdrop.isEmpty()) out.add(it.backdrop);
        if (it.imgs != null) for (String u : it.imgs) if (u != null && !u.isEmpty() && !out.contains(u)) out.add(u);
        return out;
    }

    /**
     * Renditions of one image, most useful first, ending with the URL itself. A bigger copy is asked
     * for where the CDN allows it; NPR's resizer is asked for a smaller one, since it publishes
     * 6000 px originals that would cost megabytes on a TV. Signed URLs (The Guardian, Advance) are
     * left alone: changing the size breaks their signature.
     */
    static List<String> renditions(String url) {
        List<String> out = new ArrayList<>();
        if (url == null || url.isEmpty()) return out;
        if (!signed(url)) {
            Matcher q = QUERY_SIZE.matcher(url);
            if (q.find()) add(out, url.substring(0, q.start(3)) + "1600" + url.substring(q.end(3)));
            Matcher wp = WP_SIZE.matcher(url);
            if (wp.find()) add(out, url.substring(0, wp.start()) + wp.group(3) + url.substring(wp.end()));
            if (url.contains("abcnews.go.com") || url.contains("abcotvs.com")) {
                Matcher m = ABC_SIZE.matcher(url);
                if (m.find()) add(out, url.substring(0, m.start()) + m.group(1) + "_1600" + m.group(2) + url.substring(m.end()));
            }
            Matcher bbc = BBC_SIZE.matcher(url);
            if (bbc.find()) add(out, url.substring(0, bbc.end()) + "1536" + url.substring(bbc.end()));
            if (url.contains("tegna-media.com")) {
                Matcher t = TEGNA_SIZE.matcher(url);
                if (t.find()) {
                    String base = url.substring(0, t.start());
                    add(out, base + "_1920x1080.jpg");
                    add(out, base + "_1140x641.jpg");
                }
            }
            Matcher nbc = NBC_FP.matcher(url);
            if (nbc.find()) add(out, url.substring(0, nbc.start()) + "t_fit_2000w" + url.substring(nbc.end()));
            Matcher npr = NPR_SIZE.matcher(url);
            if (npr.find()) {
                int w = Integer.parseInt(npr.group(1)), h = Integer.parseInt(npr.group(2));
                int scaled = w > 1600 ? Math.max(1, Math.round(h * 1600f / w)) : h;
                add(out, url.substring(0, npr.start()) + "/resize/" + Math.min(w, 1600) + "x" + scaled + "!"
                        + url.substring(npr.end()));
            }
        }
        add(out, url);
        return out;
    }

    /** Width in pixels of a downloadable image, or 0 when it can't be used. Downloads to the cache. */
    static int width(Context c, String url) {
        File f = Suggestions.artFile(c, url, "orig");
        if (f == null) return 0;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        return o.outWidth;
    }

    /** The story page's own share image ({@code og:image}), which is usually the full-width copy. */
    static String share(Context c, String link) {
        if (link == null || !link.startsWith("http")) return null;
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(link).openConnection();
            con.setConnectTimeout(5000);
            con.setReadTimeout(6000);
            con.setRequestProperty("User-Agent", UA);
            con.setRequestProperty("Accept", "text/html");
            if (con.getResponseCode() != 200) return null;
            String html = new String(prefix(con.getInputStream(), 400000), StandardCharsets.UTF_8);
            String got = null;
            Matcher m = META_TAG.matcher(html);
            while (m.find() && got == null) {
                String tag = m.group();
                String name = attr(tag, "property");
                if (name == null) name = attr(tag, "name");
                if (name == null) continue;
                name = name.toLowerCase(Locale.ROOT).trim();
                if (!name.equals("og:image") && !name.equals("og:image:url") && !name.equals("twitter:image")) continue;
                String content = attr(tag, "content");
                if (content != null && !content.trim().isEmpty()) got = content.trim();
            }
            return absolute(link, got);
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void add(List<String> out, String url) {
        if (url != null && !url.isEmpty() && !out.contains(url)) out.add(url);
    }

    /** An image whose size is baked into a signature can only be fetched exactly as published. */
    private static boolean signed(String url) {
        return url.contains("?s=") || url.contains("&s=") || url.contains("auth=")
                || url.contains("Signature=") || url.contains("X-Amz-");
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile(String.format(ATTR.pattern(), name)).matcher(tag);
        return m.find() ? m.group(1).replace("&amp;", "&") : null;
    }

    /** Resolve protocol- and host-relative share images against the page they came from. */
    private static String absolute(String page, String url) {
        if (url == null || url.isEmpty()) return null;
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) {
            try {
                URL u = new URL(page);
                return u.getProtocol() + "://" + u.getHost() + url;
            } catch (Exception e) {
                return null;
            }
        }
        return url.startsWith("http") ? url : null;
    }

    private static byte[] prefix(InputStream in, int max) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (bos.size() < max && (n = is.read(buf)) > 0) bos.write(buf, 0, Math.min(n, max - bos.size()));
            return bos.toByteArray();
        }
    }

    private static void restore(Context c) {
        if (restored) return;
        restored = true;
        File f = new File(c.getFilesDir(), MEMO_FILE);
        if (!f.exists()) return;
        try {
            JSONObject o = new JSONObject(new String(Suggestions.readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                memo.put(k, o.optString(k, ""));
            }
        } catch (Exception e) {
            Log.w("TvHome", "art memo: " + e);
        }
    }

    private static void save(Context c) {
        JSONObject o = new JSONObject();
        try {
            synchronized (memo) {
                for (Map.Entry<String, String> e : memo.entrySet()) o.put(e.getKey(), e.getValue());
            }
            try (OutputStream os = new FileOutputStream(new File(c.getFilesDir(), MEMO_FILE))) {
                os.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w("TvHome", "art memo save: " + e);
        }
    }
}
