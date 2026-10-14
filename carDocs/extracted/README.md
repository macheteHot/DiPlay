# extracted — 从车机拉取的应用（本地目录）

这个目录存放**从车机 adb 拉下来的原厂应用**，用于分析车机与 DiPlay 的交互方式。
内容不入库（见 `.gitignore`）：体积大、且属于厂商私有产物，只保留脚本与结论。

| 目录 | 应用 | 说明 |
| --- | --- | --- |
| [`geely-map/`](geely-map/README.md) | `com.geely.map`（GeelyMap.apk，`/vendor/app/GeelyMap/`） | 车机自带高德地图（AutoSDK）。HUD / 副驾屏地图渲染的来源。 |
| [`diminteraction/`](diminteraction/README.md) | `com.autolink.diminteraction`（DIMInteraction.apk） | 仪表/HUD 导航与投影通道。 |
| [`oneos-dimservice/`](oneos-dimservice/README.md) | `com.geely.dimservice`（OneOS-DIMService.apk） | 谁开关仪表导航区。 |
| `system/` | `/system`、`/system_ext`、`/product`、`/vendor` 全量（117 个 APK + framework JAR + permissions + getprop） | 整机系统逆向，见 [`../headunit/`](../headunit/README.md)。 |
| `svengmode-rea/` | 上述拉取产物的 REA 静态分析输出 | 复现 `../headunit/` 的结论时按需重新生成。 |

本机拉取产物已清理，目录里只剩下本文件与各子目录索引：表内每一项按标注的源路径重新 `adb pull` 即可复现。

相关结论文档：

- [`../吉利地图HUD显示机制.md`](../吉利地图HUD显示机制.md)
- [`../吉利仪表与HUD接入调研.md`](../吉利仪表与HUD接入调研.md)
- [`../headunit/README.md`](../headunit/README.md)（整机框架/APK 逆向，含键码与协议常量表）
