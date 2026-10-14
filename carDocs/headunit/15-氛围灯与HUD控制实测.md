# 氛围灯 / HUD 控制（实机实测）

> 2026-10-14，在 `geely_fx11`（`CS11_A4` / `Build.DEVICE=msmnile_gvmq`）上用 `samples/carprobe`
> 实测。全部带**设备门控**（只在 `msmnile_gvmq` 上执行）。
> 相关：[`10-氛围灯与灯光.md`](10-氛围灯与灯光.md)、[`11-音乐律动氛围灯.md`](11-音乐律动氛围灯.md)、
> [`12-可调项总表.md`](12-可调项总表.md)、[`02-框架与VDBus.md`](02-框架与VDBus.md)。

## 一句话

**普通第三方 App 可以直接读/写氛围灯与 HUD 的车机属性**（`ecarx.car.jar` 在 boot classpath）；
`adaptapi` 的 HUD 入口是空壳、不可用；**音乐律动在本机不可用**（拿不到音频频谱）。

## 一、可用通道（实测，uid 10059 普通第三方）

`ecarx.car.jar` / `ecarx.adaptapi.jar` 在 **boot classpath**（设备带 `boot-ecarx.car.vdex`、
`boot-ecarx.adaptapi.vdex`），因此 `Class.forName` 无需把厂商 jar 放进 App。设备上的
`ecarx.car.jar` / `ecarx.adaptapi.jar` 与反编译副本 **md5 一致**。

调用链（反射，不引用厂商类）：

```
ecarx.car.ECarXCar.createCar(context, ServiceConnection)   // 返回 ECarXCar
  .connect()                                               // 异步；onServiceConnected 后 isConnected()=true
  .getCarManager("car_publicattribute")                    // = ECarXCarSetManager
      .getECarXCarAmbliManager()                           // 氛围灯
      .getECarXCarVfhudManager()                           // HUD
```

- 实测连上：`com.ecarx.car/.ECarXCarService`，`isConnected()=true`。
- `ECarXCar.getCarManager(String)` **只认三个名字**（见 `ECarXCar.createCarManager`）：
  `PA_SERVICE="car_publicattribute"` → `ECarXCarSetManager`；
  `SIGNAL_SERVICE="car_signal"` → `CarSignalManager`；
  `CAR_HARDKEY_SERVICE="car_hardkey"` → `ECarXCarHardKeyManager`。
- `ECarXCarSetManager` 里有几十个 `getECarXCarXxxManager()`，氛围灯/HUD 都在其中。
- 读：`getPA_*()` → `PATypes{availability, dataValue, statusValue, formatValue}`。
- 写：`CB_*()` → `ApiResult`，实测返回 `SUCCEED`。

### 1.1 氛围灯（`ECarXCarAmbliManager`）

分区：**Zone1..5 + ZoneAll**，每区独立 **Color / Intensity / Status**。

| 维度 | 取值（实测） | 说明 |
| --- | --- | --- |
| 颜色 | `CB_Zone{n|All}ColorSettings(0xRRGGBB)` | **任意 24bit RGB**（alpha 丢弃）；写 `0xFFFF0000` 回读 `16711680`，灯变红 |
| 亮度 | `CB_Zone{n|All}IntensitySettings(0..255)` | 实测写 255 回读 255 |
| 开关 | `CB_Zone{n|All}StatusSettings(1|0)` | 实测 `Zone1` Off→On 生效 |
| 主色 | `CB_MoodLightColor(int)` | 枚举 `VFMiscMoodLightCoulor`（8 色 + OFF） |
| 模式 | `CB_AmbLiModSetting(int)` | 枚举 `AmbLiMod` |

主色枚举 `VFMiscMoodLightCoulor`（dexdump 常量）：
`OFF=0`、`Red=1`、`Orange=2`、`Yellow=3`、`Green=4`、`Indigo=5`、`Blue=6`、`Violet=7`、`White=8`。

模式枚举 `AmbLiMod`：`None / ColorStyleMode / CustomizedMode(=1) / DriveMode / MusicShowMode(=3) /
ThemeMode / TimeMode / WeatherIndn`。

实测（`samples/carprobe`）：
- `steady`：`CB_ZoneAll{Status=1, Color=0xFF0000, Intensity=255}` → 灯常亮红；
- `wheel`：`CB_ZoneAllColorSettings` 扫 HSV 色相 0..360°，灯随色轮变化；
- `marquee`：`Zone1..5` 依次 `Intensity=255/其余25`，形成跑马（写全 `SUCCEED`）。

### 1.2 HUD（`ECarXCarVfhudManager`）

只读实测（同一 `ECarXCarSetManager`）：

```
getPA_VF_HUD_ActvSts()      -> On
getPA_VF_HUD_ARHudActvReq() -> On
getPA_VF_HUD_HudSnowModeReq() -> Off
getPA_VF_HUD_IllmnReq() / getPA_VF_HUD_ErgoAdjmtReq() -> Inactive
getPA_HUD_DispModSet()      -> 车机自身 ClassCastException（Integer→byte[]，vendor bug）
```

