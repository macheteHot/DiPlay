# 吉利 FX11 红绿灯 HUD：原厂链路、DiPlay 现状与“网络层”获取路径

> 设备：`geely_fx11` / `CS11_A4`（DesaySV，Android 11，SA8155）
> 日期：2026-10-19
> 相关：`carDocs/吉利地图HUD显示机制.md`、`carDocs/吉利仪表与HUD接入调研.md`、`shihabal3amri/DiPlay#165`
> 反编译产物：`carDocs/extracted/geely-map`、`carDocs/extracted/diminteraction`、`carDocs/extracted/oneos-dimservice`

## 0. 结论

1. **原厂地图的红绿灯不是画出来的，是走 DIM 导航通道发到 HUD 的**：高德 `TrafficLightCountdown` → 地图 App 组装 JSON → OneOS 导航协议 `80189`/`80194` → `com.geely.dimservice` → XUI `INaviInteraction.updateNaviLightInfo(ILightInfo)` → `DimNaviService` 的 `LightInfo` → SOME/IP → 仪表/HUD。
2. **DiPlay 已经有一条能写仪表/HUD 导航的通道**（`GeelyDimNavi`，XUI `DimInteraction`），目前只写了转向、距离、ETA、车道，**还没写红绿灯**。XUI 接口里有 `updateNaviLightInfo` / `updateCruiseLightInfo`，补上的代码量很小，但**还没上车验证 HUD 是否真的画**。
3. **手机高德的读秒拿不到 CarPlay 元数据**：iAP2 导航只有 `0x5201`/`0x5202`/`0x5204`，`CPManeuver` 的 accNav 参数表里没有红绿灯。读秒只存在于 CarPlay 的**视频画面**里。
4. **不建议把 OCR 当第一方案**。优先测两条“网络/协议层”的路：
   - **A. 车机本地 OneOS 导航事件**：`INaviServer.addNaviEventNotify` / `registerNaviObserver` 能不能收到原厂地图的 `3808`/`3809` 灯光事件。如果能，原厂地图在后台巡航时就能提供当前路口的灯态，不需要手机、不需要 OCR。
   - **B. 网络请求**：已经从 `GeelyMap.apk` 的 native 库定位到 `wss://autoapi.amap.com/ws/shield/trafficlights/realtime`（请求类型 `400060`）和 JSON key；剩下的是鉴权（`diu`/`cifa`/`token`）和请求体，见 4.7。MITM 只用于确认；非越狱 CA 可能被证书 pinning 挡住。
5. OCR 只作为兜底：区域固定、字号稳定时才考虑 1 Hz 采样 + 数字模板/ML Kit。

---

## 1. 原厂地图的红绿灯链路（REA 结果）

### 1.1 地图侧：Amap 引擎 → JSON

文件：`com/autosdk/drive/navi/utils/NavigationUtil.java`（约 924 行起）

```text
Amap SDK INaviObserver.onUpdateTrafficLightCountdown(ArrayList<TrafficLightCountdown>)
  → NavigationUtil.notifyLightsInfoChanged(list)
  → isShowTrafficCountDownEnable() 为真才继续
  → getCurTrafficLightCountdown()  选当前 path 上的灯
  → getTrafficLightResult()        组装 JSON
  → ExternalInteractionManager.buildDataAndDispatch(
        PROTOCOL_OTHER_TRAFFIC_LIGHT_INFO, json, json.toString())
```

JSON 字段：

| 字段 | 含义 |
| --- | --- |
| `redLightCountDownSeconds` | 当前灯态剩余秒数（`etime - now`） |
| `trafficLightStatus` | `2=红`、`5=绿`、`8=黄`、`-1=无灯` |
| `dir` | 高德 `lightInfo.dir`；`7→3`、`8→4` |
| `waitRound` | 没有当前灯态时用 `lightInfo.waitNum`，表示还要等几轮 |

协议号（`com/autosdk/common/protocol/ProtocolID.java`）：

```text
PROTOCOL_OTHER_TRAFFIC_LIGHT_INFO        = 80189   // 导航
PROTOCOL_OTHER_CRUISE_TRAFFIC_LIGHT_INFO = 80194   // 巡航
```

### 1.2 OneOS → DIM

