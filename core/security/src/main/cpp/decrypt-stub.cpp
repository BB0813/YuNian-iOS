// Runtime SO decrypt stub — constructor-based self-decryption
//
// pack_so.py encrypts the .text section in-place (NO file insertion).
// This function lives in .lianyu_decrypt (separate from .text) and is
// called via __attribute__((constructor(101))) before JNI_OnLoad.
//
// Relocations are applied by the linker before constructors run, so
// .text must not contain relocation targets (PIC code satisfies this).
//
// CRITICAL DESIGN RULES:
//   1. NO calls to .text functions (it's encrypted until we decrypt it).
//   2. NO calls through the PLT — the PLT page may fall inside the
//      mprotect range and become non-executable.  Use raw inline
//      syscall(SVC #0) for mprotect instead.
//   3. NEVER request PROT_WRITE|PROT_EXEC simultaneously — Android 10+
//      enforces W^X and will reject it with EACCES.  Use RW first,
//      decrypt, flush cache, then RX.
//   4. The mprotect range must NOT include .lianyu_decrypt or .plt.
//      Only cover the .text section's page-aligned range.

#include <cstdint>
#include <sys/mman.h>

// ARM64 syscall number for mprotect (226)
// ARM32 syscall number for mprotect (125)
#if defined(__aarch64__)
#define __NR_mprotect 226
#elif defined(__arm__)
#define __NR_mprotect 125
#else
#define __NR_mprotect 226
#endif

static __attribute__((always_inline)) inline long
raw_mprotect(void *addr, size_t len, int prot) {
    long ret;
#if defined(__aarch64__)
    register long _x0 __asm__("x0") = (long)addr;
    register long _x1 __asm__("x1") = (long)len;
    register long _x2 __asm__("x2") = (long)prot;
    register long _x8 __asm__("x8") = __NR_mprotect;
    __asm__ volatile("svc #0" : "=r"(_x0) : "r"(_x0), "r"(_x1), "r"(_x2), "r"(_x8)
                     : "memory", "cc");
    ret = _x0;
#elif defined(__arm__)
    // ARM32: use r7 for syscall number (standard EABI calling convention).
    // "r" constraint lets the compiler pick a register, but we need r7
    // specifically for the SVC instruction.  Use a clobber approach instead.
    long _nr = __NR_mprotect;
    register long _r0 __asm__("r0") = (long)addr;
    register long _r1 __asm__("r1") = (long)len;
    register long _r2 __asm__("r2") = (long)prot;
    __asm__ volatile(
        "push {r7}\n"
        "mov r7, %[_n]\n"
        "svc #0\n"
        "pop {r7}\n"
        : "=r"(_r0)
        : [_n]"r"(_nr), "r"(_r0), "r"(_r1), "r"(_r2)
        : "memory", "cc");
    ret = _r0;
#else
    // x86/x86_64 fallback: use libc mprotect (no W^X issue on x86 Android)
    ret = mprotect(addr, len, prot);
#endif
    return ret;
}

// ── Global variables (in .data, patched by pack_so.py at build time) ──
// Non-zero sentinel values ensure .data placement (not .bss) so pack_so.py
// can find and patch them in the file.
// visibility("default") overrides -fvisibility=hidden so the version script
// can export these symbols to .dynsym for pack_so.py to find.
//
// ASLR NOTE: lianyu_text_start stores a SIGNED OFFSET from &lianyu_xor_key
// to .text start (NOT an absolute vaddr).  At runtime we compute:
//   text_ptr = &lianyu_xor_key + (int64_t)lianyu_text_start
// This is completely position-independent and works under ASLR.
extern "C" {

__attribute__((visibility("default")))
uint8_t lianyu_xor_key[16] = {
    0xDE, 0xAD, 0xBE, 0xEF, 0xDE, 0xAD, 0xBE, 0xEF,
    0xDE, 0xAD, 0xBE, 0xEF, 0xDE, 0xAD, 0xBE, 0xEF
};

__attribute__((visibility("default")))
uint64_t lianyu_text_start = 0xDEADBEEF42424242ULL;

__attribute__((visibility("default")))
uint64_t lianyu_text_size = 0xDEADBEEF42424242ULL;

__attribute__((visibility("default")))
void* lianyu_text_decrypt_ptr = nullptr;

} // extern "C"

