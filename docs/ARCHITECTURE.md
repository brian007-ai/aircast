# 架构

## 1. 总览

```
┌────────────────────────── App（Java）  ──────────────────────────┐
│ MainActivity          主界面：选设备、开始/停止、扬声器音量、静音按钮  │
│ SettingsActivity      设置：延迟、音量步长、日志                    │
│ AirplayDiscovery      mDNS(_airplay._tcp) 发现接收器               │
│ CastService           前台服务(mediaProjection)：采集 + native 会话  │
│ Settings/App/CastLog     偏好、Application、日志环形缓冲            │
│ Ui + Glass* / AuroraView 设计系统、玻璃材质、开关/滑块/弹窗、背景    │
└───────────────────────────────┬──────────────────────────────────┘
                                │ JNI（4 个方法）
┌───────────────────────────────▼──────────────────────────────────┐
│ native_sender.cpp   环形缓冲 + pump 线程 + 延迟管理 + JNI 桥        │
├──────────────────────────────────────────────────────────────────┤
│ airplay2-sender-cpp（vendored）  AirPlay 2 握手 / ALAC / RTP / 密钥 │
├──────────────────────────────────────────────────────────────────┤
│ third_party/mbedtls（git 子模块）  加密与 TLS 支撑                  │
└──────────────────────────────────────────────────────────────────┘
```

## 2. 数据流

```
手机上正在播放的音频
  │  ① AudioPlaybackCapture（按 usage 过滤，见 PITFALLS #7）
  ▼
AudioRecord（16-bit PCM / 立体声 / 设备实际速率）
  │  ② CastService 的 "capture" 线程：record.read() → nativeWrite(short[])
  ▼
RingBuffer<int16_t>（1<<19 采样 ≈ 5.9 s @44.1k 立体声）
  │  ③ native "pump" 线程：水位管理 + 按 latency 削峰
  ▼
RaopSender：ALAC 编码 → RTP 打包 → TCP/UDP 发给接收器
  │
  ▼
HomePod / Sonos / Apple TV …
```

## 3. 线程模型

| 线程 | 谁创建 | 干什么 | 注意 |
|---|---|---|---|
| 主线程 | Android | UI、`CastService` 的静态静音状态、Handler 重试链、`MediaSession`/`VolumeProvider` | 静音相关回调都跑在主线程，避免竞态 |
| `capture` 线程 | [CastService.onStartCommand](../app/src/main/java/com/aircast/app/CastService.java) | `record.read(buf,0,2048)` → 统计峰值/错误 → `nativeWrite` | 退出条件：`stop` 或 `AudioRecord` 致命错误 |
| `pump` 线程（native） | `nativeStart` | `RaopLoop::pump()`、远端音量、环形缓冲水位管理 | 唯一碰 `RaopSender` 的线程 |

native 侧只有 `gSender` / `gRing` / `gLatencySec` / `gRate` 等全局量，**没有加锁**，
因为它们只在 `nativeStart` 之前写好、之后只读（`gPendingVolume` 用原子交换传递）。

## 4. JNI 接口

声明在 [CastService](../app/src/main/java/com/aircast/app/CastService.java) 末尾，
实现在 [native_sender.cpp](../app/src/main/cpp/native_sender.cpp)：

| 方法 | 说明 |
|---|---|
| `nativeStart(host, rate, latencyMs)` | 建 sender/ring/pump 并开始握手；返回 0 才算成功。有"第二次调用直接 -1"的哨兵，防止重复会话 |
| `nativeWrite(short[] pcm, samples)` | 把采集到的 PCM 推进环形缓冲 |
| `nativeSetVolume(int pct)` | 设置接收器音量（0..100） |
| `nativeStop()` | 结束会话、停 pump 线程 |

`host` 支持 `IP` 或 `IP:端口`，端口缺省 7000。

## 5. 生命周期

```
点 "Start casting"
  ├─ 显示告知弹窗（可勾选不再显示）
  ▼
commitToCast()                       ← 关键：静音在这里做（有触摸）
  ├─ CastService.armCastMute()       记录投屏前音量、标记这次静音归投屏
  ├─ CastService.mutePhoneNow(this)  真正写静音
  └─ 系统授权弹窗 createScreenCaptureIntent()
       ├─ 同意 → onActivityResult(RESULT_OK)
       │        └─ startForegroundService(CastService, resultCode, data, host)
       │              └─ onStartCommand：MediaSession/VolumeProvider → 通知
       │                 → AudioRecord → nativeStart → capture 线程 → 重申静音
       └─ 拒绝/取消 → abandonCastStart()（有触摸，撤销静音）
```

