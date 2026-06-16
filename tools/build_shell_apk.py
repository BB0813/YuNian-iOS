#!/usr/bin/env python3
"""
build_shell_apk.py — One-shot shell DEX APK builder.

Orchestrates the full pipeline:
  1. Build APK with Gradle (business DEX + framework SOs)
  2. Extract business DEX → SM4-ECB encrypt → generate payload C++
  3. Rebuild SOs with ndk-build (contains encrypted DEX)
  4. Build shell DEX (minimal 3-class DEX, R8 tree-shaken)
  5. Assemble final APK: shell DEX + payload SOs → sign

Prerequisites:
  - ANDROID_HOME or ANDROID_SDK_ROOT set (falls back to /opt/android-sdk)
  - NDK 30.0.14904198 at $ANDROID_HOME/ndk/30.0.14904198
  - apksigner on PATH
  - Java 17+

Usage:
  python3 tools/build_shell_apk.py [--key <32-hex>] [--no-clean]

Output:
  app/build/outputs/apk/release/app-release-shell-signed.apk
"""

import os
import sys
import struct
import shutil
import hashlib
import zipfile
import shlex
import argparse
import tempfile
import subprocess
from pathlib import Path


# ── Configuration ──────────────────────────────────────────────────
PROJECT_ROOT = Path(__file__).resolve().parent.parent
ANDROID_HOME = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "/opt/android-sdk")))
BUILD_TOOLS = ANDROID_HOME / "build-tools" / "35.0.0"
CMDLINE_TOOLS = ANDROID_HOME / "cmdline-tools" / "latest" / "lib"
NDK = ANDROID_HOME / "ndk" / "30.0.14904198"
PLATFORM_JAR = ANDROID_HOME / "platforms" / "android-35" / "android.jar"
R8_JAR = CMDLINE_TOOLS / "r8.jar"
ANNOTATION_JAR = CMDLINE_TOOLS / "annotations.jar"
D8 = BUILD_TOOLS / "d8"
ZIPALIGN = BUILD_TOOLS / "zipalign"
SM4_TOOL = PROJECT_ROOT / "tools" / "sm4_tool"
GEN_PAYLOAD = PROJECT_ROOT / "tools" / "gen_payload_cpp.py"
PAYLOAD_CPP = PROJECT_ROOT / "core" / "security" / "src" / "main" / "cpp" / "g_vmp_payload_data.cpp"
CPP_DIR = PROJECT_ROOT / "core" / "security" / "src" / "main" / "cpp"
SHELL_SRC = PROJECT_ROOT / "shell" / "src" / "main" / "kotlin"
SHELL_OUT = PROJECT_ROOT / "shell" / "build"
ANDROID_MK = CPP_DIR / "Android.mk"
APP_MK = CPP_DIR / "Application.mk"
KOTLIN_STDLIB = None  # resolved at runtime

# Signature
KEYSTORE = PROJECT_ROOT / "release.keystore"
STORE_PASS = os.environ.get("LIANYU_STORE_PASSWORD", "3498762309")
KEY_PASS = os.environ.get("LIANYU_KEY_PASSWORD", "3498762309")
KEY_ALIAS = os.environ.get("LIANYU_KEY_ALIAS", "your_alias")

# Expected shell DEX classes (for verification)
SHELL_CLASSES = [
    "com/lianyu/ai/security/LianYuShellApplication",
    "com/lianyu/ai/security/NativeBridge",
    "com/lianyu/ai/security/OnePieceShellGate",
]


# ── Cert hash (runtime-derived seed) ───────────────────────────────

