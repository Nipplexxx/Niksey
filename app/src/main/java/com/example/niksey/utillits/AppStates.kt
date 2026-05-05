package com.example.niksey.utillits

enum class AppStates(val state: String) {
    ONLINE("online"),
    OFFLINE("offline"),
    TYPING("prints");

    companion object {
        fun updateState(appStates: AppStates) {
            if (AUTH.currentUser != null) {
                REF_DATABASE_ROOT.child(NODE_USERS).child(CURRENT_UID).child(CHILD_STATE)
                    .setValue(appStates.state)
                    .addOnSuccessListener { USER.state = appStates.state }
                    .addOnFailureListener { }
            }
        }

        fun getCurrentState(): AppStates {
            return when (USER.state) {
                ONLINE.state -> ONLINE
                OFFLINE.state -> OFFLINE
                else -> OFFLINE
            }
        }
    }
}
