package net.pekmen.one.core.platform

import android.app.Activity
import android.app.Application
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import android.service.quicksettings.Tile
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.model.BuiltInProfileSlug
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusProfile
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.platform.samsung.AppShortcutsPublisher
import net.pekmen.one.core.platform.samsung.FocusQuickSettingsTileService
import net.pekmen.one.core.platform.samsung.FocusShortcutCommand
import net.pekmen.one.core.platform.samsung.FocusTileBridgeProvider
import net.pekmen.one.core.platform.samsung.FocusTileStateBridge
import net.pekmen.one.core.platform.samsung.FocusTileStatus
import net.pekmen.one.core.platform.samsung.RoutineFocusState
import net.pekmen.one.core.platform.samsung.SamsungRoutineBridgeHelper
import net.pekmen.one.core.platform.zen.AndroidFocusZenRuleGateway
import net.pekmen.one.core.platform.zen.FocusZenRuleGateway
import net.pekmen.one.core.platform.zen.FocusZenRuleManager
import net.pekmen.one.core.platform.zen.SharedPrefsZenRuleIdStore
import net.pekmen.one.core.platform.zen.ZenRuleActionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM],
    application = SystemBridgesTest.FakeSystemBridgeApp::class
)
class SystemBridgesTest {

    private lateinit var app: FakeSystemBridgeApp
    private lateinit var notificationManager: NotificationManager
    private lateinit var idStore: SharedPrefsZenRuleIdStore

