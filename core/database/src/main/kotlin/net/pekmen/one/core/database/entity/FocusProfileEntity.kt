package net.pekmen.one.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import net.pekmen.one.core.model.BuiltInProfileSlug
import net.pekmen.one.core.model.FocusProfile
import net.pekmen.one.core.model.FocusSessionMode

@Entity(
    tableName = "focus_profiles",
    indices = [
        Index(value = ["sort_order"]),
        Index(value = ["deleted_at_ms"])
    ]
)
data class FocusProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "built_in_slug")
    val builtInSlug: String,
    @ColumnInfo(name = "custom_name")
    val customName: String?,
    @ColumnInfo(name = "color_hex")
    val colorHex: Long,
    @ColumnInfo(name = "icon_key")
    val iconKey: String,
    @ColumnInfo(name = "default_mode")
    val defaultMode: String,
    @ColumnInfo(name = "work_minutes")
    val workMinutes: Int,
    @ColumnInfo(name = "short_break_minutes")
    val shortBreakMinutes: Int,
    @ColumnInfo(name = "long_break_minutes")
    val longBreakMinutes: Int,
    @ColumnInfo(name = "cycles_before_long_break")
    val cyclesBeforeLongBreak: Int,
    @ColumnInfo(name = "dnd_enabled")
    val dndEnabled: Boolean,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean,
    @Embedded
    val sync: SyncMetadataEntity
) {
    fun toDomain(): FocusProfile = FocusProfile(
        id = id,
        builtInSlug = BuiltInProfileSlug.entries.firstOrNull { it.name == builtInSlug }
            ?: BuiltInProfileSlug.CUSTOM,
        customName = customName,
        colorHex = colorHex,
        iconKey = iconKey,
        defaultMode = FocusSessionMode.entries.firstOrNull { it.name == defaultMode }
            ?: FocusSessionMode.POMODORO,
        workMinutes = workMinutes,
        shortBreakMinutes = shortBreakMinutes,
        longBreakMinutes = longBreakMinutes,
        cyclesBeforeLongBreak = cyclesBeforeLongBreak,
        dndEnabled = dndEnabled,
        sortOrder = sortOrder,
        isArchived = isArchived
    )
}
