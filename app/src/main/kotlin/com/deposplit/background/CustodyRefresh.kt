package com.deposplit.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

/**
 * The daily holder-side pass, so that keeping a share safe does not depend on somebody opening
 * the app. Every custody signal the owner reads is emitted from the holder's inbox poll, so
 * without this a holder who simply does not launch Deposplit for nine days drops out of that
 * owner's n_live for reasons that have nothing to do with custody.
 *
 * Daily against a three-day emission interval means a heartbeat that has come due goes out
 * within a day of doing so — four days at worst, comfortably inside the nine-day loss
 * threshold. The flex window is doing a second job: the system picks the moment inside it, so
 * the relay sees an unpredictable request roughly once a day rather than a nightly tick it
 * could read as a clock. A device that beacons on a schedule tells the relay when its phone is
 * awake, which foreground-only polling never did, and an unpredictable moment is what keeps
 * that down to "awake sometime today".
 */
object CustodyRefresh {

    private const val WORK_NAME = "custody-refresh"

    /**
     * Safe to call on every launch. UPDATE rather than KEEP so a retuned interval reaches
     * devices that already have the work enqueued, and unlike CANCEL_AND_REENQUEUE it leaves
     * the next run where it was when nothing has actually changed.
     */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<CustodyRefreshWorker>(
            1,
            TimeUnit.DAYS,
            6,
            TimeUnit.HOURS,
        )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
