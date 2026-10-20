# 吉利原厂导航车道 ID（laneIconId）全表

本文是从车机原厂包里**静态还原**出来的车道图标识号表，和
[`吉利导航转向ID-turnId表.md`](吉利导航转向ID-turnId表.md) 配套，供 CarPlay 导航信息写入**仪表盘 / HUD 车道区**时使用。

分析对象：

| 包 | 作用 |
| --- | --- |
| `com.geely.map`（`GeelyMap.apk`） | 原厂高德地图：产生 30424 车道 JSON（`trafficLaneIcon` / `trafficLaneNo`） |
| `com.geely.naviService`（`OneOS-NaviService.apk`） | 把 `trafficLaneIcon` 经 `LaneIconMapping` 转成 `laneIconId` |
| `com.geely.dimservice`（`OneOS-DIMService.apk`） | `RspLanesInfo` → `INaviInteraction.ILaneInfo` |
| `/system/framework/ecarx.adaptapi.jar` | `NaviInteraction` → `AdaptLaneInfo` |
| `com.autolink.diminteraction`（`DIMInteraction.apk`） | `updateLaneInfo` → someIP `RoadInfo` |

## 一句话结论

仪表/HUD 的车道区有两个 ID，**不是同一个东西**：

| 字段 | 来源 | 含义 |
| --- | --- | --- |
| `LaneId` / `laneNumber` | 30424 `trafficLaneNo` | 车道序号，1 开始，从左到右 |
| `LaneIconId` / `laneBackIconId` | 30424 `trafficLaneIcon` → `LaneIconMapping.autoToMapSDK` | 车道箭头图标 ID |

车道图标 ID 的实际可达集合是：

```text
0~62, 71~83, 95      （96 由映射表保留，但本版地图不产生）
```

原厂映射：

```text
laneIconId = LaneIconMapping.autoToMapSDK(trafficLaneIcon)
           = trafficLaneIcon                      // <=62 或 71~83
           = 95                                   // trafficLaneIcon == 85（潮汐车道）
           = 96                                   // trafficLaneIcon == 86（本版未产生）
           = -1                                   // 其他（该车道会被丢弃）
```

## 链路与证据

```text
GeelyMap NavigationUtil.getLaneInfo()
  └─ 30424 JSON: trafficLaneInfos[] { trafficLaneNo, trafficLaneIcon, trafficLaneType, ... }
OneOS-NaviService SendLanesInfo30424Impl.getLanes()
  └─ laneIconId = LaneIconMapping.autoToMapSDK(trafficLaneIcon)
ILanesInfoConvert.convertToLaneInfoList()
  └─ laneInfo.laneIconId = laneIconId; laneIndex = trafficLaneNo; 丢弃 laneIconId == -1
OneOS-DIMService INaviEventListenerImpl.onNaviEvent(protocolID 3418)
  └─ ILaneInfo.laneNumber = laneIndex; laneBackIconId = laneIconId; laneBackIcon = null
ecarx.adaptapi NaviInteraction.iLaneInfoToAdaptLaneInfo()
  └─ AdaptLaneInfo{laneNumber, laneBackIconId, laneBackIcon}
DimNaviService.updateLaneInfoFun()
  └─ LaneInfo{laneInfo = 列表序号, laneId = laneNumber, laneIconId = laneBackIconId}
SomeIPUtils.Navi.sendLaneInfo()
  └─ {"RoadInfo":[{"LaneInfo":..., "LaneId":..., "LaneIconId":...}]}
```

