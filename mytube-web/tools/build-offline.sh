#!/usr/bin/env bash
# Build Wtube without dl.google.com or Maven Central.
#
# The project's build.sh fetches the Android SDK, R8/D8, media3 and guava from
# dl.google.com and repo1.maven.org. Those hosts are unreachable from the
# sandbox, so this script assembles an equivalent toolchain from npm and from
# artifacts already committed to GitHub mirrors:
#
#   JDK 11        npm "jdk11" (ships as split tar parts)
#   aapt2         npm "aaptjs3"
#   D8/apksigner  npm "@drxiaozhi/minapk"
#   media3 1.11.1 LineageOS/android_packages_apps_Twelve (app/libs/androidx/media3)
#   guava 31.1    eyasuyuki/keydriver (committed .m2 layout)
#
# Usage: tools/build-offline.sh [versionCode] [versionName]
set -e
VC=${1:-82}
VN=${2:-2.05}
P=$(cd "$(dirname "$0")/.." && pwd)
T=/tmp/mtw-offline
JDK=$T/jdkx/jdk11/jdk-11/bin

if [ ! -x "$JDK/javac" ]; then
  echo "[*] installing toolchain"
  mkdir -p "$T" && cd "$T" && npm init -y >/dev/null 2>&1
  npm install --silent jdk11 @drxiaozhi/minapk aaptjs3 >/dev/null 2>&1
  mkdir -p jdkx && cat node_modules/jdk11/jdk11.tar.* | tar -xf - -C jdkx
  mkdir -p mtw
  cp node_modules/aaptjs3/bin/x64/linux/aapt2 mtw/ && chmod +x mtw/aapt2
  cp node_modules/@drxiaozhi/minapk/tools/{d8.jar,apksigner.jar,android.jar} mtw/
fi

if [ ! -f "$T/mtw/m3/guava.jar" ]; then
  echo "[*] downloading media3 + guava from GitHub mirrors"
  mkdir -p "$T/mtw/m3"
  cp "$P/tools/ghfetch.py" "$T/ghfetch.py"
  (cd "$T" && python3 ghfetch.py)
fi

if [ ! -f "$T/mtw/m3s/guava.jar" ]; then
  echo "[*] stripping MethodParameters (D8 8.2.2 chokes on it)"
  mkdir -p "$T/m3c" && cd "$T/m3c"
  for j in "$T"/mtw/m3/*/*.jar "$T"/mtw/m3/*.jar; do
    n=$(basename "$j" .jar); mkdir -p "$n" && (cd "$n" && unzip -q -o "$j")
  done
  cp "$P/tools/stripattr.py" "$T/stripattr.py"
  python3 "$T/stripattr.py" "$T/m3c"
  mkdir -p "$T/mtw/m3s"
  python3 - "$T/m3c" "$T/mtw/m3s" <<'PY'
import os, sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)
for n in sorted(os.listdir(src)):
    d = os.path.join(src, n)
    if not os.path.isdir(d):
        continue
    out = os.path.join(dst, n + ".jar")
    if os.path.exists(out):
        os.remove(out)
    z = zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED)
    for dp, _, fs in os.walk(d):
        for f in fs:
            p = os.path.join(dp, f)
            z.write(p, os.path.relpath(p, d))
    z.close()
PY
fi

echo "[*] aapt2 compile+link"
rm -rf "$T/w" && mkdir -p "$T/w/gen" "$T/w/classes" "$T/w/dex"
cd "$P"
[ -f libs/arm64-v8a/libwtcodec.so ] && cp -f libs/arm64-v8a/libwtcodec.so assets/libwtcodec.so
"$T/mtw/aapt2" compile --dir res -o "$T/w/res.zip"
"$T/mtw/aapt2" link -o "$T/w/base.apk" -I "$T/mtw/android.jar" \
  --manifest AndroidManifest.xml -A assets --java "$T/w/gen" \
  --min-sdk-version 24 --target-sdk-version 36 \
  --version-code "$VC" --version-name "$VN" "$T/w/res.zip"

echo "[*] javac"
M3="$T/mtw/m3/media3-exoplayer/media3-exoplayer.jar"
for m in media3-exoplayer-hls media3-extractor media3-datasource media3-common media3-decoder media3-container; do
  M3="$M3:$T/mtw/m3/$m/$m.jar"
done
M3="$M3:$T/mtw/m3/guava.jar:$T/mtw/m3/failureaccess.jar"
find src "$T/w/gen" -name "*.java" > "$T/w/s.txt"
"$JDK/javac" -encoding UTF-8 -nowarn -source 8 -target 8 \
  -bootclasspath "$T/mtw/android.jar" -classpath "$M3" -d "$T/w/classes" @"$T/w/s.txt"
cp "$T/stripattr.py" /tmp/_sa.py && python3 /tmp/_sa.py "$T/w/classes"

echo "[*] d8"
"$JDK/java" -Xmx1100m -cp "$T/mtw/d8.jar" com.android.tools.r8.D8 --release --min-api 24 \
  --lib "$T/mtw/android.jar" --output "$T/w/dex" \
  $(find "$T/w/classes" -name "*.class") \
  "$T/mtw/m3s/media3-exoplayer.jar" "$T/mtw/m3s/media3-exoplayer-hls.jar" \
  "$T/mtw/m3s/media3-extractor.jar" "$T/mtw/m3s/media3-datasource.jar" \
  "$T/mtw/m3s/media3-common.jar" "$T/mtw/m3s/media3-decoder.jar" \
  "$T/mtw/m3s/media3-container.jar" "$T/mtw/m3s/guava.jar" "$T/mtw/m3s/failureaccess.jar"

echo "[*] package"
cd "$T/w" && cp dex/classes.dex . && zip -q base.apk classes.dex
if ls "$P"/libs/*/lib*.so >/dev/null 2>&1; then
  rm -rf nat && mkdir -p nat/lib && cp -r "$P/libs/." nat/lib/
  (cd nat && zip -q "$T/w/base.apk" \
    $(cd lib && find . -name '*.so' -o -name '*.so.*' | sed 's|^\./|lib/|'))
fi
cp "$P/tools/zipalign.py" /tmp/_za.py && python3 /tmp/_za.py base.apk aligned.apk

echo "[*] sign"
OUT="$P/apk/Wtube_v$VN.apk"
rm -f "$OUT"
"$JDK/java" -jar "$T/mtw/apksigner.jar" sign \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --min-sdk-version 24 --ks "$P/debug.keystore" --ks-key-alias mytube \
  --ks-pass pass:mytube2026 --key-pass pass:mytube2026 --out "$OUT" aligned.apk
"$JDK/java" -jar "$T/mtw/apksigner.jar" verify "$OUT"
echo "[✓] $OUT"
