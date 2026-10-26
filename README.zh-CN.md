# AirCast

[English](README.md) | 简体中文

**把手机上正在播放的声音投到 AirPlay 2 音箱 —— HomePod、Sonos、Apple TV 等。**

安卓手机说 Google Cast，AirPlay 音箱只说 AirPlay，两者原生并不相通，所以过去的
标准答案一直是"买个 iPhone"。AirCast 是个小安卓应用，专门补这个缺口：它抓取手机上
正在播放的声音，直接从手机投到同一 Wi-Fi 下的 AirPlay 接收端。不需要中转盒子，
不需要树莓派，不需要任何主机。

数据都在你的局域网内，不出网。没有账号、没有云端、没有埋点。

## 界面

界面围绕一种液态玻璃材质用代码绘制：半透明的圆角玻璃板浮在缓慢漂移的极光背景上，
配合胶囊按钮、分组列表行，以及 iOS 比例的开关与滑块。配色跟随系统的浅色/深色设置，
系统栏以 edge-to-edge 方式绘制，图标明暗随主题切换。

界面部分为本发行版原创，与业务代码放在同一目录：`Ui.java`（设计系统）、
`GlassDrawable.java`（玻璃材质）、`GlassToggle.java`、`GlassSeekBar.java`、
`GlassDialog.java` 与 `AuroraView.java`（背景）。

## 功能

- **任何应用的音频都能投** —— 音乐、播客、有声书、视频、通话。
- **自动发现接收端**：走 mDNS/Bonjour（`_airplay._tcp`），也可以直接输入 IP。
- **投屏时音量键控制音箱**，界面上另有 +/− 按钮兜底。
- **投屏时自动静音手机扬声器**，避免两头同时出声。
- **无需主机**：全部在手机上运行。

## 运行要求

- Android 10（API 29）及以上
- arm64-v8a 设备
- 同一 Wi-Fi 下的 AirPlay 2 接收端
- 安卓的采集授权（每次会话点一次，系统强制要求）

## 文档

| 文档 | 内容 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 分层、数据流、线程模型、JNI、生命周期、静音状态机 |
| [docs/FEATURES.md](docs/FEATURES.md) | 功能清单、行为契约、设置项参考 |
| [docs/PITFALLS.md](docs/PITFALLS.md) | **至今踩过的全部坑 —— 动代码前先看这个** |
| [docs/BUILD.md](docs/BUILD.md) | 精确工具链、环境搭建、产物校验 |
| [docs/TESTING.md](docs/TESTING.md) | 手动测试矩阵（静音归属是核心回归项） |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 后续功能，含"只转发指定应用" |

## 从源码构建

该有的都在仓库里。没有安装脚本，也不需要下载依赖 —— AirPlay 发送器以 vendored 方式
放在 `airplay2-sender-cpp/`，Mbed TLS 是固定版本的 git 子模块。

```bash
git clone --recurse-submodules <repository-url>
cd aircast

# 指向你的 SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # 未签名的 release APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

如果克隆时没带 `--recurse-submodules`，补执行一句：

```bash
git submodule update --init --recursive
```

**工具链要求：** JDK 17+、Android SDK（`compileSdk 36`、`build-tools 36.0.0`）、
NDK `27.2.12479018`、CMake `3.22.1`，Gradle 插件经 wrapper 使用 9.8.0。
完整说明与发版前要跑的产物校验命令见 [docs/BUILD.md](docs/BUILD.md)。

### Release 签名

未提供签名属性时 release 产物不签名，这正是可复现构建者想要的。要签名，把这些放进
`~/.gradle/gradle.properties`（不要放进仓库）：

```properties
RELEASE_STORE_FILE=/absolute/path/to/keystore
RELEASE_STORE_PASSWORD=…
RELEASE_KEY_ALIAS=…
RELEASE_KEY_PASSWORD=…
```

## 工作原理

```
手机上正在播放的音频
  │  AudioPlaybackCapture（MediaProjection —— 每次会话点一次授权）
  ▼
PCM 帧 ──► JNI ──► 环形缓冲 ──► AirPlay 2 发送器 ──► 音箱
                                          （已打补丁的 vendored 副本）
