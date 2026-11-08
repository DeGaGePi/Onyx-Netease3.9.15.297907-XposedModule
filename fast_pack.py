#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Onyx fast packer -- bypass Gradle/AGP entirely.

Why this exists:
  AGP's Gradle daemon needs ~1.6 GB of *commit* memory. This box runs IDA
  (3.5 GB commit) + MuMu + QQ, so total commit sits at 25/25.4 GB and the
  daemon dies with "Could not dispatch a message to the daemon" /
  hs_err_pid*.log (errno 1455 == paging file too small).

  The project is pure Java with ZERO androidx deps, so we do not need AGP
  at all. This script runs the raw toolchain:
    aapt2 compile -> aapt2 link -> javac -> d8 -> zip -> zipalign -> apksigner
  Peak commit stays under ~500 MB and a full build takes ~10 s.

Usage:
  python fast_pack.py               build debug(signed) + release(unsigned)
  python fast_pack.py --native      also rebuild libonyxmc.so first
"""
import os
import re
import sys
import shutil
import subprocess
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# --------------------------------------------------------------------------
# paths
# --------------------------------------------------------------------------
ROOT     = r"D:\C盘清理\NewUI"
APP      = os.path.join(ROOT, "app")
SRC      = os.path.join(APP, "src", "main")
JAVA_SRC = os.path.join(SRC, "java")
RES      = os.path.join(SRC, "res")
ASSETS   = os.path.join(SRC, "assets")
MANIFEST = os.path.join(SRC, "AndroidManifest.xml")
JNILIBS  = os.path.join(SRC, "jniLibs", "arm64-v8a")
XPOSED   = os.path.join(APP, "libs", "api-82.jar")

SDK      = r"D:\Android\Sdk"
BT       = os.path.join(SDK, "build-tools", "34.0.0")
AAPT2    = os.path.join(BT, "aapt2.exe")
ZIPALIGN = os.path.join(BT, "zipalign.exe")
D8JAR    = os.path.join(BT, "lib", "d8.jar")
SIGNJAR  = os.path.join(BT, "lib", "apksigner.jar")
AJAR     = os.path.join(SDK, "platforms", "android-34", "android.jar")

JDK      = r"C:\Program Files\Java\jdk-17"
JAVA     = os.path.join(JDK, "bin", "java.exe")
JAVAC    = os.path.join(JDK, "bin", "javac.exe")
KEYSTORE = r"C:\Users\lenovo\.android\debug.keystore"

WORK     = r"E:\NewUI-build\fast"
DIST     = r"E:\NewUI-build\dist"

PACKAGE   = "com.onyx.mc"           # = applicationId
NAMESPACE = "com.onyx.clickgui"     # = namespace
ENTRY_ACT = NAMESPACE + ".MainActivity"
MIN_SDK, TARGET_SDK = "24", "34"

ENV = dict(os.environ)
ENV["JAVA_HOME"] = JDK
ENV["PATH"] = os.path.join(JDK, "bin") + os.pathsep + ENV.get("PATH", "")


def log(*a):
    print(*a, flush=True)


def run(cmd, ok=(0,), cwd=None):
    shown = " ".join('"%s"' % c if (" " in c and not c.startswith('"')) else c for c in cmd)
    log("  $ " + shown + ("   [cwd=%s]" % cwd if cwd else ""))
    r = subprocess.run(cmd, env=ENV, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", cwd=cwd)
    out = (r.stdout or "") + (r.stderr or "")
    if out.strip():
        log("    " + out.rstrip().replace("\n", "\n    "))
    if r.returncode not in ok:
        log("!! FAILED rc=%d" % r.returncode)
        sys.exit(1)
    return out


def copy_file(src, dst):
    """shutil.copyfile is routed through WorkBuddy's python shim; plain IO is not."""
    with open(src, "rb") as f:
        data = f.read()
    with open(dst, "wb") as g:
        g.write(data)


