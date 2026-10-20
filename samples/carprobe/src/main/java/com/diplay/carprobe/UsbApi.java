package com.diplay.carprobe;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only dump of the DesaySV phone-link classes that own the USB data role (#3, question A).
 *
 * <p>The port runs as a gadget ({@code carplay_carlife,adb}) and {@code dumpsys usb} reports
 * {@code can_change_data_role=false}, so the open question is whether the vendor jar on the boot
 * classpath ({@code desaysv-phonelinkadaptor}) is reachable from a normal app. This dumps the
 * contract only: every {@code set*} method stays uncalled, so nothing changes the car.
 *
 * <p>{@link #scan} is the second half: the engineering-mode USB2.0 page can flip the port to the
 * host eye-diagram mode, which drops adb, so the probe keeps polling what the kernel and
 * {@link UsbManager} can see and appends every change to {@code usbscan.log} in the app's external
 * files dir. Pull that file after switching back to the device mode.
 */
final class UsbApi {

    static final String CLS_VEHICLE_HAL = "desaysv.ivi.phoneLinkplatformadaptorlib.PhoneLinkVehicleHal";
    static final String CLS_MANAGER = "desaysv.ivi.phoneLinkplatformadaptorlib.PhoneLinkAdaptorLibManager";

    /** Gate: the vendor USB HAL is only probed on the reverse-engineered FX11 unit. */
    static final String SUPPORTED_DEVICE = "msmnile_gvmq";

    private static final String SCAN_LOG = "usbscan.log";
    private static final long SCAN_INTERVAL_MILLIS = 500;

    private static volatile boolean scanStopping = false;
    private static Thread scanThread;

    private UsbApi() {
    }

    static void dump(Context context) {
        if (!SUPPORTED_DEVICE.equals(android.os.Build.DEVICE)) {
            ProbeLog.i("gated: Build.DEVICE=" + android.os.Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; skipping USB role probe");
            return;
        }
        ProbeLog.i("=== phone-link / USB role dump (gated to " + SUPPORTED_DEVICE + ") ===");
        ProbeLog.i("Build.PRODUCT=" + android.os.Build.PRODUCT + " MODEL=" + android.os.Build.MODEL);
        WifiApi.dumpApi(CLS_VEHICLE_HAL);
        WifiApi.dumpApi(CLS_MANAGER);
        ProbeLog.i("=== USB role dump done (nothing was called) ===");
    }

    /**
     * Polls the USB host view every 500 ms for {@code seconds} and appends a line per sample to
     * {@code usbscan.log}, plus one line per change. Runs on its own thread because the caller
     * (the command channel) must return at once.
     */
    static void scan(Context context, int seconds) {
        if (!SUPPORTED_DEVICE.equals(android.os.Build.DEVICE)) {
            ProbeLog.i("gated: Build.DEVICE=" + android.os.Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; skipping USB scan");
            return;
        }
        stopScan();
        scanStopping = false;
        final Context appContext = context.getApplicationContext();
        final long deadline = System.currentTimeMillis() + Math.max(1, seconds) * 1000L;
        scanThread = new Thread(() -> {
            File log = new File(appContext.getExternalFilesDir(null), SCAN_LOG);
            String previous = null;
            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(log, false), "UTF-8")) {
                writer.write("# usbscan start " + stamp() + " for " + seconds + " s\n");
                writer.flush();
                ProbeLog.i("usbscan started, writing " + log.getAbsolutePath());
                while (!scanStopping && System.currentTimeMillis() < deadline) {
                    String sample = sample(appContext);
                    writer.write(sample);
                    writer.write('\n');
                    writer.flush();
                    if (previous == null || !sameDevices(previous, sample)) {
                        ProbeLog.i("usbscan change: " + sample);
                    }
                    previous = sample;
                    try {
                        Thread.sleep(SCAN_INTERVAL_MILLIS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                writer.write("# usbscan stop " + stamp() + "\n");
                writer.flush();
            } catch (IOException error) {
                ProbeLog.e("usbscan could not write " + log, error);
            }
            ProbeLog.i("usbscan finished");
        }, "carprobe-usbscan");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    static void stopScan() {
        scanStopping = true;
        Thread thread = scanThread;
        if (thread != null) {
            thread.interrupt();
            scanThread = null;
        }
    }

    /** One sample: gadget state, UDC states, UsbManager devices, kernel host devices. */
    private static String sample(Context context) {
        return stamp()
                + " usb.state=" + usbState()
                + " udc=[" + udcStates() + "]"
                + " manager=[" + managerDevices(context) + "]"
                + " kernel=[" + kernelDevices() + "]";
    }

    /** The device set is what matters for "did the phone enumerate"; ignore the timestamp. */
    private static boolean sameDevices(String previous, String current) {
        return tail(previous).equals(tail(current));
    }

    private static String tail(String line) {
        int cut = line.indexOf(" usb.state=");
        return cut >= 0 ? line.substring(cut) : line;
    }

    private static String usbState() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Object value = properties.getMethod("get", String.class).invoke(null, "sys.usb.state");
            return String.valueOf(value);
        } catch (Throwable error) {
            return "unreadable(" + error.getClass().getSimpleName() + ")";
        }
    }

    private static String udcStates() {
        File dir = new File("/sys/class/udc");
        File[] controllers = dir.listFiles();
        if (controllers == null) return "unreadable";
        StringBuilder builder = new StringBuilder();
        for (File controller : controllers) {
            if (builder.length() > 0) builder.append(',');
            builder.append(controller.getName()).append(':').append(read(controller, "state"));
        }
        return builder.toString();
    }

    private static String managerDevices(Context context) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) return "no-service";
        Map<String, UsbDevice> devices;
        try {
            devices = manager.getDeviceList();
        } catch (Throwable error) {
            return "error=" + error.getClass().getSimpleName();
        }
        if (devices == null || devices.isEmpty()) return "none";
        StringBuilder builder = new StringBuilder();
        for (UsbDevice device : devices.values()) {
            if (builder.length() > 0) builder.append("; ");
            builder.append(device.getDeviceName())
                    .append(" id=").append(hex(device.getVendorId())).append(':').append(hex(device.getProductId()))
                    .append(" cls=").append(device.getDeviceClass())
                    .append(" ifaces=").append(device.getInterfaceCount())
                    .append(" product=").append(device.getProductName())
                    .append(" manufacturer=").append(device.getManufacturerName());
        }
        return builder.toString();
    }

    /**
     * Kernel-level enumeration. A host-mode port with a device plugged in shows up here even when
     * the framework never claims it, which is exactly the case the probe is looking for.
     */
    private static String kernelDevices() {
        File dir = new File("/sys/bus/usb/devices");
        File[] entries = dir.listFiles();
        if (entries == null) return "unreadable";
        List<String> found = new ArrayList<>();
        for (File entry : entries) {
            File vendor = new File(entry, "idVendor");
            if (!vendor.isFile()) continue;
            found.add(entry.getName()
                    + " " + read(entry, "idVendor") + ":" + read(entry, "idProduct")
                    + " speed=" + read(entry, "speed")
                    + " bus=" + read(entry, "busnum") + "/" + read(entry, "devnum")
                    + " product=" + read(entry, "product"));
        }
        return found.isEmpty() ? "none" : String.join("; ", found);
    }

    private static String read(File dir, String name) {
        File file = new File(dir, name);
        if (!file.isFile()) return "-";
        try {
            byte[] bytes = new byte[256];
            try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
                int count = input.read(bytes);
                if (count <= 0) return "-";
                return new String(bytes, 0, count, "UTF-8").trim();
            }
        } catch (Throwable error) {
            return "?";
        }
    }

    private static String hex(int value) {
        return String.format(Locale.US, "0x%04x", value);
    }

    private static String stamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
