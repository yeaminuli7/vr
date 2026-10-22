package com.mytube.web;

/**
 * JNI-ব্রিজ: কাস্টম-মিনিমাল FFmpeg 7.1.1 (arm64) — নেটিভ গতিতে
 * রিমাক্স (v+a→MP4) ও m4a→MP3 (libmp3lame-সহ)।
 */
public final class Ffmpeg {

    private Ffmpeg() {
    }

    public static boolean available() {
        return NativeLoader.loaded();
    }

    /** লোড-ব্যর্থতার কারণ (ডায়াগনস্টিক-বার্তায়)। */
    public static String loadError() {
        return NativeLoader.loaded() ? null : NativeLoader.error();
    }

    /** v.part + a.part → out.mp4 (রি-এনকোড ছাড়া)। 0 = সফল। */
    public static native int mux(String v, String a, String out);

    /** m4a → out.mp3 (kbps বিটরেটে)। 0 = সফল। */
    public static native int mp3(String in, String out, int kbps);

    /** শেষ ত্রুটির বর্ণনা। */
    public static native String err();
}
