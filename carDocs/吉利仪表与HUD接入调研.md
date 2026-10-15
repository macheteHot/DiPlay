# 吉利车机仪表 / HUD 接入调研（探针实测）

> 设备：`geely_fx11` / `CS11_A4`（Android 11，API 30），2026-10-11 实测。
> 探针 App：`samples/carprobe`（`com.diplay.carprobe`，普通第三方 uid `10059`，**不是** system uid）。
> 地图侧的 HUD 机制见 [`吉利地图HUD显示机制.md`](吉利地图HUD显示机制.md)。

## 一、结论速查

| 通道 | 第三方 App 可用？ | 实测 |
| --- | --- | --- |
| DIM 导航协议 `DimNaviService`（转向/距离/ETA/车道 → 仪表+HUD） | ✅ **可用** | 调用全部被 `com.autolink.diminteraction` 接收处理（logcat 有 `DimNaviService: updateXxxFun(...)`）；无权限异常 |
| `DimNaviService.getDimProjectScreenStatus()` / `notifyDimProjectScreenSwitch(bool)` | ✅ 可用 | 返回真实状态；开关调用被接受 |
| 仪表屏（display 2，1920x720）投画面：`Presentation` | ✅ **可用** | 普通 App 的 `Presentation` 成为 display 2 的焦点窗口并真实渲染（截图见下） |
| OneOS hub `com.geely.service.oneosapi` → `IServiceManager.getService(1)` = 导航服务 | ✅ 可用 | 正常返回 Binder |
| OneOS hub → `getService(41)`（setting manager：`isHudActive` / `setHudBrightness|Position|AngelAdjust`） | ❌ 返回 null | HUD 状态与调节经 OneOS 拿不到 |
| `ServiceManager.getService("dimprotocol")`（`getHUDMode` / `getNaviMode`） | ❌ 未注册 | 该车机没有 `dimprotocol` 服务（库会兜底去启 `ecarx.dim.service`，也不存在） |
| 地图 HUD Surface（`AutoSimilarWidgetService.addSurface(sourceType=1)`） | ⚠️ 调用被接受但**不会渲染** | 地图侧 `isOpenHud=false` → HUD 引擎从不初始化；10 s 后静默放弃 |

一句话：**把 CarPlay 的导航信息写进仪表/HUD 是可行的（DIM 协议），把画面投到仪表也可行（Presentation）；但"用地图那套 200x185 HUD 小地图"这条路在本车是死的。**

## 二、DIM 导航协议（推荐主路径）

binder 名与接口（`android.os.ServiceManager.getService(...)` 直连，无需绑定）：

- `DimNaviService` → `ecarx.dimprotocol.custom.service.IDimNaviService`
- `dimprotocol` → `ecarx.dimprotocol.IDIMProtocolService`（**本机未注册**）

`IDimNaviService` 事务码（取自 `com.autolink.diminteraction` 的生成 Stub）：

| # | 方法 | 参数 |
| --- | --- | --- |
| 1 | `notifyNavigationStatus` | int：`1=NAVI_SUCCESS`、`2=NAVI_START`、`3=NAVI_END`、`4=CRUISE_START`、`5=CRUISE_END` |
| 2 | `updateETA` | int（秒） |
| 3 | `updateDistanceToDestination` | int（米） |
| 4 | `updateDistanceToNextGuidancePoint` | int（米） |
| 5 | `updateNextGuidancePointName` | String |
| 6 | `updateNavigationTurnId` | int（转向图标 id） |
| 7 | `updateLaneInfo` | `List<AdaptLaneInfo>`：`{int laneNumber, int laneBackIconId, Parcelable laneBackIcon}` |
| 8 | `updateExtensionInfo` | `Bundle` |
| 9 | `notifyDimProjectScreenSwitch` | boolean |
| 10 | `getDimProjectScreenStatus` | → boolean |
| 11 / 12 | `updateNaviLightInfo` / `updateCruiseLightInfo` | `AdaptLightInfo`（未实测） |
| 13 / 14 | `register` / `unregisterNavigationInteractionCallback` | 回调：`onDoInteractionAction(int, AdaptAddress)`、`onSearchAddress(String)` |