def get_cert_hash() -> int:
    """Extract signing certificate from keystore, return CRC32 as uint32 seed.
    Same CRC32 computed at runtime via JNI (PackageManager.GET_SIGNING_CERTIFICATES).
    Seed never embedded in binary — derived fresh at build time AND runtime."""
    import re, zlib
    # Export certificate in DER format
    result = subprocess.run(
        ["keytool", "-exportcert", "-keystore", str(KEYSTORE),
         "-storepass", STORE_PASS, "-alias", KEY_ALIAS],
        capture_output=True, check=True
    )
    cert_der = result.stdout
    seed = zlib.crc32(cert_der) & 0xFFFFFFFF
    print(f"🔑 Cert: {len(cert_der)} bytes DER")
    print(f"   XOR seed: 0x{seed:08X} (CRC32 of cert)")
    return seed


# ── Helpers ────────────────────────────────────────────────────────

def run(cmd, **kwargs):
    """Run a command, print it, fail on error."""
    cmd_str = " ".join(str(c) for c in cmd)
    print(f"  \033[36m$\033[0m {cmd_str}")
    result = subprocess.run(cmd, **kwargs)
    if result.returncode != 0:
        print(f"\033[31m  FAILED (exit={result.returncode})\033[0m", file=sys.stderr)
        sys.exit(1)
    return result


def sh(cmd_str, **kwargs):
    """Run a shell command string."""
    print(f"  \033[36m$\033[0m {cmd_str}")
    result = subprocess.run(cmd_str, shell=True, **kwargs)
    if result.returncode != 0:
        print(f"\033[31m  FAILED (exit={result.returncode})\033[0m", file=sys.stderr)
        sys.exit(1)
    return result


# ── Step 1: Compile sm4_tool ───────────────────────────────────────

def build_sm4_tool():
    if SM4_TOOL.exists():
        print(f"✅ sm4_tool exists ({SM4_TOOL.stat().st_size} bytes)")
        return
    print("🔨 Building sm4_tool...")
    sm4_src = PROJECT_ROOT / "tools" / "sm4_tool.cpp"
    run(["g++", "-std=c++17", "-O3", "-Wall",
         str(sm4_src), "-o", str(SM4_TOOL)])
    print(f"✅ sm4_tool built ({SM4_TOOL.stat().st_size} bytes)")


# ── Step 1b: Dummy payload ────────────────────────────────────────

def generate_dummy_payload():
    """Generate a minimal g_vmp_payload_data.cpp so Gradle ndk-build succeeds.
    This is replaced with the real payload after extracting the business DEX."""
    if PAYLOAD_CPP.exists():
        print(f"   Payload already exists ({PAYLOAD_CPP.stat().st_size:,} bytes)")
        return
    print("📝 Generating dummy payload (placeholder for Gradle build)...")
    content = '''// Dummy payload — placeholder for Gradle build.
// Replaced by real encrypted DEX after assembleRelease.
// Structure: 256-byte header (scattered key) + placeholder DEX stub.
#include <stdint.h>
#include <stddef.h>

#define VMP_HEADER_SIZE 256

extern const uint32_t g_vmp_payload_size __attribute__((used, visibility("default"))) = VMP_HEADER_SIZE + 16;
extern const uint32_t g_vmp_payload_crc32 __attribute__((used, visibility("default"))) = 0x00000000;
extern const uint8_t g_vmp_payload[] __attribute__((used, visibility("default"))) = {
    // 256-byte header (random chaff — key scattered here in real build)
''' + ',\n'.join(
    '    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00'
    for _ in range(16)
) + ''',
    // 16-byte placeholder encrypted "DEX" (will be replaced)
    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00
};
'''
    PAYLOAD_CPP.parent.mkdir(parents=True, exist_ok=True)
    PAYLOAD_CPP.write_text(content)
    print(f"✅ Dummy payload written ({PAYLOAD_CPP.stat().st_size:,} bytes)")


# ── Manifest patching ─────────────────────────────────────────────

MANIFEST_PATH = PROJECT_ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
SHELL_APP_CLASS = ".security.LianYuShellApplication"
ORIG_APP_CLASS = ".security.StaticApkShell"

