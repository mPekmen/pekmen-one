package net.pekmen.one.core.cloud.auth

import net.pekmen.one.core.model.CloudRlsGateStatus

class CloudRlsGatedException(
    val gateStatus: CloudRlsGateStatus
) : IllegalStateException(
    "Live cloud access is gated OFF (compiled=${gateStatus.compiledWithCloudFlag}, " +
        "endpoint=${gateStatus.backendEndpointConfigured}, rlsVerified=${gateStatus.rlsVerified})"
)

class AuthRejectedException(message: String) : IllegalStateException(message)

/**
 * Remote authentication gateway for passwordless Email + OTP sign-in and token refresh.
 */
interface AuthBackendGateway {
    suspend fun requestOtp(email: String): Result<Unit>
    suspend fun verifyOtp(email: String, code: String): Result<AuthSessionRecord>
    suspend fun refreshSession(refreshToken: String): Result<AuthSessionRecord>
    suspend fun revokeSession(accessToken: String): Result<Unit>
}
