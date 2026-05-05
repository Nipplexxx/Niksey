package com.example.niksey.database

import android.net.Uri
import com.example.niksey.R
import com.example.niksey.models.CommonModel
import com.example.niksey.models.UserModel
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.AppValueEventListener
import com.example.niksey.utillits.ChatEncryptionManager
import com.example.niksey.utillits.EncryptionUtils
import com.example.niksey.utillits.TYPE_GROUP
import com.example.niksey.models.UserDataManager
import com.example.niksey.utillits.AUTH
import com.example.niksey.utillits.CHILD_BIO
import com.example.niksey.utillits.CHILD_EMAIL
import com.example.niksey.utillits.CHILD_FILE_URL
import com.example.niksey.utillits.CHILD_FROM
import com.example.niksey.utillits.CHILD_FULLNAME
import com.example.niksey.utillits.CHILD_ID
import com.example.niksey.utillits.CHILD_PASSWORD
import com.example.niksey.utillits.CHILD_PHONE
import com.example.niksey.utillits.CHILD_PHOTO_URL
import com.example.niksey.utillits.CHILD_TEXT
import com.example.niksey.utillits.CHILD_TIMESTAMP
import com.example.niksey.utillits.CHILD_TYPE
import com.example.niksey.utillits.CHILD_USERNAME
import com.example.niksey.utillits.CURRENT_UID
import com.example.niksey.utillits.FOLDER_FILES
import com.example.niksey.utillits.FOLDER_GROUPS_IMAGE
import com.example.niksey.utillits.NODE_GROUPS
import com.example.niksey.utillits.NODE_MAIN_LIST
import com.example.niksey.utillits.NODE_MEMBERS
import com.example.niksey.utillits.NODE_MESSAGES
import com.example.niksey.utillits.NODE_PHONES
import com.example.niksey.utillits.NODE_PHONES_CONTACTS
import com.example.niksey.utillits.NODE_USERNAMES
import com.example.niksey.utillits.NODE_USERS
import com.example.niksey.utillits.REF_DATABASE_ROOT
import com.example.niksey.utillits.REF_STORAGE_ROOT
import com.example.niksey.utillits.USER
import com.example.niksey.utillits.USER_CREATOR
import com.example.niksey.utillits.USER_MEMBER
import com.example.niksey.utillits.showToast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import java.io.File
import java.util.UUID

val USER_PATH get() = "$NODE_USERS/$CURRENT_UID"

// ==================== ИНИЦИАЛИЗАЦИЯ ====================
fun initFirebase() {
    AUTH = FirebaseAuth.getInstance()
    REF_DATABASE_ROOT = FirebaseDatabase.getInstance().reference
    REF_STORAGE_ROOT = FirebaseStorage.getInstance().reference
    USER = UserModel()
    CURRENT_UID = AUTH.currentUser?.uid.toString()
}

// ==================== END-TO-END ШИФРОВАНИЕ ====================
fun ensureUserEncryptionKey(onComplete: (() -> Unit)? = null) {
    if (USER.publicKey.isNotBlank()) {
        // Ключ уже есть — просто кэшируем
        ChatEncryptionManager.cachePublicKey(CURRENT_UID, USER.publicKey)
        onComplete?.invoke()
        return
    }

    // Ключа нет — генерируем новый
    try {
        val ecdhPublicKey = EncryptionUtils.generateUserKeyPair()
        val ecdhPublicKeyBase64 = EncryptionUtils.publicKeyToBase64(ecdhPublicKey)

        REF_DATABASE_ROOT.child("$NODE_USERS/$CURRENT_UID/publicKey")
            .setValue(ecdhPublicKeyBase64)
            .addOnSuccessListener {
                USER.publicKey = ecdhPublicKeyBase64
                ChatEncryptionManager.cachePublicKey(CURRENT_UID, ecdhPublicKeyBase64)
                onComplete?.invoke()
            }
            .addOnFailureListener {
                showToast("Не удалось сохранить ключ шифрования")
                onComplete?.invoke()
            }
    } catch (e: Exception) {
        showToast("Ошибка генерации ключа: ${e.message}")
        onComplete?.invoke()
    }
}

// ==================== ФУНКЦИИ ====================

inline fun putFileToStorage(uri: Uri, path: StorageReference, crossinline function: () -> Unit) {
    path.putFile(uri)
        .addOnSuccessListener { function() }
        .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_loading, it.message)) }
}