def patch_manifest_for_shell() -> str:
    """Replace Application class in manifest for shell build. Returns original content."""
    original = MANIFEST_PATH.read_text(encoding="utf-8")
    if ORIG_APP_CLASS not in original:
        print(f"\033[33m⚠ {ORIG_APP_CLASS} not found in manifest — already patched?\033[0m")
        return original
    patched = original.replace(ORIG_APP_CLASS, SHELL_APP_CLASS)
    MANIFEST_PATH.write_text(patched, encoding="utf-8")
    print(f"📝 Manifest: {ORIG_APP_CLASS} → {SHELL_APP_CLASS}")
    return original

def restore_manifest(original: str):
    """Restore original manifest content."""
    MANIFEST_PATH.write_text(original, encoding="utf-8")
    print(f"📝 Manifest restored → {ORIG_APP_CLASS}")


# ── Step 2: Gradle build ──────────────────────────────────────────

def gradle_assemble_release():
    print("🔨 Gradle :app:assembleRelease (this may take 2-5 min)...")
    env = os.environ.copy()
    # Ensure Keystore env vars
    env["LIANYU_STORE_PASSWORD"] = STORE_PASS
    env["LIANYU_KEY_PASSWORD"] = KEY_PASS
    env["LIANYU_KEY_ALIAS"] = KEY_ALIAS
    env["GRADLE_OPTS"] = "-Xmx3072m"  # limit JVM heap to avoid OOM
    run(["./gradlew", ":app:assembleRelease", "-q", "--no-daemon",
         "--max-workers=2", "-Dorg.gradle.parallel=false"],
        cwd=str(PROJECT_ROOT), env=env, timeout=1800)
    apk = PROJECT_ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    if not apk.exists():
        # Try unsigned
        apk = PROJECT_ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release-unsigned.apk"
    if not apk.exists():
        print("\033[31mAPK not found after Gradle build\033[0m", file=sys.stderr)
        sys.exit(1)
    print(f"✅ Gradle build done → {apk} ({apk.stat().st_size:,} bytes)")
    return apk


# ── Step 3: Extract & encrypt DEX ─────────────────────────────────

def extract_encrypt_dex(apk_path: Path, sm4_key_hex: str) -> tuple:
    """Extract business DEX, encrypt with SM4, return (enc_path, crc32, key_hex)."""
    print("📦 Extracting business DEX...")
    with zipfile.ZipFile(apk_path, 'r') as zf:
        try:
            dex_data = zf.read('classes.dex')
        except KeyError:
            for name in sorted(zf.namelist()):
                if '.dex' in name:
                    dex_data = zf.read(name)
                    break
            else:
                raise SystemExit("No DEX found in APK")

    original_len = len(dex_data)
    print(f"   DEX: {original_len:,} bytes")

    # Pad to 16-byte boundary
    pad = (16 - (original_len % 16)) % 16
    if pad:
        dex_data += b'\x00' * pad
    padded_len = len(dex_data)
    print(f"   Padded: {padded_len:,} bytes (+{pad})")

    # Generate key if not provided
    if sm4_key_hex is None:
        key_bytes = os.urandom(16)
        sm4_key_hex = key_bytes.hex()
    print(f"🔑 SM4 key: {sm4_key_hex}")

    # Write plaintext to temp file
    with tempfile.NamedTemporaryFile(suffix='.bin', delete=False) as f:
        plain_path = f.name
        f.write(dex_data)
    enc_path = plain_path + '.enc'

    # Encrypt with sm4_tool
    result = subprocess.run(
        [str(SM4_TOOL), 'enc', plain_path, enc_path, sm4_key_hex],
        capture_output=True, text=True, check=True
    )
    crc32_val = int(result.stdout.strip(), 16)
    print(f"   CRC32: 0x{crc32_val:08X}")

    os.unlink(plain_path)
    return enc_path, crc32_val, sm4_key_hex


# ── Shell Kotlin name patching ─────────────────────────────────────

