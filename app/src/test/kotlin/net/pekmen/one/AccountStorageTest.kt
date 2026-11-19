package net.pekmen.one

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.pekmen.one.account.PersonalAccountStorage
import net.pekmen.one.account.localLeaseIsValid
import net.pekmen.one.core.cloud.auth.AuthSessionRecord
import net.pekmen.one.core.model.AccountId
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.PersonalSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountStorageTest {
    @Test
    fun accountStoresDoNotShareSessionsOrSetup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstId = AccountId.parse("2c0d3f33-86ed-4954-a075-905695a6a4bc")
        val secondId = AccountId.parse("686c0190-d9c9-4a11-8b14-80108e49322c")
        val first = PersonalAccountStorage(context, firstId, backgroundScope)
        val second = PersonalAccountStorage(context, secondId, backgroundScope)
        try {
            val firstAccount = AppContainer(context, first)
            val secondAccount = AppContainer(context, second)
            val completedSetup = PersonalSetup(name = "Account A", username = "account_a", completed = true)
            first.preferences.savePersonalSetup(completedSetup, applySelectedProfile = true)
            first.preferences.setSelectedProfileId(BuiltInProfiles.WORK_ID)
            first.preferences.savePersonalSetup(completedSetup.copy(name = "Account A Updated"))
            assertFalse(second.preferences.personalSetup.first().completed)
            assertEquals("Account A Updated", first.preferences.personalSetup.first().name)
            assertEquals(BuiltInProfiles.WORK_ID, first.preferences.preferences.first().selectedProfileId)
            assertEquals(first.accountId.value, first.preferences.preferences.first().activeOwnerUserId)
            assertEquals(second.accountId.value, second.preferences.preferences.first().activeOwnerUserId)
            firstAccount.sessionRepository.startSession(BuiltInProfiles.STUDY_ID, FocusSessionMode.STOPWATCH)
                .getOrThrow()
            assertNotNull(firstAccount.sessionRepository.activeSession.first())
            assertNull(secondAccount.sessionRepository.activeSession.first())
        } finally {
            first.database.close()
            second.database.close()
        }
    }

    @Test
    fun offlineLeaseRequiresARecentVerifiedIdentity() {
        val start = 123456L
        val record = AuthSessionRecord(
            "id", "user@example.test", accessToken = "a", refreshToken = "r", expiresAtWallMs = 0
        )
        assertFalse(localLeaseIsValid(record, start))
        val verified = record.copy(verifiedAtWallMs = start)
        assertTrue(localLeaseIsValid(verified, start + 1))
        assertFalse(localLeaseIsValid(verified, start - 1))
        assertFalse(localLeaseIsValid(verified, start + 7 * 86_400_000L))
    }
}
