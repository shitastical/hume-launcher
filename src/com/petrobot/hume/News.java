package com.petrobot.hume;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * News for the top shelf from newsrooms' RSS feeds, which carry a large image per story
 * (free, no key): U.S. national, Local (the city from the weather location) and World stories
 * mixed together. Each story becomes a carousel item \u2014 headline, summary, image, section,
 * source and age (no button: stories are read on the shelf).
 *
 * <p>Images are taken from {@code media:content}/{@code media:thumbnail}/{@code enclosure}, or from
 * an {@code <img>} in the summary or the full body ({@code content:encoded}) \u2014 NPR and most
 * WordPress-based newsrooms only publish theirs in the body, so both are checked. A story with no
 * image is skipped, because the carousel is picture-driven.
 */
final class News {
    static final String K_SOURCES = "news_sources";
    static final String NATIONAL = "national", LOCAL = "local", WORLD = "world";
    static final String[] SECTIONS = {NATIONAL, LOCAL, WORLD};

    /** A newsroom and its national/world feeds (null = it has none for that section). */
    static final class Source {
        final String id, name, national, world;

        Source(String id, String name, String national, String world) {
            this.id = id;
            this.name = name;
            this.national = national;
            this.world = world;
        }
    }

    /** A local newsroom for one city. */
    static final class Local {
        final String name, url;

        Local(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    static final List<Source> SOURCES = new ArrayList<>();

    /** Local newsrooms per city, keyed by the slug of the city in the weather location. */
    static final Map<String, List<Local>> LOCAL_FEEDS = new HashMap<>();

    /** Statewide coverage, for Ohio cities that have no newsroom of their own. */
    private static final Local OHIO_STATE = new Local("Ohio Capital Journal",
            "https://ohiocapitaljournal.com/feed/");

    static {
        SOURCES.add(new Source("npr", "NPR",
                "https://feeds.npr.org/1001/rss.xml",
                "https://feeds.npr.org/1004/rss.xml"));
        SOURCES.add(new Source("abc", "ABC News",
                "https://abcnews.go.com/abcnews/topstories", null));
        SOURCES.add(new Source("nbc", "NBC News",
                "https://feeds.nbcnews.com/nbcnews/public/news", null));
        SOURCES.add(new Source("guardian", "The Guardian",
                "https://www.theguardian.com/us-news/rss",
                "https://www.theguardian.com/world/rss"));
        SOURCES.add(new Source("bbc", "BBC News",
                "https://feeds.bbci.co.uk/news/world/us_and_canada/rss.xml",
                "https://feeds.bbci.co.uk/news/world/rss.xml"));
        SOURCES.add(new Source("politico", "Politico",
                "https://rss.politico.com/politics-news.xml", null));
        SOURCES.add(new Source("hill", "The Hill",
                "https://thehill.com/news/feed/", null));

        // Ohio
        add("cleveland", "WKYC", "https://www.wkyc.com/feeds/syndication/rss/news");
        add("cleveland", "Fox 8", "https://fox8.com/feed/");
        add("cleveland", "cleveland.com", "https://www.cleveland.com/arc/outboundfeeds/rss/?outputType=xml");
        add("cleveland", "Signal Cleveland", "https://signalcleveland.org/feed/");
        add("columbus", "10TV", "https://www.10tv.com/feeds/syndication/rss/news");
        add("columbus", "NBC4", "https://www.nbc4i.com/feed/");
        add("dayton", "WDTN", "https://www.wdtn.com/feed/");
        add("toledo", "The Blade", "https://www.toledoblade.com/rss/");
        add("youngstown", "WKBN", "https://www.wkbn.com/feed/");
        add("youngstown", "WYTV", "https://www.wytv.com/feed/");
        // Ohio cities with no newsroom feed of their own fall back to statewide coverage
        for (String c : new String[]{"cincinnati", "akron", "canton", "hamilton", "lorain", "springfield",
                "lima", "mansfield", "elyria", "kettering", "parma", "mentor", "newark", "dublin",
                "middletown", "fairfield", "warren", "lakewood", "cuyahoga-falls", "eastlake"}) {
            add(c, OHIO_STATE.name, OHIO_STATE.url);
        }

        // Other metros, so the carousel follows you when the weather location changes
        add("los-angeles", "KTLA", "https://ktla.com/feed/");
        add("los-angeles", "ABC 7", "https://abc7.com/feed");
        add("chicago", "WGN", "https://wgntv.com/feed/");
        add("san-francisco", "KRON4", "https://kron4.com/feed/");
        add("denver", "KDVR", "https://kdvr.com/feed/");
        add("indianapolis", "FOX59", "https://fox59.com/feed/");
        add("nashville", "WKRN", "https://www.wkrn.com/feed/");
        add("st-louis", "FOX 2", "https://fox2now.com/feed/");
        add("minneapolis", "KSTP", "https://kstp.com/feed/");
        add("dallas", "WFAA", "https://www.wfaa.com/feeds/syndication/rss/news");
    }

    private static void add(String city, String name, String url) {
        List<Local> l = LOCAL_FEEDS.get(city);
        if (l == null) LOCAL_FEEDS.put(city, l = new ArrayList<>());
        l.add(new Local(name, url));
    }

    /** The local city for news: the weather location (from the internet address or chosen). */
    static String city(Context c) {
        return Weather.place(c);
    }

    /** City slug as the local feed table spells it. */
    private static String slug(String city) {
        String s = city.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z ]", "").replace(' ', '-');
        switch (s) {
            case "nyc":
            case "new-york-city":
                return "new-york";
            case "la":
            case "los-angeles-ca":
                return "los-angeles";
            case "sf":
            case "san-francisco-ca":
                return "san-francisco";
            case "saint-louis":
            case "st-louis-mo":
                return "st-louis";
            case "dc":
            case "washington-dc":
                return "washington";
            case "philly":
                return "philadelphia";
            default:
                return s;
        }
    }

