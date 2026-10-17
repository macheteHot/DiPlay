# 吉利原厂导航转向 ID（turnId）全表

本文是从车机原厂包里**静态还原**出来的转向图标识号表，供 CarPlay 导航信息写入**仪表盘 / HUD** 时使用。
车道区的 `laneIconId` 是另一套编号，见 [`吉利导航车道图标ID-laneIconId表.md`](吉利导航车道图标ID-laneIconId表.md)。

分析对象（均为本机 `/system`、`/vendor` 下的出货产物）：

| 包 | 作用 |
| --- | --- |
| `com.geely.map`（`GeelyMap.apk`） | 原厂高德地图：产生 `newIcon` + `roundAboutNum` |
| `com.geely.naviService`（`OneOS-NaviService.apk`） | OneOS 导航服务：把 `newIcon` 换算成 `turnId` |
| `com.autolink.diminteraction`（`DIMInteraction.apk`） | 仪表侧服务：`turnId` → `nextNaviAction` → someIP |
| `com.geely.dimservice`（`OneOS-DIMService.apk`） | OneOS 侧把 `RspGuideInfo` 交给 `INaviInteraction` |
| `/system/framework/ecarx.adaptapi.jar` | 框架层 `NaviInteraction`，转发到 `DimNaviService` |

拉取与反编译见 [`extracted/README.md`](extracted/README.md)、[`吉利地图HUD显示机制.md`](吉利地图HUD显示机制.md)、[`吉利仪表与HUD接入调研.md`](吉利仪表与HUD接入调研.md)。

## 一句话结论

原厂链路实际下发给仪表的 `turnId` **不是 1~8，也不是 0~28，而是 `100~148`**：

```text
turnId = TbtIconMapping.autoToMapSDK(newIcon, roundAboutNum)
```

- `newIcon` 来自高德 30407 引导信息（`GuideInfoModel.newIcon`，等于 `maneuverID`；`11→12`、`17→18` 会先被改写）。
- `roundAboutNum` 是环岛出口序号（1~10）。
- 0~99 在原厂链路里**不会出现**；`newIcon` 不在表内时返回 `-1`。
- `turnId` 在整条链路上**原样透传**，到 DIM 侧只改名为 `IntersectionInfo.nextNaviAction`，最后以 someIP 字段 `NextNaviActiion` 发出。

> 本表对应本车原厂高德路径（`NaviServerManager` 的 `getPackageName() == "com.geely.map"`）。
> `OneOS-NaviService` 也带百度地图支持（`com.baidu.naviauto`），其 `getTurnIconId()` 由百度实现提供，编号域可能不同；本车没有启用。

## 证据链（REA Evidence）

| 步骤 | 位置 | 观察 | Evidence |
| --- | --- | --- | --- |
| ① 地图生产 `newIcon` / `roundAboutNum` | `com.autosdk.common.utils.CommonUtil.getResultByGuideInfo` | `maneuverID`（`11→12`、`17→18`）写入 `newIcon`；环岛时 `roundAboutNum = naviInfo.ringOutCnt`；无 `ManeuverInfo` 时用 `crossManeuverID` | `ev_c62f181aca5ee5741b7fc95a1c7c928df505da6b8098135bb3aa8475c88fcab6` |
| ② 地图 HUD 图标资源 | `com.autosdk.bussiness.common.ManeuverBitmap` | `autosdk_hud_sou<maneuverId>`；环岛 `sou(49+N)` / `sou(59+N)`；`65→sou71`、`66→sou70` | `ev_47de977821b69145c8bd752d20bc84c55fd5bb46431a9b8a5093c45b0fa5ac63` |
| ③ turnId 换算 | `com.geely.map.mapsdk.utils.TbtIconMapping.autoToMapSDK` | `newIcon → 100~148`（见下表），未知返回 `-1` | `ev_8a02b8b4b6978d224a8dc5eeb2ae8e0ea04a165f337d5dc222f8f0cb505bcc9f` |
| ④ 协议模型 | `GuidingInfoModel30407Impl.getTurnIconId()` | 读 30407 的 `newIcon`、`roundAboutNum` 后调用 ③ | `ev_d27c0c3fc9ddef04833372f5744c0a9bed1351b3f8c53debb7d3de95cc887d87` |
| ⑤ 写入 OneOS 响应 | `IGuidingInfoModelConvert.convertToRspGuideInfo` | `rspGuideInfo.setTurnId(iGuidingInfoModel.getTurnIconId())` | `ev_28e3c35082b15b9afe7a3a0fa8461a4bac6cc63cbc83df7ef122030032e17870` |
| ⑥ OneOS 侧转发 | `INaviEventListenerImpl.updateNavigationInfo` | `getNavigationTurnId() { return rspGuideInfo.getTurnId(); }` | `ev_f9ade41914f45743fc9b7b7a12feace518474807846ddbb1fde77752d8ccc085` |
| ⑦ 框架转发 | `ecarx.adaptapi` → `NaviInteraction.updateNavigationInfo` | `mService.updateNavigationTurnId(info.getNavigationTurnId())` | `ev_00b83759db19b54afd354058d0acd8d415914884ca28016d5c11ab384eeb2e88` |
| ⑧ 仪表侧接收 | `DimNaviService.updateNavigationTurnIdFun` | `mIntersectionInfo.nextNaviAction = turnId`，随后 `SomeIPUtils.Navi.sendIntersectionInfo` 发 `NextNaviActiion` | 源码 `carDocs/extracted/diminteraction/out/.../DimNaviService.java` |

