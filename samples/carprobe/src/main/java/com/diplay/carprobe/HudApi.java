package com.diplay.carprobe;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Read-only probe of the ECARX adaptapi HUD surface.
 *
 * <p>The head-unit reverse-engineering flagged {@code adaptapi} HUD access as the one unresolved
 * channel. Measured so far on this car (uid 10059, Android 11):
 * <ul>
 *   <li>{@code HUDInteraction.create(Context)} is a stub that returns {@code null};</li>
 *   <li>{@code HUDInteractionImpl} is the real implementation but its constructor is private and
 *       registering it makes the vendor signal callback crash (a Float/Integer cast bug inside the
 *       vendor code), so it runs in its own process ({@link HudImplProbeActivity});</li>
 *   <li>{@code ecarx.car.hardware.vehicle.ECarXCarVfhudManager} has public constructors and
 *       {@code getPA_VF_HUD_*} getters;</li>
 *   <li>{@code com.ecarx.xui.adaptapi.car.Car.create(Context)} returns an {@code ICar}.</li>
 * </ul>
 *
 * <p>{@code ecarx.adaptapi.jar} and {@code ecarx.car.jar} are on the boot classpath (the device
 * ships {@code boot-ecarx.adaptapi.vdex} / {@code boot-ecarx.car.vdex}), so {@code Class.forName}
 * finds them without any vendor jar on the app classpath. Nothing vendor-owned is copied: classes
 * are reached by name and every call is reflective.
 *
 * <p>Only reads run ({@code get*}, {@code request*}, {@code build*}, {@code is*}); no
 * {@code set*}/{@code update*}/{@code CB_*} method is ever invoked.
 */
final class HudApi {

    static final String CLS_HUD_INTERACTION = "com.ecarx.xui.adaptapi.hudinteraction.HUDInteraction";
    static final String CLS_HUD_INTERACTION_IMPL = "com.ecarx.xui.adaptapi.hudinteraction.HUDInteractionImpl";

    /** The adaptapi vehicle-function wrapper for HUD (its field is the ECARX vfhud manager). */
    static final String CLS_HUD_FUNCTION = "com.ecarx.xui.adaptapi.car.vehicle.HUD";
    static final String CLS_IHUD = "com.ecarx.xui.adaptapi.car.vehicle.IHUD";
    static final String CLS_CAR = "com.ecarx.xui.adaptapi.car.Car";
    static final String CLS_ICAR = "com.ecarx.xui.adaptapi.car.ICar";
    static final String CLS_IVEHICLE = "com.ecarx.xui.adaptapi.car.vehicle.IVehicle";
    static final String CLS_VEHICLE_FUNCTION = "com.ecarx.xui.adaptapi.car.IVehicleFunction";

    /** The ECARX vehicle property manager behind HUD (needs Car/Vendor permission). */
    static final String CLS_VFHUD = "ecarx.car.hardware.vehicle.ECarXCarVfhudManager";

    /** {@code IVehicle} alone declares hundreds of inner enums; only print a sample. */
    private static final int MAX_INNER_TYPES = 12;

    private static final String[] HUD_INTERACTION_CALLS = {
            "requestHUDMode", "requestADASOpenStatus", "requestCarFollowingGAPLevel",
            "requestCalibrationMode", "requestHUDHeight", "requestADASSyncInfo",
            "requestVehicleSyncInfo",
    };

    private static final String[] HUD_FUNCTION_IDS = {
            "SETTING_FUNC_HUD_ACTIVE",
            "SETTING_FUNC_HUD_CALIBRATION",
            "SETTING_FUNC_HUD_BRIGHTNESS_ADJUST",
            "SETTING_FUNC_HUD_POSITION_ADJUST",
            "SETTING_FUNC_HUD_ANGLE_ADJUST",
            "SETTING_FUNC_HUD_ANGLE_RESET",
            "SETTING_FUNC_HUD_SNOW_MODE",
            "SETTING_FUNC_HUD_AR_ENGINE",
            "SETTING_FUNC_HUD_DISPLAY_NAVI",
    };

    private HudApi() {
    }

    /** The safe pass: adaptapi car objects only, no vendor signal listeners. */
    static void readAll(Context context) {
        ProbeLog.i("=== HUD adaptapi probe v3 (safe): car API ===");
        dumpApi(CLS_IHUD);
        dumpApi(CLS_VEHICLE_FUNCTION);
        dumpApi(CLS_CAR);
        dumpApi(CLS_ICAR);
        dumpApi(CLS_IVEHICLE);
        dumpApi(CLS_HUD_FUNCTION);

        readHudFunction(context);
        readCarChain(context);
        ProbeLog.i("=== HUD adaptapi safe probe done ===");
    }

    // --- adaptapi car function (IHUD ids) ----------------------------------