def patch_shell_kotlin(shell_names: dict) -> list:
    """Replace method/field names in shell Kotlin source with randomized names."""
    kt_files = [
        SHELL_SRC / "com" / "lianyu" / "ai" / "security" / "NativeBridge.kt",
        SHELL_SRC / "com" / "lianyu" / "ai" / "security" / "LianYuShellApplication.kt",
    ]
    backups = []
    replacements = [
        ('nativeLoadPayload',       shell_names['load_payload']),
        ('sDexClassLoader',         shell_names['dex_classloader']),
        ('sRealAppClass',           shell_names['real_app_class']),
    ]
    for kt_file in kt_files:
        if not kt_file.exists():
            continue
        original = kt_file.read_text()
        backup = kt_file.with_suffix(".kt.vmp_bak")
        backup.write_text(original)
        backups.append(backup)
        content = original
        for old, new in replacements:
            count = content.count(old)
            if count:
                content = content.replace(old, new)
                print(f"   {kt_file.name}: '{old}' -> '{new}' ({count} occurrences)")
        kt_file.write_text(content)
    return backups

def restore_shell_kotlin(backups: list) -> None:
    """Restore shell Kotlin from backups after build."""
    import shutil as _shutil
    for backup in backups:
        kt_path = Path(str(backup).replace('.vmp_bak', ''))
        _shutil.copy2(str(backup), str(kt_path))
        backup.unlink()


# ── Step 4: Generate payload C++ ───────────────────────────────────

def generate_payload(enc_path: str, crc32_val: int, sm4_key_hex: str, seed: int) -> None:
    """Generate g_vmp_payload_data.cpp using the given XOR seed."""
    print("📝 Generating g_vmp_payload_data.cpp...")
    run([
        sys.executable, str(GEN_PAYLOAD),
        enc_path, str(PAYLOAD_CPP),
        f"0x{crc32_val:08X}", sm4_key_hex, f"0x{seed:08X}"
    ])
    print(f"   XOR seed: 0x{seed:08X} (from signing cert)")
    size = PAYLOAD_CPP.stat().st_size
    print(f"✅ Payload C++ generated ({size:,} bytes)")

    # ── Step 4.5: Obfuscate VMP bytecode (per-build opaque predicates) ──
    obfuscator = PROJECT_ROOT / "tools" / "obfuscate_bytecode.py"
    if obfuscator.exists():
        print("🎲 Randomizing VMP bytecode opaque predicates...")
        run([sys.executable, str(obfuscator), "--no-reg-shuffle",
             "--seed", f"0x{seed:08X}"])
    else:
        print("⚠ obfuscate_bytecode.py not found — skipping")


# ── Step 4.6: DEX2C transpile ────────────────────────────────────

def run_dex2c_transpile(apk_path: Path) -> bool:
    """Extract DEX from APK and transpile whitelisted methods to C++.
    Generated code goes to generated/dex2c_methods.cpp, compiled into liblianyu_dex2c.so.
    Returns True on success, False on failure (non-fatal — build continues)."""
    transpiler = PROJECT_ROOT / "tools" / "dex2c_transpile.py"
    whitelist = PROJECT_ROOT / "tools" / "dex2c_whitelist.txt"
    out_cpp = CPP_DIR / "generated" / "dex2c_methods.cpp"
    out_h = CPP_DIR / "generated" / "dex2c_registry.h"

    if not transpiler.exists():
        print("⚠ dex2c_transpile.py not found — skipping DEX2C")
        return False
    if not whitelist.exists():
        print("⚠ dex2c_whitelist.txt not found — skipping DEX2C")
        return False

    print("🔧 DEX2C: Transpiling whitelisted methods to C++...")
    try:
        import zipfile, tempfile
        with zipfile.ZipFile(apk_path, 'r') as zf:
            dex_data = zf.read('classes.dex')

        with tempfile.NamedTemporaryFile(suffix='.dex', delete=False) as f:
            f.write(dex_data)
            dex_tmp = f.name

        out_cpp.parent.mkdir(parents=True, exist_ok=True)
        result = subprocess.run(
            [sys.executable, str(transpiler), dex_tmp,
             "--whitelist", str(whitelist),
             "--out-cpp", str(out_cpp),
             "--out-h", str(out_h)],
            capture_output=True, text=True, timeout=600
        )
        os.unlink(dex_tmp)

        if result.returncode == 0 and out_cpp.stat().st_size > 500:
            print(f"✅ DEX2C transpile done → {out_cpp.stat().st_size:,} bytes C++")
            return True
        else:
            print(f"⚠ DEX2C transpile returned {result.returncode}, using stub")
            if result.stderr:
                # Show last 3 lines of error
                lines = result.stderr.strip().split('\n')
                for line in lines[-3:]:
                    print(f"   {line[:120]}")
            return False
    except subprocess.TimeoutExpired:
        print("⚠ DEX2C transpile timed out (>10min), using stub")
        return False
    except Exception as e:
        print(f"⚠ DEX2C transpile failed: {e}, using stub")
        return False