`Bundle` 键（`updateExtensionInfo`）：

- `KEY_NAVI_INFO_ROAD_TYPE` → **int**
- `KEY_NAVI_INFO_NAVI_FRONT_DESK_STATUS` → **Boolean**（实测传 int 会被 DIM 侧 `getBoolean` 判类型不符并回退 false）
- `KEY_NAVI_INFO_NAVI_SCREEN_READY_STATUS` → **Boolean**
- `KEY_NAVI_INFO_ESTIMATED_ARRIVAL_TIME` → 本次未用

实测日志（DIM 侧接收）：

```text
I DimNaviService: updateNavigationTurnIdFun turnId(8)
I DimNaviService: updateDistanceToNextGuidancePointFun distanceToNextGuidancePoint(450)
I DimNaviService: updateDistanceToDestinationFun distanceToDestination(12150)
I DimNaviService: updateETAFun eta(670)
I DimNaviService: updateLaneInfoFun adaptLaneInfoList.length:1
I DimNaviService: updateExtensionInfoFun extensionInfo(extensionInfo{KEY_NAVI_INFO_ROAD_TYPE=0,
    KEY_NAVI_INFO_NAVI_FRONT_DESK_STATUS=true,KEY_NAVI_INFO_NAVI_SCREEN_READY_STATUS=true})
```

注意 `AdaptLaneInfo` 的类名不进 Parcel：自己按同样字段顺序实现一个 `Parcelable` 即可（探针里 `DimProtocol.Lane` 就是这么做的，DIM 侧正常 `length:1`）。

## 三、仪表屏投画面

- `displayId=0`：2560x1440（中控可用区），`displayId=2`：**1920x720、带 `FLAG_PRESENTATION`** = 仪表。
- 普通 App 直接 `new Presentation(context, display2).show()` 就能上仪表；实测窗口名 `Sys2037:com.diplay.carprobe/...ProbeActivity`（`2037` = `TYPE_PRESENTATION`），并成为该屏 `mCurrentFocus`、`mObscuringWindow`。
- `screencap -d 2` 能抓到画面（不是黑屏），说明是正常的 WindowManager 窗口，不是 GL 独占。
- 车机自己的地图用的是同一招（`ExternalScreenPresentation`，引擎 4，1920x720），由协议 30800 开关。

## 四、死路与坑

1. **地图 HUD/Widget Surface 通道**：`addSurface(sourceType=1, mode=1)` 被接受（无异常），但地图永远不渲染：
   - 配置 `sdk_adapter_config.json` 里 `isOpenHud=false`；
   - `ComponentManager.initWidgetService` → `ModuleServiceUtils.getHudService()` 直接返回 null → `HudExtScreenManager` / `WidgetExtScreenManager` 从不初始化；
   - `HudManager.addSurface` 等 10 s（`WidgetWaitTimeoutRunnable`）后超时，回调状态 `11` 又因为 `state <= 10` 的判断**不会**回传给宿主，所以外部看是"静默失败"；
   - 判据：call 后 13 s 再查 `isMapRunning(1)` 仍是 `false`（真要渲染时 `startRender()` 会把它置 true）。
   - 结论：除非车型把 `isOpenHud` 打开（vendor 资产，改不了），这条路不通。另外它只给 200×185 的地图，箭头/距离仍要宿主自己画。