    private static void readHudFunction(Context context) {
        ProbeLog.i("-- adaptapi car HUD function --");
        Class<?> ihud = load(CLS_IHUD);
        if (ihud != null) {
            for (String field : HUD_FUNCTION_IDS) {
                try {
                    ProbeLog.i("IHUD." + field + " = " + ihud.getField(field).get(null));
                } catch (Throwable error) {
                    ProbeLog.i("IHUD." + field + " -> " + ProbeLog.describe(unwrap(error)));
                }
            }
        }
        Object function = construct(CLS_HUD_FUNCTION, context);
        if (function == null) {
            ProbeLog.i("could not build " + CLS_HUD_FUNCTION);
            return;
        }
        invoke(function, "buildFunctions");
        readAnyNoArgGetters(function, "HUD");
        readFunctionStatus(function);
    }

    /** {@code Car.create(context)} -> vehicle -> HUD, and each object's function status. */
    private static void readCarChain(Context context) {
        ProbeLog.i("-- adaptapi Car chain --");
        Object car = callStatic(CLS_CAR, "create", context);
        if (car == null) {
            return;
        }
        Object vehicle = invoke(car, "getVehicle");
        if (vehicle == null) {
            vehicle = invoke(car, "getIVehicle");
        }
        if (vehicle == null) {
            return;
        }
        Object hud = invoke(vehicle, "getHUD");
        if (hud == null) {
            hud = invoke(vehicle, "getHud");
        }
        if (hud != null) {
            invoke(hud, "buildFunctions");
            readFunctionStatus(hud);
        }
    }

    /** Call {@code getFunctionStatus(id)} for every {@code IHUD} id, if the API is present. */
    private static void readFunctionStatus(Object function) {
        Method status = findMethod(function.getClass(), "getFunctionStatus", 1);
        if (status == null) {
            ProbeLog.i("no getFunctionStatus(id) on " + function.getClass().getSimpleName());
            return;
        }
        Class<?> ihud = load(CLS_IHUD);
        if (ihud == null) {
            return;
        }
        for (String field : HUD_FUNCTION_IDS) {
            try {
                int id = ihud.getField(field).getInt(null);
                Object value = status.invoke(function, id);
                ProbeLog.i(function.getClass().getSimpleName() + ".getFunctionStatus(" + field + "=" + id
                        + ") -> " + value);
                if (value != null) {
                    dumpFields(value);
                }
            } catch (Throwable error) {
                ProbeLog.i(function.getClass().getSimpleName() + ".getFunctionStatus(" + field + ") -> "
                        + ProbeLog.describe(unwrap(error)));
            }
        }
    }

    // --- risky pass, own process (see HudImplProbeActivity) -----------------

    /**
     * Reaches the real implementation directly. Both the private {@code HUDInteractionImpl}
     * constructor and the {@code ECarXCarVfhudManager} constructors connect to the car signal
     * stack, which on this build crashes the calling process, so this runs out of process.
     */
    static void readRisky(Context context) {
        ProbeLog.i("=== HUD adaptapi probe v3 (risky): impl in own process ===");
        Object impl = construct(CLS_HUD_INTERACTION_IMPL, context);
        if (impl != null) {
            for (String name : HUD_INTERACTION_CALLS) {
                invoke(impl, name);
            }
            Object param = invoke(impl, "getHUDCalibrationParam");
            if (param != null) {
                dumpFields(param);
            }
            registerCallback(impl);
        }
        readVfhud(context);
        ProbeLog.i("=== HUD adaptapi risky probe done ===");
    }