`com.geely.dimservice` 的 `INaviEventListenerImpl`：

```text
协议 3808 RspLightInfoResponse   → updateNaviLightInfo(...)
协议 3809 RspCruiseLightsInfo    → updateCruiseLightInfo(...)
                ↓
XUI INaviInteraction.ILightInfo
  getLightStatus()        // 2/5/8，7=无灯
  getWaitCount()          // 等待轮数
  getRedLightCountdown()  // 秒
  getDir()                // 方向
```

`RspLightInfoResponse`（Parcel 顺序）：

```text
lightStatus, waitCount, redLightCountdown, dir
```

### 1.3 DIM → SOME/IP → HUD

`com.autolink.diminteraction` 的 `DimNaviService.updateNaviLightInfoFun`：

```text
AdaptLightInfo
  → LightInfo
      trafficLightType = 0（导航）/ 1（巡航）
      trafficLightIcon = ICON_MAP[dir][statusIndex]
      trafficLightSecond = redLightCountdown          // waitCount > 0 时改用：
      trafficLightRounds = waitCount
  → SomeIPUtils.Navi.sendLightInfo(lightInfo)
```

`ICON_MAP`（`DimNaviService.java:183`）：

```text
行 dir=1: {1,2,3,4}      实际使用 6/7/8      // 左（另一套）
行 dir=2: {5,6,7,8}      实际使用 10/11/12   // 右
行 dir=3: {9,10,11,12}   实际使用 30/31/32   // 左
行 dir=4: {29,30,31,32}  实际使用 34/35/36   // 直行
```

灯色索引：`lightStatus 2/3 → 红`、`4/5 → 绿`、`6/8 → 黄`，`1/7 → 0（不显示）`。
方向：`dir=1/3 左`、`4 直行`、`2 右`。

巡航（事务 12）：

```text
leftLightIconColor / leftLightIconSecond        // dir=1/3
straightLightIconColor / straightLightIconSecond // dir=4
rightLightIconColor / rightLightIconSecond       // dir=2
```

`LightInfo` 的完整字段（`com/autolink/diminteraction/bean/navi/LightInfo.java`）：

```text
trafficLightType, trafficLightIcon, trafficLightSecond, trafficLightRounds,
leftLightIconColor, leftLightIconSecond,
straightLightIconColor, straightLightIconSecond,
rightLightIconColor, rightLightIconSecond
```

**也就是说 HUD 要的只有四件事：方向、颜色、秒数（或等待轮数）。**

---

## 2. DiPlay 现有 HUD/仪表导航代码

### 2.1 数据来源（CarPlay iAP2）

| iAP2 消息 | 用途 | 代码 |
| --- | --- | --- |
| `0x5201 RouteGuidanceUpdate` | 剩余时间/距离/到达时间 | `BydHudRouteState` |
| `0x5202 RouteGuidanceManeuverUpdate` | 转向类型/下一段路/行驶侧 | `BydHudRouteState` |
| `0x5204 LaneGuidanceInfoUpdate` | 车道 | `Iap2LaneGuidance` |

聚合到 `CarPlayGlance` → `ClusterTurnGuidance`。

相关提交：

- `fe16a456` `lynk: CarPlay turns in the cluster's own navigation area`（新增 `GeelyDimNavi`）
- `ca0f54e1` `lynk: map CarPlay maneuvers to the car's own turn ids`
- `1091689d` `lynk: draw CarPlay's lane guidance in the cluster`
- `f78ba4f5` `lynk: give the cluster the arrival time, not just the ETA`
- `a4222e5e` `lynk: stop the cluster navigation area when the route ends`

### 2.2 写入通道（XUI DimInteraction，实测可用）

`shared/src/main/java/com/shilapi/xcertplay/hud/GeelyDimNavi.kt`：

```text
DimInteraction.create(context)
  → getNaviInteraction()
  → notifyNavigationStatus(2)
  → notifyTurnByTurnStarted()
  → updateNavigationInfo(Proxy<INavigationInfo>)
  → updateTurnByTurnArrow(GeelyTurnId)
  → updateDistanceToNextGuidancePoint / updateDistanceToDestination / updateETA
  → updateExtensionInfo / updateLaneInfo
```

