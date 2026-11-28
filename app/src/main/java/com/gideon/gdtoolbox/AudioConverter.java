package com.gideon.gdtoolbox;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import io.element.android.opusencoder.OggOpusEncoder;
import io.element.android.opusencoder.configuration.SampleRate;

/**
 * Converts any decodable audio file into a WhatsApp voice-note-spec file:
 * OGG container, Opus codec, mono, 16 kHz. This is the format WhatsApp uses
 * for push-to-talk (PTT) voice notes.
 *
 * All native work (libopusenc) happens in THIS app's own process, where
 * System.loadLibrary resolves normally — not inside WhatsApp.
 */
public final class AudioConverter {

    public static final int TARGET_RATE = 16000; // WhatsApp PTT = 16 kHz mono
    public static final int TARGET_BITRATE = 24000;

    private AudioConverter() {}

    /** @return the produced .opus file. */
    public static File convert(Context ctx, Uri input, File outFile) throws Exception {
        short[] mono16k = decodeToMono16k(ctx, input);
        encodeOpus(mono16k, outFile);
        return outFile;
    }

    // ---- 1. Decode -> PCM, downmix to mono, resample to 16 kHz ----
    private static short[] decodeToMono16k(Context ctx, Uri input) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(ctx, input, null);

        int track = -1;
        MediaFormat inFmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { track = i; inFmt = f; break; }
        }
        if (track < 0) { ex.release(); throw new IllegalArgumentException("No audio track found"); }
        ex.selectTrack(track);

        String mime = inFmt.getString(MediaFormat.KEY_MIME);
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(inFmt, null, null, 0);
        codec.start();

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean sawInputEos = false, sawOutputEos = false;
        int srcRate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int srcCh = inFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

        while (!sawOutputEos) {
            if (!sawInputEos) {
                int inIdx = codec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer ib = codec.getInputBuffer(inIdx);
                    int size = ex.readSampleData(ib, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEos = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            int outIdx = codec.dequeueOutputBuffer(info, 10000);
            if (outIdx >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEos = true;
                if (info.size > 0) {
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    byte[] chunk = new byte[info.size];
                    ob.get(chunk);
                    pcm.write(chunk);
                }
                codec.releaseOutputBuffer(outIdx, false);
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                    srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    srcCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            }
        }
        codec.stop();
        codec.release();
        ex.release();

        // bytes -> shorts (16-bit LE)
        byte[] raw = pcm.toByteArray();
        ShortBuffer sb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        short[] interleaved = new short[sb.remaining()];
        sb.get(interleaved);

        // downmix to mono
        short[] mono;
        if (srcCh <= 1) {
            mono = interleaved;
        } else {
            int frames = interleaved.length / srcCh;
            mono = new short[frames];
            for (int i = 0; i < frames; i++) {
                int acc = 0;
                for (int c = 0; c < srcCh; c++) acc += interleaved[i * srcCh + c];
                mono[i] = (short) (acc / srcCh);
            }
        }

        // resample to 16 kHz (linear interpolation)
        if (srcRate == TARGET_RATE) return mono;
        return resampleLinear(mono, srcRate, TARGET_RATE);
    }

    private static short[] resampleLinear(short[] in, int srcRate, int dstRate) {
        if (in.length == 0) return in;
        long outLen = (long) in.length * dstRate / srcRate;
        short[] out = new short[(int) outLen];
        double step = (double) srcRate / dstRate;
        double pos = 0;
        for (int i = 0; i < out.length; i++) {
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, in.length - 1);
            double frac = pos - i0;
            out[i] = (short) ((1 - frac) * in[i0] + frac * in[i1]);
            pos += step;
        }
        return out;
    }

    // ---- 2. Encode PCM -> OGG/Opus via Element libopusenc wrapper ----
    private static void encodeOpus(short[] mono16k, File outFile) {
        OggOpusEncoder enc = OggOpusEncoder.Companion.create();
        enc.init(outFile.getAbsolutePath(), SampleRate.Rate16kHz.INSTANCE);
        enc.setBitrate(TARGET_BITRATE);
        int chunk = 4096; // libopusenc buffers/framing internally
        for (int off = 0; off < mono16k.length; off += chunk) {
            int n = Math.min(chunk, mono16k.length - off);
            short[] buf;
            if (off == 0 && n == mono16k.length) {
                buf = mono16k;
            } else {
                buf = new short[n];
                System.arraycopy(mono16k, off, buf, 0, n);
            }
            enc.encode(buf, n);
        }
        enc.release();
    }
}
