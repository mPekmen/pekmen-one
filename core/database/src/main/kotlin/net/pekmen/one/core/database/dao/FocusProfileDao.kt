package net.pekmen.one.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.pekmen.one.core.database.entity.FocusProfileEntity

@Dao
interface FocusProfileDao {
    @Query(
        "SELECT * FROM focus_profiles " +
            "WHERE deleted_at_ms IS NULL AND is_archived = 0 " +
            "ORDER BY sort_order ASC, created_at_ms ASC"
    )
    fun observeActiveProfiles(): Flow<List<FocusProfileEntity>>

    @Query("SELECT COUNT(*) FROM focus_profiles WHERE deleted_at_ms IS NULL")
    suspend fun countProfiles(): Int

    @Query("SELECT * FROM focus_profiles WHERE id = :profileId AND deleted_at_ms IS NULL LIMIT 1")
    suspend fun getById(profileId: String): FocusProfileEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(profiles: List<FocusProfileEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: FocusProfileEntity)

    @Update
    suspend fun update(profile: FocusProfileEntity)
}
