package com.discountscreener.android.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.discountscreener.android.domain.logging.AndroidAppLogger
import com.discountscreener.core.earnings.quotesAreLive
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Prices the reports that are due while the market is open, whether or not the user opened the app.
 *
 * Until this ran, a report was only ever priced if the user happened to launch the app during a
 * session inside the capture window. An option chain is never republished, so every session missed
 * is a report that can never be priced again — the loss the event log exists to prevent.
 *
 * The run is cheap on purpose: it restores the universe already on the phone and asks Yahoo only
 * for the chains of the reports inside the window. It never refreshes the dashboard.
 */
open class EarningsCaptureWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    private val logger = AndroidAppLogger()

    override suspend fun doWork(): Result {
        if (!quotesAreLive(now())) {
            logger.info(TAG, "earnings capture worker: market shut, nothing asked")
            return Result.success()
        }
        return runCatching { capture() }
            .onSuccess { written -> logger.info(TAG, "earnings capture worker: wrote $written event(s)") }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { error ->
                    logger.error(TAG, "earnings capture worker failed", error)
                    Result.retry()
                },
            )
    }

    internal open fun now(): Instant = Instant.now()

    internal open suspend fun capture(): Int =
        DiscountScreenerAppContainer(applicationContext).capturePendingEarnings()

    companion object {
        const val WORK_NAME = "earnings-capture"
        private const val TAG = "EarningsCaptureWorker"
        private const val PERIOD_MINUTES = 90L

        /**
         * One unique work, updated in place. An identical request changes nothing, so a relaunch
         * never resets the cadence; a new period in a new version reaches a phone that already has
         * the old one enqueued, which `KEEP` would have left on the old cadence forever.
         */
        fun schedule(context: Context) {
            var request = PeriodicWorkRequestBuilder<EarningsCaptureWorker>(
                PERIOD_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
