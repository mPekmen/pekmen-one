package net.pekmen.one.core.model

data class PersonalSetup(
    val name: String = "",
    val username: String = "",
    val purpose: String = "",
    val categories: Set<String> = emptySet(),
    val dailyGoalMinutes: Int = 30,
    val weeklyGoalMinutes: Int = 150,
    val profileId: String = BuiltInProfiles.STUDY_ID,
    val firstLesson: String = "",
    val firstLessonId: String? = null,
    val step: Int = 0,
    val completed: Boolean = false
) {
    fun hasValidIdentity(): Boolean =
        name.trim().length in 1..80 && username.trim().matches(usernamePattern)

    fun hasValidGoals(): Boolean =
        dailyGoalMinutes in 1..1_440 && weeklyGoalMinutes in dailyGoalMinutes..10_080

    companion object {
        private val usernamePattern = Regex("[a-zA-Z0-9_]{3,24}")
    }
}