2. **OneOS setting manager（`getService(41)`）为 null**：`isHudActive()`、`isHudBrightnessAndPositionAdjustOn()`、`isHudAngelAdjustOn()`、`setHud{Brightness,Position,Angel}Adjust()` 这些**拿不到**。HUD 的亮度/角度/位置调节目前只能靠车机自己的设置页（车机侧信号本身是通的：`SETTING_FUNC_HUD_*` 全部 active，`pavfhudactvsts=On`）。
3. **`dimprotocol` 未注册**：`getHUDMode()` / `getNaviMode()` / `setNaviMode()` 读不到。
4. **`requestSupportExpand` / `abandonExpand`（`IMyCarService` #12 / #14）** 也要经 setting manager，同样拿不到 binder；`Presentation` + `notifyDimProjectScreenSwitch` 已经够用。
5. 探针调用 DIM 之前**务必复位**：`notifyNavigationStatus(NAVI_END=3)`、`CRUISE_END=5`、`notifyDimProjectScreenSwitch(false)`。

## 五、探针 App

`samples/carprobe`（不是正式功能，debug 包即可，用 `carDocs/install-apk.sh` 装）：

```sh
./gradlew :carprobe:assembleDebug
./carDocs/install-apk.sh samples/carprobe/build/outputs/apk/debug/carprobe-debug.apk
adb shell am start -n com.diplay.carprobe/.ProbeActivity
adb logcat -s CarProbe
```

界面按钮：列显示 / 投屏… / 关投屏 / HUD 只读全检 / OneOS 连接 / DIM 读取 / DIM 投屏ON·OFF / 投影状态 / 导航模式 / 导航开始·刷新·结束 / 地图Surface HUD·副屏·关 / OneOS Surface HUD·副屏·关 / HUD 调节… / 集群投屏请求。
启动后 2.5 s 自动跑一次只读全检；也可用 intent 额外参数脚本化：

```sh
# 只读全检
adb shell am start -n com.diplay.carprobe/.ProbeActivity
# 在仪表上投红色探针画面 + 开投影 + 推模拟导航
adb shell am start -n com.diplay.carprobe/.ProbeActivity \
  --ez read false --ez project_on true --ei present 2 --ez navi true
# 测地图 Surface（sourceType 1=HUD，0=副屏/仪表）
adb shell am start -n com.diplay.carprobe/.ProbeActivity --ez read false --ei map_source 1
```

## 六、已打补丁：让现有「仪表地图」认这台车的仪表

改动（全部按设备门控，不影响 BYD 行为，便于上游 PR）：

| 文件 | 改动 |
| --- | --- |
| `common/.../GeelyClusterDisplay.kt`（新增） | 门控对象：`supported()` 要求 `Build.DEVICE == "msmnile_gvmq"`；`matches()` 要求 displayId ≠ 0、带 `FLAG_PRESENTATION`、尺寸 1920x720（屏名是本地化文案 `HDMI 屏幕`，不参与匹配）；`streamConfig()` 输出整屏 1920x720（100%）流并接安全区 |
| `common/.../ClusterMapPresentation.kt` | `findDisplay()` 在 DiLink 5.1 → DiLink 4 之后加吉利分支：设备匹配时取第一个符合 `GeelyClusterDisplay.matches` 的 presentation 屏（本机 = display 2） |
| `common/.../CarPlayHostActivity.kt` | `clusterDisplayConfig()` 加吉利分支：整屏 1920x720 流 + 安全区/marker 设置，写日志并更新 `MapMirrors.streamAspect` |
| `common/.../DiPlayActivity.kt` | 新增 `fullPanelCluster = diLink4 \|\| 吉利仪表`，把"安全区编辑器 / 隐藏尺寸预设 / 隐藏 BYD 大小窗暂停"四处门控改用它（原来只认 DiLink 4）；`wheelMapZoomAvailable()` 在吉利上返回 false（方向盘缩放读的是 BYD 媒体/自定义按键，本车没有这一路） |
| `common/src/test/.../GeelyClusterDisplayTest.kt`（新增） | 设备门控、屏匹配、流尺寸+安全区、`findDisplay()` 正/反例、`clusterDisplayConfig()` 端到端 1920x720 |

