# AFU-BH-TZ-B1 修复与验收

验收日期：2026-09-30。宿主基线：OPPO 健康 `6.9.37_a7d98c1_260920`，包名 `com.heytap.health`。

**报告 10 项已落实代码修复，本地验收通过。真机验收尚未执行：本机 ADB 设备列表为空，等待连接已启用 LSPosed 的手机和体脂秤。** 本地结果不代表云端接受未知型号、真实 GATT 通信或原生数据库保存已经成功。

## 逐项对应

| 报告项 | 修复 | 本地验证 | 真机验证 |
|---|---|---|---|
| 1 初始化 | 传 Context，检查初始化结果，空 userTag 也刷新资料，算法失败明确返回错误 | 首次初始化、初始化失败、算法失败测试通过；APK 方法声明匹配 | 首次 AFU-only 用户及同用户保存待验证 |
| 2 生命周期 | scanner 可取消；绑定/测量/后台 ticket 拒绝过期回调；退出页面、解绑、账户事件结束会话 | ticket 取消、账户切换、晚到帧、晚到 write callback 测试通过 | 取消扫描/绑定/测量和退出页面待验证 |
| 3 绑定兜底 | 按账户 hash 隔离 MAC；直接构造空列表 overlay，补齐 id/deviceType；按 MAC 去重；解绑移除 | 无模板对象构造、重复 MAC、跨账户隔离、新 context 恢复、解绑删除测试通过 | 云拒绝情况下重启恢复、解绑、换账户待验证 |
| 4 GATT 超时 | 5 秒单次操作超时，64 项队列上限，120 秒会话上限；关闭后停止队列并优先释放资源 | 回调缺失、错误 descriptor、队列超限、回调抛异常、开始失败、VM Error 传播测试通过 | 关闭蓝牙、断连、实际写入类型及 CCCD 待验证 |
| 5 性别 | 读取 BHUserGenderMale/Female 枚举 | 男女资料读取与现有 Profile 报文测试通过 | 女性用户实测待验证 |
| 6 扫描失败 | onError 传原 Throwable；遵循宿主 timeoutSec；结束时移除 timer/ticket | Throwable 原样反射回调与 ticket 清理测试通过 | 蓝牙关闭、权限拒绝和超时的 UI 反馈待验证 |
| 7 历史 | 老记录交给原生未认领 importer；按 timestamp/weight 去重；首次 idle 补发 8E；采用上游 fresh 容差；排空最多 15 秒 | 旧记录、重复记录、fresh 回放、无阻抗回退、超 5 秒历史排空测试通过 | 多条离线历史、认领、再次连接不重复保存待验证 |
| 8 状态迁移 | GATT 和计时统一主线程；握手前暂存 live/stored；握手只就绪一次，取消后拒绝帧 | 早到 locked 帧、重复 ready、握手前历史、静默等待、取消测试通过 | 快速上秤、重复会话、页面切换待验证 |
| 9 计算副作用 | synthetic raw device 的 isConnectScale=false，路由对象仍为连接型，避开 ICOMON 包装方法的 B3 transport 分支 | fixture 验证路由为连接型且计算/历史没有 transport 写；原 APK 的该分支已逆向核实 | ICOMON 实际数值、真实 Boohee 共存待验证 |
| 10 保活 | AFU 后台历史由自有 GATT 处理；在线状态查询真实 session；扫描/绑定/测量暂停原 SDK，后台请求不抢占前台 | 对应 Hook 的真实 DEX 声明匹配；相关状态/资源规则回归通过 | 前后台、重复保活请求、多秤共存和 UI 状态待验证 |

历史排空完成后后台会话结束，不建立无限期连接。原 OPPO 云绑定仍是异步请求；本地握手成功和云端保存成功分别判断。模块不直接生成或上传伪造测量数据。

旧 `afu_bound_macs` 无账户归属，不自动迁移。当前账户在云端已保存的 AFU 会恢复本地记录；此前仅本地绑定的设备需重新绑定一次。

