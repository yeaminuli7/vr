package com.mytube.web;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * ইউটিউবের বাইরের লিংকের জন্য ডাউনলোড-অপশন শিট।
 *
 * <p>ইউটিউবে ডাউনলোড চাপলে যেমন কোয়ালিটি/ফরম্যাট বেছে নেওয়ার অপশন আসে,
 * এখানেও তেমনি — পেজটি যে যে মিডিয়া-ফাইল লোড করে সেগুলোই অপশন হিসেবে
 * দেখানো হয় (Video · MP4 · 12.4 MB / Audio · M4A · 1.8 MB)। ফাইল একাধিক
 * কোয়ালিটিতে থাকলে সেগুলো আলাদা সারি হয়।
 *
 * <p>পেজ লোড হতে সময় লাগে, তাই শিট আগেই খোলে ("Searching for media..."),
 * ফাইল পাওয়া গেলে সারি যোগ হয়, আর কিছু না পেলে পরিষ্কার বার্তা দেখায়।
 */
public class MediaOptionsSheet {

    /** শিট কোন উৎস থেকে অপশন নেবে এবং কোনটা বেছে নেওয়া হলো। */
    public interface Source {
        java.util.ArrayList<MediaSniffer.M> list();

        /** খোঁজা শেষ হয়েছে? (আর অপেক্ষা করার দরকার নেই) */
        boolean done();
    }

    public interface OnPick {
        void pick(MediaSniffer.M m);
    }

    private static volatile AlertDialog current;

    public static void dismiss() {
        AlertDialog d = current;
        current = null;
        try {
            if (d != null && d.isShowing()) {
                d.dismiss();
            }
        } catch (Throwable t) {
        }
    }

