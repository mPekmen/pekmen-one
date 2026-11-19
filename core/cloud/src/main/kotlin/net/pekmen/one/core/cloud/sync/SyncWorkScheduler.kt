package net.pekmen.one.core.cloud.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/**
 * Background sync scheduling contract. Avoids external WorkManager dependencies so
 * offline-first builds stay minimal and zero-bloat.
 */
interface SyncWorkScheduler {
    fun scheduleImmediateSync(ownerUserId: String)
    fun schedulePeriodicSync(ownerUserId: String, intervalMs: Long = DEFAULT_PERIODIC_INTERVAL_MS)
    fun cancelSync()

    companion object {
        const val DEFAULT_PERIODIC_INTERVAL_MS = 6L * 60L * 60L * 1000L // 6 hours
    }
}

class NoOpSyncWorkScheduler : SyncWorkScheduler {
    override fun scheduleImmediateSync(ownerUserId: String) = Unit
    override fun schedulePeriodicSync(ownerUserId: String, intervalMs: Long) = Unit
    override fun cancelSync() = Unit
}

class JobSchedulerSyncScheduler(
    private val context: Context
) : SyncWorkScheduler {

    private val jobScheduler: JobScheduler?
        get() = context.applicationContext.getSystemService(JobScheduler::class.java)

    override fun scheduleImmediateSync(ownerUserId: String) {
        if (ownerUserId.isBlank()) return
        val scheduler = jobScheduler ?: return
        val jobInfo = JobInfo.Builder(
            IMMEDIATE_JOB_ID,
            ComponentName(context.applicationContext, SyncJobService::class.java)
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(0L)
            .setOverrideDeadline(IMMEDIATE_DEADLINE_MS)
            .setPersisted(false)
            .build()
        scheduler.schedule(jobInfo)
    }

    override fun schedulePeriodicSync(ownerUserId: String, intervalMs: Long) {
        if (ownerUserId.isBlank()) return
        val scheduler = jobScheduler ?: return
        val clampedInterval = intervalMs.coerceAtLeast(JobInfo.getMinPeriodMillis())
        val jobInfo = JobInfo.Builder(
            PERIODIC_JOB_ID,
            ComponentName(context.applicationContext, SyncJobService::class.java)
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(clampedInterval)
            .setPersisted(false)
            .build()
        scheduler.schedule(jobInfo)
    }

    override fun cancelSync() {
        val scheduler = jobScheduler ?: return
        scheduler.cancel(IMMEDIATE_JOB_ID)
        scheduler.cancel(PERIODIC_JOB_ID)
    }

    companion object {
        const val IMMEDIATE_JOB_ID = 5201
        const val PERIODIC_JOB_ID = 5202
        private const val IMMEDIATE_DEADLINE_MS = 60_000L
    }
}