# ── Step 5: Rebuild SOs ──────────────────────────────────────────

def build_so():
    print("🔨 Rebuilding native libraries (ndk-build)...")
    ndk_build = NDK / "ndk-build"
    if not ndk_build.exists():
        print("\033[31mndk-build not found at {}\033[0m".format(ndk_build), file=sys.stderr)
        sys.exit(1)

    # No more -DXOR_KEY_SEED — seed is derived from APK signing cert at runtime
    run([
        str(ndk_build),
        f"NDK_PROJECT_PATH={CPP_DIR}",
        f"APP_BUILD_SCRIPT={ANDROID_MK}",
        f"NDK_APPLICATION_MK={APP_MK}",
        "APP_ABI=all",
        "-j2",
    ], cwd=str(CPP_DIR), timeout=600)
    print("✅ SOs rebuilt")


# ── Step 5b: Encrypt .text sections ───────────────────────────────

def encrypt_so_text(cert_seed: int):
    """Encrypt .text section with per-build XOR key. No PT_LOAD patching needed —
    the runtime decrypt stub uses MAP_FIXED to replace file-backed pages,
    sidestepping the W^X restriction entirely."""
    enc_tool = PROJECT_ROOT / "tools" / "encrypt_text_section.py"

    for abi in ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"]:
        so_path = CPP_DIR / "libs" / abi / "liblianyu_security.so"
        if not so_path.exists():
            print(f"   ⚠ {abi}: SO not found, skipping")
            continue

        # Encrypt .text
        run([sys.executable, str(enc_tool), str(so_path), f"0x{cert_seed:08X}"],
            cwd=str(PROJECT_ROOT), timeout=60)

    print("✅ .text encryption complete")


# ── Step 6: Build shell DEX ───────────────────────────────────────

