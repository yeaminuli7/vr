import java.util.ArrayList;

public class ExtractorsTest {
    static int pass = 0, fail = 0;
    static void ok(boolean c, String m) {
        if (c) { pass++; } else { fail++; System.out.println("  FAIL " + m); }
    }
    static boolean has(ArrayList<Extractors.Hit> l, String frag) {
        for (Extractors.Hit h : l) if (h.url.contains(frag)) return true;
        return false;
    }

    public static void main(String[] a) {
        // ---------- unescape ----------
        ok("a&b".equals(Extractors.unescape("a\\u0026b")), "unicode amp");
        ok("x/y".equals(Extractors.unescape("x\\/y")), "escaped slash");
        ok("p/q".equals(Extractors.unescape("p\\u002Fq")), "unicode slash");
        ok("a\"b".equals(Extractors.unescape("a\\\"b")), "escaped quote");
        ok("plain".equals(Extractors.unescape("plain")), "no escapes");

        // ---------- host ----------
        ok(Extractors.host("https://www.tiktok.com/@u/video/1").equals("www.tiktok.com"), "host tiktok");
        ok(Extractors.host("https://example.com:8443/x").equals("example.com"), "host port");
        ok(Extractors.host("not a url").equals(""), "host junk");

        // ---------- canHandle ----------
        ok(Extractors.canHandle("https://www.tiktok.com/@u/video/1"), "tiktok handled");
        ok(Extractors.canHandle("https://vm.tiktok.com/ZM/"), "vm.tiktok handled");
        ok(Extractors.canHandle("https://www.facebook.com/watch/?v=1"), "facebook handled");
        ok(Extractors.canHandle("https://fb.watch/abc/"), "fb.watch handled");
        ok(Extractors.canHandle("https://www.instagram.com/reel/X/"), "instagram handled");
        ok(!Extractors.canHandle("https://www.youtube.com/watch?v=x"), "youtube NOT handled");
        ok(Extractors.canHandle("https://example.com/x.mp4"), "generic site now handled (v2.19)");

        // ---------- TikTok: simple page ----------
        String tik = "{\"itemStruct\":{\"video\":{"
            + "\"playAddr\":\"https:\\/\\/v16-webapp.tiktok.com\\/video\\/tos\\/abc\\/?a=1\\u0026mime_type=video_mp4\","
            + "\"downloadAddr\":\"https:\\/\\/v16-webapp.tiktok.com\\/video\\/tos\\/dl\\/?a=2\"}}}";
        ArrayList<Extractors.Hit> t = Extractors.parseTikTok(tik);
        ok(t.size() >= 2, "tiktok: two addrs found (" + t.size() + ")");
        ok(has(t, "mime_type=video_mp4"), "tiktok: playAddr unescaped correctly");
        ok(has(t, "/dl/"), "tiktok: downloadAddr found");
        for (Extractors.Hit h : t) {
            ok(h.url.startsWith("https://"), "tiktok url starts https: " + h.url);
            ok(!h.url.contains("\\"), "tiktok url has no backslash");
            ok(h.ext.equals("mp4"), "tiktok ext mp4");
        }

        // ---------- TikTok: bitrateInfo with several qualities ----------
        String tik2 = "{\"bitrateInfo\":[{\"Bitrate\":3000000,\"PlayAddr\":{\"UrlList\":[\"https:\\/\\/cdn.tiktok.com\\/hd.mp4?a=1\"]}},"
            + "{\"Bitrate\":900000,\"PlayAddr\":{\"UrlList\":[\"https:\\/\\/cdn.tiktok.com\\/sd.mp4?a=2\"]}}]}";
        ArrayList<Extractors.Hit> t2 = Extractors.parseTikTok(tik2);
        ok(has(t2, "hd.mp4"), "tiktok bitrateInfo: hd found");
        ok(has(t2, "sd.mp4"), "tiktok bitrateInfo: sd found");
        boolean hdLabelled = false;
        for (Extractors.Hit h : t2) if (h.url.contains("hd.mp4") && h.label.equals("HD")) hdLabelled = true;
        ok(hdLabelled, "tiktok bitrateInfo: 3Mbps labelled HD");

        // ---------- TikTok: nothing usable ----------
        ok(Extractors.parseTikTok("<html>no json here</html>").isEmpty(), "tiktok: empty when absent");
        ok(Extractors.parseTikTok(null).isEmpty(), "tiktok: null safe");
        ok(Extractors.parseTikTokRehydration("<html>nothing</html>").isEmpty(), "rehydration: empty");

        // ---------- Facebook ----------
        String fb = "{\"playable_url\":\"https:\\/\\/video.xx.fbcdn.net\\/v\\/sd.mp4?efg=1\","
            + "\"playable_url_quality_hd\":\"https:\\/\\/video.xx.fbcdn.net\\/v\\/hd.mp4?efg=2\"}";
        ArrayList<Extractors.Hit> f = Extractors.parseFacebook(fb);
        ok(has(f, "hd.mp4"), "fb: hd found");
        ok(has(f, "sd.mp4"), "fb: sd found");
        boolean hdFirst = f.size() > 0 && f.get(0).url.contains("hd.mp4");
        ok(hdFirst, "fb: HD listed first");
        ok(!Extractors.parseFacebook("<html>x</html>").isEmpty() == false, "fb: empty when absent");
        ok(Extractors.parseFacebook(null).isEmpty(), "fb: null safe");

        // old-style hd_src/sd_src
        String fb2 = "\"hd_src\":\"https:\\/\\/scontent.xx.fbcdn.net\\/h.mp4\",\"sd_src\":\"https:\\/\\/scontent.xx.fbcdn.net\\/s.mp4\"";
        ArrayList<Extractors.Hit> f2 = Extractors.parseFacebook(fb2);
        ok(has(f2, "h.mp4"), "fb old: hd_src found");
        ok(has(f2, "s.mp4"), "fb old: sd_src found");

        // ---------- Instagram ----------
        String ig = "{\"items\":[{\"video_versions\":[{\"url\":\"https:\\/\\/scontent.cdninstagram.com\\/v\\/hd.mp4\",\"type\":101},"
            + "{\"url\":\"https:\\/\\/scontent.cdninstagram.com\\/v\\/sd.mp4\",\"type\":100}]}]}";
        ArrayList<Extractors.Hit> i1 = Extractors.parseInstagram(ig);
        ok(has(i1, "hd.mp4"), "ig: hd found");
        ok(has(i1, "sd.mp4"), "ig: sd found");
        ok(Extractors.parseInstagram("<html>login required</html>").isEmpty(), "ig: empty when absent");

        // ---------- de-duplication ----------
        String dup = "\"playAddr\":\"https:\\/\\/x.com\\/a.mp4\",\"downloadAddr\":\"https:\\/\\/x.com\\/a.mp4\"";
        ArrayList<Extractors.Hit> d = Extractors.parseTikTok(dup);
        ok(d.size() == 1, "dedupe identical urls (" + d.size() + ")");

        // ---------- audio ext ----------
        ArrayList<Extractors.Hit> au = Extractors.parseFacebook("\"playable_url\":\"https:\\/\\/x.com\\/a.m4a\"");
        ok(au.size() == 1 && au.get(0).audio && au.get(0).ext.equals("m4a"), "m4a flagged audio");


        // ---------- direct media links: no extraction at all ----------
        ok("mp4".equals(Extractors.directExt("https://cdn.x.com/a/b.mp4")), "directExt mp4");
        ok("mp4".equals(Extractors.directExt("https://cdn.x.com/a/b.MP4")), "directExt uppercase");
        ok("mp4".equals(Extractors.directExt("https://cdn.x.com/b.mp4?token=1&x=2")), "directExt with query");
        ok("mp4".equals(Extractors.directExt("https://cdn.x.com/b.mp4#t=10")), "directExt with frag");
        ok("m4a".equals(Extractors.directExt("https://cdn.x.com/song.m4a")), "directExt m4a");
        ok("webm".equals(Extractors.directExt("https://cdn.x.com/v.webm")), "directExt webm");
        ok(Extractors.directExt("https://www.tiktok.com/@u/video/731") == null, "page is not direct");
        ok(Extractors.directExt("https://example.com/page") == null, "html is not direct");
        ok(Extractors.directExt(null) == null, "null safe");

        // ---------- generic extractor (any site) ----------
        String og = "<html><head>"
            + "<meta property=\"og:video\" content=\"https://cdn.site.com/v/clip.mp4\">"
            + "<meta name=\"twitter:player:stream\" content=\"https://cdn.site.com/v/tw.mp4\">"
            + "</head><body></body></html>";
        ArrayList<Extractors.Hit> g = Extractors.parseGeneric(og);
        ok(has(g, "clip.mp4"), "og:video found");
        ok(has(g, "tw.mp4"), "twitter:player:stream found");

        String ogSecure = "<meta content=\"https://cdn.site.com/hd.mp4\" property=\"og:video:secure_url\">";
        ok(has(Extractors.parseGeneric(ogSecure), "hd.mp4"), "og:video:secure_url (reversed attrs)");

        String vid = "<video controls src=\"https://cdn.site.com/plain.mp4\"></video>"
                   + "<video><source src=\"https://cdn.site.com/src.mp4\"></video>";
        ArrayList<Extractors.Hit> gv = Extractors.parseGeneric(vid);
        ok(has(gv, "plain.mp4"), "<video src> found");
        ok(has(gv, "src.mp4"), "<source src> found");

        String ld = "{\"@type\":\"VideoObject\",\"contentUrl\":\"https://cdn.site.com/ld.mp4\"}";
        ok(has(Extractors.parseGeneric(ld), "ld.mp4"), "JSON-LD contentUrl found");

        String ampUrl = "<meta property=\"og:video\" content=\"https://cdn.site.com/a.mp4?x=1&amp;y=2\">";
        ArrayList<Extractors.Hit> ga = Extractors.parseGeneric(ampUrl);
        ok(ga.size() == 1 && ga.get(0).url.endsWith("x=1&y=2"), "&amp; decoded");

        ok(Extractors.parseGeneric("<html>nothing at all</html>").isEmpty(), "generic: empty when absent");
        ok(Extractors.parseGeneric(null).isEmpty(), "generic: null safe");

        // ---------- canHandle now covers any site but never YouTube ----------
        ok(Extractors.canHandle("https://random-site.example/page"), "generic site handled");
        ok(Extractors.canHandle("https://vimeo.com/123"), "vimeo handled");
        ok(Extractors.canHandle("https://x.com/u/status/1"), "x handled");
        ok(!Extractors.canHandle("https://www.youtube.com/watch?v=x"), "youtube still NOT handled");
        ok(!Extractors.canHandle("https://youtu.be/x"), "youtu.be still NOT handled");

        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail > 0 ? 1 : 0);
    }
}
