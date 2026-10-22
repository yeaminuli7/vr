package com.mytube.web;

/**
 * JNI-ব্রিজ: liblamejni.so (LAME 3.100, arm64) — PCM→MP3 আসল এনকোডিং।
 * ডাউনলোড-পথ: m4a → MediaCodec AAC-ডিকোড → PCM → LAME → .mp3।
 */
public final class Lame {

    private Lame() {
    }

    /** লাইব্রেরি লোড হয়েছে কি না (NativeLoader.ensure-এর পরে)। */
    public static boolean available() {
        return NativeLoader.loaded();
    }

    /** লোড ব্যর্থ হলে তার কারণ (ডায়াগনস্টিক-বার্তায় ব্যবহার)। */
    public static String loadError() {
        return NativeLoader.loaded() ? null : NativeLoader.error();
    }

    /** এনকোডার-ইনস্ট্যান্স; 0 = ব্যর্থ। */
    public static native long open(int inRate, int channels, int bitrate);

    /** ইন্টারলিভড PCM (samples = প্রতি-চ্যানেল সংখ্যা) → out; ফেরত লেখা বাইট। */
    public static native int encode(long h, short[] pcm, int samples, byte[] out);

    /** শেষ-বাকি স্যাম্পল বের করে; ফেরত বাইট। */
    public static native int flush(long h, byte[] out);

    /** ইনস্ট্যান্স বন্ধ। */
    public static native void close(long h);
}