inline fun initUser(crossinline function: () -> Unit) {
    REF_DATABASE_ROOT.child(USER_PATH)
        .addListenerForSingleValueEvent(AppValueEventListener {
            val firebaseUser = it.getValue(UserModel::class.java)

            if (firebaseUser != null && firebaseUser.username.isNotEmpty()) {
                USER = firebaseUser
                // Сохраняем данные пользователя локально
                UserDataManager.saveUser(APP_ACTIVITY, USER)
            } else {
                // Если Firebase не вернул данные — загружаем из SharedPreferences
                val savedUser = UserDataManager.loadUser(APP_ACTIVITY)
                if (savedUser != null) {
                    USER = savedUser
                } else {
                    USER = UserModel()
                    if (USER.username.isEmpty()) USER.username = CURRENT_UID
                }
            }
            ensureUserEncryptionKey {
                function()
            }
        })
}

fun updatePhonesToDatabase(arrayContacts: ArrayList<CommonModel>) {
    if (AUTH.currentUser == null) return

    val phoneToContact = arrayContacts.associateBy { it.phone }
    val updates = hashMapOf<String, Any>()

    REF_DATABASE_ROOT.child(NODE_PHONES)
        .addListenerForSingleValueEvent(AppValueEventListener { snapshot ->
            snapshot.children.forEach { phoneSnapshot ->
                val phone = phoneSnapshot.key ?: return@forEach
                val uid = phoneSnapshot.value.toString()
                phoneToContact[phone]?.let { contact ->
                    val base = "$NODE_PHONES_CONTACTS/$CURRENT_UID/$uid"
                    updates["$base/$CHILD_ID"] = uid
                    updates["$base/$CHILD_FULLNAME"] = contact.fullname
                }
            }
            if (updates.isNotEmpty()) REF_DATABASE_ROOT.updateChildren(updates)
        })
}

// ==================== ОТПРАВКА СООБЩЕНИЙ (С ШИФРОВАНИЕМ) ====================

fun sendMessage(message: String, receivingUserID: String, typeText: String, function: () -> Unit) {
    if (message.isBlank()) {
        showToast(APP_ACTIVITY.getString(R.string.message_cannot_be_empty))
        return
    }

    ChatEncryptionManager.getOtherUserPublicKey(receivingUserID) { otherPublicKeyBase64 ->
        if (otherPublicKeyBase64.isNullOrEmpty()) {
            showToast("Ошибка шифрования: у пользователя $receivingUserID нет публичного ключа. Попросите его зайти в приложение (чтобы сгенерировался ключ).")
            return@getOtherUserPublicKey
        }

        ChatEncryptionManager.getOrCreateChatKeyAsync(receivingUserID) { chatKey ->
            if (chatKey == null) {
                showToast("Ошибка шифрования: не удалось получить ключ чата")
                return@getOrCreateChatKeyAsync
            }

            val encryptedMessage = EncryptionUtils.encryptMessage(message, chatKey)

            val messageKey = REF_DATABASE_ROOT.child("$NODE_MESSAGES/$CURRENT_UID/$receivingUserID").push().key ?: return@getOrCreateChatKeyAsync

            val messageData = mapOf(
                CHILD_FROM to CURRENT_UID,
                CHILD_TYPE to typeText,
                CHILD_TEXT to encryptedMessage,
                CHILD_ID to messageKey,
                CHILD_TIMESTAMP to ServerValue.TIMESTAMP
            )

            REF_DATABASE_ROOT.updateChildren(
                mapOf(
                    "$NODE_MESSAGES/$CURRENT_UID/$receivingUserID/$messageKey" to messageData,
                    "$NODE_MESSAGES/$receivingUserID/$CURRENT_UID/$messageKey" to messageData
                )
            ).addOnSuccessListener { function() }
                .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_sending, it.message)) }
        }
    }
}

// ==================== ОТПРАВКА В ГРУППУ (ШИФРОВАНИЕ) ====================

fun sendMessageToGroup(message: String, groupID: String, typeText: String, function: () -> Unit) {
    if (message.isBlank()) {
        showToast(APP_ACTIVITY.getString(R.string.message_cannot_be_empty))
        return
    }

    // Получаем или создаём ключ группы
    ChatEncryptionManager.getOrCreateChatKeyAsync(groupID) { chatKey ->
        if (chatKey == null) {
            showToast("Ошибка шифрования: не удалось получить ключ группы")
            return@getOrCreateChatKeyAsync
        }

        val encryptedMessage = EncryptionUtils.encryptMessage(message, chatKey)

        val messageKey = REF_DATABASE_ROOT.child("$NODE_GROUPS/$groupID/$NODE_MESSAGES").push().key ?: return@getOrCreateChatKeyAsync

        val messageData = mapOf(
            CHILD_FROM to CURRENT_UID,
            CHILD_TYPE to typeText,
            CHILD_TEXT to encryptedMessage,
            CHILD_ID to messageKey,
            CHILD_TIMESTAMP to ServerValue.TIMESTAMP
        )

        REF_DATABASE_ROOT.child("$NODE_GROUPS/$groupID/$NODE_MESSAGES/$messageKey")
            .updateChildren(messageData)
            .addOnSuccessListener { function() }
            .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_sending_to_group)) }
    }
}

