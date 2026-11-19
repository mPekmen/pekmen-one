package net.pekmen.one.core.cloud.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import net.pekmen.one.core.model.AccountId
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

enum class IdentityError { CONFIGURATION, CONNECTION, CREDENTIALS, EMAIL_UNVERIFIED, RATE_LIMIT, LINK, SERVICE }

class IdentityFailure(val reason: IdentityError) : IOException(reason.name)

@Serializable
enum class AuthLinkPurpose { SIGNUP, GOOGLE, RECOVERY }

@Serializable
data class PendingAuthLink(
    val purpose: AuthLinkPurpose,
    val verifier: String,
    val createdAtMs: Long
) {
    override fun toString(): String = "PendingAuthLink($purpose)"
}

class SupabaseAuthConfig private constructor(
    internal val origin: URI,
    internal val publishableKey: String,
    val redirect: URI?
) {
    val storageNamespace: String
        get() = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(origin.toASCIIString().trimEnd('/').encodeToByteArray())
        )

    companion object {
        fun parse(url: String, key: String, redirectHost: String): SupabaseAuthConfig? {
            val origin = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val secureOrigin = origin.scheme == "https" && origin.host != null && origin.userInfo == null
            val rootUrl = origin.query == null && origin.fragment == null && origin.path in listOf("", "/")
            if (!secureOrigin || !rootUrl) return null
            if (!isClientKey(key)) return null
            val redirect = redirectHost.takeIf(String::isNotBlank)?.let { host ->
                if (!host.matches(Regex("[a-zA-Z0-9.-]+")) || '.' !in host || host.endsWith(".invalid")) return null
                URI("https", host.lowercase(), "/auth/pekmen-one", null)
            }
            return SupabaseAuthConfig(origin, key, redirect)
        }

        private fun isClientKey(key: String): Boolean {
            if (key.startsWith("sb_publishable_")) return key.matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))
            return runCatching {
                val claims = Base64.getUrlDecoder().decode(key.split('.')[1]).decodeToString()
                Json.parseToJsonElement(claims).jsonObject.text("role") == "anon"
            }.getOrDefault(false)
        }
    }
}

private const val AUTH_TIMEOUT_MS = 12_000
private const val MAX_RESPONSE_BYTES = 128 * 1024

internal fun interface AuthHttpTransport {
    suspend fun request(method: String, url: URI, key: String, bearer: String?, body: JsonObject?): AuthHttpResponse
}

internal data class AuthHttpResponse(val status: Int, val body: String)

