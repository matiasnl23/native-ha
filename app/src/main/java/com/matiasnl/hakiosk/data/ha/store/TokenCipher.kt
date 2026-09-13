package com.matiasnl.hakiosk.data.ha.store

/**
 * Encrypts secrets before they reach disk. Abstracted so the store logic can be unit tested on the
 * JVM, where the Android Keystore is not available.
 */
interface TokenCipher {
    /** Returns an opaque, printable ciphertext for [plaintext]. */
    fun encrypt(plaintext: String): String

    /** Returns the plaintext, or throws if [ciphertext] cannot be decrypted (e.g. the key was lost). */
    fun decrypt(ciphertext: String): String
}