    private News() {
    }

    static final String K_REFRESH = "news_refresh_min";
    static final int[] REFRESH_CHOICES = {15, 30, 60};

    /** Minutes between news updates (30 by default). */
    static int refreshMinutes(Context c) {
        return Suggestions.prefs(c).getInt(K_REFRESH, 30);
    }

    static Set<String> sources(Context c) {
        Set<String> def = new HashSet<>(Arrays.asList("npr", "abc", "nbc", "guardian", "bbc", "politico"));
        Set<String> known = new HashSet<>();
        for (Source s : SOURCES) known.add(s.id);
        Set<String> stored = new HashSet<>(Suggestions.prefs(c).getStringSet(K_SOURCES, def));
        stored.retainAll(known);  // drop newsrooms that no longer exist
        return stored.isEmpty() ? def : stored;
    }

    static void setSources(Context c, Set<String> ids) {
        SharedPreferences p = Suggestions.prefs(c);
        p.edit().putStringSet(K_SOURCES, ids).apply();
    }

    /** Latest stories: national, local and world mixed together, alternating between newsrooms. */
    static List<Suggestions.Item> fetch(Context c, int max) {
        List<List<Suggestions.Item>> lists = new ArrayList<>();
        Set<String> chosen = sources(c);
        String city = city(c);
        for (String sec : SECTIONS) {
            if (sec.equals(LOCAL)) {
                if (city.isEmpty()) continue;
                List<Local> locals = LOCAL_FEEDS.get(slug(city));
                if (locals == null) continue;
                for (Local l : locals) {
                    try {
                        // city news moves slower than national
                        List<Suggestions.Item> got = read(l.url, city + "  \u00b7  " + l.name, 7 * 86400000L);
                        if (!got.isEmpty()) lists.add(got);
                    } catch (Exception e) {
                        Log.w("TvHome", "local news " + l.url + ": " + e);
                    }
                }
                continue;
            }
            for (Source s : SOURCES) {
                if (!chosen.contains(s.id)) continue;
                String url = sec.equals(NATIONAL) ? s.national : s.world;
                if (url == null) continue;
                String label = (sec.equals(WORLD) ? "World" : "U.S.") + "  \u00b7  " + s.name;
                try {
                    List<Suggestions.Item> got = read(url, label, 2 * 86400000L);
                    if (!got.isEmpty()) lists.add(got);
                } catch (Exception e) {
                    Log.w("TvHome", "news feed " + url + ": " + e);
                }
            }
        }
        List<Suggestions.Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int round = 0; out.size() < max; round++) {
            boolean any = false;
            for (List<Suggestions.Item> l : lists) {
                if (round < l.size()) {
                    any = true;
                    Suggestions.Item it = l.get(round);
                    if (out.size() < max && seen.add(it.title.toLowerCase(Locale.ROOT))) out.add(it);
                }
            }
            if (!any) break;
        }
        return out;
    }

