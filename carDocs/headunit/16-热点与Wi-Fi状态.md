# 车机热点 / Wi-Fi 状态（原厂 API 与「显示未开启」的原因）

> 现象：车机上热点**明明开着**，第三方 App（DiPlay）却显示「未开启」。
> 本文给出原厂 Wi-Fi/热点的正式 API、车机实测状态、以及为什么标准 Android API 看不到。

## 一、车机侧实测（2026-10-14）

```sh
adb shell dumpsys tethering
#   p2p0 - LocalHotspotState - lastError = 0
#   OBSERVED iface=p2p0 state=3 error=0
#   LinkProperties ... InterfaceName: p2p0 ... 192.168.79.222/24
#   [p2p0] ... CMD_TETHER_REQUESTED

adb shell dumpsys wifi
#   mState: 0
#   mWifiInfo ... Supplicant state: DISCONNECTED
#   mWifiLogProto.numSetupSoftApInterfaceFailureDueTo* = 0   （标准 SoftAP 无活动）

adb shell getprop
#   persist.setlocalwifi.switch = false
#   persist.settcamwifi.switch  = false
```

⇒ 车机的「热点」实际是一个 **P2P/本地热点**（`p2p0`，`LocalHotspotState` → `LOCAL_ONLY`，
客户端网段 `192.168.79.x`），**不是**标准 Android SoftAP（`softap0` / `hostapd`）。
`WifiManager.isWifiApEnabled()` / `getWifiApState()` 这类标准 SoftAP 状态**不会反映它**。

## 二、原厂 Wi-Fi / 热点 API（逆向）

### 2.1 框架层：`ecarx.fw.api.wifi.TcamWifi`（`autolink.adapterservice.jar`，boot classpath）

| 作用 | 方法 |
| --- | --- |
| 热点状态 | `isWifiApEnabled()`、`getWifiApState()`、`getWifiApConfiguration()`、`getSoftApConDevInfo()` |
| 热点控制 | `startSoftAp(android.net.wifi.WifiConfiguration)`、`stopSoftAp()`、`setWifiApConfiguration(...)` |
| 热点回调 | `registerWifiApCallback(TcamWifiApCallback, Handler)`、`registerSoftApCallback(TcamSoftApCallback, Handler)` |
| STA（连 Wi-Fi） | `isWifiEnabled()`、`setWifiEnabled(boolean)`、`getWifiState()`、`getScanResults()`、`connect(...)`、`disconnect()`、`addNetwork`/`enableNetwork`/`removeNetwork` |
| 其它 | `getTcamInfo()`、`getTestConnStatusInfo()`、`queryTcamSyncInformation()`、`testCurrentConnectionStatus()` |

实现类 `com.autolink.adaptersrv.wifi.TcamWifiImpl` 有 **public 构造** `<init>(Context)` / `<init>(Context, Handler)`，
以及 `getService()` —— **第三方可能可以直接 `new TcamWifiImpl(ctx)` 后读 `isWifiApEnabled()`**（待上车验证）。

### 2.2 适配层：`com.ecarx.xui.adaptapi.wifiap.ExternalWifiManager`（`ecarx.adaptapi.jar`，boot classpath）

```java
ExternalWifiManager create(Context[, int]);            // 返回 null 表示不可用（见 HUDInteraction 的情形）
boolean isWifiEnabled();  boolean setWifiEnabled(boolean);  int getWifiState();
List<ScanResult> getScanResults();  List<WifiConfiguration> getConfiguredNetworks();
int addNetwork(WifiConfiguration);  boolean enableNetwork(int, boolean);  boolean disconnect();
void connect(WifiConfiguration, ActionListener);
void registerWifiStateListener(...); registerScanResultListener(...); registerConnectionStateListener(...);
FunctionStatus isSupported();
```

热点（SoftAP）相关的适配类：`com.ecarx.xui.adaptapi.wifiap.WifiAp`、`WifiApHostImp`、
`WifiApImp`、`IWifiApClient`、`WifiApConfigChangeListener`。

> 对照：[`02-框架与VDBus.md`](02-框架与VDBus.md) §三、[`09-隐藏能力与未开启项.md`](09-隐藏能力与未开启项.md) §七。

## 三、为什么 DiPlay 显示「未开启」

DiPlay 的热点判断走的是**标准 Android 路径**（SoftAP 状态 / Wi-Fi Direct `WifiP2pManager` /
`LocalOnlyHotspotManager` / `dumpsys` 解析兜底），而本车热点是 **vendor 的 `p2p0` 本地热点**：

- 标准 SoftAP 面无活动（`numSetupSoftApInterfaceFailureDueTo* = 0`，无 `hostapd`）；
- DiPlay 的 ADB 兜底**显式忽略** `LocalOnly` 与 station 状态
  （见 `CarHotspotAdbFallbackTest.dumpParserIgnoresHistoricalStationAndLocalOnlyState`）；
- 于是 vendor 本地热点不被识别 → 界面显示未开启，但车机上明明是开的。

## 四、待验证 / 建议

1. 用 `samples/carprobe` 试：`new com.autolink.adaptersrv.wifi.TcamWifiImpl(ctx)` →
   `isWifiApEnabled()` / `getWifiApState()`，看第三方能否直接读到真实热点状态。
2. 若可用，DiPlay 的热点检测应加一路 **`TcamWifi`（或 `ExternalWifiManager`）后端**，
   按 `Build.DEVICE` 门控，替代/补充标准的 SoftAP / dumpsys 判断。
3. `adaptapi.ExternalWifiManager.create()` 也可能像 `HUDInteraction.create()` 一样是 stub，
   需实机确认（返回 null = 不可用）。

## 五、一句话

**车机热点是 vendor 的 `p2p0` 本地热点，标准 `WifiManager` SoftAP 状态看不到它**；
原厂用 `ecarx.fw.api.wifi.TcamWifi`（`isWifiApEnabled` / `getWifiApState` / `startSoftAp` / `stopSoftAp`）
和 adaptapi `ExternalWifiManager` 管 Wi-Fi/热点。DiPlay 要正确显示热点状态，得接这两条 OEM 通道。