`INaviInteraction` 里本来就有（在 `OneOS-DIMService.apk` 的 dex 中确认）：

```text
updateNaviLightInfo(INaviInteraction$ILightInfo)
updateCruiseLightInfo(java.util.List)
```

`ILightInfo` 就是上面四个 getter；用和 `InfoHandler` 一样的 `Proxy` 即可。

### 2.3 缺口

- 没有 `updateNaviLightInfo` 调用；
- 没有红绿灯数据源；
- `GeelyDimNavi` 目前画在仪表导航区，**HUD 是否显示同一份灯光信息需要在车上确认**。

---

## 3. 手机高德：CarPlay 协议能给什么

仓库声明的 CarPlay 导航端点：

```text
0x5201 RouteGuidanceUpdate
0x5202 RouteGuidanceManeuverUpdate
0x5204 LaneGuidanceInfoUpdate
```

`0x5202` 字段：转向类型、下一段路、行驶侧、路口类型、路口角度、出口角度、关联车道、高速出口名、符号等。

`CPManeuver` 的 13 个 accNav 参数（社区对 iOS 26 的逆向）：

```text
componentID, index, instructionVariants, maneuverType,
roadFollowingManeuverVariants, initialDistance, initialDistanceDisplay,
trafficSide, junctionType, junctionElementAngles, junctionExitAngle,
linkedLaneGuidanceIndex, highwayExitLabel
```

**没有红绿灯字段。** 手机端的高德读秒是画在 CarPlay 视频里的，车机拿到的是像素。

---

## 4. “网络层”路径评估

### 4.1 CarPlay/iAP2 链路（已排除）

- 车机是 Wi-Fi Direct 的 GO，iPhone 是 client；
- iAP2 导航元数据由 DiPlay 自己解析，字段表里没有红绿灯；
- AirPlay 视频是 H.264 像素，不含结构化读秒。

**结论：从 CarPlay 协议层拿不到。**

### 4.2 手机高德云端（MITM）

可行，但仅限研究/越狱机：

- iPhone 同时跑 CarPlay 时，业务网络通常走蜂窝，不走 CarPlay 链路，车机抓不到；
- 越狱 iPhone 上装 mitmproxy/Frida + SSL Kill Switch，把高德的 HTTPS/QUIC 流量解开，定位红绿灯接口的 URL、参数、签名、返回体；
- 生产端要复刻同一接口，需要 App 鉴权、设备指纹、签名/加密、风控，且受 ToS 限制。

**结论：可以用来确认“高德到底从哪个接口拿读秒”，不适合作为 DiPlay 的通用数据源。**

### 4.3 车机本地 OneOS 导航事件（推荐先测）

原厂地图的灯光事件是发给 `com.geely.naviService` 的。OneOS 对外还有：

```text
INaviServer.addNaviEventNotify(INaviEventNotify)      // 事务 66
INaviServer.removeNaviEventNotify(...)
INaviServer.registerNaviObserver(INaviObserver)       // 事务 1
INaviEventNotify.onNaviNotify(TransferModel)
INaviObserver.onNaviAPICallback(TransferModel)
```

`TransferModel` 里就是 `NaviBaseModel`（协议号 3808/3809 等）。

要验证的问题：

1. 第三方 `addNaviEventNotify` 能不能收到原厂地图的 `RspLightInfoResponse`（3808）/`RspCruiseLightsInfo`（3809）？
2. 原厂地图**不导航、只巡航**时，会不会持续发 3809？
3. 原厂地图在后台、CarPlay 在前台时，事件还在不在？
4. 如果只在导航时发 3808，它的灯是不是原厂地图自己的路线（和 CarPlay 路线不一致）？

如果 **3809 巡航灯**可用，这条就是最优解：

```text
原厂地图后台巡航（Amap 云端数据）
  → OneOS 3809 灯光事件
  → DiPlay 订阅
  → GeelyDimNavi.updateNaviLightInfo / updateCruiseLightInfo
  → HUD
```

不需要手机、不需要 OCR，数据源和高德车机版一致。

风险：

- 原厂地图可能被系统回收/不启动；
- 巡航灯只覆盖当前 link，路口变化时需要及时更新；
- 原厂地图如果同时抢占仪表/HUD，需要确认不会和 CarPlay 冲突。

