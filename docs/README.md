# AirCast 开发文档

本目录是面向**二次开发**的说明文档。源码注释为英文（沿用项目既有风格），
文档为中文。

| 文档 | 内容 | 什么时候看 |
|---|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 分层、数据流、线程模型、JNI 接口、生命周期、状态机 | 想改任何代码前 |
| [FEATURES.md](FEATURES.md) | 功能清单、行为契约、设置项一览 | 想知道"现在到底是怎么行为的" |
| [PITFALLS.md](PITFALLS.md) | **踩过的坑：现象 / 原因 / 结论** | **重开发前必读** |
| [BUILD.md](BUILD.md) | 工具链版本、环境搭建、构建、产物校验 | 换机器 / 装环境 |
| [TESTING.md](TESTING.md) | 手动测试矩阵与验收标准 | 每次改完要回归 |
| [ROADMAP.md](ROADMAP.md) | 后续功能与设计取舍（含"只转发指定应用"） | 规划下一步 |

其它既有文件：根目录 [README.md](../README.md)（项目介绍）、
[PRIVACY.md](../PRIVACY.md)、[SECURITY.md](../SECURITY.md)、
[CONTRIBUTING.md](../CONTRIBUTING.md)、[NOTICE](../NOTICE)（上游补丁与许可）。

## 一句话概括

这是一个**纯本地**的 Android 应用：用 `AudioPlaybackCapture` 抓到手机上正在播放的
音频，经 JNI 送进一个环形缓冲，由 native 线程按固定延迟喂给内置的 AirPlay 2 发送端
（`airplay2-sender-cpp`，已 vendored 并打过补丁），最终推到局域网里的 HomePod /
Sonos / Apple TV 等 AirPlay 接收器。没有中转盒子、没有云端、没有账号。

## 版本

当前源码版本：**1.0.0**（`versionCode 25`）。构建产物见
[BUILD.md](BUILD.md) 的"产物校验"一节。