package app.tempwatch.cook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import app.tempwatch.MainActivity
import app.tempwatch.R
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Notification channels and the one-off alerts: done, almost done, signal lost. */
object Alerts {
    const val CH_LIVE = "live"
    const val CH_DONE = "done"
    const val CH_ALERTS = "alerts"

    const val LIVE_ID = 1
    private const val DONE_ID = 2
    private const val HEADS_UP_ID = 3
    private const val SIGNAL_ID = 4

    fun ensureChannels(context: Context) {
        val nm = manager(context)
        // LOW: silent, but still allowed to become a Live Update (MIN isn't).
        nm.createNotificationChannel(
            NotificationChannel(CH_LIVE, "Cook in progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Live temperatures on the lock screen and status bar while you cook"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        // The alarm sound itself comes from AlarmRinger (on the alarm volume), so this channel is silent.
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Target reached", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pops up when the meat hits its target (the alarm sound plays separately)"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Almost done & signal", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A heads-up shortly before the target, and a warning if the thermometer goes out of range"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
                )
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun showDone(context: Context, rec: CookRecord, meatC: Double, unit: TempUnit) {
        val stop = ActionReceiver.pending(context, ActionReceiver.STOP_ALARM)
        val n = Notification.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_thermo)
            .setContentTitle("${rec.title} is ready")
            .setContentText("Meat is at ${Temps.format(meatC, unit)} (target ${Temps.format(rec.targetC, unit)}). Time to pull it.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context))
            .setDeleteIntent(stop)
            .setAutoCancel(true)
            .addAction(action(context, "Stop alarm", stop))
            .build()
        notify(context, DONE_ID, n)
    }

    fun showHeadsUp(context: Context, rec: CookRecord, meatC: Double, unit: TempUnit) {
        val away = (Temps.toUnit(rec.targetC, unit) - Temps.toUnit(meatC, unit)).roundToInt().coerceAtLeast(1)
        val n = Notification.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_thermo)
            .setContentTitle("Almost done · ${Temps.format(meatC, unit)}")
            .setContentText("${rec.title} is $away° from its ${Temps.format(rec.targetC, unit)} target.")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        notify(context, HEADS_UP_ID, n)
    }

    fun showSignalLost(context: Context) {
        val n = Notification.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_thermo)
            .setContentTitle("Lost the thermometer's signal")
            .setContentText("No reading for over a minute. Bring your phone closer to the booster.")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        notify(context, SIGNAL_ID, n)
    }

    fun cancelDone(context: Context) = manager(context).cancel(DONE_ID)
    fun cancelHeadsUp(context: Context) = manager(context).cancel(HEADS_UP_ID)
    fun cancelSignalLost(context: Context) = manager(context).cancel(SIGNAL_ID)

    fun cancelAll(context: Context) {
        val nm = manager(context)
        listOf(DONE_ID, HEADS_UP_ID, SIGNAL_ID).forEach { nm.cancel(it) }
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun action(context: Context, title: String, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_thermo), title, intent).build()

    private fun notify(context: Context, id: Int, n: Notification) {
        // Without notification permission this just doesn't show; the alarm sound still plays.
        runCatching { manager(context).notify(id, n) }
    }

    fun manager(context: Context): NotificationManager = context.getSystemService(NotificationManager::class.java)
}

/** What the lock-screen card shows. Compared between updates so we only re-post on change. */
data class LiveContent(
    val title: String,
    val text: String,
    val subText: String,
    val chip: String?,
    val progressPermille: Int,
    val ringing: Boolean,
)

/** The ongoing "cook in progress" card: lock screen, notification shade, status-bar chip. */
object LiveCard {
    private const val STEPS = 1000

