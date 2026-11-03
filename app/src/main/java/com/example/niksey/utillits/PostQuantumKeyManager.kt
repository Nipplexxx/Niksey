package com.example.niksey.utillits

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Ключ пользователя для протокола Signal и старой схемы: ECDH P-256 в Android Keystore.
 * Приватный ключ не покидает Keystore.
 *
 * Пост-квантовая часть здесь не реализована: её даёт протокол Signal (PQXDH, ML-KEM) — см. utillits/signal.
 */
object PostQuantumKeyManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val USER_ECDH_KEY_ALIAS = "niksey_user_ecdh_key_v2"

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun generateECDHKeyPair(): PublicKey {
        if (keyStore.containsAlias(USER_ECDH_KEY_ALIAS)) {
            return getECDHPublicKey()
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            USER_ECDH_KEY_ALIAS,
            KeyProperties.PURPOSE_AGREE_KEY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()

        keyPairGenerator.initialize(spec)
        return keyPairGenerator.generateKeyPair().public
    }

    fun getECDHPublicKey(): PublicKey {
        val entry = keyStore.getEntry(USER_ECDH_KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.certificate.publicKey
    }

    fun getECDHPrivateKey(): PrivateKey {
        val entry = keyStore.getEntry(USER_ECDH_KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey
    }
}