    public static boolean isShowing() {
        AlertDialog d = current;
        try {
            return d != null && d.isShowing();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void show(final Activity a, final String pageTitle,
                            final Source src, final OnPick onPick) {
        show(a, pageTitle, src, onPick, "Resolving link...");
    }

    /**
     * @param emptyHint ফাইল না পাওয়া পর্যন্ত দেখানোর লেখা — যেটা সত্যি সেটাই:
     *                  সরাসরি ভিডিও-লিংকে "Resolving link...",
     *                  পেজ-স্ক্যানে "Searching for media...".
     */
    public static void show(final Activity a, final String pageTitle,
                            final Source src, final OnPick onPick,
                            final String emptyHint) {
        final String hint = emptyHint;
        if (a == null || a.isFinishing()) {
            return;
        }
        dismiss();
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 18);
        box.setPadding(p, Ui.dp(a, 16), p, Ui.dp(a, 12));
        box.setBackgroundColor(0xFF121214);

        // ---- শিরোনাম ----
        TextView t = new TextView(a);
        t.setText("Download");
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(17);
        t.setTypeface(null, 1);
        box.addView(t);

        TextView sub = new TextView(a);
        sub.setText(pageTitle == null || pageTitle.length() == 0 ? "This page" : pageTitle);
        sub.setTextColor(0xFF8E8E96);
        sub.setTextSize(12);
        sub.setMaxLines(2);
        sub.setPadding(0, Ui.dp(a, 3), 0, Ui.dp(a, 10));
        box.addView(sub);

        // ---- অপশন-তালিকা ----
        final LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        box.addView(rows, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(a);
        note.setTextColor(0xFF8E8E96);
        note.setTextSize(11);
        note.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 6));
        box.addView(note);

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        final AlertDialog d = new AlertDialog.Builder(a)
                .setView(sv)
                .setNegativeButton("Close", null)
                .create();
        try {
            if (d.getWindow() != null) {
                d.getWindow().setBackgroundDrawable(
                        Ui.roundRect(0xFF121214, 4));
            }
        } catch (Throwable t2) {
        }
        current = d;
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface di) {
                if (current == d) {
                    current = null;
                }
            }
        });
        d.show();

        // ---- লাইভ রিফ্রেশ: ফাইল পেলেই নতুন সারি ----
        final View poster = d.getWindow() != null ? d.getWindow().getDecorView() : box;
        final Runnable[] tick = new Runnable[1];
        final long start = System.currentTimeMillis();
        tick[0] = new Runnable() {
            @Override
            public void run() {
                if (!isShowing()) {
                    return;
                }
                try {
                    render(a, rows, note, src, onPick, hint);
                } catch (Throwable e) {
                }
                boolean finished = src.done()
                        || (System.currentTimeMillis() - start) > 15000L;
                if (!finished) {
                    poster.postDelayed(tick[0], 600);
                } else {
                    try {
                        renderFinal(a, rows, note, src, onPick, hint);
                    } catch (Throwable e) {
                    }
                }
            }
        };
        poster.post(tick[0]);
    }

    private static void render(Activity a, LinearLayout rows, TextView note,
                               Source src, OnPick onPick, String emptyHint) {
        java.util.ArrayList<MediaSniffer.M> ms = src.list();
        if (rows.getTag() != null && ((Integer) rows.getTag()).intValue() == ms.size()) {
            note.setText(ms.size() == 0 ? emptyHint : "");
            return; // একই সংখ্যক সারি — আবার আঁকার দরকার নেই
        }
        rows.setTag(Integer.valueOf(ms.size()));
        rows.removeAllViews();
        for (int i = 0; i < ms.size(); i++) {
            rows.addView(row(a, ms.get(i), src, onPick));
        }
        note.setText(ms.size() == 0 ? emptyHint : "");
    }

    private static void renderFinal(Activity a, LinearLayout rows, TextView note,
                                    Source src, OnPick onPick, String emptyHint) {
        java.util.ArrayList<MediaSniffer.M> ms = src.list();
        rows.setTag(null);
        render(a, rows, note, src, onPick, emptyHint);
        if (ms.size() == 0) {
            note.setText("No downloadable file found.\n\n"
                    + "This site plays its video in protected pieces, so there is "
                    + "nothing to save. You can still watch it here.");
        }
    }

    /** একটি অপশন-সারি — ট্যাপে সেটি ডাউনলোড হয়। */
    private static View row(final Activity a, final MediaSniffer.M m,
                            Source src, final OnPick onPick) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(a, 12), Ui.dp(a, 11), Ui.dp(a, 12), Ui.dp(a, 11));
        r.setBackground(roundRow());

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(a);
        title.setText((m.audio ? "Audio" : "Video") + "  ·  " + m.ext.toUpperCase());
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(14);
        col.addView(title);

        TextView meta = new TextView(a);
        String size = m.sizeStr();
        meta.setText(m.host + (size != null ? "  ·  " + size : ""));
        meta.setTextColor(0xFF8E8E96);
        meta.setTextSize(11);
        meta.setSingleLine(true);
        col.addView(meta);

        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView dl = new TextView(a);
        dl.setText("DOWNLOAD");
        dl.setTextColor(0xFF0F0F0F);
        dl.setTextSize(11);
        dl.setTypeface(null, 1);
        dl.setLetterSpacing(0.06f);
        dl.setGravity(Gravity.CENTER);
        dl.setBackground(Ui.roundRect(0xFFF2F2F5, 3));
        dl.setPadding(Ui.dp(a, 12), Ui.dp(a, 6), Ui.dp(a, 12), Ui.dp(a, 6));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.leftMargin = Ui.dp(a, 10);
        r.addView(dl, dlp);

        View.OnClickListener go = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    dismiss();
                    onPick.pick(m);
                } catch (Throwable e) {
                }
            }
        };
        r.setOnClickListener(go);
        dl.setOnClickListener(go);
        return r;
    }

    private static GradientDrawable roundRow() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1C1C20);
        g.setCornerRadius(22f);
        return g;
    }
}
