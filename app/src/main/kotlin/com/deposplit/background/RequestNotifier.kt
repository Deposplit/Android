package com.deposplit.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.deposplit.MainActivity
import com.deposplit.R
import com.deposplit.value_objects.ShareRequest

/**
 * The one thing a background pass is allowed to interrupt somebody for.
 *
 * A pending retrieval is a contact who cannot go any further until this phone answers, and who
 * has no other way to say so — the relay is blind and may not hold a push token beside a public
 * key, so the alternative to this is that they wait until somebody happens to open the app. The
 * notice is produced here, on this device, from rows it has already fetched under its own
 * identity: nothing about it reaches a third party and nothing new is asked of the relay.
 *
 * Removals deliberately say nothing. The sender flipped her secret to DESTROYING the moment she
 * asked and is not waiting on the answer to carry on, so learning of it at the next launch costs
 * nobody anything — and a lock screen that speaks twice as often for one real interruption is
 * worse at the job.
 *
 * What it says names nobody and no secret, and does not count them. Somebody glancing at a
 * locked phone learns only that Deposplit is installed, which the launcher already told them.
 */
object RequestNotifier {

    private const val CHANNEL_ID = "retrieval-requests"

    // One fixed id, so a second pass replaces the standing notice rather than stacking another
    // copy of the same sentence behind it.
    private const val NOTIFICATION_ID = 1

    private const val PREFS = "deposplit"
    private const val KEY_ANNOUNCED = "announced_retrieval_requests"
    private const val KEY_PERMISSION_ASKED = "notification_permission_asked"

    /**
     * Posts once per request, however many passes see it. [pending] is the retrieval half of
     * listPendingRequests, which the relay has already had its signatures checked against, so a
     * forged row cannot raise a notification.
     */
    fun announce(context: Context, pending: List<ShareRequest>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stillPending = pending.map { it.id.toString() }.toSet()
        // getStringSet hands back an instance that must not be modified, so this is a copy.
        val announced = prefs.getStringSet(KEY_ANNOUNCED, emptySet())?.toSet().orEmpty()

        if (!canNotify(context)) {
            // Record nothing that was never shown: permission may be granted later, and the
            // request will still be waiting when it is. Pruning still happens, so a phone that
            // never gets the permission does not accumulate ids for ever.
            prefs.edit().putStringSet(KEY_ANNOUNCED, announced intersect stillPending).apply()
            return
        }

        val unannounced = stillPending - announced
        // Pruned to what is still pending, so answering a request lets its id go — and a
        // re-issued ask is a fresh row with a fresh id, so it announces itself again.
        prefs.edit().putStringSet(KEY_ANNOUNCED, stillPending).apply()
        if (unannounced.isEmpty()) return

        createChannel(context)
        val sentence = context.getString(R.string.notification_retrieval_waiting)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentText(sentence)
            // The sentence is longer than a collapsed line, and truncating it would cut off the
            // half that says what is being asked for.
            .setStyle(NotificationCompat.BigTextStyle().bigText(sentence))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Granted and not switched off again in system settings — both have to hold to post. */
    fun canNotify(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Asked once, and only once this device is actually keeping something for somebody: before
     * that a notification could never fire, so the prompt would be a dialog about nothing.
     */
    fun shouldAskForPermission(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PERMISSION_ASKED, false)) return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    /** Recorded before the dialog is shown, not after: a permanently denied permission is
     * answered by the system without the user seeing anything, and asking again every launch
     * would be a prompt nobody can ever satisfy. Settings is the way back from there. */
    fun markPermissionAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PERMISSION_ASKED, true)
            .apply()
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_retrievals),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_retrievals_description)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    // Opens the app, and no further. Taking the reader straight to the Requests tab would want a
    // UNUserNotificationCenterDelegate on the other platform, which is the UIKit iOS keeps out —
    // so the two would stop matching in the one place they should. Requests is a top-level tab.
    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