// ── Decrypt function — in custom section, NOT encrypted by pack_so.py ──
// Section name does NOT start with .text. so the linker won't merge it
// into .text. It has SHF_EXECINSTR so it lands in an executable segment.
__attribute__((section(".lianyu_decrypt"), used, noinline, optimize("O0"), visibility("default")))
extern "C" void lianyu_d2_decrypt() {
    uint8_t* key_ptr  = lianyu_xor_key;

    // Compute .text runtime address via ASLR-independent offset.
    // lianyu_text_start stores the signed offset from &lianyu_xor_key to .text.
    uint8_t* text_ptr = key_ptr + (int64_t)lianyu_text_start;
    uint64_t text_sz  = lianyu_text_size;

    // Sentinel check — skip if not patched (debug builds)
    if (lianyu_text_start == 0xDEADBEEF42424242ULL) return;
    if (!text_ptr || !text_sz || !key_ptr) return;

    // 1. Make .text writable.
    //    On Android 10+ W^X is enforced, so we use PROT_READ|PROT_WRITE
    //    (no PROT_EXEC).  This makes the page non-executable, but on
    //    ARM64 the instruction cache is physically tagged and is NOT
    //    invalidated by mprotect — the CPU continues executing our
    //    decrypt code from the I-cache until we explicitly flush it.
    //    Use raw syscall to avoid PLT dependency (PLT page may be in range).
    uint64_t page_start = ((uint64_t)text_ptr) & ~0xFFFULL;
    uint64_t page_end   = (((uint64_t)text_ptr) + text_sz + 0xFFF) & ~0xFFFULL;
    long mp_ret = raw_mprotect((void*)page_start, page_end - page_start,
                               PROT_READ | PROT_WRITE);
    // If RW fails (some kernels reject removing X from an R-X page),
    // try RWX as fallback — some Android versions still allow it for
    // file-backed mappings.
    if (mp_ret != 0) {
        mp_ret = raw_mprotect((void*)page_start, page_end - page_start,
                              PROT_READ | PROT_WRITE | PROT_EXEC);
    }
    // If both attempts fail, abort — .text stays encrypted but the
    // app won't crash (constructors in .text will fail later, but
    // at least we don't SIGSEGV here).
    if (mp_ret != 0) return;

    // 2. XOR decrypt in-place
    // Use volatile to prevent compiler from replacing with memset/memcpy
    volatile uint8_t* vp = text_ptr;
    for (uint64_t i = 0; i < text_sz; i++) {
        vp[i] ^= key_ptr[i & 0xF];
    }

    // 3. Flush instruction cache using inline asm.
    // CRITICAL: Cannot use __builtin___clear_cache because it calls
    // __clear_cache() which lives in .text — at this point .text memory
    // is decrypted but the instruction cache still holds encrypted bytes,
    // so calling any .text function would SIGILL.
    // Instead we do cache flush instructions directly.
    {
        uint64_t addr = (uint64_t)text_ptr;
        uint64_t end  = (uint64_t)text_ptr + text_sz;
#if defined(__aarch64__)
        // ARM64: DC CVAU + DSB ISH + IC IVAU + DSB ISH + ISB
        for (uint64_t a = addr; a < end; a += 64) {
            __asm__ volatile("dc cvau, %0" :: "r"(a) : "memory");
        }
        __asm__ volatile("dsb ish" ::: "memory");
        for (uint64_t a = addr; a < end; a += 64) {
            __asm__ volatile("ic ivau, %0" :: "r"(a) : "memory");
        }
        __asm__ volatile("dsb ish" ::: "memory");
        __asm__ volatile("isb" ::: "memory");
#elif defined(__arm__)
        // ARM32: Use mcr p15 to clean D-cache and invalidate I-cache.
        // c15, c5, 7 = DCCMVAC (clean data cache by MVA)
        // c15, c5, 6 = ICIMVAU (invalidate I-cache by MVA)
        for (uint64_t a = addr; a < end; a += 32) {
            __asm__ volatile("mcr p15, 0, %0, c7, c10, 1" :: "r"(a) : "memory");
        }
        __asm__ volatile("dsb" ::: "memory");
        for (uint64_t a = addr; a < end; a += 32) {
            __asm__ volatile("mcr p15, 0, %0, c7, c5, 1" :: "r"(a) : "memory");
        }
        __asm__ volatile("dsb" ::: "memory");
        __asm__ volatile("isb" ::: "memory");
#else
        // x86/x86_64: Use __builtin___clear_cache (no separate I-cache issue
        // on x86 with strong memory ordering, but call for correctness).
        __builtin___clear_cache((char*)text_ptr, (char*)text_ptr + text_sz);
#endif
    }

    // 4. Restore read-execute (use raw syscall, NOT PLT)
    raw_mprotect((void*)page_start, page_end - page_start,
                 PROT_READ | PROT_EXEC);

    // 5. Wipe key from memory
    for (int i = 0; i < 16; i++) key_ptr[i] = 0;
    lianyu_text_start = 0;
    lianyu_text_size  = 0;
    lianyu_text_decrypt_ptr = nullptr;
}

// ── Constructor — runs before JNI_OnLoad, after relocations ──
// We place a function pointer directly in .init_array using
// __attribute__((section(".init_array"))).  The linker processes
// .init_array entries at load time, calling each function pointer.
// This bypasses the compiler's constructor mechanism which is
// unreliable with -ffunction-sections + --gc-sections.
//
// CRITICAL: lianyu_auto_decrypt MUST be in .lianyu_decrypt (not .text),
// otherwise pack_so.py will encrypt it and the constructor will crash
// with SIGILL when the linker tries to call it.
extern "C" __attribute__((section(".lianyu_decrypt"), used, noinline, optimize("O0")))
void lianyu_auto_decrypt() {
    lianyu_d2_decrypt();
}

// Function pointer placed directly in .init_array section.
// The 'used' attribute prevents --gc-sections from removing it.
// On ARM64, .init_array entries are 8-byte function pointers.
typedef void (*init_func_t)(void);
__attribute__((used, section(".init_array")))
init_func_t lianyu_init_ptr = &lianyu_auto_decrypt;
