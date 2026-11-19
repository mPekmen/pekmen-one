package net.pekmen.one.core.cloud.auth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Authenticated session secrets stored exclusively in the encrypted token vault.
 * [toString] redacts token fields so secrets never leak into logs or crash reports.
 */
@Serializable
data class AuthSessionRecord(
    val userId: String,
    val email: String,
    val displayName: String? = null,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtWallMs: Long
) {
    override fun toString(): String =
        "AuthSessionRecord(userId=$userId, email=$email, expiresAtWallMs=$expiresAtWallMs, tokens=[REDACTED])"
}

interface TokenVault {
    suspend fun saveSession(session: AuthSessionRecord)
    suspend fun readSession(): AuthSessionRecord?
    suspend fun clearSession()
}

/**
 * Stores encrypted session credentials inside `noBackupFilesDir` using AES-GCM [TokenCipher].
 * Tokens are never persisted in Room, DataStore, SharedPreferences, or JSON backup archives.
 */
class KeystoreTokenVault(
    private val storageFile: File,
    private val tokenCipher: TokenCipher,
    private val json: Json = DEFAULT_JSON
) : TokenVault {

    constructor(
        context: Context,
        keyAlias: String = AndroidKeystoreTokenCipher.DEFAULT_KEY_ALIAS
    ) : this(
        storageFile = File(context.applicationContext.noBackupFilesDir, VAULT_FILE_NAME),
        tokenCipher = AndroidKeystoreTokenCipher(keyAlias)
    )

    private val vaultMutex = Mutex()

    override suspend fun saveSession(session: AuthSessionRecord) {
        vaultMutex.withLock {
            withContext(Dispatchers.IO) {
                val parent = storageFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                val encoded = json.encodeToString(AuthSessionRecord.serializer(), session).encodeToByteArray()
                val encrypted = tokenCipher.encrypt(encoded)
                val tempFile = File(storageFile.parentFile, "${storageFile.name}.tmp")
                tempFile.writeBytes(encrypted)
                if (!tempFile.renameTo(storageFile)) {
                    storageFile.writeBytes(encrypted)
                    tempFile.delete()
                }
            }
        }
    }

    override suspend fun readSession(): AuthSessionRecord? = vaultMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!storageFile.exists()) return@withContext null
            val decrypted = runCatching {
                val cipherBytes = storageFile.readBytes()
                val plainBytes = tokenCipher.decrypt(cipherBytes)
                json.decodeFromString(AuthSessionRecord.serializer(), plainBytes.decodeToString())
            }.getOrNull()

            if (decrypted == null) {
                storageFile.delete()
            }
            decrypted
        }
    }

    override suspend fun clearSession() {
        vaultMutex.withLock {
            withContext(Dispatchers.IO) {
                if (storageFile.exists()) {
                    storageFile.delete()
                }
                val tempFile = File(storageFile.parentFile, "${storageFile.name}.tmp")
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    }

    companion object {
        private const val VAULT_FILE_NAME = "pekmen_auth_vault.enc"
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
