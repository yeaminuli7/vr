package com.mytube.web;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * ইউটিউব-ধাঁচের ডাউনলোড-স্ক্রিন: প্রতিটি ভিডিও 16:9 থাম্বনেইল + দৈর্ঘ্য-ব্যাজ
 * সহ কার্ড হিসেবে, নিচে চ্যানেল · সাইজ, ডানে তিন-বিন্দু মেনু (Play/Share/Delete)।
 *
 * থাম্বনেইল ফাইল থেকেই তোলা হয় (MediaMetadataRetriever), তাই অফলাইনেও কাজ করে
 * এবং আগে নামানো ভিডিওতেও থাম্বনেইল দেখা যায়।
 *
 * লিস্টটা প্রতি ০.৫ সেকেন্ডে নতুন করে বানানো হয় না — শুধু প্রগ্রেস-লাইনগুলো
 * আপডেট হয়; জব/ফাইলের সেট বদলালেই পুরো রিবিল্ড।
 */
public final class DownloadsSheet {

    private DownloadsSheet() {
    }

    private static final int TH_W = 128;  // dp, 16:9
    private static final int TH_H = 72;
    private static final int JOB_W = 108;
    private static final int JOB_H = 61;

    /** প্রতি টিক-এ আপডেট হওয়া কাজগুলো। */
    private static final ArrayList<Runnable> TICKERS = new ArrayList<Runnable>();
    private static String sig;

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

