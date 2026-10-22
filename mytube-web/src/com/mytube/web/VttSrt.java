package com.mytube.web;

/** WEBVTT (ইউটিউব timedtext) → SRT রূপান্তর। */
public final class VttSrt {

    private VttSrt() {
    }

    /** VTT বা timedtext-XML (srv1/srv3) — যেটাই আসুক SRT বানায়। */
    public static String toSrtAny(String body) {
        if (body == null) {
            return "";
        }
        String t = body.trim();
        if (t.startsWith("<?xml") || t.startsWith("<transcript")) {
            return xmlToSrt(t);
        }
        return toSrt(t);
    }

    /** timedtext XML → SRT। srv1: <text start="12.34" dur="5.6">…</text>;
     * srv3: <p t="12340" d="5600">…</p> (ভেতরে <s> থাকতে পারে)। */
    private static String xmlToSrt(String xml) {
        StringBuilder out = new StringBuilder();
        int idx = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "<(?:text|p)\\b([^>]*)>(.*?)</(?:text|p)>",
                java.util.regex.Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            String attrs = m.group(1);
            String txt = clean(m.group(2));
            txt = txt.replaceAll("<s\\b[^>]*>", "").replaceAll("</s>", " ").trim();
            txt = txt.replaceAll("\\s+", " ").trim();
            double st = attrMs(attrs, "start", "t");
            double du = attrMs(attrs, "dur", "d");
            if (txt.length() == 0 || du <= 0) {
                continue;
            }
            idx++;
            out.append(idx).append('\n');
            out.append(srtMs((long) st)).append(" --> ").append(srtMs((long) (st + du)));
            out.append('\n').append(txt).append('\n').append('\n');
        }
        return out.toString();
    }

    /** "start"/"t" ও "dur"/"d" অ্যাট্রিবিউট → মিলিসেকেন্ড (দশমিক থাকলে সেকেন্ড)। */
    private static double attrMs(String attrs, String k1, String k2) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:^|[\\s])(?:" + k1 + "|" + k2 + ")=\"([^\"]+)\"").matcher(attrs);
        if (!m.find()) {
            return 0;
        }
        try {
            double v = Double.parseDouble(m.group(1));
            if (m.group(1).contains(".")) {
                return v * 1000.0; // srv1: সেকেন্ড
            }
            return v; // srv3: মিলিসেকেন্ড
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String srtMs(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long h = ms / 3600000;
        long m = (ms % 3600000) / 60000;
        long sec = (ms % 60000) / 1000;
        long r = ms % 1000;
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", h, m, sec, r);
    }

    public static String toSrt(String vtt) {
        if (vtt == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        String[] lines = vtt.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        int idx = 0;
        int i = 0;
        int n = lines.length;
        while (i < n) {
            // কিউ-ব্লক খোঁজি: "-->" লাইন
            while (i < n && !lines[i].contains("-->")) {
                i++;
            }
            if (i >= n) {
                break;
            }
            String times = lines[i].trim();
            i++;
            StringBuilder txt = new StringBuilder();
            while (i < n && lines[i].trim().length() > 0) {
                if (txt.length() > 0) {
                    txt.append('\n');
                }
                txt.append(clean(lines[i]));
                i++;
            }
            String t = txt.toString().trim();
            if (t.length() == 0) {
                continue;
            }
            idx++;
            out.append(idx).append('\n');
            out.append(srtTime(times)).append('\n');
            out.append(t).append('\n').append('\n');
        }
        return out.toString();
    }

    /** "00:00:01.360 --> 00:00:03.040" → "00:00:01,360 --> 00:00:03,040" (MM:SS.mmmও সামলায়)। */
    private static String srtTime(String line) {
        String[] parts = line.split("-->");
        if (parts.length != 2) {
            return line;
        }
        return fix(parts[0].trim()) + " --> " + fix(parts[1].trim().split(" ")[0]);
    }

    private static String fix(String t) {
        // মিলিসেকেন্ডের পরে অতিরিক্ত কিছু (position ইত্যাদি) ফেলে দিই
        int sp = t.indexOf(' ');
        if (sp > 0) {
            t = t.substring(0, sp);
        }
        String h = "00";
        String m;
        String s;
        String ms;
        String[] c1 = t.split(":");
        try {
            if (c1.length == 3) {
                h = pad(c1[0]);
                m = pad(c1[1]);
                String[] c2 = c1[2].split("\\.");
                s = pad(c2[0]);
                ms = ms(c2.length > 1 ? c2[1] : "0");
            } else if (c1.length == 2) {
                m = pad(c1[0]);
                String[] c2 = c1[1].split("\\.");
                s = pad(c2[0]);
                ms = ms(c2.length > 1 ? c2[1] : "0");
            } else {
                String[] c2 = t.split("\\.");
                m = "00";
                s = pad(c2[0]);
                ms = ms(c2.length > 1 ? c2[1] : "0");
            }
        } catch (Throwable e) {
            return t;
        }
        return h + ":" + m + ":" + s + "," + ms;
    }

    private static String pad(String x) {
        String v = x.trim();
        if (v.length() == 1) {
            v = "0" + v;
        }
        return v;
    }

    private static String ms(String x) {
        String v = x.trim();
        if (v.length() > 3) {
            v = v.substring(0, 3);
        }
        while (v.length() < 3) {
            v = v + "0";
        }
        return v;
    }

    /** ট্যাগ ও entity পরিষ্কার। */
    private static String clean(String line) {
        String v = line.replaceAll("<[^>]*>", "");
        v = v.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&#39;", "'").replace("&quot;", "\"")
                .replace("&#160;", " ");
        return v.trim();
    }
}