| 步骤 | 位置 | 观察 | Evidence |
| --- | --- | --- | --- |
| ① 地图生产车道 JSON | `com.autosdk.bussiness.widget.navi.utils.NavigationUtil.getLaneInfo` | `trafficLaneNo`、`trafficLaneIcon`、`trafficLaneType`；`TRAFFIC_LANE_ICON_MAP` 决定 `trafficLaneIcon` | `ev_f5bff9638efbbff71b7029cfa30d9a518f336d667540b84104f2f1ff599d02c1` |
| ② 车道图标换算 | `com.geely.map.mapsdk.utils.LaneIconMapping.autoToMapSDK` | `<=62`、`71~83` 原样；`85→95`、`86→96`；其他 `-1` | `ev_0b8e4a53803a5aa8a9a58bc7d90affb63470d8ce0845aaa7b31f3046d137acaf` |
| ③ 解析 30424 | `SendLanesInfo30424Impl.getLanes` | `laneIconId = autoToMapSDK(trafficLaneIcon)`；`laneIndex = trafficLaneNo` | `ev_0b2d48b30cab7820114877e194abcde90d0dd5759ca35f79540a076f21bf3989` |
| ④ OneOS 车道模型 | `ILanesInfoConvert.convertToLaneInfoList` | `laneIconId == -1` 的车道被丢弃 | `ev_ff52b6c3401840ff70fe5b06543857efd9c95505e8f832a5868c8b0aa26771f7` |
| ⑤ 框架转换 | `NaviInteraction.iLaneInfoToAdaptLaneInfo` | `laneNumber`、`laneBackIconId`、`laneBackIcon` 原样 | `ev_41c9705cbacb0a7690027a0633fe568529b20b3a5d4361d3e0c42d4305bd23ca` |
| ⑥ OneOS-DIM 侧 | `Info.getLaneBackIconId()` / `getLaneBackIcon()` | `laneBackIconId = laneInfo.getLaneIconId()`；`laneBackIcon = null` | `ev_86df5275a3e4a55cc5d6224fdf7363d842c5c677e5c6962c37d360d9325504cd` |
| ⑦ 车道事件入口 | `INaviEventListenerImpl.onNaviEvent`（protocolID `3418`） | `RspLanesInfo` → `ILaneInfo[]` → `updateLaneInfo` | `ev_2b453e48985c41ae2e53fa5daf0acf7fd3c6a72548909e89f6bdbebd00559f4c` |
| ⑧ 仪表侧落库 | `DimNaviService.updateLaneInfoFun` | `laneInfo/laneId/laneIconId` → someIP `RoadInfo` | 源码 `carDocs/extracted/diminteraction/out/.../DimNaviService.java` |

## 车道动作枚举（`LaneAction`）

`trafficLaneIcon` 由「本车道动作 + 前向动作」组合决定。动作值来自高德
`com.autonavi.gbl.guide.model.LaneAction`：

| 值 | 动作 | 含义 |
| ---: | --- | --- |
| 0 | `LaneActionAhead` | 直行 |
| 1 | `LaneActionLeft` | 左转 |
| 2 | `LaneActionAheadLeft` | 左前 |
| 3 | `LaneActionRight` | 右转 |
| 4 | `LaneActionAheadRight` | 右前 |
| 5 | `LaneActionLUTurn` | 左掉头 |
| 6 | `LaneActionLeftRight` | 左右转 |
| 7 | `LaneActionAheadLeftRight` | 直/左/右 |
| 8 | `LaneActionRUTurn` | 右掉头 |
| 9 | `LaneActionAheadLUTurn` | 直行+左掉头 |
| 10 | `LaneActionAheadRUTurn` | 直行+右掉头 |
| 11 | `LaneActionLeftLUTurn` | 左转+左掉头 |
| 12 | `LaneActionRightRUTurn` | 右转+右掉头 |
| 13 | `LaneActionLeftInAhead` | 直行含左转 |
| 14 | `LaneActionLeftLUturn` | 左转+左掉头（第二形态） |
| 16 | `LaneActionAheadLeftLUTurn` | 左前+左掉头 |
| 17 | `LaneActionRightLUTurn` | 右转+左掉头 |
| 18 | `LaneActionLeftRightLUTurn` | 左右转+左掉头 |
| 19 | `LaneActionAheadRightLUTurn` | 右前+左掉头 |
| 20 | `LaneActionLeftRUTurn` | 左转+右掉头 |
| 21 | `LaneActionBus` | 公交车道 |
| 23 | `LaneActionVariable` | 可变车道 |
| 24 | `LaneActionDedicated` | 专用车道 |
| 25 | `LaneActionTidal` | 潮汐车道 |

`LaneActionReserved(15)`、`LaneActionEmpty(22)`、`LaneActionDedicated(24)` 在原厂 JSON → 仪表链路里**不会留下图标**（15/22 无映射，24 映射到 88 后被 `LaneIconMapping` 丢弃）。

## 全表：`laneIconId`