行为：设置里「仪表地图」开关打开后，本车会把 iPhone 的仪表流按整屏 1920x720 投到 display 2，
沿用现成的安全区/marker 设置；其他车机（`Build.DEVICE` 不匹配）行为完全不变。
`ClusterMapPresentation.diagnosticReport()` 的 `selectedCluster` 现在会显示 `2:HDMI 屏幕`（此前是 `none`）。

验证：

```sh
./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.GeelyClusterDisplayTest"
```

提 PR 时的范围建议：只带
`GeelyClusterDisplay.kt` + 三处调用点改动 + `GeelyClusterDisplayTest.kt` + 本目录文档；
`samples/carprobe` 是诊断用探针（不是产品功能），按维护者意见决定是否一起提；
`CHANGELOG.md` / `docs/COMPATIBILITY.md` 的条目等有 issue/PR 号后再补。
另外 PR 描述里要写清"只在这台 `msmnile_gvmq` 上验证过、默认开关仍是关的、
HUD 导航未验证"。

注意（上车确认）：吉利仪表是**整块属于我们**的 1920x720 面板，Presentation 会盖住车机自己的读数，
所以**安全区设置很重要**（`cluster_safe_area_1920x720` 已经存在）；上车后要对着仪表把安全区调好，
再确认「小地图/全屏导航」两种内容都正常。

## 七、HUD 导航仍未解决 + 下一步

仪表确认可用（红色探针界面），但 HUD 没显示我们推的 DIM 导航数据。下一轮上车值得试：

1. `INaviServer.switchARNavigation(true)`（事务码 27，OneOS 导航服务我们能拿到 binder）→ 看 HUD 是否进 AR 导航；
2. `INaviServer.getGuideInfo()`（9）/ `addNaviEventNotify`（66）→ 看车机自己上报的导航数据结构，反推 HUD 吃哪一路；
3. DIM 组合：`notifyDimProjectScreenSwitch(true)` + `notifyNavigationStatus(2)` + `updateExtensionInfo` 带 ETA / `ROAD_TYPE`（ETA 键还没试）。

若最终确认 HUD 只吃原厂导航（BYD 的 W-HUD 就是这种结论），就在文档和设置文案里明确"仪表地图可用、HUD 导航不可用"，
不要把 HUD 写进宣称范围。

## 八、OneOS 导航服务（INaviServer）实测（2026-10-12）

探针新增 `NaviApi`，把原厂地图走的那条「DIM/HUD 显示模式」通道补上了，链路和结果如下。

### 8.1 正确的取服务链路（关键）

`IServiceManager.getService(1)` **不是** `INaviServer`，而是 `IServiceBinder`（接口名实测）：
`com.geely.lib.oneosapi.navi.IServiceBinder`。要用它换真正的导航服务：

| 步骤 | 接口 / 事务码 | 参数 | 实测 |
| --- | --- | --- | --- |
| 取 type 1 | `IServiceManager.getService(1)`（2） | int | 返回 `IServiceBinder` binder（不常驻：地图没起来时为 null） |
| 默认地图 | `IServiceBinder.getDefaultMap()`（3） | — | `6`（= `com.geely.map`） |
| 换导航服务 | `IServiceBinder.getNaviServer(vendor, launchMap, cb)`（1） | int, boolean, binder | 回调 `IServerCallback.initSuccess(INaviServer)`，接口名 `com.geely.lib.oneosapi.navi.INaviServer` |
| 回调 | `IServerCallback.initSuccess`（2）/`initFailed`（3）、`getKey`（1） | — | 实测走 `initSuccess` |

> 之前直接拿 `INaviServer` 的 `registerNaviObserver`(1) 会报 `SecurityException: Binder invocation to an incorrect interface`，
> 就是因为拿错了接口。`INaviServer` 的 `getNaviServer` 才是入口。

### 8.2 下发的模型（原厂 `NaviAPI` 封装）

