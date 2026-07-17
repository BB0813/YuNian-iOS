package com.lianyu.ai.network

import okhttp3.CertificatePinner

/**
 * Certificate pinning — 仅对自建 Clove API (suflow.cloud) 固定证书。
 * 所有第三方 API 走系统 CA 信任链，不使用证书固定。
 *
 * Each pin is the SHA-256 hash of the server's SubjectPublicKeyInfo (SPKI).
 * Pins survive certificate renewal as long as the private key stays the same.
 *
 * To update pins: run tools/extract_cert_pins.py
 */
object CertificatePins {

    // ── SuFlowAPI / Clove API (suflow.cloud) ──
    // SPKI pin of TrustAsia DV TLS cert (valid until 2026-09-21)
    private const val SUFLOW_PIN = "sha256/Nh9PzSv3Z/jvrTTdRgBJWEp2CkPjcSHBtzZ2O8Nkmgs="

    /**
     * 仅固定 Clove API (suflow.cloud) 证书。
     * 第三方 API 不在此列表中，走系统默认 CA 验证。
     */
    val certificatePinner: CertificatePinner = CertificatePinner.Builder()
        .add("suflow.cloud", SUFLOW_PIN)
        .build()
}