下表由 `NavigationUtil` 的静态表逐项还原。`front=255` 表示导航态下本车道前方无后续动作；
`front=-1` 表示巡航态（`isNavi=false`）或没有前向动作；`front=自身动作` 表示下一段仍走同一方向，
原厂把它归到和 `-1` 同一组编号。复合项写成「本车道动作 + 下一段动作」。

### 导航态单动作（front=255）

| laneIconId | 车道动作 |
| ---: | --- |
| 0 | 直行 |
| 1 | 左转 |
| 2 | 左前 |
| 3 | 右转 |
| 4 | 右前 |
| 5 | 左掉头 |
| 6 | 左右转 |
| 7 | 直/左/右 |
| 8 | 右掉头 |
| 9 | 直行+左掉头 |
| 10 | 直行+右掉头 |
| 11 | 左转+左掉头 |
| 12 | 右转+右掉头 |
| 13 | 直行含左转 |
| 14 | 左转+左掉头（第二形态） |
| 49 | 左前+左掉头 |
| 50 | 右转+左掉头 |
| 51 | 左右转+左掉头 |
| 52 | 右前+左掉头 |
| 53 | 左转+右掉头 |
| 54 | 公交车道 |
| 55 | 可变车道 |

### 无前向变化 / 同向延续（front=-1 或 front=自身）

| laneIconId | 车道动作 |
| ---: | --- |
| 15 | 直行 |
| 16 | 左转 |
| 17 | 左前 |
| 18 | 右转 |
| 19 | 右前 |
| 20 | 左掉头 |
| 21 | 左右转 |
| 22 | 直/左/右 |
| 23 | 右掉头 |
| 24 | 直行+左掉头 |
| 25 | 直行+右掉头 |
| 26 | 左转+左掉头 |
| 27 | 右转+右掉头 |
| 28 | 直行含左转 |
| 29 | 左转+左掉头（第二形态） |
| 56 | 左前+左掉头 |
| 57 | 右转+左掉头 |
| 58 | 左右转+左掉头 |
| 59 | 右前+左掉头 |
| 60 | 左转+右掉头 |
| 61 | 公交车道 |
| 62 | 可变车道 |
| 95 | 潮汐车道 |

### 导航态复合动作（本车道 + 下一段）

| laneIconId | 复合动作 |
| ---: | --- |
| 30 | 左前 + 直行 |
| 31 | 左前 + 左转 |
| 32 | 右前 + 直行 |
| 33 | 右前 + 右转 |
| 34 | 左右转 + 左转 |
| 35 | 左右转 + 右转 |
| 36 | 直/左/右 + 直行 |
| 37 | 直/左/右 + 左转 |
| 38 | 直/左/右 + 右转 |
| 39 | 直行+左掉头 + 直行 |
| 40 | 直行+左掉头 + 左掉头 |
| 41 | 直行+右掉头 + 直行 |
| 42 | 直行+右掉头 + 右掉头 |
| 43 | 左转+左掉头 + 左转 |
| 44 | 左转+左掉头 + 左掉头 |
| 45 | 右转+右掉头 + 右转 |
| 46 | 右转+右掉头 + 右掉头 |
| 47 | 左转+左掉头（第二形态） + 左转 |
| 48 | 左转+左掉头（第二形态） + 左掉头 |
| 71 | 左前+左掉头 + 直行 |
| 72 | 左前+左掉头 + 左转 |
| 73 | 左前+左掉头 + 左掉头 |
| 74 | 右转+左掉头 + 右转 |
| 75 | 右转+左掉头 + 左掉头 |
| 76 | 左右转+左掉头 + 左转 |
| 77 | 左右转+左掉头 + 右转 |
| 78 | 左右转+左掉头 + 左掉头 |
| 79 | 右前+左掉头 + 直行 |
| 80 | 右前+左掉头 + 右转 |
| 81 | 右前+左掉头 + 左掉头 |
| 82 | 左转+右掉头 + 左转 |
| 83 | 左转+右掉头 + 右掉头 |

### 未产生 / 被丢弃

