package com.diplay.carprobe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.audiofx.Visualizer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * Probe of the ECARX ambient-light (and HUD) surface through the wired car service.
 *
 * <p>The reverse-engineering puts the real controller in
 * {@code ecarx.car.hardware.vehicle.ECarXCarAmbliManager} (reads {@code getPA_*}, writes
 * {@code CB_*}). A directly constructed manager has no property base and fails; the wired way in
 * is {@code ECarXCar.getCarManager(PA_SERVICE).getECarXCarAmbliManager()} — and the same
 * {@code ECarXCarSetManager} also exposes {@code getECarXCarVfhudManager()} for the HUD.
 *
 * <p>On this head unit a normal third-party app really does connect to
 * {@code com.ecarx.car/.ECarXCarService}, so this probe reads through that path. The only write it
 * makes is a no-op on the ambient-light switch (it writes back the value it just read); HUD is
 * read only.
 */
final class AmbientApi {

    static final String CLS_AMBLI = "ecarx.car.hardware.vehicle.ECarXCarAmbliManager";
    static final String CLS_ECARXCAR = "ecarx.car.ECarXCar";
    static final String CLS_LAMP = "com.ecarx.xui.adaptapi.car.vehicle.Lamp";
    static final String CLS_LIGHT_SHOW = "com.ecarx.xui.adaptapi.lightshow.LightShowImpl";

    /** Gate: the ECARX car-property path is enabled only on the reverse-engineered FX11 unit. */
    static final String SUPPORTED_DEVICE = "msmnile_gvmq";
    static final String CLS_AMBLI_MOD = "ecarx.car.hardware.annotation.AmbLiMod";

    /** {@code ECarXCar.getCarManager} only knows these three service names. */
    private static final String[] SERVICE_CONSTANTS = {"PA_SERVICE", "SIGNAL_SERVICE", "CAR_HARDKEY_SERVICE"};

    private static final String[] AMBLI_READS = {
            "getPA_AmbLiAll", "getPA_MoodLightSwitch", "getPA_MoodLightColor",
            "getPA_AmbLiModSetting", "getPA_AmbLiMod_ColorStyleMode", "getPA_AmbLiMod_ThemeMode",
            "getPA_AmbLiMod_TimeMode", "getPA_AmbLiMod_CustomizedMode", "getPA_AmbLiMod_MusicShowMode",
            "getPA_AmbLiSwapEffectStatus", "getPA_SolidColorSettings", "getPA_BreatheColorSettings",
            "getPA_ThemeColor", "getPA_TransitionEffectSel", "getPA_CustomEffect",
            "getPA_Zone1ColorSettings", "getPA_Zone1StatusSettings", "getPA_ReadLightAllOnSwitch",
    };

    private static final String[] VFHUD_READS = {
            "getPA_VF_HUD_ActvSts", "getPA_HUD_DispModSet", "getPA_VF_HUD_ARHudActvReq",
            "getPA_VF_HUD_IllmnReq", "getPA_VF_HUD_ErgoAdjmtReq", "getPA_VF_HUD_HudSnowModeReq",
    };

    private static final String[] MANAGER_IDS = {
            "ManagerId_cbambliall", "ManagerId_paambliall", "ManagerId_cbmoodlightswitch",
            "ManagerId_pamoodlightswitch",
    };

    private AmbientApi() {
    }

    static void readAll(Context context) {
        ProbeLog.i("=== ambient light probe v2 ===");
        dumpManagerIds();
        readAmbliDirect();
        readThroughCar(context);
        readLamp(context);
        readLightShow(context);
        ProbeLog.i("=== ambient light probe v2 done ===");
    }

    static boolean supported() {
        return SUPPORTED_DEVICE.equals(Build.DEVICE);
    }

    /** Enables the head unit's own music-reactive lighting; the write only runs on the gated unit. */
    static void enableOemMusicShow(Context context) {
        if (!supported()) {
            ProbeLog.i("gated: Build.DEVICE=" + Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; refusing to touch ambient lighting");
            return;
        }
        ProbeLog.i("=== enable OEM music-reactive lighting (gated to " + SUPPORTED_DEVICE + ") ===");
        musicShowRequested = true;
        readAll(context);
    }

    private static boolean musicShowRequested;
    private static boolean restoreRequested;
    private static boolean controlProbeRequested;
    private static boolean marqueeRequested;
    /** Highest intensity the head unit accepted, learned by the control probe (0..255). */
    private static int maxIntensity = 255;
    private static boolean steadyRequested;
    private static boolean colorWheelRequested;
    private static boolean perfRequested;
    private static int perfSamples = 200;
    private static boolean fastRequested;
    private static int fastHz = 40;
    private static int fastSeconds = 8;
    /** 0 = hue sweep, 1 = red/blue flash (easier to judge by eye). */
    private static int fastMode = 0;

    private static final int[] zoneColor = new int[6];
    private static final int[] zoneIntensity = new int[6];
    private static final int[] zoneStatus = new int[6];
    private static int zoneAllColor;
    private static int zoneAllIntensity;
    private static int zoneAllStatus;
    private static boolean zonesSnapshotted;

    /** Values observed on the gated unit before any probe write; used if a read came back empty. */
    private static final int[] BASELINE_ZONE_COLOR = {0, 16736077, 9140479, 0, 0, 0};
    private static final int[] BASELINE_ZONE_INTENSITY = {0, 20, 20, 0, 0, 0};
    private static final int BASELINE_ALL_COLOR = 16742655;
    private static final int BASELINE_ALL_INTENSITY = 20;

    /** Measures how long one ZoneAll write takes, and what frame rate that allows. */
    static void perf(Context context, int samples) {
        if (!supported()) {
            ProbeLog.i("gated: Build.DEVICE=" + Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; refusing to touch ambient lighting");
            return;
        }
        perfSamples = Math.max(20, Math.min(1000, samples));
        ProbeLog.i("=== ambient write latency (gated to " + SUPPORTED_DEVICE
                + ") samples=" + perfSamples + " ===");
        perfRequested = true;
        readAll(context);
    }

    /**
     * Times {@code CB_ZoneAll*} writes one by one, then a colour+intensity pair (one animation
     * frame), then a one-second burst. The pair latency is what sets the achievable frame rate:
     * the client schedules the next frame only after the previous write returned.
     */
    private static void measureWriteLatency(Object manager, int samples) {
        int baseIntensity = dataValueInt(invokeNoArg(manager, "getPA_ZoneAllIntensitySettings"));
        int baseColor = dataValueInt(invokeNoArg(manager, "getPA_ZoneAllColorSettings"));
        ProbeLog.i("baseline intensity=" + baseIntensity + " color=" + baseColor);

        for (int i = 0; i < 5; i++) {
            quietInt(manager, "CB_ZoneAllIntensitySettings", 20 + i);
        }

        long[] intensity = new long[samples];
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            quietInt(manager, "CB_ZoneAllIntensitySettings", 20 + (i % 100));
            intensity[i] = System.nanoTime() - start;
        }
        reportLatency("intensity", intensity);

        long[] color = new long[samples];
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            quietInt(manager, "CB_ZoneAllColorSettings", 0x100000 + (i % 200) * 0x101);
            color[i] = System.nanoTime() - start;
        }
        reportLatency("color", color);

        long[] frame = new long[samples];
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            quietInt(manager, "CB_ZoneAllColorSettings", 0x200000 + (i % 200) * 0x101);
            quietInt(manager, "CB_ZoneAllIntensitySettings", 30 + (i % 80));
            frame[i] = System.nanoTime() - start;
        }
        reportLatency("frame(color+intensity)", frame);

