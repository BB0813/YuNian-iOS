package com.lianyu.ai.domain

/**
 * Controls access to bundled, provider-managed cloud capacity.
 *
 * This deliberately does not govern local features or user-configured API keys.
 */
interface BuiltinCloudAccessPolicy {
    fun isBuiltinCloudAccessAllowed(): Boolean

    fun denialReason(): String?
}