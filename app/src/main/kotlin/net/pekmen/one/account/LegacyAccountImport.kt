package net.pekmen.one.account

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.pekmen.one.AppContainer
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.model.AccountId
import net.pekmen.one.core.model.BackupImportResult
import net.pekmen.one.core.portability.BackupPortabilityService
import net.pekmen.one.core.portability.PekmenBackupArchive
import java.io.IOException

/** The original database remains a separate, read-only source of eligible records. */
internal class LegacyAccountImport(private val context: Context) {
    private val source by lazy { AppContainer(context).backupPortabilityService }
    private val claim by lazy { context.getSharedPreferences("pekmen_legacy_claim", Context.MODE_PRIVATE) }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun count(owner: AccountId): Int = withContext(Dispatchers.IO) {
        if (!canClaim(owner) || !context.getDatabasePath(PekmenDatabase.DATABASE_NAME).exists()) {
            return@withContext 0
        }
        val archive = source.exportJsonArchive().getOrThrow()
        eligibleRows(archive)
    }

    suspend fun claim(owner: AccountId, target: BackupPortabilityService): Int = withContext(Dispatchers.IO) {
        check(canClaim(owner))
        val archive = source.exportJsonArchive().getOrThrow()
        val count = eligibleRows(archive)
        if (count == 0) return@withContext 0
        // Reserve this legacy archive to one owner before any target writes. A retry after
        // process death uses the same IDs and can only proceed under the same account.
        if (!claim.edit().putString(OWNER_KEY, owner.value).commit()) {
            throw IOException("Legacy claim reservation failed")
        }
        val withoutPreferences = json.decodeFromString(PekmenBackupArchive.serializer(), archive)
            .copy(preferences = null)
        val export = json.encodeToString(PekmenBackupArchive.serializer(), withoutPreferences)
        if (target.importJsonArchive(export) !is BackupImportResult.Success) throw IOException("Legacy import failed")
        if (!claim.edit().putBoolean(COMPLETE_KEY, true).commit()) {
            throw IOException("Legacy receipt could not be saved")
        }
        count
    }

    private fun canClaim(owner: AccountId): Boolean =
        !claim.getBoolean(COMPLETE_KEY, false) && claim.getString(OWNER_KEY, owner.value) == owner.value

    private fun eligibleRows(raw: String): Int {
        val archive = json.decodeFromString(PekmenBackupArchive.serializer(), raw)
        return archive.sessions.size + archive.subjects.size + archive.tasks.size +
            archive.profiles.count { it.builtInSlug == "CUSTOM" }
    }

    private companion object {
        const val OWNER_KEY = "owner"
        const val COMPLETE_KEY = "complete"
    }
}
