# MYXR WINAMP

<img src="logo.png" width="160" alt="MYXR WINAMP logo">

在 Android 上 1:1 复刻经典 Winamp 2.x 播放器：真正的 base-skin 位图、左上角实时频谱、10 段均衡器、播放列表。

An Android 1:1 remake of the classic Winamp 2.x player: real base-skin bitmaps, live spectrum analyzer, 10-band equalizer and playlist editor.

## 功能 Features

- 像素级还原 Winamp base skin（真实皮肤位图，整数倍缩放、无滤波，保持像素锐利）
- 左上角频谱分析仪：柱状频谱 / 折线示波器 / 关闭，点击循环切换
  - 本地 PCM 解码 + 自研 FFT，不依赖系统 `Visualizer`（绕开各家 ROM 的音频链路坑）
- 10 段均衡器 + 前级放大（Preamp）：自研 DSP（PCM 解码 → 10 个 biquad → AudioTrack），频点固定 60/170/310/600/1k/3k/6k/12k/14k/16k，不依赖机型自带的 5 段 Equalizer
- 播放列表：ADD / REM / SEL / MISC、LIST OPTS、已选时长/总时长显示
- 首次打开自动播放内置默认歌曲（`assets/wenroutaozi_Llama.wav`）
  - **致敬彩蛋**：由豆包合成——角色「温柔桃子」以摇滚情绪演绎 Winamp 经典开场白（"Winamp, it really whips the llama's ass!"），首次打开自动播放
- 定制应用图标与应用名

## 开发笔记 Dev Notes

- 无 Gradle，纯手工构建链：`aapt2 → javac → d8 → zipalign → apksigner`（详见 `BUILD.md`）
- 播放链路自研：PcmAudioEngine（MediaExtractor → MediaCodec → 10 段 biquad → AudioTrack），不经 MediaPlayer / 系统 Equalizer；频谱也从同一份 PCM 计算
- 包名 `com.dalang.winamp`，minSdk 26 / targetSdk 35
- 调试签名（`~/.android/debug.keystore`），覆盖安装必须使用同一证书
- `versionCode` / `versionName` 必须同时设置：MIUI / HyperOS 会拒装空版本号的 APK（"解析软件包出现问题"）
- APK 交付必须用文件直链，**不要用聊天附件**（附件传输会破坏 APK 导致解析失败）
- 皮肤美术资源 © Nullsoft，通过 Webamp 项目获取，仅作致敬用途；本仓库的**代码**采用 MIT License

## 本地开发与编译 Build Guide

### 环境要求

- JDK 17（`javac` / `keytool` 直接可用）
- Android SDK：`build-tools 35.0.0`、`platforms/android-35`
- 环境变量：
  ```bash
  export ANDROID_HOME=$HOME/android-sdk
  export PATH=$ANDROID_HOME/build-tools/35.0.0:$ANDROID_HOME/platform-tools:$PATH
  ```

### 编译（无 Gradle）

```bash
AAPT2=$ANDROID_HOME/build-tools/35.0.0/aapt2
D8=$ANDROID_HOME/build-tools/35.0.0/d8
ANDROID_JAR=$ANDROID_HOME/platforms/android-35/android.jar
SRC=~/workspace/winamp-android/app-src
cd $SRC
mkdir -p build/gen build/classes build/dex

# 1. 编译资源
$AAPT2 compile --dir res -o build/compiled.zip

# 2. 链接（必须带 --version-code/--version-name 和 -A assets）
$AAPT2 link -o build/app-unsigned.apk \
  --manifest AndroidManifest.xml \
  -I $ANDROID_JAR \
  --version-code 12 --version-name 0.7 \
  -A assets \
  --java build/gen \
  build/compiled.zip

# 3. 编译 Java（必须 -encoding UTF-8；JDK17 上用 -cp 而不用 -bootclasspath）
find build/gen java -name '*.java' > build/srcs.txt
javac -encoding UTF-8 -source 8 -target 8 -cp $ANDROID_JAR \
  -d build/classes @build/srcs.txt

# 4. dex
$D8 --min-api 26 --lib $ANDROID_JAR --output build/dex \
  $(find build/classes -name '*.class')

# 5. 装进 APK，对齐，签名
cp build/app-unsigned.apk build/app.apk
(cd build && zip -j -q app.apk dex/classes.dex)
zipalign -f 4 build/app.apk build/aligned.apk
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --out WinampClassic.apk build/aligned.apk

# 6. 验证
aapt2 dump badging WinampClassic.apk | head -5
apksigner verify WinampClassic.apk
```

产物：`app-src/WinampClassic.apk`（debug 签名，可直接安装）。

## License

本项目**代码**采用 MIT License（见 `LICENSE` 文件）。

皮肤位图等美术资源版权归 Nullsoft 所有，由 Webamp 项目分发，此处仅用于致敬与学习，不在 MIT 授权范围内。

## 致谢 Thanks

- **Winamp / Nullsoft**：传奇的 Winamp 播放器与 base skin 美术资源（skin art © Nullsoft）
- **Webamp**（Jordan Eldredge，MIT License）：开源的 HTML5 版 Winamp —— https://github.com/captbaritone/webamp 。本项目的皮肤位图（`packages/webamp/assets/skins/base-2.91`）与布局/精灵数据（`skinSprites.ts`、各窗口 CSS）即从该项目提取，特此感谢
