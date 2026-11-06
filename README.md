# OHealthBridge-mi-scale1

让 OPPO 健康支持小米体脂秤（1 代）的 LSPosed 模块。

## 支持版本

| 项目 | 版本 / 要求 |
| --- | --- |
| OPPO 健康 | 6.10.13（仅此版本测试过） |
| 秤 | 小米体脂秤 1 代（其他型号未测） |
| 系统 | Android 8.0+ |
| 框架 | LSPosed / Xposed（Zygisk 或 Riru） |

其他 OPPO 健康版本混淆有差异，不保证能用。

## 能做什么

1. 在「添加设备 → 体脂秤」里加入「小米体脂秤（一代）」。
2. 点开始配对后，模块扫描小米秤的 BLE 广播，发现后走 OPPO 原生的绑定流程。
3. 绑定后踩秤，模块解析广播数据，把体重和阻抗写入 OPPO 健康，体脂率等由 OPPO 自己算。

## 注意

- 不写死 MAC，按广播特征识别，换秤不需要改代码。
- 成员、档案、排序不归模块管，由 OPPO 健康自己处理。
- 小米秤静置时不广播，要先踩一下唤醒。

## 安装

1. 下载 `OHealthBridge-mi-scale1-v0.8.5.apk` 安装。
2. LSPosed 里勾选模块，作用域选 OPPO 健康。
3. 强制停止 OPPO 健康，再重新打开。

## 使用

1. 添加设备 → 体脂秤 → 小米体脂秤（一代）→ 开始配对。
2. 轻踩秤唤醒，等它出现在列表，点它绑定。
3. 之后上秤即可，数据落入 OPPO 健康。

> v0.8.5 的签名证书是 `CN=Mi Scale Bridge Test`，和更早版本不同，要先卸载旧版再装。

## 构建

JDK 21 + Gradle 9.2.1 + Android SDK（platform-36 / build-tools 36.0.0）：

```bash
gradle assembleRelease --no-daemon
zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
apksigner sign --ks <your.keystore> --out OHealthBridge.apk aligned.apk
```

源码包不含签名密钥，`local.properties` 按本机 SDK 路径自己建。

## 免责声明

模块会修改 OPPO 健康的运行时行为，可能违反其使用条款，升级后可能失效。源码与 APK 不含真实姓名、账号、生日、身高、体重、MAC 或 Token。仅供学习交流，风险自负。

上游基于 [OHealthDeviceBridge](https://github.com/BruceZhang1993/OHealthDeviceBridge)（MIT），详见 [LICENSE](LICENSE)。