写方法存在（未做破坏性测试）：`CB_VF_HUD_ActvReq / IllmnReq / ErgoAdjmtReq / Set_ErgoAdjmtReq /
ImgRotAdjmtReq / Set_ImgRotAdjmtReq / HudSnowModeReq / ARHudActvReq / Reboot`（均 `int`）。
`IHUD.SETTING_FUNC_HUD_*` 功能号见 [`06-键码与协议.md`](06-键码与协议.md) 第四节。

## 二、不可用通道（第三方）

- **adaptapi `HUDInteraction`**：`HUDInteraction.create(Context)` 反编译即 `return null;`（stub），
  设备实测同样返回 `null`。
- **adaptapi `HUDInteractionImpl`**：构造函数 `private`；反射强造抛
  `ClassCastException: Integer cannot be cast to byte[]`，且其信号回调
  `HUDInteractionImpl$1.onChangeEvent` 抛 `Float cannot be cast to Integer`，**会打崩调用进程**。
- **adaptapi `Car.create(Context)`**：`SecurityException: Permission denied`。
- **adaptapi `Lamp` / `HUD`（`car.vehicle`）**：可反射实例化但未接线，`buildFunctions()` /
  `getFunctionStatus()` NPE（`ECarXCarSetManager` 为 null）。
- **`LightShowImpl.create()`**：可用但 `isLightingShowEnabled()=notactive`（灯光秀，非律动）。

## 三、音乐律动：三个来源都不通

| 来源 | 结果 |
| --- | --- |
| DiPlay 自身 PCM（CarPlay 音频） | 本车 UDP 丢包，音频改走蓝牙 → DiPlay 拿不到音频 |
| 车机频谱 `IAudioExtControl.registerDataCallback(IVisualizerCallback)` | 服务可绑、回调可注册，但**车机不推数据** |
| 本地 `Visualizer(0)`（抓输出混音） | `RuntimeException: ... error: -3`（`ERROR_NO_INIT`）——输出混音没有挂 visualizer 效果 |

车机侧证据：
- `AudioExtControlService`（**exported**，action `com.autolink.audioextservice.AudioExtControlService`）
  可绑，接口 `IAudioExtControl`；`registerDataCallback` = 事务码 22，`IVisualizerCallback.onDataChanged` = 1。
- `AudioExtService`（**exported**，action `com.autolink.audioextservice.AudioExtService`）返回
  `IAudioExtService`，但其 `notifyMediaStatusChanged(int,String)`（事务码 1）**反编译是空实现**（只打日志）。
- 真正启动 Visualizer 的是 `AudioExtService` 内部的音频焦点回调 → `VisualizerManager.updateMusicState`
  → `prepareVisualizer` → `initVisualizer`；本机没有触发。
- `dumpsys media.audio_flinger`：**0 Effect Chains**（没有任何音频效果挂载）。
- 设置 App 里只有「氛围灯联动（里程/雷达/欢迎/欢送）」，**没有「音乐律动」入口**；
  `isHighAmbient()`（`persist.vendor.ecarx.cfg.cc.interior.illumination != 19`）不是门槛。

⇒ 判断：音乐律动的代码在，但**这台车的配置没有启用/触发**（不是能从第三方 App 翻开的开关）。

## 四、`samples/carprobe` 命令（门控 `msmnile_gvmq`）

| 命令 | 作用 |
| --- | --- |
| `ambli` | 氛围灯只读（模式/联动/各 zone 颜色/亮度/状态） |
| `ambprobe` | 亮度量程 + 颜色格式实测（会短暂写入再还原） |
| `ambsteady` | ZoneAll 常亮单色（红 0xFF0000，亮度 255） |
| `ambwheel` | ZoneAll 色轮扫掠（结束还原） |
| `ambmarquee` | Zone1..5 跑马（结束还原） |
| `ambmusic` | 切原厂 `MusicShowMode` + 打开音乐联动 |
| `ambrestore` | 还原（回 `CustomizedMode`、音乐联动关、恢复 zone 快照/基线） |
| `ambspectrum` | 绑 `AudioExtControlService` 注册频谱回调 |
| `ambvisualizer` | 本地 `Visualizer(0)` 抓输出混音 |
| `ambforcemusic` | 调 `IAudioExtService.notifyMediaStatusChanged` |
| `hudapi` | adaptapi HUD 只读 |
| `hudimpl` | `HUDInteractionImpl`（独立进程，防崩主进程） |

## 五、门控

所有会动车的操作都要求 `Build.DEVICE == "msmnile_gvmq"`，否则直接拒绝（不写任何属性）。
还原采用"快照 + 基线兜底"，避免把车留在异常状态。

## 六、给 DiPlay 的结论

- **能做**：读/写氛围灯颜色（任意 RGB）、亮度（0–255）、分区开关、模式/联动；
  读/写 HUD 的部分属性。
- **不能做**：让氛围灯跟音乐律动（拿不到音频频谱）。
- **落地方式**：`ECarXCar → getCarManager("car_publicattribute") → getECarXCarAmbliManager()`，
  写 `CB_Zone{1..5,All}{Color,Intensity,Status}Settings`；色号→RGB 可复用
  `AmbientAlbumPalette.previewRgb(1..31)`，亮度映射到 0–255。