def build_shell_dex() -> Path:
    """Compile shell Kotlin via Gradle → R8 tree-shake → minimal DEX."""
    print("🔨 Building shell DEX...")

    # Step 6a: Compile Kotlin via Gradle (:shell:jar)
    run(["./gradlew", ":shell:jar", "-q", "--no-daemon"],
        cwd=str(PROJECT_ROOT), timeout=600)

    jar_file = SHELL_OUT / "libs" / "shell.jar"
    if not jar_file.exists():
        raise SystemExit(f"shell.jar not found at {jar_file}")

    print(f"   shell.jar: {jar_file.stat().st_size:,} bytes")

    # Step 6b: Find kotlin-stdlib + androidx.core for R8 classpath
    cache = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"
    stdlib_cands = sorted(
        cache.glob("org.jetbrains.kotlin/kotlin-stdlib/**/kotlin-stdlib-*.jar"),
        key=lambda p: p.stat().st_size, reverse=True
    )
    kotlin_stdlib = None
    for c in stdlib_cands:
        if 'sources' not in str(c) and 'javadoc' not in str(c):
            kotlin_stdlib = str(c)
            break
    if not kotlin_stdlib:
        raise SystemExit("kotlin-stdlib not found in Gradle cache")
    print(f"   stdlib:  {kotlin_stdlib}")

    # Extract kotlin.jvm.internal.Intrinsics + Result/Unit from kotlin-stdlib into a mini JAR.
    # R8 only includes classes from input JARs; kotlin-stdlib is on --classpath
    # (reference-only). Without this, the shell DEX references these but
    # the class definitions are missing → NoClassDefFoundError at runtime.
    # Required by shell Kotlin code:
    #   - Intrinsics (from @JvmStatic, object, ==, ?.)
    #   - Result/ResultKt (from runCatching, Result.success/failure)
    #   - Unit (from Kotlin void functions)
    intrinsics_jar = SHELL_OUT / "kotlin_intrinsics.jar"
    if not intrinsics_jar.exists() or intrinsics_jar.stat().st_size < 100:
        print("   Extracting Kotlin runtime classes from kotlin-stdlib...")
        import tempfile as _tmp
        intrinsics_tmp = _tmp.mkdtemp(prefix="intrinsics_")
        for pattern in [
            "kotlin/jvm/internal/Intrinsics*.class",
            "kotlin/Result*.class",
            "kotlin/Unit.class",
        ]:
            subprocess.run(
                ["unzip", "-o", kotlin_stdlib, pattern, "-d", intrinsics_tmp],
                capture_output=True, check=True
            )
        subprocess.run(
            ["jar", "cf", str(intrinsics_jar), "-C", intrinsics_tmp, "kotlin"],
            capture_output=True, check=True
        )
        shutil.rmtree(intrinsics_tmp)
    print(f"   intrinsics.jar: {intrinsics_jar.stat().st_size:,} bytes")

    # Find AndroidX core (needed for CoreComponentFactory referenced in Manifest)
    androidx_core = None
    core_aar_cands = list(cache.glob("androidx.core/core/**/core-*.aar"))
    for aar in core_aar_cands:
        # Extract classes.jar from AAR
        import io
        aar_path = str(aar)
        try:
            with zipfile.ZipFile(aar_path, 'r') as zf:
                classes_bytes = zf.read('classes.jar')
            tmp_jar = SHELL_OUT / "androidx_core_classes.jar"
            tmp_jar.write_bytes(classes_bytes)
            androidx_core = str(tmp_jar)
            print(f"   androidx.core: {aar_path} ({len(classes_bytes):,} bytes)")
            break
        except Exception:
            continue
    if not androidx_core:
        print("\033[33m⚠ androidx.core not found — CoreComponentFactory may be missing\033[0m")

    # Step 6c: R8 tree-shake into DEX
    r8_out = SHELL_OUT / "r8_output"
    if r8_out.exists():
        shutil.rmtree(r8_out)
    r8_out.mkdir()

    # Build classpath list
    classpath_parts = [kotlin_stdlib]
    annot_jar = CMDLINE_TOOLS / "annotations.jar"
    if annot_jar.exists():
        classpath_parts.append(str(annot_jar))
    if androidx_core:
        classpath_parts.append(androidx_core)

    r8_cmd = ["java", "-cp", str(R8_JAR), "com.android.tools.r8.R8",
              "--lib", str(PLATFORM_JAR),
              "--output", str(r8_out),
              "--min-api", "26",
              "--pg-conf", str(PROJECT_ROOT / "shell" / "proguard-rules.pro")]
    # Add classpath entries
    for cp in classpath_parts:
        r8_cmd += ["--classpath", cp]
    # Add input JAR(s): shell.jar + intrinsics + androidx_core (for CoreComponentFactory)
    r8_cmd.append(str(jar_file))
    r8_cmd.append(str(intrinsics_jar))  # Input: Intrinsics classes for kotlin-jvm runtime
    if androidx_core:
        r8_cmd.append(androidx_core)  # Input: R8 can include kept classes from here

    # Suppress missing class warnings
    r8_cmd.append("--pg-conf")
    # Write a temp suppress rules file
    suppress_conf = SHELL_OUT / "suppress.pro"
    suppress_conf.write_text("-dontwarn **\n")
    r8_cmd.append(str(suppress_conf))

    run(r8_cmd, timeout=120)

    dex_path = r8_out / "classes.dex"
    if not dex_path.exists():
        print("\033[31mR8 did not produce classes.dex\033[0m", file=sys.stderr)
        for f in r8_out.rglob("*"):
            print(f"  {f}")
        sys.exit(1)

    size = dex_path.stat().st_size
    print(f"✅ Shell DEX built ({size:,} bytes)")

    verify_shell_dex(dex_path)
    return dex_path