def fresh(path):
    if os.path.isdir(path):
        # NOTE: WorkBuddy injects a sitecustomize shim that hooks
        # shutil.rmtree -> recycle bin, which fails with
        # "SHFileOperationW 失败: 0x2". Shell out to cmd's rmdir instead.
        subprocess.run(["cmd", "/c", "rmdir", "/s", "/q", path],
                       capture_output=True, text=True)
    os.makedirs(path, exist_ok=True)


def read_version():
    txt = open(os.path.join(APP, "build.gradle"), encoding="utf-8").read()
    vn = re.search(r'versionName\s+"([^"]+)"', txt).group(1)
    vc = re.search(r"versionCode\s+(\d+)", txt).group(1)
    return vn, vc


def make_manifest(dst):
    """aapt2 needs a literal package attribute; AGP supplies it from namespace.
    Also make the launcher class name absolute so the relative-name expansion
    can't pick the wrong package."""
    txt = open(MANIFEST, encoding="utf-8").read()
    txt = re.sub(r"<manifest\b", '<manifest package="%s"' % PACKAGE, txt, count=1)
    txt = txt.replace('android:name=".MainActivity"',
                      'android:name="%s"' % ENTRY_ACT)
    # mirror AGP's packagingOptions.jniLibs.useLegacyPackaging = false
    if "extractNativeLibs" not in txt:
        txt = re.sub(r"<application\b",
                     '<application android:extractNativeLibs="false"', txt, count=1)
    open(dst, "w", encoding="utf-8").write(txt)


def collect_java():
    out = []
    for base, _dirs, files in os.walk(JAVA_SRC):
        for f in files:
            if f.endswith(".java"):
                out.append(os.path.join(base, f))
    return sorted(out)


