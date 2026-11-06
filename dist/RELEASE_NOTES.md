# v0.8.5

让 OPPO 健康支持小米体脂秤（1 代）的 LSPosed 模块。

## 发布内容

| 文件 | 说明 |
| --- | --- |
| `OHealthBridge-mi-scale1-v0.8.5.apk` | 模块安装包，771,746 B，versionCode 13 |
| `源码包-clean.zip` | Kotlin / Gradle 源码（已剔除 local.properties） |
| `RELEASE_NOTES.md` | 本文件 |

## 功能

- 在「添加设备 → 体脂秤」里加入「小米体脂秤（一代）」。
- 点开始配对后，模块扫描小米秤的 BLE 广播，走 OPPO 原生的绑定流程。
- 绑定后踩秤，模块解析数据写入 OPPO 健康，体脂率等由 OPPO 计算。
- 不写死 MAC，按广播特征识别。

## 支持版本

- OPPO 健康 6.10.13（仅此版本测试过）
- 小米体脂秤 1 代（其他型号未测）
- Android 8.0+，需 LSPosed / Xposed

## 安装注意

1. 本版签名证书为 `CN=Mi Scale Bridge Test`，与 v0.8.1 及更早版本不同，需先卸载旧版再装。
2. LSPosed 勾选模块（作用域 OPPO 健康），强制停止 OPPO 健康后重开。
3. 添加设备 → 体脂秤 → 小米体脂秤（一代）→ 开始配对；小米秤静置不广播，先轻踩唤醒。

## 隐私

源码与 APK 不含真实姓名、账号、生日、身高、体重、MAC 或 Token；不含数据导入、成员修正逻辑。

## 来源

上游基于 OHealthDeviceBridge（MIT），本项目针对 OPPO 健康 6.10.13 做了重写。
