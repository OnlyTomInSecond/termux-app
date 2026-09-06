# Termux App - 开发环境搭建指南（纯命令行 + 真机调试）

## 1. 项目概览

termux-app 是一个多模块 Android 项目，包含以下子模块：

| 模块 | 说明 | 原生代码 |
|------|------|----------|
| `:app` | 主应用 (Android Application) | C (bootstrap) |
| `:termux-shared` | 共享库（设置、通知、Shell 等） | C |
| `:terminal-emulator` | 终端模拟器核心 | C (JNI) |
| `:terminal-view` | 终端视图组件（纯 Java） | 无 |

### 关键版本信息

| 组件 | 版本 |
|------|------|
| Gradle | 9.2.1 (wrapper) |
| Android Gradle Plugin | 8.13.2 |
| NDK | **29.0.14206865** |
| compileSdk | 36 (Android 15) |
| minSdk | 21 (Android 5.0) |
| targetSdk | 28 (Android 9) |
| Java | 17+（当前已装 21，兼容） |
| 构建变体 | `apt-android-7`（默认，Android 7+）/ `apt-android-5` |

---

## 2. 环境要求

### ✅ 已具备

- **系统**: Arch Linux (x86_64)
- **Java**: OpenJDK 21
- **CPU**: 32 核 / 内存: 46 GB
- **AUR helper**: `paru`

### ❌ 需要安装

1. **Android SDK 命令行工具**（Command-line Tools）
2. **Android SDK Platform 36**
3. **Android NDK 29.0.14206865**
4. **Platform Tools**（包含 `adb`）

---

## 3. 搭建步骤（纯命令行，无 GUI）

### 3.1 安装 Android SDK Command-line Tools

```bash
# 从 AUR 安装（推荐，自动处理依赖和更新）
paru -S android-sdk-cmdline-tools android-platform-tools

# SDK 会被安装到 /opt/android-sdk
# 但建议将 SDK 放到用户目录下，方便管理
```

或者手动下载：

```bash
# 创建 SDK 目录
mkdir -p ~/Android/Sdk
cd ~/Android/Sdk

# 下载 command line tools（版本 11076708 对应 2024 年最新）
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip
rm commandlinetools-linux-11076708_latest.zip

# 重命名为标准路径
mv cmdline-tools latest
mkdir -p cmdline-tools
mv latest cmdline-tools/
```

### 3.2 安装 SDK Platform 36 + NDK + Platform Tools

```bash
# 确保环境变量已设置（见下一步）
export ANDROID_HOME=~/Android/Sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

# 安装核心组件
sdkmanager --install \
  "platforms;android-36" \
  "ndk;29.0.14206865" \
  "build-tools;36.0.0" \
  "platform-tools"

# 查看已安装列表确认
sdkmanager --list --installed
```

