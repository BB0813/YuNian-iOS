#ifndef LIANYU_VM_ENGINE_H
#define LIANYU_VM_ENGINE_H

#include <cstdint>
#include <cstddef>

#ifdef __cplusplus
extern "C" {
#endif

/* ========== VM Instruction Set ==========
 * All instructions are 16-bit aligned.
 * Format: [opcode:8] [operands:...]
 * Registers: R0-R15 (32-bit each)
 * Stack: 256 x 32-bit
 */

/* Opcodes */
enum VMOpcode : uint8_t {
    // Data movement (0x01-0x0F)
    OP_NOP       = 0x00,
    OP_LOAD_IMM  = 0x01,  // LOAD_IMM rd, imm32    [op:1][rd:4][imm:32]
    OP_LOAD_REG  = 0x02,  // LOAD_REG rd, rs       [op:1][rd:4][rs:4]
    OP_STORE_REG = 0x03,  // STORE_REG rd, rs
    OP_LOAD_MEM  = 0x04,  // LOAD_MEM rd, rs       *(uint32_t*)rs
    OP_STORE_MEM = 0x05,  // STORE_MEM addr_reg, rs *addr = rs

    // Arithmetic/Logic (0x10-0x1F)
    OP_ADD  = 0x10,  // ADD rd, rs1, rs2
    OP_SUB  = 0x11,  // SUB rd, rs1, rs2
    OP_XOR  = 0x12,  // XOR rd, rs1, rs2
    OP_AND  = 0x13,
    OP_OR   = 0x14,
    OP_SHL  = 0x15,  // rd = rs1 << rs2
    OP_SHR  = 0x16,  // rd = rs1 >> rs2 (logical)
    OP_ADD_IMM = 0x17, // ADD_IMM rd, imm32   [op:1][rd:4][imm:32]

    // Crypto
    OP_SBOX  = 0x18, // rd = SBOX[rs & 0xFF]
    OP_GFMUL = 0x19, // rd = gf_mul(rs1 & 0xFF, rs2 & 0xFF)
    OP_XTIME = 0x1A, // rd = xtime(rs & 0xFF)
    OP_MUL   = 0x1B, // MUL rd, rs1, rs2     [op:1][rd:4][rs1:4][rs2:4]
    OP_MUL_IMM = 0x1C, // MUL_IMM rd, imm32    [op:1][rd:4][imm:32]

    // Control
    OP_CMP     = 0x20,  // CMP rs1, rs2 (sets flags)
    OP_CMP_IMM = 0x26,  // CMP_IMM rs1, imm32 [op:1][rs1:4][imm:32]
    OP_JMP  = 0x21,  // JMP addr [op:1][addr:32]
    OP_JE   = 0x22,  // JE  addr — jump if equal
    OP_JNE  = 0x23,  // JNE addr — jump if not equal
    OP_JG   = 0x24,  // JG  addr — jump if greater
    OP_JL   = 0x25,  // JL  addr — jump if less
    OP_JGE  = 0x27,  // JGE addr — jump if >= (Z|C)
    OP_CALL = 0x30,  // CALL addr (push return addr)
    OP_RET  = 0x31,  // RET
    OP_HYPERCALL = 0x32, // HYPERCALL func_id, rd, rs1, rs2
                          //   [op:8][func_id:8][rd:4][rs1:4][rs2:4][pad:8]
                          //   Hypervisor call for syscall-level operations
    OP_HALT = 0xFF,
};

/* VM State */
typedef struct {
    uint32_t regs[16];      // R0-R15
    uint32_t stack[256];    // call/scratch stack
    uint32_t sp;            // stack pointer
    uint32_t pc;            // program counter (byte offset)
    uint32_t flags;         // CMP results: bit0=Z, bit1=C
    const uint8_t* code;    // bytecode
    uint32_t code_size;     // bytecode size in bytes
    uint32_t steps;         // instruction counter
    int halted;             // 1 = halted
    int error;              // error code
    /* === VMP Hardening (Task 2.3) === */
    uint32_t bytecode_crc;     /**< Expected CRC32 of bytecode (set at init) */
    uint64_t last_tick_ts;     /**< Monotonic timestamp of last instruction (anti-singlestep) */
    uint32_t tick_count;       /**< Instruction counter for periodic integrity check */
    uint32_t integrity_seed;   /**< Random seed for per-run code hash */
    uint8_t  tampered;         /**< Set to 1 if integrity violation detected */
} VMState;

/* Initialize VM with bytecode */
void vm_init(VMState* vm, const uint8_t* bytecode, uint32_t size);

/* Execute until HALT or max_steps */
int vm_run(VMState* vm, uint32_t max_steps);

/* Set register value */
void vm_set_reg(VMState* vm, int reg, uint32_t value);

/* Get register value */
uint32_t vm_get_reg(VMState* vm, int reg);

/* ========== Bytecode Builders ========== */

/* Hypercall function IDs */
#define VM_HYPER_READ_FILE  0   // rs1=filename_ptr, rs2=buf_ptr → rd=bytes_read
#define VM_HYPER_TRACER     1   // → rd=1 if traced, 0 if clean
#define VM_HYPER_DELAY      2   // rd=milliseconds to sleep
#define VM_HYPER_FRIDA      3   // → rd=1 if frida detected
#define VM_HYPER_CRC32      4   // → rd=CRC32 of .text section
#define VM_HYPER_ROOT_CHECK 5   // → rd=1 if rooted
#define VM_HYPER_KMS_STATUS 6   // → rd=KMS state (0=uninit,1=ready,-1=destroyed)
#define VM_HYPER_KMS_INIT   7   // → rd=1 if KMS init OK
#define VM_HYPER_KDF_SM3    8   // rs1=ctx_ptr, rs2=ctx_len → rd=buf_ptr(32B in scratch)
#define VM_HYPER_WB_AES_DEC 9   // rs1=in_ptr, rs2=out_ptr → rd=1 ok
#define VM_HYPER_SM3_HASH   10  // rs1=data_ptr, rs2=data_len → rd=hash_ptr
#define VM_HYPER_TEE_ATTEST 11  // → rd=1 if TEE attestation passes
#define VM_HYPER_SIG_VERIFY 12  // → rd=1 if APK signature valid
#define VM_HYPER_SECURE_WIPE 13 // rs1=ptr, rs2=len → secure wipe
#define VM_HYPER_WB_AES_KEYCHECK 14 // → rd=1 if WB-AES T-Box integrity OK
#define VM_HYPER_SM4_KEY_EXPAND   15 // rs1=key_ptr(16B), rs2=rk_ptr(128B out) → rd=1 ok
#define VM_HYPER_SM4_DECRYPT_BLOCK 16 // rs1=rk_ptr, rs2=block_ptr(16B in/out) → rd=1 ok

/* Encode instructions into bytecode buffer.
 * Returns bytes written. */
void vm_encode_nop(uint8_t* buf, uint32_t* off);
void vm_encode_load_imm(uint8_t* buf, uint32_t* off, uint8_t rd, uint32_t imm);
void vm_encode_add(uint8_t* buf, uint32_t* off, uint8_t rd, uint8_t rs1, uint8_t rs2);
void vm_encode_xor(uint8_t* buf, uint32_t* off, uint8_t rd, uint8_t rs1, uint8_t rs2);
void vm_encode_sbox(uint8_t* buf, uint32_t* off, uint8_t rd, uint8_t rs);
void vm_encode_cmp(uint8_t* buf, uint32_t* off, uint8_t rs1, uint8_t rs2);
void vm_encode_jmp(uint8_t* buf, uint32_t* off, uint32_t addr);
void vm_encode_je(uint8_t* buf, uint32_t* off, uint32_t addr);
void vm_encode_halt(uint8_t* buf, uint32_t* off);
void vm_encode_hypercall(uint8_t* buf, uint32_t* off, uint8_t func_id, uint8_t rd, uint8_t rs1, uint8_t rs2);
/* v2.0 extended encoders */
void vm_encode_add_imm(uint8_t* buf, uint32_t* off, uint8_t rd, uint32_t imm);
void vm_encode_mul(uint8_t* buf, uint32_t* off, uint8_t rd, uint8_t rs1, uint8_t rs2);
void vm_encode_mul_imm(uint8_t* buf, uint32_t* off, uint8_t rd, uint32_t imm);
void vm_encode_cmp_imm(uint8_t* buf, uint32_t* off, uint8_t rs, uint32_t imm);

/* ========== Pre-compiled VM Bytecode Entry Points ========== */

/* VM program that decrypts an AES block using bytecode.
 * Runs entirely inside the VM — no key material exposed. */
extern const uint8_t g_vm_aes_decrypt[];
extern const uint32_t g_vm_aes_decrypt_size;

/* VM program that checks TracerPid (anti-debug) */
extern const uint8_t g_vm_check_tracer[];
extern const uint32_t g_vm_check_tracer_size;

/* VMP v2.0 — Core Security Bytecode Programs */
extern const uint8_t g_vmp_wb_aes_keycheck[];
extern const uint32_t g_vmp_wb_aes_keycheck_size;
extern const uint8_t g_vmp_kms_derive_sk[];
extern const uint32_t g_vmp_kms_derive_sk_size;
extern const uint8_t g_vmp_tee_attest[];
extern const uint32_t g_vmp_tee_attest_size;
extern const uint8_t g_vmp_apk_sig_verify[];
extern const uint32_t g_vmp_apk_sig_verify_size;

/* VMP v3.0 — Extended Security Bytecode Programs */
extern const uint8_t g_vmp_root_detect[];
extern const uint32_t g_vmp_root_detect_size;
extern const uint8_t g_vmp_code_integrity[];
extern const uint32_t g_vmp_code_integrity_size;
extern const uint8_t g_vmp_sm3_hash[];
extern const uint32_t g_vmp_sm3_hash_size;
extern const uint8_t g_vmp_frida_heartbeat[];
extern const uint32_t g_vmp_frida_heartbeat_size;

/* Wipe all cached key material and VM state from memory.
 * Called by zero-trust incident response on BREACH (ZT_ACTION_WIPE).
 * Zeroes internal VM key cache, register file, and any buffered secrets.
 */
void vm_engine_wipe_cache(void);

/* === VMP Hardening (Task 2.3) === */

/** Compute CRC32 of bytecode and store in VMState */
void vm_compute_crc(VMState* vm);

/** Verify bytecode integrity (compares live CRC with stored) */
int vm_verify_integrity(VMState* vm);

/** Anti-singlestep: measure instruction timing */
int vm_check_timing(VMState* vm);

/** Interpreter self-hash: verify the native interpreter code */
int vm_verify_interpreter(VMState* vm);

/** Save interpreter prologue at init time for later verification */
void vm_save_interpreter_prologue(void);

/** Full security checkpoint: runs all VMP hardening checks */
int vm_security_checkpoint(VMState* vm);

#ifdef __cplusplus
}
#endif

#endif /* LIANYU_VM_ENGINE_H */
