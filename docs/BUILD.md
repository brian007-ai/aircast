# 构建与产物校验

## 1. 工具链版本（本仓库构建通过的确切组合）

| 组件 | 版本 | 备注 |
|---|---|---|
| JDK | 17+（实测 OpenJDK **25.0.2**） | Gradle 9.8 / AGP 要求 17+ |
| Gradle | **9.8.0** | 由 `gradle/wrapper/gradle-wrapper.properties` 固定，无需手装 |
| Android SDK Platform | **android-36** | `compileSdk = 36` / `targetSdk = 36` |
| Android Build-Tools | **36.0.0** | |
| NDK | **27.2.12479018** | 写死在 [app/build.gradle.kts](../app/build.gradle.kts) 的 `ndkVersion` |
| CMake | **3.22.1** | 写死在 `externalNativeBuild.cmake.version` |
| ABI | `arm64-v8a` | `abiFilters`，无其它架构 |
| minSdk | 29 | Android 10 |

> 版本不一致是最常见的构建失败原因。NDK/CMake 的版本号必须与 `build.gradle.kts`
> 完全一致，否则 Gradle 会尝试自己下载。

## 2. 依赖：mbedtls 是 git 子模块

`third_party/mbedtls` 是 **git 子模块**（固定到 mbedtls-3.6.7），普通 clone 不会带上内容：

```bash
git clone --recurse-submodules <repo-url>
cd AirCast

# 或者已经 clone 过：
git submodule update --init --recursive
```

`airplay2-sender-cpp/` 不是子模块，是**直接 vendored 进仓库**的（含本项目补丁），
随 clone 一起下来。

## 3. 搭环境

```bash
# 1) 装 SDK 组件（cmdline-tools 已具备时）
sdkmanager --sdk_root=$ANDROID_SDK \
  "platforms;android-36" "build-tools;36.0.0" \
  "ndk;27.2.12479018" "cmake;3.22.1" "platform-tools"

# 2) 指向 SDK
echo "sdk.dir=/absolute/path/to/android-sdk" > local.properties
```

`local.properties` 已在 `.gitignore` 中，不会入库。

> 国内网络可用镜像：`HTTP_PROXY` / `HTTPS_PROXY` 走代理后，Gradle 与 sdkmanager 都能正常拉包。

## 4. 构建

```bash
./gradlew :app:assembleDebug      # 调试包（自签名）
./gradlew :app:assembleRelease    # 未签名 release 包（默认）
```

产物：
```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

安装：
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 5. 签名（release）

签名参数只从**机器级** `~/.gradle/gradle.properties` 读取，绝不入库：

```properties
RELEASE_STORE_FILE=/absolute/path/to/keystore.jks
RELEASE_STORE_PASSWORD=…
RELEASE_KEY_ALIAS=…
RELEASE_KEY_PASSWORD=…
```

不提供这些参数时，release 产物就是**未签名**的（可复现构建工具需要的形态）。

## 6. 可复现构建相关（已在工程里做好，改动时别破坏）

- `dependenciesInfo { includeInApk = false }`：去掉 Google 的 dependency metadata 签名块。
- native 侧 `-ffile-prefix-map` / `-fdebug-prefix-map` / `-Wl,--build-id=none` /
  `--sort-common` / `--no-rosegment`：不留绝对路径、去掉随机器变化的 build-id。

## 7. 产物校验（发版前必做）

**a) 版本号**：
```bash
$ANDROID_SDK/build-tools/36.0.0/aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk \
  | grep -E "^package:|native-code"
# 期望：versionCode / versionName 正确，native-code: 'arm64-v8a'
```

**b) 确认改动真的进了 dex**（注意多 dex！见 [PITFALLS.md](PITFALLS.md) #12）：
```bash
python3 - <<'PY'
import zipfile
apk = 'app/build/outputs/apk/debug/app-debug.apk'
keys = [b'CastService', b'consent denied']          # 期望存在
gone = [b'show-ui', b'rollbackLeftoverCastMute']    # 期望不存在
z = zipfile.ZipFile(apk)
for n in [x for x in z.namelist() if x.endswith('.dex')]:
    d = z.read(n)
    print(n, {k.decode(): d.count(k) for k in keys + gone})
PY
```

**c) 16 KB 页对齐**（Android 15+ 必须）：
```bash
zipalign -c -P 16 -v 4 app-debug.apk | tail -3
```

## 8. 发版步骤清单

1. 改 [app/build.gradle.kts](../app/build.gradle.kts) 的 `versionCode +1`、`versionName`。
2. `./gradlew :app:assembleDebug`。
3. 按第 7 节做三项校验。
4. **用新文件名**另存一份构建产物（例如 `AirCast-1.0.15-debug.apk`），不要覆盖同名旧文件
   ——否则装机时会被判定为"同一个版本"（[PITFALLS.md](PITFALLS.md) #13）。
5. 提交 + 打 tag。