> 证据 ①~⑦ 来自 `OneOS-NaviService.apk`、`OneOS-DIMService.apk`、`ecarx.adaptapi.jar` 的静态反编译；⑧ 是反编译源码。
> 未在实车上逐个图标验证，见文末「残余未知」。

## 全表：turnId 100~148

`newIcon` 是地图 30407 的原始转向号；「仪表/HUD 图标」是地图自家 HUD 资源名（`autosdk_hud_sou*`），可用于第三方自绘 HUD 时对号。

### 直行 / 转弯 / 道路事件

| newIcon | turnId | 含义 | 地图 HUD 图标 |
| ---: | ---: | --- | --- |
| 0 | 100 | 无转向 / 未定义 | `sou0`（定位针） |
| 1 | 101 | 自车（原厂未给独立图标） | —（无 `sou1`） |
| 2 | 102 | 左转 | `sou2` |
| 3 | 103 | 右转 | `sou3` |
| 4 | 104 | 左前方 | `sou4` |
| 5 | 105 | 右前方 | `sou5` |
| 6 | 106 | 左后方 / 急左转 | `sou6` |
| 7 | 107 | 右后方 / 急右转 | `sou7` |
| 8 | 108 | 掉头 | `sou8` |
| 9 | 109 | 直行 | `sou9` |
| 10 | 110 | 途经点（经） | `sou10` |
| 11 | 111 | 进入环岛 | `sou11` |
| 12 | **112** | 驶出环岛 / 直行出口（未带 1~10 出口号，见下节） | `sou12` |
| 13 | 113 | 服务区 / 停车场 | `sou13` |
| 14 | 114 | 收费站 | `sou14` |
| 15 | 115 | 到达目的地（终） | `sou15` |
| 16 | 116 | 进入隧道 | `sou16` |
| 17 | 117 | 进入左环岛 | `sou17` |
| 18 | **118** | 驶出左环岛 / 直行出口（未带 1~10 出口号，见下节） | `sou18` |
| 19 | 119 | 掉头（路口放大形态） | `sou19` |
| 20 | 120 | 直行（路口放大形态） | `sou20` |
| 21 | 141 | 环岛左出口（气泡形态） | `sou21` |
| 22 | 142 | 环岛右出口（气泡形态） | `sou22` |
| 23 | **112** | 环岛直行（气泡形态） | `sou23` |
| 24 | 143 | 环岛掉头 / 后方出口（气泡形态） | `sou24` |
| 25 | 144 | 环岛左出口（第二形态） | `sou25` |
| 26 | 145 | 环岛右出口（第二形态） | `sou26` |
| 27 | **118** | 环岛直行（第二形态） | `sou27` |
| 28 | 146 | 环岛掉头 / 后方出口（第二形态） | `sou28` |
| 65 | 147 | 靠左 | `sou71` |
| 66 | 148 | 靠右 | `sou70` |
| 其他 | -1 | 原厂映射表未覆盖 | — |

> 关于 11/12、17/18：地图在写 30407 前会把 `maneuverID` 的 `11→12`、`17→18` 各改写一次
> （`CommonUtil.getResultByGuideInfo`），所以协议/TurnId 侧的 112/118 与地图 HUD 的 `sou12/sou18` 对号，
> 而源 `maneuverID` 是 11/17。地图的旧 `icon` 字段也把 `23→12`、`27→18`
> （`GuideInfoModel.convertIcon`），与本表里 `newIcon 23→112`、`27→118` 的合并一致。
> 原厂改写语义与实车 ECU 图形未逐一验证，见文末。

### 环岛出口（`roundAboutNum = 1~10`）

只有 `newIcon = 12`（驶出环岛）和 `newIcon = 18`（驶出左环岛）会带出口号；出口号非法（≤0 或 >10）时退回 112 / 118。

| 出口 N | newIcon 12 → turnId | 地图 HUD 图标 | newIcon 18 → turnId | 地图 HUD 图标 |
| ---: | ---: | --- | ---: | --- |
| 1 | 121 | `sou50` | 131 | `sou60` |
| 2 | 122 | `sou51` | 132 | `sou61` |
| 3 | 123 | `sou52` | 133 | `sou62` |
| 4 | 124 | `sou53` | 134 | `sou63` |
| 5 | 125 | `sou54` | 135 | `sou64` |
| 6 | 126 | `sou55` | 136 | `sou65` |
| 7 | 127 | `sou56` | 137 | `sou66` |
| 8 | 128 | `sou57` | 138 | `sou67` |
| 9 | 129 | `sou58` | 139 | `sou68` |
| 10 | 130 | `sou59` | 140 | `sou69` |

