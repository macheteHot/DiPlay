package com.diplay.carprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * One screen, one pass: what the head unit lets a normal app do with HUD / instrument APIs.
 *
 * <p>Every button is safe to press; the only calls that touch the car are the HUD adjust ones
 * (behind a confirm dialog) and the navigation pushes (stop with "导航结束").
 */
public final class ProbeActivity extends Activity {

    private TextView logView;
    private SurfaceView mapSurfaceView;

    private OneOsHub hub;
    private MapWidget mapWidget;
    private IBinder myCar;
    private IBinder naviServer;
    private IBinder naviMapServer;
    private ProbePresentation presentation;

    private final NaviApi.EventNotify naviEventNotify = new NaviApi.EventNotify();
    private final NaviApi.Observer naviObserver = new NaviApi.Observer();
    private final OneOsHub.ServiceManagerCallback hubCallback = new OneOsHub.ServiceManagerCallback();
    private final NaviApi.ServerCallback naviServerCallback = new NaviApi.ServerCallback();
    private IBinder serviceBinder;
    private int naviProbeAttempts;
    private boolean naviLaunchMap;
    private Runnable pendingNaviAction;
    private Runnable naviHold;
    private File commandFile;

    // One colour per functional group, so a press can be traced to its group at a glance.
    private static final int COLOR_DISPLAY = 0xFF1565C0;
    private static final int COLOR_DIM = 0xFF2E7D32;
    private static final int COLOR_MAP = 0xFFEF6C00;
    private static final int COLOR_ONEOS = 0xFF6A1B9A;
    private static final int COLOR_NAVI = 0xFFC62828;
    private static final int COLOR_TOOLS = 0xFF455A64;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable readOnlyCheck = this::readOnlyHudCheck;

    private boolean naviRunning;
    private int naviTicks;
    private int stepIndex;
    private int distanceToNext = 800;
    private int distanceToDestination = 12500;

    private static final int[] TURN_IDS = {1, 2, 3, 4, 5, 6, 7, 8};
    private static final String[] STEP_NAMES = {"西二旗北路", "上地十街", "信息路", "中关村大街", "北四环西路"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        ProbeLog.attach(logView);
        ProbeLog.i("== CarProbe start ==");
        ProbeLog.i("package=" + getPackageName() + " uid=" + android.os.Process.myUid()
                + " sdk=" + android.os.Build.VERSION.SDK_INT + " device=" + android.os.Build.DEVICE);

        hub = new OneOsHub(this);
        mapWidget = new MapWidget(this);
        // Bind both up front: "HUD 只读全检" should not need a second press.
        hub.bind(connected -> ProbeLog.i("OneOS hub ready=" + connected));
        mapWidget.bind(connected -> ProbeLog.i("map widget service ready=" + connected));
        // And run the read-only pass automatically, so a probe run can be read from logcat alone.
        handler.postDelayed(readOnlyCheck, 2500);
        startCommandChannel();

        // Optional scripted steps, so a run can be driven from adb without tapping the screen:
        //   --ez read false --ei present <displayId> --ei map_source <sourceType> --ei map_mode <m>
        //   --ez navi true --ez cluster_expand true
        android.content.Intent intent = getIntent();
        if (!intent.getBooleanExtra("read", true)) {
            handler.removeCallbacks(readOnlyCheck);
        }
        int presentDisplay = intent.getIntExtra("present", Integer.MIN_VALUE);
        if (presentDisplay != Integer.MIN_VALUE) {
            Display display = ((DisplayManager) getSystemService(Context.DISPLAY_SERVICE)).getDisplay(presentDisplay);
            if (display != null) {
                handler.postDelayed(() -> showPresentation(display), 3000);
            } else {
                ProbeLog.i("no display with id " + presentDisplay);
            }
        }
        int mapSource = intent.getIntExtra("map_source", Integer.MIN_VALUE);
        if (mapSource != Integer.MIN_VALUE) {
            int mode = intent.getIntExtra("map_mode", 1);
            handler.postDelayed(() -> mapSurface(mapSource, mode), 3000);
            // The map waits up to 10 s for its HUD/widget engine to initialise, so ask again later:
            // isMapRunning() flips to true only once it actually started rendering into our Surface.
            handler.postDelayed(() -> mapWidget.isMapRunning(mapSource), 16000);
        }
        if (intent.getBooleanExtra("navi", false)) {
            handler.postDelayed(this::startNavi, 3000);
        }
        if (intent.getBooleanExtra("cluster_expand", false)) {
            handler.postDelayed(this::requestClusterExpand, 3000);
        }
        if (intent.getBooleanExtra("project_on", false)) {
            handler.postDelayed(() -> DimProtocol.setProjectScreen(true), 3000);
        }
        if (intent.getBooleanExtra("project_off", false)) {
            handler.postDelayed(() -> DimProtocol.setProjectScreen(false), 3000);
        }
        // OneOS navigation server (DIM/HUD), scriptable:
        //   --ei dim_mode <0..3> --ei ar_action <0|1> --ez ar_direct true --ez guide_info true --ez subscribe true
        int dim = intent.getIntExtra("dim_mode", Integer.MIN_VALUE);
        if (dim != Integer.MIN_VALUE) {
            handler.postDelayed(() -> dimMode(dim), 3000);
        }
        int arAction = intent.getIntExtra("ar_action", Integer.MIN_VALUE);
        if (arAction != Integer.MIN_VALUE) {
            handler.postDelayed(() -> arGuide(arAction), 3000);
        }
        if (intent.getBooleanExtra("ar_direct", false)) {
            handler.postDelayed(() -> NaviApi.switchARNavigation(navi(), true), 3000);
        }
        if (intent.getBooleanExtra("guide_info", false)) {
            handler.postDelayed(this::guideInfo, 3000);
        }
        if (intent.getBooleanExtra("subscribe", false)) {
            handler.postDelayed(() -> subscribeEvents(true), 3000);
        }
        if (intent.getBooleanExtra("ifaces", false)) {
            handler.postDelayed(this::logInterfaceNames, 3000);
        }
        if (intent.getBooleanExtra("probe_navi", false)) {
            handler.postDelayed(this::probeNaviService, 3000);
        }
        if (intent.getBooleanExtra("hud_test", false)) {
            handler.postDelayed(this::hudTestSequence, 3000);
        }
        if (intent.getBooleanExtra("hud_cycle", false)) {
            handler.postDelayed(this::hudCycleSequence, 3000);
        }
        if (intent.getBooleanExtra("hud_api", false)) {
            handler.postDelayed(() -> HudApi.readAll(this), 3000);
        }
        if (intent.getBooleanExtra("hud_impl", false)) {
            handler.postDelayed(() -> startActivity(new Intent(this, HudImplProbeActivity.class)), 3000);
        }
        if (intent.getBooleanExtra("amb_li", false)) {
            handler.postDelayed(() -> AmbientApi.readAll(this), 3000);
        }
        if (intent.getBooleanExtra("amb_music", false)) {
            handler.postDelayed(() -> AmbientApi.enableOemMusicShow(this), 3000);
        }
        if (intent.getBooleanExtra("amb_restore", false)) {
            handler.postDelayed(() -> AmbientApi.restoreOemAmbient(this), 3000);
        }
        if (intent.getBooleanExtra("amb_probe", false)) {
            handler.postDelayed(() -> AmbientApi.runControlProbe(this), 3000);
        }
        if (intent.getBooleanExtra("amb_marquee", false)) {
            handler.postDelayed(() -> AmbientApi.startMarquee(this), 3000);
        }
        if (intent.getBooleanExtra("amb_steady", false)) {
            handler.postDelayed(() -> AmbientApi.steadyColor(this), 3000);
        }
        if (intent.getBooleanExtra("amb_wheel", false)) {
            handler.postDelayed(() -> AmbientApi.startColorWheel(this), 3000);
        }
        if (intent.getBooleanExtra("amb_spectrum", false)) {
            handler.postDelayed(() -> AmbientApi.readSpectrum(this), 3000);
        }
        if (intent.getBooleanExtra("amb_visualizer", false)) {
            handler.postDelayed(() -> AmbientApi.readLocalVisualizer(this), 3000);
        }
        if (intent.getBooleanExtra("amb_force_music", false)) {
            handler.postDelayed(() -> AmbientApi.forceMusicShow(this), 3000);
        }
        if (intent.getBooleanExtra("wifi_probe", false)) {
            handler.postDelayed(() -> WifiApi.readAll(this), 3000);
        }
        if (intent.getBooleanExtra("wifi_bringup", false)) {
            handler.postDelayed(() -> WifiApi.bringUp(this), 3000);
        }
    }

