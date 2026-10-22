/* WebTube: FFmpeg JNI-ব্রিজ — নেটিভ mux (v+a→mp4) ও m4a→mp3 (libmp3lame)।
 * কাস্টম-মিনিমাল FFmpeg 7.1.1 (zig-ক্রস-বিল্ড, arm64)। LGPL। */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/opt.h>

static char last_err[512];

static void seterr(const char *fn, int r) {
    char tmp[256];
    av_strerror(r < 0 ? r : AVERROR(r), tmp, sizeof(tmp));
    snprintf(last_err, sizeof(last_err), "%s: %s", fn, tmp);
}

const char *wt_ff_err(void) {
    return last_err;
}

/* ---------- ১) রিমাক্স: v.part + a.part → out.mp4 (-c copy) ---------- */

static int do_mux(const char *v, const char *a, const char *out) {
    AVFormatContext *iv = NULL, *ia = NULL, *o = NULL;
    AVPacket *pv = NULL, *pa = NULL;
    int ret = -1;
    int vs = -1, as = -1, ovsi = -1, oasi = -1;
    int vh = 0, ah = 0; /* প্রতিটি প্রান্তে পেন্ডিং প্যাকেট আছে কি */

    if ((ret = avformat_open_input(&iv, v, NULL, NULL)) < 0) {
        seterr("open video", ret);
        goto done;
    }
    if ((ret = avformat_open_input(&ia, a, NULL, NULL)) < 0) {
        seterr("open audio", ret);
        goto done;
    }
    vs = av_find_best_stream(iv, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    as = av_find_best_stream(ia, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (vs < 0 || as < 0) {
        snprintf(last_err, sizeof(last_err), "no stream v=%d a=%d", vs, as);
        ret = -1;
        goto done;
    }
    if ((ret = avformat_alloc_output_context2(&o, NULL, NULL, out)) < 0) {
        seterr("out ctx", ret);
        goto done;
    }
    AVStream *ovs = avformat_new_stream(o, NULL);
    AVStream *oas = avformat_new_stream(o, NULL);
    if (!ovs || !oas) {
        snprintf(last_err, sizeof(last_err), "new stream");
        ret = -1;
        goto done;
    }
    avcodec_parameters_copy(ovs->codecpar, iv->streams[vs]->codecpar);
    ovs->codecpar->codec_tag = 0;
    ovs->time_base = iv->streams[vs]->time_base;
    avcodec_parameters_copy(oas->codecpar, ia->streams[as]->codecpar);
    oas->codecpar->codec_tag = 0;
    oas->time_base = ia->streams[as]->time_base;
    if (!(o->oformat->flags & AVFMT_NOFILE)) {
        if ((ret = avio_open(&o->pb, out, AVIO_FLAG_WRITE)) < 0) {
            seterr("avio", ret);
            goto done;
        }
    }
    if ((ret = avformat_write_header(o, NULL)) < 0) {
        seterr("header", ret);
        goto done;
    }
    pv = av_packet_alloc();
    pa = av_packet_alloc();
    if (!pv || !pa) {
        ret = -1;
        goto done;
    }
    while (1) {
        if (!vh) {
            if (av_read_frame(iv, pv) < 0) {
                vh = -1; /* শেষ */
            } else if (pv->stream_index != vs) {
                av_packet_unref(pv);
            } else {
                vh = 1;
            }
        }
        if (!ah) {
            if (av_read_frame(ia, pa) < 0) {
                ah = -1;
            } else if (pa->stream_index != as) {
                av_packet_unref(pa);
            } else {
                ah = 1;
            }
        }
        if (vh == -1 && ah == -1) {
            break;
        }
        AVPacket *pick;
        AVStream *ist;
        int oidx;
        int useV;
        if (vh != 1) {
            useV = 0;
        } else if (ah != 1) {
            useV = 1;
        } else {
            /* দুই ফাইলের time_base আলাদা — সেকেন্ডে রূপান্তর করে তুলনা */
            int64_t dv = pv->dts != AV_NOPTS_VALUE ? pv->dts : pv->pts;
            int64_t da = pa->dts != AV_NOPTS_VALUE ? pa->dts : pa->pts;
            double sv = (dv == AV_NOPTS_VALUE ? 0 : dv)
                    * av_q2d(iv->streams[vs]->time_base);
            double sa = (da == AV_NOPTS_VALUE ? 0 : da)
                    * av_q2d(ia->streams[as]->time_base);
            useV = sv <= sa;
        }
        if (useV) {
            pick = pv;
            ist = iv->streams[vs];
            oidx = ovs->index;
            vh = 0;
        } else {
            pick = pa;
            ist = ia->streams[as];
            oidx = oas->index;
            ah = 0;
        }
        av_packet_rescale_ts(pick, ist->time_base,
                o->streams[oidx]->time_base);
        pick->stream_index = oidx;
        pick->pos = -1;
        if ((ret = av_interleaved_write_frame(o, pick)) < 0) {
            seterr("write", ret);
            goto done;
        }
        av_packet_unref(pick);
    }
    if ((ret = av_write_trailer(o)) < 0) {
        seterr("trailer", ret);
        goto done;
    }
    ret = 0;
    last_err[0] = 0;
done:
    av_packet_free(&pv);
    av_packet_free(&pa);
    if (o) {
        if (o->pb) {
            avio_closep(&o->pb);
        }
        avformat_free_context(o);
    }
    if (iv) {
        avformat_close_input(&iv);
    }
    if (ia) {
        avformat_close_input(&ia);
    }
    return ret;
}

/* ---------- ২) m4a → mp3 (AAC-ডিকোড → swr → libmp3lame) ---------- */

static int do_mp3(const char *in, const char *out, int kbps) {
    AVFormatContext *ic = NULL, *oc = NULL;
    AVCodecContext *dc = NULL, *ec = NULL;
    SwrContext *swr = NULL;
    AVFrame *df = NULL, *ef = NULL;
    AVPacket *pkt = NULL;
    AVAudioFifo *fifo = NULL;
    int ret = -1;
    int is = -1;
    int64_t out_samples = 0;

    if ((ret = avformat_open_input(&ic, in, NULL, NULL)) < 0) {
        seterr("open", ret);
        goto done;
    }
    is = av_find_best_stream(ic, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (is < 0) {
        snprintf(last_err, sizeof(last_err), "no audio stream");
        ret = -1;
        goto done;
    }
    AVStream *ist = ic->streams[is];
    const AVCodec *dec = avcodec_find_decoder(ist->codecpar->codec_id);
    if (!dec) {
        snprintf(last_err, sizeof(last_err), "no decoder");
        ret = -1;
        goto done;
    }
    dc = avcodec_alloc_context3(dec);
    if ((ret = avcodec_parameters_to_context(dc, ist->codecpar)) < 0) {
        seterr("par2ctx", ret);
        goto done;
    }
    if ((ret = avcodec_open2(dc, dec, NULL)) < 0) {
        seterr("dec open", ret);
        goto done;
    }
    const AVCodec *enc = avcodec_find_encoder(AV_CODEC_ID_MP3);
    if (!enc) {
        snprintf(last_err, sizeof(last_err), "no mp3 encoder");
        ret = -1;
        goto done;
    }
    ec = avcodec_alloc_context3(enc);
    if (!ec->ch_layout.nb_channels) {
        av_channel_layout_default(&ec->ch_layout, dc->ch_layout.nb_channels
                ? dc->ch_layout.nb_channels : 2);
    } else {
        av_channel_layout_uninit(&ec->ch_layout);
        av_channel_layout_copy(&ec->ch_layout, &dc->ch_layout);
        if (!ec->ch_layout.nb_channels) {
            av_channel_layout_default(&ec->ch_layout, 2);
        }
    }
    ec->sample_rate = dc->sample_rate ? dc->sample_rate : 44100;
    ec->sample_fmt = enc->sample_fmts ? enc->sample_fmts[0] : AV_SAMPLE_FMT_S16P;
    ec->bit_rate = (int64_t) kbps * 1000;
    if ((ret = avcodec_open2(ec, enc, NULL)) < 0) {
        seterr("enc open", ret);
        goto done;
    }
    if ((ret = avformat_alloc_output_context2(&oc, NULL, NULL, out)) < 0) {
        seterr("out ctx", ret);
        goto done;
    }
    AVStream *ost = avformat_new_stream(oc, NULL);
    if (!ost) {
        ret = -1;
        goto done;
    }
    if ((ret = avcodec_parameters_from_context(ost->codecpar, ec)) < 0) {
        seterr("ctx2par", ret);
        goto done;
    }
    ost->time_base = (AVRational) {1, ec->sample_rate};
    if (!(oc->oformat->flags & AVFMT_NOFILE)) {
        if ((ret = avio_open(&oc->pb, out, AVIO_FLAG_WRITE)) < 0) {
            seterr("avio", ret);
            goto done;
        }
    }
    if ((ret = avformat_write_header(oc, NULL)) < 0) {
        seterr("header", ret);
        goto done;
    }
    swr = swr_alloc();
    if (!swr) {
        ret = -1;
        goto done;
    }
    {
        AVChannelLayout in_layout;
        if (dc->ch_layout.nb_channels) {
            av_channel_layout_copy(&in_layout, &dc->ch_layout);
        } else {
            av_channel_layout_default(&in_layout, 2);
        }
        av_opt_set_chlayout(swr, "in_chlayout", &in_layout, 0);
        av_opt_set_int(swr, "in_sample_rate", dc->sample_rate, 0);
        av_opt_set_sample_fmt(swr, "in_sample_fmt", dc->sample_fmt, 0);
        av_opt_set_chlayout(swr, "out_chlayout", &ec->ch_layout, 0);
        av_opt_set_int(swr, "out_sample_rate", ec->sample_rate, 0);
        av_opt_set_sample_fmt(swr, "out_sample_fmt", ec->sample_fmt, 0);
        av_channel_layout_uninit(&in_layout);
    }
    if ((ret = swr_init(swr)) < 0) {
        seterr("swr", ret);
        goto done;
    }
    fifo = av_audio_fifo_alloc(ec->sample_fmt, ec->ch_layout.nb_channels, ec->frame_size * 4);
    df = av_frame_alloc();
    pkt = av_packet_alloc();
    if (!fifo || !df || !pkt) {
        ret = -1;
        goto done;
    }
    ef = av_frame_alloc();
    ef->format = ec->sample_fmt;
    ef->sample_rate = ec->sample_rate;
    av_channel_layout_copy(&ef->ch_layout, &ec->ch_layout);
    ef->nb_samples = ec->frame_size;
    if ((ret = av_frame_get_buffer(ef, 0)) < 0) {
        seterr("frame buf", ret);
        goto done;
    }
    int eof = 0;
    while (!eof) {
        if ((ret = av_read_frame(ic, pkt)) < 0) {
            eof = 1;
            avcodec_send_packet(dc, NULL);
        } else {
            if (pkt->stream_index == is) {
                avcodec_send_packet(dc, pkt);
            }
            av_packet_unref(pkt);
        }
        while (avcodec_receive_frame(dc, df) >= 0) {
            uint8_t **conv = NULL;
            /* এই ফ্রেমের আউটপুট আলাদা বাফারে */
            int outn = swr_get_out_samples(swr, df->nb_samples);
            if (outn <= 0) {
                outn = df->nb_samples;
            }
            conv = av_mallocz(sizeof(uint8_t *) * ec->ch_layout.nb_channels);
            int ch = ec->ch_layout.nb_channels;
            int line = av_samples_get_buffer_size(NULL, ch, outn, ec->sample_fmt, 0);
            for (int c = 0; c < ch; c++) {
                conv[c] = av_mallocz(line ? line : 1);
            }
            int got = swr_convert(swr, conv, outn,
                    (const uint8_t **) df->extended_data, df->nb_samples);
            if (got > 0) {
                av_audio_fifo_write(fifo, (void **) conv, got);
            }
            for (int c = 0; c < ch; c++) {
                av_free(conv[c]);
            }
            av_free(conv);
            av_frame_unref(df);
            /* ফুল ফ্রেম এনকোড */
            while (av_audio_fifo_size(fifo) >= ec->frame_size) {
                av_audio_fifo_read(fifo, (void **) ef->data, ec->frame_size);
                ef->pts = out_samples;
                out_samples += ec->frame_size;
                avcodec_send_frame(ec, ef);
                while (avcodec_receive_packet(ec, pkt) >= 0) {
                    av_packet_rescale_ts(pkt, (AVRational) {1, ec->sample_rate},
                            ost->time_base);
                    pkt->stream_index = ost->index;
                    if (av_interleaved_write_frame(oc, pkt) < 0) {
                        seterr("write", -1);
                        goto done;
                    }
                    av_packet_unref(pkt);
                }
            }
        }
    }
    /* swr-এর ভেতরের বাকি স্যাম্পল */
    {
        int ch = ec->ch_layout.nb_channels;
        for (;;) {
            int avail = swr_get_out_samples(swr, 0);
            if (avail <= 0) {
                break;
            }
            uint8_t **conv = av_mallocz(sizeof(uint8_t *) * ch);
            int line = av_samples_get_buffer_size(NULL, ch, avail, ec->sample_fmt, 0);
            for (int c = 0; c < ch; c++) {
                conv[c] = av_mallocz(line ? line : 1);
            }
            int got = swr_convert(swr, conv, avail, NULL, 0);
            if (got > 0) {
                av_audio_fifo_write(fifo, (void **) conv, got);
            }
            for (int c = 0; c < ch; c++) {
                av_free(conv[c]);
            }
            av_free(conv);
            if (got <= 0) {
                break;
            }
        }
    }
    /* শেষ ছোট ফ্রেম */
    while (av_audio_fifo_size(fifo) > 0) {
        int n = FFMIN(av_audio_fifo_size(fifo), ec->frame_size);
        av_frame_unref(ef);
        ef->format = ec->sample_fmt;
        ef->sample_rate = ec->sample_rate;
        av_channel_layout_uninit(&ef->ch_layout);
        av_channel_layout_copy(&ef->ch_layout, &ec->ch_layout);
        ef->nb_samples = n;
        if ((ret = av_frame_get_buffer(ef, 0)) < 0) {
            seterr("frame buf2", ret);
            goto done;
        }
        av_audio_fifo_read(fifo, (void **) ef->data, n);
        ef->pts = out_samples;
        out_samples += n;
        avcodec_send_frame(ec, ef);
        while (avcodec_receive_packet(ec, pkt) >= 0) {
            av_packet_rescale_ts(pkt, (AVRational) {1, ec->sample_rate},
                    ost->time_base);
            pkt->stream_index = ost->index;
            if (av_interleaved_write_frame(oc, pkt) < 0) {
                seterr("write", -1);
                goto done;
            }
            av_packet_unref(pkt);
        }
    }
    avcodec_send_frame(ec, NULL);
    while (avcodec_receive_packet(ec, pkt) >= 0) {
        av_packet_rescale_ts(pkt, (AVRational) {1, ec->sample_rate}, ost->time_base);
        pkt->stream_index = ost->index;
        av_interleaved_write_frame(oc, pkt);
        av_packet_unref(pkt);
    }
    if ((ret = av_write_trailer(oc)) < 0) {
        seterr("trailer", ret);
        goto done;
    }
    ret = 0;
    last_err[0] = 0;
done:
    av_frame_free(&df);
    av_frame_free(&ef);
    av_packet_free(&pkt);
    if (fifo) {
        av_audio_fifo_free(fifo);
    }
    swr_free(&swr);
    avcodec_free_context(&dc);
    avcodec_free_context(&ec);
    if (oc) {
        if (oc->pb) {
            avio_closep(&oc->pb);
        }
        avformat_free_context(oc);
    }
    if (ic) {
        avformat_close_input(&ic);
    }
    return ret;
}

/* ---------- JNI ---------- */

JNIEXPORT jint JNICALL
Java_com_mytube_web_Ffmpeg_mux(JNIEnv *env, jobject th, jstring jv, jstring ja, jstring jo) {
    const char *v = (*env)->GetStringUTFChars(env, jv, NULL);
    const char *a = (*env)->GetStringUTFChars(env, ja, NULL);
    const char *o = (*env)->GetStringUTFChars(env, jo, NULL);
    int r = do_mux(v, a, o);
    (*env)->ReleaseStringUTFChars(env, jv, v);
    (*env)->ReleaseStringUTFChars(env, ja, a);
    (*env)->ReleaseStringUTFChars(env, jo, o);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_mytube_web_Ffmpeg_mp3(JNIEnv *env, jobject th, jstring ji, jstring jo, jint kbps) {
    const char *i = (*env)->GetStringUTFChars(env, ji, NULL);
    const char *o = (*env)->GetStringUTFChars(env, jo, NULL);
    int r = do_mp3(i, o, kbps);
    (*env)->ReleaseStringUTFChars(env, ji, i);
    (*env)->ReleaseStringUTFChars(env, jo, o);
    return r;
}

JNIEXPORT jstring JNICALL
Java_com_mytube_web_Ffmpeg_err(JNIEnv *env, jobject th) {
    return (*env)->NewStringUTF(env, last_err[0] ? last_err : "ok");
}
