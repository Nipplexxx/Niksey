package com.example.niksey.utillits

import com.example.niksey.MainActivity
import com.example.niksey.models.UserModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.StorageReference

// ==================== КОНСТАНТЫ ====================
const val NODE_USERS = "users"
const val NODE_MAIN_LIST = "main_list"
const val NODE_PHONES_CONTACTS = "phone_book"
const val NODE_PHONES = "phones_and_uid"
const val NODE_MESSAGES = "private_messages"
const val NODE_GROUPS = "groups_messages"
const val CHILD_PUBLIC_KEY = "publicKey"
const val NODE_USERNAMES = "usernames"
const val FOLDER_PROFILE_IMAGE = "profile_image"
const val CHILD_ID = "id"
const val CHILD_USERNAME = "username"
const val CHILD_PHONE = "phone"
const val CHILD_FULLNAME = "fullname"
const val CHILD_BIO = "bio"
const val CHILD_EMAIL = "email"
const val CHILD_PASSWORD = "password"
const val CHILD_PHOTO_URL = "photoUrl"
const val FOLDER_FILES = "messages_files"
const val CHILD_STATE = "state"
const val USER_MEMBER = "member"
const val USER_CREATOR = "creator"
const val CHILD_FILE_URL = "fileUrl"
const val FOLDER_GROUPS_IMAGE = "groups_image"
const val NODE_MEMBERS = "members"
const val TYPE_TEXT = "text"
const val CHILD_TEXT = "text"
const val CHILD_TYPE = "type"
const val CHILD_FROM = "from"
const val CHILD_TIMESTAMP = "timeStamp"
const val TYPE_CHAT ="chat"
const val TYPE_GROUP ="group"
const val NODE_PARTICIPANTS = "participants"
const val TYPE_MESSAGE_IMAGE = "image"
const val TYPE_MESSAGE_TEXT = "text"
const val TYPE_MESSAGE_VOICE ="voice"
const val TYPE_MESSAGE_FILE = "file"
const val TYPE_MESSAGE_VIDEO = "video"

// ==================== ГЛОБАЛЬНЫЕ ПЕРЕМЕННЫЕ ====================
lateinit var AUTH: FirebaseAuth
lateinit var CURRENT_UID: String
lateinit var REF_DATABASE_ROOT: com.google.firebase.database.DatabaseReference
lateinit var REF_STORAGE_ROOT: StorageReference
lateinit var USER: UserModel
lateinit var APP_ACTIVITY:MainActivity