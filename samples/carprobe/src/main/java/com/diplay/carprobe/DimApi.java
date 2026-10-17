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
    static final String CLS_INFO = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction$INavigationInfo";
    static final String CLS_LANE = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction$ILaneInfo";

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

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
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
            if (shown++ >= 200) {
                ProbeLog.i("   " + prefix + ": (more methods omitted)");
                break;
            }
            ProbeLog.i("   " + prefix + ": " + method.getReturnType().getSimpleName() + " "
                    + method.getName()
                    + java.util.Arrays.toString(method.getParameterTypes()));
        }
    }

    /**
     * Pushes one fake turn-by-turn navigation for a few seconds, the way jiopia's ECARX port does:
     * a {@link java.lang.reflect.Proxy} stands in for {@code INavigationInfo}, then the getters are
     * refreshed once a second. Ends with {@code notifyTurnByTurnStopped} so the cluster is left as
     * it was found. Runs on a worker thread; the command channel must stay responsive.
     */
    static void pushTest(Context context) {
        if (!SUPPORTED_DEVICE.equals(android.os.Build.DEVICE)) {
            ProbeLog.i("gated: Build.DEVICE=" + android.os.Build.DEVICE + " != " + SUPPORTED_DEVICE);
            return;
        }
        new Thread(() -> {
            try {
                pushTestBlocking(context);
            } catch (Throwable error) {
                ProbeLog.e("dimnav failed", error);
            }
        }, "carprobe-dimnav").start();
    }

    private static void pushTestBlocking(Context context) throws Exception {
        ProbeLog.i("=== DIM navi push test (fake route, 10 s) ===");
        Class<?> dimClass = WifiApi.load(CLS_DIM);
        Class<?> infoClass = WifiApi.load(CLS_INFO);
        if (dimClass == null || infoClass == null) {
            ProbeLog.i("DimInteraction or INavigationInfo unreachable; push test skipped");
            return;
        }
        Object dim = null;
        for (Method method : dimClass.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getName().equals("create")) {
                Object result = method.invoke(null, context);
                if (result != null) {
                    dim = result;
                    break;
                }
            }
        }
        if (dim == null) {
            ProbeLog.i("DimInteraction.create returned null; push test skipped");
            return;
        }
        Object navi = invoke(dim, "getNaviInteraction");
        if (navi == null) {
            ProbeLog.i("getNaviInteraction() returned null; push test skipped");
            return;
        }
        Object info = java.lang.reflect.Proxy.newProxyInstance(
                infoClass.getClassLoader(), new Class<?>[]{infoClass}, new InfoHandler(9999, "PROBE"));
        ProbeLog.i("fake INavigationInfo = " + info.getClass().getName());
        ProbeLog.i("notifyNavigationStatus(2) -> " + call(navi, "notifyNavigationStatus", 2));
        ProbeLog.i("notifyTurnByTurnStarted() -> " + call(navi, "notifyTurnByTurnStarted"));
        for (int i = 0; i < 10; i++) {
            ProbeLog.i("push " + i + ": updateNavigationInfo -> " + call(navi, "updateNavigationInfo", info));
            call(navi, "updateDistanceToDestination", 450);
            call(navi, "updateDistanceToNextGuidancePoint", 300);
            call(navi, "updateETA", 670);
            call(navi, "updateNextGuidancePointName", "PROBE");
            call(navi, "updateTurnByTurnArrow", 9999);
            Thread.sleep(1000);
        }
        ProbeLog.i("notifyTurnByTurnStopped() -> " + call(navi, "notifyTurnByTurnStopped"));
        ProbeLog.i("notifyNavigationStatus(3) -> " + call(navi, "notifyNavigationStatus", 3));
        ProbeLog.i("=== DIM navi push test done (clusters restored to stopped) ===");
    }

    private static Object call(Object target, String name, Object... arguments) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) {
                continue;
            }
            try {
                Class<?>[] types = method.getParameterTypes();
                Object[] coerced = new Object[arguments.length];
                for (int i = 0; i < arguments.length; i++) {
                    Object value = arguments[i];
                    if (types[i] == int.class && value instanceof Integer) {
                        coerced[i] = value;
                    } else {
                        coerced[i] = value;
                    }
                }
                return method.invoke(target, coerced);
            } catch (Throwable error) {
                return ProbeLog.describe(HudApi.unwrap(error));
            }
        }
        return "no such method";
    }

    /** The 13 getters of INavigationInfo, answered with one fake route. */
    private static final class InfoHandler implements java.lang.reflect.InvocationHandler {
        private final int turnId;
        private final String name;

        InfoHandler(int turnId, String name) {
            this.turnId = turnId;
            this.name = name;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getNavigationTurnId": return turnId;
                case "getDistanceToDestination": return 450;
                case "getDistanceToNextGuidancePoint": return 300;
                case "getETA": return 670;
                case "getNextGuidancePointName": return name;
                case "getNavigationStatus": return 2;
                case "getDayNightMode": return 0;
                case "getExtensionInfo": return new android.os.Bundle();
                case "toString": return "InfoHandler(PROBE)";
                case "hashCode": return 9999;
                case "equals": return proxy == (args == null || args.length == 0 ? null : args[0]);
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            if (type == short.class) return (short) 0;
            if (type == byte.class) return (byte) 0;
            if (type == char.class) return (char) 0;
            return null;
        }
    }

    /**
     * Pushes one cluster turn id for a few seconds, so the car's own id vocabulary can be read off
     * the cluster: run `dimturn 2`, `dimturn 3`, ... and note which arrow appears.
     */
    static void pushTurn(Context context, int turnId) {
        if (!SUPPORTED_DEVICE.equals(android.os.Build.DEVICE)) {
            ProbeLog.i("gated: Build.DEVICE=" + android.os.Build.DEVICE + " != " + SUPPORTED_DEVICE);
            return;
        }
        new Thread(() -> {
            try {
                ProbeLog.i("=== dimturn " + turnId + " (5 s) ===");
                Object dim = null;
                Class<?> dimClass = WifiApi.load(CLS_DIM);
                if (dimClass == null) {
                    ProbeLog.i("DimInteraction unreachable");
                    return;
                }
                for (Method method : dimClass.getMethods()) {
                    if (Modifier.isStatic(method.getModifiers()) && method.getName().equals("create")) {
                        Object result = method.invoke(null, context);
                        if (result != null) {
                            dim = result;
                            break;
                        }
                    }
                }
                if (dim == null) {
                    ProbeLog.i("DimInteraction.create returned null");
                    return;
                }
                Object navi = invoke(dim, "getNaviInteraction");
                if (navi == null) {
                    ProbeLog.i("getNaviInteraction() returned null");
                    return;
                }
                Class<?> infoClass = WifiApi.load(CLS_INFO);
                if (infoClass == null) {
                    ProbeLog.i("INavigationInfo unreachable");
                    return;
                }
                Object info = java.lang.reflect.Proxy.newProxyInstance(
                        infoClass.getClassLoader(), new Class<?>[]{infoClass},
                        new InfoHandler(turnId, "TURN " + turnId));
                call(navi, "notifyNavigationStatus", 2);
                call(navi, "notifyTurnByTurnStarted");
                for (int i = 0; i < 5; i++) {
                    ProbeLog.i("dimturn " + turnId + " push " + i
                            + " navInfo -> " + call(navi, "updateNavigationInfo", info)
                            + " turn -> " + call(navi, "updateTurnByTurnArrow", turnId));
                    call(navi, "updateDistanceToNextGuidancePoint", 450);
                    call(navi, "updateDistanceToDestination", 2000);
                    call(navi, "updateETA", 670);
                    call(navi, "updateNextGuidancePointName", "TURN " + turnId);
                    Thread.sleep(1000);
                }
                call(navi, "notifyTurnByTurnStopped");
                ProbeLog.i("=== dimturn " + turnId + " done ===");
            } catch (Throwable error) {
                ProbeLog.e("dimturn failed", error);
            }
        }, "carprobe-dimturn").start();
    }

    /**
     * Pushes one lane layout (count lanes, all with the same icon id) for a few seconds. The client
     * side takes `INaviInteraction$ILaneInfo[]`, so the probe answers that interface with a Proxy
     * and lets the car's own icon ids be read off the cluster.
     */
    static void pushLanes(Context context, int iconId, int count) {
        if (!SUPPORTED_DEVICE.equals(android.os.Build.DEVICE)) {
            ProbeLog.i("gated: Build.DEVICE=" + android.os.Build.DEVICE + " != " + SUPPORTED_DEVICE);
            return;
        }
        new Thread(() -> {
            try {
                ProbeLog.i("=== dimlane icon=" + iconId + " count=" + count + " (5 s) ===");
                Class<?> dimClass = WifiApi.load(CLS_DIM);
                Class<?> laneClass = WifiApi.load(CLS_LANE);
                Class<?> infoClass = WifiApi.load(CLS_INFO);
                if (dimClass == null || laneClass == null || infoClass == null) {
                    ProbeLog.i("DimInteraction / ILaneInfo / INavigationInfo unreachable");
                    return;
                }
                Object dim = null;
                for (Method method : dimClass.getMethods()) {
                    if (Modifier.isStatic(method.getModifiers()) && method.getName().equals("create")) {
                        Object result = method.invoke(null, context);
                        if (result != null) {
                            dim = result;
                            break;
                        }
                    }
                }
                if (dim == null) {
                    ProbeLog.i("DimInteraction.create returned null");
                    return;
                }
                Object navi = invoke(dim, "getNaviInteraction");
                if (navi == null) {
                    ProbeLog.i("getNaviInteraction() returned null");
                    return;
                }
                Object info = java.lang.reflect.Proxy.newProxyInstance(
                        infoClass.getClassLoader(), new Class<?>[]{infoClass},
                        new InfoHandler(102, "LANE " + iconId));
                Object lanes = java.lang.reflect.Array.newInstance(laneClass, count);
                for (int i = 0; i < count; i++) {
                    final int laneNumber = i + 1;
                    Object lane = java.lang.reflect.Proxy.newProxyInstance(
                            laneClass.getClassLoader(), new Class<?>[]{laneClass},
                            (proxy, method, args) -> {
                                switch (method.getName()) {
                                    case "getLaneNumber": return laneNumber;
                                    case "getLaneBackIconId": return iconId;
                                    case "toString": return "ILaneInfo(" + laneNumber + "," + iconId + ")";
                                    case "hashCode": return laneNumber;
                                    case "equals": return proxy == (args == null || args.length == 0 ? null : args[0]);
                                    default: return defaultValue(method.getReturnType());
                                }
                            });
                    java.lang.reflect.Array.set(lanes, i, lane);
                }
                call(navi, "notifyNavigationStatus", 2);
                call(navi, "notifyTurnByTurnStarted");
                for (int i = 0; i < 5; i++) {
                    ProbeLog.i("dimlane push " + i + " navInfo -> "
                            + call(navi, "updateNavigationInfo", info)
                            + " lanes -> " + call(navi, "updateLaneInfo", lanes));
                    call(navi, "updateTurnByTurnArrow", 102);
                    call(navi, "updateDistanceToNextGuidancePoint", 450);
                    call(navi, "updateNextGuidancePointName", "LANE " + iconId);
                    Thread.sleep(1000);
                }
                call(navi, "notifyTurnByTurnStopped");
                ProbeLog.i("=== dimlane done ===");
            } catch (Throwable error) {
                ProbeLog.e("dimlane failed", error);
            }
        }, "carprobe-dimlane").start();
    }
}
