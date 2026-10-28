# 手动构建说明（无 Gradle）

源码在 `app-src/`，纯 framework API（无 androidx），可用 aapt2 + d8 + apksigner
手工链路打包。环境变量以搭建好的工具链为准（见 `~/TOOLS.md`）。

> **懒人版**：`./build.sh` 就是下面这套链路的一键脚本（自动找 SDK / JDK，
> 处理 Windows 上 `d8.bat`、没有 `zip` 命令这些坑），改完代码直接跑它。

```bash
export ANDROID_HOME=~/android-sdk
export PATH=$ANDROID_HOME/build-tools/35.0.0:$ANDROID_HOME/platform-tools:$PATH
AAPT2=$ANDROID_HOME/build-tools/35.0.0/aapt2
D8=$ANDROID_HOME/build-tools/35.0.0/d8
ANDROID_JAR=$ANDROID_HOME/platforms/android-35/android.jar
SRC=~/workspace/winamp-android/app-src
cd $SRC
mkdir -p build/gen build/classes build/dex

# 1. 编译资源
$AAPT2 compile --dir res -o build/compiled.zip

# 2. 链接，生成 APK 骨架 + R.java
# 注意：必须带 --version-code/--version-name，某些手机的包解析器会拒绝
# versionCode 为空的 APK（"解析软件包出现问题"）。
$AAPT2 link -o build/app-unsigned.apk \
  --manifest AndroidManifest.xml \
  -I $ANDROID_JAR \
  --version-code 13 --version-name 0.8 \
  -A assets \
  --java build/gen \
  build/compiled.zip

# 3. 编译 Java（含生成的 R.java）
# 注意两个坑（2026-10-15 实测）：
#   - 本机 javac 默认编码是 US-ASCII，源码含中文注释必须加 -encoding UTF-8；
#   - JDK 17 上 `-source 8 -bootclasspath android.jar` 编译 lambda 会报
#     "cannot find symbol: method metafactory"（android.jar 缺 LambdaMetafactory），
#     改为只用 -cp android.jar（不用 -bootclasspath），d8 负责脱糖。
find build/gen java -name '*.java' > build/srcs.txt
javac -encoding UTF-8 -source 8 -target 8 -cp $ANDROID_JAR \
  -d build/classes @build/srcs.txt

# 4. dex
$D8 --min-api 26 --lib $ANDROID_JAR --output build/dex \
  $(find build/classes -name '*.class')

# 5. 把 classes.dex 装进 APK
cp build/app-unsigned.apk build/app.apk
cd build && zip -j app.apk dex/classes.dex && cd ..

# 6. 对齐 + 签名（debug key 可直接安装）
[ -f ~/.android/debug.keystore ] || keytool -genkeypair -keystore ~/.android/debug.keystore \
  -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10950 \
  -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US"
zipalign -f 4 build/app.apk build/aligned.apk
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --out WinampClassic.apk build/aligned.apk
# 注意：--ks-pass 后面是空格写法（"--ks-pass pass:android"），不是 "--ks-pass:android"；
# ~/.android 目录不存在时 keytool 写 keystore 会失败，先 mkdir -p ~/.android。

# 7. 验证
aapt2 dump badging WinampClassic.apk | head -5
```

产物：`~/workspace/winamp-android/WinampClassic.apk`（debug 签名，可直接安装）。

## Windows 上的三个额外坑（2026-10-29 实测）

1. `d8` / `apksigner` 在 build-tools 里是 `.bat` 包装脚本，`zipalign` 是 `.exe`，
   脚本里别硬写 `d8`，按文件是否存在挑后缀。
2. Git Bash 默认没有 `zip` 命令。用 Python 重写 zip 补 `classes.dex`：
   **必须重写整个包、并保持 `resources.arsc` 为 STORED**，不要用 `'a'` 追加模式
   （追加出来的条目本地头不对，zipalign 会刷一屏 `header mismatch`）。
3. 本机没有 Android Studio 自带的 JBR，JDK 装在 `F:\tools\jdk-17.0.20.1+1`
   （Temurin 17，从清华镜像下的，Adoptium 官方源在国内只有 ~100KB/s）。

## v0.8 新增的清单项

- 权限：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、
  `POST_NOTIFICATIONS`（Android 13+ 运行时授权）、`WAKE_LOCK`
- 组件：`<service android:name=".PlaybackService" android:foregroundServiceType="mediaPlayback" />`
- 图标：`res/drawable/ic_stat_winamp.xml` 与 `ic_media_{play,pause,prev,next}.xml`
  （纯白矢量剪影，通知栏小图标必须是这个形态）
