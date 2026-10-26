# 踩过的坑

按"现象 → 原因 → 结论"记录。**重开发前请先读完这一页**，里面每一条都是用真实设备
日志换来的；照直觉改回去会立刻复现。

---

## 1. 系统音量条会莫名从屏幕侧边弹出来

**现象**：投屏中点弹窗外部让它消失、或在通知栏点停止之后，手机侧边弹出系统音量条；
而且那个自动"恢复音量"本身还不生效（声音没回来，条却弹了）。

**原因**：这两条路径上的恢复写入**背后没有触摸**，被 ROM 拒绝。旧代码为了"强行写进去"
用 `AudioManager.FLAG_SHOW_UI` 重试了一次——这个 flag 的语义就是"把音量面板显示给用户看"，
于是条被顶出来，而写入照样被拒。

**结论**：
- 删掉那些"无触摸自动恢复"路径（弹窗外部消失、通知栏停止、任意触摸、回到前台）。
- **全项目不再使用 `FLAG_SHOW_UI`**（`writeMute` / `replayUnmuteTap` 里的兜底分支已移除）。
- 恢复音量只发生在两帧：授权弹窗返回、点 Stop casting。遗留静音交给 `Unmute phone` 按钮。

> 判定：任何操作后侧边都不应出现音量条。若又出现，先全局搜 `FLAG_SHOW_UI` 和"无触摸的
> `setStreamVolume` 调用"。

---

## 2. ROM 只接受"有真实触摸"的音量写入（核心）

**现象**：从服务里、或从"不是用户正在操作"的 Activity 写静音，全部被拒。

**设备日志证据**（`via=plain` 表示普通写入）：
- 一次自动重放"手动两连击"，16 次尝试横跨 32 s，每次都读回旧音量、流静音标志仍为空。
- 有一个已经持有焦点 5 s 的窗口，连续 11 次被拒 → **"应用在前台"既不充分也不必要**。
- 有触摸时：commit tap 3/3 成功；点静音按钮永远成功；用户切回本应用后的重申一次成功。
- 授权弹窗返回（无触摸）：某版本 2/3 成功，另一版本 0/4 → **不可依赖**。

**结论**：
- 静音**在点 Start casting 的那次触摸上做**（`commitToCast` → `armCastMute` + `mutePhoneNow`），
  授权弹窗返回时**重申**，窗口重获焦点时**再重申**，服务内的定时重试仅作为最后兜底。
- 不要把"没有触摸"的操作当成可靠的写入口。

---

## 3. "想要静音" 与 "实际静音" 必须分开记录

**现象**：按钮显示 "Unmute phone"，但手机其实还在响；或者下一次投屏的重试链刚起跑就
"以为自己已经静音"而立刻退出。

**原因**：把"我请求了静音"当成"已经静音"。

**结论**：
- `muteWanted`（用户意图）与 `phoneMuted`（**从 `AudioManager` 读回派生**）分开。
- `phoneMuted` 在每次会话开始时从流上重新派生。上一会话的撤销被拒会留下 `true`，
  陈旧的 `true` 会立刻终止下一会话的重试链。

---

## 4. `savedVolume` 被过早清空 → 撤销时无音量可恢复

**现象**：手机因上次撤销失败仍静音，用户再点 Start casting，之后撤销时音量回不来。

**原因**：`armCastMute()` 原实现无条件 `savedVolume = -1`；此时流本来就读到 0，
重新记录什么也记不到，旧会话记录的音量被抹掉。

**结论**：只在"手机当前真的有声音"时才重置记录：

```java
if (readVolume(audioManager()) > 0) savedVolume = -1;
```

另外 `savedVolume` 初始为 `-1`，撤销时若 `<= 0` 只解除 mute 标志，
**绝不虚构一个手机从来没有过的音量**。

---

## 5. 定时兜底会在授权弹窗还开着时就恢复音量（声音会"响一下"）

**现象**：弹窗里犹豫久了，声音突然回来一下，点"继续"后又静音。

**原因**：旧实现有一个 pending 定时器（8 s）到期直接调 `rollbackLeftoverCastMute()`。

