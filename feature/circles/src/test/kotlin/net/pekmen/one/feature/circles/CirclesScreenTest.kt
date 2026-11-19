package net.pekmen.one.feature.circles

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import net.pekmen.one.core.designsystem.theme.PekmenOneTheme
import net.pekmen.one.core.model.CircleMemberStanding
import net.pekmen.one.core.model.CircleMembership
import net.pekmen.one.core.model.CirclePeriodType
import net.pekmen.one.core.model.CircleRole
import net.pekmen.one.core.model.CircleSharingConfig
import net.pekmen.one.core.model.CloudRlsGateStatus
import net.pekmen.one.core.model.SocialCircle
import net.pekmen.one.core.model.SubjectSharedTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class CirclesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "en")
    fun `gated offline mode shows gate banner privacy switches off and distinct live vs manual standings`() {
        val circle = SocialCircle(
            id = "c-1",
            name = "Study Squad",
            inviteCode = "ABCD-2345-EFGH-6789",
            createdByUserId = "user-alice",
            createdAtMs = 1_000L
        )
        val membership = CircleMembership(
            id = "m-1",
            circleId = "c-1",
            userId = "user-alice",
            displayName = "Alice",
            role = CircleRole.OWNER,
            sharingConfig = CircleSharingConfig(),
            joinedAtMs = 1_000L
        )
        val standings = listOf(
            CircleMemberStanding(
                rank = 1,
                circleId = "c-1",
                userId = "user-bob",
                displayName = "Bob",
                periodType = CirclePeriodType.DAILY,
                periodStartDate = "2026-11-19",
                liveTimerFocusSec = 1_500L,
                manualEntryFocusSec = 900L,
                subjectTotals = listOf(SubjectSharedTotal("Almanca B2", 2_400L)),
                isCurrentlyFocusing = true,
                updatedAtMs = 2_000L
            )
        )

        composeRule.setContent {
            PekmenOneTheme {
                CirclesScreen(
                    state = CirclesUiState(
                        gateStatus = CloudRlsGateStatus(
                            compiledWithCloudFlag = false,
                            backendEndpointConfigured = false,
                            rlsVerified = false
                        ),
                        isSignedIn = true,
                        circles = listOf(circle),
                        selectedCircleId = circle.id,
                        selectedCircle = circle,
                        activeMemberships = listOf(membership),
                        myMembership = membership,
                        leaderboard = standings,
                        availableSubjectTitles = listOf("Almanca B2", "Kisisel")
                    ),
                    actions = CirclesScreenActions()
                )
            }
        }

        composeRule.onNodeWithText("Offline mode active (cloud sync gated)").assertIsDisplayed()

        composeRule.onNodeWithTag("circles_list").performScrollToNode(hasText("Share progress"))
        composeRule.onNodeWithText("Share progress").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Share progress").assertIsOff()
        composeRule.onNodeWithText("Share live status").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Share live status").assertIsOff()

        composeRule.onNodeWithTag("circles_list").performScrollToNode(hasText("Almanca B2"))
        composeRule.onNodeWithText("Almanca B2").assertIsDisplayed()

        composeRule.onNodeWithTag("circles_list").performScrollToNode(hasText("Live timer: 25 min"))
        composeRule.onNodeWithText("Focusing now").assertIsDisplayed()
        composeRule.onNodeWithText("Live timer: 25 min").assertIsDisplayed()
        composeRule.onNodeWithText("Manual logged: 15 min").assertIsDisplayed()
        composeRule.onNodeWithText("Almanca B2 · 40 min").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "tr")
    fun `turkish locale renders gate banner switches and leaderboard labels`() {
        val circle = SocialCircle(
            id = "c-1",
            name = "Çalışma Grubu",
            inviteCode = "ABCD-2345-EFGH-6789",
            createdByUserId = "user-alice",
            createdAtMs = 1_000L
        )
        val membership = CircleMembership(
            id = "m-1",
            circleId = "c-1",
            userId = "user-alice",
            displayName = "Alice",
            role = CircleRole.OWNER,
            sharingConfig = CircleSharingConfig(),
            joinedAtMs = 1_000L
        )
        val standings = listOf(
            CircleMemberStanding(
                rank = 1,
                circleId = "c-1",
                userId = "user-bob",
                displayName = "Bob",
                periodType = CirclePeriodType.DAILY,
                periodStartDate = "2026-11-19",
                liveTimerFocusSec = 1_800L,
                manualEntryFocusSec = 600L,
                subjectTotals = emptyList(),
                isCurrentlyFocusing = false,
                updatedAtMs = 2_000L
            )
        )

        composeRule.setContent {
            PekmenOneTheme {
                CirclesScreen(
                    state = CirclesUiState(
                        gateStatus = CloudRlsGateStatus(
                            compiledWithCloudFlag = false,
                            backendEndpointConfigured = false,
                            rlsVerified = false
                        ),
                        isSignedIn = false,
                        circles = listOf(circle),
                        selectedCircleId = circle.id,
                        selectedCircle = circle,
                        activeMemberships = listOf(membership),
                        myMembership = membership,
                        leaderboard = standings
                    ),
                    actions = CirclesScreenActions()
                )
            }
        }

        composeRule.onNodeWithText("Özel Çemberler").assertIsDisplayed()
        composeRule.onNodeWithText("Çevrimdışı mod etkin (bulut eşitleme kapalı)").assertIsDisplayed()
        composeRule.onNodeWithTag("circles_list").performScrollToNode(hasText("İlerlemeyi paylaş"))
        composeRule.onNodeWithText("İlerlemeyi paylaş").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("İlerlemeyi paylaş").assertIsOff()
        composeRule.onNodeWithText("Canlı durumu paylaş").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Canlı durumu paylaş").assertIsOff()

        composeRule.onNodeWithTag("circles_list").performScrollToNode(hasText("Canlı sayaç: 30 dk"))
        composeRule.onNodeWithText("Canlı sayaç: 30 dk").assertIsDisplayed()
        composeRule.onNodeWithText("Elle eklenen: 10 dk").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun `create and join dialogs submit inputs cleanly`() {
        var createdName = ""
        var createdDisplay = ""

        composeRule.setContent {
            PekmenOneTheme {
                CirclesScreen(
                    state = CirclesUiState(
                        gateStatus = CloudRlsGateStatus(
                            compiledWithCloudFlag = true,
                            backendEndpointConfigured = true,
                            rlsVerified = true
                        ),
                        isSignedIn = true,
                        showCreateDialog = true
                    ),
                    actions = CirclesScreenActions(
                        onCreateCircle = { name, display ->
                            createdName = name
                            createdDisplay = display
                        }
                    )
                )
            }
        }

        composeRule.onNodeWithText("Create private circle").assertIsDisplayed()
        composeRule.onNodeWithText("Circle name").performTextInput("Study Squad")
        composeRule.onNodeWithText("Your display name").performTextInput("Alice")
        composeRule.onNodeWithText("Create circle").performClick()

        assertEquals("Study Squad", createdName)
        assertEquals("Alice", createdDisplay)
    }

    @Test
    @Config(qualifiers = "en")
    fun `subject whitelist chip toggles cleanly and defaults stay strict opt-in`() {
        val circle = SocialCircle(
            id = "c-1",
            name = "Focus Club",
            inviteCode = "ZXCV-2345-BNMQ-6789",
            createdByUserId = "user-alice",
            createdAtMs = 1_000L
        )
        val membership = CircleMembership(
            id = "m-1",
            circleId = "c-1",
            userId = "user-alice",
            displayName = "Alice",
            role = CircleRole.OWNER,
            sharingConfig = CircleSharingConfig(),
            joinedAtMs = 1_000L
        )

        var toggledSubject = ""
        composeRule.setContent {
            PekmenOneTheme {
                CirclesScreen(
                    state = CirclesUiState(
                        gateStatus = CloudRlsGateStatus(true, true, true),
                        isSignedIn = true,
                        circles = listOf(circle),
                        selectedCircleId = circle.id,
                        selectedCircle = circle,
                        activeMemberships = listOf(membership),
                        myMembership = membership,
                        availableSubjectTitles = listOf("Almanca B2")
                    ),
                    actions = CirclesScreenActions(
                        onToggleSubject = { toggledSubject = it }
                    )
                )
            }
        }

        composeRule.onNodeWithTag("circles_list").performScrollToNode(hasText("Almanca B2"))
        composeRule.onNodeWithText("Almanca B2").performClick()
        assertEquals("Almanca B2", toggledSubject)
        assertTrue(membership.sharingConfig == CircleSharingConfig(shareEnabled = false, shareLiveFocusStatus = false))
    }
}
