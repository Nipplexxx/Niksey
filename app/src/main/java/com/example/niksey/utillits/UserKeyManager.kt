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
 * Ключ пользователя ECDH P-256 в Android Keystore: им выводятся ключи личных бесед (см. [EncryptionUtils]).
 * Приватный ключ не покидает Keystore.
 *
 * Ключи Signal (включая пост-квантовый PQXDH) хранятся отдельно, в utillits/signal.
 */
object UserKeyManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    /** Имя ключа в Keystore. Менять нельзя: от него зависит расшифровка старых сообщений. */
    private const val USER_ECDH_KEY_ALIAS = "niksey_user_ecdh_key_v2"

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /** Создаёт ключ, если его ещё нет, и возвращает публичную часть. */
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