// ==================== ОСТАЛЬНЫЕ ФУНКЦИИ (БЕЗ ИЗМЕНЕНИЙ) ====================

fun updateCurrentUsername(newUserName: String) {
    if (newUserName.isBlank()) {
        showToast(APP_ACTIVITY.getString(R.string.username_cannot_be_empty))
        return
    }

    REF_DATABASE_ROOT.child("$USER_PATH/$CHILD_USERNAME")
        .setValue(newUserName)
        .addOnSuccessListener {
            showToast(APP_ACTIVITY.getString(R.string.toast_data_update))
            deleteOldUsername(newUserName)
        }
        .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_generic, it.message)) }
}

private fun deleteOldUsername(newUserName: String) {
    REF_DATABASE_ROOT.child("$NODE_USERNAMES/${USER.username}").removeValue()
        .addOnSuccessListener {
            APP_ACTIVITY.supportFragmentManager.popBackStack()
            USER.username = newUserName
        }
        .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_generic, it.message)) }
}

fun setBioToDatabase(newBio: String) = updateUserField(CHILD_BIO, newBio) { USER.bio = newBio; APP_ACTIVITY.mAppDrawer.updateHeader() }
fun setEmailToDatabase(newEmail: String) = updateUserField(CHILD_EMAIL, newEmail) { USER.email = newEmail; APP_ACTIVITY.mAppDrawer.updateHeader() }
fun setPasswordToDatabase(newPassword: String) = updateUserField(CHILD_PASSWORD, newPassword) { USER.password = newPassword; APP_ACTIVITY.mAppDrawer.updateHeader() }
fun setPhoneToDatabase(newPhone: String) = updateUserField(CHILD_PHONE, newPhone) { USER.phone = newPhone; APP_ACTIVITY.mAppDrawer.updateHeader() }

fun setNameToDatabase(fullname: String) {
    if (fullname.isBlank()) {
        showToast(APP_ACTIVITY.getString(R.string.name_cannot_be_empty))
        return
    }
    updateUserField(CHILD_FULLNAME, fullname) {
        USER.fullname = fullname
        APP_ACTIVITY.mAppDrawer.updateHeader()
    }
}

private fun updateUserField(field: String, value: String, onSuccess: () -> Unit) {
    REF_DATABASE_ROOT.child("$USER_PATH/$field")
        .setValue(value)
        .addOnSuccessListener {
            showToast(APP_ACTIVITY.getString(R.string.toast_data_update))
            onSuccess()
            APP_ACTIVITY.supportFragmentManager.popBackStack()
        }
        .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_generic, it.message)) }
}

fun removePhotoUser(@Suppress("UNUSED_PARAMETER") function1: String, function: () -> Unit) {
    REF_DATABASE_ROOT.child("$USER_PATH/$CHILD_PHOTO_URL").removeValue()
        .addOnSuccessListener { function() }
        .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_deleting_photo, it.message)) }
}

inline fun putUrlToDatabase(url: String, crossinline function: () -> Unit) {
    REF_DATABASE_ROOT.child("$USER_PATH/$CHILD_PHOTO_URL")
        .setValue(url)
        .addOnSuccessListener {
            USER.photoUrl = url
            APP_ACTIVITY.mAppDrawer.updateHeader()
            function()
        }
        .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_generic, it.message)) }
}

inline fun getUrlFromStorage(path: StorageReference, crossinline function: (url: String) -> Unit) {
    path.downloadUrl
        .addOnSuccessListener { function(it.toString()) }
        .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_generic, it.message)) }
}

inline fun putImageToStorage(uri: Uri, path: StorageReference, crossinline function: () -> Unit) {
    path.putFile(uri)
        .addOnSuccessListener { function() }
        .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_generic, it.message)) }
}

fun getMessageKey(id: String) = REF_DATABASE_ROOT.child("$NODE_MESSAGES/$CURRENT_UID/$id").push().key.toString()
fun getMessageKeyGroup(id: String) = REF_DATABASE_ROOT.child("$NODE_GROUPS/$id/$NODE_MESSAGES").push().key.toString()

