package app.tempwatch.cook

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Rings the "it's done" alarm in Chroma Noise (app.chromanoise), so it sounds and looks like
 * your other alarms: full screen over the lock screen, Stop button, flip the phone to stop.
 * Stopping it there stops TempWatch's alarm too, and the other way round.
 *
 * Chroma Noise confirms it's ringing ([ActionReceiver.CHROMA_RINGING]); if that doesn't come,
 * [TempHub] rings the phone's own alarm sound instead, so a cook is never missed.
 */
object ChromaLink {
    const val PACKAGE = "app.chromanoise"
    private const val RECEIVER = "app.chromanoise.alarm.ExternalAlarmReceiver"
    private const val ACTION_RING = "app.chromanoise.action.RING_EXTERNAL"
    private const val ACTION_STOP = "app.chromanoise.action.STOP_EXTERNAL"
    private const val EXTRA_TITLE = "app.chromanoise.extra.TITLE"
    private const val EXTRA_DETAIL = "app.chromanoise.extra.DETAIL"
    private const val EXTRA_ON_STOP = "app.chromanoise.extra.ON_STOP"
    private const val EXTRA_ON_RINGING = "app.chromanoise.extra.ON_RINGING"
    private const val TAG = "ChromaLink"

    enum class Status { READY, NEEDS_UPDATE, NOT_INSTALLED }

    fun status(context: Context): Status {
        val pm = context.packageManager
        try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(PACKAGE, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return Status.NOT_INSTALLED
        }
        // Versions before the external alarm don't have the receiver.
        @Suppress("DEPRECATION")
        val receivers = pm.queryBroadcastReceivers(Intent(ACTION_RING).setPackage(PACKAGE), 0)
        return if (receivers.isNotEmpty()) Status.READY else Status.NEEDS_UPDATE
    }

    /** Asks Chroma Noise to ring. False if it can't (not installed, too old): ring here instead. */
    fun ring(context: Context, title: String, detail: String): Boolean {
        if (status(context) != Status.READY) return false
        return send(
            context,
            intent(context, ACTION_RING)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_DETAIL, detail)
                .putExtra(EXTRA_ON_RINGING, ActionReceiver.pending(context, ActionReceiver.CHROMA_RINGING)),
        )
    }

    /** Stops a ring we asked for (cancelled before it starts, or silenced). */
    fun stop(context: Context) {
        if (status(context) == Status.READY) send(context, intent(context, ACTION_STOP))
    }

    /** The stop callback doubles as our ID: Chroma Noise only listens to apps it knows. */
    private fun intent(context: Context, action: String): Intent =
        Intent(action)
            .setClassName(PACKAGE, RECEIVER)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(EXTRA_ON_STOP, ActionReceiver.pending(context, ActionReceiver.STOP_ALARM))

    private fun send(context: Context, intent: Intent): Boolean =
        try {
            context.sendBroadcast(intent)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't reach Chroma Noise", e)
            false
        }
}
