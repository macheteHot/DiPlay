package com.diplay.carprobe;

import android.content.Context;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Read-only dump of the XUI DIM interaction API (#3, question C).
 *
 * <p>{@code com.ecarx.xui.adaptapi.diminteraction.DimInteraction} is the fourth path to the
 * cluster: {@code create(Context)} → {@code getNaviInteraction()} → {@code updateNavigationInfo}.
 * This probe only reports whether the class and the factory are reachable and what the returned
 * objects expose; it never calls an {@code update*} method, so nothing is pushed to the cluster.
 */
final class DimApi {

    static final String CLS_DIM = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction";
    static final String CLS_NAVI = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction";

    static final String SUPPORTED_DEVICE = "msmnile_gvmq";

    private DimApi() {
    }

    static void dump(Context context) {
        if (!SUPPORTED_DEVICE.equals(android.os.Build.DEVICE)) {
            ProbeLog.i("gated: Build.DEVICE=" + android.os.Build.DEVICE + " != " + SUPPORTED_DEVICE
                    + "; skipping DIM interaction probe");
            return;
        }
        ProbeLog.i("=== DIM interaction dump (read-only, nothing pushed) ===");
        Class<?> dimClass = WifiApi.load(CLS_DIM);
        Class<?> naviClass = WifiApi.load(CLS_NAVI);
        ProbeLog.i("DimInteraction=" + dimClass + " INaviInteraction=" + naviClass);
        if (dimClass == null) {
            ProbeLog.i("DimInteraction unreachable; the XUI DIM route cannot be called from an app");
            return;
        }
        WifiApi.dumpApi(CLS_DIM);
        Object dim = null;
        for (Method method : dimClass.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || !method.getName().equals("create")) {
                continue;
            }
            try {
                Object result = method.invoke(null, context);
                ProbeLog.i("DimInteraction.create(" + method.getParameterCount() + " args) -> " + result);
                if (result != null) {
                    dim = result;
                    break;
                }
            } catch (Throwable error) {
                ProbeLog.i("DimInteraction.create failed -> " + ProbeLog.describe(HudApi.unwrap(error)));
            }
        }
        if (dim == null) {
            ProbeLog.i("DimInteraction.create returned null or failed; nothing else tried");
            return;
        }
        for (String name : new String[]{"getShowPresentationOption", "getNaviInteraction",
                "getMediaInteraction", "getPhoneInteraction"}) {
            Object value = invoke(dim, name);
            if (value != null && !(value instanceof String)) {
                dumpMethods(name, value);
            }
        }
        ProbeLog.i("=== DIM interaction dump done ===");
    }

    private static Object invoke(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            Object result = method.invoke(target);
            ProbeLog.i(target.getClass().getSimpleName() + "." + name + "() -> " + result);
            return result;
        } catch (NoSuchMethodException missing) {
            ProbeLog.i(target.getClass().getSimpleName() + "." + name + "() not found");
        } catch (Throwable error) {
            ProbeLog.i(target.getClass().getSimpleName() + "." + name + "() -> "
                    + ProbeLog.describe(HudApi.unwrap(error)));
        }
        return null;
    }

    /** Methods of the object actually returned, so a package-private impl is still described. */
    private static void dumpMethods(String prefix, Object value) {
        int shown = 0;
        for (Method method : value.getClass().getMethods()) {
            if (method.getDeclaringClass() == Object.class || method.isSynthetic()) {
                continue;
            }
            if (shown++ >= 40) {
                ProbeLog.i("   " + prefix + ": (more methods omitted)");
                break;
            }
            ProbeLog.i("   " + prefix + ": " + method.getReturnType().getSimpleName() + " "
                    + method.getName()
                    + java.util.Arrays.toString(method.getParameterTypes()));
        }
    }
}
