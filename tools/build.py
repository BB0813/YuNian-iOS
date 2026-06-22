#!/usr/bin/env python3
"""
LianYu Ultimate Shell — One-Click APK Builder
=============================================
Generates a fully-hardened APK with:
  - 6KB pure-Java shell DEX (zero Kotlin, zero content provider stripping)
  - XOR-encrypted business DEX in assets/shell/
  - dexElements injection (ContentProvider-compatible)
  - Native shell SO with VMP, KMS, maps binding, HW attestation
  - V3 APK signature

Usage:
  python build.py [--release] [--no-build]

Options:
  --release    Build release APK (default: debug)
  --no-build   Skip Gradle build (use existing APK)

Output:
  app/build/outputs/apk/<variant>/LianYu-<variant>.apk
  Desktop: LianYu-Release.apk (release only)
"""

import zipfile
import shutil
import os
import sys
import subprocess
import glob
import argparse

# ═══════════════════════════════════════════════════════════
# Configuration
# ═══════════════════════════════════════════════════════════

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHELL_SRC = os.path.join(PROJECT, "app", "build", "tmp", "ultimate_shell", "src",
                         "com", "lianyu", "ai", "security")
SHELL_WORK = os.path.join(PROJECT, "app", "build", "tmp", "ultimate_shell")
# Key = HMAC-SHA256(cert_SHA256, maps_crc64 || hw_sig(zeros) || label)
# 56-byte salt, matches nativeDeriveDexKey() in dex-extractor.cpp
XOR_KEY = bytes([
    0xe1, 0x74, 0x3e, 0x36, 0x56, 0xb5, 0xd2, 0xc5,
    0x5f, 0xbb, 0x47, 0xab, 0x8a, 0x52, 0x37, 0x17,
    0x04, 0x4a, 0xe0, 0x65, 0xa5, 0x02, 0xc4, 0x3c,
    0xf9, 0x62, 0x04, 0x52, 0x57, 0xc5, 0x82, 0x8b,
])

# Key files to replace in APK
SHELL_SO_FILES = [
    "lib/arm64-v8a/liblianyu_shell.so",
    "lib/x86_64/liblianyu_shell.so",
    "lib/arm64-v8a/liblianyu_security.so",
    "lib/x86_64/liblianyu_security.so",
]

# Keystore paths
DEBUG_KEYSTORE = os.path.join(os.environ.get("USERPROFILE", ""),
                              ".android", "debug.keystore")
RELEASE_KEYSTORE = os.path.join(PROJECT, "release.keystore")

# Android SDK
LOCALAPPDATA = os.environ.get("LOCALAPPDATA", "")
SDK_DIR = os.path.join(LOCALAPPDATA, "Android", "Sdk")
BT = os.path.join(SDK_DIR, "build-tools", "36.0.0")
ANDROID_JAR = os.path.join(SDK_DIR, "platforms", "android-35", "android.jar")


def run(cmd, **kwargs):
    """Run a command, print output, raise on error."""
    print(f"  $ {' '.join(cmd) if isinstance(cmd, list) else cmd}")
    kwargs.setdefault("capture_output", True)
    result = subprocess.run(cmd, check=True, **kwargs)
    if result.stdout:
        for line in result.stdout.decode("utf-8", errors="replace").strip().split("\n")[-3:]:
            print(f"    {line}")
    return result