因此 `turnId` 的完整可达集合就是 **100~148 连续 49 个值**。

## 两个必须注意的坑

1. **112 / 118 是等价合并值**
   - `112` = `newIcon 12` 未带合法出口号 **或** `newIcon 23`（环岛直行出口）。
   - `118` = `newIcon 18` 未带合法出口号 **或** `newIcon 27`（左环岛直行出口）。
   原厂用同一张图表示这两种输入（旧 `icon` 也是 `23→12`、`27→18`），所以仪表侧无法区分，也不需要区分。

2. **不要照搬探针里的 1~8**
   [`吉利仪表与HUD接入调研.md`](吉利仪表与HUD接入调研.md) 的探针用 `turnId = 1..8` 只是验证 binder 通路的占位值，**不属于原厂图标域**。
   真实显示必须用 100~148；探针的 1~8 在仪表上大概率落到未知/空白图标。

## 给 DiPlay 用的 Kotlin 常量

```kotlin
/** 原厂 OneOS 导航 → DIM 的转向图标 ID（turnId）。数值来自 GeelyMap/OneOS-NaviService。 */
object GeelyTurnId {
    const val UNKNOWN = 100
    const val SELF = 101
    const val LEFT = 102
    const val RIGHT = 103
    const val SLIGHT_LEFT = 104
    const val SLIGHT_RIGHT = 105
    const val HARD_LEFT = 106
    const val HARD_RIGHT = 107
    const val U_TURN = 108
    const val CONTINUE = 109
    const val WAYPOINT = 110
    const val ENTER_ROUNDABOUT = 111
    const val LEAVE_ROUNDABOUT = 112
    const val SERVICE_AREA = 113
    const val TOLL_GATE = 114
    const val DESTINATION = 115
    const val TUNNEL = 116
    const val ENTER_LEFT_ROUNDABOUT = 117
    const val LEAVE_LEFT_ROUNDABOUT = 118
    const val U_TURN_BUBBLE = 119
    const val CONTINUE_BUBBLE = 120

    // 气泡/第二形态的环岛转弯
    const val ROUNDABOUT_LEFT_BUBBLE = 141
    const val ROUNDABOUT_RIGHT_BUBBLE = 142
    const val ROUNDABOUT_UTURN_BUBBLE = 143
    const val ROUNDABOUT_LEFT_2 = 144
    const val ROUNDABOUT_RIGHT_2 = 145
    const val ROUNDABOUT_UTURN_2 = 146

    const val KEEP_LEFT = 147
    const val KEEP_RIGHT = 148

    /** 环岛第 exit 个出口（1~10），否则退回 [LEAVE_ROUNDABOUT]。 */
    fun roundaboutExit(exit: Int): Int = if (exit in 1..10) 120 + exit else LEAVE_ROUNDABOUT

    /** 左环岛第 exit 个出口（1~10），否则退回 [LEAVE_LEFT_ROUNDABOUT]。 */
    fun leftRoundaboutExit(exit: Int): Int = if (exit in 1..10) 130 + exit else LEAVE_LEFT_ROUNDABOUT
}
```

写仪表：

```kotlin
binder.transact(N_UPDATE_TURN_ID /* 6 */, data.apply { writeInt(GeelyTurnId.LEFT) }, null, 0)
```

`DimNaviService` 的事务码、`notifyDimProjectScreenSwitch` 等见
[`吉利仪表与HUD接入调研.md`](吉利仪表与HUD接入调研.md) 与 `samples/carprobe/.../DimProtocol.java`。

## 残余未知

- **未逐值实车验证**：100~148 的编号与换算来自原厂代码，但仪表/HUD ECU（QNX 侧）的具体图形没有在本工程内反编译；本表按原厂链路推断 ECU 认这套 ID。
- **19~28 的中文名**是按地图自带 HUD 矢量图（`autosdk_hud_sou19`~`sou28`）的外观标注的；原厂常量类 `RouteActionUtil` 对这些值的命名（`Bubble_midd*`、`Merge_*`）与实际图形不完全一致，属于历史命名，勿用于判图。
- **11→12 / 17→18 改写**是原厂代码里的实际行为，但「源 maneuverID 11 究竟是进入还是驶出环岛」没有运行时日志可对照；本表按地图 HUD 资源（`sou11`=进入、`sou12`=驶出）标注。若实车把 111/112 显示反了，对调这两个常量即可，其余 ID 不受影响。
- **`nextNextTurnIcon`**（下一路口的再下一个转向）在 `RspGuideInfo` 里是**原始 maneuverID**，不是 turnId；DIM 协议没有对应字段，本链路不传。
- 原厂 `turnId` 只覆盖高德转向枚举；CarPlay 的环岛、出口、匝道语义需要第三方先映射到上表，未覆盖的组合建议回退到 `UNKNOWN`(100) 或最接近的转弯图标。