    private static List<Suggestions.Item> read(String url, String label, long maxAge) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(12000);
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) HumeLauncher");
        List<Suggestions.Item> out = new ArrayList<>();
        try (InputStream in = con.getInputStream()) {
            XmlPullParser x = XmlPullParserFactory.newInstance().newPullParser();
            x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            x.setInput(in, null);
            Suggestions.Item cur = null;
            String tag = null, rawDesc = "", rawBody = "";
            int seenItems = 0;
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT && out.size() < 30 && seenItems < 80; ev = x.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = x.getName();
                    if (tag.equals("item")) {
                        seenItems++;
                        cur = new Suggestions.Item();
                        cur.type = "news";
                        cur.title = cur.overview = cur.backdrop = cur.year = "";
                        cur.genres = label;
                        cur.logo = "";
                        rawDesc = "";
                        rawBody = "";
                    } else if (cur != null && (tag.equals("media:content") || tag.equals("media:thumbnail") || tag.equals("enclosure"))) {
                        String u = x.getAttributeValue(null, "url");
                        String type = x.getAttributeValue(null, "type");
                        if (u != null && (type == null || type.startsWith("image")) && cur.backdrop.isEmpty()) cur.backdrop = bigger(u);
                    }
                } else if (ev == XmlPullParser.TEXT && cur != null && tag != null) {
                    String t = x.getText();
                    switch (tag) {
                        case "title": cur.title += t; break;
                        case "link": cur.link += t.trim(); break;
                        case "description": cur.overview += t; rawDesc += t; break;
                        case "content:encoded": rawBody += t; break;
                        case "pubDate": cur.published = parseDate(t.trim()); break;
                        default: break;
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (x.getName().equals("item") && cur != null) {
                        cur.title = clean(cur.title);
                        cur.overview = clean(cur.overview);
                        if (cur.backdrop.isEmpty()) {  // many newsrooms only put the image in the body
                            String img = firstImage(rawDesc);
                            if (img == null) img = firstImage(rawBody);
                            if (img != null) cur.backdrop = bigger(img);
                        }
                        boolean fresh = cur.published == 0 || System.currentTimeMillis() - cur.published < maxAge;
                        if (!cur.title.isEmpty() && !cur.backdrop.isEmpty() && fresh) out.add(cur);
                        cur = null;
                    }
                    tag = null;
                }
            }
        } finally {
            con.disconnect();
        }
        out.sort((a, b) -> Long.compare(b.published, a.published));  // newest first
        return out.size() > 8 ? new ArrayList<>(out.subList(0, 8)) : out;
    }

    /** First {@code <img src>} in a chunk of HTML, with the common entities decoded. */
    private static String firstImage(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<img[^>]+src=[\"']([^\"']+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html);
        if (!m.find()) return null;
        return m.group(1).replace("&amp;", "&");
    }

    /** Ask for a larger rendition where the newsroom's image URLs allow it. */
    private static String bigger(String u) {
        u = u.replaceAll("/LANDSCAPE_\\d+/", "/LANDSCAPE_1200/");
        // WordPress serves resized copies as name-1024x576.jpg; the original is there without the size
        if (u.contains("/wp-content/uploads/")) {
            u = u.replaceFirst("-\\d+x\\d+(\\.[a-zA-Z]+)(\\?.*)?$", "$1");
        }
        return u;
    }

    /** Strip tags and entities; keep one short paragraph. */
    static String clean(String s) {
        String t = s.replaceAll("(?s)<[^>]*>", " ").replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ").trim();
        return t.length() > 260 ? t.substring(0, 257).trim() + "\u2026" : t;
    }

    /** RSS dates: RFC 822 ("Thu, 22 Oct 2026 13:33:54 +0530") or ISO 8601. */
    static long parseDate(String pub) {
        String[] fmts = {"EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz",
                "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"};
        for (String f : fmts) {
            try {
                return new SimpleDateFormat(f, Locale.US).parse(pub).getTime();
            } catch (Exception ignored) {
                // try the next format
            }
        }
        return 0;
    }

    /** "Just now", "5 min ago", "3 h ago", "Yesterday" \u2014 worked out when shown, so it never goes stale. */
    static String age(long published) {
        if (published <= 0) return "";
        long min = (System.currentTimeMillis() - published) / 60000;
        if (min < 1) return "Just now";
        if (min < 60) return min + " min ago";
        if (min < 24 * 60) return (min / 60) + " h ago";
        return min < 48 * 60 ? "Yesterday" : (min / 1440) + " days ago";
    }
}
