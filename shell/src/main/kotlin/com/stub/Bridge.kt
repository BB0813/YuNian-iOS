package com.stub

/**
 * JNI method declarations — used by RegisterNatives in native code.
 * All method names are intentionally non-descriptive (360-style).
 * 
 * Method mapping:
 *   interface13 → native_load_payload(ctx, realAppClass) → decrypt + load
 *   interface14 → native_get_config(key)                  → read shell config
 *   interface15 → native_verify_and_load()                → verify + create ClassLoader
 */
object Bridge {
    init {
        System.loadLibrary("lianyu_shell")
    }

    @JvmStatic external fun interface13(ctx: Any): Int
    @JvmStatic external fun interface14(): String
    @JvmStatic external fun interface15(): Boolean
}
