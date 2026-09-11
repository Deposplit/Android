package com.deposplit.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.deposplit.DeposplitApp
import com.deposplit.value_objects.IdentityIntegrity
import com.deposplit.value_objects.ShareTransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One pass: syncInbox() and nothing else.
 *
 * That is the holder half — collect deposits addressed to this device, process rotations and
 * recovery metadata, emit the heartbeats that have come due — and it is exactly the beacon the
 * trust model asks for, which is a holder reporting in rather than an owner auditing him.
 * syncDistributed() is deliberately absent: it refreshes this device's view of its *own*
 * holders, which nobody can see until the app is opened, and opening it syncs anyway. Polling
 * on the owner's behalf while she is not looking would double the relay chatter to refresh
 * state nothing renders.
 *
 * Key storage is available whenever the app runs — the master key is not bound to user
 * authentication, see AndroidIdentityStore — so unlike iOS there is no locked-device case to
 * sit out here. Only a device with no identity at all has nothing to say.
 */
class CustodyRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as DeposplitApp
        // Nothing to emit and nothing to sign with. Not a failure, so it must not retry: the
        // next pass will find the same thing until the user does something about it.
        if (!app.authAdapter.isRegistered()) return@withContext Result.success()
        if (app.authAdapter.integrity() == IdentityIntegrity.KEYS_LOST) return@withContext Result.success()

        try {
            app.shareManagement.syncInbox()
        } catch (_: Exception) {
            // An unreachable relay is the ordinary case, not an error worth surfacing. Backoff
            // retries well inside the day this pass repeats on.
            return@withContext Result.retry()
        }

        // Best-effort, and last. Failing to announce must never undo a pass that already emitted
        // its heartbeats: a retry would re-run syncInbox, which is harmless but pointless, and
        // meanwhile the custody signal this pass exists for has already gone out.
        runCatching {
            val waiting = app.shareManagement.listPendingRequests()
                .filter { it.transactionType == ShareTransactionType.RETRIEVAL }
            RequestNotifier.announce(applicationContext, waiting)
        }

        Result.success()
    }
}