class SupabaseAuthClient internal constructor(
    private val config: SupabaseAuthConfig,
    private val transport: AuthHttpTransport,
    private val clock: () -> Long
) {
    constructor(config: SupabaseAuthConfig) : this(config, HttpsAuthTransport, System::currentTimeMillis)

    fun createLink(purpose: AuthLinkPurpose): PendingAuthLink =
        PendingAuthLink(purpose, encoder.encodeToString(ByteArray(32).also(random::nextBytes)), clock())

    fun googleAuthorization(link: PendingAuthLink): URI {
        check(link.purpose == AuthLinkPurpose.GOOGLE)
        return endpoint(
            "/authorize",
            "provider" to "google",
            "redirect_to" to redirect(),
            "code_challenge" to challenge(link),
            "code_challenge_method" to "s256",
            "prompt" to "select_account"
        )
    }

    suspend fun signUp(email: String, password: String, link: PendingAuthLink) {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
            put("code_challenge", challenge(link))
            put("code_challenge_method", "s256")
        }
        request("POST", endpoint("/signup", "redirect_to" to redirect()), body)
    }

    suspend fun requestPasswordReset(email: String, link: PendingAuthLink) {
        val body = buildJsonObject {
            put("email", email)
            put("code_challenge", challenge(link))
            put("code_challenge_method", "s256")
        }
        request("POST", endpoint("/recover", "redirect_to" to redirect()), body)
    }

    suspend fun signIn(email: String, password: String): AuthSessionRecord =
        token("password", buildJsonObject {
            put("email", email)
            put("password", password)
        })

    suspend fun refresh(refreshToken: String): AuthSessionRecord =
        token("refresh_token", buildJsonObject { put("refresh_token", refreshToken) })

    suspend fun exchange(link: PendingAuthLink, uri: URI): AuthSessionRecord {
        val callback = config.redirect ?: throw IdentityFailure(IdentityError.CONFIGURATION)
        val validDestination = uri.scheme == callback.scheme && uri.rawAuthority == callback.rawAuthority &&
            uri.path == callback.path
        val isFreshLink = clock() - link.createdAtMs in 0..MAX_LINK_AGE_MS
        val codes = uri.rawQuery.orEmpty().split('&').filter { it.startsWith("code=") }
        val code = codes.singleOrNull()?.substringAfter('=')
            ?.takeIf { isFreshLink && validDestination && uri.fragment == null && it.matches(codeFormat) }
            ?: throw IdentityFailure(IdentityError.LINK)
        return token("pkce", buildJsonObject {
            put("auth_code", code)
            put("code_verifier", link.verifier)
        })
    }

    suspend fun changePassword(accessToken: String, password: String) {
        request("PUT", endpoint("/user"), buildJsonObject { put("password", password) }, accessToken)
    }

    suspend fun updateProfile(accessToken: String, name: String, username: String) {
        request("PUT", endpoint("/user"), buildJsonObject {
            put("data", buildJsonObject {
                put("full_name", name)
                put("username", username)
            })
        }, accessToken)
    }

    suspend fun revoke(accessToken: String) {
        request("POST", endpoint("/logout", "scope" to "local"), bearer = accessToken)
    }

    private suspend fun token(grant: String, body: JsonObject): AuthSessionRecord {
        val value = request("POST", endpoint("/token", "grant_type" to grant), body)
        val now = clock()
        return try {
            val user = value.getValue("user").jsonObject
            if (user.text("email_confirmed_at").isNullOrBlank()) {
                throw IdentityFailure(IdentityError.EMAIL_UNVERIFIED)
            }
            val duration = value["expires_in"]?.jsonPrimitive?.longOrNull
                ?.takeIf { it in 1..MAX_TOKEN_SECONDS } ?: error("Invalid expiration")
            val metadata = user["user_metadata"] as? JsonObject
            AuthSessionRecord(
                userId = AccountId.parse(user.requiredText("id")).value,
                email = user.requiredText("email"),
                displayName = metadata?.text("full_name"),
                accessToken = value.requiredText("access_token"),
                refreshToken = value.requiredText("refresh_token"),
                expiresAtWallMs = now + duration * 1_000,
                verifiedAtWallMs = now
            )
        } catch (_: RuntimeException) {
            throw IdentityFailure(IdentityError.SERVICE)
        }
    }

    private suspend fun request(
        method: String,
        url: URI,
        body: JsonObject? = null,
        bearer: String? = null
    ): JsonObject {
        val response = try {
            transport.request(method, url, config.publishableKey, bearer, body)
        } catch (_: IOException) {
            throw IdentityFailure(IdentityError.CONNECTION)
        }
        val parsed = try {
            if (response.body.isBlank()) buildJsonObject {} else Json.parseToJsonElement(response.body).jsonObject
        } catch (_: SerializationException) {
            buildJsonObject {}
        } catch (_: IllegalArgumentException) {
            buildJsonObject {}
        }
        if (response.status in 200..299) return parsed
        val code = (parsed["error_code"] ?: parsed["code"])?.let { element ->
            runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
        }
        val failure = when {
            response.status == 429 -> IdentityError.RATE_LIMIT
            code == "email_not_confirmed" -> IdentityError.EMAIL_UNVERIFIED
            code in invalidLinkCodes || response.status in 400..499 && url.rawQuery == "grant_type=pkce" ->
                IdentityError.LINK
            response.status in 400..499 -> IdentityError.CREDENTIALS
            else -> IdentityError.SERVICE
        }
        throw IdentityFailure(failure)
    }

    private fun redirect(): String = config.redirect?.toASCIIString()
        ?: throw IdentityFailure(IdentityError.CONFIGURATION)

    private fun endpoint(path: String, vararg query: Pair<String, String>): URI {
        val parameters = query.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        val suffix = if (parameters.isEmpty()) "" else "?$parameters"
        return config.origin.resolve("/auth/v1$path$suffix")
    }

    private fun challenge(link: PendingAuthLink): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(link.verifier.encodeToByteArray()))

    private companion object {
        val random = SecureRandom()
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val codeFormat = Regex("[A-Za-z0-9_-]{10,256}")
        val invalidLinkCodes = setOf("flow_state_expired", "flow_state_not_found", "bad_code_verifier", "otp_expired")
        const val MAX_LINK_AGE_MS = 60 * 60 * 1_000L
        const val MAX_TOKEN_SECONDS = 7 * 24 * 60 * 60L
    }
}

private object HttpsAuthTransport : AuthHttpTransport {
    override suspend fun request(
        method: String,
        url: URI,
        key: String,
        bearer: String?,
        body: JsonObject?
    ): AuthHttpResponse = withContext(Dispatchers.IO) {
        val connection = url.toURL().openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = AUTH_TIMEOUT_MS
            connection.readTimeout = AUTH_TIMEOUT_MS
            connection.setRequestProperty("apikey", key)
            connection.setRequestProperty("Accept", "application/json")
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            body?.let {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { stream -> stream.write(it.toString().encodeToByteArray()) }
            }
            val status = connection.responseCode
            val input = if (status < HttpURLConnection.HTTP_BAD_REQUEST) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val output = input?.use { stream ->
                val buffer = ByteArray(8_192)
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    if (bytes.size() + read > MAX_RESPONSE_BYTES) throw IOException("Auth response too large")
                    bytes.write(buffer, 0, read)
                }
                bytes.toByteArray().decodeToString()
            }.orEmpty()
            AuthHttpResponse(status, output)
        } finally {
            connection.disconnect()
        }
    }
}

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.requiredText(key: String): String =
    requireNotNull(text(key)?.takeIf(String::isNotBlank))