def verify_shell_dex(dex_path: Path):
    """Verify shell DEX contains expected classes and no business logic."""
    # Use strings to check
    result = subprocess.run(
        ["strings", str(dex_path)],
        capture_output=True, text=True
    )
    content = result.stdout

    missing = []
    for cls in SHELL_CLASSES:
        cls_path = cls.replace("/", ".") if "." not in cls else cls
        if cls_path not in content and cls.replace(".", "/") not in content:
            missing.append(cls)

    if missing:
        print(f"\033[33m⚠ Shell DEX missing classes: {missing}\033[0m")

    # Check no business classes leaked (excluding string constants in shell source)
    # The string "com.lianyu.ai.LianYuApplication" appears in LianYuShellApplication.kt
    # as a constant for loading the real Application — NOT a leaked class definition.
    leaked = []
    for pattern in ["LianYuApplication", "MainActivity", "ChatViewModel"]:
        if pattern in content and "Shell" not in pattern:
            idx = content.find(pattern)
            ctx = content[max(0, idx-30):idx+30]
            # Skip string constants referencing the real Application
            if "LianYuShellApplication" not in ctx and "OnePieceShellGate" not in ctx:
                if 'loadClass' not in ctx and 'realAppClass' not in ctx:
                    leaked.append(pattern)
    if leaked:
        print(f"\033[33m⚠ Possible business class leak: {leaked}\033[0m")
    else:
        print(f"   Shell classes verified clean")


# ── Step 7: Assemble final APK ────────────────────────────────────

def assemble_apk(gradle_apk: Path, shell_dex: Path) -> Path:
    """Replace classes.dex with shell DEX, replace SOs, re-sign.
    Uses zip command-line for zero-temp-file in-place update."""
    print("📦 Assembling final APK...")

    signed = PROJECT_ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release-shell-signed.apk"

    # Work on a copy of the Gradle APK
    work_apk = Path(tempfile.mktemp(suffix=".apk"))
    shutil.copy2(gradle_apk, work_apk)

    # Remove old classes.dex and SOs
    subprocess.run(["zip", "-d", str(work_apk), "classes.dex"], capture_output=True)
    for abi in ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"]:
        so_entry = f"lib/{abi}/liblianyu_security.so"
        subprocess.run(["zip", "-d", str(work_apk), so_entry], capture_output=True)

    # Add shell DEX (stored)
    subprocess.run(["zip", "-0", "-j", str(work_apk), str(shell_dex)],
                   capture_output=True, check=True)
    print(f"   classes.dex → shell DEX ({shell_dex.stat().st_size:,} bytes)")

    # Add new SOs (stored, with correct lib/<abi>/ path using staging dirs)
    so_replaced = 0
    so_staging = Path(tempfile.mkdtemp(prefix="so_staging_"))
    for abi in ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"]:
        so_src = CPP_DIR / "libs" / abi / "liblianyu_security.so"
        if so_src.exists():
            so_dir = so_staging / "lib" / abi
            so_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy2(so_src, so_dir / "liblianyu_security.so")
            so_replaced += 1
    # Add all SOs at once with correct directory structure
    subprocess.run(
        ["zip", "-0", "-r", str(work_apk), "lib"],
        capture_output=True, check=True, cwd=str(so_staging)
    )
    shutil.rmtree(so_staging)
    print(f"   Replaced {so_replaced}/4 SOs")

    # Zipalign
    aligned = work_apk.parent / "aligned.apk"
    run([str(ZIPALIGN), "-p", "-f", "4", str(work_apk), str(aligned)])

    # Sign
    run([
        "apksigner", "sign",
        "--ks", str(KEYSTORE),
        "--ks-pass", f"pass:{STORE_PASS}",
        "--key-pass", f"pass:{KEY_PASS}",
        "--ks-key-alias", KEY_ALIAS,
        "--out", str(signed),
        str(aligned),
    ])

    run(["apksigner", "verify", "--verbose", str(signed)])

    # Cleanup
    work_apk.unlink(missing_ok=True)
    aligned.unlink(missing_ok=True)

    print(f"\n🎉 \033[1;32mShell APK ready:\033[0m {signed}")
    print(f"   Size: {signed.stat().st_size:,} bytes")

    with zipfile.ZipFile(signed, 'r') as zf:
        dex_size = zf.getinfo('classes.dex').file_size if 'classes.dex' in zf.namelist() else 0
        print(f"   Shell DEX: {dex_size:,} bytes")
        for abi in ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"]:
            so_name = f"lib/{abi}/liblianyu_security.so"
            if so_name in zf.namelist():
                print(f"   {abi}: {zf.getinfo(so_name).file_size:,} bytes")

    return signed


