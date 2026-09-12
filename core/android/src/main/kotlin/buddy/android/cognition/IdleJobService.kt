package buddy.android.cognition

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import buddy.android.BuddyApp
import buddy.android.ledger.LedgerHolder
import kotlin.concurrent.thread

/**
 * The idle cycle (docs/01-architecture.md, "Planning cycles"): when the phone is
 * charging and idle, consolidate memory from the last day of events and corrections.
 */
class IdleJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "buddy-idle") {
            try {
                val ledger = LedgerHolder.getOrNull()
                val mc = Brain.memory
                if (ledger != null && mc != null) {
                    val now = System.currentTimeMillis()
                    val window = ledger.recent(2000).filter { it.ts > now - 24 * 3_600_000L }.reversed()
                    val r = mc.consolidate(window, now)
                    Log.i(BuddyApp.TAG, "idle: memory ${r.status}, ${r.written.size} notes")
                }
            } catch (t: Throwable) {
                Log.e(BuddyApp.TAG, "idle job failed", t)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 42

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java)
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, IdleJobService::class.java))
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setPeriodic(12 * 3_600_000L)
                .setPersisted(true)
                .build()
            js.schedule(job)
        }
    }
}
