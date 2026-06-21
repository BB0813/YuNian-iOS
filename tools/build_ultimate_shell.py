#!/usr/bin/env python3
"""
build_ultimate_shell.py — 一键构建极简壳 APK

流水线:
  1. Gradle assembleDebug → 全量 debug APK
  2. 提取全部业务 DEX → assets/shell/
  3. javac 编译纯 Java 壳类 → d8 转 DEX (6KB)
  4. apktool 解码 → 编辑 manifest → 重打包
  5. 替换 classes.dex + hardened SOs
  6. 签名

产出: app/build/outputs/apk/release/app-release.apk
"""

import sys, os, subprocess, shutil, zipfile, re, zlib
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
SDK = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"
BT = max((SDK / "build-tools").glob("*"))
ANDROID_JAR = SDK / "platforms" / "android-35" / "android.jar"
D8 = BT / "d8.bat"
APKSIGNER = BT / "apksigner.bat"
APKTOOL = PROJECT / "tools" / "apktool.jar"
KEYSTORE = PROJECT / "release.keystore"
KS_PASS = "3498762309"
KS_ALIAS = "your_alias"

SHELL_SRC = PROJECT / "app/build/tmp/ultimate_shell/src"
BUILD_DIR = PROJECT / "app/build/tmp/ultimate_shell/build"
DEX_OUT = PROJECT / "app/build/tmp/ultimate_shell/dex_final"
SHELL_DECODED = PROJECT / "app/build/tmp/shell_decoded"
ASSETS_SHELL = SHELL_DECODED / "assets" / "shell"
SO_DIR = PROJECT / "core/security/build/intermediates/cxx/Release"
RELEASE_APK = PROJECT / "app/build/outputs/apk/release/app-release.apk"
DEBUG_APK = PROJECT / "app/build/outputs/apk/debug/app-debug.apk"


def run(cmd, desc=""):
    print(f"  {desc}...", end=" ", flush=True)
    if isinstance(cmd, str):
        result = subprocess.run(cmd, shell=True, capture_output=True, text=True, cwd=str(PROJECT))
    else:
        result = subprocess.run(cmd, capture_output=True, text=True, cwd=str(PROJECT))
    if result.returncode != 0:
        print(f"FAILED")
        print(result.stderr[-500:])
        sys.exit(1)
    print("OK")
    return result


def phase1_gradle():
    """Build debug APK with Gradle (includes NDK hardened SOs)."""
    print("\n═══ Phase 1: Gradle assembleDebug ═══")
    run('cmd.exe /c "gradlew.bat assembleDebug --no-daemon -q"', "Gradle debug build")
    assert DEBUG_APK.exists(), f"Debug APK not found: {DEBUG_APK}"


def phase2_extract_dex():
    """Extract all business DEX files to assets/shell/."""
    print("\n═══ Phase 2: Extract business DEX ═══")
    ASSETS_SHELL.mkdir(parents=True, exist_ok=True)
    count = 0
    with zipfile.ZipFile(DEBUG_APK, 'r') as z:
        for f in sorted(z.namelist()):
            if f.endswith('.dex'):
                data = z.read(f)
                name = f.replace('/', '_')
                (ASSETS_SHELL / name).write_bytes(data)
                count += 1
    print(f"  Extracted {count} business DEX files → assets/shell/")


def phase3_compile_shell():
    """javac → d8: produce ~6KB shell DEX."""
    print("\n═══ Phase 3: Compile shell DEX ═══")
    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    shutil.rmtree(BUILD_DIR, ignore_errors=True)
    BUILD_DIR.mkdir()

    java_files = list(SHELL_SRC.rglob("*.java"))
    run(["javac", "-cp", str(ANDROID_JAR), "-d", str(BUILD_DIR)] + [str(f) for f in java_files], "javac")

    # Skip MainActivity stub — business DEX provides it
    for stub in BUILD_DIR.rglob("MainActivity*.class"):
        stub.unlink()
        print("  Removed MainActivity stub")

    jar_path = BUILD_DIR / "shell.jar"
    run(f'cd "{BUILD_DIR}" && jar cf "{jar_path}" .', "jar")

    DEX_OUT.mkdir(parents=True, exist_ok=True)
    shutil.rmtree(DEX_OUT, ignore_errors=True)
    DEX_OUT.mkdir()
    run([str(D8), "--lib", str(ANDROID_JAR), "--release", "--output", str(DEX_OUT), str(jar_path)], "d8")

    dex = DEX_OUT / "classes.dex"
    size = dex.stat().st_size
    compressed = len(zlib.compress(dex.read_bytes(), 9))
    print(f"  Shell DEX: {size}B (~{compressed}B compressed)")