# ── Main ───────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="One-shot shell DEX APK builder for LianYu VMP"
    )
    parser.add_argument("--key", help="SM4 key (32 hex chars). Random if not provided.")
    parser.add_argument("--no-clean", action="store_true", help="Skip clean before build")
    parser.add_argument("--skip-gradle", action="store_true", help="Skip Gradle build (use existing APK)")
    parser.add_argument("--skip-so", action="store_true", help="Skip SO rebuild")
    parser.add_argument("--shell-only", action="store_true", help="Only rebuild shell DEX (fast iteration)")
    args = parser.parse_args()

    os.chdir(PROJECT_ROOT)

    # ── Build shell DEX only mode ──
    if args.shell_only:
        shell_dex = build_shell_dex()
        return

    # ── Full pipeline ──
    build_sm4_tool()
    generate_dummy_payload()

    if not args.skip_gradle:
        manifest_orig = patch_manifest_for_shell()
        try:
            apk = gradle_assemble_release()
        finally:
            restore_manifest(manifest_orig)
    else:
        apk = PROJECT_ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
        if not apk.exists():
            apk = PROJECT_ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release-unsigned.apk"
        if not apk.exists():
            raise SystemExit(f"APK not found at {apk}. Run without --skip-gradle first.")

    enc_path, crc32_val, key_hex = extract_encrypt_dex(apk, args.key)

    # Seed from signing certificate (same hash computed at runtime via JNI)
    cert_seed = get_cert_hash()
    generate_payload(enc_path, crc32_val, key_hex, cert_seed)

    # Read randomized shell names from generated JSON
    import json as _json
    names_json = CPP_DIR / "g_vmp_shell_names.json"
    with open(names_json) as f:
        shell_names = _json.load(f)
    kt_backup = patch_shell_kotlin(shell_names)

    # Step 4.6: DEX2C transpile whitelisted security methods
    run_dex2c_transpile(apk)

    if not args.skip_so:
        build_so()
        # D2: encrypt .text sections
        encrypt_so_text(cert_seed)

    shell_dex = build_shell_dex()

    restore_shell_kotlin(kt_backup)

    signed_apk = assemble_apk(apk, shell_dex)

    # Cleanup temp encrypted file
    if os.path.exists(enc_path):
        os.unlink(enc_path)

    print("\n✅ \033[1;32mAll done!\033[0m")
    print(f"   Install: adb install {signed_apk}")


if __name__ == "__main__":
    main()
