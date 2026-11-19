package net.pekmen.one.core.cloud.auth

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** PKCE verifiers survive a browser round trip and process death, but are never backed up. */
class PendingAuthLinkStore(context: Context, namespace: String) {
    private val cipher = AndroidKeystoreTokenCipher("pekmen_v2_pkce")
    private val storage = AtomicFile(File(context.noBackupFilesDir, "pekmen_pkce_$namespace.enc"))

    suspend fun write(link: PendingAuthLink) = withContext(Dispatchers.IO) {
        val bytes = cipher.encrypt(Json.encodeToString(PendingAuthLink.serializer(), link).encodeToByteArray())
        val stream = storage.startWrite()
        try {
            stream.write(bytes)
            storage.finishWrite(stream)
        } catch (error: IOException) {
            storage.failWrite(stream)
            throw error
        }
    }

    suspend fun read(): PendingAuthLink? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = cipher.decrypt(storage.readFully())
            Json.decodeFromString(PendingAuthLink.serializer(), bytes.decodeToString())
        }.getOrNull()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        storage.delete()
        if (storage.baseFile.exists()) throw IOException("Could not clear the pending authentication flow")
    }
}
