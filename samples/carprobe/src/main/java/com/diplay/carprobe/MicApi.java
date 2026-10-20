package com.diplay.carprobe;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Microphone spectrum probe: can the head unit hear its own speakers well enough to drive the
 * interior lamps from music?
 *
 * <p>The Bluetooth audio path is closed to third-party apps (no {@code CAPTURE_AUDIO_OUTPUT}, the
 * vendor spectrum service refuses {@code setSpectrumMode}), so the microphone is the last option for
 * a music-reactive effect on this unit. This probe measures how usable that is: band levels, the
 * noise floor and whether kicks show up as a clear low-band jump.
 */
final class MicApi {

    /** Band centres in Hz; low bands carry the beat, high bands the detail. */
    private static final double[] BANDS = {63, 125, 250, 500, 1000, 2000, 4000, 8000};
    private static final int SAMPLE_RATE = 48_000;
    private static final int WINDOW = 1024;
    private static final long REPORT_MILLIS = 200;

    private MicApi() {
    }

    static void start(Context context, int seconds, int source) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ProbeLog.i("mic probe needs RECORD_AUDIO: run"
                    + " 'pm grant com.diplay.carprobe android.permission.RECORD_AUDIO'");
            return;
        }
        int durationSeconds = Math.max(2, Math.min(120, seconds));
        ProbeLog.i("=== mic spectrum probe " + durationSeconds + " s source=" + source + " ===");
        new Thread(() -> run(context, durationSeconds, source), "carprobe-mic").start();
    }

    private static void run(Context context, int seconds, int requested) {
        AudioRecord record = null;
        int source = requested == 1 ? MediaRecorder.AudioSource.VOICE_RECOGNITION
                : requested == 2 ? MediaRecorder.AudioSource.MIC
                : MediaRecorder.AudioSource.UNPROCESSED;
        try {
            record = open(source);
            if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
                release(record);
                source = MediaRecorder.AudioSource.MIC;
                record = open(source);
            }
            if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
                ProbeLog.i("mic unavailable: no initialized AudioRecord for UNPROCESSED or MIC");
                return;
            }
            ProbeLog.i("mic source=" + (source == MediaRecorder.AudioSource.UNPROCESSED ? "UNPROCESSED" : "MIC")
                    + " rate=" + SAMPLE_RATE + " window=" + WINDOW
                    + " bands=" + BANDS.length);

            record.startRecording();
            short[] buffer = new short[WINDOW];
            long end = System.currentTimeMillis() + seconds * 1000L;
            long nextReport = 0L;
            double previousLow = 0;
            double noiseFloor = 0;
            int windows = 0;
            while (System.currentTimeMillis() < end) {
                int read = record.read(buffer, 0, buffer.length);
                if (read <= 0) continue;
                windows++;
                double rms = rms(buffer, read);
                double[] bands = bands(buffer, read);
                long now = System.currentTimeMillis();
                double low = bands[0] + bands[1];
                boolean beat = previousLow > 0 && low - previousLow > 6.0;
                previousLow = low;
                noiseFloor = windows == 1 ? rms : Math.min(noiseFloor, rms);
                if (now >= nextReport) {
                    nextReport = now + REPORT_MILLIS;
                    ProbeLog.i("mic rms=" + db(rms) + "dBFS peak=" + peak(buffer, read)
                            + "/32767 floor=" + db(noiseFloor)
                            + " bands=" + formatBands(bands) + (beat ? " BEAT" : ""));
                }
            }
            record.stop();
            ProbeLog.i("=== mic probe done windows=" + windows + " ===");
        } catch (Throwable error) {
            ProbeLog.e("mic probe failed", error);
        } finally {
            release(record);
        }
    }

    private static AudioRecord open(int source) {
        try {
            int minimum = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            return new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, WINDOW * 4));
        } catch (Throwable error) {
            ProbeLog.i("mic open(" + source + ") failed: " + ProbeLog.describe(HudApi.unwrap(error)));
            return null;
        }
    }

    private static void release(AudioRecord record) {
        if (record == null) return;
        try {
            record.release();
        } catch (Throwable ignored) {
            // Nothing left to release.
        }
    }

    private static int peak(short[] samples, int count) {
        int peak = 0;
        for (int i = 0; i < count; i++) {
            int value = Math.abs(samples[i]);
            if (value > peak) peak = value;
        }
        return peak;
    }

    private static double rms(short[] samples, int count) {
        double sum = 0;
        for (int i = 0; i < count; i++) {
            double value = samples[i] / 32768.0;
            sum += value * value;
        }
        return Math.sqrt(sum / Math.max(1, count));
    }

    /** Goertzel power per band, so a full FFT is not needed for eight centres. */
    private static double[] bands(short[] samples, int count) {
        double[] result = new double[BANDS.length];
        for (int band = 0; band < BANDS.length; band++) {
            double k = BANDS[band] * WINDOW / SAMPLE_RATE;
            double omega = 2 * Math.PI * k / WINDOW;
            double coefficient = 2 * Math.cos(omega);
            double s0 = 0;
            double s1 = 0;
            double s2 = 0;
            for (int i = 0; i < count; i++) {
                s0 = samples[i] / 32768.0 + coefficient * s1 - s2;
                s2 = s1;
                s1 = s0;
            }
            double power = s1 * s1 + s2 * s2 - coefficient * s1 * s2;
            result[band] = 10 * Math.log10(Math.max(1e-12, power / WINDOW / WINDOW));
        }
        return result;
    }

    private static String db(double value) {
        return String.format(java.util.Locale.US, "%.1f", 20 * Math.log10(Math.max(1e-9, value)));
    }

    private static String formatBands(double[] values) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) text.append(' ');
            text.append(BANDS[i] < 1000 ? String.valueOf((int) BANDS[i]) : ((int) BANDS[i] / 1000) + "k");
            text.append('=').append(String.format(java.util.Locale.US, "%.0f", values[i]));
        }
        return text.append(']').toString();
    }
}