fun getFileFromStorage(mFile: File, fileUrl: String, function: () -> Unit) {
    REF_STORAGE_ROOT.storage.getReferenceFromUrl(fileUrl)
        .getFile(mFile)
        .addOnSuccessListener { function() }
        .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_generic, it.message)) }
}

fun uploadFileToStorage(uri: Uri, messageKey: String, receivedID: String, typeMessage: String, filename: String = "", onComplete: () -> Unit = {}) {
    val path = REF_STORAGE_ROOT.child("$FOLDER_FILES/$messageKey")
    putFileToStorage(uri, path) {
        getUrlFromStorage(path) { url ->
            sendMessageAsFile(receivedID, url, messageKey, typeMessage, filename)
            onComplete()
        }
    }
}

fun uploadFileToStorageGroup(uri: Uri, messageKey: String, groupID: String, typeMessage: String, filename: String = "", onComplete: () -> Unit = {}) {
    val path = REF_STORAGE_ROOT.child("$FOLDER_FILES/$messageKey")
    putFileToStorage(uri, path) {
        getUrlFromStorage(path) { url ->
            sendMessageAsFileGroup(groupID, url, messageKey, typeMessage, filename)
            onComplete()
        }
    }
}

private fun sendMessageAsFile(receivingUserID: String, fileUrl: String, messageKey: String, typeMessage: String, filename: String) {
    val messageData = mapOf(
        CHILD_FROM to CURRENT_UID,
        CHILD_TYPE to typeMessage,
        CHILD_ID to messageKey,
        CHILD_TIMESTAMP to ServerValue.TIMESTAMP,
        CHILD_FILE_URL to fileUrl,
        CHILD_TEXT to filename
    )
    REF_DATABASE_ROOT.updateChildren(
        mapOf(
            "$NODE_MESSAGES/$CURRENT_UID/$receivingUserID/$messageKey" to messageData,
            "$NODE_MESSAGES/$receivingUserID/$CURRENT_UID/$messageKey" to messageData
        )
    )
}

private fun sendMessageAsFileGroup(groupID: String, fileUrl: String, messageKey: String, typeMessage: String, filename: String) {
    val messageData = mapOf(
        CHILD_FROM to CURRENT_UID,
        CHILD_TYPE to typeMessage,
        CHILD_ID to messageKey,
        CHILD_TIMESTAMP to ServerValue.TIMESTAMP,
        CHILD_FILE_URL to fileUrl,
        CHILD_TEXT to filename
    )
    REF_DATABASE_ROOT.child("$NODE_GROUPS/$groupID/$NODE_MESSAGES/$messageKey")
        .updateChildren(messageData)
}

fun createGroupToDatabase(nameGroup: String, uri: Uri, listContacts: List<CommonModel>, function: () -> Unit) {
    if (nameGroup.isBlank()) {
        showToast(APP_ACTIVITY.getString(R.string.group_name_cannot_be_empty))
        return
    }

    val groupId = REF_DATABASE_ROOT.child(NODE_GROUPS).push().key ?: return
    val groupPath = REF_DATABASE_ROOT.child("$NODE_GROUPS/$groupId")
    val storagePath = REF_STORAGE_ROOT.child("$FOLDER_GROUPS_IMAGE/$groupId")

    val members = listContacts.associate { it.id to USER_MEMBER }.toMutableMap()
    members[CURRENT_UID] = USER_CREATOR

    val groupData = mapOf(
        CHILD_ID to groupId,
        CHILD_FULLNAME to nameGroup,
        CHILD_PHOTO_URL to "empty",
        NODE_MEMBERS to members
    )

    groupPath.updateChildren(groupData).addOnSuccessListener {
        // Создаём ключ шифрования для группы
        ChatEncryptionManager.getOrCreateChatKeyAsync(groupId) { _ -> }

        if (uri != Uri.EMPTY) {
            putFileToStorage(uri, storagePath) {
                getUrlFromStorage(storagePath) { url ->
                    groupPath.child(CHILD_PHOTO_URL).setValue(url)
                    addGroupsToMainList(groupData, listContacts, function)
                }
            }
        } else {
            addGroupsToMainList(groupData, listContacts, function)
        }
    }.addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_creating_group, it.message)) }
}

