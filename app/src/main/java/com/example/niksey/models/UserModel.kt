package com.example.niksey.models

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class UserModel(
    var id: String = "",
    var username: String = "",
    var bio: String = "",
    var fullname: String = "",
    var state: Any = "",           // ← Any — принимает Long и String
    var phone: String = "",
    var photoUrl: String = "empty",
    var email: String = "",
    var password: String = "",

    // ==================== КЛАССИЧЕСКИЕ КЛЮЧИ (ECDH) ====================
    var publicKey: String = "",           // ECDH публичный ключ (P-256)

    // ==================== ПОСТ-КВАНТОВЫЕ КЛЮЧИ (ML-KEM / Kyber) ====================
    var kyberPublicKey: String = "",      // ML-KEM-768 публичный ключ (пост-квантовый)
    var kyberPrivateKeyRef: String = "",  // Ссылка на приватный ключ в Keystore (не храним в Firebase!)

    // ==================== X3DH КЛЮЧИ (для Signal-подобного протокола) ====================
    var signedPreKey: String = "",        // Signed PreKey (для X3DH)
    var oneTimePreKeys: List<String> = emptyList(), // One-Time PreKeys

    // ==================== МЕТАДАННЫЕ ====================
    var encryptionVersion: Int = 2,       // 1 = старое AES, 2 = гибридное пост-квантовое
    var lastKeyUpdate: Long = 0           // Время последнего обновления ключей
) {

    fun isOnline(): Boolean {
        val s = state
        return when (s) {
            is String -> s.lowercase() == "online" || s == "В сети"
            else -> false
        }
    }

    fun getDisplayName(): String {
        return fullname.ifBlank { username.ifBlank { phone.ifBlank { id } } }
    }

    fun getShortName(): String {
        return fullname.ifBlank { username }.take(20)
    }

    fun getStateText(): String {
        val s = state
        return when (s) {
            is String -> s
            is Long -> "был(а) недавно"
            else -> "offline"
        }
    }

    override fun toString(): String {
        return "UserModel(id='$id', username='$username', fullname='$fullname', state='$state')"
    }
}