    @Override
    protected void onDestroy() {
        stopNavi();
        if (mapWidget != null) {
            mapWidget.unbind();
        }
        if (hub != null) {
            hub.unbind();
        }
        if (presentation != null) {
            presentation.release();
            presentation = null;
        }
        super.onDestroy();
    }

    // --- UI ----------------------------------------------------------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF0F1216);

        // Left: log, with the map-Surface preview under it.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(0xFFE6E6E6);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(12), dp(12), dp(12), dp(12));
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(logView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        left.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mapSurfaceView = new SurfaceView(this);
        left.addView(mapSurfaceView, new LinearLayout.LayoutParams(dp(320), dp(180)));
        root.addView(left, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.6f));

        // Right: three accordion columns.
        ScrollView buttonScroll = new ScrollView(this);
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(0, 0, dp(6), dp(8));
        buttonScroll.addView(columns);
        root.addView(buttonScroll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout colA = addColumn(columns);
        LinearLayout colB = addColumn(columns);
        LinearLayout colC = addColumn(columns);

        LinearLayout display = addGroup(colA, "显示 / 投屏", COLOR_DISPLAY);
        addButton(display, "列显示", COLOR_DISPLAY, this::dumpDisplays);
        addButton(display, "投屏…", COLOR_DISPLAY, this::chooseDisplay);
        addButton(display, "关投屏", COLOR_DISPLAY, this::hidePresentation);

        LinearLayout dim = addGroup(colB, "DIM 协议", COLOR_DIM);
        addButton(dim, "DIM 读取", COLOR_DIM, DimProtocol::readHudState);
        addToggle(dim, "DIM 投屏", COLOR_DIM, false, DimProtocol::setProjectScreen);
        addButton(dim, "投影状态", COLOR_DIM, DimProtocol::readProjectScreenStatus);
        addButton(dim, "导航模式1", COLOR_DIM, () -> DimProtocol.setNaviMode(1));
        addButton(dim, "导航模式0", COLOR_DIM, () -> DimProtocol.setNaviMode(0));
        addButton(dim, "导航开始", COLOR_DIM, this::startNavi);
        addButton(dim, "导航刷新", COLOR_DIM, this::tickNavi);
        addButton(dim, "导航结束", COLOR_DIM, this::stopNavi);

        LinearLayout map = addGroup(colC, "地图 Surface", COLOR_MAP);
        addButton(map, "地图Surface HUD", COLOR_MAP, () -> mapSurface(1, 1));
        addButton(map, "地图Surface 副屏", COLOR_MAP, () -> mapSurface(0, 1));
        addButton(map, "地图Surface 关", COLOR_MAP, () -> removeMapSurface(1));

        LinearLayout oneos = addGroup(colA, "OneOS Surface", COLOR_ONEOS);
        addButton(oneos, "OneOS Surface HUD", COLOR_ONEOS, () -> oneOsSurface(1));
        addButton(oneos, "OneOS Surface 副屏", COLOR_ONEOS, () -> oneOsSurface(0));
        addButton(oneos, "OneOS Surface 关", COLOR_ONEOS, () -> removeOneOsSurface(1));

        LinearLayout navi = addGroup(colB, "OneOS 导航", COLOR_NAVI);
        addButton(navi, "仪表显示 关", COLOR_NAVI, () -> dimMode(NaviApi.DIM_OFF));
        addButton(navi, "仪表显示 简", COLOR_NAVI, () -> dimMode(NaviApi.DIM_SIMPLIFY));
        addButton(navi, "仪表显示 全", COLOR_NAVI, () -> dimMode(NaviApi.DIM_FULL));
        addButton(navi, "仪表显示 AR", COLOR_NAVI, () -> dimMode(NaviApi.DIM_AR));
        addToggle(navi, "AR导航", COLOR_NAVI, false, on -> arGuide(on ? NaviApi.AR_ON : NaviApi.AR_OFF));
        addToggle(navi, "switchAR", COLOR_NAVI, false, on -> withNavi(() -> NaviApi.switchARNavigation(navi(), on)));
        addButton(navi, "取导航信息", COLOR_NAVI, this::guideInfo);
        addButton(navi, "取navi服务", COLOR_NAVI, this::probeNaviService);
        addButton(navi, "取navi(启动)", COLOR_NAVI, () -> probeNaviService(true));
        addButton(navi, "navi 探测", COLOR_NAVI, () -> withNavi(() -> NaviApi.inspect(navi())));
        addButton(navi, "HUD 测试", COLOR_NAVI, this::hudTestSequence);
        addButton(navi, "HUD 循环", COLOR_NAVI, this::hudCycleSequence);
        addToggle(navi, "事件订阅", COLOR_NAVI, false, this::subscribeEvents);
        addButton(navi, "接口名", COLOR_NAVI, this::logInterfaceNames);

        LinearLayout tools = addGroup(colC, "工具 / 连接", COLOR_TOOLS);
        addButton(tools, "HUD 只读全检", COLOR_TOOLS, this::readOnlyHudCheck);
        addButton(tools, "HUD adaptapi", COLOR_TOOLS, () -> HudApi.readAll(this));
        addButton(tools, "HUD impl(独立进程)", COLOR_TOOLS,
                () -> startActivity(new Intent(this, HudImplProbeActivity.class)));
        addButton(tools, "氛围灯 只读", COLOR_TOOLS, () -> AmbientApi.readAll(this));
        addButton(tools, "原厂音乐律动", COLOR_TOOLS, () -> AmbientApi.enableOemMusicShow(this));
        addButton(tools, "还原氛围灯", COLOR_TOOLS, () -> AmbientApi.restoreOemAmbient(this));
        addButton(tools, "氛围灯 控制测试", COLOR_TOOLS, () -> AmbientApi.runControlProbe(this));
        addButton(tools, "氛围灯 跑马灯", COLOR_TOOLS, () -> AmbientApi.startMarquee(this));
        addButton(tools, "氛围灯 常亮单色", COLOR_TOOLS, () -> AmbientApi.steadyColor(this));
        addButton(tools, "氛围灯 色轮", COLOR_TOOLS, () -> AmbientApi.startColorWheel(this));
        addButton(tools, "音频频谱(车机)", COLOR_TOOLS, () -> AmbientApi.readSpectrum(this));
        addButton(tools, "音频频谱(本地)", COLOR_TOOLS, () -> AmbientApi.readLocalVisualizer(this));
        addButton(tools, "强制音乐秀(车机)", COLOR_TOOLS, () -> AmbientApi.forceMusicShow(this));
        addButton(tools, "Wi-Fi/热点(原厂)", COLOR_TOOLS, () -> WifiApi.readAll(this));
        addButton(tools, "开原厂热点(写)", COLOR_TOOLS, () -> WifiApi.bringUp(this));
        addButton(tools, "OneOS 连接", COLOR_TOOLS, () -> hub.bind(c -> ProbeLog.i("OneOS hub ready=" + c)));
        addButton(tools, "OneOS 断开", COLOR_TOOLS, () -> hub.unbind());
        addButton(tools, "HUD 调节…", COLOR_TOOLS, this::hudAdjustDialog);
        addButton(tools, "集群投屏请求", COLOR_TOOLS, this::requestClusterExpand);
        addButton(tools, "清空日志", COLOR_TOOLS, () -> logView.setText(""));

        return root;
    }

