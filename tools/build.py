#!/usr/bin/env python3
"""LianYu One-Click APK Builder — strips ContentProviders, injects shell DEX."""

import zipfile, shutil, os, sys, subprocess, glob, re, argparse, tempfile, struct, hashlib, hmac

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHELL_SRC = os.path.join(PROJECT, "app/build/tmp/ultimate_shell/src/com/lianyu/ai/security")
SHELL_WORK = os.path.join(PROJECT, "app/build/tmp/ultimate_shell")
LOCALAPPDATA = os.environ.get("LOCALAPPDATA", "")
SDK = os.path.join(LOCALAPPDATA, "Android", "Sdk")
BT = os.path.join(SDK, "build-tools", "36.0.0")
ANDROID_JAR = os.path.join(SDK, "platforms", "android-35", "android.jar")
APKTOOL = os.path.join(PROJECT, "tools", "apktool.jar")

# cert obs from nativeDeriveDexKey (obfuscation mask)
CERT_OBS = bytes([
    0x4e,0x0d,0xa5,0x67,0x7e,0xc7,0x29,0x22,0x5f,0xbc,0x9e,0xf0,0x7a,0x73,0xf0,0x88,
    0x41,0x64,0x2b,0x4f,0x39,0xef,0x22,0xca,0xe1,0x5f,0x78,0x49,0x9a,0x41,0x13,0xec,
])
# Expected maps CRC64 from nativeDeriveDexKey
# Can be auto-detected from SO: python tools/build.py --detect-maps
EXPECTED_MAPS = 0x8e7beee5d9b3c6e4

def detect_maps_crc_from_so():
    """Extract CRC64 from liblianyu_shell.so .text section — sync with native EXPECTED_MAPS.
       The native side reads /proc/self/maps line CRC64 for the SO's r-xp segment.
       This function approximates it by CRC64-ing the SO file's .text section.
       Call with --detect-maps flag or run: python tools/build.py --detect-maps"""
    import struct as _st
    so_path = None
    for search in [
        "core/security/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/arm64-v8a/liblianyu_shell.so",
        "core/security/build/intermediates/cxx/Release/*/obj/local/arm64-v8a/liblianyu_shell.so",
    ]:
        matches = glob.glob(os.path.join(PROJECT, search))
        if matches:
            so_path = sorted(matches, key=os.path.getmtime)[-1]
            break
    if not so_path:
        print("  WARNING: Cannot find liblianyu_shell.so — using hardcoded EXPECTED_MAPS")
        return EXPECTED_MAPS

    data = open(so_path, "rb").read()
    # Parse ELF: find .text section offset + size
    if data[:4] != b'\x7fELF':
        print("  WARNING: Not a valid ELF — using hardcoded EXPECTED_MAPS")
        return EXPECTED_MAPS

    is_64bit = data[4] == 2
    if is_64bit:
        e_shoff = _st.unpack_from('<Q', data, 0x28)[0]
        e_shentsize, e_shnum, e_shstrndx = _st.unpack_from('<HHH', data, 0x3A)
    else:
        e_shoff = _st.unpack_from('<I', data, 0x20)[0]
        e_shentsize, e_shnum, e_shstrndx = _st.unpack_from('<HHH', data, 0x2E)

    # Find .shstrtab
    shstr_offset = e_shoff + e_shstrndx * e_shentsize
    shstrtab_off = _st.unpack_from('<I' if not is_64bit else '<Q', data, shstr_offset + 0x18)[0]

    # Find .text section
    for i in range(e_shnum):
        sh_off = e_shoff + i * e_shentsize
        sh_name_idx = _st.unpack_from('<I', data, sh_off)[0]
        sh_name = data[shstrtab_off + sh_name_idx:].split(b'\x00')[0].decode('ascii', errors='replace')
        if sh_name == '.text':
            sh_addr = _st.unpack_from('<I' if not is_64bit else '<Q', data, sh_off + 0x10)[0]
            sh_size = _st.unpack_from('<I' if not is_64bit else '<Q', data, sh_off + 0x20)[0]
            sh_offset = _st.unpack_from('<I' if not is_64bit else '<Q', data, sh_off + 0x18)[0]
            if sh_offset > 0 and sh_size > 0 and sh_offset + sh_size <= len(data):
                text_crc = crc64(data[sh_offset:sh_offset + sh_size])
                print(f"  Detected EXPECTED_MAPS from SO .text: 0x{text_crc:016x} (was 0x{EXPECTED_MAPS:016x})")
                return text_crc
            break

    print("  WARNING: Cannot find .text section — using hardcoded EXPECTED_MAPS")
    return EXPECTED_MAPS

