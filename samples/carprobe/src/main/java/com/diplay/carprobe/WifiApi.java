package com.diplay.carprobe;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * Read-only probe of the ECARX Wi-Fi / hotspot surface.
 *
 * <p>The head unit's hotspot is a vendor {@code p2p0} local hotspot, so the standard Android SoftAP
 * state does not see it. The reverse-engineering points at {@code ecarx.fw.api.wifi.TcamWifi}
 * (framework, boot classpath; implementation {@code com.autolink.adaptersrv.wifi.TcamWifiImpl} with
 * a public {@code (Context)} constructor) and the adaptapi wrapper
 * {@code com.ecarx.xui.adaptapi.wifiap.ExternalWifiManager}. This probe reads, and never writes.
 *
 * <p>Only read paths run ({@code is*}, {@code get*}, {@code create}).
 */
final class WifiApi {

    static final String CLS_TCAM_WIFI_IMPL = "com.autolink.adaptersrv.wifi.TcamWifiImpl";
    static final String CLS_EXTERNAL_WIFI = "com.ecarx.xui.adaptapi.wifiap.ExternalWifiManager";

    /** Gate: the ECARX Wi-Fi path is only enabled on the reverse-engineered FX11 unit. */
    static final String SUPPORTED_DEVICE = "msmnile_gvmq";

    private WifiApi() {
    }