| 值 | 原因 |
| ---: | --- |
| 63~70 | 本版 `TRAFFIC_LANE_ICON_MAP` 不产生 |
| 68 | 动作 13 + 前向 13，被 `LaneIconMapping` 丢弃（`>62` 且不在 `71~83`） |
| 84 | 本版不产生 |
| 85 | 只作为 `trafficLaneIcon` 中间值，最终变 95 |
| 86 | 映射表保留给 96，本版不产生 |
| 88 | 专用车道 `24` 的中间值，被 `LaneIconMapping` 丢弃 |
| 96 | 映射表保留，本版不产生 |
| -1 | `LaneIconMapping` 未覆盖，整条车道被丢弃 |

## 写仪表时怎么用

`DimNaviService` 的事务 7：

```text
updateLaneInfo(List<AdaptLaneInfo>)
AdaptLaneInfo {
    int laneNumber      = trafficLaneNo（1 开始）
    int laneBackIconId  = 上表的 laneIconId
    Parcelable laneBackIcon = null
}
```

原厂顺序：`laneInfo` 是列表位置（`idx+1`），`LaneId` 是车道序号，`LaneIconId` 是箭头 ID。
`AdaptLaneInfo` 的类名不进 Parcel，字段顺序固定为
`laneNumber` → `laneBackIconId` → `laneBackIcon`；第三方按同样顺序实现一个 `Parcelable` 即可。
一个最小的两车道示例：

```text
laneNumber=1, laneBackIconId=16   // 左转
laneNumber=2, laneBackIconId=15   // 直行
```

注意：

1. **不要自己重排 ID 或把 `-1` 发给仪表**。原厂在 `convertToLaneInfoList` 里把 `-1` 的车道直接过滤掉；发 `-1` 不属于正常域。
2. **30424 缺 `trafficLaneIcon` 字段时 `optInt(..., 0)` 会落到 0（直行）**，这是原厂行为；第三方不要依赖“缺字段=无箭头”。
3. `laneBackIcon` 在原厂链路里恒为 `null`，仪表只按 `laneBackIconId` 取图标；自带位图覆盖这条路本车没有启用。
4. `laneIconId` 是**图标 ID**，不是车道序号；`LaneId` 才是 `trafficLaneNo`。

## 实测记录（2026-10-21，compound 组）

用 `samples/carprobe` 的 `dimlane` 探针（走真实 `DimInteraction` → `updateLaneInfo`）在
`CS11_A4` 上逐个推送，仪表实际画出来的图形与上表一致：

| `laneIconId` | 上表枚举 | 仪表画面 | 结果 |
| --- | --- | --- | --- |
| 6 | `LaneActionLeftRight` | 左右转 | ✅ |
| 7 | `LaneActionAheadLeftRight` | 直/左/右 | ✅ |
| 9 | `LaneActionAheadLUTurn` | 直行 + 左掉头 | ✅ |
| 10 | `LaneActionAheadRUTurn` | 直行 + 右掉头 | ✅ |
| 11 | `LaneActionLeftLUTurn` | 左转 + 左掉头 | ✅ |
| 12 | `LaneActionRightRUTurn` | 右转 + 右掉头 | ✅ |
| 21 | `6 + 15` | 与 6 同形、仅变亮 | ✅ 高亮组可用 |

做法（每 6 秒重推一次，仪表上基本常亮）：

```sh
D=/sdcard/Android/data/com.diplay.carprobe/files/carprobe.cmd
adb shell "echo 'dimlane 6 1' > $D"     # 换 6 / 7 / 9 / 10 / 11 / 12 / 21
```

因此 `GeelyLaneIcon` 里复合角度用到的这六个 id、以及「推荐车道 = 朴素 id + 15」这一约定，
都有实车画面支持，不再是纯静态推导。

## 残余未知

- **未逐值实车验证**：上表只覆盖了 compound 组（6/7/9/10/11/12/21）；0~62 其余值、71~83、95 的编号来自原厂代码，仪表/HUD ECU（QNX 侧）的具体图形没有在本工程内反编译。
- **`front=255` / `front=-1` 的中文名**是按 `getLaneInfo` 的分支和 `LaneKey` 反推的；原厂在导航态/巡航态使用不同的平行编号，第三方直传对应值即可。
- **96** 是 `LaneIconMapping` 为输入 86 保留的目标值，本版地图的静态表不产生 86；96 的具体图形语义未确认。
- 车道箭头与转向箭头是两套独立编号：转向用 `100~148`，车道用 `0~62 / 71~83 / 95`，不要混用。
