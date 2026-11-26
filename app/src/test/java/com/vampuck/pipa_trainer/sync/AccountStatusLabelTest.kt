package com.vampuck.pipa_trainer.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountStatusLabelTest {
    @Test fun `offline cloud and local data have distinct labels`() {
        assertEquals(AccountStatusLabel.OFFLINE_CLOUD,
            AccountStatusLabel.choose(true, SyncState.OFFLINE, showingCloud = true))
        assertEquals(AccountStatusLabel.OFFLINE_LOCAL,
            AccountStatusLabel.choose(true, SyncState.OFFLINE, showingCloud = false))
    }

    @Test fun `guests show local records and invalid sessions require auth regardless of data source`() {
        for (showingCloud in listOf(false, true)) {
            for (state in SyncState.values().filter { it != SyncState.AUTH_REQUIRED }) {
                assertEquals(AccountStatusLabel.LOCAL, AccountStatusLabel.choose(false, state, showingCloud))
            }
            for (loggedIn in listOf(false, true)) {
                assertEquals(AccountStatusLabel.AUTH_REQUIRED,
                    AccountStatusLabel.choose(loggedIn, SyncState.AUTH_REQUIRED, showingCloud))
            }
        }
    }

    @Test fun `online labels follow sync state regardless of data source`() {
        val labels = mapOf(SyncState.SYNCING to AccountStatusLabel.SYNCING,
            SyncState.OK to AccountStatusLabel.OK, SyncState.IDLE to AccountStatusLabel.IDLE)
        for (showingCloud in listOf(false, true)) {
            labels.forEach { (state, label) ->
                assertEquals(label, AccountStatusLabel.choose(true, state, showingCloud))
            }
        }
    }
}