    /** One of the three vertical columns that hold the accordion groups. */
    private LinearLayout addColumn(LinearLayout parent) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(column);
        return column;
    }

    /**
     * A collapsible group: a coloured header that expands to show the group's buttons. Keeps the
     * panel readable instead of showing every button at once.
     */
    private LinearLayout addGroup(LinearLayout parent, String title, int color) {
        Button header = new Button(this);
        header.setAllCaps(false);
        header.setTextColor(Color.WHITE);
        header.setBackgroundColor(color);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        header.setMinHeight(dp(58));
        LinearLayout.LayoutParams headerParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(dp(4), dp(6), dp(4), dp(2));
        header.setLayoutParams(headerParams);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setVisibility(View.GONE);

        header.setText(title + "  ▸");
        header.setOnClickListener(view -> {
            boolean show = column.getVisibility() != View.VISIBLE;
            column.setVisibility(show ? View.VISIBLE : View.GONE);
            header.setText(title + (show ? "  ▾" : "  ▸"));
        });

        parent.addView(header);
        parent.addView(column);
        return column;
    }

    private void addButton(LinearLayout column, String label, int color, Runnable action) {
        Button button = newButton(label, color);
        button.setOnClickListener(view -> {
            ProbeLog.i("--- " + label + " ---");
            try {
                action.run();
            } catch (Throwable error) {
                ProbeLog.e(label + " failed", error);
            }
        });
        placeButton(column, button);
    }

    /** One button that flips an on/off state, instead of two. */
    private void addToggle(LinearLayout column, String label, int color, boolean initial,
                           Consumer<Boolean> onChange) {
        final boolean[] state = {initial};
        Button button = newButton(label + " · " + (initial ? "ON" : "OFF"), color);
        button.setOnClickListener(view -> {
            state[0] = !state[0];
            button.setText(label + " · " + (state[0] ? "ON" : "OFF"));
            ProbeLog.i("--- " + label + " -> " + (state[0] ? "ON" : "OFF") + " ---");
            try {
                onChange.accept(state[0]);
            } catch (Throwable error) {
                ProbeLog.e(label + " failed", error);
            }
        });
        placeButton(column, button);
    }

    private Button newButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(color);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setMinHeight(dp(56));
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        button.setLayoutParams(params);
        return button;
    }

    /** One button per row; they stretch to fill the column. */
    private void placeButton(LinearLayout column, Button button) {
        LinearLayout row;
        int last = column.getChildCount() - 1;
        if (last >= 0 && column.getChildAt(last) instanceof LinearLayout
                && ((LinearLayout) column.getChildAt(last)).getChildCount() < 1) {
            row = (LinearLayout) column.getChildAt(last);
        } else {
            row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            column.addView(row);
        }
        row.addView(button);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // --- displays ----------------------------------------------------------

    private Display[] displays() {
        DisplayManager manager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        return manager.getDisplays();
    }

    private void dumpDisplays() {
        for (Display display : displays()) {
            ProbeLog.i("display id=" + display.getDisplayId()
                    + " name=" + display.getName()
                    + " " + display.getWidth() + "x" + display.getHeight()
                    + " flags=0x" + Integer.toHexString(display.getFlags())
                    + " state=" + display.getState()
                    + " rotation=" + display.getRotation()
                    + " refresh=" + display.getRefreshRate()
                    + " presentation=" + ((display.getFlags() & Display.FLAG_PRESENTATION) != 0));
        }
    }

    private void chooseDisplay() {
        Display[] all = displays();
        String[] labels = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            Display display = all[i];
            labels[i] = "id=" + display.getDisplayId() + "  " + display.getWidth() + "x" + display.getHeight()
                    + (display.getDisplayId() == Display.DEFAULT_DISPLAY ? " (default)" : "")
                    + ((display.getFlags() & Display.FLAG_PRESENTATION) != 0 ? " (presentation)" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("投到哪块屏")
                .setItems(labels, (dialog, which) -> showPresentation(all[which]))
                .show();
    }

    private void showPresentation(Display display) {
        hidePresentation();
        try {
            presentation = new ProbePresentation(this, display, display.getDisplayId());
            presentation.show();
        } catch (Throwable error) {
            ProbeLog.e("Presentation.show() on display " + display.getDisplayId(), error);
            presentation = null;
        }
    }

    private void hidePresentation() {
        if (presentation != null) {
            presentation.release();
            presentation = null;
        }
    }

    // --- read-only HUD check ----------------------------------------------

    private void readOnlyHudCheck() {
        dumpDisplays();
        DimProtocol.readHudState();
        DimProtocol.readProjectScreenStatus();
        HudApi.readAll(this);
        AmbientApi.readAll(this);
        if (!hub.isConnected()) {
            ProbeLog.i("OneOS hub not connected; re-binding and finishing the check when it is");
            hub.bind(connected -> {
                ProbeLog.i("OneOS hub ready=" + connected);
                if (connected) {
                    readHudThroughHub();
                }
            });
        } else {
            readHudThroughHub();
        }
        if (mapWidget.isConnected()) {
            mapWidget.isMapRunning(1);
            mapWidget.isMapRunning(0);
        } else {
            ProbeLog.i("map widget service not connected yet");
        }
    }

    private void readHudThroughHub() {
        myCar = hub.service(OneOsHub.TYPE_SETTING_MANAGER);
        naviServer = hub.service(OneOsHub.TYPE_NAVI);
        logInterfaceNames();
        OneOsHub.readHudState(myCar);
    }

    private void logInterfaceNames() {
        ProbeLog.i("interface hub=" + Ipc.interfaceName(hub.raw())
                + " | navi(" + OneOsHub.TYPE_NAVI + ")=" + Ipc.interfaceName(naviServer)
                + " | settingManager(" + OneOsHub.TYPE_SETTING_MANAGER + ")=" + Ipc.interfaceName(myCar));
    }

    // --- HUD adjust (asked first) -----------------------------------------

    private void hudAdjustDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        EditText keyStatus = new EditText(this);
        keyStatus.setText("1");
        keyStatus.setHint("keyStatus (1 = down, 0 = up)");
        EditText keyCode = new EditText(this);
        keyCode.setText("1");
        keyCode.setHint("keyCode");
        layout.addView(keyStatus);
        layout.addView(keyCode);
        ProbeLog.i("HUD adjust: keyStatus drives a virtual key press on the driver's HUD; "
                + "hold-on then hold-off to complete one adjustment");
        new AlertDialog.Builder(this)
                .setTitle("HUD 调节（会真的动 HUD）")
                .setView(layout)
                .setPositiveButton("亮度", (dialog, which) -> sendHudAdjust(0, keyStatus, keyCode))
                .setNeutralButton("位置", (dialog, which) -> sendHudAdjust(1, keyStatus, keyCode))
                .setNegativeButton("角度", (dialog, which) -> sendHudAdjust(2, keyStatus, keyCode))
                .show();
    }

    private void sendHudAdjust(int which, EditText keyStatus, EditText keyCode) {
        if (myCar == null) {
            myCar = hub.service(OneOsHub.TYPE_SETTING_MANAGER);
        }
        OneOsHub.adjustHud(myCar, which, intOf(keyStatus, 1), intOf(keyCode, 1));
    }

    private int intOf(EditText field, int fallback) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void requestClusterExpand() {
        if (myCar == null) {
            myCar = hub.service(OneOsHub.TYPE_SETTING_MANAGER);
        }
        OneOsHub.requestSupportExpand(myCar, getPackageName(), 0, new MapWidget.ClusterExpandListener());
    }

    // --- OneOS navigation server (DIM/HUD display) -------------------------

    private IBinder navi() {
        IBinder resolved = naviServerCallback.naviServer;
        if (resolved != null) {
            naviServer = resolved;
        }
        if (naviServer == null) {
            ProbeLog.i("INaviServer not resolved yet: press 取navi服务 first "
                    + "(getService(1) hands back an IServiceBinder, not the INaviServer)");
        }
        return naviServer;
    }

    /** Runs an action once the real INaviServer is resolved, resolving it first if needed. */
    private void withNavi(Runnable action) {
        if (navi() != null) {
            action.run();
            return;
        }
        pendingNaviAction = action;
        probeNaviService();
        handler.postDelayed(this::runPendingNavi, 1500);
    }

    private void runPendingNavi() {
        if (naviServerCallback.naviServer != null && pendingNaviAction != null) {
            Runnable action = pendingNaviAction;
            pendingNaviAction = null;
            action.run();
        }
    }

    /** Polls the hub for the type-1 binder, then asks it for the real INaviServer. */
    private void probeNaviService() {
        probeNaviService(false);
    }

    private void probeNaviService(boolean launchMap) {
        if (!hub.isConnected()) {
            ProbeLog.i("hub not connected yet; will probe once it is");
            hub.bind(connected -> probeNaviService(launchMap));
            return;
        }
        naviLaunchMap = launchMap;
        naviProbeAttempts = 0;
        hub.registerCallback(hubCallback);
        pollNaviService();
    }

    private void pollNaviService() {
        naviProbeAttempts++;
        IBinder binder = hub.service(OneOsHub.TYPE_NAVI);
        if (binder == null) {
            binder = hub.permissionService(OneOsHub.TYPE_NAVI, getPackageName());
        }
        if (binder != null) {
            serviceBinder = binder;
            ProbeLog.i("type " + OneOsHub.TYPE_NAVI + " binder on attempt " + naviProbeAttempts
                    + " interface=" + Ipc.interfaceName(binder));
            int mapVendor = NaviApi.getDefaultMap(binder);
            NaviApi.getNaviServer(binder, mapVendor, naviLaunchMap, naviServerCallback);
            return;
        }
        if (naviProbeAttempts < 15) {
            handler.postDelayed(this::pollNaviService, 1000);
        } else {
            ProbeLog.i("type " + OneOsHub.TYPE_NAVI + " binder never appeared in "
                    + naviProbeAttempts + " attempts");
        }
    }

    /** Cycle the DIM/HUD display mode so it is easy to watch on the cluster. */
    private void hudCycleSequence() {
        ProbeLog.i("=== HUD cycle: OFF -> SIMPLIFY -> FULL -> AR (3s each) ===");
        probeNaviService(true);
        int[] modes = {NaviApi.DIM_OFF, NaviApi.DIM_SIMPLIFY, NaviApi.DIM_FULL, NaviApi.DIM_AR};
        for (int i = 0; i < modes.length; i++) {
            final int mode = modes[i];
            handler.postDelayed(() -> withNavi(() -> NaviApi.switchDimNaviMode(
                    navi(), naviObserver, mode, getPackageName())), 4000 + i * 3000L);
        }
    }

    // --- command channel (drive the probe from adb without rebuilding) ------

    /**
     * Watches {@code <externalFilesDir>/carprobe.cmd}: write a command line from adb, the probe
     * runs it within a second and deletes the file. A generic {@code raw} command can call any
     * binder transaction, so new experiments do not need a new APK.
     */
    private void startCommandChannel() {
        File dir = getExternalFilesDir(null);
        commandFile = new File(dir == null ? getFilesDir() : dir, "carprobe.cmd");
        ProbeLog.i("command file (write a line to run it): " + commandFile);
        handler.postDelayed(commandPoller, 1000);
    }

    private final Runnable commandPoller = new Runnable() {
        @Override
        public void run() {
            try {
                if (commandFile != null && commandFile.isFile()) {
                    String text = readText(commandFile);
                    //noinspection ResultOfMethodCallIgnored
                    commandFile.delete();
                    if (text != null) {
                        for (String line : text.split("\n")) {
                            String trimmed = line.trim();
                            if (!trimmed.isEmpty()) {
                                executeCommand(trimmed);
                            }
                        }
                    }
                }
            } catch (Throwable error) {
                ProbeLog.e("command channel failed", error);
            }
            handler.postDelayed(this, 1000);
        }
    };

    private String readText(File file) {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } catch (Throwable error) {
            ProbeLog.e("read " + file, error);
        }
        return builder.toString();
    }

    private void executeCommand(String line) {
        ProbeLog.i("> " + line);
        String[] p = line.split("\\s+");
        try {
            switch (p[0]) {
                case "help":
                    ProbeLog.i("commands: dim <0-3> | ar <0|1> | switchar <0|1> | guide | subscribe <0|1> "
                            + "| inspect | cycle | hudapi | hudimpl | ambli | ambmusic | ambrestore | ambprobe | ambmarquee | ambsteady | ambwheel | ambspectrum | ambvisualizer | ambforcemusic | wifi | wifibringup | wifiset [on|off] | usbhal | dimapi | dimnav | naviserv [1] | service <type> | permservice <type> "
                            + "| simnavi | stopnavi | dimnavi <start|tick|stop> | dimproj <0|1> "
                            + "| navistart [naviType] | route [action] [lat] [lng] [name] "
                            + "| hold <turn> <dist> | unhold | binddim "
                            + "| turn <id> | dist <m> | next <name> | dimnavimode <0|1> "
                            + "| present <displayId> | mapsurface <0|1> "
                            + "| raw <hub|sb|navi|mycar> <desc> <code> <v|i|s|b> [i:1|s:x|b:1|l:5|B:navi]...");
                    break;
                case "dim":
                    dimMode(Integer.parseInt(p[1]));
                    break;
                case "dimproj":
                    DimProtocol.setProjectScreen("1".equals(p[1]));
                    break;
                case "dimnavimode":
                    DimProtocol.setNaviMode(Integer.parseInt(p[1]));
                    break;
                case "dimread":
                    DimProtocol.readHudState();
                    DimProtocol.readProjectScreenStatus();
                    break;
                case "dimnavi":
                    if ("start".equals(p[1])) {
                        startNavi();
                    } else if ("tick".equals(p[1])) {
                        tickNavi();
                    } else {
                        stopNavi();
                    }
                    break;
                case "simnavi":
                    // Feed a simulated route to the cluster/HUD over the DIM protocol.
                    DimProtocol.setProjectScreen(true);
                    DimProtocol.setNaviMode(1);
                    startNavi();
                    break;
                case "turn":
                    // Hold one turn icon without the ticker overwriting it.
                    naviRunning = false;
                    handler.removeCallbacks(naviTicker);
                    DimProtocol.notifyStatus(DimProtocol.STATUS_NAVI_START);
                    DimProtocol.notifyStatus(DimProtocol.STATUS_NAVI_SUCCESS);
                    DimProtocol.setProjectScreen(true);
                    DimProtocol.updateTurnId(Integer.parseInt(p[1]));
                    break;
                case "hold":
                    // Hammer one turn/distance faster than the map, to see whose data wins.
                    naviRunning = false;
                    handler.removeCallbacks(naviTicker);
                    // Reset then activate: convertNaviStatus(2) reports "active" only when no info
                    // has been sent yet, so END first and START after.
                    DimProtocol.notifyStatus(DimProtocol.STATUS_NAVI_END);
                    DimProtocol.notifyStatus(DimProtocol.STATUS_NAVI_START);
                    DimProtocol.setProjectScreen(true);
                    final int holdTurn = Integer.parseInt(p[1]);
                    final int holdDist = Integer.parseInt(p[2]);
                    naviHold = new Runnable() {
                        @Override
                        public void run() {
                            // The service only forwards to the cluster inside updateETAFun, so ETA
                            // must be sent too or nothing reaches the instrument.
                            DimProtocol.updateTurnId(holdTurn);
                            DimProtocol.updateDistanceToNextGuidancePoint(holdDist);
                            DimProtocol.updateNextGuidancePointName("PROBE");
                            DimProtocol.updateEta(600);
                            handler.postDelayed(this, 150);
                        }
                    };
                    handler.post(naviHold);
                    break;
                case "unhold":
                    handler.removeCallbacks(naviHold);
                    break;
                case "dist":
                    DimProtocol.updateDistanceToNextGuidancePoint(Integer.parseInt(p[1]));
                    break;
                case "next":
                    DimProtocol.updateNextGuidancePointName(p.length > 1 ? p[1] : "");
                    break;
                case "stopnavi":
                    stopNavi();
                    DimProtocol.setProjectScreen(false);
                    break;
                case "ar":
                    arGuide(Integer.parseInt(p[1]));
                    break;
                case "switchar":
                    final boolean on = "1".equals(p[1]);
                    withNavi(() -> NaviApi.switchARNavigation(navi(), on));
                    break;
                case "guide":
                    guideInfo();
                    break;
                case "subscribe":
                    subscribeEvents("1".equals(p[1]));
                    break;
                case "inspect":
                    withNavi(() -> NaviApi.inspect(navi()));
                    break;
                case "hudapi":
                    HudApi.readAll(this);
                    break;
                case "hudimpl":
                    startActivity(new Intent(this, HudImplProbeActivity.class));
                    break;
                case "ambli":
                    AmbientApi.readAll(this);
                    break;
                case "ambmusic":
                    AmbientApi.enableOemMusicShow(this);
                    break;
                case "ambrestore":
                    AmbientApi.restoreOemAmbient(this);
                    break;
                case "ambprobe":
                    AmbientApi.runControlProbe(this);
                    break;
                case "ambmarquee":
                    AmbientApi.startMarquee(this);
                    break;
                case "ambsteady":
                    AmbientApi.steadyColor(this);
                    break;
                case "ambwheel":
                    AmbientApi.startColorWheel(this);
                    break;
                case "ambspectrum":
                    AmbientApi.readSpectrum(this);
                    break;
                case "ambvisualizer":
                    AmbientApi.readLocalVisualizer(this);
                    break;
                case "ambforcemusic":
                    AmbientApi.forceMusicShow(this);
                    break;
                case "wifi":
                    WifiApi.readAll(this);
                    break;
                case "wifibringup":
                    WifiApi.bringUp(this);
                    break;
                case "dimapi":
                    DimApi.dump(this);
                    break;
                case "dimnav":
                    DimApi.pushTest(this);
                    break;
                case "usbhal":
                    UsbApi.dump(this);
                    break;
                case "wifiset":
                    // #2: ExternalWifiManager.setWifiEnabled on|off (off when the argument is missing)
                    WifiApi.setClientWifi(this, p.length > 1 && !"off".equals(p[1]));
                    break;
                case "cycle":
                    hudCycleSequence();
                    break;
                case "naviserv":
                    probeNaviService(p.length > 1 && "1".equals(p[1]));
                    break;
                case "navistart":
                    // Route B: ask the OneOS navi service to start navigation, so its
                    // RspMapStatus{GUIDE_START} makes com.geely.dimservice open the cluster navi area.
                    final int naviType = p.length > 1 ? Integer.parseInt(p[1]) : 0;
                    withNavi(() -> NaviApi.startNavi(navi(), naviObserver, naviType, getPackageName()));
                    break;
                case "route": {
                    // Plan/start a real route so the engine emits RspMapStatus{GUIDE_START}.
                    int action = p.length > 1 ? Integer.parseInt(p[1]) : NaviApi.ROUTE_ACTION_NAVI;
                    double lat = p.length > 2 ? Double.parseDouble(p[2]) : 39.908;
                    double lng = p.length > 3 ? Double.parseDouble(p[3]) : 116.397;
                    String dest = p.length > 4 ? p[4] : "PROBE目的地";
                    withNavi(() -> NaviApi.routePlan(navi(), naviObserver, action, lat, lng, dest,
                            getPackageName()));
                    break;
                }
                case "binddim":
                    bindDimService();
                    break;
                case "service": {
                    IBinder binder = hub.service(Integer.parseInt(p[1]));
                    ProbeLog.i("service(" + p[1] + ") interface=" + Ipc.interfaceName(binder));
                    break;
                }
                case "permservice": {
                    IBinder binder = hub.permissionService(Integer.parseInt(p[1]), getPackageName());
                    ProbeLog.i("permissionService(" + p[1] + ") interface=" + Ipc.interfaceName(binder));
                    break;
                }
                case "present": {
                    Display display = ((DisplayManager) getSystemService(Context.DISPLAY_SERVICE))
                            .getDisplay(Integer.parseInt(p[1]));
                    if (display != null) {
                        showPresentation(display);
                    } else {
                        ProbeLog.i("no display " + p[1]);
                    }
                    break;
                }
                case "mapsurface":
                    mapSurface(Integer.parseInt(p[1]), 1);
                    break;
                case "raw":
                    rawCommand(p);
                    break;
                case "clear":
                    logView.setText("");
                    break;
                default:
                    ProbeLog.i("unknown command: " + p[0] + " (try help)");
            }
        } catch (Throwable error) {
            ProbeLog.e("command '" + line + "' failed", error);
        }
    }

    /** {@code raw <hub|sb|navi|mycar> <descriptor> <code> <reader> [arg...]}. */
    private void rawCommand(String[] p) {
        if (p.length < 5) {
            ProbeLog.i("raw needs: <hub|sb|navi|mycar> <descriptor> <code> <v|i|s|b> [args]");
            return;
        }
        IBinder binder = binderFor(p[1]);
        String descriptor = p[2];
        int code = Integer.parseInt(p[3]);
        String reader = p[4];
        Ipc.Reader read = "i".equals(reader) ? Ipc.INT : "s".equals(reader) ? Ipc.STRING
                : "b".equals(reader) ? Ipc.BOOL : Ipc.VOID;
        Ipc.probe("raw " + p[1] + " " + descriptor + " #" + code, binder, descriptor, code,
                data -> {
                    for (int i = 5; i < p.length; i++) {
                        writeArg(data, p[i]);
                    }
                }, read);
    }

    private IBinder binderFor(String name) {
        switch (name) {
            case "hub":
                return hub.raw();
            case "sb":
                if (serviceBinder == null) {
                    serviceBinder = hub.service(OneOsHub.TYPE_NAVI);
                }
                return serviceBinder;
            case "navi":
                return navi();
            case "mycar":
                if (myCar == null) {
                    myCar = hub.service(OneOsHub.TYPE_SETTING_MANAGER);
                }
                return myCar;
            default:
                ProbeLog.i("unknown binder '" + name + "' (hub|sb|navi|mycar)");
                return null;
        }
    }

    /** Argument tokens: {@code i:123}, {@code l:123456}, {@code b:1}, {@code s:text}, {@code B:navi}. */
    private void writeArg(Parcel data, String token) {
        String value = token.length() > 2 ? token.substring(2) : "";
        switch (token.charAt(0)) {
            case 'i':
                data.writeInt(Integer.parseInt(value));
                break;
            case 'l':
                data.writeLong(Long.parseLong(value));
                break;
            case 'b':
                data.writeInt("1".equals(value) ? 1 : 0);
                break;
            case 's':
                data.writeString(value);
                break;
            case 'B':
                data.writeStrongBinder(binderFor(value));
                break;
            default:
                data.writeString(token);
        }
    }

    /**
     * Route A: bind {@code com.geely.dimservice.service.DimService} (permission
     * {@code geely.oneos.permission.SERVICE} is normal-level) and see what binder it hands back.
     */
    private final ServiceConnection dimConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ProbeLog.i("dimservice connected: " + name.flattenToShortString() + " binder=" + service
                    + " interface=" + Ipc.interfaceName(service));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            ProbeLog.i("dimservice disconnected: " + name.flattenToShortString());
        }
    };

    private void bindDimService() {
        Intent intent = new Intent("android.intent.action.GEELY_DIM_MENU_SERVICE");
        intent.addCategory("com.geely.intent.category.DIM");
        intent.setPackage("com.geely.dimservice");
        boolean bound = false;
        try {
            bound = bindService(intent, dimConnection, Context.BIND_AUTO_CREATE);
        } catch (Throwable error) {
            ProbeLog.e("bindService(DimService)", error);
        }
        ProbeLog.i("bindService(DimService) -> " + bound
                + " (onBind returns null in the shipped app, so this stays unbound)");
    }

    /** Resolve the INaviServer (launching the map), then walk the DIM/AR display modes. */    private void hudTestSequence() {
        ProbeLog.i("=== HUD test: resolve INaviServer (launch map), then push DIM modes ===");
        probeNaviService(true);
        handler.postDelayed(() -> withNavi(() -> NaviApi.inspect(navi())), 2000);
        handler.postDelayed(() -> withNavi(() -> NaviApi.switchDimNaviMode(
                navi(), naviObserver, NaviApi.DIM_FULL, getPackageName())), 4000);
        handler.postDelayed(() -> withNavi(() -> NaviApi.switchDimNaviMode(
                navi(), naviObserver, NaviApi.DIM_AR, getPackageName())), 7000);
        handler.postDelayed(() -> withNavi(() ->
                NaviApi.switchARNavigation(navi(), true)), 10000);
        handler.postDelayed(() -> withNavi(() ->
                NaviApi.getGuideInfo(navi(), naviObserver, getPackageName())), 12000);
    }

    private void dimMode(int mode) {
        withNavi(() -> NaviApi.switchDimNaviMode(navi(), naviObserver, mode, getPackageName()));
    }

    private void arGuide(int action) {
        withNavi(() -> NaviApi.switchMapArGuide(navi(), naviObserver, action, getPackageName()));
    }

    private void guideInfo() {
        // The reply (and any pushes) arrive on a registered callback, so subscribe first.
        withNavi(() -> {
            NaviApi.registerNaviObserver(navi(), naviObserver);
            NaviApi.addNaviEventNotify(navi(), naviEventNotify);
            NaviApi.getGuideInfo(navi(), naviObserver, getPackageName());
            NaviApi.getGuideInfoDirect(navi());
        });
    }

    private void subscribeEvents(boolean on) {
        if (on) {
            withNavi(() -> {
                NaviApi.registerNaviObserver(navi(), naviObserver);
                NaviApi.addNaviEventNotify(navi(), naviEventNotify);
                long flags = NaviApi.EVENT_GUIDE | NaviApi.EVENT_LANES
                        | NaviApi.EVENT_SPEED_LIMIT | NaviApi.EVENT_ROAD_CONDITION;
                NaviApi.setNaviEventConfig(navi(), naviObserver, flags, getPackageName());
            });
        } else {
            withNavi(() -> NaviApi.removeNaviEventNotify(navi(), naviEventNotify));
        }
    }

    // --- map widget Surface ------------------------------------------------

    private Surface liveSurface() {
        SurfaceHolder holder = mapSurfaceView.getHolder();
        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) {
            ProbeLog.i("map SurfaceView has no valid Surface yet");
            return null;
        }
        return surface;
    }

    private void mapSurface(int sourceType, int mode) {
        Surface surface = liveSurface();
        if (surface == null) {
            return;
        }
        if (!mapWidget.isConnected()) {
            ProbeLog.i("map widget service not connected; retrying bind");
            mapWidget.bind(connected -> ProbeLog.i("map widget service ready=" + connected));
            return;
        }
        mapWidget.addSurface(surface, sourceType, mode);
    }

    private void removeMapSurface(int sourceType) {
        Surface surface = liveSurface();
        if (surface == null) {
            return;
        }
        mapWidget.removedSurface(surface, sourceType, 1);
    }

    private void oneOsSurface(int sourceType) {
        Surface surface = liveSurface();
        if (surface == null) {
            return;
        }
        if (naviMapServer == null) {
            naviMapServer = hub.service(OneOsHub.TYPE_NAVI);
        }
        OneOsHub.addSurfaceWithSourceType(naviMapServer, surface, sourceType, 1,
                mapSurfaceView.getHeight(), mapSurfaceView.getWidth());
    }

    private void removeOneOsSurface(int sourceType) {
        Surface surface = liveSurface();
        if (surface == null) {
            return;
        }
        OneOsHub.removedSurfaceWithSourceType(naviMapServer, surface, sourceType, 1);
    }

    // --- simulated guidance ------------------------------------------------

    private void startNavi() {
        naviRunning = true;
        naviTicks = 0;
        stepIndex = 0;
        distanceToNext = 800;
        distanceToDestination = 12500;
        DimProtocol.notifyStatus(DimProtocol.STATUS_NAVI_START);
        DimProtocol.notifyStatus(DimProtocol.STATUS_NAVI_SUCCESS);
        tickNavi();
        handler.postDelayed(naviTicker, 1000);
    }

    private final Runnable naviTicker = new Runnable() {
        @Override
        public void run() {
            if (!naviRunning) {
                return;
            }
            tickNavi();
            handler.postDelayed(this, 1000);
        }
    };

    private void tickNavi() {
        naviTicks++;
        distanceToNext -= 50;
        if (distanceToNext <= 0) {
            distanceToNext = 800;
            stepIndex = (stepIndex + 1) % STEP_NAMES.length;
            DimProtocol.updateNextGuidancePointName(STEP_NAMES[stepIndex]);
        }
        distanceToDestination = Math.max(0, distanceToDestination - 50);
        DimProtocol.updateTurnId(TURN_IDS[naviTicks % TURN_IDS.length]);
        DimProtocol.updateDistanceToNextGuidancePoint(distanceToNext);
        DimProtocol.updateDistanceToDestination(distanceToDestination);
        DimProtocol.updateEta(600 + (naviTicks % 60) * 10);
        DimProtocol.updateLaneInfo(DimProtocol.demoLanes(naviTicks % 3));
        android.os.Bundle extras = new android.os.Bundle();
        extras.putInt(DimProtocol.KEY_ROAD_TYPE, 0);
        // These two are read back as booleans by the DIM service.
        extras.putBoolean(DimProtocol.KEY_FRONT_DESK_STATUS, true);
        extras.putBoolean(DimProtocol.KEY_SCREEN_READY_STATUS, true);
        DimProtocol.updateExtensionInfo(extras);
        ProbeLog.i("guidance tick " + naviTicks + ": next=" + STEP_NAMES[stepIndex]
                + " " + distanceToNext + "m, dest " + distanceToDestination + "m");
    }

    private void stopNavi() {
        naviRunning = false;
        handler.removeCallbacks(naviTicker);
        DimProtocol.stopNavigation();
    }
}
