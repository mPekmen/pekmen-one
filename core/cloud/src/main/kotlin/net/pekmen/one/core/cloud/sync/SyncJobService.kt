package net.pekmen.one.core.cloud.sync

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Implemented by PekmenApplication when cloud sync is enabled.
 */
interface SyncTriggerProvider {
    fun syncRunner(): suspend () -> Result<Int>
}

/**
 * Native Android JobScheduler service executing outbox push + delta pull on network availability.
 * Registered in AndroidManifest.xml with android:permission="android.permission.BIND_JOB_SERVICE"
 * and android:exported="false" only when cloud builds are enabled.
 */
class SyncJobService : JobService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val provider = applicationContext as? SyncTriggerProvider ?: return false
        activeJob?.cancel()
        activeJob = serviceScope.launch {
            val shouldReschedule = runCatching {
                provider.syncRunner().invoke().isFailure
            }.getOrDefault(true)
            jobFinished(params, shouldReschedule)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        activeJob?.cancel()
        activeJob = null
        return true
    }
}
