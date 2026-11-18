package net.pekmen.one.core.platform

import android.content.Context
import net.pekmen.one.core.model.BuiltInProfileSlug
import net.pekmen.one.core.model.FocusProfile

fun interface ProfileNameProvider {
    fun resolve(profile: FocusProfile): String
}

class AndroidProfileNameProvider(
    private val context: Context
) : ProfileNameProvider {
    override fun resolve(profile: FocusProfile): String {
        val custom = profile.customName?.trim()
        if (!custom.isNullOrEmpty()) return custom
        return when (profile.builtInSlug) {
            BuiltInProfileSlug.STUDY -> context.getString(R.string.notif_profile_study)
            BuiltInProfileSlug.WORK -> context.getString(R.string.notif_profile_work)
            BuiltInProfileSlug.SLEEP -> context.getString(R.string.notif_profile_sleep)
            BuiltInProfileSlug.PERSONAL -> context.getString(R.string.notif_profile_personal)
            BuiltInProfileSlug.CUSTOM -> context.getString(R.string.notif_profile_custom)
        }
    }
}