## 已执行的本地验收

- 项目 Gradle 任务 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`：成功。
- JUnit：**30 项，0 failures，0 errors**，使用项目 Kotlin 2.2.21 与 JUnit 4.13.2。
- Lint：**0 errors，4 warnings**。4 条来自原有 Manifest 属性、图标及 libxposed API 更新提示；保持 API 101 以兼容现有 legacy Hook。
- 宿主 APK DEX 校验：**17 个目标类、56 个精确方法签名**，另检查产品渲染与账户回调入口。移除了仅在 Kotlin 元数据出现、实际 DEX 已被裁剪的 stopKeepAlive Hook。
- Debug APK 的 Modern Xposed 入口、API 101、静态 com.heytap.health scope：通过；无 legacy assets/xposed_init。
- 测试替身及宿主/Xposed API 类未打入模块 APK：通过。
- `git diff --check`：通过。未提交或推送。

测试替身复现已核实的 6.9.37 方法签名和字段类型，只验证模块适配与副作用隔离。测试不执行专有 ICOMON 数值算法、Android 蓝牙栈、LSPosed Hook 引擎或 OPPO 数据库。

构建采用 JDK 21、Gradle 9.5.0、AGP 8.13.1。首轮系统 Gradle 9.7.1 与 AGP 不兼容，改用已安装的兼容版本。AGP 对 compileSdk 37.0 有既有兼容性警告，未修改项目 SDK 版本或升级依赖。

每次编译、测试和 lint 都串行运行于 systemd user scope，MemoryMax=8G、MemorySwapMax=4G。本机 systemd 拒绝同时设置 --scope 与 --wait，使用同步等待的 --scope --collect。

## 产物与证据

- APK：[app-debug.apk](../app/build/outputs/apk/debug/app-debug.apk)，985826 bytes。
- APK SHA-256：`a8abda86df8293ab6d3e0e548286a465c6ab10f245372dd8001f5b4df98be79f`。
- 测试报告：[index.html](../app/build/reports/tests/testDebugUnitTest/index.html)。
- Lint 报告：[lint-results-debug.html](../app/build/reports/lint-results-debug.html)。
- 宿主签名校验：[verify_host_contract.py](../scripts/verify_host_contract.py)。
- 本机原始验收输出：`/home/bruce/Documents/Codex/2026-09-30/OHealthDeviceBridge-fix-acceptance-evidence/`。

## 真机验收步骤（全部待执行）

1. 记录手机 OS、OPPO 健康版本和 LSPosed 版本；安装模块，确认作用域与入口加载日志，重新启动 OPPO 健康。
2. 确认添加设备中出现 AFU；开关蓝牙、拒绝权限、扫描超时和离开页面都能正常结束，没有迟到设备回调。
3. 仅有 AFU 的用户完成绑定，分别记录本地握手结果和云请求结果；重启 App 后设备仍存在。
4. 用当前账号与女性家庭成员各称重一次，确认重量实时变化、下秤后锁定；通过原生确认页面保存。
5. 在 OPPO 原生记录中确认体重、时间、阻抗和体成分字段，不将 onLockWeight 日志当作保存成功。算法数值与 AFU 官方 App 的差异另行核对。
6. 测量中退出页面、取消绑定、关闭蓝牙；确认没有继续写入结果或残留会话，重新开始可正常使用。
7. 留存多条真实离线记录后重连，确认导入未认领历史、认领正确、再次连接不会重复保存。
8. 解绑后重启 App，设备不因本地 overlay 回来；换账户后旧账户的本地绑定不可见，切回可恢复未解绑记录。
9. 存在真实 Boohee 秤时验证互斥与计算隔离；AFU 操作不向其 GATT 发送 B3，退出后原设备功能正常。
10. 切换前后台、快速上秤和重复打开测量页面，检查连接状态、超时及历史排空；收集仅与该模块相关的脱敏结果。

手机连接后继续执行上述步骤，当前没有把任何一项真机结果标为通过。