def phase_shell_dex():
    """Compile shell Java → DEX (pure Java, no Kotlin)."""
    print("\n═══ Phase 1: Shell DEX ═══")

    classes_dir = os.path.join(SHELL_WORK, "classes")
    dex_dir = os.path.join(SHELL_WORK, "dex")

    for d in [classes_dir, dex_dir]:
        if os.path.exists(d):
            shutil.rmtree(d, ignore_errors=True)
        os.makedirs(d, exist_ok=True)

    # Find shell Java files — only the 3 shell source files
    java_files = [
        os.path.join(SHELL_SRC, "StaticApkShell.java"),
        os.path.join(SHELL_SRC, "SActivity.java"),
        os.path.join(SHELL_SRC, "MethodRecoveryEngine.java"),
    ]
    java_files = [f for f in java_files if os.path.exists(f)]

    # 1. javac
    run(["javac", "-cp", ANDROID_JAR, "-d", classes_dir] + java_files, timeout=60)

    # 2. jar
    jar_path = os.path.join(SHELL_WORK, "shell.jar")
    run(["jar", "cf", jar_path, "-C", classes_dir, "."], timeout=10)

    # 3. d8
    d8 = os.path.join(BT, "d8.bat") if os.name == "nt" else os.path.join(BT, "d8")
    run([d8, "--lib", ANDROID_JAR, "--release", "--output", dex_dir, jar_path],
        timeout=30)

    dex_path = os.path.join(dex_dir, "classes.dex")
    size = os.path.getsize(dex_path)
    print(f"  Shell DEX: {size}B")
    return dex_path


def phase_gradle(variant):
    """Build app module with Gradle."""
    print(f"\n═══ Phase 2: Gradle {variant.capitalize()} ═══")

    gradlew = os.path.join(PROJECT, "gradlew.bat")
    task = f"assemble{variant.capitalize()}"

    run([gradlew, task, "--no-daemon", "-q"], timeout=600, shell=True,
        cwd=PROJECT)

    apk_path = os.path.join(PROJECT, "app", "build", "outputs", "apk",
                            variant, f"app-{variant}.apk")
    print(f"  Gradle APK: {os.path.getsize(apk_path)/1024/1024:.1f}MB")
    return apk_path


def phase_encrypt_dex(src_apk, work_dir):
    """Extract business DEX from APK, XOR encrypt, save to assets/shell/."""
    print("\n═══ Phase 3: DEX Encryption ═══")

    output_dir = os.path.join(work_dir, "encrypted")
    if os.path.exists(output_dir):
        shutil.rmtree(output_dir, ignore_errors=True)
    os.makedirs(output_dir, exist_ok=True)

    total_size = 0
    count = 0
    with zipfile.ZipFile(src_apk, "r") as z:
        for name in sorted(z.namelist()):
            if not name.endswith(".dex"):
                continue
            data = bytearray(z.read(name))
            for i in range(len(data)):
                data[i] ^= XOR_KEY[i % len(XOR_KEY)]
            out_name = name.replace("/", "_").replace(".dex", ".dat")
            out_path = os.path.join(output_dir, out_name)
            with open(out_path, "wb") as f:
                f.write(data)
            total_size += len(data)
            count += 1
            print(f"  {name:20s} → {out_name:20s} {len(data)/1024:7.1f}KB")

    print(f"  {count} DEX files, {total_size/1024:.1f}KB total")
    return output_dir, count


