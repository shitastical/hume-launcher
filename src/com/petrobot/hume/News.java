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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * News for the top shelf from newsrooms' RSS feeds, which carry a large image per story
 * (free, no key): U.S. national, Local (the state from the weather location, or the city's own
 * newsrooms when it has any) and World stories
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
    /** The chosen state for local news; empty follows the weather location. */
    static final String K_STATE = "news_state";
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

    /**
     * Local newsrooms per state (and DC), used when the weather location's city has no newsroom of
     * its own: statewide coverage plus the largest metros in that state. Every feed here was checked
     * for the image the carousel needs, because a feed without images contributes nothing.
     */
    static final Map<String, List<Local>> STATE_FEEDS = new LinkedHashMap<>();

    /** The state each city-keyed newsroom belongs to, for "Columbus, OH" style labels. */
    private static final Map<String, String> CITY_STATE = new HashMap<>();

    /** Every state and DC, alphabetical, for the "Local news state" picker. */
    static final String[] STATE_CODES = {"AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY"};
    static final String[] STATE_NAMES = {"Alabama", "Alaska", "Arizona", "Arkansas", "California", "Colorado", "Connecticut", "Delaware", "District of Columbia", "Florida", "Georgia", "Hawaii", "Idaho", "Illinois", "Indiana", "Iowa", "Kansas", "Kentucky", "Louisiana", "Maine", "Maryland", "Massachusetts", "Michigan", "Minnesota", "Mississippi", "Missouri", "Montana", "Nebraska", "Nevada", "New Hampshire", "New Jersey", "New Mexico", "New York", "North Carolina", "North Dakota", "Ohio", "Oklahoma", "Oregon", "Pennsylvania", "Rhode Island", "South Carolina", "South Dakota", "Tennessee", "Texas", "Utah", "Vermont", "Virginia", "Washington", "West Virginia", "Wisconsin", "Wyoming"};

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

        // Every state and DC: statewide coverage plus its largest metros, so local news follows the
        // weather location anywhere in the country. Ohio's cities above stay city-specific.
        addState("AL", "Alabama Reflector", "https://alabamareflector.com/feed/", "WDHN", "https://www.wdhn.com/feed/");
        addState("AK", "Alaska Beacon", "https://alaskabeacon.com/feed/", "Anchorage Daily News", "https://www.adn.com/arc/outboundfeeds/rss/");
        addState("AZ", "Arizona Mirror", "https://azmirror.com/feed/", "12News", "https://www.12news.com/feeds/syndication/rss/news", "KTAR", "https://ktar.com/feed/");
        addState("AR", "Arkansas Advocate", "https://arkansasadvocate.com/feed/", "THV11", "https://www.thv11.com/feeds/syndication/rss/news", "KARK", "https://www.kark.com/feed/");
        addState("CA", "KRON4", "https://kron4.com/feed/", "ABC 10", "https://www.abc10.com/feeds/syndication/rss/news", "KTLA", "https://ktla.com/feed/", "ABC 7", "https://abc7.com/feed");
        addState("CO", "Colorado Newsline", "https://coloradonewsline.com/feed/", "9NEWS", "https://www.9news.com/feeds/syndication/rss/news", "KDVR", "https://kdvr.com/feed/");
        addState("CT", "CT Mirror", "https://ctmirror.org/feed/", "WTNH", "https://www.wtnh.com/feed/");
        addState("DE", "Delaware Live", "https://delawarelive.com/feed/");
        addState("DC", "WUSA9", "https://www.wusa9.com/feeds/syndication/rss/news", "DC News Now", "https://www.dcnewsnow.com/feed/", "WTOP", "https://wtop.com/feed/");
        addState("FL", "Florida Phoenix", "https://floridaphoenix.com/feed/", "WTSP", "https://www.wtsp.com/feeds/syndication/rss/news");
        addState("GA", "Georgia Recorder", "https://georgiarecorder.com/feed/", "11Alive", "https://www.11alive.com/feeds/syndication/rss/news");
        addState("HI", "Star-Advertiser", "https://www.staradvertiser.com/feed/", "Civil Beat", "https://www.civilbeat.org/feed/");
        addState("ID", "Idaho Capital Sun", "https://idahocapitalsun.com/feed/", "KTVB", "https://www.ktvb.com/feeds/syndication/rss/news");
        addState("IL", "WGN", "https://wgntv.com/feed/", "Block Club Chicago", "https://blockclubchicago.org/feed/");
        addState("IN", "Indiana Capital Chronicle", "https://indianacapitalchronicle.com/feed/", "WTHR", "https://www.wthr.com/feeds/syndication/rss/news", "FOX59", "https://fox59.com/feed/");
        addState("IA", "Iowa Capital Dispatch", "https://iowacapitaldispatch.com/feed/", "WHO13", "https://who13.com/feed/");
        addState("KS", "Kansas Reflector", "https://kansasreflector.com/feed/", "KSN", "https://www.ksn.com/feed/");
        addState("KY", "Kentucky Lantern", "https://kentuckylantern.com/feed/");
        addState("LA", "WWL", "https://www.wwltv.com/feeds/syndication/rss/news", "WGNO", "https://wgno.com/feed/");
        addState("ME", "Maine Morning Star", "https://mainemorningstar.com/feed/", "NEWS CENTER Maine", "https://www.newscentermaine.com/feeds/syndication/rss/news");
        addState("MD", "Maryland Matters", "https://marylandmatters.org/feed/", "Baltimore Banner", "https://www.thebaltimorebanner.com/arc/outboundfeeds/rss/", "Baltimore Fishbowl", "https://baltimorefishbowl.com/feed/");
        addState("MA", "WBUR", "https://www.wbur.org/feed", "MassLive", "https://www.masslive.com/arc/outboundfeeds/rss/");
        addState("MI", "Michigan Advance", "https://michiganadvance.com/feed/", "WZZM13", "https://www.wzzm13.com/feeds/syndication/rss/news");
        addState("MN", "Minnesota Reformer", "https://minnesotareformer.com/feed/", "KARE 11", "https://www.kare11.com/feeds/syndication/rss/news", "KSTP", "https://kstp.com/feed/");
        addState("MS", "Mississippi Today", "https://mississippitoday.org/feed/", "WJTV", "https://www.wjtv.com/feed/");
        addState("MO", "KSDK", "https://www.ksdk.com/feeds/syndication/rss/news", "FOX 2", "https://fox2now.com/feed/");
        addState("MT", "Missoulian", "https://missoulian.com/search/?f=rss", "Bozeman Chronicle", "https://www.bozemandailychronicle.com/search/?f=rss", "Montana Free Press", "https://montanafreepress.org/feed/");
        addState("NE", "Nebraska Examiner", "https://nebraskaexaminer.com/feed/", "Lincoln Journal Star", "https://journalstar.com/search/?f=rss", "Omaha World-Herald", "https://omaha.com/search/?f=rss");
        addState("NV", "Nevada Current", "https://nevadacurrent.com/feed/", "8 News Now", "https://www.8newsnow.com/feed/");
        addState("NH", "New Hampshire Bulletin", "https://newhampshirebulletin.com/feed/", "InDepthNH", "https://indepthnh.org/feed/");
        addState("NJ", "New Jersey Monitor", "https://newjerseymonitor.com/feed/", "NJ.com", "https://www.nj.com/arc/outboundfeeds/rss/", "NJ Spotlight", "https://www.njspotlightnews.org/feed/");
        addState("NM", "Source New Mexico", "https://sourcenm.com/feed/", "KOB", "https://www.kob.com/feed/", "Santa Fe New Mexican", "https://www.santafenewmexican.com/search/?f=rss");
        addState("NY", "WGRZ", "https://www.wgrz.com/feeds/syndication/rss/news", "PIX11", "https://pix11.com/feed/");
        addState("NC", "NC Newsline", "https://ncnewsline.com/feed/", "WCNC", "https://www.wcnc.com/feeds/syndication/rss/news", "WGHP", "https://www.wghp.com/feed/");
        addState("ND", "North Dakota Monitor", "https://northdakotamonitor.com/feed/", "WDAY", "https://www.wday.com/feed", "KX Net", "https://www.kxnet.com/feed/");
        addState("OH", "Ohio Capital Journal", "https://ohiocapitaljournal.com/feed/", "NBC4", "https://www.nbc4i.com/feed/", "cleveland.com", "https://www.cleveland.com/arc/outboundfeeds/rss/?outputType=xml", "WKBN", "https://www.wkbn.com/feed/");
        addState("OK", "Oklahoma Voice", "https://oklahomavoice.com/feed/", "KFOR", "https://kfor.com/feed/");
        addState("OR", "Oregon Capital Chronicle", "https://oregoncapitalchronicle.com/feed/", "KGW", "https://www.kgw.com/feeds/syndication/rss/news");
        addState("PA", "PennLive", "https://www.pennlive.com/arc/outboundfeeds/rss/", "WHYY", "https://whyy.org/feed/");
        addState("RI", "Rhode Island Current", "https://rhodeislandcurrent.com/feed/", "WPRI", "https://www.wpri.com/feed/");
        addState("SC", "South Carolina Daily Gazette", "https://scdailygazette.com/feed/", "WSPA", "https://www.wspa.com/feed/");
        addState("SD", "South Dakota Searchlight", "https://southdakotasearchlight.com/feed/", "KELOLAND", "https://www.keloland.com/feed/");
        addState("TN", "Tennessee Lookout", "https://tennesseelookout.com/feed/", "WBIR", "https://www.wbir.com/feeds/syndication/rss/news", "WKRN", "https://www.wkrn.com/feed/");
        addState("TX", "KHOU", "https://www.khou.com/feeds/syndication/rss/news", "KVUE", "https://www.kvue.com/feeds/syndication/rss/news", "WFAA", "https://www.wfaa.com/feeds/syndication/rss/news", "KXAN", "https://www.kxan.com/feed/");
        addState("UT", "Utah News Dispatch", "https://utahnewsdispatch.com/feed/", "KSL", "https://www.ksl.com/rss/news");
        addState("VT", "VTDigger", "https://vtdigger.org/feed/", "Seven Days", "https://www.sevendaysvt.com/feed");
        addState("VA", "Virginia Mercury", "https://virginiamercury.com/feed/", "WAVY", "https://www.wavy.com/feed/");
        addState("WA", "Washington State Standard", "https://washingtonstatestandard.com/feed/", "KREM", "https://www.krem.com/feeds/syndication/rss/news", "KING 5", "https://www.king5.com/feeds/syndication/rss/news");
        addState("WV", "West Virginia Watch", "https://westvirginiawatch.com/feed/", "WBOY", "https://www.wboy.com/feed/");
        addState("WI", "Wisconsin Examiner", "https://wisconsinexaminer.com/feed/", "Madison.com", "https://madison.com/search/?f=rss");
        addState("WY", "WyoFile", "https://wyofile.com/feed/", "Casper Star-Tribune", "https://trib.com/search/?f=rss");

        // Each city-keyed newsroom's state, so labels can read "Columbus, OH".
        String[][] cityStates = {{"cleveland", "OH"}, {"columbus", "OH"}, {"dayton", "OH"},
                {"toledo", "OH"}, {"youngstown", "OH"}, {"los-angeles", "CA"}, {"chicago", "IL"},
                {"san-francisco", "CA"}, {"denver", "CO"}, {"indianapolis", "IN"}, {"nashville", "TN"},
                {"st-louis", "MO"}, {"minneapolis", "MN"}, {"dallas", "TX"}};
        for (String[] cs : cityStates) CITY_STATE.put(cs[0], cs[1]);
    }

    private static void add(String city, String name, String url) {
        List<Local> l = LOCAL_FEEDS.get(city);
        if (l == null) LOCAL_FEEDS.put(city, l = new ArrayList<>());
        l.add(new Local(name, url));
    }

    /** Name/URL pairs for one state, added in one go. */
    private static void addState(String code, String... namesAndUrls) {
        List<Local> l = new ArrayList<>();
        for (int i = 0; i + 1 < namesAndUrls.length; i += 2) l.add(new Local(namesAndUrls[i], namesAndUrls[i + 1]));
        STATE_FEEDS.put(code, l);
    }

    /** State code for a name the weather sources report ("Ohio" or "OH"); "" when unknown. */
    static String codeFor(String name) {
        if (name == null) return "";
        String n = name.trim();
        if (n.isEmpty()) return "";
        for (int i = 0; i < STATE_CODES.length; i++) {
            if (STATE_CODES[i].equalsIgnoreCase(n) || STATE_NAMES[i].equalsIgnoreCase(n)) return STATE_CODES[i];
        }
        return "";
    }

    static String stateName(String code) {
        for (int i = 0; i < STATE_CODES.length; i++) if (STATE_CODES[i].equals(code)) return STATE_NAMES[i];
        return "";
    }

    /**
     * The state local news comes from: the state chosen in Settings, else the weather location's
     * state (from the geocoder or the internet address), else the state of a city-keyed newsroom.
     */
    static String state(Context c) {
        String chosen = stateChoice(c);
        if (!chosen.isEmpty()) return chosen;
        String fromWeather = codeFor(Weather.region(c));
        if (!fromWeather.isEmpty()) return fromWeather;
        String city = city(c);
        return city.isEmpty() ? "" : CITY_STATE.getOrDefault(slug(city), "");
    }

    /** The state chosen in Settings, or "" when local news follows the weather location. */
    static String stateChoice(Context c) {
        String code = Suggestions.prefs(c).getString(K_STATE, "");
        for (String s : STATE_CODES) if (s.equals(code)) return code;
        return "";
    }

    /** Null or "" follows the weather location again. */
    static void setState(Context c, String code) {
        Suggestions.prefs(c).edit().putString(K_STATE, code == null ? "" : code).apply();
    }

    /** How many local newsrooms the given state contributes. */
    static int localCount(String code) {
        List<Local> l = STATE_FEEDS.get(code);
        return l == null ? 0 : l.size();
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
                String st = state(c);
                List<Local> locals = city.isEmpty() ? null : LOCAL_FEEDS.get(slug(city));
                boolean own = locals != null;
                if (locals == null) locals = STATE_FEEDS.get(st);
                if (locals == null) continue;
                String where = own ? (st.isEmpty() ? city : city + ", " + st) : stateName(st);
                for (Local l : locals) {
                    try {
                        // local news moves slower than national
                        List<Suggestions.Item> got = read(l.url, where + "  \u00b7  " + l.name, 7 * 86400000L);
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
