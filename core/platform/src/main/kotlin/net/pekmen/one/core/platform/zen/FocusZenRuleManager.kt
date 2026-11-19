package net.pekmen.one.core.platform.zen

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenDeviceEffects
import android.service.notification.ZenPolicy
import androidx.annotation.RequiresApi
import net.pekmen.one.core.model.BuiltInProfileSlug
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusProfile
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.platform.AndroidProfileNameProvider
import net.pekmen.one.core.platform.R

/**
 * Manages Pekmen One's profile-scoped, opt-in [AutomaticZenRule].
 * Never overrides or resets Android's global interruption filter directly.
 */
class FocusZenRuleManager(
    context: Context,
    private val gateway: FocusZenRuleGateway = AndroidFocusZenRuleGateway(context),
    private val idStore: ZenRuleIdStore = SharedPrefsZenRuleIdStore(context),
    private val configurationActivity: ComponentName? = null,
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val ruleNameProvider: () -> String = {
        context.applicationContext.getString(R.string.zen_rule_name)
    },
    private val conditionSummaryProvider: (FocusProfile) -> String = { profile ->
        val appContext = context.applicationContext
        val resolvedName = AndroidProfileNameProvider(appContext).resolve(profile)
        appContext.getString(R.string.zen_condition_active_summary, resolvedName)
    },
    private val inactiveSummaryProvider: () -> String = {
        context.applicationContext.getString(R.string.zen_condition_inactive_summary)
    }
) {

    fun hasPolicyAccess(): Boolean = gateway.isNotificationPolicyAccessGranted()

    fun ownedRuleId(): String? = idStore.getOwnedRuleId()

    fun syncForSessionState(
        zenIntegrationEnabled: Boolean,
        profile: FocusProfile?,
        status: FocusSessionStatus?,
        phase: FocusPhase?
    ): ZenRuleActionResult {
        val shouldActivate = zenIntegrationEnabled &&
            profile != null &&
            profile.dndEnabled &&
            status == FocusSessionStatus.RUNNING &&
            phase == FocusPhase.FOCUS

        return if (shouldActivate && profile != null) {
            activateForProfile(zenIntegrationEnabled = true, profile = profile)
        } else {
            deactivateOwnedRule()
        }
    }

    fun activateForProfile(
        zenIntegrationEnabled: Boolean,
        profile: FocusProfile
    ): ZenRuleActionResult {
        if (!zenIntegrationEnabled || !profile.dndEnabled) {
            return ZenRuleActionResult.SkippedOptOut
        }
        if (!gateway.isNotificationPolicyAccessGranted()) {
            return ZenRuleActionResult.SkippedMissingPolicyAccess
        }

        return try {
            val rule = buildAutomaticZenRule(profile)
            val ruleId = upsertOwnedRule(rule)
            if (ruleId.isNullOrBlank()) {
                ZenRuleActionResult.Failed(
                    IllegalStateException("NotificationManager failed to register Pekmen Zen rule.")
                )
            } else {
                val condition = Condition(
                    CONDITION_URI,
                    conditionSummaryProvider(profile),
                    Condition.STATE_TRUE
                )
                gateway.setRuleState(ruleId, condition)
                ZenRuleActionResult.Activated(ruleId)
            }
        } catch (securityException: SecurityException) {
            ZenRuleActionResult.PermissionRevoked(securityException)
        }
    }

    fun deactivateOwnedRule(): ZenRuleActionResult {
        val ownedRuleId = idStore.getOwnedRuleId()
            ?: return ZenRuleActionResult.SkippedNoOwnedRule
        if (!gateway.isNotificationPolicyAccessGranted()) {
            return ZenRuleActionResult.PermissionRevoked()
        }

        return try {
            val condition = Condition(
                CONDITION_URI,
                inactiveSummaryProvider(),
                Condition.STATE_FALSE
            )
            gateway.setRuleState(ownedRuleId, condition)
            ZenRuleActionResult.Deactivated
        } catch (securityException: SecurityException) {
            ZenRuleActionResult.PermissionRevoked(securityException)
        } catch (illegalArgument: IllegalArgumentException) {
            handleMissingRuleOnDeactivate(ownedRuleId, illegalArgument)
        }
    }

    private fun handleMissingRuleOnDeactivate(
        ownedRuleId: String,
        error: IllegalArgumentException
    ): ZenRuleActionResult {
        val stillExists = runCatching { gateway.getRule(ownedRuleId) }.getOrNull() != null
        return if (!stillExists) {
            idStore.clearOwnedRuleId()
            ZenRuleActionResult.SkippedNoOwnedRule
        } else {
            ZenRuleActionResult.Failed(error)
        }
    }

    private fun upsertOwnedRule(rule: AutomaticZenRule): String? {
        val existingId = idStore.getOwnedRuleId()
        if (existingId != null) {
            val existingRule = runCatching { gateway.getRule(existingId) }
                .onFailure { if (it is SecurityException) throw it }
                .getOrNull()
            if (existingRule != null && gateway.updateRule(existingId, rule)) {
                return existingId
            }
        }
        val createdId = gateway.addRule(rule)
        if (!createdId.isNullOrBlank()) {
            idStore.saveOwnedRuleId(createdId)
        }
        return createdId
    }

    private fun buildAutomaticZenRule(profile: FocusProfile): AutomaticZenRule {
        val policy = ZenPolicy.Builder()
            .allowAlarms(true)
            .allowMedia(true)
            .build()
        val applyVisualEffects = profile.builtInSlug == BuiltInProfileSlug.STUDY ||
            profile.builtInSlug == BuiltInProfileSlug.SLEEP

        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            sdkIntProvider() >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        ) {
            buildApi35ZenRule(
                ruleName = ruleNameProvider(),
                policy = policy,
                applyVisualEffects = applyVisualEffects
            )
        } else {
            buildPreApi35ZenRule(
                ruleName = ruleNameProvider(),
                policy = policy
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun buildApi35ZenRule(
        ruleName: String,
        policy: ZenPolicy,
        applyVisualEffects: Boolean
    ): AutomaticZenRule {
        val deviceEffects = ZenDeviceEffects.Builder()
            .setShouldDimWallpaper(applyVisualEffects)
            .setShouldDisplayGrayscale(applyVisualEffects)
            .build()
        val builder = AutomaticZenRule.Builder(ruleName, CONDITION_URI)
            .setType(AutomaticZenRule.TYPE_OTHER)
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .setZenPolicy(policy)
            .setDeviceEffects(deviceEffects)
            .setIconResId(R.drawable.ic_stat_focus)
            .setEnabled(true)
        configurationActivity?.let { builder.setConfigurationActivity(it) }
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun buildPreApi35ZenRule(
        ruleName: String,
        policy: ZenPolicy
    ): AutomaticZenRule {
        return AutomaticZenRule(
            ruleName,
            null,
            configurationActivity,
            CONDITION_URI,
            policy,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            true
        )
    }

    companion object {
        val CONDITION_URI: Uri = Uri.parse("pekmen://focus/zen-rule")
    }
}
