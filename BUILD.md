# 手动构建说明（无 Gradle）

源码在 `app-src/`，纯 framework API（无 androidx），可用 aapt2 + d8 + apksigner
手工链路打包。环境变量以搭建好的工具链为准（见 `~/TOOLS.md`）。

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
  --version-code 12 --version-name 0.7 \
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