`invokeNaviAPI`/`invokeNaviAPIAsync` 收一个 `TransferModel`（= 类名 + `NaviBaseModel` 子类）。
`NaviBaseModel` parcel 布局：`protocolID, callbackId, protocolVersion, packageName, extraData(Bundle), mapVendor`，子类再追加字段。

| protocolID | 模型 | 作用 | 字段 |
| --- | --- | --- | --- |
| 4100 | `DimDisplayMode` | 仪表/HUD 导航显示模式（原厂 `switchDimNaviMode`） | int：0 关 / 1 简 / 2 全 / **3 AR** |
| 3007 | `MapOperaSwitchAR` | AR 导航开关（`switchMapARGuide`） | int：0 开 / 1 关 |
| 4000 | `NaviEventConfig` | 高频事件订阅位（1 引导 / 2 车道 / 4 限速 / 8 路况） | long |
| 3407 | `NaviBaseModel` | 取引导信息（`getGuideInfo`，结果走回调） | — |
| 3425 | `SwitchMapPresentation` | 把地图投到其它屏/窗口 | int×3 |

另有直连方法：`INaviServer.switchARNavigation(boolean)`（27）、`getGuideInfo()`（9）、`addNaviEventNotify(cb)`（66）。
回调：`INaviObserver.onNaviAPICallback(TransferModel)`（2）、`INaviEventNotify.onNaviNotify(TransferModel)`（2）。

### 8.3 实测结果：已打通

`getNaviServer(vendor=6, launchMap, cb)` 拿到 `INaviServer` 后，`invokeNaviAPIAsync` 逐个模型都返回 **`0`（`RESULT_API_SUCCESS`）**：

```text
switchDimNaviMode(FULL)  invokeNaviAPIAsync(proto=4100) -> 0
switchDimNaviMode(AR)    invokeNaviAPIAsync(proto=4100) -> 0
switchARNavigation(true) -> 无异常（void）
```

**关键坑（踩了一轮）**：手工组 `TransferModel` 时，`Parcel.writeParcelable()` 写的是
**类名字符串**（不是存在性 int）。所以正确字节流是

```text
writeInt(1)              // 代理层：TransferModel 非空
writeString(类名)         // TransferModel.writeToParcel
writeString(类名)         // writeParcelable 内部的类名
字段...                   // NaviBaseModel + 子类字段
```

之前多写了一个 `writeInt(1)`，服务端 `readParcelable` 失败 → `naviBaseModel is null` →
`invokeNaviAPIAsync` 返回 `-1`（`RESULT_API_NO_SERVICE`，**这个码会误导成“服务未就绪”**）。
服务端日志给出判据（`NaviTag`）：`invokeNaviAPIAsync: naviBaseModel is null`。

另外两点：

- 走**同步** `invokeNaviAPI`（64）会让服务端在回包序列化时空指针崩溃（`TransferModel.writeToParcel` 里
  `naviBaseModel.getClass()`），所以统一用**异步** `invokeNaviAPIAsync`（65，回 int，模型走 `INaviObserver`）。
- `NaviBaseModel.mapVendor` 取 `getDefaultMap()` 的值（本机 `6`）；`-1` 时同样可复现 `-1` 返回。
- 探针 `navi 探测` 里的 `INaviServer.getPackageName()` = `com.geely.map`、`mapIsLaunched()` = `false`，
  说明这两个只读字段不代表引擎是否可用；**真正判据是 `invokeNaviAPIAsync` 的返回码**。

### 8.4 结论（2026-10-12 定案）

**HUD / 仪表导航内容：第三方做不了；只有「把画面投到仪表」可行。**

判定过程（探针 `NaviApi` + `CarProbe` 命令通道，命令也可用 `adb shell printf '...' > carprobe.cmd` 驱动）：

