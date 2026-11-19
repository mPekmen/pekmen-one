package net.pekmen.one.core.cloud.auth

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

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
    val expiresAtWallMs: Long,
    val verifiedAtWallMs: Long = 0
) {
    override fun toString(): String =
        "AuthSessionRecord(expiresAtWallMs=$expiresAtWallMs, tokens=[REDACTED])"
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
        keyAlias: String = AndroidKeystoreTokenCipher.DEFAULT_KEY_ALIAS,
        storageNamespace: String? = null
    ) : this(
        storageFile = File(
            context.applicationContext.noBackupFilesDir,
            storageNamespace?.let { "pekmen_v2_auth_$it.enc" } ?: VAULT_FILE_NAME
        ),
        tokenCipher = AndroidKeystoreTokenCipher(keyAlias)
    )

    private val vaultMutex = Mutex()
    private val atomicFile = AtomicFile(storageFile)

    override suspend fun saveSession(session: AuthSessionRecord) {
        vaultMutex.withLock {
            withContext(Dispatchers.IO) {
                val parent = storageFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                val encoded = json.encodeToString(AuthSessionRecord.serializer(), session).encodeToByteArray()
                val encrypted = tokenCipher.encrypt(encoded)
                val output = atomicFile.startWrite()
                try {
                    output.write(encrypted)
                    atomicFile.finishWrite(output)
                } catch (error: IOException) {
                    atomicFile.failWrite(output)
                    throw error
                }
            }
        }
    }

    override suspend fun readSession(): AuthSessionRecord? = vaultMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val cipherBytes = atomicFile.readFully()
                val plainBytes = tokenCipher.decrypt(cipherBytes)
                json.decodeFromString(AuthSessionRecord.serializer(), plainBytes.decodeToString())
            } catch (_: IOException) {
                null
            } catch (_: SerializationException) {
                atomicFile.delete()
                null
            } catch (_: IllegalArgumentException) {
                atomicFile.delete()
                null
            } catch (_: GeneralSecurityException) {
                atomicFile.delete()
                null
            }
        }
    }

    override suspend fun clearSession() {
        vaultMutex.withLock {
            withContext(Dispatchers.IO) {
                atomicFile.delete()
                if (storageFile.exists()) throw IOException("Could not clear the auth vault")
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
