package com.mytube.web;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** লোকাল লাইব্রেরি: Watch Later + নাম-দেওয়া প্লেলিস্ট (prefs-JSON, কোনো সার্ভার নয়)। */
public final class Library {

    private Library() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("mtw", 0);
    }

    private static JSONArray arr(String s) {
        try {
            return new JSONArray(s);
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    private static JSONObject item(String id, String title) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("t", title == null ? "" : title);
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean has(JSONArray a, String id) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) {
                return true;
            }
        }
        return false;
    }

    // ===== Watch history (দেখা ভিডিও) =====

    public static JSONArray hist(Context c) {
        return arr(p(c).getString("whist", "[]"));
    }

    /** ইতিহাসে যোগ: একই ভিডিও হলে সামনে সরে আসে; সর্বোচ্চ ২০০। */
    public static void histAdd(Context c, String id, String title) {
        if (id == null || id.length() != 11) {
            return;
        }
        try {
            JSONArray a = hist(c);
            JSONArray n = new JSONArray();
            JSONObject cur = new JSONObject();
            cur.put("id", id);
            cur.put("t", title != null && title.length() > 0 ? title : id);
            cur.put("at", System.currentTimeMillis());
            n.put(cur);
            for (int i = 0; i < a.length() && n.length() < 200; i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || id.equals(o.optString("id"))) {
                    continue;
                }
                n.put(o);
            }
            p(c).edit().putString("whist", n.toString()).apply();
        } catch (Throwable t) {
        }
    }

    public static void clearHist(Context c) {
        p(c).edit().remove("whist").apply();
    }

    /** ইতিহাস থেকে একটি ভিডিও সরানো; সত্য = কিছু মুছেছে। */
    public static boolean histRemove(Context c, String id) {
        if (id == null || id.length() == 0) {
            return false;
        }
        try {
            JSONArray a = hist(c);
            JSONArray n = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || id.equals(o.optString("id"))) {
                    continue;
                }
                n.put(o);
            }
            if (n.length() == a.length()) {
                return false;
            }
            p(c).edit().putString("whist", n.toString()).apply();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ===== Watch Later =====

    public static JSONArray wl(Context c) {
        return arr(p(c).getString("wl", "[]"));
    }

    public static boolean inWl(Context c, String id) {
        return has(wl(c), id);
    }

    /** যোগ করে; আগে থেকে থাকলে false। */
    public static boolean addWl(Context c, String id, String title) {
        JSONArray a = wl(c);
        if (has(a, id)) {
            return false;
        }
        JSONObject o = item(id, title);
        if (o == null) {
            return false;
        }
        a.put(o);
        p(c).edit().putString("wl", a.toString()).apply();
        return true;
    }

    public static void removeWl(Context c, String id) {
        JSONArray a = wl(c);
        JSONArray n = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id"))) {
                n.put(o);
            }
        }
        p(c).edit().putString("wl", n.toString()).apply();
    }

    public static void clearWl(Context c) {
        p(c).edit().putString("wl", "[]").apply();
    }

    // ===== নাম-দেওয়া প্লেলিস্ট =====

    public static java.util.ArrayList<String> playlists(Context c) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        JSONArray a = arr(p(c).getString("plIndex", "[]"));
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i);
            if (s.length() > 0) {
                out.add(s);
            }
        }
        return out;
    }

    private static String key(String name) {
        return "pl_" + name.replace('\n', ' ').trim();
    }

    public static boolean createPl(Context c, String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() == 0 || n.length() > 40) {
            return false;
        }
        java.util.ArrayList<String> ps = playlists(c);
        if (ps.contains(n)) {
            return false;
        }
        ps.add(n);
        JSONArray a = new JSONArray();
        for (String s : ps) {
            a.put(s);
        }
        p(c).edit().putString("plIndex", a.toString()).apply();
        return true;
    }

    public static void deletePl(Context c, String name) {
        java.util.ArrayList<String> ps = playlists(c);
        ps.remove(name);
        JSONArray a = new JSONArray();
        for (String s : ps) {
            a.put(s);
        }
        SharedPreferences.Editor e = p(c).edit();
        e.putString("plIndex", a.toString());
        e.remove(key(name));
        e.apply();
    }

    public static JSONArray pl(Context c, String name) {
        return arr(p(c).getString(key(name), "[]"));
    }

    /** প্লেলিস্টে যোগ; আগে থেকে থাকলে false। */
    public static boolean addToPl(Context c, String name, String id, String title) {
        if (!playlists(c).contains(name)) {
            return false;
        }
        JSONArray a = pl(c, name);
        if (has(a, id)) {
            return false;
        }
        JSONObject o = item(id, title);
        if (o == null) {
            return false;
        }
        a.put(o);
        p(c).edit().putString(key(name), a.toString()).apply();
        return true;
    }
}