def phase4_apktool():
    """Decode debug APK, edit manifest, repack."""
    print("\n═══ Phase 4: apktool manifest edit ═══")
    shutil.rmtree(SHELL_DECODED, ignore_errors=True)
    run(["java", "-jar", str(APKTOOL), "d", "-f", "-o", str(SHELL_DECODED), str(DEBUG_APK)], "apktool decode")

    manifest = SHELL_DECODED / "AndroidManifest.xml"
    mf = manifest.read_text(encoding='utf-8')

    # Remove all ContentProviders (crash before attachBaseContext)
    mf = re.sub(r'<provider[^>]*>.*?</provider>', '', mf, flags=re.DOTALL)
    mf = re.sub(r'<provider[^/]*/>', '', mf)
    print("  Stripped ContentProviders")

    # Remove appComponentFactory (CoreComponentFactory not in shell DEX)
    mf = re.sub(r'\s+android:appComponentFactory="[^"]*"', '', mf)
    print("  Stripped appComponentFactory")

    # Move LAUNCHER intent-filter from SActivity to MainActivity
    mf = re.sub(
        r'(<activity[^>]*android:name="com\.lianyu\.ai\.security\.SActivity"[^>]*>)\s*<intent-filter>.*?</intent-filter>',
        r'\1', mf, flags=re.DOTALL
    )
    mf = mf.replace(
        '<activity android:exported="false" android:hardwareAccelerated="true" android:launchMode="singleTask" android:name="com.lianyu.ai.MainActivity"',
        '<activity android:exported="true" android:hardwareAccelerated="true" android:launchMode="singleTask" android:name="com.lianyu.ai.MainActivity"'
    )
    # Fix self-closing tag → open/close with intent-filter
    mf = re.sub(
        r'(<activity[^>]*android:name="com\.lianyu\.ai\.MainActivity"[^>]*)/>',
        r'\1>\n            <intent-filter>\n                <action android:name="android.intent.action.MAIN"/>\n                <category android:name="android.intent.category.LAUNCHER"/>\n            </intent-filter>\n        </activity>',
        mf
    )
    print("  Moved LAUNCHER intent → MainActivity")

    # Add meta-data for potential real_app_class override
    if '<meta-data android:name="real_application_class"' not in mf:
        mf = mf.replace('<uses-native-library',
            '<meta-data android:name="real_application_class" android:value="com.lianyu.ai.LianYuApplication"/>\n        <uses-native-library', 1)
    print("  Added real_application_class meta-data")

    manifest.write_text(mf, encoding='utf-8')
    print("  Manifest saved")

    # Repack
    repacked = PROJECT / "app/build/outputs/apk/release/app-release-repacked.apk"
    if repacked.exists():
        repacked.unlink()
    run(["java", "-jar", str(APKTOOL), "b", "-f", "-o", str(repacked), str(SHELL_DECODED)], "apktool repack")


def phase5_assemble():
    """Replace DEX + SOs + sign."""
    print("\n═══ Phase 5: Assemble final APK ═══")
    repacked = PROJECT / "app/build/outputs/apk/release/app-release-repacked.apk"
    shell_dex = DEX_OUT / "classes.dex"
    shell_data = shell_dex.read_bytes()

    # Find SO dir (latest build)
    so_dirs = sorted(SO_DIR.glob("*/obj/local"))
    so_base = so_dirs[-1] if so_dirs else SO_DIR

    tmp = str(RELEASE_APK) + '.tmp'
    replaced = 0
    with zipfile.ZipFile(repacked, 'r') as zin:
        with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename == 'classes.dex':
                    data = shell_data
                    replaced += 1
                elif item.filename.startswith('META-INF/'):
                    continue
                elif item.filename.startswith('classes') and item.filename.endswith('.dex'):
                    continue
                else:
                    for abi in ['arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86']:
                        for soname in ['liblianyu_shell.so', 'liblianyu_security.so', 'liblianyu_dex2c.so']:
                            if item.filename == f'lib/{abi}/{soname}':
                                src = so_base / abi / soname
                                if src.exists():
                                    data = src.read_bytes()
                                    replaced += 1
                                break
                        else:
                            continue
                        break
                zout.writestr(item, data)

    shutil.move(tmp, str(RELEASE_APK))
    print(f"  Replaced {replaced} files")

    # Sign
    signed = str(RELEASE_APK).replace('.apk', '-signed.apk')
    run([
        'cmd', '/c', str(APKSIGNER), 'sign',
        '--ks', str(KEYSTORE), '--ks-pass', f'pass:{KS_PASS}',
        '--key-pass', f'pass:{KS_PASS}', '--ks-key-alias', KS_ALIAS,
        '--out', signed, str(RELEASE_APK)
    ], "Sign")
    shutil.move(signed, str(RELEASE_APK))

    size_mb = RELEASE_APK.stat().st_size / 1024 / 1024
    print(f"\n✅ Final APK: {size_mb:.1f}MB")
    print(f"   Shell DEX: {len(shell_data)}B pure Java, zero Kotlin")
    print(f"   Path: {RELEASE_APK}")


def main():
    print("=" * 60)
    print("LianYu Ultimate Shell — 一键构建")
    print("=" * 60)

    if not APKTOOL.exists():
        print("❌ apktool.jar not found. Download from: https://github.com/iBotPeaches/Apktool/releases")
        print("   Save as: tools/apktool.jar")
        sys.exit(1)

    phase1_gradle()
    phase2_extract_dex()
    phase3_compile_shell()
    phase4_apktool()
    phase5_assemble()

    print("\n" + "=" * 60)
    print("Build complete. Install:")
    print(f"  adb install -r \"{RELEASE_APK}\"")
    print("=" * 60)


if __name__ == "__main__":
    main()
