# 小米体脂秤接入与验收

## 使用

1. 安装模块并在 LSPosed 中只勾选 `com.heytap.health`；不需要给米家添加 Xposed 作用域。
2. Mi Scale v1/v2：在 OPPO 健康的体脂秤产品列表中选择对应小米型号，唤醒秤并搜索添加。
3. S400/S800：先在米家登录、选择设备所在地区并绑定秤，再在 OPPO 健康选择对应“从米家读取”产品。
4. 模块授权页点击“授权 root 并读取”，在 Magisk/KernelSU/SukiSU 中授权 **OHealth Device Bridge 模块**。不需要给 OPPO 健康 root。
5. 从列表选择秤，自动获取凭证后返回 OPPO 健康。站上秤发送广播，完成本地验证，再由 OPPO 原有流程保存云端绑定。

无需手动填写账号、Token 或 BLE Key。首次读取与重新取钥需要联网；后续测量会按需通过模块 root 服务核对本机米家账号和地区，使用加密保存的设备 Key。

## 能力边界

| 型号 | 传输 | 数据 |
| --- | --- | --- |
| Mi Scale v1 | BLE GATT | 体重、历史、时间同步 |
| Mi Scale v2 | BLE GATT | 体重、阻抗、历史、时间同步；身体成分交给 OPPO ICOMON |
| S400 (`yunmai.scales.ms103/ms104`) | 加密 MiBeacon | 体重、双频阻抗、心率；使用随体重包发送的 50 kHz 阻抗调用 ICOMON |
| S800 (`xiaomi.scales.ms116`) | 加密 MiBeacon | 体重；不读取分段身体成分 |

S400 的两包只在同一测量的短时间窗口内合并；按当前 xiaomi-ble 的 `obj6e16` 字段定义，随体重包发送的是 50 kHz 阻抗，另一包是 250 kHz 阻抗。第二包缺失时保留已收到的 50 kHz 值，250 kHz 值留空。资料对高低频命名存在差异，这一频率映射须在真实固件上核对。缺失指标不沿用上次结果。S400 Pro/S200 等型号不作为上述四款冒充支持。

所有身体成分均来自 OPPO 的计算流程；没有复制 OpenScale 的身体成分算法。一代、S800 无阻抗时不计算虚构体脂。宿主暂不支持同时展示双频阻抗；250 kHz 原始值保留在单次桥接记录中，50 kHz 值映射至宿主体阻字段。

## 隔离与资源边界

- root 服务只读取当前 Android 用户下米家固定的三个 SharedPreferences 文件；不会修改、强停或注入米家，不提供任意路径或命令执行功能。
- 米家 11.7.709 的缓存格式是适配基线。缺少必要字段或缓存不兼容会明确失败，不尝试其他用户的文件。
- 小米账号会话只在模块进程同步期间使用，不传给 OPPO、不写日志或磁盘。选中的 BLE Key 用模块自身 Android Keystore AES-GCM 加密。
- Provider 仅接受同一 Android 用户的 OPPO 健康 UID。模块授权页验证由 OPPO 创建的 PendingIntent；URI 授权用于解决宿主没有声明模块包可见性的问题，不能绕过 Provider UID 检查。
- 设备缓存按 OPPO 账号、MAC 隔离，并核对小米账号、地区和型号。发现小米账号/地区改变时撤销旧缓存；解绑删除对应凭证。
- 云请求串行，单次客户端最多 60 秒，每个网络操作最多 10 秒，响应最多 1 MiB。最多 300 个家庭、2000 个设备、每个家庭 10 页；游标重复或服务未返回继续游标会明确报列表不完整。
- root 服务按需绑定后解绑；无常驻 root 守护进程。BLE 会话最多 120 秒，绑定广播验证最多 30 秒，GATT 队列沿用每操作 5 秒、最多 64 项的限制。

## 真机验收（尚需执行）

- [ ] 仅勾选 OPPO 健康作用域，模块自身收到 root 请求；OPPO 和米家没有新增 root 授权。
- [ ] 新安装模块、首次读取、重启后重新读取：模块页面正常打开，Provider 可访问，无手动凭证输入。
- [ ] 米家中国区和一个海外区分别选中正确会话、家庭和体脂秤；设备共享无权限时有明确提示。
- [ ] 拒绝 root、未装米家、未登录、锁屏用户、缓存不兼容、会话过期、断网、取消读取均可恢复重试。
- [ ] S400/S800 只有匹配 MAC、产品 ID、通过 AES-CCM 认证的广播才通过验证；错误 Key、重绑后旧 Key 均失败并提示重新读取。
- [ ] S400 双包阻抗对应频率与实际固件一致，心率能进入宿主模型；缺第二包、穿袜子、连续两次测量不串指标。
- [ ] S800 只有体重，不出现沿用的体脂、心率或分段指标。
- [ ] 一代、二代 GATT 时间同步、历史导入、通知订阅与真实固件匹配；反复进入页面不重复导入已处理历史。
- [ ] 宿主后台 keep-alive 可触发一代、二代历史导入，传输确认完成后断开；前台扫描、绑定和测量不被后台会话抢占。S400/S800 返回当前广播监听状态，不建立 GATT 历史会话。
- [ ] OPPO 账号切换、米家账号或地区切换、测量退出、解绑后不会接收旧会话回调或泄漏旧凭证。
- [ ] 现有 AFU 扫描、绑定、实时测量、独立历史会话与云端重试正常。
- [ ] 云绑定回包 `errorCode == 0`，再从原始云列表核对 MAC、对应桥接型号和 `deviceType=100`。本地验证成功不等于云端接受绑定。

本地单元测试和宿主 DEX 契约检查不能证明真实米家会话复用、BLE 通信或 OPPO 云端接受新增型号；这三项必须按上面的清单实测。

## 协议参考与许可

项目继续使用 MIT。协议字段、加密格式与交互行为独立实现，未移植 OpenScale GPL 源码或算法。

- OpenScale 支持表及协议行为：https://github.com/oliexdev/openScale/wiki/Supported-scales-in-openScale
- MIT 云接口参考：https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor
- MiBeacon 公开协议实现：https://github.com/Bluetooth-Devices/xiaomi-ble
- root IPC 依赖 libsu 6.0.0（Apache-2.0）：https://github.com/topjohnwu/libsu
- AES-CCM 依赖 Bouncy Castle 1.83（MIT）：https://www.bouncycastle.org/about/license/
