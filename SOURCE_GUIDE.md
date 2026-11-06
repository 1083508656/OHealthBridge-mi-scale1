# 源码导航 — OHealthBridge mi-scale1 v0.8.1

对应交付物：`OHealthBridge-mi-scale1-v0.8.1.apk`（versionCode 9 / `0.8.1-mi1`）
目标环境：OPPO 健康 `6.10.13_16d7513_261030`，Android 17 (SDK 37)，LSPosed 运行时 Hook。
完整背景与验证记录见工作区的《小米体脂秤接入OPPO健康-项目成果报告.md》。

## 一、运行时真正生效的链路

```
META-INF/xposed/java_init.list  →  HookEntry (libxposed API 101)
assets/xposed_init              →  LegacyEntry (经典 Xposed，两条入口并存)
        ↓ 二者都调用
RecordInjector.install(classLoader)
        ↓ Hook Application.attach / onCreate，捕获 Context
        ├─ DbBridge.install        数据库导出（门控：/sdcard/Download/oh_export.flag）
        ├─ CatalogHook.install     产品目录注入（Hook BaseResponse.getBody）
        ├─ ScanHook.install        配对识别 + 厂商路由 + 扫描/绑定接管  ★核心
        ├─ BindProbe.install       诊断日志（钩 boohee.h 的方法）
        └─ :SportDaemonService 进程
             ├─ XiaomiAutoRecord.start   后台测量循环
             │    └─ OppoRecordWriter.write  →  h.m → i0.d → q0 → BodyFatStore.b
             └─ MemberFix.run（仅当 oh_fix_members.flag 存在，一次性维护）
```

## 二、本次（v0.8.x）新增或重写的文件

| 文件 | 作用 |
| --- | --- |
| `hook/oppo/ScanHook.kt` | **核心**。识别注入的产品、强制 vendor=BOOHEE、接管 `boohee/y.c` 与 `je/t.c` 搜索、`y.d`/`je/t.d` 绑定、抑制原生超时 |
| `hook/oppo/ScaleCatalog.kt` | 通用型号表，内置 3 个小米型号，支持外部 `oh_scales.json` 扩展；一处改动驱动目录/扫描/绑定/测量 |
| `store/ScalePrefs.kt` | 持久化**扫描学到**的 MAC（SharedPreferences + `files/oh_scale.txt` 跨进程），无硬编码 |
| `device/xiaomi/XiaomiBleScan.kt` | BLE setup 自动切主线程，`stop()` 线程安全 |
| `device/xiaomi/MiScaleDriver.kt` | 只认 0x181B/0x181D/0xFE95 广播、MI_SCALE 名称或可解析载荷；新增 `scanMac()` |
| `hook/oppo/CatalogHook.kt` | 按 ScaleCatalog 注入多个产品 |
| `hook/oppo/XiaomiAutoRecord.kt` | 去掉硬编码 MAC 与无效的设备表写入，按已绑定 MAC 定向监听 |
| `BridgeLog.kt` | 日志镜像到 `files/oh_bridge.log`，无 USB 也能查 |
| `hook/oppo/DbBridge.kt` 内的 `ContextHolder` | `applicationContext ?: c` 兜底，修复 attach 阶段 Context 为 null |
| `HookEntry.kt` / `LegacyEntry.kt` | 两个入口统一只装 `RecordInjector` |
| `hook/oppo/RecordInjector.kt` | 增加 `Application.onCreate` 二次设置 Context、安装幂等保护 |

## 三、保留但**默认不执行**的代码

- `OppoHookInstaller.kt` 及其引用的 `BooheeSearchHook` / `BooheeBindHook` / `BooheeMeasureHook` / `BooheeKeepAliveHook` / `BooheeDeviceOverlayHook` / `ScaleRoutingHook` / `ProductCatalogHook` / `OppoAfu*` / `OppoObjectFactory` / `OppoReflect`：针对 **6.9.37** 的挂钩点，其目标类在 6.10.13 已被 R8 全量改名，运行时不会被调用（两个入口已不再安装它们）。保留仅为兼容旧版本与留档。
- `ZeppImporter.kt` / `FamilyImporter.kt` / `DeviceInjector.kt`：一次性数据导入与「路线 A 设备表注入」代码（路线 A 已证伪）。保留作历史记录，**不在运行时启动**。
- `MemberFix.kt`：仅在 `/sdcard/Download/oh_fix_members.flag` 存在时执行一次。
- `device/afu/*`：上游 AFU B1 秤驱动，`DeviceRegistry` 初始化时会创建实例但本项目不使用。
- `BridgeConstants.TARGET_VERSION` 仍是 `6.9.37_a7d98c1_260920`，**只用于日志输出**，不参与任何判断，可忽略。

## 四、构建

```bash
cd <工程根目录>
JAVA_HOME=<JDK21 路径> <gradle-9.2.1>/bin/gradle assembleRelease --no-daemon

BT=<android-sdk>/build-tools/36.0.0
$BT/zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
$BT/apksigner sign --ks miscale-signing-key.jks --ks-key-alias miscale \
  --ks-pass pass:miscale2026 --key-pass pass:miscale2026 --out <输出>.apk aligned.apk
```

依赖：`compileOnly` 的 Xposed API 82 与 libxposed API 101；Xposed 元数据在 `app/src/main/resources/META-INF/xposed/`，经典入口在 `app/src/main/assets/xposed_init`。仓库已切阿里云镜像（`settings.gradle.kts`）。

## 五、关键混淆类对照（6.10.13）

| 用途 | 类名 |
| --- | --- |
| 厂商型号判定 | `st/a.b(String)` |
| 绑定页 / ViewModel | `com.heytap.device.ui.weight.BodyFatScaleBindActivity` / `BodyFatScaleBindViewModel` |
| 搜索中枢 | `com.heytap.device.ui.weight.scale.i` |
| 两个厂商 Binder | `...scale.boohee.y`（BOOHEE）、`je.t`（LIFESENSE） |
| 设备模型 | `...scale.BindableScaleDevice`（构造参数顺序：vendor, mac, displayName, model, isConnectScale, rawRef） |
| 体成分 / 记录 | `boohee.h.m`、`boohee.i0.d`、`WeightScaleServiceImpl.q0`、`BodyFatStore.b` |
| 数据库 | `AppDatabase.G(Context)`（Hook after 拿已解密连接；SQLCipher） |

## 六、已知限制

- 「真机前台点配对 → 发现设备 → 绑定成功」尚未完整验证；已验证到 `SCAN BLE start` 且无立即失败。
- 后台测量用的用户档案仍是占位（30 岁 / 男 / 75 kg），体脂为近似值。
- 小米秤只在踩上去唤醒后广播，静置时不发 BLE 广播。