def crc64(data):
    """CRC64-ECMA-182 for SO maps matching."""
    table = []
    for i in range(256):
        crc = i
        for _ in range(8):
            crc = (crc >> 1) ^ (0x42F0E1EBA9EA3693 if crc & 1 else 0)
        table.append(crc)
    crc = 0xFFFFFFFFFFFFFFFF
    for b in data:
        crc = table[(crc ^ b) & 0xFF] ^ (crc >> 8)
    return crc ^ 0xFFFFFFFFFFFFFFFF

def derive_dex_key(cert_sha256):
    """Derive DEX encryption key matching nativeDeriveDexKey()."""
    # XOR cert with obs mask → cert_hash
    cert_hash = bytes(a ^ b for a, b in zip(cert_sha256[:32], CERT_OBS))
    # Salt: maps_crc(8) || dev_fp(8) || hw_sig(32) || "lianyu_dex_v3"(16)
    salt = struct.pack('<Q', EXPECTED_MAPS) + b'\x00' * 8 + b'\x00' * 32 + b'lianyu_dex_v3\x00\x00'
    return hmac.new(cert_hash, salt, hashlib.sha256).digest()

# Fallback XOR_KEY for when cert is unavailable
_FALLBACK_KEY = bytes([
    0x50,0x59,0x65,0x1c,0xb6,0xac,0x74,0xf7,0xf5,0x4c,0x5d,0x32,0x75,0x80,0x1f,0x51,
    0x0b,0x3c,0x1f,0x48,0x3e,0x20,0xc7,0xd7,0x32,0x60,0x17,0xcc,0x23,0xf1,0xe9,0x53,
])

# Vivo multi-DEX: put unencrypted business DEX as classes2.dex (system auto-loads)
VIVO_MULTIDEX = os.environ.get("LIANYU_VIVO_MULTIDEX", "").lower() in ("1", "true", "yes")

# Dynamically computed encryption key — populated in main()
DEX_KEY = _FALLBACK_KEY

SHELL_SO_LIST = ["lib/arm64-v8a/liblianyu_shell.so",
                 "lib/arm64-v8a/liblianyu_security.so"]

def run(cmd, timeout=120):
    print(f"  $ {' '.join(cmd) if isinstance(cmd,list) else cmd}")
    return subprocess.run(cmd, check=True, capture_output=True, timeout=timeout)

def shell_dex():
    """Compile shell DEX from source."""
    print("\n═══ Shell DEX ═══")
    cls = os.path.join(SHELL_WORK, "classes"); dex = os.path.join(SHELL_WORK, "dex")
    if os.path.exists(cls): shutil.rmtree(cls, ignore_errors=True)
    if os.path.exists(dex): shutil.rmtree(dex, ignore_errors=True)
    os.makedirs(cls); os.makedirs(dex)
    java_files = [os.path.join(SHELL_SRC, f) for f in
                  ["StaticApkShell.java","SActivity.java","MethodRecoveryEngine.java"]
                  if os.path.exists(os.path.join(SHELL_SRC, f))]
    run(["javac","-cp",ANDROID_JAR,"-d",cls] + java_files, timeout=30)
    jar = os.path.join(SHELL_WORK, "shell.jar")
    run(["jar","cf",jar,"-C",cls,"."], timeout=10)
    d8 = os.path.join(BT, "d8.bat") if os.name=="nt" else os.path.join(BT,"d8")
    run([d8,"--lib",ANDROID_JAR,"--release","--output",dex,jar], timeout=30)
    out = os.path.join(dex, "classes.dex")
    print(f"  {os.path.getsize(out)}B")
    return out

