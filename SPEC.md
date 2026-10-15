# Winamp 经典版 1:1 复刻（Android）— 构建规格

用户上传了 Winamp 2.x 经典皮肤截图，要求在安卓上 1:1 复刻，重点：
左上角频谱/波形可视化 + 均衡器窗口。最终交付可直接安装的 debug 签名 APK。

## 工具链
- 构建环境搭建由另一 agent 负责，产物在 `~/android-sdk`（ANDROID_HOME），结论追加在 `~/TOOLS.md`。
- 本项目目录：`~/workspace/winamp-android/app-src/`（源码），APK 输出到 `~/workspace/winamp-android/`。
- 包名：`com.dalang.winamp`；minSdk 26，targetSdk 35；纯 Java（不引入 Kotlin，避免额外插件下载）。

## 界面：三个窗口竖向堆叠（可滚动），1:1 还原截图
截图 275px 宽经典皮肤，按比例放大到手机宽度，保持相对布局。

### 1. 主窗口 MAIN
- 标题栏：左侧 "WINAMP" 字样 + 右侧最小化/关闭按钮（经典皮肤样式，可只做装饰）。
- 左上可视化框：实时频谱柱状条（绿色），带峰值保持/下落效果 —— 用 Android `Visualizer` 取播放音频的 FFT 数据真实现场绘制，不是假动画。预留示波器模式切换（Winamp 经典可点可视化框切换频谱/示波器）。
- 歌曲跑马灯：滚动显示 "序号. 艺术家 - 标题"。
- 时间显示：`-3:04` / `0:00` 样式（剩余/已播可点切换）。
- `kbps` / `kHz` / `mono/stereo` 标签（尽量从 MediaMetadata 取，取不到显示 "--"）。
- 音量条、声道平衡条：可拖动，真实控制 MediaPlayer。
- 播放进度条：可拖动 seek。
- 控制按钮行：上一首 / 播放 / 暂停 / 停止 / 下一首 / 打开文件（EJECT），SHUFFLE、REPEAT 开关（功能真实生效）。
- 右下角 Winamp 闪电 logo（用 Canvas 简单绘制近似）。

### 2. 均衡器 EQUALIZER
- 标题栏 "WINAMP EQUALIZER"；ON / AUTO 按钮（ON 真实开关音效，AUTO 可做装饰或按曲风自动）。
- PRESETS 下拉：Flat / Rock / Pop / Jazz / Classical / 自定义，真实生效。
- 10 根竖向滑杆 + 左侧 PREAMP 滑杆，dB 刻度（+12dB / 0dB / -12dB），频率标注 60 170 310 600 1K 3K 6K 12K 14K 16K。
- 用 Android `Equalizer` 音效实现：查询设备实际 band 数量后把 10 根滑杆映射过去（多数设备 5 bands，相邻两根合并映射）。

### 3. 播放列表 PLAYLIST
- 标题栏 "WINAMP PLAYLIST"；列表行格式 `1. 艺术家 - 标题    1:22`，经典绿字黑底等宽字体风。
- 底部按钮 ADD / REM / SEL / MISC：ADD 接 SAF 文件选择器（ACTION_OPEN_DOCUMENT，可多选音频），真实加入列表并持久化 URI；REM 删除选中；SEL 全选/反选；MISC 排序等（可简化）。
- 底部状态条：总时长 `0:66/1:56:16` 样式 + LIST OPTS。

## 行为
- 播放本机音频文件（用户通过 ADD 自行添加）；MediaPlayer 播放，通知栏可先不做。
- 权限：READ_MEDIA_AUDIO（API 33+）/ READ_EXTERNAL_STORAGE（低版本），动态申请。
- 列表点击切歌；上一首/下一首；播完按 repeat/shuffle 逻辑继续。

## 验收
- `aapt dump badging` 验证通过；APK 在真机/模拟器可安装启动（本机无设备则至少保证构建成功 + badging 正常）。
- 交付：`~/workspace/winamp-android/WinampClassic.apk`（debug 签名，可直接安装）。
