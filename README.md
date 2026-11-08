# OHealth Device Bridge

让 OPPO 健康连接更多第三方体脂秤的 Xposed 模块。装上之后，你可以直接在 OPPO 健康里配对并使用小米体脂秤：站上秤自动测量、自动记录，体脂率、肌肉率、水分、骨量、内脏脂肪、基础代谢等身体成分数据一并同步到 OPPO 健康。

## 功能

- 在 OPPO 健康的「添加设备 → 体脂秤」中显示并配对支持的设备。
- 站上秤即可开始测量，实时显示体重；测量完成后自动保存，并生成身体成分报告（体脂率、肌肉率、水分、骨量、内脏脂肪、基础代谢等）。
- 在任意页面站上秤也会自动称重并弹出测量页，无需手动进入测量页。
- 测量结果自动归入对应成员，无需手动认领。
- 读取秤内保存的历史测量记录。

## 支持的设备

| 设备 | 型号 | 支持状态 | 测试状态 |
| --- | --- | --- | --- |
| 小米体脂秤（一代） | XIAOMI-MI-SCALE-V1-BODY | 支持 | 已实测 |
| 小米体重秤（一代） | XIAOMI-MI-SCALE-V1 | 支持 | 未实测 |
| 小米体脂秤（二代） | XIAOMI-MI-SCALE-V2 | 支持 | 未实测 |
| 薄荷健康 B1 智能体脂秤（蚂蚁阿福联名款） | AFU-BH-TZ-B1 | 支持 | 已实测 |
| 京东健康 LEN-1001 体脂秤 | JD-LEN-1001 | 实验性支持 | 未实测 |
| 小米体脂秤 S400 | XIAOMI-S400（米家：yunmai.scales.ms103 / ms104） | 实验性支持 | 未实测 |
| 小米体脂秤 S800 | XIAOMI-S800（米家：xiaomi.scales.ms116） | 实验性支持 | 未实测 |

目前仅适配上述型号。其中小米体脂秤（一代）与薄荷健康 B1 已经过真机实测；其余型号按同一套流程适配，但尚未逐台真机验证，欢迎反馈。LEN-1001 采用纯广播数据（无需蓝牙连接），其广播协议已解析，模块侧接入尚待真机验证。S400 / S800 的自动取钥及广播测量属于实验性支持，需先在米家登录并绑定设备。

## OPPO 健康支持版本

| 应用 | 版本 | 测试状态 |
| --- | --- | --- |
| OPPO 健康 | 6.10.13 / 6.10.14 / 6.10.15 | 已实测 |
| OPPO 健康 | 6.9.37 | 已适配 |

## 使用条件

- Android 8.0 或更高版本。
- 已安装并启用支持本模块的 LSPosed / Xposed 框架，作用应用为 OPPO 健康。
- 上述版本的 OPPO 健康，并允许其使用蓝牙及相关权限。
- 一台支持列表中的设备。

## 安装与使用

1. 从 [Releases](https://github.com/Xposed-Modules-Repo/io.github.brucezhang1993.ohealthdevicebridge/releases) 下载并安装 APK。
2. 在 LSPosed / Xposed 管理器中启用 **OHealth Device Bridge**，作用应用勾选 **OPPO 健康**。
3. 完全退出并重新打开 OPPO 健康。
4. 打开「添加设备 → 体脂秤」，选择对应设备，唤醒体脂秤并按提示完成配对。S400 / S800 需选择对应的“从米家读取”产品，在模块授权页授权后返回 OPPO 健康完成验证。
5. 站上秤即可测量；也可进入设备的测量页面手动称重。

## 问题反馈

请在 [Issues](https://github.com/Xposed-Modules-Repo/io.github.brucezhang1993.ohealthdevicebridge/issues) 中提供：设备型号、手机型号、Android 版本、OPPO 健康完整版本、模块版本，以及问题出现的步骤。请勿提交账号、个人健康记录等隐私信息。

## 开源许可

[MIT](LICENSE) · [查看源码](https://github.com/BruceZhang1993/OHealthDeviceBridge)