| 通道 | 结果 |
| --- | --- |
| 仪表**画面**：`Presentation` → display 2 | ✅ 成功，第三方窗口成为仪表焦点并渲染 |
| OneOS 导航**显示模式**：`DimDisplayMode(4100)` / `MapOperaSwitchAR(3007)` | ✅ 调用返回 `0`，但**只切模式，不含导航内容** |
| DIM 协议**导航数据**：`DimNaviService.updateTurnId/Distance/ETA/Lane` | ⚠️ 服务端 `DimNaviService` **全部收到**，但**仪表/HUD 不渲染** |
| HUD/仪表导航内容 | ❌ 只由**原厂地图的导航引擎**（OneOS `INaviServer` ← `com.geely.map`）驱动 |

关键判据：**临时禁用原厂地图**（`pm disable-user --user 0 com.geely.map`）后，服务端只剩我们推的数据、无任何竞争源，
再叠加 OneOS `DimDisplayMode=FULL/AR` 与 `notifyDimProjectScreenSwitch(true)`，仪表/HUD **依然不显示**我们的转向/距离；
反过来，原厂地图一导航，仪表就显示（且我们的数据会被原厂覆盖）。

原因（与第四节一致）：
- DIM 导航协议真正驱动渲染的那一路是 `ecarx.dimprotocol` / `ecarx.dim.service`，**本机未注册**；
  我们调到的 `DimNaviService`（`com.autolink.diminteraction`）只接收、不渲染。
- 原厂地图走的是 OneOS `INaviServer` → 地图导航引擎，第三方无法注入导航内容。

**因此产品范围**：本机只做「**CarPlay 画面投到仪表**」（`GeelyClusterDisplay`，`Presentation`），
**不要宣称 HUD/仪表导航**。

（禁用地图后记得恢复：`pm enable com.geely.map`；本机自签安装、`com.diplay.carprobe` 均为诊断用。）

## 九、仪表导航区“开关”逆向定案（2026-10-13）

第八节只解决了“写内容”，本节解决“**谁来开关导航区**”。

### 9.1 两条通道分工

| 通道 | 归属 | 作用 | 第三方 |
| --- | --- | --- | --- |
| `DimNaviService`（`com.autolink.diminteraction`） | `ecarx.dimprotocol.custom.service.IDimNaviService` | **写内容**：转向/距离/ETA/车道 → 经 SOME/IP 发仪表 | ✅ 可写（但要 `updateEta` 才会触发发送，见下） |
| `com.geely.dimservice`（系统 app） | 监听 OneOS navi | **开关导航区** + 把 OneOS 引导转仪表 | ❌ 不可直接调 |

### 9.2 写内容的必要条件（差点漏掉）

`DimNaviService` 里 `updateNavigationTurnIdFun` / `updateDistanceToNextGuidancePointFun` / `updateNextGuidancePointNameFun`
**只存字段、不发送**；真正 `SomeIPUtils.Navi.sendIntersectionInfo/sendTotalNaviInfo` 在 **`updateETAFun`** 里。
→ **必须持续推 ETA**，内容才会到仪表。另外 `convertNaviStatus(2)` 依赖 `mIsNaviInfoSend`，激活前先发 `NAVI_END(3)` 再 `NAVI_START(2)`。

### 9.3 开关导航区的唯一信号：`RspMapStatus`

`com.geely.dimservice` 的 `INaviEventListenerImpl` 把 OneOS navi 的 `RspMapStatus`（protocolID 1001）映射为：

| mapStatus | 含义 | dimservice 动作 |
| --- | --- | --- |
| 16 | `GUIDE_START` | `notifyNavigationStatus(2)` → **开导航区** |
| 33 | `SIMULATE_GUIDE_START` | 同上 |
| 17 | `GUIDE_STOP` | `notifyNavigationStatus(3)` → 关 |
| 14 | `ROUTE_SUCCESS` | status 1 |
| 13 | `ROUTE_START` | — |
| 1050 / 1051 | 地图前台 / 后台 | — |