### 4.4 原厂地图的云端流量

车机上的原厂地图本身就从高德云端拿 `TrafficLightCountdown`。理论上可以在车机侧用 VpnService/代理抓它的流量，但：

- Amap AutoSDK 可能使用 TLS pinning、私有协议、加密 protobuf；
- 抓到的是原厂地图的路线/位置，不是 CarPlay 的；
- 逆向后仍要复刻签名/鉴权。

**结论：成本高、收益低，不作为首选。**

### 4.5 车机热点抓包

如果 iPhone 走车机热点上网，车机作为网关能看到 DNS/SNI，但：

- 单 Wi-Fi 芯片车机在 CarPlay Wi-Fi Direct 下，手机业务流量一般走蜂窝；
- 载荷是 TLS/QUIC，没有 MITM 看不到读秒。

**结论：不可行。**

### 4.6 V2X / SPaT

如果车辆/车机接入了城市信号灯的 SPaT 数据，可以直接拿灯态；目前没有证据表明这台车有这条通道，原厂地图用的也是高德云端数据。

**结论：不作为方案。**

### 4.7 已定位到的真实请求（静态逆向，2026-10-19）

从 `GeelyMap.apk` 的 native 库（`libGNet.so` / `libGbl.so`）里已经找到高德红绿灯的请求入口：

| 项 | 值 |
| --- | --- |
| 请求类型 | `AOS_REQTYPE_M5_WS_SHIELD_TRAFFICLIGHTS_REALTIME = 400060`（`EGAOSREQUESTTYPE.java:133`） |
| native 枚举 | `GD_REQ_M5_WS_SHIELD_TRAFFICLIGHTS_REALTIME`（`libGNet.so`） |
| 路径 | `ws/shield/trafficlights/realtime`（`libGNet.so`，`BLReqInfoManager` 路径表） |
| Host | `autoapi.amap.com`（测试环境还有 `pre-autoapi.amap.com`、`itest-autoapi.testing.amap.com`） |
| 协议 | WebSocket（`GDNetwork::CWebSocketPushClient`），JSON（`Content-Type: application/json;charset=UTF-8`） |
| 鉴权/参数 | `diu`、`adiu`、`cifa`、`token`；native 日志有 `HMI must set diu`、`CheckAndSetToken`、`SetAdiu` |
| 请求/响应 JSON key（`libGbl.so`） | `linkIds`、`pathIds`、`trafficLights`、`lightInfo`、`lightStates`、`stime`、`etime`、`lightType`、`waitNum`、`crossManeuverID`、`segmentIndex`、`linkID`、`pathID` |
| native 日志字段 | `trafficLightStatus`、`lightCountDown`、`waitRoundCount`、`crossManeuverID` |

根据 key 和 Java 侧模型，推断请求/响应形状如下（**待 MITM/日志确认**）：

```json
// request（订阅当前路线/路口的灯）
{"linkIds":[...],"pathIds":[...]}

// response
{"trafficLights":[{
  "linkID":123,
  "pathID":456,
  "segmentIndex":0,
  "crossManeuverID":...,
  "lightInfo":{
    "lightStates":[{"stime":...,"etime":...,"lightType":2}],
    "waitNum":0
  }
}]}
```

`libGNet.so` 里还有第二个和车道级红绿灯有关的通道：

```text
ws/avs/data/traffic_light
ws/avs/data/lane_info
```

它属于车道级/AR 模块（`libAutoDice.so` 的 `lane::TrafficLight`），不是普通导航 HUD 用的这一条；先不混在一起。

**下一步**：

- [ ] 车上抓原厂地图 logcat，确认实际 URL/参数/响应：
  ```sh
  adb logcat -v time | grep -Ei "HttpRequest|trafficlights|notifyLightsInfoChanged|redLightCountDown|lightInfo|lightStates"
  ```
- [ ] 非越狱 iPhone + CA + 代理（有线 CarPlay + Wi-Fi）抓 `autoapi.amap.com/ws/shield/trafficlights/realtime` 的 WS 帧；
- [ ] 如果普通 CA 被 pinning 挡住，用越狱 XR（SSL Kill Switch）确认；
- [ ] 对比手机高德和车机高德是不是同一端点/同一套参数；
- [ ] 如果鉴权（`diu`/`cifa`/`token`）无法复刻，就走 4.3 的 OneOS 本地事件：让原厂地图去发请求，DiPlay 只订阅 3808/3809。

