# AGENTS.md — MYXR WINAMP

给在此仓库工作的 agent 的约定。动手前先读 `README.md` 和 `BUILD.md`。

## 构建（唯一链路）

- 无 Gradle。唯一构建链：`BUILD.md`（aapt2 compile → link → javac → d8 → zipalign → apksigner）。
- `aapt2 link` 必须带 `-A assets` 和 `--version-code N --version-name X`（MIUI / HyperOS 拒装空版本号 APK）。
- `javac` 必须加 `-encoding UTF-8`（源码含中文注释）；JDK 17 上不要用 `-bootclasspath`，用 `-cp android.jar`（d8 负责脱糖）。
- 签名固定复用 `~/.android/debug.keystore`（storepass/keypass 均为 `android`）；覆盖安装必须同一证书。
- 每次发版 `versionCode` 递增、`versionName` 同步更新，`BUILD.md` 里的命令示例同步改。

## 交付

- APK 一律用 muse.ai 文件直链交付，并让用户先核对字节数；**绝不用聊天附件**（附件会破坏 APK 导致解析失败）。

## 代码约定

- 播放核心 `WinampPlayer.java` / `PcmAudioEngine.java` / `MainActivity.java` 的行为逻辑：只做 UI 改动时不要碰。
- `SpectrumView.setFft/setWave`、`TickerView.setText` 的接口语义必须保留（PCM 频谱泵依赖它们）。
- 皮肤渲染：整数倍缩放、关闭位图滤波，保持像素锐利。
- `SkinAssets.java` 头部的出处注释（skin art © Nullsoft via Webamp）必须保留。
- 只用 Android framework API，不引入 androidx / 第三方库。
- 换默认歌曲：替换 `assets/wenroutaozi_Llama.wav` 即可，改名只需改 `MainActivity` 里 `DEFAULT_SONG_ASSET` / `DEFAULT_SONG_TITLE` 两个常量。

## 测试

- 本机无 KVM，Android 模拟器不可用；以用户真机（Redmi K100）截图验收为准。
- 发版前自查：`aapt2 dump badging`（确认 versionCode / versionName / application-label）、`apksigner verify` 通过。

## 素材来源

- 皮肤位图与布局/精灵数据来自 https://github.com/captbaritone/webamp（`packages/webamp/assets/skins/base-2.91`、`js/skinSprites.ts`、各窗口 CSS），skin art © Nullsoft，仅致敬用途。