    static void readAll(Context context) {
        if (!SUPPORTED_DEVICE.equals(android.os.Build.DEVICE)) {
            ProbeLog.i("gated: Build.DEVICE=" + android.os.Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; skipping Wi-Fi probe");
            return;
        }
        ProbeLog.i("=== Wi-Fi / hotspot probe (gated to " + SUPPORTED_DEVICE + ") ===");
        dumpApi(CLS_TCAM_WIFI_IMPL);
        dumpApi(CLS_EXTERNAL_WIFI);
        readTcamWifi(context);
        readExternalWifi(context);
        ProbeLog.i("=== Wi-Fi / hotspot probe done ===");
    }

    /**
     * Brings up the vendor hotspot to see whether a normal app can make it serve. Order:
     * report state, {@code setWifiEnabled(true)}, report, {@code startSoftAp(current config)},
     * report. Every step logs the vendor state plus the kernel interfaces, so a change is visible.
     */
    static void bringUp(Context context) {
        if (!SUPPORTED_DEVICE.equals(android.os.Build.DEVICE)) {
            ProbeLog.i("gated: Build.DEVICE=" + android.os.Build.DEVICE + " != " + SUPPORTED_DEVICE);
            return;
        }
        Object wifi = newTcamWifi(context);
        if (wifi == null) {
            return;
        }
        ProbeLog.i("=== hotspot bring-up ===");
        report(wifi, "before");
        ProbeLog.i("setWifiEnabled(true) -> " + invoke(wifi, "setWifiEnabled", true));
        sleep(6000);
        report(wifi, "after setWifiEnabled(true)");
        Object config = invoke(wifi, "getWifiApConfiguration");
        ProbeLog.i("startSoftAp(" + config + ") -> " + invoke(wifi, "startSoftAp", config));
        sleep(8000);
        report(wifi, "after startSoftAp");
        ProbeLog.i("=== hotspot bring-up done ===");
    }

    private static void report(Object wifi, String phase) {
        ProbeLog.i("-- " + phase + ": isWifiApEnabled=" + invoke(wifi, "isWifiApEnabled")
                + " getWifiApState=" + invoke(wifi, "getWifiApState")
                + " isWifiEnabled=" + invoke(wifi, "isWifiEnabled")
                + " clients=" + invoke(wifi, "getSoftApConDevInfo"));
        interfaces();
    }

    /** Kernel interfaces, including the down ones Java's NetworkInterface hides. */
    private static void interfaces() {
        ProbeLog.i("   ip -4 -o addr: " + exec("ip -4 -o addr"));
        ProbeLog.i("   operstate: " + exec("for i in /sys/class/net/*; do printf '%s=%s ' $(basename $i) $(cat $i/operstate 2>/dev/null); done"));
    }

    private static String exec(String command) {
        try {
            Process process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true).start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line.trim()).append(" | ");
            }
            process.waitFor();
            return text.toString();
        } catch (Throwable error) {
            return "exec failed: " + ProbeLog.describe(error);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Object newTcamWifi(Context context) {
        Class<?> cls = load(CLS_TCAM_WIFI_IMPL);
        if (cls == null) {
            return null;
        }
        try {
            Constructor<?> ctor = cls.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            return ctor.newInstance(context);
        } catch (Throwable error) {
            ProbeLog.i("new TcamWifiImpl(context) failed -> " + ProbeLog.describe(HudApi.unwrap(error)));
            return null;
        }
    }

    private static Object invoke(Object target, String methodName, Object... arguments) {
        Method method = findMethod(target.getClass(), methodName, arguments.length);
        if (method == null) {
            return "no such method";
        }
        try {
            return method.invoke(target, arguments);
        } catch (Throwable error) {
            return "error: " + ProbeLog.describe(HudApi.unwrap(error));
        }
    }

    private static void readTcamWifi(Context context) {
        ProbeLog.i("-- " + CLS_TCAM_WIFI_IMPL + " --");
        Class<?> cls = load(CLS_TCAM_WIFI_IMPL);
        if (cls == null) {
            return;
        }
        Object wifi = null;
        try {
            Constructor<?> ctor = cls.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            wifi = ctor.newInstance(context);
            ProbeLog.i("new TcamWifiImpl(context) -> " + wifi);
        } catch (Throwable error) {
            ProbeLog.i("new TcamWifiImpl(context) failed -> " + ProbeLog.describe(HudApi.unwrap(error)));
        }
        if (wifi == null) {
            return;
        }
        String[] reads = {
                "isWifiApEnabled", "getWifiApState", "getWifiApConfiguration", "getSoftApConDevInfo",
                "isWifiEnabled", "getWifiState", "getTcamInfo", "getTestConnStatusInfo",
        };
        for (String name : reads) {
            invokeNoArg(wifi, name);
        }
        dumpConnectedClients(wifi);
    }

    /**
     * Dumps one object's declared fields, walking the superclass chain, then calls its no-argument
     * data getters. AIDL parcelables keep their values in a parent class and expose getters only.
     */
    private static void describe(String prefix, Object value) {
        for (Class<?> cls = value.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (java.lang.reflect.Field field : cls.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    ProbeLog.i("     " + prefix + "." + field.getName() + " = " + field.get(value));
                } catch (Throwable error) {
                    ProbeLog.i("     " + prefix + "." + field.getName() + " -> " + ProbeLog.describe(error));
                }
            }
        }
        for (java.lang.reflect.Method method : value.getClass().getMethods()) {
            String name = method.getName();
            if (method.getParameterCount() != 0 || method.getReturnType() == void.class
                    || name.equals("toString") || name.equals("hashCode") || name.equals("getClass")
                    || name.startsWith("getCreator") || name.equals("describeContents")
                    || name.equals("writeToParcel")) {
                continue;
            }
            try {
                ProbeLog.i("     " + prefix + "." + name + "() = " + method.invoke(value));
            } catch (Throwable error) {
                ProbeLog.i("     " + prefix + "." + name + "() -> " + ProbeLog.describe(HudApi.unwrap(error)));
            }
        }
    }

    /** The AP's client list carries each station's address; it says whether the AP network is local. */
    private static void dumpConnectedClients(Object wifi) {
        Object clients = invoke(wifi, "getSoftApConDevInfo");
        if (!(clients instanceof java.util.List)) {
            return;
        }
        for (Object client : (java.util.List<?>) clients) {
            if (client == null) {
                continue;
            }
            for (java.lang.reflect.Field field : client.getClass().getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(client);
                    ProbeLog.i("   client." + field.getName() + " = " + value);
                    if (value != null && !(value instanceof String) && !value.getClass().isPrimitive()
                            && !value.getClass().getName().startsWith("java.")) {
                        describe(field.getName(), value);
                    }
                } catch (Throwable error) {
                    ProbeLog.i("   client." + field.getName() + " -> " + ProbeLog.describe(error));
                }
            }
        }
    }

    private static void readExternalWifi(Context context) {
        ProbeLog.i("-- " + CLS_EXTERNAL_WIFI + " --");
        Object manager = externalWifi(context);
        if (manager == null) {
            ProbeLog.i("ExternalWifiManager.create returned null (stub or unsupported)");
            return;
        }
        for (String name : new String[]{"isSupported", "isWifiEnabled", "getWifiState"}) {
            invokeNoArg(manager, name);
        }
    }

    /** The adaptapi Wi-Fi wrapper, or null when its class or {@code create()} does not work here. */
    private static Object externalWifi(Context context) {
        Class<?> cls = load(CLS_EXTERNAL_WIFI);
        if (cls == null) {
            return null;
        }
        for (Method method : cls.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || !method.getName().equals("create")) {
                continue;
            }
            try {
                Object result = method.getParameterCount() == 1
                        ? method.invoke(null, context) : method.invoke(null, context, 0);
                ProbeLog.i("ExternalWifiManager.create(" + method.getParameterCount() + " args) -> " + result);
                if (result != null) {
                    return result;
                }
            } catch (Throwable error) {
                ProbeLog.i("ExternalWifiManager.create failed -> " + ProbeLog.describe(HudApi.unwrap(error)));
            }
        }
        return null;
    }

    /**
     * #2: can a normal app turn the Wi-Fi client on through the ECARX wrapper, without the driver
     * opening Settings and without ADB? Both the wrapper's own answer and Settings.Global are
     * reported before and after a wait, so a SecurityException, a silent no-op and a real change
     * are told apart. Nothing else is touched, and the caller can switch it back the same way.
     */
    static void setClientWifi(Context context, boolean on) {
        if (!SUPPORTED_DEVICE.equals(android.os.Build.DEVICE)) {
            ProbeLog.i("gated: Build.DEVICE=" + android.os.Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; skipping Wi-Fi client write");
            return;
        }
        ProbeLog.i("=== Wi-Fi client " + (on ? "on" : "off") + " (gated to " + SUPPORTED_DEVICE + ") ===");
        ProbeLog.i("Settings.Global wifi_on before = " + globalWifiOn(context));
        Object manager = externalWifi(context);
        if (manager == null) {
            ProbeLog.i("ExternalWifiManager unavailable; nothing tried");
            return;
        }
        ProbeLog.i("isSupported -> " + invokeNoArg(manager, "isSupported"));
        ProbeLog.i("isWifiEnabled before -> " + invokeNoArg(manager, "isWifiEnabled"));
        ProbeLog.i("setWifiEnabled(" + on + ") -> " + invoke(manager, "setWifiEnabled", on));
        sleep(4000);
        ProbeLog.i("isWifiEnabled after -> " + invokeNoArg(manager, "isWifiEnabled"));
        ProbeLog.i("Settings.Global wifi_on after = " + globalWifiOn(context));
        interfaces();
        ProbeLog.i("=== Wi-Fi client " + (on ? "on" : "off") + " done ===");
    }

    private static int globalWifiOn(Context context) {
        try {
            return android.provider.Settings.Global.getInt(
                    context.getContentResolver(), "wifi_on", -1);
        } catch (Throwable error) {
            return -1;
        }
    }

    static Class<?> load(String name) {
        try {
            return Class.forName(name, false, WifiApi.class.getClassLoader());
        } catch (Throwable appError) {
            // Boot-classpath classes are reached through the bootstrap loader, which is not the app
            // one; try it before calling the class unreachable.
            try {
                return Class.forName(name, false, null);
            } catch (Throwable bootError) {
                ProbeLog.i("Class.forName(" + name + ") -> app: " + ProbeLog.describe(appError)
                        + "; boot: " + ProbeLog.describe(bootError));
                return null;
            }
        }
    }

    static void dumpApi(String className) {
        Class<?> cls = load(className);
        if (cls == null) {
            return;
        }
        ProbeLog.i("-- API " + cls.getName() + " extends " + cls.getSuperclass());
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            ProbeLog.i("   <init> " + Modifier.toString(ctor.getModifiers()) + " "
                    + Arrays.toString(ctor.getParameterTypes()));
        }
        int shown = 0;
        for (Method method : cls.getDeclaredMethods()) {
            if (method.isSynthetic() || method.getName().startsWith("lambda$")
                    || method.getName().startsWith("access$")) {
                continue;
            }
            if (shown++ >= 40) {
                ProbeLog.i("   (more methods omitted)");
                break;
            }
            ProbeLog.i("   " + Modifier.toString(method.getModifiers()) + " "
                    + method.getReturnType().getSimpleName() + " " + method.getName()
                    + Arrays.toString(method.getParameterTypes()));
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
            ProbeLog.i(target.getClass().getSimpleName() + "." + methodName + "() -> " + result);
            return result;
        } catch (Throwable error) {
            ProbeLog.i(target.getClass().getSimpleName() + "." + methodName + "() -> "
                    + ProbeLog.describe(HudApi.unwrap(error)));
            return null;
        }
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
