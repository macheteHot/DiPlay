# headunit — 吉利 FX11 车机系统逆向

对 `carDocs/extracted/system/`（从车机拉取的 117 个 APK + framework JAR + 权限 + getprop）
做静态逆向的结论集合。方法：**REA**（`rea-agents@6.4.0` + 审计版 `jadx-headless-mcp 0.7.1`），
辅以 `dexdump` / `aapt2` / `getprop`（凡普通工具观测均在文内标注）。

> 各章标注的 `extracted/*` 原始产物与根目录 `oneosapi-all.bin` 是只在本机的拉取结果，早已清理、不入库；
> 需要复现时按相同路径重新从车机拉取（来源见 `carDocs/extracted/README.md`）。结论文档与
> `evidence/` 的静态分析证据保留，文内引用按当时的路径书写。

## 文档

| 文档 | 内容 |
| --- | --- |
| [`01-系统盘点.md`](01-系统盘点.md) | 设备/固件身份、分区、117 个 APK 清单、framework JAR、权限、待挖清单 |
| [`02-框架与VDBus.md`](02-框架与VDBus.md) | `ecarx.car.jar` / `ecarx.adaptapi.jar` / `autolink.adapterservice.jar`（VDBus + `ecarx.fw.api.*`）的类与 API |
| [`03-手机互联与投屏.md`](03-手机互联与投屏.md) | CarLink/HiCar/FlymeLink/Starinyt、phonelink AIDL、`IProject` 投屏、副驾屏/分屏、导航 JSON 协议 |
| [`04-音频与输入.md`](04-音频与输入.md) | `AudioExtService` + `adapterbinder`、麦克风、`OneOS-InputService`、ECARX 硬按键、蓝牙、权限 |
| [`05-顶层应用与子系统.md`](05-顶层应用与子系统.md) | 服务/代理层、桌面/系统栏、设置、车辆信号字典、厂商应用 |
| [`06-键码与协议.md`](06-键码与协议.md) | 厂商键码表、ECARX 硬按键常量、仪表导航 ACTION、HUD 功能号、投屏服务常量、地图协议号 |
| [`07-启动界面与品牌.md`](07-启动界面与品牌.md) | 启动画面（Launcher splash = LYNK & CO）、新手引导、开机动画缺失说明、主题壁纸 |
| [`08-Launcher功能.md`](08-Launcher功能.md) | OneOS-Launcher3 全功能：首页卡片/小组件、应用页、全局搜索、白名单、零页生态、副驾屏、壁纸主题、无界互联/投屏 |
| [`09-隐藏能力与未开启项.md`](09-隐藏能力与未开启项.md) | 隐藏的工程模式/DTC/ADB、SV 调试服务、白名单宣称但未预装、当前为关的开关、车型能力门控 |
| [`10-氛围灯与灯光.md`](10-氛围灯与灯光.md) | 氛围灯/阅读灯/灯光秀：控制方 APK 与进程、ECARX 属性 ID 协议、灯光功能号、灯光秀传输 |
| [`11-音乐律动氛围灯.md`](11-音乐律动氛围灯.md) | 音乐/语音律动：Visualizer→FFT→频段/增益→MusicLightShow protobuf→FDBus，开关与可调参数 |
| [`12-可调项总表.md`](12-可调项总表.md) | 除氛围灯外的可调项：DSP/EQ/声场/混音、HUD、车灯、51 个车辆功能管理器、设置页、工程模式 |
| [`13-APK安装通道.md`](13-APK安装通道.md) | 不用 adb 的安装：系统安装器、商店 TaskService 静默安装协议、手机互联/OTA/PKI |
| [`14-USB与有线CarPlay.md`](14-USB与有线CarPlay.md) | 本车 USB 是 accessory/AOA + vendor `carplay_carlife` gadget、未声明 usb.host → 有线 CarPlay 不可行的系统原因 |
| [`15-氛围灯与HUD控制实测.md`](15-氛围灯与HUD控制实测.md) | **实机实测**：普通第三方可读写氛围灯（任意 RGB/亮度 0-255/分区）与 HUD 属性；adaptapi HUD 入口不可用；音乐律动三个来源都不通 |
| [`16-热点与Wi-Fi状态.md`](16-热点与Wi-Fi状态.md) | 车机热点是 vendor 的 `p2p0` 本地热点，标准 SoftAP 状态看不到；原厂 `TcamWifi` / `ExternalWifiManager` 管 Wi-Fi/热点 |

相关既有文档（同目录的上一级 `carDocs/`）：

- [`../吉利仪表与HUD接入调研.md`](../吉利仪表与HUD接入调研.md) — 本机仪表/HUD 通道实测结论
- [`../吉利地图HUD显示机制.md`](../吉利地图HUD显示机制.md) — 原厂地图如何把地图画进 HUD/仪表 Surface
- [`../车机兼容记录.md`](../车机兼容记录.md) — Wi-Fi/音频/麦克风/方向盘键实测

## 证据与复现

原始 REA evidence（类清单/成员清单/方法反编译）在 `evidence/`，由脚本生成：

```sh
# 1) REA 需要单独提供的 JADX 引擎（SHA-256 与 REA 审计值一致）
curl -L -o ~/.cache/rea/jadx-headless-mcp-0.7.1-all.jar \
  https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1/jadx-headless-mcp-0.7.1-all.jar
export REA_JADX_MCP_JAR="$HOME/.cache/rea/jadx-headless-mcp-0.7.1-all.jar"

# 2) 采集
bash tools/collect-rea.sh        # 各 APK 与 framework JAR 的 package/classes 清单
bash tools/inspect-classes.sh    # 关键类的成员清单
bash tools/inspect-methods.sh    # 关键方法的反编译
bash tools/dump-consts.sh        # 静态常量值（dexdump）

# 3) 单点查询
npx -y rea-agents@6.4.0 inspect-android-class <apk> <fqcn> --format json --filter-output normalized_result
npx -y rea-agents@6.4.0 search-android-classes <apk> "" --format json --filter-output normalized_result
```

> 注意：本机 framework JAR 是 **DEX-in-JAR**，REA 的 Android provider 只接受 stand-alone APK，
> 因此 `collect-rea.sh` 会把每个 `classes.dex` 包一层空 APK（`/tmp/headunit-wrap/*.apk`）再喂给 REA。
> 这些包装 APK 只用于分析，不是安装包。

## 一句话总览

这是一台 **DesaySV（德赛西威）代工、ECARX（亿咖通）软件栈**的吉利/领克 FX11（F`geely_fx11`/`CS11_A4`，
高通 SA8155，Android 11）。车机把大量能力拆成多层：
**AOSP Car → 车厂 CarService 扩展（`com.ecarx.car`）→ 适配代理（`com.autolink.*` + VDBus）→ OneOS API（`com.geely.*`）→ 应用**。
对第三方（DiPlay）**已确认可用**的是：`Presentation` 投屏 + DIM `notifyDimProjectScreenSwitch`、
`IInputManager` 方向盘键订阅、OneOS `INaviServer` 调用、`ECarXCar` 车辆属性（**氛围灯读写**、HUD 读）；
**待验证**的高价值通道：`IProject` 投屏服务、`ExternalWifiManager`/`TcamWifi` 开关 Wi-Fi、
`ECarXCarHardKeyManager` 拦麦克风键。
**已实测不可用**：`adaptapi` HUD 入口（stub/崩溃）、音乐律动（拿不到音频频谱）。
