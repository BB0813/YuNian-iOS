// Runtime SO decrypt stub — pure C + inline ARM64 cache flush
#include <cstdint>
#include <sys/mman.h>

// Placeholders patched by pack_so.py at build time
extern "C" {
    void* lianyu_text_decrypt_ptr = nullptr;  // ptr to XOR key
    uint64_t lianyu_text_start = 0;           // .text VA
    uint64_t lianyu_text_size = 0;            // .text size
}

// D2 SO self-decrypt — called from JNI_OnLoad before any code runs
extern "C" void lianyu_d2_decrypt() {
    uint8_t* text_ptr = (uint8_t*)lianyu_text_start;
    uint64_t text_sz = lianyu_text_size;
    uint8_t* key_ptr = (uint8_t*)lianyu_text_decrypt_ptr;

    if (!text_ptr || !text_sz || !key_ptr) return;  // not packed

    // 1. Make .text writable
    uint64_t page_start = ((uint64_t)text_ptr) & ~0xFFFULL;
    uint64_t page_end = (((uint64_t)text_ptr) + text_sz + 0xFFF) & ~0xFFFULL;
    mprotect((void*)page_start, page_end - page_start, PROT_READ | PROT_WRITE | PROT_EXEC);

    // 2. XOR decrypt in-place
    for (uint64_t i = 0; i < text_sz; i++) {
        text_ptr[i] ^= key_ptr[i & 0xF];
    }

    // 3. Flush caches (ARM64)
    uint64_t cache_line = 64; // ARM64 cache line size
    for (uint64_t i = 0; i < text_sz; i += cache_line) {
        __builtin___clear_cache((char*)text_ptr + i, (char*)text_ptr + i + cache_line);
    }

    // 4. Restore RX
    mprotect((void*)page_start, page_end - page_start, PROT_READ | PROT_EXEC);

    // Wipe key
    for (int i = 0; i < 16; i++) key_ptr[i] = 0;

    // Mark as done
    lianyu_text_decrypt_ptr = nullptr;
}
