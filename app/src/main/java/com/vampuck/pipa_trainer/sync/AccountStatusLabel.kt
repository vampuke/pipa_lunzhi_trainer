package com.vampuck.pipa_trainer.sync

/** Select wording using the source actually displayed on the home screen. */
enum class AccountStatusLabel {
    LOCAL, SYNCING, OK, OFFLINE_CLOUD, OFFLINE_LOCAL, IDLE, AUTH_REQUIRED;

    companion object {
        fun choose(loggedIn: Boolean, state: SyncState, showingCloud: Boolean): AccountStatusLabel {
            if (!loggedIn && state != SyncState.AUTH_REQUIRED) return LOCAL
            return when (state) {
                SyncState.SYNCING -> SYNCING
                SyncState.OK -> OK
                SyncState.OFFLINE -> if (showingCloud) OFFLINE_CLOUD else OFFLINE_LOCAL
                SyncState.IDLE -> IDLE
                SyncState.AUTH_REQUIRED -> AUTH_REQUIRED
            }
        }
    }
}
