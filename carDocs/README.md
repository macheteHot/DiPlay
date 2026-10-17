# carDocs — 索引

本目录是**吉利/领克 FX11（`geely_fx11` / `CS11_A4`，DesaySV，高通 SA8155，Android 11）**这台车机的适配记录与文档。

## 给同款车主：从这里开始

| 文档 | 语言 | 用途 |
|---|---|---|
| [`同款车机安装指南.md`](同款车机安装指南.md) | 中 | **从零到可用**：安装 → Wi-Fi → 停用 HiCar → 麦克风 → 图标 → 限制 → 验收 → 排错 |
| [`INSTALL-SAME-HEAD-UNIT.md`](INSTALL-SAME-HEAD-UNIT.md) | 英 | 同上，英文版 |

> ⚠️ **不提供现成 APK**：release 包内嵌了从车机提取的离线 MFi 身份（Apple 凭据），不能公开分发。
> 请用自己的车机资产按 [`build-lynk.sh`](build-lynk.sh) 自行构建。

## 排查 / 诊断

| 文档 | 语言 | 用途 |
|---|---|---|
| [`诊断法-实时音频UDP丢包.md`](诊断法-实时音频UDP丢包.md) | 中 | **UDP 自检法**：判断「音频几秒后断」是不是车机 Wi-Fi 收包问题 |
| [`DIAGNOSTIC-UDP-AUDIO-LOSS.md`](DIAGNOSTIC-UDP-AUDIO-LOSS.md) | 英 | 同上，英文版 |
| [`tools/udp-soak/`](tools/udp-soak/README.md) | — | 诊断用最小 UDP 接收 App（源码 + 构建说明） |

## 记录 / 结论

| 文档 | 用途 |
|---|---|
| [`车机兼容记录.md`](车机兼容记录.md) | 各问题的**实测根因与处理**（Wi-Fi/热点、方向盘键、麦克风、实时音频、跨 AP 实测结论） |
| [`车机安装与桌面说明.md`](车机安装与桌面说明.md) | 安装机制（adbd 关键字过滤）、桌面环境、命令速查 |
| [`车机桌面集成结论.md`](车机桌面集成结论.md) | 桌面集成结论 |
| [`吉利地图HUD显示机制.md`](吉利地图HUD显示机制.md) | 车机自带高德地图（`com.geely.map`）如何把 HUD/副驾屏地图渲染进宿主 `Surface`（原厂 APK 分析见 [`extracted/`](extracted/README.md)） |
| [`吉利仪表与HUD接入调研.md`](吉利仪表与HUD接入调研.md) | 第三方 App 能用的仪表/HUD 通道（DIM 导航协议、仪表 Presentation）与死路，含探针 App `samples/carprobe` 实测 |
| [`吉利导航转向ID-turnId表.md`](吉利导航转向ID-turnId表.md) | 原厂地图/OneOS 导航服务 → 仪表/HUD 的**转向图标 ID（turnId 100~148）全表**，含换算公式与 Kotlin 常量 |
| [`headunit/`](headunit/README.md) | **车机整机系统逆向**（117 个 APK + framework JAR）：ECARX/德赛框架 API、VDBus、手机互联、投屏服务、键码/协议常量表 |

## 脚本

| 脚本 | 用途 |
|---|---|
| [`build-lynk.sh`](build-lynk.sh) | 构建 lynk release（自动提取 MFi 资产 + 本地签名） |
| [`install-apk.sh`](install-apk.sh) | 车机安装（绕过 adbd 的 `install` 关键字过滤） |

## 一句话结论

这台车**实时 UDP 音频不可用**（车机 Wi-Fi 收包实测丢 77~87%），因此实时音乐/导航/通话只能走
**「缓冲音乐」（TCP）** 或 **「车机蓝牙」**；其余功能（画面、触控、方向盘键、Siri 麦克风）正常。
详见 [`车机兼容记录.md`](车机兼容记录.md) 第十节与上游 issue #165 / #131。
