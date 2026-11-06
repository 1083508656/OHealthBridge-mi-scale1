# 小米体脂秤 · OPPO 健康桥接（LSPosed 模块）

把小米体脂秤 / 小米体重秤接入 OPPO 健康：**配对一次，之后踩秤自动记录，并自动出现在「设备」列表里**。

OPPO 健康原生只认自家和薄荷系的秤。小米秤称重后虽然会广播数据，但 OPPO 健康收不到，也不会把它当成自己的设备。本模块通过运行时 Hook 把小米秤的广播接进 OPPO 健康的体成分管线，让它像原生秤一样工作。

> 非官方项目，与 OPPO / 小米 / 薄荷健康均无关联。需要 root 与 LSPosed，请自行评估风险。

## 功能

| 功能 | 说明 |
| --- | --- |
| 一次配对，长期生效 | 在「添加设备 → 体脂秤」里配对一次，之后不用再配对 |
| 通用扫描，不绑定 MAC | 按小米秤广播特征（Service UUID / 名称 / 可解析数据帧）识别，不写死任何 MAC。换秤、换手机、给别人用都不需要改代码 |
| 踩秤自动记录 | 上秤站稳后自动写入体重与阻抗，由 OPPO 健康计算体脂率、肌肉率、BMI 等体成分 |
| 自动进入设备列表 | 配对后加入 OPPO 健康的「设备」列表，下次称重自动识别 |
| 按家庭成员归档 | 按参考体重匹配成员，体成分计算使用该成员自己的性别 / 身高 / 生日档案 |
| 多型号支持 | 小米体脂秤一代 / 二代、小米体重秤、薄荷健康 B1（AFU）等 |

## 适配环境

- 已在 **OPPO 健康 6.10.13** 上真机验证（K90U / KernelSU + LSPosed Zygisk）。
- Android 8.0 及以上，需已启用 LSPosed / Xposed 框架，作用域勾选 **OPPO 健康**。
- 其他 OPPO 健康版本因混淆差异可能不兼容，升级 App 后可能需要等待适配。

## 安装与使用

1. 从 [Releases](../../releases) 下载 `OHealthBridge-mi-scale1-v0.8.5.apk` 并安装。
2. 在 LSPosed 中勾选模块（作用域：OPPO 健康），**强制停止 OPPO 健康后重新打开**。
3. 「添加设备 → 体脂秤 → 小米体脂秤（一代）」→ 开始配对。
4. **轻踩一下秤唤醒**：小米秤只在称重 / 唤醒后广播，静置时不发 BLE 广播。
5. 之后正常上秤，数据会自动落入 OPPO 健康。

> **签名提示**：v0.8.5 使用 `CN=Mi Scale Bridge Test` 证书，与更早版本证书不同，无法直接覆盖安装，需先卸载旧版模块。

## 仓库结构

```
app/src/main/java/.../hook/oppo/   OPPO 健康运行时 Hook（入口、目录、扫描、绑定、记录）
app/src/main/java/.../device/     可插拔秤驱动与 BLE 协议实现
app/src/test/                    单元测试
docs/                            开发说明与上游 README 存档
SOURCE_GUIDE.md                   源码导航（运行时链路、遗留代码说明）
FORUM_POST.zh-CN.md              功能介绍 / 论坛发布文案
```

`SOURCE_GUIDE.md` 会告诉你哪些文件真正在运行时生效、哪些是针对旧版 OPPO 健康的留档代码。

## 构建

JDK 21 + Gradle 9.2.1 + Android SDK platform-36 / build-tools 36.0.0：

```bash
gradle assembleRelease --no-daemon
zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
apksigner sign --ks <your.keystore> --out OHealthBridge.apk aligned.apk
```

模块元数据在 `app/src/main/resources/META-INF/xposed/`，经典 Xposed 入口在 `app/src/main/assets/xposed_init`。源码包不含签名密钥，`local.properties` 请按本机 SDK 路径自行创建。

## 隐私

源码与 APK 不含真实姓名、账号 ID、生日、身高、体重记录、MAC 地址、设备序列号或 Token；不含一次性数据导入、成员修正或数据库修复逻辑。成员档案、成员排序、成员管理全部由 OPPO 健康自己的数据库负责，模块**只读不改**。日志只打印脱敏后的 MAC 与数量，不打印健康数值明细。

## 免责声明

本项目通过 Hook 修改第三方应用的运行时行为，可能违反相关应用的使用条款；升级 OPPO 健康后可能失效。仅供学习与技术交流，使用风险自负。

## 来源与许可

上游基于 [OHealthDeviceBridge](https://github.com/BruceZhang1993/OHealthDeviceBridge)（MIT），本项目针对 OPPO 健康 6.10.13 的记录层与设备配对链路做了重写。详见 [LICENSE](LICENSE)。
