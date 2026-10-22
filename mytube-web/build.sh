#!/usr/bin/env bash
# MyTube Web builder
# টুল না থাকলে /tmp-এ নামিয়ে নেয়, তারপর কম্পাইল + প্যাকেজ + সাইন।
# ব্যবহার: ./build.sh [আউটপুট.apk] [versionCode] [versionName]
set -e
T=/tmp/mtw-tools
P=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-/home/user/MyTubeWeb_v0.1.apk}
VC=${2:-1}
VN=${3:-0.1}
mkdir -p "$T"

fetch() { # fetch <output> <url-1> [url-2] ...
    local o=$1; shift
    if [ ! -s "$o" ]; then
        for u in "$@"; do
            echo "[*] নামাচ্ছি: $u"
            if curl -fsSL -o "$o" "$u" && [ -s "$o" ]; then
                return 0
            fi
            rm -f "$o"
        done
        echo "✗ ডাউনলোড ফেইল"; exit 1
    fi
}

if [ ! -f "$T/platform.jar" ]; then
    fetch "$T/p.zip" \
        "https://dl.google.com/android/repository/platform-36_r01.zip" \
        "https://dl.google.com/android/repository/platform-36_r02.zip" \
        "https://dl.google.com/android/repository/platform-35_r02.zip"
    unzip -q -o "$T/p.zip" -d "$T/p"
    find "$T/p" -name android.jar -exec cp {} "$T/platform.jar" \;
    rm -rf "$T/p" "$T/p.zip"
fi