**结论**：这类"到点就恢复"的兜底全部删掉。任何自动恢复都必须挂在**用户刚触摸过本应用
的那一帧**。（历史上曾把它缩到 1 s 并只"解锁不恢复"，最终整条 pending 机制连同
`castStartPending` / `isCastStartPending` / `rollbackLeftoverCastMute` 一起删除。）

---

## 6. `onUserInteraction` 兜底：随便点一下就出声

**现象**：投屏中在界面上随便点一下，声音回来了。

**原因**：曾用 `onUserInteraction()` 作为"用户回来了"的恢复信号，但它对**任何**触摸都触发。

**结论**：已删除。恢复不能由"任意触摸"驱动，只能由明确的意图（点 Stop / 拒绝授权）驱动。

---

## 7. 声音捕获是**按用途（usage）**过滤的，不是按应用

**现象**：系统授权弹窗里选了"QQ 音乐"，结果用网易云音乐播放的声音也被转发到 HomePod。

**原因**（两层）：
1. 本项目**根本不录屏**——全仓没有 `createVirtualDisplay`，`MediaProjection` 只被当作
   `AudioPlaybackCapture` 的授权令牌。系统那个"单个应用 / 整个屏幕"的选择约束的是**画面**。
2. 音频过滤写的是 `addMatchingUsage(USAGE_MEDIA / USAGE_UNKNOWN / USAGE_GAME)`，
   **没有 `addMatchingUid`**。谁被捕获取决于"这段音频把自己声明成什么用途"。

**结论**：
- 当前行为是**预期行为**：任何声明 media/game/unknown 用途的应用都会被一起转发。
- 想做"只转发我选的那个应用"，必须改成 `addMatchingUid(uid)` 并自己在应用里做选择器
  —— 见 [ROADMAP.md](ROADMAP.md) P1。
- 注意：系统那个选择框**不会**把它选中的应用告诉本应用，没有对应 API，只能自己选。

---

## 8. 延迟改了要"下次投屏"才生效

**现象**：投屏中拖动"Audio latency"，当下听不出变化。

**原因**：这个值只在会话开始时固化一次，且固化进两处结构性位置：

| 用在哪 | 位置 | 会话中能否改 |
|---|---|---|
| 握手：AP2 `SETUP` plist 的 `latencyMax` / `latencyMin`（接收器在这个窗口里选抖动缓冲） | `raop_sender.cpp` 的 `SETUP` 构造 | SETUP 只发一次，不重谈 |
| 时间轴锚点：RTP 时间戳 = `latency_ + framesSent_`，同步包带 `now - latency_` / `now` | `rtptime32_()`、sync 包 | `latency_` 只在 start 时赋值 |
| App 侧环形缓冲水位阈值（预连接排空、live 时按 1.6× 削 10 ms） | `native_sender.cpp` 的 `pumpThread` | 变量同样只在 start 时写入 |

**结论**：`CastService` 在启动时把 `Settings.getLatencyMs()` 传给 `nativeStart`，native 在
握手前 `setLatency()`。**没有**运行中下发的接口，所以只能下次生效——设置页也照实写了
"Applies to the next cast"。若要做成实时生效，需要重新握手（代价大），不要贸然加。

---

## 9. 通知栏停止后手机仍然静音（这是设计，不是 bug）

**现象**：通知栏点停止，手机会一直静音。

**原因**：见 #1/#2——通知栏停止没有触摸，写不进去。

**结论**：保留该行为。恢复入口是界面上的 `Unmute phone` 按钮（`togglePhoneMute()` 在没有
投屏、甚至服务已销毁时也能工作，见 #10）。

---

## 10. 服务销毁后静音按钮曾是"哑"的

**现象**：投屏结束（服务已销毁）后点 Mute/Unmute 没反应，恰好这时最需要它。

**原因**：实现绑在服务实例上。

**结论**：`togglePhoneMute()` 用 `App.get()` 拿 `AudioManager`，**不依赖服务实例**；
并按**实际生效状态**（`phoneMuted`）取反，而不是按用户意图，否则自动静音被拒后按钮会去
"取消一个从未生效的静音"。

