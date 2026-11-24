# Car+ 车联画面源（OPPO）

更新：2026-11-23。把原来的小米 CarWith 画面源换成 OPPO「Car+ 车联」。

## 为什么只换“拉起谁”

HyperCar 的 CarLife 模式不是“把对方界面截下来”，而是**本机七通道桥**：
[CarWith 源](CARWITH-SOURCE.md) 里的 CMD 7240 / VIDEO 8240 / MEDIA 9240 / TTS 9241 /
VR 9242 / CTRL 9340 / DATA 9440 由来源 App 自己建立，Rust 引擎以**车机身份**主动拨号接管，
再转成 AirPlay 送真车机。

Car+ 车联与 CarWith 同属 CarLife 手机端，通道、认证、加密、视频分片、触摸和音频路径
完全一致。因此 `carlife_source`、`nativePrepareCarWith`、`nativeStartCarWith` 与 Rust 侧
不需要任何改动，唯一变化是**启动哪个包的哪个 Activity**。

- CarWith 有公开入口：`com.miui.carlink/com.miui.carlink.castfwk.usb.CarlifeAdbActivity`
- Car+ 车联没有公开入口，只能按包名 + Activity 关键字探测

## 入口探测

`CarPlusSource`（`app/src/main/java/io/github/hypercar/session/CarPlusSource.kt`）：

0. **拉起时机**：本轮开始时只准备七通道守候（`nativePrepareCarWith`），源端 App 的
   `am start` 推迟到引擎上报 `connected`（CONTROL_READY，即车机 AirPlay 真连上）之后，
   避免车机没连上时手机先把 CarLife 画面弹到前台。守候服务每 ~5s 重试 7240，
   App 晚到不影响接管；引擎在这段时间请求“重开源端”会被忽略（`CARWITH-START: … CarPlay 尚未连上`）。

1. 包名候选按顺序取第一个「已安装且启用」的：
   `com.oplus.ocar`（现行包名）、`com.oplus.carplus`、`com.coloros.carplus`、
   `com.oplus.carlink`、`com.coloros.carlink`
2. 读该包全部 Activity，按类名打分：
   `carlife` +100、`adb|debug|wired|usb|projection|session` +40、`cast|screen|link|connect` +15、
   `CarlifeActivity` 再加 30、导出 +20；`guide|download|about|help|settings|…` −60，
   `launcher|MainActivity|settings` −25
3. **不在只启动一个**：未指定入口时按分数顺序逐个试（最多 5 个，每个等 3 秒），
   用 `/proc/net/tcp` 里 7240 是否 `LISTEN`（0A）判定命中。命中就写回
   `carplus_entry_component`，以后直接启动该组件。
   注意这里**不**用 socket 去连 7240，一次真实连接会被 CarLife 消耗掉。
4. 启动用 Shizuku `am start -n 包名/Activity`，失败再走普通 `startActivity`
5. 每个候选、`am start` 退出码、7240 监听状态、以及 `pm list packages` 的
   CarLife 相关包快照都写入诊断日志

日志格式：

```
CARPLUS: pkg=com.oplus.ocar version=…/… activities=N [XxxActivity(exp=true,s=120),…]
CARPLUS: packages=com.oplus.ocar,com.baidu.carlife.…
CARPLUS: 未指定入口，开始候选扫描（5 个，各等 3s 看 7240）
CARPLUS-SWEEP: try=1/5 com.oplus.ocar/.xxx.CarlifeActivity listen=false am start exit=0
CARPLUS-SWEEP: 命中并记住入口 com.oplus.ocar/.xxx.XxxActivity
```

2026-11-23 实测（0.1.0.610，Car+ 车联 17.32.3）：`com.oplus.ocar` 有 134 个 Activity，
其中 `CarlifeAdbWiredConnectGuideActivity` 只是“有线连接引导页”，启动退出码 0 但 7240 不监听——
所以“am start 成功”不能当入口正确，必须看 7240 是否 `LISTEN`。

## 用日志判断卡在哪一步

| 现象 | 含义 |
|---|---|
| `activities=N` 为 0 或没有带分候选 | 该版本没有本机 CarLife 调试入口，拉起的是普通首页 |
| `am start exit=0` 但 7240 一直 `ECONNREFUSED` | 入口能启动但没进 CarLife 会话，需要换 Activity |
| 7240 `LISTEN@` 出现、随后七通道逐个 ESTABLISHED | 入口正确，桥已接管 |
| 七路通了但 VIDEO 无 AU | 回到 [CarWith 源](CARWITH-SOURCE.md) 的视频/触摸排查 |

## 覆盖入口

自动探测只保证“能启动一个导出 Activity”，不保证它是本机 CarLife 调试入口。
真机上确认到正确 Activity 后，写入覆盖值（Advanced 设置或直接改偏好）：

```
carplus_entry_component = com.oplus.ocar/.carlife.CarLifeDebugActivity
```

`SettingsStore.carPlusComponent` 读取该键，格式必须是 `包名/Activity`，以 `.` 开头的类名
会自动补全包名。清空即回到自动探测。

## 当前状态

- 协议层复用、设置项、来源选择 UI、包可见性 `<queries>`、诊断日志均已实现。
- **入口 Activity 尚未在真机确认**：Car+ 车联各版本是否保留本机 CarLife 调试入口未知，
  需要一轮真机日志（`CARPLUS:` 行 + 7240 监听表）才能定稿。
- 不能把“am start 退出码 0”当成源端就绪；与 CarWith 一样，以七通道握手和视频 AU 为准。