if [ ! -x "$T/aapt2" ]; then
    fetch "$T/b.zip" \
        "https://dl.google.com/android/repository/build-tools_r36-linux.zip" \
        "https://dl.google.com/android/repository/build-tools_r36.0.0-linux.zip" \
        "https://dl.google.com/android/repository/build-tools_r34-linux.zip"
    unzip -q -o "$T/b.zip" -d "$T/b"
    cp "$T"/b/*/aapt2 "$T/aapt2" && chmod +x "$T/aapt2"
    rm -rf "$T/b" "$T/b.zip"
fi

if [ ! -f "$T/r8.jar" ]; then
    fetch "$T/r8.jar" \
        "https://dl.google.com/android/maven2/com/android/tools/r8/9.4.26/r8-9.4.26.jar" \
        "https://dl.google.com/android/maven2/com/android/tools/r8/3.3.75/r8-3.3.75.jar"
fi

# Media3 (ExoPlayer) — প্লেয়ার-ইঞ্জিন লাইব্রেরি
M3V=1.11.1
M3MODS="media3-exoplayer media3-exoplayer-hls media3-extractor media3-datasource media3-common media3-decoder media3-container"
for m in $M3MODS; do
    if [ ! -f "$T/m3/$m/classes.jar" ]; then
        mkdir -p "$T/m3/$m"
        fetch "$T/m3/$m.aar" \
            "https://dl.google.com/android/maven2/androidx/media3/$m/$M3V/$m-$M3V.aar"
        unzip -q -o "$T/m3/$m.aar" classes.jar -d "$T/m3/$m"
    fi
done
if [ ! -f "$T/m3/annotation.jar" ]; then
    fetch "$T/m3/annotation.jar" \
        "https://dl.google.com/android/maven2/androidx/annotation/annotation/1.9.1/annotation-1.9.1.jar"
fi
if [ ! -f "$T/m3/guava.jar" ]; then
    fetch "$T/m3/guava.jar" \
        "https://repo1.maven.org/maven2/com/google/guava/guava/33.3.1-android/guava-33.3.1-android.jar"
    fetch "$T/m3/failureaccess.jar" \
        "https://repo1.maven.org/maven2/com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar"
fi
M3CP="$T/m3/annotation.jar:$T/m3/guava.jar:$T/m3/failureaccess.jar"
for m in $M3MODS; do
    M3CP="$M3CP:$T/m3/$m/classes.jar"
done
M3JARS="$T/m3/annotation.jar $T/m3/guava.jar $T/m3/failureaccess.jar"
for m in $M3MODS; do
    M3JARS="$M3JARS $T/m3/$m/classes.jar"
done

# আইকন না থাকলে বানাও
if [ ! -f "$P/res/mipmap-xxxhdpi/ic_launcher.png" ]; then
    python3 "$P/tools/mkicon.py"
fi

# নেটিভ-লাইব্রেরি assets-এও (NativeLoader-ফলব্যাক; ইনস্টলার-নির্ভরতা বাদ)
if [ -f "$P/libs/arm64-v8a/libwtcodec.so" ]; then
    mkdir -p "$P/assets"
    cp -f "$P/libs/arm64-v8a/libwtcodec.so" "$P/assets/libwtcodec.so"
fi

rm -rf "$T/w" && mkdir -p "$T/w/gen" "$T/w/classes" "$T/w/dex"
cd "$P"

echo "[*] aapt2 compile+link"
"$T/aapt2" compile --dir res -o "$T/w/res.zip"
"$T/aapt2" link -o "$T/w/base.apk" -I "$T/platform.jar" \
    --manifest AndroidManifest.xml -A assets --java "$T/w/gen" \
    --min-sdk-version 24 --target-sdk-version 36 \
    --version-code "$VC" --version-name "$VN" \
    "$T/w/res.zip"

echo "[*] javac"
find src "$T/w/gen" -name "*.java" > "$T/w/s.txt"
javac -encoding UTF-8 -nowarn -classpath "$T/platform.jar:$M3CP" -d "$T/w/classes" @"$T/w/s.txt"

echo "[*] d8"
java -Xmx700m -cp "$T/r8.jar" com.android.tools.r8.D8 --release --min-api 24 \
    --lib "$T/platform.jar" --output "$T/w/dex" \
    $(find "$T/w/classes" -name "*.class") $M3JARS

cd "$T/w/dex"
ls classes*.dex > /dev/null 2>&1 || { echo "✗ dex নেই"; exit 1; }
zip -q "$T/w/base.apk" classes*.dex

# নেটিভ-লাইব্রেরি (যেমন liblamejni.so) → lib/<abi>/
if ls "$P"/libs/*/lib*.so >/dev/null 2>&1; then
    echo "[*] native libs"
    rm -rf "$T/w/nat" && mkdir -p "$T/w/nat/lib"
    cp -r "$P/libs/." "$T/w/nat/lib/"
    (cd "$T/w/nat" && zip -q "$T/w/base.apk" \
        $(cd lib && find . -name '*.so' -o -name '*.so.*' | sed 's|^\./|lib/|'))
fi

echo "[*] সাইন"
cp "$T/w/base.apk" "$T/unsigned.apk"
java -jar /home/user/patchwork/uber-signer.jar -a "$T/unsigned.apk" --allowResign \
    --ks /home/user/patchwork/webtube.p12 --ksAlias mytube --ksPass mytube2026 \
    --ksKeyPass mytube2026 --ksType PKCS12 > /dev/null
rm -f "$OUT"
if [ -f "$T/unsigned-aligned-signed.apk" ]; then
    mv "$T/unsigned-aligned-signed.apk" "$OUT"
else
    mv "$T/unsigned-signed.apk" "$OUT"
fi
rm -f "$T/unsigned.apk.idsig"
echo "[✓] $OUT"

# Publish: keep a copy in apk/, which is committed and pushed so every build
# is retrievable from GitHub.  Never use build/ - it is excluded from
# workspace snapshots and files written there are silently discarded.
mkdir -p "$P/apk"
cp -f "$OUT" "$P/apk/$(basename "$OUT")"
echo "[✓] $P/apk/$(basename "$OUT")"
echo "[!] Commit + push apk/$(basename "$OUT") so this build is on GitHub."