    fun content(cook: ActiveCook, reading: app.tempwatch.ble.ProbeReading?, unit: TempUnit, now: Long): LiveContent {
        val rec = cook.record
        val meat = reading?.internalC ?: rec.samples.lastOrNull { it.internalC != null }?.internalC
        val est = Estimator.estimate(rec.samples, rec.targetC, now, TempHub.TOLERANCE_C)
        val title = if (rec.reachedAt != null) {
            "Done · ${Temps.format(meat, unit)}"
        } else {
            "${Temps.format(meat, unit)} → ${Temps.format(rec.targetC, unit)}"
        }
        val status = when {
            cook.signalLost -> "No signal for ${Temps.duration(now - cook.lastReadingAt)}"
            rec.reachedAt != null -> "Hit ${Temps.format(rec.targetC, unit)} at ${clock(rec.reachedAt)}"
            else -> estimateText(est)
        }
        val grill = reading?.ambientC?.let { "Grill ${Temps.format(it, unit)}" }
        return LiveContent(
            title = title,
            text = listOfNotNull(status, grill).joinToString(" · "),
            subText = rec.title,
            chip = meat?.let { Temps.format(it, unit, withUnit = false) },
            progressPermille = (Estimator.progress(rec.startInternalC, meat, rec.targetC) * STEPS).toInt(),
            ringing = cook.alarmRinging,
        )
    }

    fun estimateText(est: Estimator.Estimate): String = when (est.status) {
        Estimator.Status.RISING -> {
            val m = est.minutesLeft ?: 0.0
            if (m < 1.0) "Less than a minute left" else "About ${Temps.duration((m * 60_000).toLong())} left"
        }
        Estimator.Status.STALLED -> "Holding steady (stall)"
        Estimator.Status.FALLING -> "Temperature dropping"
        Estimator.Status.DONE -> "At target"
        Estimator.Status.WAITING -> "Working out time left…"
    }

    fun build(context: Context, c: LiveContent): Notification {
        val b = Notification.Builder(context, Alerts.CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_thermo)
            .setContentTitle(c.title)
            .setContentText(c.text)
            .setSubText(c.subText)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(Alerts.openApp(context))
        if (c.ringing) b.addAction(Alerts.action(context, "Stop alarm", ActionReceiver.pending(context, ActionReceiver.STOP_ALARM)))
        b.addAction(Alerts.action(context, "End cook", ActionReceiver.pending(context, ActionReceiver.END_COOK)))
        LiveUpdates.request(b, c.chip)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            b.setStyle(
                Notification.ProgressStyle()
                    .setProgressSegments(listOf(Notification.ProgressStyle.Segment(STEPS)))
                    .setProgress(c.progressPermille),
            )
        } else {
            b.setProgress(STEPS, c.progressPermille, false)
        }
        return b.build()
    }

    /** Shown for the moment the service starts without a cook (it then stops straight away). */
    fun placeholder(context: Context): Notification =
        Notification.Builder(context, Alerts.CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_thermo)
            .setContentTitle("TempWatch")
            .setContentText("Starting…")
            .setOngoing(true)
            .build()

    private fun clock(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
}

/**
 * Android 16 "Live Updates" (promoted ongoing notifications): the status-bar chip and the
 * pinned lock-screen card (Samsung shows it in the Now Bar). On older phones this does nothing
 * and the ordinary ongoing notification shows instead.
 */
object LiveUpdates {
    /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING, spelled out so it also works on the API 36 SDK. */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    fun request(builder: Notification.Builder, chipText: String?) {
        builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        if (chipText != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setShortCriticalText(chipText)
        }
    }

    enum class Status { ON, NOTIFICATIONS_OFF, LIVE_UPDATES_OFF, NOT_SUPPORTED }

    fun status(context: Context): Status {
        val nm = Alerts.manager(context)
        return when {
            !nm.areNotificationsEnabled() -> Status.NOTIFICATIONS_OFF
            Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA -> Status.NOT_SUPPORTED
            !nm.canPostPromotedNotifications() -> Status.LIVE_UPDATES_OFF
            else -> Status.ON
        }
    }

    fun settingsIntent(context: Context, status: Status): Intent {
        val action = if (status == Status.LIVE_UPDATES_OFF && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS
        } else {
            Settings.ACTION_APP_NOTIFICATION_SETTINGS
        }
        return Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
}