---

## 11. 构建环境：NDK / CMake / 子模块三件套

**现象**：换机器后 `./gradlew` 直接失败；或 CMake 报 "airplay2-sender-cpp not found"。

**原因**：需要 **NDK 27.2.12479018 + CMake 3.22.1**（版本写死在
[app/build.gradle.kts](../app/build.gradle.kts)），且 `third_party/mbedtls` 是 **git 子模块**，
默认 clone 不带内容。

**结论**：
```bash
git clone --recurse-submodules <url>
# 或
git submodule update --init --recursive
```
详见 [BUILD.md](BUILD.md)。

---

## 12. 验证 APK 内容时"只扫 classes.dex"会得到假阴性

**现象**：想确认改动有没有进 APK，`unzip -p app.apk classes.dex | grep 某方法名` 命中 0，
连对照组也是 0。

**原因**：应用类被分到了 **`classes3.dex`**（multidex）。`classes.dex` 里是别的东西。

**结论**：扫描要遍历**所有** `classes*.dex`；并且优先用 Python 直接读 zip 里的字节
（`zipfile`），不要依赖管道里的 `strings`（在二进制流上容易静默失败）。示例见
[BUILD.md](BUILD.md) 的"产物校验"。

---

## 13. 分发时 versionCode 一定要递增

**现象**：安装时提示"已安装的 1.0.11 与要安装的是同一个版本"，装上后行为还是旧的。

**原因**：分发出去的文件用了与旧版相同/被覆盖过的内容，`versionCode` 没变（21 vs 21）。

**结论**：每次对外发版都递增 `versionCode`，并**用新文件名**发布；
发布前用 `aapt2 dump badging` 核对 `versionCode/versionName`，别相信文件名。

---

## 14. 通知 / 会话：MediaStyle 与重复条目

- `Notification.MediaStyle` + 空标题的动作 → 渲染成**纯图标**按钮（否则会漏出 `mut`/`unm`
  这种被截断的文字）。`setShowActionsInCompactView(0,1,2,3)` 让 4 个动作在折叠态就可见。
- `MediaSession` / `VolumeProvider` 让系统音量面板和锁屏出现 "AirCast" 这一列。
  **重建前必须 `session.release()`**，否则第二次开始投屏会在音量面板里留下两个 "AirCast"。
- 重复 start 要有哨兵：`if (instance == this) return;` 否则会双份采集。

---

## 15. mDNS 在 API 34+ 要用新回调并且必须反注册

`NsdManager.resolveService()` 在 API 34 起走 `registerServiceInfoCallback()`，
**每个注册都必须 `unregisterServiceInfoCallback()`**，否则 NSD 会一直持有回调与其 socket
流量（即使 Activity 已经销毁）。

---

## 16. Android 15+ 的两个默认行为

- **16 KB 页**：native 库要 `-Wl,-z,max-page-size=16384`，否则 Android 15+ 上加载失败。
- **强制 edge-to-edge**：界面要用 inset 让开状态栏/导航栏/挖孔（本项目在
  `Ui.applyInsets()` 统一处理，横屏还要让开侧边导航栏与摄像头挖孔）。

---

## 17. 其它零碎

- **采集速率可能被改**：设备可能只给 48 kHz，`AudioRecord` 会静默改；必须读回
  `record.getSampleRate()` 再告诉 native，否则发送端节奏错。构造失败要先降级到 48 kHz 重试。
- **环形缓冲够大**：`1<<19` 采样（~5.9 s @44.1k 立体声），5 s 延迟上限的 8 s 阈值也放得下。
- **前台服务类型**：`mediaProjection`；服务不得活得比 projection 长，`onStop()` 里 `stopSelf()`。
- **隐私文案与实际一致**：抓音频、只在局域网转发、不录音不存储，启动弹窗与 `PRIVACY.md` 都这么写。
- **第一次投屏前不要动音量**：`setRemoteVolume` 即使无会话也要持久化，否则投屏开始时音量跳回旧值。