fun addGroupsToMainList(mapData: Map<String, Any>, listContacts: List<CommonModel>, function: () -> Unit) {
    val groupId = mapData[CHILD_ID].toString()
    val updates = hashMapOf<String, Any>()

    listContacts.forEach {
        updates["$NODE_MAIN_LIST/${it.id}/$groupId"] = mapOf(CHILD_ID to groupId, CHILD_TYPE to TYPE_GROUP)
    }
    updates["$NODE_MAIN_LIST/$CURRENT_UID/$groupId"] = mapOf(CHILD_ID to groupId, CHILD_TYPE to TYPE_GROUP)

    REF_DATABASE_ROOT.updateChildren(updates)
        .addOnSuccessListener { function() }
        .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_generic, it.message)) }
}

fun saveToMainList(id: String, type: String) {
    val data = mapOf(CHILD_ID to id, CHILD_TYPE to type)
    REF_DATABASE_ROOT.updateChildren(
        mapOf(
            "$NODE_MAIN_LIST/$CURRENT_UID/$id" to data,
            "$NODE_MAIN_LIST/$id/$CURRENT_UID" to mapOf(CHILD_ID to CURRENT_UID, CHILD_TYPE to type)
        )
    )
}

fun deleteChat(id: String, function: () -> Unit) {
    REF_DATABASE_ROOT.updateChildren(
        mapOf(
            "$NODE_MESSAGES/$CURRENT_UID/$id" to null,
            "$NODE_MESSAGES/$id/$CURRENT_UID" to null,
            "$NODE_MAIN_LIST/$CURRENT_UID/$id" to null
        )
    ).addOnSuccessListener { function() }
}

fun clearChat(id: String, function: () -> Unit) {
    REF_DATABASE_ROOT.updateChildren(
        mapOf(
            "$NODE_MESSAGES/$CURRENT_UID/$id" to null,
            "$NODE_MESSAGES/$id/$CURRENT_UID" to null
        )
    ).addOnSuccessListener { function() }
}

fun removeChat(id: String, function: () -> Unit) {
    REF_DATABASE_ROOT.child("$NODE_MAIN_LIST/$CURRENT_UID/$id").removeValue()
        .addOnSuccessListener { function() }
}

fun deleteChatGroup(id: String, function: () -> Unit) {
    REF_DATABASE_ROOT.updateChildren(
        mapOf(
            "$NODE_MAIN_LIST/$CURRENT_UID/$id" to null,
            "$NODE_GROUPS/$id" to null,
            "$NODE_GROUPS/$id/$NODE_MESSAGES/$CURRENT_UID" to null
        )
    ).addOnSuccessListener { function() }
}

fun clearChatGroup(id: String, function: () -> Unit) {
    REF_DATABASE_ROOT.child("$NODE_GROUPS/$id/$NODE_MESSAGES").removeValue()
        .addOnSuccessListener { function() }
}

fun removeChatGroup(id: String, function: () -> Unit) {
    REF_DATABASE_ROOT.child("$NODE_MAIN_LIST/$CURRENT_UID/$id").removeValue()
        .addOnSuccessListener { function() }
}

fun DataSnapshot.getCommonModel(): CommonModel = getValue(CommonModel::class.java) ?: CommonModel()
fun DataSnapshot.getUserModel(): UserModel {
    return try {
        getValue(UserModel::class.java) ?: UserModel()
    } catch (_: Exception) {
        val map = getValue(object : com.google.firebase.database.GenericTypeIndicator<Map<String, Any>>() {})
            ?: return UserModel()

        UserModel(
            id = map["id"] as? String ?: "",
            username = map["username"] as? String ?: "",
            bio = map["bio"] as? String ?: "",
            fullname = map["fullname"] as? String ?: "",
            state = map["state"] ?: "",
            phone = map["phone"] as? String ?: "",
            photoUrl = map["photoUrl"] as? String ?: "empty",
            email = map["email"] as? String ?: "",
            password = map["password"] as? String ?: "",
            publicKey = map["publicKey"] as? String ?: "",
            kyberPublicKey = map["kyberPublicKey"] as? String ?: "",
            encryptionVersion = (map["encryptionVersion"] as? Number)?.toInt() ?: 2
        )
    }
}

fun generateRandomUsername(): String = "user${UUID.randomUUID().toString().substring(0, 8)}"

fun generateRandomFullname(): String {
    val prefixes = listOf(
        "Shadow", "Ghost", "Neon", "Cyber", "Phantom", "Vortex", "Nebula", "Echo",
        "Nova", "Pulse", "Raven", "Hawk", "Wolf", "Fox", "Dragon", "Phoenix", "Cobra", "Tiger"
    )
    val suffixes = listOf(
        "42", "93", "17", "88", "X", "Z", "Pro", "Elite", "Dark", "Void", "Storm", "Blade", "Nova"
    )
    return "${prefixes.random()}${suffixes.random()}"
}