结束：

| 触发 | 后果 |
|---|---|
| 点 "Stop casting" | 先 `unmuteOnStopTap()`（有触摸，撤销静音）→ `stopService` → `onDestroy` |
| 通知栏停止按钮 | `onStartCommand("STOP_CAST") → stopSelf()` → `onDestroy`；**不**自动恢复音量 |
| 系统收回投屏 | `MediaProjection.Callback.onStop()` → `stopSelf()`；**不**自动恢复音量 |
| 前台服务约束 | 服务不得活得比 projection 久，所以 `onStop()` 里必须 `stopSelf()` |

## 6. 手机侧静音：状态机

这是全项目最容易写错的部分。三个归属标志（都在 `CastService`，**static**，
以便跨服务实例存活）：

| 标志 | 含义 | 何时清 |
|---|---|---|
| `castMuteOwned` | "这次静音是投屏造成的，撤销由我们负责" | 撤销成功 / 重试超时 / 新一轮 `armCastMute` |
| `muteWanted` | 用户希望手机静音（不代表已静音） | 撤销完成 / 显式取消静音 |
| `savedVolume` | 静音前的音量，`-1` = 没记录 | 撤销真正完成后 |

再加两个派生量：`phoneMuted`（**从 `AudioManager` 读回派生**，绝不相信写入结果）、
`muteGen`/`rollbackGen`（代次号，防止排队中的重试在用户改主意后仍然执行）。

**撤销（恢复音量）只允许发生在有触摸的两帧**：

1. 授权弹窗被拒/取消 → `abandonCastStart()` → `rollbackCastMute("consent denied")`
2. 点 "Stop casting" → `unmuteOnStopTap()` → `rollbackCastMute("stop tap")`

其它路径（通知栏停止、点弹窗外部让它消失、随便点一下屏幕、回到前台）**一律不自动撤销**，
原因见 [PITFALLS.md](PITFALLS.md) #1–#3。被这些路径遗留的静音，由界面上的
`Unmute phone` 按钮清除（按钮的触摸是这台设备唯一稳定接受的写入）。

`rollbackCastMute` 之后还有一条 500 ms 间隔、最长 5 s 的重试链（`rollbackAttempt`），
仅作为上面两条路径的兜底；因为它**不**使用任何强制手段，正常情况下第一次就成功。

## 7. 音量（接收器侧）路径

所有入口最终都汇到 `CastService.setRemoteVolume(int)`：

| 入口 | 位置 |
|---|---|
| 界面滑块 | MainActivity 的 `GlassSeekBar` |
| 界面音量键 | `onKeyDown`（仅 `running` 时；消费掉，不改手机音量） |
| 通知栏 4 个按钮中的 +/− | `onStartCommand("VOL_UP"/"VOL_DOWN")`，步长取设置 |
| 系统音量面板 / 锁屏 | `MediaSession` + `VolumeProvider`（`VOLUME_CONTROL_RELATIVE`） |

`setRemoteVolume` 做四件事：更新静态值 → 持久化到 `Settings` → `nativeSetVolume` →
刷新通知并同步 `volumeProvider.setCurrentVolume`。**即使没有投屏也在持久化**，
否则投屏一开始音量会跳回旧值。

## 8. 日志

[CastLog](../app/src/main/java/com/aircast/app/CastLog.java) 是内存环形缓冲，
默认关闭；打开后可分级、查看、导出（纯文本分享）。native 侧通过 `postToJava`
把 `raop` 的日志镜像进同一个缓冲，所以握手、延迟决策、采集统计在一条时间线上。

## 9. 与上游的差异

`airplay2-sender-cpp/` 是 vendored 副本，相对上游有两处补丁（详见 [NOTICE](../NOTICE)）：

1. **AirPlay 2 瞬态配对的 event 通道密钥**：上游在该配对路径上只派生控制通道密钥，
   event 通道没有密钥，接收器约 30 s 后丢会话（即使音频一直在流）。本项目在该路径
   上也派生 Events 密钥。
2. **延迟管理**：连接阶段把缓冲排到 ~0.25 s，避免握手期间留下陈旧积压；进入 live 后
   以 10 ms 为单位削峰来吸收时钟漂移。