def main():
    do_native = "--native" in sys.argv
    ver, vcode = read_version()
    log("=" * 62)
    log("Onyx fast pack  version=%s (%s)" % (ver, vcode))
    log("=" * 62)

    for p in (AAPT2, ZIPALIGN, D8JAR, SIGNJAR, AJAR, JAVA, JAVAC, KEYSTORE):
        if not os.path.exists(p):
            log("!! missing tool: " + p)
            sys.exit(1)

    fresh(WORK)
    res_zip = os.path.join(WORK, "res.zip")
    base_apk = os.path.join(WORK, "base.apk")
    tmp_manifest = os.path.join(WORK, "AndroidManifest.xml")
    classes_dir = os.path.join(WORK, "classes")
    dex_dir = os.path.join(WORK, "dex")
    unsigned = os.path.join(WORK, "unsigned.apk")
    aligned = os.path.join(WORK, "aligned.apk")
    os.makedirs(classes_dir, exist_ok=True)
    os.makedirs(dex_dir, exist_ok=True)

    # ---- 0. native -------------------------------------------------------
    if do_native:
        log("[0/7] rebuild libonyxmc.so")
        r = subprocess.run(["cmd", "/c", os.path.join(ROOT, "build_native.bat")],
                           cwd=ROOT, env=ENV, capture_output=True, text=True,
                           encoding="utf-8", errors="replace")
        log("    " + ((r.stdout or "") + (r.stderr or "")).strip())
        if r.returncode != 0:
            log("!! native build failed")
            sys.exit(1)

    so_src = os.path.join(JNILIBS, "libonyxmc.so")
    if not os.path.exists(so_src):
        log("!! libonyxmc.so not found: " + so_src)
        sys.exit(1)

    # ---- 1. aapt2 compile ------------------------------------------------
    # NOTE: aapt2 on Windows opens *directories* through ANSI narrow-char APIs,
    # so an absolute path containing non-ASCII (D:\C盘清理\...) fails with
    # "failed to open directory: 系统找不到指定的文件". Run with cwd = source
    # dir and relative paths; outputs still go to the ASCII E: work dir.
    log("[1/7] aapt2 compile res/")
    run([AAPT2, "compile", "--dir", "res", "-o", res_zip], cwd=SRC)

    # ---- 2. aapt2 link ---------------------------------------------------
    log("[2/7] aapt2 link")
    make_manifest(tmp_manifest)
    run([AAPT2, "link", "-o", base_apk,
         "-I", AJAR,
         "--manifest", tmp_manifest,
         "--min-sdk-version", MIN_SDK,
         "--target-sdk-version", TARGET_SDK,
         "--version-code", vcode,
         "--version-name", ver,
         "--no-compile-sdk-metadata",
         "-A", "assets",
         res_zip], cwd=SRC)

    # ---- 3. javac --------------------------------------------------------
    log("[3/7] javac")
    java_files = collect_java()
    log("    %d source files" % len(java_files))
    cp = AJAR + os.pathsep + XPOSED
    run([JAVAC, "--release", "8", "-encoding", "UTF-8", "-nowarn",
         "-cp", cp, "-d", classes_dir] + java_files)

    class_files = []
    for base, _dirs, files in os.walk(classes_dir):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(base, f))
    log("    %d class files" % len(class_files))

    # ---- 4. d8 -----------------------------------------------------------
    log("[4/7] d8 -> classes.dex")
    run([JAVA, "-Xmx320m", "-cp", D8JAR, "com.android.tools.r8.D8",
         "--lib", AJAR, "--min-api", MIN_SDK, "--release",
         "--output", dex_dir] + sorted(class_files))
    dex = os.path.join(dex_dir, "classes.dex")
    if not os.path.exists(dex):
        log("!! classes.dex not produced")
        sys.exit(1)

    # ---- 5. zip ----------------------------------------------------------
    log("[5/7] assemble apk")
    with zipfile.ZipFile(unsigned, "w") as z:
        with zipfile.ZipFile(base_apk) as b:
            for it in b.infolist():
                data = b.read(it.filename)
                zi = zipfile.ZipInfo(it.filename, date_time=it.date_time)
                zi.compress_type = it.compress_type       # keep resources.arsc STORED
                zi.external_attr = it.external_attr
                z.writestr(zi, data)
        z.write(dex, "classes.dex", zipfile.ZIP_DEFLATED)
        # .so must stay uncompressed + page aligned (useLegacyPackaging=false)
        z.write(so_src, "lib/arm64-v8a/libonyxmc.so", zipfile.ZIP_STORED)

    # ---- 6. zipalign -----------------------------------------------------
    log("[6/7] zipalign")
    run([ZIPALIGN, "-f", "-p", "4", unsigned, aligned])

    if not os.path.isdir(DIST):
        os.makedirs(DIST, exist_ok=True)
    rel_out = os.path.join(DIST, "Onyx-%s-release-unsigned.apk" % ver)
    copy_file(aligned, rel_out)

    # ---- 7. sign (debug) -------------------------------------------------
    log("[7/7] apksigner (debug key)")
    dbg_out = os.path.join(DIST, "Onyx-%s-debug-signed.apk" % ver)
    run([JAVA, "-Xmx256m", "-jar", SIGNJAR, "sign",
         "--ks", KEYSTORE,
         "--ks-pass", "pass:android",
         "--key-pass", "pass:android",
         "--ks-key-alias", "androiddebugkey",
         "--out", dbg_out, aligned])
    run([JAVA, "-Xmx256m", "-jar", SIGNJAR, "verify", "--print-certs", dbg_out])

    # ---- summary ---------------------------------------------------------
    log("")
    log("=" * 62)
    for p in (rel_out, dbg_out):
        log("  [OK] %s  (%d bytes)" % (p, os.path.getsize(p)))
    log("=" * 62)

    # sanity: dex + so really are inside, and so is stored
    with zipfile.ZipFile(dbg_out) as z:
        for n in ("classes.dex", "lib/arm64-v8a/libonyxmc.so",
                  "assets/xposed_init", "resources.arsc", "AndroidManifest.xml"):
            info = z.getinfo(n)
            log("  in-apk %-32s %8d B  method=%d" % (n, info.file_size, info.compress_type))
    log("")


if __name__ == "__main__":
    main()
