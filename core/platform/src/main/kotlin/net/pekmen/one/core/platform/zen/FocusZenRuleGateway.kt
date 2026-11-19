package net.pekmen.one.core.platform.zen

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.service.notification.Condition

/**
 * Abstraction over Android [NotificationManager] AutomaticZenRule operations.
 * Deliberately exposes only Pekmen-owned rule APIs and never global interruption filter setters.
 */
interface FocusZenRuleGateway {
    fun isNotificationPolicyAccessGranted(): Boolean
    fun getRule(ruleId: String): AutomaticZenRule?
    fun addRule(rule: AutomaticZenRule): String?
    fun updateRule(ruleId: String, rule: AutomaticZenRule): Boolean
    fun setRuleState(ruleId: String, condition: Condition)
}

sealed interface ZenRuleActionResult {
    data class Activated(val ruleId: String) : ZenRuleActionResult
    data object Deactivated : ZenRuleActionResult
    data object SkippedOptOut : ZenRuleActionResult
    data object SkippedMissingPolicyAccess : ZenRuleActionResult
    data object SkippedNoOwnedRule : ZenRuleActionResult
    data class PermissionRevoked(val cause: SecurityException? = null) : ZenRuleActionResult
    data class Failed(val error: Throwable) : ZenRuleActionResult
}

interface ZenRuleIdStore {
    fun getOwnedRuleId(): String?
    fun saveOwnedRuleId(ruleId: String)
    fun clearOwnedRuleId()
}

class SharedPrefsZenRuleIdStore(
    private val prefs: SharedPreferences
) : ZenRuleIdStore {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    override fun getOwnedRuleId(): String? = prefs.getString(KEY_OWNED_RULE_ID, null)

    override fun saveOwnedRuleId(ruleId: String) {
        prefs.edit().putString(KEY_OWNED_RULE_ID, ruleId).apply()
    }

    override fun clearOwnedRuleId() {
        prefs.edit().remove(KEY_OWNED_RULE_ID).apply()
    }

    private companion object {
        const val PREFS_NAME = "pekmen_zen_rule_store"
        const val KEY_OWNED_RULE_ID = "owned_zen_rule_id"
    }
}

class AndroidFocusZenRuleGateway(
    context: Context
) : FocusZenRuleGateway {

    private val appContext = context.applicationContext

    private val notificationManager: NotificationManager?
        get() = appContext.getSystemService(NotificationManager::class.java)

    override fun isNotificationPolicyAccessGranted(): Boolean {
        return notificationManager?.isNotificationPolicyAccessGranted == true
    }

    override fun getRule(ruleId: String): AutomaticZenRule? {
        return notificationManager?.getAutomaticZenRule(ruleId)
    }

    override fun addRule(rule: AutomaticZenRule): String? {
        return notificationManager?.addAutomaticZenRule(rule)
    }

    override fun updateRule(ruleId: String, rule: AutomaticZenRule): Boolean {
        return notificationManager?.updateAutomaticZenRule(ruleId, rule) ?: false
    }

    override fun setRuleState(ruleId: String, condition: Condition) {
        notificationManager?.setAutomaticZenRuleState(ruleId, condition)
    }
}
