#!/usr/bin/env python3
"""Fetch the build dependencies from GitHub mirrors.

dl.google.com and repo1.maven.org are both unreachable from the sandbox, so
media3 and guava are pulled from GitHub repos that commit the artifacts:

  media3 1.11.1  LineageOS/android_packages_apps_Twelve  app/libs/androidx/media3
  guava 31.1     eyasuyuki/keydriver                     lib/com/google/guava

Written to ./mtw/m3/ relative to the current directory.
"""
import base64, os, subprocess, sys

MEDIA3_REPO = "LineageOS/android_packages_apps_Twelve"
MODULES = ["media3-exoplayer", "media3-exoplayer-hls", "media3-extractor",
           "media3-datasource", "media3-common", "media3-decoder", "media3-container"]


def api(path, jq=None):
    cmd = ["gh", "api", path]
    if jq:
        cmd += ["--jq", jq]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip()[:300])
    return r.stdout.strip()


def download(repo, remote_path, local_path):
    meta = api("repos/%s/contents/%s" % (repo, remote_path),
               jq='.sha + " " + (.size|tostring)')
    sha, size = meta.split()
    os.makedirs(os.path.dirname(local_path) or ".", exist_ok=True)
    blob = api("repos/%s/git/blobs/%s" % (repo, sha), jq=".content")
    with open(local_path, "wb") as f:
        f.write(base64.b64decode(blob))
    got = os.path.getsize(local_path)
    ok = got == int(size)
    print("  %-30s %8d B  %s" % (os.path.basename(local_path), got,
                                 "OK" if ok else "SIZE MISMATCH"))
    return ok


def main():
    ok = True
    for m in MODULES:
        ok &= download(MEDIA3_REPO,
                       "app/libs/androidx/media3/%s/1.11.1/%s-1.11.1.aar" % (m, m),
                       "mtw/m3/%s.aar" % m)
    ok &= download("eyasuyuki/keydriver",
                   "lib/com/google/guava/guava/31.1-jre/guava-31.1-jre.jar",
                   "mtw/m3/guava.jar")
    ok &= download("eyasuyuki/keydriver",
                   "lib/com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar",
                   "mtw/m3/failureaccess.jar")
    print("  extracting classes.jar from each AAR")
    for m in MODULES:
        d = "mtw/m3/%s" % m
        os.makedirs(d, exist_ok=True)
        subprocess.run(["unzip", "-q", "-o", "mtw/m3/%s.aar" % m, "classes.jar", "-d", d],
                       check=False)
        src = os.path.join(d, "classes.jar")
        if os.path.exists(src):
            os.replace(src, os.path.join(d, "%s.jar" % m))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
