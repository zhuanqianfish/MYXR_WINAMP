#!/usr/bin/env bash
# 一键构建（等价于 BUILD.md 里的手工链路）
#   用法：./build.sh
# 环境变量：ANDROID_HOME / JAVA_HOME 可覆盖自动探测结果
set -e

VERSION_CODE=13
VERSION_NAME=0.8

cd "$(dirname "$0")"
SRC="$(pwd)/app-src"

# ---- Android SDK ----
if [ -z "$ANDROID_HOME" ]; then
  for d in "$LOCALAPPDATA/Android/Sdk" "$HOME/android-sdk" "/c/Android/Sdk"; do
    [ -d "$d/build-tools" ] && ANDROID_HOME="$d" && break
  done
fi
[ -n "$ANDROID_HOME" ] || { echo "找不到 Android SDK，请设置 ANDROID_HOME"; exit 1; }

BT="$ANDROID_HOME/build-tools/35.0.0"
[ -d "$BT" ] || BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
AAPT2="$BT/aapt2"
# Windows 上 d8 / apksigner 是 .bat 包装脚本，zipalign 是 .exe
if [ -f "$BT/d8.bat" ]; then
  D8="$BT/d8.bat"
  APKSIGNER="$BT/apksigner.bat"
  ZIPALIGN="$BT/zipalign.exe"
else
  D8="$BT/d8"
  APKSIGNER="$BT/apksigner"
  ZIPALIGN="$BT/zipalign"
fi
PLATFORM="$ANDROID_HOME/platforms/android-35"
[ -d "$PLATFORM" ] || PLATFORM="$(ls -d "$ANDROID_HOME"/platforms/* | sort -V | tail -1)"
ANDROID_JAR="$PLATFORM/android.jar"

# ---- JDK ----
if [ -z "$JAVA_HOME" ]; then
  for d in /f/tools/jdk-17* "$HOME/.jdks"/* "$HOME/jdk"*; do
    [ -x "$d/bin/javac.exe" ] && JAVA_HOME="$d" && break
  done
fi
[ -n "$JAVA_HOME" ] || { echo "找不到 JDK，请设置 JAVA_HOME"; exit 1; }
export PATH="$JAVA_HOME/bin:$BT:$PATH"

echo "SDK : $ANDROID_HOME"
echo "JDK : $JAVA_HOME"

cd "$SRC"
rm -rf build
mkdir -p build/gen build/classes build/dex

# 1. 资源
"$AAPT2" compile --dir res -o build/compiled.zip

# 2. 链接（必须带 --version-code / --version-name 与 -A assets）
"$AAPT2" link -o build/app-unsigned.apk \
  --manifest AndroidManifest.xml \
  -I "$ANDROID_JAR" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -A assets \
  --java build/gen \
  build/compiled.zip

# 3. Java（-encoding UTF-8 必须；JDK17 上用 -cp，不要 -bootclasspath）
find build/gen java -name '*.java' > build/srcs.txt
javac -encoding UTF-8 -source 8 -target 8 -cp "$ANDROID_JAR" \
  -d build/classes @build/srcs.txt

# 4. dex
"$D8" --min-api 26 --lib "$ANDROID_JAR" --output build/dex \
  $(find build/classes -name '*.class')

# 5. 装 dex 进 APK（Windows 的 Git Bash 通常没有 zip，用 python 重写 zip；
#    重写而不是追加，是为了让每个条目都带正确的本地头，避免 zipalign 报 header mismatch）
if command -v zip >/dev/null 2>&1; then
  cp build/app-unsigned.apk build/app.apk
  (cd build && zip -j -q app.apk dex/classes.dex)
else
  python - <<'PY'
import zipfile
src, dst = 'build/app-unsigned.apk', 'build/app.apk'
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w') as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
        zi.compress_type = item.compress_type      # resources.arsc 必须保持 STORED
        zi.external_attr = item.external_attr
        zout.writestr(zi, data)
    zi = zipfile.ZipInfo('classes.dex', date_time=(2020, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_DEFLATED
    zout.writestr(zi, open('build/dex/classes.dex', 'rb').read())
PY
fi

# 6. 对齐 + 签名
[ -d "$HOME/.android" ] || mkdir -p "$HOME/.android"
[ -f "$HOME/.android/debug.keystore" ] || keytool -genkeypair \
  -keystore "$HOME/.android/debug.keystore" \
  -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10950 \
  -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US"
"$ZIPALIGN" -f 4 build/app.apk build/aligned.apk
"$APKSIGNER" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
  --out WinampClassic.apk build/aligned.apk

# 7. 验证
"$AAPT2" dump badging WinampClassic.apk | head -5
"$APKSIGNER" verify WinampClassic.apk && echo "签名校验通过"
ls -l WinampClassic.apk
