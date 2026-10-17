package com.diplay.carprobe;

import android.content.Context;

/**
 * Read-only dump of the DesaySV phone-link classes that own the USB data role (#3, question A).
 *
 * <p>The port runs as a gadget ({@code carplay_carlife,adb}) and {@code dumpsys usb} reports
 * {@code can_change_data_role=false}, so the open question is whether the vendor jar on the boot
 * classpath ({@code desaysv-phonelinkadaptor}) is reachable from a normal app. This dumps the
 * contract only: every {@code set*} method stays uncalled, so nothing changes the car.
 */
final class UsbApi {

    static final String CLS_VEHICLE_HAL = "desaysv.ivi.phoneLinkplatformadaptorlib.PhoneLinkVehicleHal";
    static final String CLS_MANAGER = "desaysv.ivi.phoneLinkplatformadaptorlib.PhoneLinkAdaptorLibManager";

    /** Gate: the vendor USB HAL is only probed on the reverse-engineered FX11 unit. */
    static final String SUPPORTED_DEVICE = "msmnile_gvmq";

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
}