    private val studyProfile = BuiltInProfiles.defaults().first { it.id == BuiltInProfiles.STUDY_ID }
    private val workProfile = BuiltInProfiles.defaults().first { it.id == BuiltInProfiles.WORK_ID }
    private val sleepProfile = BuiltInProfiles.defaults().first { it.id == BuiltInProfiles.SLEEP_ID }
    private val personalProfile = BuiltInProfiles.defaults().first { it.id == BuiltInProfiles.PERSONAL_ID }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        notificationManager = app.getSystemService(NotificationManager::class.java)
        idStore = SharedPrefsZenRuleIdStore(
            app.getSharedPreferences("test_zen_prefs", Context.MODE_PRIVATE).also {
                it.edit().clear().apply()
            }
        )
        ShortcutManagerCompat.removeAllDynamicShortcuts(app)
        app.tileBridge.currentStatus = FocusTileStatus(status = null)
        app.tileBridge.toggleCount = 0
    }

    @Test
    fun `zen rule respects opt-in flag profile dndEnabled and policy access`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        val manager = FocusZenRuleManager(context = app, idStore = idStore)

        val disabledGlobal = manager.activateForProfile(zenIntegrationEnabled = false, profile = studyProfile)
        assertEquals(ZenRuleActionResult.SkippedOptOut, disabledGlobal)
        assertNull(manager.ownedRuleId())

        val disabledProfile = manager.activateForProfile(zenIntegrationEnabled = true, profile = personalProfile)
        assertEquals(ZenRuleActionResult.SkippedOptOut, disabledProfile)
        assertNull(manager.ownedRuleId())

        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        val missingAccess = manager.activateForProfile(zenIntegrationEnabled = true, profile = studyProfile)
        assertEquals(ZenRuleActionResult.SkippedMissingPolicyAccess, missingAccess)
        assertNull(manager.ownedRuleId())
    }

    @Test
    fun `zen own-rule activation updates policy and deactivation never touches global interruption filter`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)

        val thirdPartyUri = Uri.parse("otherapp://rule/keep-alive")
        val thirdPartyRule = AutomaticZenRule(
            "User Bedtime",
            null,
            ComponentName(app, FakeLauncherActivity::class.java),
            thirdPartyUri,
            ZenPolicy.Builder().allowAlarms(true).build(),
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            true
        )
        val thirdPartyId = notificationManager.addAutomaticZenRule(thirdPartyRule)
        notificationManager.setAutomaticZenRuleState(
            thirdPartyId,
            Condition(thirdPartyUri, "Active", Condition.STATE_TRUE)
        )

        val recordingGateway = FakeZenGateway(delegate = AndroidFocusZenRuleGateway(app))
        val manager = FocusZenRuleManager(
            context = app,
            gateway = recordingGateway,
            idStore = idStore,
            configurationActivity = ComponentName(app, FakeLauncherActivity::class.java)
        )

        val activated = manager.syncForSessionState(
            zenIntegrationEnabled = true,
            profile = studyProfile,
            status = FocusSessionStatus.RUNNING,
            phase = FocusPhase.FOCUS
        )
        assertTrue(activated is ZenRuleActionResult.Activated)
        val ownedId = (activated as ZenRuleActionResult.Activated).ruleId
        assertEquals(ownedId, idStore.getOwnedRuleId())

        val storedRule = notificationManager.getAutomaticZenRule(ownedId)
        assertNotNull(storedRule)
        assertEquals(ZenPolicy.STATE_ALLOW, storedRule?.zenPolicy?.priorityCategoryAlarms)
        assertEquals(ZenPolicy.STATE_ALLOW, storedRule?.zenPolicy?.priorityCategoryMedia)
        assertEquals(ownedId, recordingGateway.stateUpdates.last().first)
        assertEquals(Condition.STATE_TRUE, recordingGateway.stateUpdates.last().second.state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            assertTrue(storedRule?.deviceEffects?.shouldDimWallpaper() == true)
            assertTrue(storedRule?.deviceEffects?.shouldDisplayGrayscale() == true)
        }

        // Switching to WORK updates the same ruleId cleanly and clears Study visual effects.
        val updated = manager.activateForProfile(zenIntegrationEnabled = true, profile = workProfile)
        assertEquals(ZenRuleActionResult.Activated(ownedId), updated)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val workRule = notificationManager.getAutomaticZenRule(ownedId)
            assertFalse(workRule?.deviceEffects?.shouldDimWallpaper() == true)
            assertFalse(workRule?.deviceEffects?.shouldDisplayGrayscale() == true)
        }

        // Pause / Finish / Discard must deactivate ONLY Pekmen's owned ruleId, never global filter.
        val paused = manager.syncForSessionState(
            zenIntegrationEnabled = true,
            profile = workProfile,
            status = FocusSessionStatus.PAUSED,
            phase = FocusPhase.FOCUS
        )
        assertEquals(ZenRuleActionResult.Deactivated, paused)
        assertEquals(ownedId, recordingGateway.stateUpdates.last().first)
        assertEquals(Condition.STATE_FALSE, recordingGateway.stateUpdates.last().second.state)
        assertTrue(recordingGateway.stateUpdates.none { it.first == thirdPartyId })
        assertTrue(notificationManager.getAutomaticZenRule(thirdPartyId)?.isEnabled == true)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, notificationManager.currentInterruptionFilter)
    }

    @Test
    fun `zen manager handles mid-session permission revocation and SecurityException safely`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        val manager = FocusZenRuleManager(context = app, idStore = idStore)
        val activated = manager.activateForProfile(zenIntegrationEnabled = true, profile = sleepProfile)
        assertTrue(activated is ZenRuleActionResult.Activated)

        // Revoke permission mid-session via NotificationManager shadow.
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        val revokedDeactivate = manager.deactivateOwnedRule()
        assertTrue(revokedDeactivate is ZenRuleActionResult.PermissionRevoked)

        // Simulate TOCTOU race where policy access appears true but gateway throws SecurityException.
        val throwingManager = FocusZenRuleManager(
            context = app,
            gateway = FakeZenGateway(throwOnWrite = true),
            idStore = idStore
        )
        val activateRace = throwingManager.activateForProfile(zenIntegrationEnabled = true, profile = studyProfile)
        assertTrue(activateRace is ZenRuleActionResult.PermissionRevoked)
        val deactivateRace = throwingManager.deactivateOwnedRule()
        assertTrue(deactivateRace is ZenRuleActionResult.PermissionRevoked)
    }

    @Test
    fun `api 35 attaches ZenDeviceEffects for Study and Sleep while pre-35 guard omits them`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)

        val api35Manager = FocusZenRuleManager(
            context = app,
            idStore = idStore,
            sdkIntProvider = { Build.VERSION_CODES.VANILLA_ICE_CREAM }
        )
        val sleepResult = api35Manager.activateForProfile(zenIntegrationEnabled = true, profile = sleepProfile)
        val sleepId = (sleepResult as ZenRuleActionResult.Activated).ruleId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val sleepRule = notificationManager.getAutomaticZenRule(sleepId)
            assertTrue(sleepRule?.deviceEffects?.shouldDimWallpaper() == true)
            assertTrue(sleepRule?.deviceEffects?.shouldDisplayGrayscale() == true)
        }

        idStore.clearOwnedRuleId()
        val pre35Manager = FocusZenRuleManager(
            context = app,
            idStore = idStore,
            sdkIntProvider = { Build.VERSION_CODES.UPSIDE_DOWN_CAKE }
        )
        val legacyResult = pre35Manager.activateForProfile(zenIntegrationEnabled = true, profile = studyProfile)
        val legacyId = (legacyResult as ZenRuleActionResult.Activated).ruleId
        val legacyRule = notificationManager.getAutomaticZenRule(legacyId)
        assertNotNull(legacyRule)
        assertEquals(ZenPolicy.STATE_ALLOW, legacyRule?.zenPolicy?.priorityCategoryAlarms)
        assertEquals(ZenPolicy.STATE_ALLOW, legacyRule?.zenPolicy?.priorityCategoryMedia)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            assertNull(legacyRule?.deviceEffects)
        }
    }

    @Test
    fun `app shortcuts publisher publishes explicit shortcut intents and parses commands safely`() {
        val publisher = AppShortcutsPublisher(app, FakeLauncherActivity::class.java)
        assertTrue(publisher.publishDynamicShortcuts())

        val published = ShortcutManagerCompat.getDynamicShortcuts(app)
        assertEquals(4, published.size)
        assertEquals(
            setOf(
                AppShortcutsPublisher.SHORTCUT_STUDY_POMODORO,
                AppShortcutsPublisher.SHORTCUT_WORK_FOCUS,
                AppShortcutsPublisher.SHORTCUT_SLEEP_CUSTOM,
                AppShortcutsPublisher.SHORTCUT_TOGGLE_FOCUS
            ),
            published.map { it.id }.toSet()
        )

        published.forEach { shortcut ->
            assertEquals(app.packageName, shortcut.intent.`package`)
            assertEquals(FakeLauncherActivity::class.java.name, shortcut.intent.component?.className)
        }

        val studyShortcut = published.first { it.id == AppShortcutsPublisher.SHORTCUT_STUDY_POMODORO }
        assertEquals(
            FocusShortcutCommand.StartProfile(BuiltInProfiles.STUDY_ID, FocusSessionMode.POMODORO),
            AppShortcutsPublisher.parseShortcutIntent(studyShortcut.intent)
        )

        val sleepShortcut = published.first { it.id == AppShortcutsPublisher.SHORTCUT_SLEEP_CUSTOM }
        assertEquals(
            FocusShortcutCommand.StartProfile(BuiltInProfiles.SLEEP_ID, FocusSessionMode.CUSTOM),
            AppShortcutsPublisher.parseShortcutIntent(sleepShortcut.intent)
        )

        val toggleShortcut = published.first { it.id == AppShortcutsPublisher.SHORTCUT_TOGGLE_FOCUS }
        assertEquals(
            FocusShortcutCommand.ToggleActiveFocus,
            AppShortcutsPublisher.parseShortcutIntent(toggleShortcut.intent)
        )

        assertNull(AppShortcutsPublisher.parseShortcutIntent(null))
        assertNull(AppShortcutsPublisher.parseShortcutIntent(Intent("com.evil.UNTRUSTED_SHORTCUT")))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `quick settings tile service displays live focus status and toggles pause resume`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val tileService = Robolectric.buildService(FocusQuickSettingsTileService::class.java)
            .create()
            .get()
        tileService.mainDispatcher = dispatcher
        tileService.ioDispatcher = dispatcher

        // Idle -> STATE_INACTIVE and clicking launches explicit main activity intent.
        var launchedIntent: Intent? = null
        tileService.tileActivityStarter = { intent, _ -> launchedIntent = intent }
        tileService.onStartListening()
        val tile = tileService.qsTile
        assertNotNull(tile)
        assertEquals(Tile.STATE_INACTIVE, tile.state)

        tileService.onClick()
        assertNotNull(launchedIntent)
        assertEquals(FakeLauncherActivity::class.java.name, launchedIntent?.component?.className)
        assertEquals(0, app.tileBridge.toggleCount)

        // Running -> STATE_ACTIVE, click pauses -> STATE_INACTIVE, click resumes -> STATE_ACTIVE.
        app.tileBridge.currentStatus = FocusTileStatus(
            status = FocusSessionStatus.RUNNING,
            profileName = "Study",
            phase = FocusPhase.FOCUS
        )
        tileService.onStartListening()
        assertEquals(Tile.STATE_ACTIVE, tile.state)
        assertEquals("Study", tile.label?.toString())

        tileService.onClick()
        assertEquals(1, app.tileBridge.toggleCount)
        assertEquals(Tile.STATE_INACTIVE, tile.state)

        tileService.onClick()
        assertEquals(2, app.tileBridge.toggleCount)
        assertEquals(Tile.STATE_ACTIVE, tile.state)
    }

    @Test
    fun `samsung routine bridge helper formats keyword tags only when enabled`() {
        assertNull(
            SamsungRoutineBridgeHelper.formatKeywordTag(
                routineKeywordEnabled = false,
                slug = BuiltInProfileSlug.STUDY,
                status = FocusSessionStatus.RUNNING,
                phase = FocusPhase.FOCUS
            )
        )
        assertEquals(
            "Timer is running",
            SamsungRoutineBridgeHelper.appendKeywordSubtext(
                baseSubtext = "Timer is running",
                routineKeywordEnabled = false,
                slug = BuiltInProfileSlug.STUDY,
                status = FocusSessionStatus.RUNNING,
                phase = FocusPhase.FOCUS
            )
        )

        assertEquals(
            "[PekmenFocus:STUDY:RUNNING]",
            SamsungRoutineBridgeHelper.formatKeywordTag(
                routineKeywordEnabled = true,
                slug = BuiltInProfileSlug.STUDY,
                status = FocusSessionStatus.RUNNING,
                phase = FocusPhase.FOCUS
            )
        )
        assertEquals(
            "[PekmenFocus:WORK:PAUSED]",
            SamsungRoutineBridgeHelper.formatKeywordTag(
                routineKeywordEnabled = true,
                slug = BuiltInProfileSlug.WORK,
                status = FocusSessionStatus.PAUSED,
                phase = FocusPhase.FOCUS
            )
        )
        assertEquals(
            "[PekmenFocus:SLEEP:BREAK]",
            SamsungRoutineBridgeHelper.formatKeywordTag(
                routineKeywordEnabled = true,
                slug = BuiltInProfileSlug.SLEEP,
                status = FocusSessionStatus.RUNNING,
                phase = FocusPhase.SHORT_BREAK
            )
        )
        assertEquals(
            "[PekmenFocus:PERSONAL:AWAITING]",
            SamsungRoutineBridgeHelper.formatKeywordTag(
                routineKeywordEnabled = true,
                slug = BuiltInProfileSlug.PERSONAL,
                status = FocusSessionStatus.AWAITING_NEXT_PHASE,
                phase = FocusPhase.FOCUS
            )
        )
        assertEquals(
            "[PekmenFocus:CUSTOM:STOPPED]",
            SamsungRoutineBridgeHelper.formatKeywordTag(
                routineKeywordEnabled = true,
                slug = BuiltInProfileSlug.CUSTOM,
                state = RoutineFocusState.STOPPED
            )
        )
        assertEquals(
            "Focus · [PekmenFocus:STUDY:RUNNING]",
            SamsungRoutineBridgeHelper.appendKeywordSubtext(
                baseSubtext = "Focus",
                routineKeywordEnabled = true,
                slug = BuiltInProfileSlug.STUDY,
                status = FocusSessionStatus.RUNNING,
                phase = FocusPhase.FOCUS
            )
        )
        assertEquals(3, SamsungRoutineBridgeHelper.oneUiSetupSteps(app).size)
    }

    @Test
    @Config(
        sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM],
        application = FakeSystemBridgeApp::class,
        qualifiers = "tr"
    )
    fun `turkish resources localize shortcuts tile and routine setup steps`() {
        val publisher = AppShortcutsPublisher(app, FakeLauncherActivity::class.java)
        val shortcuts = publisher.buildShortcuts()
        assertTrue(shortcuts.any { it.shortLabel == "Çalışma Pomodoro" })
        val steps = SamsungRoutineBridgeHelper.oneUiSetupSteps(app)
        assertEquals(3, steps.size)
        assertTrue(steps.first().contains("Samsung Modlar ve Rutinler"))
    }

    class FakeLauncherActivity : Activity()

    class FakeSystemBridgeApp : Application(), FocusTileBridgeProvider {
        val tileBridge = FakeTileStateBridge()
        override fun focusTileStateBridge(): FocusTileStateBridge = tileBridge
        override fun launcherActivityClass(): Class<out Activity> = FakeLauncherActivity::class.java
    }

    class FakeTileStateBridge : FocusTileStateBridge {
        var currentStatus: FocusTileStatus = FocusTileStatus(status = null)
        var toggleCount: Int = 0

        override suspend fun currentTileStatus(): FocusTileStatus = currentStatus

        override suspend fun toggleFromTile(): FocusTileStatus {
            toggleCount += 1
            currentStatus = when (currentStatus.status) {
                FocusSessionStatus.RUNNING -> currentStatus.copy(status = FocusSessionStatus.PAUSED)
                FocusSessionStatus.PAUSED,
                FocusSessionStatus.AWAITING_NEXT_PHASE -> currentStatus.copy(status = FocusSessionStatus.RUNNING)
                else -> currentStatus
            }
            return currentStatus
        }
    }

    private class FakeZenGateway(
        private val delegate: FocusZenRuleGateway? = null,
        private val throwOnWrite: Boolean = false
    ) : FocusZenRuleGateway {
        val stateUpdates = mutableListOf<Pair<String, Condition>>()

        override fun isNotificationPolicyAccessGranted(): Boolean {
            return if (throwOnWrite) true else delegate?.isNotificationPolicyAccessGranted() == true
        }

        override fun getRule(ruleId: String): AutomaticZenRule? {
            if (throwOnWrite) throw SecurityException("Revoked")
            return delegate?.getRule(ruleId)
        }

        override fun addRule(rule: AutomaticZenRule): String? {
            if (throwOnWrite) throw SecurityException("Revoked")
            return delegate?.addRule(rule)
        }

        override fun updateRule(ruleId: String, rule: AutomaticZenRule): Boolean {
            if (throwOnWrite) throw SecurityException("Revoked")
            return delegate?.updateRule(ruleId, rule) ?: false
        }

        override fun setRuleState(ruleId: String, condition: Condition) {
            if (throwOnWrite) throw SecurityException("Revoked")
            stateUpdates.add(ruleId to condition)
            delegate?.setRuleState(ruleId, condition)
        }
    }
}