#### 直接请求测试（2026-10-19，从 Mac）

不经过车机和手机，直接请求这条 WS：

| 请求 | 结果 |
| --- | --- |
| GET，无 key | `INVALID_USER_KEY` / `Key不正确` |
| header `key` | `INVALID_USER_SCODE` / `MD5参数验证失败` |
| header `key` + `timestamp` + `signature`（APK 里的候选值） | `INVALID_USER_KEY` / `Key数据不存在` |
| 把 `key`/`timestamp`/`signature` 放 query | 被忽略；必须是 **header** |

结论：**协议层没有挡住，挡的是 key + securityCode**。

- 服务端读的是 header：`key`、`timestamp`、`signature`；
- `libGNet.so` 的 `BLNetworkRestEncrytionManager::CreateSignature(key, timestamp, securityCode)` 做 MD5；
- key/securityCode 在 `assets/blRes/GRestConfig.ini`（加密）和运行时日志
  `SetKeyAndSecurityCode m_strKey=%s` / `m_strSecurityCode=%s` 里；
- APK 里能直接 `strings` 到的两个 32 位 hex（`649b...`、`52BFEE...`）不是有效 key；
- 拿到 key/securityCode 后，还要有 `linkIds`/`pathIds`（当前路线或路口），这是第二个待解问题。

车上抓 key/securityCode 不需要越狱：

```sh
adb logcat -c
adb logcat -v time | grep -Ei "SetKeyAndSecurityCode|m_strRestKey|m_strRestSecurityCode|GRestConfig|GNet"
# 然后打开原厂地图 / 起一条路线
```

如果日志级别不打印，再考虑解 `GRestConfig.ini` 或手机 MITM。

### 4.8 手机高德实测（2026-10-19，越狱 XR）

设备：iPhone XR，iOS 16.6.1，高德 `17.01.1`；LocSimHTTP 模拟定位 + 导航；Frida hook `Security SSLRead/SSLWrite` 和 `libboringssl`。

抓到的请求：

```text
GET  render-prod-tile.amap.com/ws/render/bmd/tile?tileType=0/1/2/3/5/6&tileId=...&adiu=...
GET  mapstudio-data.amap.com/map_lane/publish/model_zip/...
POST fp.amap.com/ws/shield/location/fp/reports
```

**没有** `ws/shield/trafficlights/realtime`，也没有 `linkIds`/`pathIds`/`lightInfo` 等字段。

瓦片验证：

- 用抓到的 `adiu` 从 Mac 重放 `tileType=5/6`，HTTP 200，返回同样的二进制；
- `tileType=5` 是 zstd，解出约 25KB bitpacked 地图数据；
- `tileType=6` 是 raw BMD，能看到“东直门”“首都机场高速”等路名，不是灯态 JSON；
- 手机高德的读秒在地图引擎/BMD 瓦片里，不是一条独立请求。

**定案**：

- 手机高德这条路不能“直接请求”拿明文读秒；只能 hook 越狱机的高德引擎/UI，或解 BMD 瓦片。
- 原厂地图的 AutoSDK `trafficlights/realtime` / OneOS `3808/3809` 是结构化路径，适合 DiPlay。
- 下一步：车机 logcat 抓 `key`/`securityCode`，或验证 OneOS `3808/3809` 事件订阅。

---

## 5. 上车验证计划

### A. XUI 灯光注入（确认 HUD 吃不吃）

在 `samples/carprobe` 的 `DimApi` 里加一个“灯光”按钮/命令：

1. 起一个假导航（复用现有 `push`）；
2. 用 `Proxy` 实现 `INaviInteraction$ILightInfo`；
3. 依次推：
   - `lightStatus=2（红）/ dir=4（直行）/ redLightCountdown=15`
   - `lightStatus=5（绿）/ dir=1（左）/ redLightCountdown=8`
   - `lightStatus=7（无灯）` 清除
4. 观察仪表和 HUD 是否出现红绿灯、秒数/轮数、图标方向。

同时抓原厂地图导航时的：