    private static void readVfhud(Context context) {
        ProbeLog.i("-- ECarXCarVfhudManager --");
        Class<?> cls = load(CLS_VFHUD);
        if (cls == null) {
            return;
        }
        Object manager = null;
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            try {
                ctor.setAccessible(true);
                Object instance = ctor.getParameterCount() == 0
                        ? ctor.newInstance() : ctor.newInstance(new Object[]{null});
                ProbeLog.i("new " + cls.getSimpleName() + "(" + Arrays.toString(ctor.getParameterTypes())
                        + ") -> " + instance);
                if (instance != null) {
                    manager = instance;
                }
                break;
            } catch (Throwable error) {
                ProbeLog.i("new " + cls.getSimpleName() + "(" + Arrays.toString(ctor.getParameterTypes())
                        + ") -> " + ProbeLog.describe(unwrap(error)));
            }
        }
        if (manager == null) {
            return;
        }
        for (Method method : cls.getMethods()) {
            String name = method.getName();
            if (method.getParameterCount() != 0 || method.isSynthetic() || !name.startsWith("getPA_")) {
                continue;
            }
            try {
                ProbeLog.i(cls.getSimpleName() + "." + name + "() -> " + method.invoke(manager));
            } catch (Throwable error) {
                ProbeLog.i(cls.getSimpleName() + "." + name + "() -> " + ProbeLog.describe(unwrap(error)));
            }
        }
    }

    // --- reflection helpers ------------------------------------------------

    static Class<?> load(String name) {
        try {
            return Class.forName(name, false, HudApi.class.getClassLoader());
        } catch (Throwable error) {
            ProbeLog.i("Class.forName(" + name + ") -> " + ProbeLog.describe(error));
            return null;
        }
    }

    /** Every declared member, so a run also reports the API shape we could not statically see. */
    static void dumpApi(String className) {
        Class<?> cls = load(className);
        if (cls == null) {
            return;
        }
        ProbeLog.i("-- API " + cls.getName() + " extends " + cls.getSuperclass()
                + " implements " + Arrays.toString(cls.getInterfaces()));
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            ProbeLog.i("   <init> " + Modifier.toString(ctor.getModifiers()) + " "
                    + Arrays.toString(ctor.getParameterTypes()));
        }
        Method[] methods = cls.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        for (Method method : methods) {
            if (method.isSynthetic() || method.getName().startsWith("lambda$")
                    || method.getName().startsWith("access$")) {
                continue;
            }
            ProbeLog.i("   " + Modifier.toString(method.getModifiers()) + " "
                    + method.getReturnType().getSimpleName() + " " + method.getName()
                    + Arrays.toString(method.getParameterTypes()));
        }
        Class<?>[] inners = cls.getDeclaredClasses();
        for (int i = 0; i < inners.length && i < MAX_INNER_TYPES; i++) {
            ProbeLog.i("   inner " + inners[i].getName() + " interface=" + inners[i].isInterface());
        }
        if (inners.length > MAX_INNER_TYPES) {
            ProbeLog.i("   (" + inners.length + " inner types total; listed the first "
                    + MAX_INNER_TYPES + ")");
        }
    }

    private static Object construct(String className, Context context) {
        Class<?> cls = load(className);
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
            ProbeLog.i(className + "(context) failed -> " + ProbeLog.describe(unwrap(error)));
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
            ProbeLog.i(className + "." + methodName + "(context) failed -> " + ProbeLog.describe(unwrap(error)));
            return null;
        }
    }

    private static Object invoke(Object target, String methodName) {
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
                    + ProbeLog.describe(unwrap(error)));
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

    /** Register every callback interface the object accepts, logging each invocation. */
    private static void registerCallback(Object target) {
        Method register = findMethod(target.getClass(), "registerCallback", 1);
        if (register == null) {
            ProbeLog.i("no registerCallback(1) on " + target.getClass().getSimpleName());
            return;
        }
        Class<?> callbackType = register.getParameterTypes()[0];
        ProbeLog.i("callback interface = " + callbackType.getName()
                + " methods=" + Arrays.toString(callbackType.getMethods()));
        Object proxy = Proxy.newProxyInstance(callbackType.getClassLoader(),
                new Class<?>[]{callbackType}, (p, method, args) -> {
                    ProbeLog.i("HUD callback " + method.getName()
                            + (method.getParameterCount() == 0 ? "()" : "(" + Arrays.toString(args) + ")"));
                    return defaultValue(method.getReturnType());
                });
        try {
            ProbeLog.i("registerCallback(cb) -> " + register.invoke(target, proxy));
        } catch (Throwable error) {
            ProbeLog.i("registerCallback(cb) -> " + ProbeLog.describe(unwrap(error)));
        }
    }

    /** Call every no-arg {@code get*}/{@code is*} the object exposes, to read current values. */
    private static void readAnyNoArgGetters(Object target, String tag) {
        for (Method method : target.getClass().getDeclaredMethods()) {
            String name = method.getName();
            if (method.getParameterCount() != 0 || method.isSynthetic()) {
                continue;
            }
            if (!(name.startsWith("get") || name.startsWith("is"))) {
                continue;
            }
            try {
                method.setAccessible(true);
                ProbeLog.i(tag + "." + name + "() -> " + method.invoke(target));
            } catch (Throwable error) {
                ProbeLog.i(tag + "." + name + "() -> " + ProbeLog.describe(unwrap(error)));
            }
        }
    }

    private static void dumpFields(Object value) {
        for (Field field : value.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                ProbeLog.i("   " + value.getClass().getSimpleName() + "." + field.getName()
                        + " = " + field.get(value));
            } catch (Throwable error) {
                ProbeLog.i("   " + value.getClass().getSimpleName() + "." + field.getName()
                        + " -> " + ProbeLog.describe(unwrap(error)));
            }
        }
    }

    static Throwable unwrap(Throwable error) {
        if (error instanceof InvocationTargetException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == long.class) {
            return 0L;
        }
        return 0;
    }
}
