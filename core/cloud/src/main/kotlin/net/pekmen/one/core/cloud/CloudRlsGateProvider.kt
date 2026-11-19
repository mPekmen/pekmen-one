package net.pekmen.one.core.cloud

import net.pekmen.one.core.model.CloudRlsGateStatus

/**
 * Provides the runtime verification gate controlling whether live cloud auth, sync,
 * and private circles operations are permitted. Defaults to fail-closed offline mode.
 */
fun interface CloudRlsGateProvider {
    fun currentGateStatus(): CloudRlsGateStatus
}

class DefaultCloudRlsGateProvider(
    private val compiledWithCloudFlag: Boolean = false,
    private val backendEndpointConfigured: Boolean = false,
    private val rlsVerified: Boolean = false
) : CloudRlsGateProvider {
    override fun currentGateStatus(): CloudRlsGateStatus = CloudRlsGateStatus(
        compiledWithCloudFlag = compiledWithCloudFlag,
        backendEndpointConfigured = backendEndpointConfigured,
        rlsVerified = rlsVerified
    )
}
