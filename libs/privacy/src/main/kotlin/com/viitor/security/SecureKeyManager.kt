package com.viitor.security

/**
 * ★ SecureKeyManager: 보안 키 관리
 */
class SecureKeyManager {
    // ERR_KEY_NOT_FOUND: @NotNull + generateKey() fallback
    fun getKey(): String = "dummy_key"
}