```sh
adb logcat -s DimNaviService
# 期望：
# updateNaviLightInfoFun lightStatus:...;dir:...;waitCount:...;redLightCountdown:...;trafficLightIcon:...
```

### B. OneOS 事件订阅（确认网络/协议层能否拿数据）

在 carprobe 里：

1. `IServiceManager.getService(1)` → `IServiceBinder.getNaviServer(6, false, cb)`
2. `INaviServer.addNaviEventNotify(cb)` / `registerNaviObserver(cb)`
3. `onNaviNotify` / `onNaviAPICallback` 里打印：
   - `TransferModel` 的类名、`protocolID`
   - `RspLightInfoResponse` 的四个字段
4. 测试场景：
   - 原厂地图前台巡航（不导航）
   - 原厂地图导航
   - 原厂地图退到后台，CarPlay 前台

如果 3808/3809 能收到，就把 `GeelyDimNavi` 的灯光数据源接到这里。

### C. 原厂地图日志对照

```sh
adb logcat -s DimNaviService
adb logcat | grep -E "NavigationUtil|notifyLightsInfoChanged|trafficLight"
```

记录原厂地图实际下发的 `lightStatus/dir/waitCount/redLightCountdown`，作为字段和图标表的对照。

### D. OCR（兜底，不优先）

只有在 A/B 都不通时才考虑：

- `PixelCopy` 截 CarPlay 画面的一小块；
- 1 Hz 采样；
- ML Kit 数字识别或 0-9 模板匹配；
- 单调递减 + 突变复位校验；
- 只上传统计，不上传画面。

需要先拿到一张高德 CarPlay 红灯读秒的截图，确认区域/字号/主题。

---

## 6. 决策与风险

| 方案 | 数据源 | 通用性 | 风险 |
| --- | --- | --- | --- |
| A. OneOS 3809 巡航灯 | 原厂地图后台 | 本车可用即可 | 地图需启动/存活；可能被系统回收 |
| B. 手机 MITM | 手机高德云端 | 仅越狱机 | 鉴权/签名/ToS；生产不可行 |
| C. 视频 OCR | CarPlay 画面 | 任意手机 | UI 改版/误读；需要模板维护 |
| D. 原厂地图云端流量 | 车机地图 | 本车 | 私有协议/加密；数据是原厂路线 |
| E. CarPlay 元数据 | iAP2 | — | 协议里没有红绿灯 |

**建议顺序：A → B（研究）→ C（兜底）。**

---

## 附录：关键文件与字段

### A. 反编译文件

- `carDocs/extracted/geely-map/out/full/sources/com/autosdk/drive/navi/utils/NavigationUtil.java`
- `carDocs/extracted/geely-map/out/full/sources/com/autosdk/common/protocol/ProtocolID.java`
- `carDocs/extracted/oneos-dimservice/out/com/geely/dimservice/impl/INaviEventListenerImpl.java`
- `carDocs/extracted/diminteraction/out/ecarx/dimprotocol/custom/bean/AdaptLightInfo.java`
- `carDocs/extracted/diminteraction/out/com/autolink/diminteraction/service/DimNaviService.java`
- `carDocs/extracted/diminteraction/out/com/autolink/diminteraction/bean/navi/LightInfo.java`

### B. DiPlay 文件

- `shared/src/main/java/com/shilapi/xcertplay/hud/GeelyDimNavi.kt`
- `shared/src/main/java/com/shilapi/xcertplay/hud/GeelyTurnId.kt`
- `shared/src/main/java/com/shilapi/xcertplay/hud/BydHudRouteState.kt`
- `shared/src/main/java/com/shilapi/xcertplay/iap2/message/Iap2LaneGuidance.kt`
- `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`

### C. 字段速查

```text
AdaptLightInfo / RspLightInfoResponse
  lightStatus       2红 5绿 8黄 7/1无灯
  waitCount         等待轮数
  redLightCountdown 秒
  dir               1/3左 2右 4直行

LightInfo（SOME/IP）
  trafficLightType   0导航 1巡航
  trafficLightIcon   方向+颜色图标 id（6/7/8、10/11/12、30/31/32、34/35/36）
  trafficLightSecond 秒
  trafficLightRounds 轮数
  left/straight/rightLightIconColor + Second（巡航）
```