def encrypt_dex(src_apk):
    """HMAC-SHA256 CTR encrypt all .dex files + app_meta.bin (real Application class name)."""
    import hashlib as _hl, hmac as _hm
    print("\n═══ DEX Encryption ═══")
    work = tempfile.mkdtemp(prefix="lianyu_dex_")
    extra_dex = tempfile.mkdtemp(prefix="lianyu_extra_dex_")
    count = 0
    with zipfile.ZipFile(src_apk, "r") as z:
        for name in sorted(z.namelist()):
            if not name.endswith(".dex"): continue
            data = z.read(name)
            if VIVO_MULTIDEX:
                extra_name = name
                if count == 0 and extra_name == "classes.dex":
                    extra_name = "classes2.dex"
                open(os.path.join(extra_dex, extra_name), "wb").write(data)
            iv = os.urandom(16)
            enc = bytearray(iv)
            for i in range(0, len(data), 16):
                ctr = struct.pack('>16sQ8x', iv, i // 16)
                ks = _hm.new(DEX_KEY, ctr[:32], _hl.sha256).digest()
                for j in range(min(16, len(data) - i)):
                    enc.append(data[i + j] ^ ks[j])
            out_name = name.replace("/","_").replace(".dex",".dat")
            open(os.path.join(work, out_name), "wb").write(bytes(enc))
            count += 1
            print(f"  {name} → {out_name} {len(enc)//1024}KB")

    # Encrypt real Application class name as app_meta.bin
    real_app_class = "com.lianyu.ai.LianYuApplication"
    iv = os.urandom(16)
    data = real_app_class.encode("utf-8")
    enc = bytearray(iv)
    for i in range(0, len(data), 16):
        ctr = struct.pack('>16sQ8x', iv, i // 16)
        ks = _hm.new(DEX_KEY, ctr[:32], _hl.sha256).digest()
        for j in range(min(16, len(data) - i)):
            enc.append(data[i + j] ^ ks[j])
    open(os.path.join(work, "app_meta.bin"), "wb").write(bytes(enc))
    print(f"  app_meta.bin → {real_app_class} ({len(data)}B + 16B IV)")

    print(f"  {count} DEX + 1 meta")
    return work, count, extra_dex

def assemble(shell_dex, dex_dir, extra_dex_dir, repacked, variant, keystore, ks_pass, key_alias, key_pass):
    """Replace DEX + SOs + encrypted DEX → sign. Optionally add extra DEX for Vivo."""
    print("\n═══ Assembly ═══")
    out = os.path.join(PROJECT, f"LianYu-v2.apk")
    tmp = out + ".tmp"
    shell = open(shell_dex, "rb").read()

    so_base = None
    for v in [variant.capitalize(), "Release", "Debug"]:
        dirs = glob.glob(os.path.join(PROJECT,"core/security/build/intermediates/cxx",v,"*","obj","local"))
        if dirs: so_base = max(dirs, key=os.path.getmtime); break
    if not so_base: print("  WARNING: No compiled SOs")

    dex_files = {}
    for f in os.listdir(dex_dir):
        p = os.path.join(dex_dir, f)
        dex_files[f"assets/shell/{f}"] = open(p, "rb").read()

    # Extra unencrypted DEX for Vivo (classes2.dex, classes3.dex, ...)
    extra_dex_entries = {}
    if os.path.isdir(extra_dex_dir):
        for f in sorted(os.listdir(extra_dex_dir)):
            if f == "classes.dex": continue
            extra_dex_entries[f] = open(os.path.join(extra_dex_dir, f), "rb").read()
    if extra_dex_entries:
        print(f"  Adding {len(extra_dex_entries)} unencrypted DEX: {list(extra_dex_entries.keys())}")

    with zipfile.ZipFile(repacked, "r") as zin:
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename == "classes.dex": data = shell
                elif item.filename.startswith("META-INF/"): continue
                elif item.filename.startswith("classes") and item.filename.endswith(".dex"): continue
                else:
                    for so in SHELL_SO_LIST:
                        if item.filename == so and so_base:
                            sp = os.path.join(so_base, os.path.dirname(so).replace("lib/",""),
                                              os.path.basename(so))
                            if os.path.exists(sp): data = open(sp,"rb").read(); break
                zout.writestr(item, data)
            for name, data in dex_files.items(): zout.writestr(name, data)
            for name, data in extra_dex_entries.items(): zout.writestr(name, data)

    shutil.move(tmp, out)
    apksigner = os.path.join(BT, "apksigner.bat") if os.name=="nt" else os.path.join(BT,"apksigner")
    signed = out.replace(".apk", "-signed.apk")
    run(["cmd","/c",apksigner,"sign","--ks",keystore,"--ks-pass",f"pass:{ks_pass}",
         "--ks-key-alias",key_alias,"--key-pass",f"pass:{key_pass}","--out",signed,out])
    shutil.move(signed, out)
    shutil.rmtree(dex_dir, ignore_errors=True)
    if os.path.isdir(extra_dex_dir): shutil.rmtree(extra_dex_dir, ignore_errors=True)
    print(f"  {os.path.getsize(out)//1048576}MB → {out}")
    return out

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--release", action="store_true")
    p.add_argument("--no-build", action="store_true")
    p.add_argument("--detect-maps", action="store_true",
                   help="Auto-detect EXPECTED_MAPS from liblianyu_shell.so .text CRC64")
    args = p.parse_args()

    global EXPECTED_MAPS
    if args.detect_maps:
        EXPECTED_MAPS = detect_maps_crc_from_so()
    variant = "release" if args.release else "debug"
    print(f"═══ LianYu {variant.upper()} Build ═══")
    if VIVO_MULTIDEX:
        print(f"  VIVO mode: unencrypted multi-DEX (system auto-loads)")

    sdex = shell_dex()
    if args.no_build:
        gradle_apk = os.path.join(PROJECT, "app/build/outputs/apk", variant, f"app-{variant}.apk")
    else:
        gradlew = os.path.join(PROJECT, "gradlew.bat")
        run([gradlew, f"assemble{variant.capitalize()}", "--no-daemon", "-q"], timeout=600)
        gradle_apk = os.path.join(PROJECT, "app/build/outputs/apk", variant, f"app-{variant}.apk")

    if args.release:
        ks = os.path.join(PROJECT, "release.keystore")
        kp = os.environ.get("LIANYU_KEYSTORE_PASS", "")
        alias = os.environ.get("LIANYU_KEY_ALIAS", "your_alias")
        if not kp:
            ks = os.path.join(os.environ["USERPROFILE"], ".android", "debug.keystore")
            kp = "android"; alias = "androiddebugkey"
            print("  WARNING: LIANYU_KEYSTORE_PASS not set — using debug keystore")
    else:
        ks = os.path.join(os.environ["USERPROFILE"], ".android", "debug.keystore")
        kp = "android"; alias = "androiddebugkey"

    # Derive DEX encryption key from actual signing cert (matches nativeDeriveDexKey)
    global DEX_KEY
    try:
        kt = subprocess.run(
            ["keytool", "-list", "-v", "-keystore", ks, "-storepass", kp, "-alias", alias],
            capture_output=True, timeout=30
        )
        out = kt.stdout.decode('utf-8', errors='replace')
        # Extract SHA-256 from keytool output (format: "SHA256: AB:CD:...")
        for line in out.split('\n'):
            if "SHA256:" in line:
                cert_hex = line.split("SHA256:")[-1].strip().replace(':', '').replace(' ', '')
                if len(cert_hex) == 64:
                    cert_sha = bytes.fromhex(cert_hex)
                    DEX_KEY = derive_dex_key(cert_sha)
                    print(f"  Derived encryption key from cert SHA256: {cert_hex[:16]}...")
                    break
        if DEX_KEY == _FALLBACK_KEY:
            print("  WARNING: Could not extract cert — using fallback key")
    except Exception as e:
        print(f"  WARNING: Key derivation failed ({e}) — using fallback key")

    dex_dir, count, extra_dex = encrypt_dex(gradle_apk)
    repacked = gradle_apk

    final = assemble(sdex, dex_dir, extra_dex, repacked, variant, ks, kp, alias, kp)

    if args.release:
        desk = os.path.join(os.environ.get("USERPROFILE",""), "Desktop", "LianYu-Release.apk")
        shutil.copy(final, desk)
        print(f"\n  Desktop: {desk}")

    print(f"\n═══ DONE ═══")

if __name__ == "__main__":
    main()