> **关于 NDK 版本**：`gradle.properties` 中指定的 `ndkVersion=29.0.14206865` 必须完全匹配。如果 sdkmanager 下载慢，可以手动从 [NDK 存档](https://github.com/android/ndk/wiki/Unsupported-Downloads#r29) 下载并解压到 `$ANDROID_HOME/ndk/29.0.14206865/`。

### 3.3 设置环境变量

添加到 `~/.bashrc` 或 `~/.zshrc`：

```bash
# Android SDK
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME      # 部分工具需要
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865
export NDK_HOME=$ANDROID_NDK_HOME

export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
export PATH=$ANDROID_HOME/platform-tools:$PATH
```

重载配置：

```bash
source ~/.bashrc   # 或 source ~/.zshrc
```

### 3.4 创建 local.properties（可选但推荐）

在项目根目录创建 `local.properties`，明确指定 SDK 位置，避免环境变量遗漏时出错：

```bash
cd ~/src/termux-app
cat > local.properties << EOF
sdk.dir=$HOME/Android/Sdk
ndk.dir=$HOME/Android/Sdk/ndk/29.0.14206865
EOF
```

### 3.5 验证安装

```bash
# Java
java -version
# 期望: openjdk version "21.0.x"

# Android SDK
ls $ANDROID_HOME/platforms/android-36
# 期望: android-36 目录存在

# NDK
ls $ANDROID_NDK_HOME/ndk-build
# 期望: 存在 ndk-build 可执行文件

# adb
adb --version
# 期望: Android Debug Bridge version ...
```

### 3.6 安装系统库（Arch Linux）

```bash
# 安装 32 位兼容库（SDK 工具需要）
sudo paru -S lib32-glibc lib32-libxcb lib32-libx11 lib32-libxcomposite \
  lib32-libxdamage lib32-libxext lib32-libxfixes lib32-libxrender \
  lib32-libxtst lib32-libstdc++5
```

---

## 4. 构建项目

### 4.1 首次构建

```bash
cd ~/src/termux-app

# Debug 构建（默认 apt-android-7 变体，Android 7+ 设备用这个）
./gradlew assembleDebug

# 只构建 arm64-v8a（你的手机很可能是这个架构，构建更快）
./gradlew assembleDebug -Pandroid.injected.build.abi=arm64-v8a

# 指定 Android 5/6 变体
TERMUX_PACKAGE_VARIANT=apt-android-5 ./gradlew assembleDebug
```

> **首次构建会下载 bootstrap zip**（约 120MB/架构 × 4 个架构），这是 Termux 运行所需的最小 Linux 根文件系统（busybox、bash 等）。如果下载失败，请参照第 7 节手动下载。

### 4.2 构建产物

```
app/build/outputs/apk/debug/
├── termux-app_apt-android-7-debug_arm64-v8a.apk   # 你的手机用这个
├── termux-app_apt-android-7-debug_armeabi-v7a.apk
├── termux-app_apt-android-7-debug_x86_64.apk
├── termux-app_apt-android-7-debug_x86.apk
└── termux-app_apt-android-7-debug_universal.apk    # 通用包
```

**确定你的手机架构**：
```bash
adb shell getprop ro.product.cpu.abi
# 常见输出: arm64-v8a, armeabi-v7a, x86_64
```

### 4.3 运行单元测试

```bash
# 运行所有单元测试
./gradlew testDebugUnitTest

# 只跑某个模块
./gradlew :app:testDebugUnitTest
./gradlew :terminal-emulator:testDebugUnitTest
```

### 4.4 清理

```bash
# 清理构建产物（包括下载的 bootstrap zip）
./gradlew clean

# 仅清理 app 模块
./gradlew :app:clean
```

---

## 5. 真机调试（USB）

### 5.1 手机端准备

1. **开启开发者选项**：
   - 设置 → 关于手机 → 连续点击"版本号"7 次
2. **开启 USB 调试**：
   - 设置 → 系统 → 开发者选项 → 启用 **USB 调试**
3. **连接电脑**：
   - 用 USB 数据线连接手机
   - 手机上选择"文件传输"或"传输文件"模式
   - 首次连接会弹出"允许 USB 调试？"→ 勾选"一律允许"并确认

### 5.2 验证连接

```bash
adb devices
# 期望输出:
# List of devices attached
# 0123456789ABCDEF    device
```

如果显示 `unauthorized`，请检查手机上的授权弹窗。如果显示 `no permissions`，可能需要 udev 规则：

```bash
# 查看手机 USB 信息
lsusb

# 创建 udev 规则（以 Google Pixel 为例，VID 为 18d1）
echo 'SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0666", GROUP="plugdev"' \
  | sudo tee /etc/udev/rules.d/51-android.rules
sudo udevadm control --reload-rules
# 重新插拔 USB 线
```

常见厂商 USB VID：
- Google/Pixel: `18d1`
- OnePlus: `22d9`
- Xiaomi: `2717`
- Samsung: `04e8`
- Huawei: `12d1`
- OPPO: `22d9`
- Vivo: `2d95`

### 5.3 安装 APK 到手机

```bash
# 安装 arm64-v8a 版本（替换已安装的旧版）
adb install -r app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk

# 如果手机上还没有 Termux，需要先卸载旧版（不同签名源不兼容）
adb uninstall com.termux   # 先卸载旧版
adb install app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk
```

> **重要**：如果手机上之前从 F-Droid 或 Google Play 安装了 Termux，必须先卸载，因为不同源的签名密钥不同。调试版 APK 使用 `app/testkey_untrusted.jks` 签名。

### 5.4 查看日志

```bash
# 实时查看 Termux 相关日志
adb logcat -s Termux:* Termux-shared:* TerminalEmulator:*

# 查看全部日志并过滤
adb logcat | grep -E "Termux|TerminalEmulator"

# 清空旧日志后开始跟踪
adb logcat -c && adb logcat -s Termux:*

# 保存日志到文件
adb logcat -s Termux:* > termux.log

# 查看 crash 堆栈
adb logcat -s AndroidRuntime:* *:E
```

### 5.5 快速迭代工作流

```bash
# 一键构建 + 安装（最常用）
./gradlew assembleDebug && \
adb install -r app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk

# 也可以写个 alias
alias termux-build="cd ~/src/termux-app && ./gradlew assembleDebug -Pandroid.injected.build.abi=arm64-v8a && adb install -r app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk"
```

---

## 6. 无线调试（USB 不方便时）

```bash
# 1. 先用 USB 连接一次，执行：
adb tcpip 5555

# 2. 拔掉 USB，通过 WiFi 连接（确保手机和电脑在同一网络）
adb connect <手机IP>:5555

# 3. 查看手机 IP
adb shell ip route
# 或 设置 → 关于手机 → 状态信息

# 4. 断开无线连接
adb disconnect
```

> 注意：部分手机在锁屏后会断开无线 adb 连接。Android 11+ 支持"无线调试"（开发者选项内），无需 USB 即可配对。

---

## 7. 常见问题

### Q: Gradle 构建报 `SDK location not found`

```bash
# 方案 1：确认环境变量已设置
echo $ANDROID_HOME

# 方案 2：创建 local.properties
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

### Q: Bootstrap 下载失败（网络问题）

构建时会从 GitHub 下载 bootstrap zip，如果下载慢或失败：

```bash
# 1. 查看 app/build.gradle 中需要的版本和 checksum
#    找到类似:
#    def version = "2026.02.12-r1" + "%2B" + "apt.android-7"

# 2. 手动从 GitHub 下载
#    https://github.com/termux/termux-packages/releases
#    找到对应的 bootstrap 版本 tag，下载 bootstrap-<arch>.zip

# 3. 放入正确位置
#    app/src/main/cpp/bootstrap-<arch>.zip

# 4. 重新构建
./gradlew assembleDebug
```

### Q: NDK 构建失败

```bash
# 确认 NDK 版本完全匹配
ls $ANDROID_HOME/ndk/
# 必须包含 29.0.14206865

# 检查 NDK 路径
$ANDROID_NDK_HOME/ndk-build --version
```

### Q: `adb install` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

手机上已安装的 Termux 签名与本调试版不匹配：

```bash
adb uninstall com.termux
adb install termux-app_apt-android-7-debug_arm64-v8a.apk
```

### Q: `adb install` 报 `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`

Termux 插件（API、Widget 等）签名冲突。需要 **卸载所有 com.termux 开头的应用**：

```bash
adb shell pm list packages | grep termux
# 卸载所有列出的包
adb uninstall com.termux
adb uninstall com.termux.api
adb uninstall com.termux.widget
# ... 等
```

### Q: 构建很慢

```bash
# 1. 只构建需要的 ABI（推荐）
./gradlew assembleDebug -Pandroid.injected.build.abi=arm64-v8a

# 2. 使用 Gradle 构建缓存
./gradlew assembleDebug --build-cache

# 3. 利用守护进程（第二次开始会快很多）
export GRADLE_OPTS="-Dorg.gradle.daemon=true"
```

### Q: Java 版本问题

AGP 8.13.2 + Gradle 9.2.1 兼容 Java 17-21。如果装了多个 JDK：

```bash
# 查看已安装
archlinux-java status

# 切换
sudo archlinux-java set java-21-openjdk
```

---

## 8. 项目架构速查

### 8.1 源码结构

```
termux-app/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml    # 应用清单
│       ├── cpp/                   # 原生 C (bootstrap 嵌入)
│       └── java/                  # Java 源码 + res/ 资源
├── termux-shared/
│   └── src/main/
│       ├── cpp/                   # 原生 C
│       └── java/                  # 共享功能（设置、通知、Shell 执行等）
├── terminal-emulator/
│   └── src/main/
│       ├── jni/                   # 终端 JNI C 代码
│       └── java/                  # 终端模拟实现
├── terminal-view/
│   └── src/main/java/             # 终端 UI 组件
├── build.gradle                   # 根构建脚本
├── settings.gradle                # 模块声明
└── gradle.properties              # 版本定义
```

### 8.2 构建流程

```
./gradlew assembleDebug
   │
   ├── 1. downloadBootstraps  → 下载 bootstrap-<arch>.zip（4 个架构）
   │
   ├── 2. NDK build           → 编译原生 C 代码为 .so
   │     app/.../cpp/         → libtermux-bootstrap.so
   │     termux-shared/.../   → libtermux-shared.so
   │     terminal-emulator/   → libtermux-emulator.so
   │
   ├── 3. Java compile        → javac 编译 Java 源码
   │
   ├── 4. Bootstrap 嵌入      → 将 bootstrap.zip 以 .S 汇编方式嵌入 .so
   │
   └── 5. APK 打包            → 生成最终 APK（分 ABI 和 universal 版）
```

### 8.3 关键环境变量

| 变量 | 用途 | 默认值 |
|------|------|--------|
| `TERMUX_PACKAGE_VARIANT` | Bootstrap 变体 | `apt-android-7` |
| `TERMUX_APP_VERSION_NAME` | 自定义版本名 | `0.118.0` |
| `TERMUX_APK_VERSION_TAG` | APK 文件名标识 | 空 |
| `TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS` | 是否拆分 ABI | `1` |

---

## 9. 相关链接

- [Termux App GitHub](https://github.com/termux/termux-app)
- [Termux Packages (bootstrap 来源)](https://github.com/termux/termux-packages)
- [Bootstrap 发布页](https://github.com/termux/termux-packages/releases)
- [NDK 29 存档下载](https://github.com/android/ndk/wiki/Unsupported-Downloads#r29)
- [Android SDK Command-line Tools](https://developer.android.com/studio#command-line-tools-only)

---

## 附录：快速安装脚本

如果从头搭建，可直接运行以下脚本一键安装：

```bash
#!/bin/bash
set -e

# === SDK 安装 ===
mkdir -p ~/Android/Sdk
cd ~/Android/Sdk

# 下载 commandline-tools（如果未安装）
if [ ! -d cmdline-tools ]; then
  wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip commandlinetools-linux-11076708_latest.zip
  rm commandlinetools-linux-11076708_latest.zip
  mkdir -p cmdline-tools && mv latest cmdline-tools/
fi

# 设置临时环境变量
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

# 安装 SDK/NDK/Platform-Tools
yes | sdkmanager --install \
  "platforms;android-36" \
  "ndk;29.0.14206865" \
  "build-tools;36.0.0" \
  "platform-tools"

# === 项目 local.properties ===
cd ~/src/termux-app
cat > local.properties << EOF
sdk.dir=$HOME/Android/Sdk
ndk.dir=$HOME/Android/Sdk/ndk/29.0.14206865
EOF

echo "=== 环境就绪！运行 ./gradlew assembleDebug 开始构建 ==="
```