### 9.4 第三方怎么让引擎发 16（实测可行）

用 OneOS navi 服务起一条**真实路线**（`NaviAPI.routePlanOrNavi` → `invokeNaviAPIAsync`，protocolID **3401**，`NaviRoutePlan`）：

```text
route(action=1, dest=30.2497,120.1441 西湖风景区)
  → 返回 0
  → dimservice 收到 status13(ROUTE_START) → status14(ROUTE_SUCCESS) → status16(GUIDE_START)
  → getNavigationStatus = 2    ← 导航区打开
```

随后 `DimNaviService` 的 `PROBE` / `9999` / 左转**显示在仪表导航区**，并与原厂抢（谁后写谁赢）。
手工组 `NaviRoutePlan` 的字节：`NaviBaseModel` + `action,strategy,destPoiInfo,startPoiInfo,viaRoad,viaPoiInfos`；
`PoiInfo`=`name,uid,type,address,distance,latLng`；`LatLng`=`lat(double),lng(double),coordType`。

### 9.5 结论与限制

- **能开**：起一条真实路线即触发 `GUIDE_START`，导航区打开，之后内容由 `DimNaviService` 写。
- **副作用**：规划路线会把**原厂地图拉到前台**（`MAP_APP_ON_RESUME=1050`）。
- **依赖**：**禁用/关闭原厂地图后导航区立即关闭**（实测“停止了”）——即“已开”的状态需要地图引擎存活。
- **路线 A（直接绑 `DimService`）不可行**：`DimService.onBind` 返回 `null`（权限 `geely.oneos.permission.SERVICE` 虽是 normal，但拿不到 binder）。
- `StartNaviModel`(3426) 单独调用**不会**发 `GUIDE_START`；必须先有路线。

**产品结论**：本机要做“仪表导航”就必须**开着原厂地图并起一条路线**，体验不可接受；
因此仍按第八节收敛——**只做「CarPlay 画面投仪表」（`Presentation` / `GeelyClusterDisplay`），不宣称 HUD/仪表导航**。
以上 DIM/OneOS 能力保留在 `samples/carprobe`，供将来原厂放开或有系统权限时再启用。

## 十、CarPlay 画面投仪表：打通（2026-10-13）

第八节留下了“能投屏、但看不到”的问题，本节补上**最后一块拼图**。

### 10.1 症状与根因

- DiPlay 在 display 2 建了 `Presentation`（`Sys2037`，`isVisible=true`、`mCurrentFocus` 就是它、Surface 1920×720、`SCREEN_TYPE_ALT active=true`），
  但**仪表仍显示车机原厂表盘**。
- 用探针投一块**不透明**画面到 display 2，**同样盖不住**原厂表盘 → 说明仪表把 Android 的 display 2 层**压在原厂层之下**，
  除非车机被要求**投影到仪表**。

### 10.2 开关：DIM 服务的“投影”调用

```
ServiceManager.getService("DimNaviService")
  → ecarx.dimprotocol.custom.service.IDimNaviService
  → notifyDimProjectScreenSwitch(true/false)      // 事务码 9，int
```
调用后服务端日志：`DimNaviService: notifyDimProjectScreenSwitchFun turnId(true)`。
发 `true` 的瞬间，Android display 2（我们的 Presentation）就盖到仪表上了。

原厂地图走的也是这一路：`notifyDimProjectScreenSwitchFun → SomeIPUtils.Navi.sendScreenAreaAndResultInfo(naviDisplayArea=1)`。

### 10.3 实现（已提交）

| 文件 | 改动 |
| --- | --- |
| `common/.../GeelyClusterProjection.kt`（新增） | 反射取 `DimNaviService` binder，调 `notifyDimProjectScreenSwitch`（事务码 9）；仅 `GeelyClusterDisplay.supported()` 时使用 |
| `common/.../ClusterMapPresentation.kt` | `onStart()` → 开投影，`onStop()` → 关投影（吉利设备） |

