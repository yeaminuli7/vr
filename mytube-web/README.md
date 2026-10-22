# Wtube 2.0 — সোর্স কোড

YouTube-ফ্রন্ট-এন্ড Android-অ্যাপ (ওয়েব-ব্রাউজিং) + সম্পূর্ণ নিজস্ব প্লেয়ার-ইঞ্জিন
(ExoPlayer/Media3) + নিজস্ব ডাউনলোডার (মাল্টি-চাংক, Pause/Resume, MP3/SRT-কনভার্ট)।

- **প্যাকেজ:** `com.mytube.web` · **বর্তমান ভার্সন:** 2.0 (versionCode 77)
- **বিল্ড-ফাইল:** `Wtube_v2.0.apk`-এর সমান কোড-অবস্থা

## ফোল্ডার-ম্যাপ

| পথ | কী আছে |
|---|---|
| `src/com/mytube/web/` | সব Java-সোর্স (১৬টি ফাইল) |
| `assets/inject.js` | WebView-এ চলা রিব্র্যান্ডিং/বেল/শর্টস-ফিক্স/অ্যাকাউন্ট-স্ক্যান স্ক্রিপ্ট |
| `res/` | আইকন (mipmap), থিম-রং (values) |
| `libs/arm64-v8a/libwtcodec.so` | প্রিবিল্ট নেটিব কোডেক-স্টাব (arm64) |
| `tools/ffmpeg`, `tools/lame` | নেটিব-স্টাবের JNI C-সোর্স (রেফারেন্স) |
| `build.sh` | বিল্ড-স্ক্রিপ্ট |
| `DEVLOG.md` | ভার্সন-ধরে-ধরে সম্পূর্ণ উন্নয়ন-ইতিহাস (v1.16 → 2.0) |
| `ROADMAP.md` | ফিচার-বিশ্লেষণ + ভবিষ্যৎ-পরিকল্পনা |

## মূল Java-ফাইল

| ফাইল | দায়িত্ব |
|---|---|
| `MainActivity.java` | ওয়েব-ব্রাউজিং, নেভ-বার, Library/Profile/Settings, অফলাইন-স্ক্রিন, ব্যাকআপ |
| `PlayerActivity.java` | ভিডিও-প্লেয়ার UI: নিয়ন্ত্রণ, জেসচার, কোয়ালিটি, ক্যাপশন, চ্যাপ্টার, SponsorBlock-স্কিপ, ইকুয়ালাইজার, অটোপ্লে-নেক্সট |
| `PlayerService.java` | ব্যাকগ্রাউন্ড-প্লে + নোটিফিকেশন + Next/Prev-কিউ |
| `DownloadService.java` | ডাউনলোড-ইঞ্জিন: মাল্টি-চাংক প্যারালাল, পজ/রিজিউম, MP3/SRT, হিস্ট্রি |
| `Resolver.java` | স্ট্রিম-রিজলভার (ইন/অডিও/ভ্যারিয়েন্ট/ক্যাপশন/রিলেটেড/কমেন্ট), SponsorBlock+ডিসলাইক API |
| `DownloadsSheet.java` | ডাউনলোড-স্ক্রিন (সেকশন: Active/Completed/Playlists/Watch Later/History) |
| `Library.java` | Watch Later, প্লেলিস্ট, দেখা-ইতিহাস স্টোর (SharedPreferences "mtw") |
| `AdBlock.java` | অ্যাড/ট্র্যাকার-ডোমেইন ব্লক |
| `StreamStore.java` | googlevideo-URL ক্যাপচার + ডায়াগনস্টিকস |
| `VttSrt.java` | VTT→SRT কনভার্টার |
| `NativeLoader/Ffmpeg/Lame/BgService/NavTab/CrashHandler` | নেটিব-লোডার, কোডেক-স্টাব-বাইন্ডিং, প্লে-রিস্টার্ট, নেভ-ট্যাব, ক্র্যাশ-লগ |

## কীভাবে বিল্ড করবে

```bash
./build.sh Wtube_v2.0.apk 77 2.0
```

প্রয়োজন: Linux + JDK (javac) + curl + unzip। বাকি সব (android.jar, aapt2, apksigner,
media3-লাইব্রেরি) স্ক্রিপ্টটি নিজেই Google-ভাণ্ডার থেকে /tmp/mtw-tools-এ নামিয়ে নেয়।
নেটিব .so প্রিবিল্ট থাকায় NDK লাগে না (পুনর্নির্মাণ করতে চাইলে tools/-এর C-সোর্স দেখো)।

## ভার্সন-রীতি

`build.sh [আউটপুট.apk] [versionCode] [versionName]` — পরবর্তী বিল্ড v2.01 (vc 78)।
APK-নাম-প্যাটার্ন: `Wtube_vX.apk`। প্রতি ভার্সনের বিস্তারিত DEVLOG.md-তে।

## নোট

- ইউটিউব-ওয়েব ব্লক-এড়ানোর কৌশলগুলো (UA, ইনজেকশন, রিজলভার-এন্ডপয়েন্ট) প্রায়ই বদলায় —
  ভেঙে গেলে Resolver.java আর assets/inject.js-ই প্রথম দেখার জায়গা।
- এটি শিক্ষামূলক/ব্যক্তিগত ব্যবহারের প্রকল্প; YouTube-এর শর্তের সাথে নিজ দায়িত্বে ব্যবহার করো।