```

`AudioPlaybackCapture` 跑在 `mediaProjection` 类型的前台服务里，把原始 PCM 喂进环形
缓冲；一个 native 线程按节奏把缓冲送进 AirPlay 2 会话，用的是 vendored 的
`airplay2-sender-cpp`。

让这套东西稳定工作的关键是对该库的两处补丁，都已打在 vendored 副本里，并在 `NOTICE`
中说明：

1. **AirPlay 2 瞬态配对的 event 通道密钥。** 在 HomePod 走的那条配对路径上，上游只
   派生控制通道密钥，导致 event 通道始终没有被加密，接收端会在约 30 秒后断开——哪怕
   声音一直在传。AirCast 在该路径上也派生了 Events 密钥。
2. **延迟管理。** 连接阶段把缓冲排空到 ~0.25 s，避免握手完成后还压着一堆陈旧积压；
   进入直播状态后，积压超过 1.6 s 时做一次小幅漂移裁剪，用来吸收时钟漂移。

延迟大约 **2–3 秒**，这是 AirPlay 协议自身的缓冲，不是本应用额外加的。

## 手机静音：哪些是自动的，哪些不是

投屏时把手机静音，是这个应用最难的部分——某些 ROM 只接受**背后有真实触摸**的音量
写入。应用现在的行为是刻意设计的结果，改之前请先读
[docs/PITFALLS.md](docs/PITFALLS.md#1-系统音量条会莫名从屏幕侧边弹出来)：

| 场景 | 手机音量会自动恢复吗 |
|---|---|
| 采集授权弹窗：点了右上角 X / 取消 | **会**（这次返回是有触摸支撑的） |
| 采集授权弹窗：点弹窗外部使其消失 | **不会** —— 点界面上的 *Unmute phone* |
| 从通知栏停止投屏 | **不会** —— 点界面上的 *Unmute phone* |
| 用界面上的 **Stop casting** 按钮停止 | **会** |

应用从不使用 `FLAG_SHOW_UI`：唯一用过的一次，它把系统音量条弹到了屏幕上，写入却依然
没生效。

## 仓库结构

```
app/                    安卓应用（Java，无 AndroidX）+ JNI 胶水层
  src/main/java/com/aircast/app/   MainActivity、SettingsActivity、
                                   CastService、AirplayDiscovery、
                                   Settings、CastLog、App，以及界面系统
                                   （Ui、GlassDrawable、GlassToggle、
                                   GlassSeekBar、GlassDialog、AuroraView）
  src/main/cpp/                    native_sender.cpp（环形缓冲 + 泵线程）
airplay2-sender-cpp/    vendored 的 AirPlay 2 发送器（已打补丁 —— 见 NOTICE）
third_party/mbedtls/    加密库（git 子模块，mbedtls-3.6.7）
docs/                   开发者文档（架构、功能、踩坑、构建、测试、路线图）
```

## 已知限制

- 主动退出播放采集的应用，投屏期间是静音。DRM 与流媒体应用（Netflix 之类）如此设计，
  任何应用都抓不到它们。
- 采集按**音频用途（usage）过滤，而不是按应用**：任何正在播放 media 音频的应用都会被
  抓走，所以系统那个"共享一个应用 / 整个屏幕"的选择并不会缩小采集范围（本应用从不
  录制屏幕）。按应用采集在计划中 —— 见 [docs/ROADMAP.md](docs/ROADMAP.md)。
- 音频延迟设置作用于**下一次**投屏：它在会话建立时就被固定（接收端的抖动缓冲窗口在
  `SETUP` 中协商，RTP 时间轴锚定于此）。
- 每个投屏会话需要一次采集授权（安卓 MediaProjection 的强制要求）。
- 接收端中途断开不会自动重连 —— 停止后再启动。
- 如果媒体会话被其他应用占用，音量键可能调的是那个应用的音量而不是音箱的。请用界面上
  的 +/− 按钮。
- 设备实际授予的采集采样率可能与请求值不同（44.1 kHz vs 48 kHz）；应用会回读真实
  采样率并据此调整发送节奏。

## 隐私

AirCast 不收集、不存储任何东西，除与局域网内的 AirPlay 接收端通信外没有任何网络代码。
详见 [PRIVACY.md](PRIVACY.md)。

## 致谢与许可

AirCast 采用 **Apache-2.0** 许可 —— 见 [LICENSE](LICENSE)。本发行版在上游 AirCast
（Wesley DeLand）的基础上重做了界面与应用代码；原始版权声明与全部第三方声明均保留在
[NOTICE](NOTICE) 中，符合 Apache-2.0 的要求。

它构建于 **[airplay2-sender-cpp](https://github.com/akustikrausch/airplay2-sender-cpp)**
（Apache-2.0，© 2026 Andreas Wendorf）之上，该项目以 vendored 形式放在
`airplay2-sender-cpp/`，并附带上文所述的补丁。该项目又致谢
[pyatv](https://github.com/postlund/pyatv) 与
[pair_ap](https://github.com/ejurgensen/pair_ap)（均为 MIT），因其协议逻辑被移植与
交叉验证。加密来自 [Mbed TLS](https://github.com/Mbed-TLS/mbedtls)（Apache-2.0，
作为子模块）与 [orlp/ed25519](https://github.com/orlp/ed25519)（zlib）。完整声明见
[NOTICE](NOTICE) 与 [`licenses/`](licenses/)。

"AirPlay"、"HomePod"、"Sonos"、"Apple TV" 是其各自所有者的商标，此处仅用于说明兼容性。
AirCast 与 Apple Inc. 或其他音箱厂商无任何关联，也未获其背书。