提交：`dbeb4672`（在 `4e8210ad` 的 `GeelyClusterDisplay` 之上）。

### 10.4 结果

打开「仪表地图」后，仪表直接显示 **CarPlay 的仪表流**（`SCREEN_TYPE_ALT`）：
```
dim projection: notifyDimProjectScreenSwitch(true) ok
cluster presentation shown display=2 name=HDMI 屏幕
cluster surface 1920x720 / cluster stream active=true
```
**本机「CarPlay 画面投仪表」可用**。安全区/marker 设置照旧用于避开车机自身读数。

## 十一、投影被原厂地图抢走后不自动回来（2026-10-15 定位并修复）

### 11.1 症状

方向盘上的地图键把**原厂地图**（`com.geely.map`）唤醒后，仪表上正在显示的 CarPlay 画面被顶掉；
把原厂地图关掉，CarPlay 也**不会回来**（仪表保持没有投影的状态）。

### 11.2 根因

`notifyDimProjectScreenSwitch` 这个投影开关是**车机全局的**，DiPlay 和原厂地图**共用**它
（原厂地图走 `SomeIPUtils.Navi.sendScreenAreaAndResultInfo(naviDisplayArea=1)`，同一个开关）。
而 DiPlay 只在 `Presentation` 启停时各断言一次：

| 位置 | 行为 |
| --- | --- |
| `ClusterMapPresentation.onStart()` | `GeelyClusterProjection.set(true)` |
| `ClusterMapPresentation.onStop()` | `GeelyClusterProjection.set(false)` |

实测时间线（一次真实抢占）：

```text
22:44:24  lynkPlay-Cluster: dim projection: notifyDimProjectScreenSwitch(true) ok   # 我们开投影
22:45:43  DimNaviService: updateNavigationTurnIdFun / updateETAFun ...
          DimNaviService: notifyNavigationStatusFun s_naviStatus:5                  # 原厂导航会话收尾
22:45:45  DimNaviService: notifyDimProjectScreenSwitchFun turnId(false)             # 开关被第三方关掉
22:46:29  lynkPlay-Cluster: cluster stream active=false                             # 我们只看到码流停，Presentation 没 stop
```

于是 `onStart` 不会再触发，开关永远停在 off。且这条路**没有可依附的事件**：

1. DIM 投影是车机/QNX 侧开关，**不会**产生 Android display 回调（`onDisplayChanged` 收不到）。
2. 那个方向盘地图键**不进 DiPlay**（`GeelyWheelKeys` 只订阅媒体键与麦克风键），没有键事件可挂钩。

### 11.3 修复

| 文件 | 改动 |
| --- | --- |
| `common/.../GeelyClusterProjection.kt` | 新增 `status()`：事务码 10 `getDimProjectScreenStatus`，返回 `Boolean?`（读不到 = null） |
| `common/.../ClusterProjectionWatchdog.kt`（新增） | 判定策略：**连续两次**读到 off 才要求重夺；读到 on（原厂地图正当占用）或 null（读不到）一律不动 |
| `common/.../ClusterMapPresentation.kt` | `onStart` 起一个 `PROJECTION_TICK_MILLIS`（2 s）的 tick，判定被抢走就重新 `set(true)`；`onStop` 停 tick |

行为：原厂地图占用期间开关是 on，DiPlay 不干预；原厂地图退出关掉开关后，DiPlay 最迟约 4 s 内把投影夺回。
两个 off 读数是为了避开原厂地图切换过程中短暂经过 off 的瞬间，避免两边互相抢。

`getDimProjectScreenStatus` 也可直接用 adb 只读复核（`service call` 不带接口 token，本服务接受）：

```sh
adb shell 'service call DimNaviService 10'      # Result: Parcel(00000000 00000001 ...) → 0=异常码 1=投影ON
```

测试：`common/src/test/.../ClusterProjectionWatchdogTest.kt`。