            final Handler h = new Handler(Looper.getMainLooper());
            final Runnable tick = new Runnable() {
                @Override
                public void run() {
                    if (!dg.isShowing()) {
                        return;
                    }
                    String now = signature(a);
                    if (now == null || !now.equals(sig)) {
                        sig = now;
                        rebuild(a, box);
                    } else {
                        for (int i = 0; i < TICKERS.size(); i++) {
                            try {
                                TICKERS.get(i).run();
                            } catch (Throwable t) {
                            }
                        }
                    }
                    h.postDelayed(this, 500);
                }
            };
            dg.setContentView(sv);
            dg.setCanceledOnTouchOutside(true);
            Window win = dg.getWindow();
            if (win != null) {
                win.setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(0xFF0F0F0F));
                win.setLayout(-1, -1);
            }
            dg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    h.removeCallbacks(tick);
                    TICKERS.clear();
                    sig = null;
                }
            });
            dg.show();
            h.post(tick);
        } catch (Throwable t) {
        }
    }

    /** জব + ফাইলের সেট — এটা বদলালেই লিস্ট নতুন করে বানাতে হবে। */
    private static String signature(Context c) {
        StringBuilder b = new StringBuilder();
        try {
            ArrayList<DownloadService.Snap> sn = DownloadService.snapshot();
            for (int i = 0; i < sn.size(); i++) {
                b.append(sn.get(i).slot).append(',');
            }
            b.append('|');
            ArrayList<Object[]> dls = MainActivity.queryDownloads(c);
            for (int i = 0; i < dls.size(); i++) {
                b.append(dls.get(i)[0]).append(':').append(dls.get(i)[1]).append(',');
            }
        } catch (Throwable t) {
            return null;
        }
        return b.toString();
    }

    private static void rebuild(final Activity a, LinearLayout box) {
        try {
            TICKERS.clear();
            box.removeAllViews();

            ArrayList<DownloadService.Snap> snaps = DownloadService.snapshot();
            ArrayList<Object[]> dls = MainActivity.queryDownloads(a);

            // ---- শিরোনাম + স্টোরেজ ----
            TextView title = new TextView(a);
            title.setText("Downloads");
            title.setTextSize(24);
            title.setTypeface(null, 1);
            title.setTextColor(0xFFF2F2F2);
            box.addView(title);

            long used = 0;
            for (int i = 0; i < dls.size(); i++) {
                used += ((Long) dls.get(i)[1]).longValue();
            }
            TextView st = new TextView(a);
            st.setText(storageLine(used));
            st.setTextColor(0xFF9A9A9A);
            st.setTextSize(12);
            st.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 10));
            box.addView(st);

            if (snaps.isEmpty() && dls.isEmpty()) {
                box.addView(emptyState(a));
                return;
            }

            // ---- চলমান / সদ্য শেষ ----
            if (!snaps.isEmpty()) {
                box.addView(Ui.sectionLabel(a, "DOWNLOADING"));
                for (int i = 0; i < snaps.size(); i++) {
                    box.addView(jobCard(a, snaps.get(i)));
                    if (i < snaps.size() - 1) {
                        box.addView(Ui.hair(a));
                    }
                }
                if (!dls.isEmpty()) {
                    box.addView(Ui.hair(a));
                }
            }

            // ---- নামানো ভিডিও ----
            if (!dls.isEmpty()) {
                box.addView(Ui.sectionLabel(a, "DOWNLOADED"));
                for (int i = 0; i < dls.size(); i++) {
                    box.addView(fileCard(a, dls.get(i)));
                    if (i < dls.size() - 1) {
                        box.addView(Ui.hair(a));
                    }
                }
            }
        } catch (Throwable t) {
        }
    }

    // ------------------------------------------------------------------
    // কার্ড
    // ------------------------------------------------------------------

    /** নামানো ফাইলের কার্ড: থাম্বনেইল + দৈর্ঘ্য, শিরোনাম, চ্যানেল · সাইজ, মেনু। */
    private static View fileCard(final Activity a, final Object[] d) {
        final String name = (String) d[0];
        final long size = ((Long) d[1]).longValue();
        final Uri uri = (Uri) d[3];
        final boolean audio = isAudio(name);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));

        // -- থাম্বনেইল (16:9) + দৈর্ঘ্য-ব্যাজ --
        FrameLayout th = new FrameLayout(a);
        th.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, TH_W), Ui.dp(a, TH_H)));

        final ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Ui.roundRect(0xFF26262A, 4));
        iv.setClipToOutline(true);
        iv.setImageDrawable(Ui.placeholder(a, audio));
        th.addView(iv, new FrameLayout.LayoutParams(-1, -1));

        final TextView dur = new TextView(a);
        dur.setText("");
        dur.setTextSize(10);
        dur.setTextColor(0xFFF2F2F5);
        dur.setBackground(Ui.roundRect(0xCC000000, 2));
        dur.setPadding(Ui.dp(a, 4), Ui.dp(a, 1), Ui.dp(a, 4), Ui.dp(a, 1));
        dur.setVisibility(View.GONE);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.END);
        dlp.setMargins(0, 0, Ui.dp(a, 4), Ui.dp(a, 4));
        th.addView(dur, dlp);
        row.addView(th);

        // -- টেক্সট-কলাম --
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
        clp.leftMargin = Ui.dp(a, 12);
        col.setLayoutParams(clp);

        TextView t1 = new TextView(a);
        t1.setText(prettyName(name));
        t1.setTextColor(0xFFF2F2F5);
        t1.setTextSize(14);
        t1.setMaxLines(2);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t1);

        JSONObject m = DlMeta.get(a, name);
        String ch = m != null ? m.optString("c", "") : "";
        String q = m != null ? m.optString("q", "") : "";
        String sub = Ui.sizeStr(size);
        if (q.length() > 0 && !name.contains("[" + q + "]")) {
            sub = sub + "  ·  " + q;
        }
        if (ch.length() > 0) {
            sub = ch + "  ·  " + sub;
        }

        LinearLayout subRow = new LinearLayout(a);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        Ui.CheckIcon ck = new Ui.CheckIcon(a);
        subRow.addView(ck, new LinearLayout.LayoutParams(Ui.dp(a, 13), Ui.dp(a, 13)));
        TextView t2 = new TextView(a);
        t2.setText(sub);
        t2.setTextColor(0xFF9A9A9A);
        t2.setTextSize(12);
        t2.setMaxLines(1);
        t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams t2lp = new LinearLayout.LayoutParams(0, -2, 1f);
        t2lp.leftMargin = Ui.dp(a, 4);
        subRow.addView(t2, t2lp);
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(-1, -2);
        srlp.topMargin = Ui.dp(a, 3);
        col.addView(subRow, srlp);
        row.addView(col);

        // -- তিন-বিন্দু মেনু --
        final View dots = new Ui.DotsIcon(a);
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 30));
        dlp2.leftMargin = Ui.dp(a, 2);
        dots.setLayoutParams(dlp2);
        dots.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(a, v, name, uri);
            }
        });
        row.addView(dots);

        // -- থাম্বনেইল + দৈর্ঘ্য আনা (ব্যাকগ্রাউন্ড) --
        DlThumb.load(a, name, uri, audio, new DlThumb.Callback() {
            @Override
            public void onInfo(DlThumb.Info info) {
                try {
                    if (info.bmp != null) {
                        iv.setImageBitmap(info.bmp);
                    }
                    if (info.durMs > 0) {
                        dur.setText(Ui.fmtDur(info.durMs / 1000L));
                        dur.setVisibility(View.VISIBLE);
                    }
                } catch (Throwable t) {
                }
            }
        });

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.openDownloaded(a, uri, name);
            }
        });
        return row;
    }

    /** চলমান জবের কার্ড: লাইভ প্রগ্রেস + Pause/Resume/Retry/Cancel। */
    private static View jobCard(final Activity a, final DownloadService.Snap s) {
        final boolean audio = s.mp3 > 0 || (s.v == null && s.a != null);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));

        // -- থাম্বনেইল-জায়গা (চলমান জবে ফাইল নেই তখনো) --
        FrameLayout th = new FrameLayout(a);
        th.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, JOB_W), Ui.dp(a, JOB_H)));
        final ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Ui.roundRect(0xFF26262A, 4));
        iv.setClipToOutline(true);
        th.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        if (s.vid != null && s.vid.length() == 11) {
            YtThumb.load(a, s.vid, iv);
        } else {
            iv.setImageDrawable(Ui.placeholder(a, audio));
        }

        if (!s.done) {
            // থাম্বনেইলের ওপর পাজ-চিহ্ন (চলমান অবস্থায়)
            final TextView ov = new TextView(a);
            ov.setText(s.paused ? "II" : "");
            ov.setTextColor(0xFFF2F2F5);
            ov.setTextSize(11);
            ov.setTypeface(null, 1);
            ov.setGravity(Gravity.CENTER);
            ov.setBackground(Ui.roundRect(0x99000000, 11));
            th.addView(ov, new FrameLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22), Gravity.CENTER));
            final TextView ov2 = ov;
            TICKERS.add(new Runnable() {
                @Override
                public void run() {
                    ov2.setText(s.paused ? "II" : "");
                }
            });
        }
        row.addView(th);

        // -- টেক্সট-কলাম --
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
        clp.leftMargin = Ui.dp(a, 12);
        col.setLayoutParams(clp);

        TextView t1 = new TextView(a);
        String nm = s.title != null ? s.title : "Download";
        if (s.label != null && s.label.length() > 0 && !nm.contains("[" + s.label + "]")) {
            nm = nm + "  [" + s.label + "]";
        }
        t1.setText(nm);
        t1.setTextColor(0xFFF2F2F5);
        t1.setTextSize(14);
        t1.setMaxLines(1);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t1);

        final TextView status = new TextView(a);
        status.setTextSize(12);
        status.setTextColor(0xFF9A9A9A);
        status.setMaxLines(1);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(status);

        final ProgressBar pb = new ProgressBar(a, null,
                android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        try {
            pb.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF242427));
        } catch (Throwable t) {
        }
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 4));
        plp.topMargin = Ui.dp(a, 6);
        col.addView(pb, plp);

        // -- নিয়ন্ত্রণ-বোতাম --
        LinearLayout ctl = new LinearLayout(a);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        if (!s.done) {
            if (s.phase != 3) {
                final Ui.ActionButton pp = new Ui.ActionButton(a, "Pause");
                pp.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            a.startService(new Intent(a, DownloadService.class)
                                    .setAction(s.paused ? DownloadService.ACT_RESUME
                                            : DownloadService.ACT_PAUSE)
                                    .putExtra(DownloadService.EXTRA_JID, s.slot));
                        } catch (Throwable t) {
                        }
                    }
                });
                ctl.addView(pp);
                TICKERS.add(new Runnable() {
                    @Override
                    public void run() {
                        pp.setText(s.paused ? "Resume" : "Pause");
                    }
                });
            }
            Ui.ActionButton cancel = new Ui.ActionButton(a, "Cancel");
            cancel.setTextColor(0xFF9A9A9A);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        a.startService(new Intent(a, DownloadService.class)
                                .setAction(DownloadService.ACT_CANCEL)
                                .putExtra(DownloadService.EXTRA_JID, s.slot));
                    } catch (Throwable t) {
                    }
                }
            });
            ctl.addView(cancel);
        } else if (s.failed) {
            Ui.ActionButton rt = new Ui.ActionButton(a, "Retry");
            rt.setTextColor(0xFF34D399);
            rt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Intent ri = new Intent(a, DownloadService.class)
                                .setAction(DownloadService.ACT_START)
                                .putExtra(DownloadService.EXTRA_TITLE, s.title)
                                .putExtra(DownloadService.EXTRA_LABEL, s.label)
                                .putExtra(DownloadService.EXTRA_VID, s.vid)
                                .putExtra(DownloadService.EXTRA_CH, s.ch);
                        if (s.v != null) {
                            ri.putExtra(DownloadService.EXTRA_V, s.v);
                        }
                        if (s.a != null) {
                            ri.putExtra(DownloadService.EXTRA_A, s.a);
                        }
                        if (s.direct != null) {
                            ri.putExtra(DownloadService.EXTRA_DIRECT, s.direct);
                        }
                        if (s.srt != null) {
                            ri.putExtra(DownloadService.EXTRA_SRT, s.srt);
                        }
                        if (s.mp3 > 0) {
                            ri.putExtra(DownloadService.EXTRA_MP3, s.mp3);
                        }
                        a.startService(ri);
                    } catch (Throwable t) {
                    }
                }
            });
            ctl.addView(rt);
        }
        row.addView(ctl);

        final DownloadService.Snap sf = s;
        TICKERS.add(new Runnable() {
            @Override
            public void run() {
                try {
                    int tint = 0xFF34D399;
                    if (sf.done) {
                        status.setText(sf.msg != null ? sf.msg : "");
                        status.setTextColor(sf.failed ? 0xFFE8635A : 0xFF34D399);
                        pb.setProgress(100);
                    } else {
                        status.setTextColor(0xFF9A9A9A);
                        status.setText(statusText(sf));
                        pb.setProgress(progressOf(sf));
                    }
                    pb.setProgressTintList(android.content.res.ColorStateList.valueOf(
                            sf.done && sf.failed ? 0xFFE8635A : tint));
                } catch (Throwable t) {
                }
            }
        });
        TICKERS.get(TICKERS.size() - 1).run();

        return row;
    }

    // ------------------------------------------------------------------
    // সহায়ক ভিউ
    // ------------------------------------------------------------------

    /** তিন-বিন্দু (ওভারফ্লো) আইকন — কোনো ইমোজি নয়, নিজে আঁকা। */
        /** "নামানো হয়েছে" টিক-চিহ্ন। */
        /** ছোট গোলাকার বোতাম (Pause/Cancel/Retry)। */
                private static View emptyState(Context c) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        b.setPadding(0, Ui.dp(c, 64), 0, 0);

        View icon = new DlGlyph(c);
        LinearLayout.LayoutParams ilp =
                new LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56));
        ilp.bottomMargin = Ui.dp(c, 18);
        b.addView(icon, ilp);

        TextView t1 = new TextView(c);
        t1.setText("No downloads yet");
        t1.setTextColor(0xFFF2F2F5);
        t1.setTextSize(16);
        t1.setTypeface(null, 1);
        b.addView(t1);

        TextView t2 = new TextView(c);
        t2.setText("Videos you save will show up here,\nready to play with no network.");
        t2.setTextColor(0xFF6F6F75);
        t2.setTextSize(12);
        t2.setGravity(Gravity.CENTER_HORIZONTAL);
        t2.setPadding(0, Ui.dp(c, 8), 0, 0);
        b.addView(t2);
        return b;
    }

    /** এম্পটি-স্টেটের ডাউনলোড-আইকন (নিচের দিকে তীর)। */
    private static final class DlGlyph extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        DlGlyph(Context c) {
            super(c);
            p.setColor(0xFF2A2A32);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            p.setStyle(Paint.Style.FILL);
            // তীরের মাথা
            android.graphics.Path pa = new android.graphics.Path();
            pa.moveTo(w * 0.5f, h * 0.95f);
            pa.lineTo(w * 0.16f, h * 0.52f);
            pa.lineTo(w * 0.36f, h * 0.52f);
            pa.lineTo(w * 0.36f, h * 0.14f);
            pa.lineTo(w * 0.64f, h * 0.14f);
            pa.lineTo(w * 0.64f, h * 0.52f);
            pa.lineTo(w * 0.84f, h * 0.52f);
            pa.close();
            cv.drawPath(pa, p);
            // নিচের রেখা
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, h * 0.055f));
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawLine(w * 0.16f, h * 0.99f, w * 0.84f, h * 0.99f, p);
        }
    }

            // ------------------------------------------------------------------
    // মেনু / ফরম্যাট
    // ------------------------------------------------------------------

    private static void showMenu(final Activity a, View anchor, final String name,
            final Uri uri) {
        try {
            PopupMenu pm = new PopupMenu(a, anchor);
            pm.getMenu().add(0, 1, 0, "Play");
            pm.getMenu().add(0, 2, 1, "Share");
            pm.getMenu().add(0, 3, 2, "Delete");
            pm.setOnMenuItemClickListener(
                    new PopupMenu.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(android.view.MenuItem item) {
                            int id = item.getItemId();
                            if (id == 1) {
                                MainActivity.openDownloaded(a, uri, name);
                            } else if (id == 2) {
                                share(a, uri, name);
                            } else if (id == 3) {
                                confirmDelete(a, uri, name);
                            }
                            return true;
                        }
                    });
            pm.show();
        } catch (Throwable t) {
            // PopupMenu নাও চলতে পারে — তখন ডিলিট-কনফার্ম দেখানোই যথেষ্ট
            confirmDelete(a, uri, name);
        }
    }

    private static void share(Context c, Uri uri, String name) {
        try {
            Intent sh = new Intent(Intent.ACTION_SEND);
            sh.setType(mimeOf(name));
            sh.putExtra(Intent.EXTRA_STREAM, uri);
            sh.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(Intent.createChooser(sh, name));
        } catch (Throwable t) {
        }
    }

    private static void confirmDelete(final Context c, final Uri uri, final String name) {
        try {
            new android.app.AlertDialog.Builder(c)
                    .setTitle("Delete download")
                    .setMessage(name)
                    .setPositiveButton("Delete",
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d2, int w2) {
                                    boolean ok = MainActivity.deleteDownloaded(c, uri, name);
                                    if (ok) {
                                        DlMeta.drop(c, name);
                                    }
                                    android.widget.Toast.makeText(c,
                                            ok ? "Deleted" : "Could not delete",
                                            android.widget.Toast.LENGTH_SHORT).show();
                                    sig = null; // পরের টিক-এ লিস্ট রিবিল্ড হবে
                                }
                            })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Throwable t) {
        }
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        if (n.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (n.endsWith(".srt")) {
            return "text/plain";
        }
        if (n.endsWith(".m4a")) {
            return "audio/mp4";
        }
        return "video/mp4";
    }

    private static boolean isAudio(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        return n.endsWith(".mp3") || n.endsWith(".m4a");
    }

    /** "Title [720p].mp4" → "Title [720p]" (এক্সটেনশন লুকানো)। */
    private static String prettyName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            return name.substring(0, dot);
        }
        return name;
    }

    private static String storageLine(long used) {
        try {
            java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            long free = dir != null ? dir.getUsableSpace() : -1L;
            if (free > 0) {
                return Ui.sizeStr(used) + " used  ·  " + Ui.sizeStr(free) + " free";
            }
        } catch (Throwable t) {
        }
        return Ui.sizeStr(used) + " used";
    }

            private static String statusText(DownloadService.Snap s) {
        if (s.done) {
            return s.msg != null ? s.msg : "";
        }
        if (s.paused && s.phase == 0) {
            return "Paused";
        }
        if (s.phase == 3) {
            return "Queued";
        }
        if (s.phase == 1) {
            return "Muxing video and audio";
        }
        if (s.phase == 2) {
            return "Converting to MP3";
        }
        double sec = Math.max(0.001, (System.currentTimeMillis() - s.t0) / 1000.0);
        double mbs = s.speed > 0 ? s.speed : s.got / 1048576.0 / sec;
        if (s.total > 0) {
            int pct = (int) Math.min(95, 95 * s.got / s.total);
            String eta = Ui.etaStr(s.got, s.total, mbs);
            return String.format(java.util.Locale.US,
                    "%d%%  ·  %.1f MB/s  ·  %.1f of %.1f MB%s",
                    pct, mbs, s.got / 1048576.0, s.total / 1048576.0,
                    eta != null ? "  ·  " + eta : "");
        }
        String eta2 = s.total > 0 ? Ui.etaStr(s.got, s.total, mbs) : null;
        return String.format(java.util.Locale.US, "%.1f MB  ·  %.1f MB/s%s",
                s.got / 1048576.0, mbs, eta2 != null ? "  ·  " + eta2 : "");
    }

    private static int progressOf(DownloadService.Snap s) {
        if (s.done) {
            return 100;
        }
        if (s.phase == 3) {
            return 0;
        }
        if (s.phase == 1) {
            return s.muxTotal > 0
                    ? (int) Math.min(99, 95 + 5 * s.muxWritten / s.muxTotal) : 96;
        }
        if (s.phase == 2) {
            return 97;
        }
        return s.total > 0 ? (int) Math.min(95, 95 * s.got / s.total) : 0;
    }

    }