def phase_assemble(src_apk, shell_dex, dex_dir, variant, keystore, ks_pass, key_alias, key_pass):
    """Replace shell DEX + SOs + add encrypted DEX → sign."""
    print("\n═══ Phase 4: APK Assembly ═══")

    out_apk = os.path.join(PROJECT, "app", "build", "outputs", "apk",
                           variant, f"LianYu-{variant}.apk")
    tmp_apk = out_apk + ".tmp"

    shell_data = open(shell_dex, "rb").read()

    # Find SO directory — try Debug, Release, and Release variants
    so_base = None
    for v in [variant.capitalize(), "Release", "Debug"]:
        so_dirs = glob.glob(os.path.join(
            PROJECT, "core", "security", "build", "intermediates", "cxx",
            v, "*", "obj", "local"))
        if so_dirs:
            so_base = max(so_dirs, key=os.path.getmtime)
            break
    if not so_base:
        print("  WARNING: No compiled SOs found")

    dex_files = {}
    for f in os.listdir(dex_dir):
        if f.endswith(".dat"):
            full = os.path.join(dex_dir, f)
            dex_files[f"assets/shell/{f}"] = open(full, "rb").read()

    with zipfile.ZipFile(src_apk, "r") as zin:
        with zipfile.ZipFile(tmp_apk, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename == "classes.dex":
                    data = shell_data
                    print(f"  classes.dex → {len(data)}B shell")
                elif item.filename.startswith("META-INF/"):
                    continue  # strip old signature
                elif (item.filename.startswith("classes") and
                      item.filename.endswith(".dex")):
                    continue  # remove R8's multidex stubs
                else:
                    for so in SHELL_SO_FILES:
                        if item.filename == so and so_base:
                            abi_dir = os.path.dirname(so).replace("lib/", "")
                            so_path = os.path.join(so_base, abi_dir, os.path.basename(so))
                            if os.path.exists(so_path):
                                data = open(so_path, "rb").read()
                            break
                zout.writestr(item, data)

            # Add encrypted DEX files
            for name, data in dex_files.items():
                zout.writestr(name, data)
                print(f"  + {name} ({len(data)/1024:.1f}KB)")

    shutil.move(tmp_apk, out_apk)

    # Sign
    apksigner = os.path.join(BT, "apksigner.bat") if os.name == "nt" \
        else os.path.join(BT, "apksigner")
    signed = out_apk.replace(".apk", "-signed.apk")

    run(["cmd", "/c", apksigner, "sign",
         "--ks", keystore,
         "--ks-pass", f"pass:{ks_pass}",
         "--ks-key-alias", key_alias,
         "--key-pass", f"pass:{key_pass}",
         "--out", signed, out_apk])

    shutil.move(signed, out_apk)

    size_mb = os.path.getsize(out_apk) / (1024 * 1024)
    print(f"\n  ✅ {size_mb:.1f}MB → {out_apk}")
    return out_apk


def main():
    parser = argparse.ArgumentParser(description="LianYu One-Click APK Builder")
    parser.add_argument("--release", action="store_true",
                        help="Build release APK (default: debug)")
    parser.add_argument("--no-build", action="store_true",
                        help="Skip Gradle build")
    args = parser.parse_args()

    variant = "release" if args.release else "debug"
    print(f"═══ LianYu Ultimate Shell — {variant.upper()} Build ═══")

    # ── Phase 1: Shell DEX ──
    shell_dex = phase_shell_dex()

    # ── Phase 2: Gradle ──
    if args.no_build:
        gradle_apk = os.path.join(PROJECT, "app", "build", "outputs", "apk",
                                  variant, f"app-{variant}.apk")
        print(f"\n  Skipping Gradle — using {gradle_apk}")
    else:
        gradle_apk = phase_gradle(variant)

    # ── Phase 3: Encrypt DEX ──
    work_dir = os.path.join(PROJECT, "app", "build", "tmp", f"pipeline_{variant}")
    dex_dir, count = phase_encrypt_dex(gradle_apk, work_dir)

    # ── Phase 4: Assemble + Sign ──
    if args.release:
        keystore = RELEASE_KEYSTORE
        ks_pass = "3498762309"
        key_alias = "your_alias"
        key_pass = "3498762309"
    else:
        keystore = DEBUG_KEYSTORE
        ks_pass = "android"
        key_alias = "androiddebugkey"
        key_pass = "android"

    final_apk = phase_assemble(gradle_apk, shell_dex, dex_dir,
                               variant, keystore, ks_pass, key_alias, key_pass)

    # ── Desktop copy (release only) ──
    if args.release:
        desktop = os.path.join(os.environ.get("USERPROFILE", ""),
                               "Desktop", "LianYu-Release.apk")
        shutil.copy(final_apk, desktop)
        print(f"\n  📦 Desktop: {desktop}")

    print(f"\n═══ DONE ═══")
    print(f"  Shell DEX: {os.path.getsize(shell_dex)}B")
    print(f"  Business DEX: {count} files encrypted")
    print(f"  Manifest: intact (ContentProvider-compatible)")
    print(f"  ClassLoader: dexElements injection")


if __name__ == "__main__":
    main()