        long start = System.nanoTime();
        int frames = 0;
        while (System.nanoTime() - start < 1_000_000_000L) {
            quietInt(manager, "CB_ZoneAllColorSettings", 0x300000 + (frames % 200) * 0x101);
            quietInt(manager, "CB_ZoneAllIntensitySettings", 40 + (frames % 60));
            frames++;
        }
        ProbeLog.i("sustained writes in 1 s: frames=" + frames
                + " (" + frames + " fps ceiling, no scheduling)");

        quietInt(manager, "CB_ZoneAllColorSettings", baseColor != 0 ? baseColor : BASELINE_ALL_COLOR);
        quietInt(manager, "CB_ZoneAllIntensitySettings",
                baseIntensity > 0 ? baseIntensity : BASELINE_ALL_INTENSITY);
        ProbeLog.i("=== ambient write latency done; baseline restored ===");
    }

    private static void reportLatency(String label, long[] samples) {
        long[] sorted = samples.clone();
        java.util.Arrays.sort(sorted);
        long min = sorted[0];
        long p50 = sorted[sorted.length / 2];
        long p95 = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.95))];
        long max = sorted[sorted.length - 1];
        ProbeLog.i("write " + label + " n=" + sorted.length
                + " min=" + ms(min) + " p50=" + ms(p50) + " p95=" + ms(p95) + " max=" + ms(max)
                + " -> p95 allows " + (p95 > 0 ? (1000f / (p95 / 1_000_000f)) : Float.POSITIVE_INFINITY) + " fps");
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.US, "%.2fms", nanos / 1_000_000f);
    }

    /** Same call as [invokeInt] without the per-call log line, for timing loops. */
    private static void quietInt(Object target, String methodName, int value) {
        Method method = findMethod(target.getClass(), methodName, 1);
        if (method == null) {
            return;
        }
        try {
            method.invoke(target, value);
        } catch (Throwable ignored) {
            // A refusing lamp service shows up as a flat zero-latency loop; the caller restores.
        }
    }

    /** Sweeps the hue at a chosen frame rate, so the lamp's real refresh limit can be judged. */
    static void fast(Context context, int hz, int seconds) {
        if (!supported()) {
            ProbeLog.i("gated: Build.DEVICE=" + Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; refusing to touch ambient lighting");
            return;
        }
        fastHz = Math.max(5, Math.min(120, hz));
        fastSeconds = Math.max(2, Math.min(60, seconds));
        fastMode = 0;
        ProbeLog.i("=== ambient fast sweep " + fastHz + " Hz for " + fastSeconds + " s ===");
        fastRequested = true;
        readAll(context);
    }

    /** Alternates red and blue at the chosen rate, which is far easier to judge than a hue sweep. */
    static void flash(Context context, int hz, int seconds) {
        if (!supported()) {
            ProbeLog.i("gated: Build.DEVICE=" + Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; refusing to touch ambient lighting");
            return;
        }
        fastHz = Math.max(1, Math.min(120, hz));
        fastSeconds = Math.max(2, Math.min(60, seconds));
        fastMode = 1;
        ProbeLog.i("=== ambient red/blue flash " + fastHz + " Hz for " + fastSeconds + " s ===");
        fastRequested = true;
        readAll(context);
    }

    /**
     * Writes colour+intensity frames at a fixed rate on its own thread, measuring the achieved rate
     * so the visible limit can be separated from the requested one.
     */
    private static void fastSweep(Object manager, int hz, int seconds) {
        int baseColor = dataValueInt(invokeNoArg(manager, "getPA_ZoneAllColorSettings"));
        int baseIntensity = dataValueInt(invokeNoArg(manager, "getPA_ZoneAllIntensitySettings"));
        new Thread(() -> {
            long frameNanos = 1_000_000_000L / hz;
            long end = System.nanoTime() + seconds * 1_000_000_000L;
            long start = System.nanoTime();
            int frames = 0;
            try {
                while (System.nanoTime() < end) {
                    long frameStart = System.nanoTime();
                    float progress = (frameStart - start) / (float) (seconds * 1_000_000_000L);
                    if (fastMode == 1) {
                        quietInt(manager, "CB_ZoneAllColorSettings",
                                (frames % 2 == 0) ? 0xFF0000 : 0x0000FF);
                        quietInt(manager, "CB_ZoneAllIntensitySettings", 200);
                    } else {
                        int color = hsvToRgb((progress * 360f) % 360f, 1f, 1f);
                        quietInt(manager, "CB_ZoneAllColorSettings", color);
                        quietInt(manager, "CB_ZoneAllIntensitySettings",
                                (int) (60 + 150 * (0.5 + 0.5 * Math.sin(progress * 2 * Math.PI * 4))));
                    }
                    frames++;
                    long wait = frameNanos - (System.nanoTime() - frameStart);
                    if (wait > 0) {
                        Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                    }
                }
            } catch (Throwable error) {
                ProbeLog.i("ambfast stopped: " + ProbeLog.describe(HudApi.unwrap(error)));
            }
            quietInt(manager, "CB_ZoneAllColorSettings", baseColor != 0 ? baseColor : BASELINE_ALL_COLOR);
            quietInt(manager, "CB_ZoneAllIntensitySettings",
                    baseIntensity > 0 ? baseIntensity : BASELINE_ALL_INTENSITY);
            long elapsedNanos = System.nanoTime() - start;
            float achieved = frames * 1_000_000_000f / Math.max(1L, elapsedNanos);
            ProbeLog.i("ambfast done mode=" + (fastMode == 1 ? "flash" : "hue")
                    + " requested=" + hz + "fps frames=" + frames
                    + " achieved=" + String.format(java.util.Locale.US, "%.1f", achieved) + "fps");
        }, "carprobe-ambfast").start();
    }

    private static int hsvToRgb(float hue, float saturation, float value) {
        float h = (hue % 360f) / 60f;
        int sector = (int) Math.floor(h);
        float f = h - sector;
        int v = (int) (value * 255);
        int p = (int) (value * (1 - saturation) * 255);
        int q = (int) (value * (1 - saturation * f) * 255);
        int t = (int) (value * (1 - saturation * (1 - f)) * 255);
        switch (sector % 6) {
            case 0:
                return (v << 16) | (t << 8) | p;
            case 1:
                return (q << 16) | (v << 8) | p;
            case 2:
                return (p << 16) | (v << 8) | t;
            case 3:
                return (p << 16) | (q << 8) | v;
            case 4:
                return (t << 16) | (p << 8) | v;
            default:
                return (v << 16) | (p << 8) | q;
        }
    }

    /** Enables the head unit's spectrum stream: setSpectrumMode(1), then read it back and subscribe. */
    static void enableSpectrum(Context context, int mode) {
        if (!supported()) {
            ProbeLog.i("gated: Build.DEVICE=" + Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; refusing to touch audio extensions");
            return;
        }
        ProbeLog.i("=== spectrum enable probe mode=" + mode + " ===");
        Intent intent = new Intent(ACTION_AUDIO_EXT_CONTROL).setPackage(PKG_AUDIO_EXT);
        try {
            context.getApplicationContext().bindService(intent, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    ProbeLog.i("AudioExtControlService connected: " + service);
                    // Transaction 8 is setSpectrumMode: the AIDL declares it right after
                    // getSpectrumMode (7). Read it back with 7 to prove the write landed.
                    Ipc.probe("IAudioExtControl.setSpectrumMode(" + mode + ")",
                            service, CLS_AUDIO_EXT_CONTROL, 8, data -> data.writeInt(mode), Ipc.INT);
                    Ipc.probe("IAudioExtControl.getSpectrumMode(" + mode + ") after set",
                            service, CLS_AUDIO_EXT_CONTROL, IAC_GET_SPECTRUM_MODE,
                            data -> data.writeInt(mode), Ipc.INT);
                    Ipc.probe("IAudioExtControl.registerDataCallback(cb)",
                            service, CLS_AUDIO_EXT_CONTROL, IAC_REGISTER_DATA_CALLBACK,
                            data -> data.writeStrongBinder(visualizerCallback), Ipc.VOID);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    ProbeLog.i("AudioExtControlService disconnected: " + name.flattenToShortString());
                }
            }, Context.BIND_AUTO_CREATE);
        } catch (Throwable error) {
            ProbeLog.e("bindService(AudioExtControlService)", error);
        }
    }

    /** Sets one steady red across all zones through the ZoneAll property, then leaves it for viewing. */
    static void steadyColor(Context context) {        if (!supported()) {
            ProbeLog.i("gated: Build.DEVICE=" + Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; refusing to touch ambient lighting");
            return;
        }
        ProbeLog.i("=== ambient steady color (gated to " + SUPPORTED_DEVICE + ") ===");
        steadyRequested = true;
        readAll(context);
    }

    /** Sweeps the hue wheel on ZoneAll, then restores the baseline color. */
    static void startColorWheel(Context context) {
        if (!supported()) {
            ProbeLog.i("gated: Build.DEVICE=" + Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; refusing to touch ambient lighting");
            return;
        }
        ProbeLog.i("=== ambient color wheel (gated to " + SUPPORTED_DEVICE + ") ===");
        colorWheelRequested = true;
        readAll(context);
    }

    static final String CLS_AUDIO_EXT_CONTROL = "com.autolink.audioextservice.IAudioExtControl";
    static final String ACTION_AUDIO_EXT_CONTROL = "com.autolink.audioextservice.AudioExtControlService";
    static final String PKG_AUDIO_EXT = "com.autolink.audioextservice";
    static final int IAC_REGISTER_DATA_CALLBACK = 22;
    static final int IAC_GET_SPECTRUM_MODE = 7;

    private static final VisualizerCallback visualizerCallback = new VisualizerCallback();

    /**
     * Reads the head unit's own audio spectrum ({@code IAudioExtControl.registerDataCallback}).
     * If it works, music-reactive lighting is possible for any audio source, not only CarPlay.
     */
    static void readSpectrum(Context context) {
        if (!supported()) {
            ProbeLog.i("gated: Build.DEVICE=" + Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; refusing to touch audio extensions");
            return;
        }
        ProbeLog.i("=== audio spectrum probe (gated to " + SUPPORTED_DEVICE + ") ===");
        Intent intent = new Intent(ACTION_AUDIO_EXT_CONTROL).setPackage(PKG_AUDIO_EXT);
        boolean bound = false;
        try {
            bound = context.getApplicationContext().bindService(intent, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    ProbeLog.i("AudioExtControlService connected: " + name.flattenToShortString()
                            + " binder=" + service + " iface=" + Ipc.interfaceName(service));
                    Ipc.probe("IAudioExtControl.getSpectrumMode(0)", service, CLS_AUDIO_EXT_CONTROL,
                            IAC_GET_SPECTRUM_MODE, data -> data.writeInt(0), Ipc.INT);
                    Ipc.probe("IAudioExtControl.registerDataCallback(cb)", service, CLS_AUDIO_EXT_CONTROL,
                            IAC_REGISTER_DATA_CALLBACK,
                            data -> data.writeStrongBinder(visualizerCallback), Ipc.VOID);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    ProbeLog.i("AudioExtControlService disconnected: " + name.flattenToShortString());
                }
            }, Context.BIND_AUTO_CREATE);
        } catch (Throwable error) {
            ProbeLog.e("bindService(AudioExtControlService)", error);
        }
        ProbeLog.i("bindService(AudioExtControlService) -> " + bound + " (callback logs onDataChanged)");
    }

    static final String CLS_AUDIO_EXT_SERVICE = "com.autolink.adapterbinder.IAudioExtService";
    static final String ACTION_AUDIO_EXT_SERVICE = "com.autolink.audioextservice.AudioExtService";
    static final int AES_NOTIFY_MEDIA_STATUS = 1;

    /**
     * Registers the spectrum callback, then pokes the head unit's media-state entry
     * ({@code IAudioExtService.notifyMediaStatusChanged}) to try to start its Visualizer.
     */
    static void forceMusicShow(Context context) {
        if (!supported()) {
            ProbeLog.i("gated: Build.DEVICE=" + Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; refusing to touch audio extensions");
            return;
        }
        ProbeLog.i("=== force music show (gated to " + SUPPORTED_DEVICE + ") ===");
        readSpectrum(context);
        Context app = context.getApplicationContext();
        Intent intent = new Intent(ACTION_AUDIO_EXT_SERVICE).setPackage(PKG_AUDIO_EXT);
        boolean bound = false;
        try {
            bound = app.bindService(intent, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    ProbeLog.i("AudioExtService connected: " + name.flattenToShortString()
                            + " iface=" + Ipc.interfaceName(service));
                    Ipc.probe("IAudioExtService.notifyMediaStatusChanged(1, com.android.bluetooth)",
                            service, CLS_AUDIO_EXT_SERVICE, AES_NOTIFY_MEDIA_STATUS, data -> {
                                data.writeInt(1);
                                data.writeString("com.android.bluetooth");
                            }, Ipc.VOID);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    ProbeLog.i("AudioExtService disconnected: " + name.flattenToShortString());
                }
            }, Context.BIND_AUTO_CREATE);
        } catch (Throwable error) {
            ProbeLog.e("bindService(AudioExtService)", error);
        }
        ProbeLog.i("bindService(AudioExtService) -> " + bound);
    }

    private static Visualizer localVisualizer;
    private static int visualizerCallbacks;
    private static int visualizerMaxLength;
    /**
     * Captures the device output mix with the app's own {@code Visualizer(0)}. If this works, DiPlay
     * can drive the lamps from any audio source (Bluetooth, built-in, CarPlay) without the head unit.
     */
    static void readLocalVisualizer(Context context) {
        ProbeLog.i("=== local Visualizer(0) probe ===");
        try {
            Visualizer visualizer = new Visualizer(0);
            int size = Visualizer.getCaptureSizeRange()[1];
            visualizer.setCaptureSize(size);
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer effect, byte[] waveform, int samplingRate) {
                    noteVisualizer(waveform, samplingRate, "waveform");
                }

                @Override
                public void onFftDataCapture(Visualizer effect, byte[] fft, int samplingRate) {
                    noteVisualizer(fft, samplingRate, "fft");
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, true);
            visualizer.setEnabled(true);
            localVisualizer = visualizer;
            ProbeLog.i("Visualizer(0) enabled: captureSize=" + size + " state=" + visualizer.getEnabled());
        } catch (Throwable error) {
            ProbeLog.e("Visualizer(0)", error);
        }
    }

    private static synchronized void noteVisualizer(byte[] data, int samplingRate, String kind) {
        int length = data == null ? 0 : data.length;
        visualizerCallbacks++;
        visualizerMaxLength = Math.max(visualizerMaxLength, length);
        if (visualizerCallbacks <= 5 || visualizerCallbacks % 100 == 0) {
            StringBuilder text = new StringBuilder("[");
            for (int i = 0; data != null && i < data.length && i < 12; i++) {
                if (i > 0) {
                    text.append(',');
                }
                text.append(data[i] & 0xff);
            }
            text.append(']');
            ProbeLog.i("Visualizer " + kind + " #" + visualizerCallbacks + " len=" + length
                    + " max=" + visualizerMaxLength + " rate=" + samplingRate + " " + text);
        }
    }
    static void restoreOemAmbient(Context context) {
        if (!supported()) {
            ProbeLog.i("gated: Build.DEVICE=" + Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; refusing to touch ambient lighting");
            return;
        }
        ProbeLog.i("=== restore ambient state (gated to " + SUPPORTED_DEVICE + ") ===");
        restoreRequested = true;
        readAll(context);
    }

    /** Measures the intensity range and color format the head unit accepts, then restores. */
    static void runControlProbe(Context context) {
        if (!supported()) {
            ProbeLog.i("gated: Build.DEVICE=" + Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; refusing to touch ambient lighting");
            return;
        }
        ProbeLog.i("=== ambient control probe (gated to " + SUPPORTED_DEVICE + ") ===");
        controlProbeRequested = true;
        readAll(context);
    }

    /** Runs the 5-zone running-light ("marquee") demo, then restores the zone state. */
    static void startMarquee(Context context) {
        if (!supported()) {
            ProbeLog.i("gated: Build.DEVICE=" + Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; refusing to touch ambient lighting");
            return;
        }
        ProbeLog.i("=== ambient marquee demo (gated to " + SUPPORTED_DEVICE + ") ===");
        marqueeRequested = true;
        readAll(context);
    }

    private static void dumpManagerIds() {
        Class<?> cls = load(CLS_AMBLI);
        if (cls == null) {
            return;
        }
        for (String name : MANAGER_IDS) {
            try {
                ProbeLog.i("AmbliManager." + name + " = " + cls.getField(name).get(null));
            } catch (Throwable error) {
                ProbeLog.i("AmbliManager." + name + " -> " + ProbeLog.describe(HudApi.unwrap(error)));
            }
        }
    }

    /** The unwired manager, to show what a directly constructed controller can do (nothing). */
    private static void readAmbliDirect() {
        ProbeLog.i("-- " + CLS_AMBLI + " direct (no property base) --");
        Object manager = constructNoArg(CLS_AMBLI);
        if (manager == null) {
            return;
        }
        invokeNoArg(manager, "getPA_MoodLightSwitch");
        invokeInt(manager, "CB_MoodLightSwitch", 1);
    }

    // --- the wired path: ECarXCar -> ECarXCarSetManager -> sub-managers -------

    private static void readThroughCar(Context context) {
        ProbeLog.i("-- ECarXCar.createCar -> getCarManager(PA_SERVICE) --");
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                ProbeLog.i("ECarXCar service connected: " + name.flattenToShortString()
                        + " binder=" + service);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                ProbeLog.i("ECarXCar service disconnected: " + name.flattenToShortString());
            }
        };
        Object car = callStatic(CLS_ECARXCAR, "createCar", context, connection);
        if (car == null) {
            return;
        }
        invokeNoArg(car, "connect");
        // connect() binds asynchronously; ask for the managers once the bind has settled.
        new Handler(Looper.getMainLooper()).postDelayed(() -> queryManagers(car), 4000);
    }

    private static void queryManagers(Object car) {
        ProbeLog.i("-- ECarXCar after connect settles --");
        if (!Boolean.TRUE.equals(invokeNoArg(car, "isConnected"))) {
            ProbeLog.i("ECarXCar did not connect: the car service bind never completed");
            return;
        }
        for (String constant : SERVICE_CONSTANTS) {
            String name = staticString(CLS_ECARXCAR, constant);
            ProbeLog.i("ECarXCar." + constant + " = " + name);
        }
        String paService = staticString(CLS_ECARXCAR, "PA_SERVICE");
        Object setManager = getManager(car, paService);
        if (setManager == null) {
            ProbeLog.i("no manager from PA_SERVICE; cannot reach the sub-managers");
            return;
        }
        Object ambli = invokeNoArg(setManager, "getECarXCarAmbliManager");
        if (ambli != null) {
            readAmbliManager(ambli);
            if (musicShowRequested) {
                enableMusicShow(ambli);
            }
            if (restoreRequested) {
                restoreAmbient(ambli);
            }
            if (controlProbeRequested) {
                controlProbe(ambli);
            }
            if (marqueeRequested) {
                marquee(ambli);
            }
            if (steadyRequested) {
                steadyColor(ambli);
            }
            if (colorWheelRequested) {
                colorWheel(ambli);
            }
            if (perfRequested) {
                perfRequested = false;
                measureWriteLatency(ambli, perfSamples);
            }
            if (fastRequested) {
                fastRequested = false;
                fastSweep(ambli, fastHz, fastSeconds);
            }
        }
        Object vfhud = invokeNoArg(setManager, "getECarXCarVfhudManager");
        if (vfhud != null) {
            readVfhudManager(vfhud);
        }
    }

    private static Object getManager(Object car, String name) {
        if (name == null) {
            return null;
        }
        Method getManager = findMethod(car.getClass(), "getCarManager", 1);
        if (getManager == null) {
            return null;
        }
        try {
            Object manager = getManager.invoke(car, name);
            ProbeLog.i("ECarXCar.getCarManager(" + name + ") -> " + manager);
            return manager;
        } catch (Throwable error) {
            ProbeLog.i("ECarXCar.getCarManager(" + name + ") -> " + ProbeLog.describe(HudApi.unwrap(error)));
            return null;
        }
    }

    private static void readAmbliManager(Object manager) {
        ProbeLog.i("-- ECarXCarAmbliManager (wired) reads --");
        for (String name : AMBLI_READS) {
            invokeNoArg(manager, name);
        }
        for (int zone = 1; zone <= 5; zone++) {
            invokeNoArg(manager, "getPA_Zone" + zone + "ColorSettings");
            invokeNoArg(manager, "getPA_Zone" + zone + "IntensitySettings");
            invokeNoArg(manager, "getPA_Zone" + zone + "StatusSettings");
        }
        invokeNoArg(manager, "getPA_ZoneAllColorSettings");
        invokeNoArg(manager, "getPA_ZoneAllIntensitySettings");
        invokeNoArg(manager, "getPA_ZoneAllStatusSettings");
    }

    /** Switches the head unit into its own music-reactive mode and turns the music linkage on. */
    private static void enableMusicShow(Object manager) {
        ProbeLog.i("-- enable OEM music show (gated) --");
        Integer musicShowMode = staticInt(CLS_AMBLI_MOD, "MusicShowMode");
        ProbeLog.i("AmbLiMod.MusicShowMode = " + musicShowMode);
        invokeNoArg(manager, "getPA_AmbLiModSetting");
        invokeNoArg(manager, "getPA_AmbLiMusicCorrln");
        invokeNoArg(manager, "getPA_AmbLiMusicShowTyp");
        if (musicShowMode != null) {
            invokeInt(manager, "CB_AmbLiModSetting", musicShowMode);
        }
        invokeInt(manager, "CB_AmbLiMusicCorrln", 1);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            ProbeLog.i("-- after enabling music show --");
            invokeNoArg(manager, "getPA_AmbLiModSetting");
            invokeNoArg(manager, "getPA_AmbLiMusicCorrln");
            invokeNoArg(manager, "getPA_AmbLiMusicShowTyp");
        }, 2500);
    }

    /** Restores the mode observed before the probe and turns the music linkage back off. */
    private static void restoreAmbient(Object manager) {
        ProbeLog.i("-- restore ambient (gated) --");
        Integer customized = staticInt(CLS_AMBLI_MOD, "CustomizedMode");
        ProbeLog.i("AmbLiMod.CustomizedMode = " + customized);
        if (customized != null) {
            invokeInt(manager, "CB_AmbLiModSetting", customized);
        }
        invokeInt(manager, "CB_AmbLiMusicCorrln", 0);
        restoreZones(manager);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            ProbeLog.i("-- after restore --");
            invokeNoArg(manager, "getPA_AmbLiModSetting");
            invokeNoArg(manager, "getPA_AmbLiMusicCorrln");
        }, 2500);
    }

    private static Integer staticInt(String className, String fieldName) {
        Class<?> cls = load(className);
        if (cls == null) {
            return null;
        }
        try {
            return cls.getField(fieldName).getInt(null);
        } catch (Throwable error) {
            ProbeLog.i(className + "." + fieldName + " -> " + ProbeLog.describe(HudApi.unwrap(error)));
            return null;
        }
    }

    /** Measures the intensity range and the color format, then restores the zone-all values. */
    private static void controlProbe(Object manager) {
        ProbeLog.i("-- control probe: intensity range + color format --");
        int baseIntensity = dataValueInt(invokeNoArg(manager, "getPA_ZoneAllIntensitySettings"));
        int baseColor = dataValueInt(invokeNoArg(manager, "getPA_ZoneAllColorSettings"));
        Handler handler = new Handler(Looper.getMainLooper());
        int[] tries = {100, 255, 0};
        for (int i = 0; i < tries.length; i++) {
            final int value = tries[i];
            handler.postDelayed(() -> invokeInt(manager, "CB_ZoneAllIntensitySettings", value),
                    300L + i * 700L);
            handler.postDelayed(() -> {
                int got = dataValueInt(invokeNoArg(manager, "getPA_ZoneAllIntensitySettings"));
                if (value == 255 && got > maxIntensity) {
                    maxIntensity = got;
                }
            }, 700L + i * 700L);
        }
        handler.postDelayed(() -> invokeInt(manager, "CB_ZoneAllColorSettings", 0xFFFF0000), 2500);
        handler.postDelayed(() -> invokeNoArg(manager, "getPA_ZoneAllColorSettings"), 3000);
        handler.postDelayed(() -> invokeInt(manager, "CB_ZoneAllIntensitySettings",
                baseIntensity > 0 ? baseIntensity : 20), 3300);
        handler.postDelayed(() -> invokeInt(manager, "CB_ZoneAllColorSettings",
                baseColor != 0 ? baseColor : 16742655), 3600);
        handler.postDelayed(() -> {
            ProbeLog.i("control probe done; maxIntensity=" + maxIntensity);
            invokeNoArg(manager, "getPA_ZoneAllIntensitySettings");
            invokeNoArg(manager, "getPA_ZoneAllColorSettings");
        }, 4200);
    }

    /** Snapshots every zone value once, for a later full restore. */
    private static void snapshotZones(Object manager) {
        if (zonesSnapshotted) {
            return;
        }
        for (int zone = 1; zone <= 5; zone++) {
            zoneColor[zone] = dataValueInt(invokeNoArg(manager, "getPA_Zone" + zone + "ColorSettings"));
            zoneIntensity[zone] = dataValueInt(invokeNoArg(manager, "getPA_Zone" + zone + "IntensitySettings"));
            zoneStatus[zone] = dataValueInt(invokeNoArg(manager, "getPA_Zone" + zone + "StatusSettings"));
        }
        zoneAllColor = dataValueInt(invokeNoArg(manager, "getPA_ZoneAllColorSettings"));
        zoneAllIntensity = dataValueInt(invokeNoArg(manager, "getPA_ZoneAllIntensitySettings"));
        zoneAllStatus = dataValueInt(invokeNoArg(manager, "getPA_ZoneAllStatusSettings"));
        zonesSnapshotted = true;
        ProbeLog.i("zone snapshot: all(c=" + zoneAllColor + ",i=" + zoneAllIntensity + ",s=" + zoneAllStatus
                + ") zoneStatus=" + Arrays.toString(zoneStatus));
    }

    private static void restoreZones(Object manager) {
        int allColor = zoneAllColor > 0 ? zoneAllColor : BASELINE_ALL_COLOR;
        int allIntensity = zoneAllIntensity > 0 ? zoneAllIntensity : BASELINE_ALL_INTENSITY;
        int allStatus = zoneAllStatus != 0 ? zoneAllStatus : 1;
        invokeInt(manager, "CB_ZoneAllStatusSettings", allStatus);
        invokeInt(manager, "CB_ZoneAllColorSettings", allColor);
        invokeInt(manager, "CB_ZoneAllIntensitySettings", allIntensity);
        for (int zone = 1; zone <= 5; zone++) {
            int color = zoneColor[zone] > 0 ? zoneColor[zone] : BASELINE_ZONE_COLOR[zone];
            int intensity = zoneIntensity[zone] > 0 ? zoneIntensity[zone] : BASELINE_ZONE_INTENSITY[zone];
            invokeInt(manager, "CB_Zone" + zone + "StatusSettings", zoneStatus[zone]);
            invokeInt(manager, "CB_Zone" + zone + "ColorSettings", color);
            invokeInt(manager, "CB_Zone" + zone + "IntensitySettings", intensity);
        }
        zonesSnapshotted = false;
        ProbeLog.i("zone state restored (baseline fallback for unread values)");
    }

    /** Sweeps the hue wheel on ZoneAll so the color range is visible, then restores the baseline. */
    private static void colorWheel(Object manager) {
        ProbeLog.i("-- color wheel: ZoneAll hue 0..360, intensity 255 --");
        invokeInt(manager, "CB_ZoneAllStatusSettings", 1);
        invokeInt(manager, "CB_ZoneAllIntensitySettings", 255);
        final int steps = 60;
        final long interval = 180L;
        Handler handler = new Handler(Looper.getMainLooper());
        for (int step = 0; step < steps; step++) {
            final int hue = (step * (360 / steps)) % 360;
            handler.postDelayed(() -> {
                int rgb = hsvToRgb(hue, 1f, 1f);
                invokeInt(manager, "CB_ZoneAllColorSettings", rgb);
            }, 400L + step * interval);
        }
        handler.postDelayed(() -> restoreZones(manager), 400L + steps * interval + 300L);
    }

    /** HSV (h in degrees) to 0xRRGGBB. */
    private static int hsvToRgb(int h, float s, float v) {
        float c = v * s;
        float x = c * (1 - Math.abs((h / 60f) % 2 - 1));
        float m = v - c;
        float r;
        float g;
        float b;
        if (h < 60) {
            r = c; g = x; b = 0;
        } else if (h < 120) {
            r = x; g = c; b = 0;
        } else if (h < 180) {
            r = 0; g = c; b = x;
        } else if (h < 240) {
            r = 0; g = x; b = c;
        } else if (h < 300) {
            r = x; g = 0; b = c;
        } else {
            r = c; g = 0; b = x;
        }
        int ri = Math.round((r + m) * 255);
        int gi = Math.round((g + m) * 255);
        int bi = Math.round((b + m) * 255);
        return (ri << 16) | (gi << 8) | bi;
    }

    /** Writes one steady color through ZoneAll (the path the visible light seems to follow). */
    private static void steadyColor(Object manager) {
        snapshotZones(manager);
        ProbeLog.i("-- steady color: ZoneAll red 0xFF0000, intensity 255 --");
        invokeInt(manager, "CB_ZoneAllStatusSettings", 1);
        invokeInt(manager, "CB_ZoneAllColorSettings", 0xFF0000);
        invokeInt(manager, "CB_ZoneAllIntensitySettings", 255);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            invokeNoArg(manager, "getPA_ZoneAllColorSettings");
            invokeNoArg(manager, "getPA_ZoneAllIntensitySettings");
            invokeNoArg(manager, "getPA_ZoneAllStatusSettings");
        }, 2000);
    }

    /** A running light across the 5 zones: one zone bright at a time, then the zone state restored. */
    private static void marquee(Object manager) {
        ProbeLog.i("-- marquee: 5 zones, one bright at a time --");
        int[] baseStatus = new int[6];
        int[] baseIntensity = new int[6];
        int[] baseColor = new int[6];
        for (int zone = 1; zone <= 5; zone++) {
            baseStatus[zone] = dataValueInt(invokeNoArg(manager, "getPA_Zone" + zone + "StatusSettings"));
            baseIntensity[zone] = dataValueInt(invokeNoArg(manager, "getPA_Zone" + zone + "IntensitySettings"));
            baseColor[zone] = dataValueInt(invokeNoArg(manager, "getPA_Zone" + zone + "ColorSettings"));
            invokeInt(manager, "CB_Zone" + zone + "StatusSettings", 1);
            invokeInt(manager, "CB_Zone" + zone + "ColorSettings", 0xFF00A0FF);
        }
        final int high = Math.max(maxIntensity, 1);
        final int low = Math.max(high / 10, 0);
        final int steps = 25;
        Handler handler = new Handler(Looper.getMainLooper());
        for (int step = 0; step < steps; step++) {
            final int active = step % 5 + 1;
            handler.postDelayed(() -> {
                for (int zone = 1; zone <= 5; zone++) {
                    invokeInt(manager, "CB_Zone" + zone + "IntensitySettings", zone == active ? high : low);
                }
            }, 500L + step * 250L);
        }
        handler.postDelayed(() -> {
            for (int zone = 1; zone <= 5; zone++) {
                invokeInt(manager, "CB_Zone" + zone + "IntensitySettings", baseIntensity[zone]);
                invokeInt(manager, "CB_Zone" + zone + "ColorSettings", baseColor[zone]);
                invokeInt(manager, "CB_Zone" + zone + "StatusSettings", baseStatus[zone]);
            }
            ProbeLog.i("marquee done; zone state restored");
        }, 500L + steps * 250L + 300L);
    }

    /** {@code IVisualizerCallback}: the head unit pushes the realtime spectrum byte[] here. */
    static final class VisualizerCallback extends Binder {
        static final String DESCRIPTOR = "com.autolink.audioextservice.IVisualizerCallback";
        private int count;
        private int maxLength;

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == 1598968902) {
                try {
                    reply.writeString(DESCRIPTOR);
                } catch (Throwable ignored) {
                    // nothing to do
                }
                return true;
            }
            try {
                data.enforceInterface(DESCRIPTOR);
                if (code == 1) {
                    byte[] bytes = data.createByteArray();
                    int length = bytes == null ? 0 : bytes.length;
                    count++;
                    maxLength = Math.max(maxLength, length);
                    if (count <= 5 || count % 100 == 0) {
                        ProbeLog.i("IVisualizerCallback.onDataChanged(#" + count + ", len=" + length
                                + ", max=" + maxLength + ") " + summarize(bytes));
                    }
                    reply.writeNoException();
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            } catch (Throwable error) {
                ProbeLog.e("IVisualizerCallback failed", error);
            }
            return true;
        }

        private String summarize(byte[] bytes) {
            if (bytes == null || bytes.length == 0) {
                return "[]";
            }
            StringBuilder text = new StringBuilder("[");
            for (int i = 0; i < bytes.length && i < 16; i++) {
                if (i > 0) {
                    text.append(',');
                }
                text.append(bytes[i] & 0xff);
            }
            return text.append(']').toString();
        }
    }

    /** The integer inside a {@code PATypes} value (boxed number, boolean, or "On"/"Off" enum). */
    private static int dataValueInt(Object patypes) {
        if (patypes == null) {
            return 0;
        }
        String text = String.valueOf(patypes);
        int at = text.indexOf("dataValue = ");
        if (at >= 0) {
            String token = text.substring(at + "dataValue = ".length()).split("[,}]")[0].trim();
            Integer parsed = parseDataValue(token);
            if (parsed != null) {
                return parsed;
            }
        }
        return 0;
    }

    private static Integer parseDataValue(String token) {
        if ("On".equalsIgnoreCase(token) || "True".equalsIgnoreCase(token)) {
            return 1;
        }
        if ("Off".equalsIgnoreCase(token) || "False".equalsIgnoreCase(token)) {
            return 0;
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void readVfhudManager(Object manager) {
        ProbeLog.i("-- ECarXCarVfhudManager (wired) reads (read-only) --");
        for (String name : VFHUD_READS) {
            invokeNoArg(manager, name);
        }
    }

    private static void readLamp(Context context) {
        ProbeLog.i("-- adaptapi Lamp --");
        Object lamp = construct(CLS_LAMP, context);
        if (lamp == null) {
            return;
        }
        invokeNoArg(lamp, "buildFunctions");
        invokeNoArg(lamp, "getSupportedLampExteriorLightControl");
    }

    private static void readLightShow(Context context) {
        ProbeLog.i("-- adaptapi LightShowImpl --");
        Object show = callStatic(CLS_LIGHT_SHOW, "create", context);
        if (show == null) {
            show = construct(CLS_LIGHT_SHOW, context);
        }
        if (show == null) {
            return;
        }
        for (String name : new String[]{"isLightingShowEnabled", "getLightingShowState", "getLightShowCarConfig"}) {
            invokeNoArg(show, name);
        }
    }

    // --- reflection helpers -------------------------------------------------

    static Class<?> load(String name) {
        try {
            return Class.forName(name, false, AmbientApi.class.getClassLoader());
        } catch (Throwable error) {
            ProbeLog.i("Class.forName(" + name + ") -> " + ProbeLog.describe(error));
            return null;
        }
    }

    private static String staticString(String className, String fieldName) {
        Class<?> cls = load(className);
        if (cls == null) {
            return null;
        }
        try {
            return (String) cls.getField(fieldName).get(null);
        } catch (Throwable error) {
            return null;
        }
    }

    private static Object construct(String className, Context context) {        Class<?> cls = load(className);
        if (cls == null) {
            return null;
        }
        try {
            Constructor<?> ctor = cls.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            Object instance = ctor.newInstance(context);
            ProbeLog.i(className + "(context) -> " + instance);
            return instance;
        } catch (Throwable error) {
            ProbeLog.i(className + "(context) failed -> " + ProbeLog.describe(HudApi.unwrap(error)));
            return null;
        }
    }

    private static Object constructNoArg(String className) {
        Class<?> cls = load(className);
        if (cls == null) {
            return null;
        }
        try {
            Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object instance = ctor.newInstance();
            ProbeLog.i(className + "() -> " + instance);
            return instance;
        } catch (Throwable error) {
            ProbeLog.i(className + "() failed -> " + ProbeLog.describe(HudApi.unwrap(error)));
            return null;
        }
    }

    private static Object callStatic(String className, String methodName, Context context,
                                     ServiceConnection connection) {
        Class<?> cls = load(className);
        if (cls == null) {
            return null;
        }
        try {
            Method method = cls.getMethod(methodName, Context.class, ServiceConnection.class);
            Object result = method.invoke(null, context, connection);
            ProbeLog.i(className + "." + methodName + "(context, connection) -> " + result);
            return result;
        } catch (Throwable error) {
            ProbeLog.i(className + "." + methodName + "(context, connection) failed -> "
                    + ProbeLog.describe(HudApi.unwrap(error)));
            return null;
        }
    }

    private static Object callStatic(String className, String methodName, Context context) {
        Class<?> cls = load(className);
        if (cls == null) {
            return null;
        }
        try {
            Method method = cls.getMethod(methodName, Context.class);
            Object result = method.invoke(null, context);
            ProbeLog.i(className + "." + methodName + "(context) -> " + result);
            return result;
        } catch (Throwable error) {
            ProbeLog.i(className + "." + methodName + "(context) failed -> "
                    + ProbeLog.describe(HudApi.unwrap(error)));
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String methodName) {
        Method method = findMethod(target.getClass(), methodName, 0);
        if (method == null) {
            ProbeLog.i(target.getClass().getSimpleName() + "." + methodName + "() not found");
            return null;
        }
        try {
            Object result = method.invoke(target);
            ProbeLog.i(target.getClass().getSimpleName() + "." + methodName + "() -> " + value(result));
            return result;
        } catch (Throwable error) {
            ProbeLog.i(target.getClass().getSimpleName() + "." + methodName + "() -> "
                    + ProbeLog.describe(HudApi.unwrap(error)));
            return null;
        }
    }

    private static void invokeInt(Object target, String methodName, int value) {
        Method method = findMethod(target.getClass(), methodName, 1);
        if (method == null) {
            ProbeLog.i(target.getClass().getSimpleName() + "." + methodName + "(int) not found");
            return;
        }
        try {
            Object result = method.invoke(target, value);
            ProbeLog.i(target.getClass().getSimpleName() + "." + methodName + "(" + value + ") -> "
                    + value(result));
        } catch (Throwable error) {
            ProbeLog.i(target.getClass().getSimpleName() + "." + methodName + "(" + value + ") -> "
                    + ProbeLog.describe(HudApi.unwrap(error)));
        }
    }

    /** {@code ApiResult} and the {@code PATypes} values print opaque; show their fields. */
    private static String value(Object result) {
        if (result == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder(String.valueOf(result));
        for (Field field : result.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                text.append(" ").append(field.getName()).append("=").append(field.get(result));
            } catch (Throwable ignored) {
                // best effort
            }
        }
        return text.toString();
    }

    /** The first primitive int in a PATypes value, or {@link Integer#MIN_VALUE}. */
    private static int firstInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return Integer.MIN_VALUE;
        }
        for (Field field : value.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                return field.getInt(value);
            } catch (Throwable ignored) {
                // keep looking
            }
        }
        return Integer.MIN_VALUE;
    }

    private static Method findMethod(Class<?> cls, String name, int parameterCount) {
        for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }
}
