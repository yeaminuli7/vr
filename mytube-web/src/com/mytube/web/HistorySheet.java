package com.mytube.web;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ইউটিউব-ধাঁচের হিস্ট্রি-স্ক্রিন: দিনভিত্তিক সেকশন (Today / Yesterday / তারিখ),
 * প্রতিটি ভিডিও থাম্বনেইল + নাম সহ কার্ড হিসেবে — ঠিক ডাউনলোড-স্ক্রিনের মতো।
 *
 * থাম্বনেইল i.ytimg.com থেকে আসে (সঞ্চিত ভিডিও-আইডি দিয়ে), তাই পুরনো
 * হিস্ট্রিতেও ছবি দেখা যায়।
 */
public final class HistorySheet {

    private HistorySheet() {
    }

    private static final int TH_W = 124;
    private static final int TH_H = 70;

    /** এক হিস্ট্রি-এন্ট্রি। */
    private static final class Item {
        String id;
        String title;
        long at;
    }

    public static void show(final Activity a) {
        try {
            final Dialog dg = new Dialog(a, android.R.style.Theme_Material_NoActionBar);
            ScrollView sv = new ScrollView(a);
            final LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(Ui.dp(a, 16), Ui.dp(a, 18), Ui.dp(a, 16), Ui.dp(a, 20));
            box.setBackgroundColor(0xFF0F0F0F);
            sv.setBackgroundColor(0xFF0F0F0F);
            sv.addView(box);
            sv.setVerticalScrollBarEnabled(false);

            final Runnable[] redraw = new Runnable[1];
            redraw[0] = new Runnable() {
                @Override
                public void run() {
                    if (dg.isShowing()) {
                        rebuild(a, box, redraw);
                    }
                }
            };
            rebuild(a, box, redraw);

            dg.setContentView(sv);
            dg.setCanceledOnTouchOutside(true);
            Window win = dg.getWindow();
            if (win != null) {
                win.setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(0xFF0F0F0F));
                win.setLayout(-1, -1);
            }
            final Handler h = new Handler(Looper.getMainLooper());
            dg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    h.removeCallbacksAndMessages(null);
                }
            });
            dg.show();
        } catch (Throwable t) {
        }
    }

    private static void rebuild(final Activity a, LinearLayout box, final Runnable[] redraw) {
        try {
            box.removeAllViews();

            // ---- হেডার ----
            LinearLayout head = new LinearLayout(a);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(a);
            title.setText("History");
            title.setTextSize(24);
            title.setTypeface(null, 1);
            title.setTextColor(0xFFF2F2F5);
            head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

            ArrayList<Item> all = read(a);
            if (!all.isEmpty()) {
                TextView clear = new TextView(a);
                clear.setText("Clear all");
                clear.setTextColor(0xFF3EA6FF);
                clear.setTextSize(13);
                clear.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), 0, Ui.dp(a, 8));
                clear.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmClear(a, redraw);
                    }
                });
                head.addView(clear);
            }
            box.addView(head);

            if (all.isEmpty()) {
                box.addView(emptyState(a));
                return;
            }

            box.addView(divider(a));

            // ---- দিনভিত্তিক সেকশন ----
            LinkedHashMap<String, ArrayList<Item>> groups =
                    new LinkedHashMap<String, ArrayList<Item>>();
            for (int i = 0; i < all.size(); i++) {
                Item it = all.get(i);
                String key = dayLabel(it.at);
                ArrayList<Item> list = groups.get(key);
                if (list == null) {
                    list = new ArrayList<Item>();
                    groups.put(key, list);
                }
                list.add(it);
            }

            boolean first = true;
            for (Map.Entry<String, ArrayList<Item>> e : groups.entrySet()) {
                box.addView(Ui.sectionLabel(a, e.getKey().toUpperCase(java.util.Locale.US)));
                ArrayList<Item> list = e.getValue();
                for (int i = 0; i < list.size(); i++) {
                    box.addView(card(a, list.get(i), redraw));
                    if (i < list.size() - 1) {
                        box.addView(Ui.hair(a));
                    }
                }
                first = false;
            }
            if (first) {
                box.addView(emptyState(a));
            }
        } catch (Throwable t) {
        }
    }

    /** হিস্ট্রি পড়া; নতুন-থেকে-পুরনো (Library ইতোমধ্যে তাই রাখে)। */
    private static ArrayList<Item> read(Activity a) {
        ArrayList<Item> out = new ArrayList<Item>();
        try {
            JSONArray arr = Library.hist(a);
            for (int i = 0; i < arr.length() && out.size() < 200; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Item it = new Item();
                it.id = o.optString("id", "");
                it.title = o.optString("t", "");
                it.at = o.optLong("at", 0L);
                if (it.id.length() == 0) {
                    continue;
                }
                out.add(it);
            }
        } catch (Throwable t) {
        }
        return out;
    }

    private static View card(final Activity a, final Item it, final Runnable[] redraw) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));

        // -- থাম্বনেইল --
        FrameLayout th = new FrameLayout(a);
        th.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, TH_W), Ui.dp(a, TH_H)));
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Ui.placeholder(a, false));
        iv.setClipToOutline(true);
        th.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        row.addView(th);
        if (it.id.length() == 11) {
            YtThumb.load(a, it.id, iv);
        }

        // -- টেক্সট --
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
        clp.leftMargin = Ui.dp(a, 12);
        col.setLayoutParams(clp);

        TextView t1 = new TextView(a);
        t1.setText(it.title.length() > 0 ? it.title : it.id);
        t1.setTextColor(0xFFF2F2F5);
        t1.setTextSize(14);
        t1.setMaxLines(2);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t1);

        TextView t2 = new TextView(a);
        t2.setText(whenStr(it.at));
        t2.setTextColor(0xFF9A9A9A);
        t2.setTextSize(12);
        t2.setMaxLines(1);
        LinearLayout.LayoutParams t2lp = new LinearLayout.LayoutParams(-1, -2);
        t2lp.topMargin = Ui.dp(a, 3);
        col.addView(t2, t2lp);
        row.addView(col);

        // -- তিন-বিন্দু মেনু --
        View dots = new Ui.DotsIcon(a);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Ui.dp(a, 22), Ui.dp(a, 30));
        dlp.leftMargin = Ui.dp(a, 2);
        dots.setLayoutParams(dlp);
        dots.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                menu(a, v, it, redraw);
            }
        });
        row.addView(dots);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    if (a instanceof MainActivity) {
                        ((MainActivity) a).openVideoById(it.id, it.title);
                    }
                } catch (Throwable t) {
                }
            }
        });
        return row;
    }

    private static void menu(final Activity a, View anchor, final Item it,
            final Runnable[] redraw) {
        try {
            PopupMenu pm = new PopupMenu(a, anchor);
            pm.getMenu().add(0, 1, 0, "Play");
            pm.getMenu().add(0, 2, 1, "Remove from history");
            pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(android.view.MenuItem item) {
                    if (item.getItemId() == 1) {
                        try {
                            if (a instanceof MainActivity) {
                                ((MainActivity) a).openVideoById(it.id, it.title);
                            }
                        } catch (Throwable t) {
                        }
                    } else if (item.getItemId() == 2) {
                        Library.histRemove(a, it.id);
                        android.widget.Toast.makeText(a, "Removed from history",
                                android.widget.Toast.LENGTH_SHORT).show();
                        if (redraw != null && redraw[0] != null) {
                            redraw[0].run();
                        }
                    }
                    return true;
                }
            });
            pm.show();
        } catch (Throwable t) {
        }
    }

    private static void confirmClear(final Activity a, final Runnable[] redraw) {
        try {
            new android.app.AlertDialog.Builder(a)
                    .setTitle("Clear watch history?")
                    .setMessage("Removes the watched-videos list")
                    .setPositiveButton("Clear",
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    Library.clearHist(a);
                                    android.widget.Toast.makeText(a, "Watch history cleared",
                                            android.widget.Toast.LENGTH_SHORT).show();
                                    if (redraw != null && redraw[0] != null) {
                                        redraw[0].run();
                                    }
                                }
                            })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Throwable t) {
        }
    }

    private static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0xFF2A2A30);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(c, 1)));
        lp.topMargin = Ui.dp(c, 10);
        v.setLayoutParams(lp);
        return v;
    }

    private static View emptyState(Context c) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        b.setPadding(0, Ui.dp(c, 72), 0, 0);

        TextView t1 = new TextView(c);
        t1.setText("No watch history yet");
        t1.setTextColor(0xFFF2F2F5);
        t1.setTextSize(16);
        t1.setTypeface(null, 1);
        b.addView(t1);

        TextView t2 = new TextView(c);
        t2.setText("Videos you watch will show up here.");
        t2.setTextColor(0xFF6F6F75);
        t2.setTextSize(12);
        t2.setPadding(0, Ui.dp(c, 8), 0, 0);
        b.addView(t2);
        return b;
    }

    // ------------------------------------------------------------------

    /** সেকশন-নাম: Today / Yesterday / নির্দিষ্ট তারিখ। */
    private static String dayLabel(long at) {
        if (at <= 0) {
            return "Earlier";
        }
        long day = 86400000L;
        long d = System.currentTimeMillis() / day - at / day;
        if (d <= 0) {
            return "Today";
        }
        if (d == 1) {
            return "Yesterday";
        }
        try {
            java.text.SimpleDateFormat f;
            if (d < 365) {
                f = new java.text.SimpleDateFormat("MMM d", java.util.Locale.US);
            } else {
                f = new java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US);
            }
            return f.format(new java.util.Date(at));
        } catch (Throwable t) {
            return "Earlier";
        }
    }

    /** "2 hours ago" / "Yesterday at 21:40" / "Oct 18 at 18:02"। */
    private static String whenStr(long at) {
        if (at <= 0) {
            return "";
        }
        long diff = System.currentTimeMillis() - at;
        if (diff < 60 * 60 * 1000L) {
            long m = Math.max(1, diff / 60000L);
            return m + (m == 1 ? " minute ago" : " minutes ago");
        }
        long d = System.currentTimeMillis() / 86400000L - at / 86400000L;
        String clock;
        try {
            clock = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(at));
        } catch (Throwable t) {
            clock = "";
        }
        if (d == 0) {
            long h = diff / 3600000L;
            return h + (h == 1 ? " hour ago" : " hours ago");
        }
        if (d == 1) {
            return "Yesterday at " + clock;
        }
        try {
            return new java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
                    .format(new java.util.Date(at)) + " at " + clock;
        } catch (Throwable t) {
            return "";
        }
    }
}
