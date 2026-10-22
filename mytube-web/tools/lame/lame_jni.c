/* WebTube: ছোট JNI-ব্রিজ — libmp3lame PCM→MP3 এনকোডিং। (LGPL, LAME 3.100) */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "lame.h"

JNIEXPORT jlong JNICALL
Java_com_mytube_web_Lame_open(JNIEnv *env, jobject thiz,
        jint inRate, jint channels, jint bitrate) {
    lame_global_flags *gfp = lame_init();
    if (!gfp) return 0;
    lame_set_in_samplerate(gfp, inRate);
    lame_set_out_samplerate(gfp, inRate);
    lame_set_num_channels(gfp, channels);
    lame_set_brate(gfp, bitrate);
    lame_set_quality(gfp, 2);
    if (channels == 1) {
        lame_set_mode(gfp, MONO);
    }
    lame_set_bWriteVbrTag(gfp, 0);
    if (lame_init_params(gfp) < 0) {
        lame_close(gfp);
        return 0;
    }
    return (jlong) (intptr_t) gfp;
}

/* interleaved PCM (samples = মোট-নয়, প্রতি-চ্যানেল স্যাম্পল-সংখ্যা) → out; ফেরত বাইট */
JNIEXPORT jint JNICALL
Java_com_mytube_web_Lame_encode(JNIEnv *env, jobject thiz, jlong h,
        jshortArray pcm, jint samples, jbyteArray out) {
    lame_global_flags *gfp = (lame_global_flags *) (intptr_t) h;
    if (!gfp || samples <= 0) return 0;
    jshort *p = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    if (!p) return -1;
    short *L = (short *) malloc(sizeof(short) * (size_t) samples);
    short *R = (short *) malloc(sizeof(short) * (size_t) samples);
    if (!L || !R) {
        free(L); free(R);
        (*env)->ReleasePrimitiveArrayCritical(env, pcm, p, JNI_ABORT);
        return -1;
    }
    int ch = lame_get_num_channels(gfp);
    for (int i = 0; i < samples; i++) {
        if (ch >= 2) {
            L[i] = p[i * 2];
            R[i] = p[i * 2 + 1];
        } else {
            L[i] = p[i];
            R[i] = p[i];
        }
    }
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, p, JNI_ABORT);
    jbyte *o = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!o) { free(L); free(R); return -1; }
    jsize ocap = (*env)->GetArrayLength(env, out);
    int n = lame_encode_buffer(gfp, L, R, samples,
            (unsigned char *) o, (int) ocap);
    (*env)->ReleasePrimitiveArrayCritical(env, out, o, 0);
    free(L);
    free(R);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_mytube_web_Lame_flush(JNIEnv *env, jobject thiz, jlong h, jbyteArray out) {
    lame_global_flags *gfp = (lame_global_flags *) (intptr_t) h;
    if (!gfp) return 0;
    jbyte *o = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!o) return -1;
    jsize ocap = (*env)->GetArrayLength(env, out);
    int n = lame_encode_flush(gfp, (unsigned char *) o, (int) ocap);
    (*env)->ReleasePrimitiveArrayCritical(env, out, o, 0);
    return n;
}

JNIEXPORT void JNICALL
Java_com_mytube_web_Lame_close(JNIEnv *env, jobject thiz, jlong h) {
    lame_global_flags *gfp = (lame_global_flags *) (intptr_t) h;
    if (gfp) lame_close(gfp);
}
