package app.tempwatch.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import app.tempwatch.MainActivity
import app.tempwatch.R
import app.tempwatch.cook.DonenessLabel
import app.tempwatch.cook.Estimator
import app.tempwatch.cook.HeatScale
import app.tempwatch.cook.TempHub
import app.tempwatch.cook.TempUnit
import app.tempwatch.cook.Temps
import app.tempwatch.data.Prefs
import java.text.DateFormat
import java.util.Date

/** Everything the widgets and the tile show, worked out once per update. */
data class WidgetSnapshot(
    val cooking: Boolean,
    val title: String,
    val meatC: Double?,
    val grillC: Double?,
    val targetC: Double?,
    val range: ClosedFloatingPointRange<Double>,
    val status: String,
    val statusColor: Int,
    val timeLeft: String?,
    val readyAt: String?,
    val stale: Boolean,
    val unit: TempUnit,
) {
    companion object {
        private const val STORE = "widget"
        private const val TEAL = 0xFF4FD8DF.toInt()
        private const val GREY = 0xFF9A9A9E.toInt()
        private const val WARN = 0xFFFF8A7A.toInt()

        fun build(context: Context): WidgetSnapshot {
            val now = System.currentTimeMillis()
            val unit = Prefs.unit.value
            val cook = TempHub.cook.value
            val rec = cook?.record
            val primary = TempHub.primary(TempHub.devices.value, cook)
            val reading = primary?.second
            val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

            // Remember the last reading so an idle widget still has something to show.
            val readingAt = primary?.first?.lastReadingAt?.takeIf { it > 0 }
            if (reading?.internalC != null && readingAt != null) {
                store.edit()
                    .putFloat("meat", reading.internalC.toFloat())
                    .putFloat("grill", (reading.ambientC ?: Double.NaN).toFloat())
                    .putLong("at", readingAt)
                    .apply()
            }
            val savedMeat = store.getFloat("meat", Float.NaN).takeIf { !it.isNaN() }?.toDouble()
            val savedGrill = store.getFloat("grill", Float.NaN).takeIf { !it.isNaN() }?.toDouble()
            val lastAt = readingAt ?: store.getLong("at", 0L).takeIf { it > 0 }

            val meat = reading?.internalC ?: rec?.samples?.lastOrNull { it.internalC != null }?.internalC ?: savedMeat
            val grill = reading?.ambientC ?: savedGrill
            val stale = lastAt == null || now - lastAt > 30_000L
            val est = rec?.let { Estimator.estimate(it.samples, it.targetC, now, TempHub.TOLERANCE_C) }
            val minutes = est?.minutesLeft
            val rising = rec != null && rec.reachedAt == null && est?.status == Estimator.Status.RISING && minutes != null
            val heat = meat?.let { HeatScale.argb(it) } ?: GREY

            val (status, statusColor) = when {
                rec == null && stale && lastAt != null -> "${Temps.duration(now - lastAt).uppercase()} AGO" to GREY
                rec == null && stale -> "NO READING" to GREY
                rec == null -> "LIVE" to TEAL
                cook?.signalLost == true -> "NO SIGNAL" to WARN
                rec.reachedAt != null -> "DONE" to heat
                est?.status == Estimator.Status.STALLED -> "STALL" to 0xFFFFB020.toInt()
                else -> (meat?.let { DonenessLabel.forCook(rec.title, it) } ?: "COOKING") to heat
            }
            return WidgetSnapshot(
                cooking = rec != null,
                title = rec?.title ?: "TempWatch",
                meatC = meat,
                grillC = grill,
                targetC = rec?.targetC,
                range = HeatScale.ringRange(rec?.startInternalC ?: meat, rec?.targetC),
                status = status,
                statusColor = statusColor,
                timeLeft = if (rising) Temps.timeLeft(minutes!!) else null,
                readyAt = if (rising) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now + (minutes!! * 60_000).toLong())) else null,
                stale = stale,
                unit = unit,
            )
        }
    }

    val meatText: String get() = meatC?.let { "${Temps.whole(it, unit)}°" } ?: "--"
    val heatColor: Int get() = meatC?.let { HeatScale.argb(it) } ?: 0xFF9A9A9E.toInt()
}

/**
 * Keeps the home-screen widgets and the quick-settings tile current. TempHub calls
 * [onStateChanged] on every reading and cook change; updates are spaced at least 5 s apart.
 */
object Widgets {
    const val EXTRA_OPEN_PICKER = "app.tempwatch.OPEN_PICKER"
    private const val MIN_GAP_MS = 5_000L

    private val main = Handler(Looper.getMainLooper())
    private var lastUpdate = 0L
    private var pending = false

    fun onStateChanged(context: Context) {
        val app = context.applicationContext
        val wait = lastUpdate + MIN_GAP_MS - SystemClock.elapsedRealtime()
        if (wait <= 0) {
            updateAll(app)
        } else if (!pending) {
            pending = true
            main.postDelayed({
                pending = false
                updateAll(app)
            }, wait)
        }
    }

    fun updateAll(context: Context) {
        lastUpdate = SystemClock.elapsedRealtime()
        val mgr = AppWidgetManager.getInstance(context) ?: return
        val smallIds = mgr.getAppWidgetIds(ComponentName(context, RingWidget::class.java))
        val wideIds = mgr.getAppWidgetIds(ComponentName(context, CookWidget::class.java))
        if (smallIds.isNotEmpty() || wideIds.isNotEmpty()) {
            val snap = WidgetSnapshot.build(context)
            if (smallIds.isNotEmpty()) {
                val views = ringViews(context, snap)
                smallIds.forEach { runCatching { mgr.updateAppWidget(it, views) } }
            }
            if (wideIds.isNotEmpty()) {
                val views = cookViews(context, snap)
                wideIds.forEach { runCatching { mgr.updateAppWidget(it, views) } }
            }
        }
        runCatching { TileService.requestListeningState(context, ComponentName(context, TempTile::class.java)) }
    }

    /** Opens the app; when nothing is cooking, straight to the preset picker. */
    fun openIntent(context: Context, toPicker: Boolean, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_PICKER, toPicker),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ringPx(context: Context, dp: Int) = (dp * context.resources.displayMetrics.density).toInt()

    private fun ringBitmap(context: Context, s: WidgetSnapshot, dp: Int, small: String?) = RingBitmap.draw(
        sizePx = ringPx(context, dp),
        range = s.range,
        currentC = s.meatC,
        targetC = s.targetC,
        big = s.meatText,
        bigColor = s.heatColor,
        small = small,
        smallColor = if (s.cooking && s.timeLeft != null) 0xFFF3F3F3.toInt() else s.statusColor,
        dim = s.stale,
    )

    /** Small round widget: the ring with the temperature and either time left or the status. */
    fun ringViews(context: Context, s: WidgetSnapshot): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_ring).apply {
            setImageViewBitmap(R.id.ring, ringBitmap(context, s, 170, s.timeLeft ?: s.status))
            setContentDescription(R.id.ring, "Meat ${s.meatText}, ${s.status.lowercase()}")
            setOnClickPendingIntent(R.id.widget_root, openIntent(context, toPicker = !s.cooking, requestCode = 11))
        }

    /** Wide widget: ring on the left, the cook's details on the right. */
    fun cookViews(context: Context, s: WidgetSnapshot): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_cook).apply {
            setImageViewBitmap(R.id.ring, ringBitmap(context, s, 130, null))
            setContentDescription(R.id.ring, "Meat ${s.meatText}")
            setTextViewText(R.id.title, s.title.uppercase())
            if (s.cooking) {
                setTextViewText(R.id.main, s.timeLeft?.let { "$it left" } ?: s.status.lowercase().replaceFirstChar { it.uppercase() })
                setTextViewText(R.id.status, listOfNotNull(s.status, s.readyAt?.let { "READY ~$it" }).joinToString(" · "))
                setTextViewText(R.id.detail1, "Target ${s.targetC?.let { Temps.format(it, s.unit) } ?: "--"}")
            } else {
                setTextViewText(R.id.main, "Not cooking")
                setTextViewText(R.id.status, s.status)
                setTextViewText(R.id.detail1, "Tap to start a cook")
            }
            setTextColor(R.id.status, s.statusColor)
            setTextViewText(R.id.detail2, "Grill ${Temps.format(s.grillC, s.unit)}")
            setOnClickPendingIntent(R.id.widget_root, openIntent(context, toPicker = !s.cooking, requestCode = 12))
        }
}

/** 2×2 round ring widget. */
class RingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.updateAll(context)
}

/** 4×2 cook widget. */
class CookWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.updateAll(context)
}

/** Quick-settings tile: the meat temperature and time left during a cook; tap opens the app. */
class TempTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val s = WidgetSnapshot.build(this)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_thermo)
        if (s.cooking) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = s.meatC?.let { Temps.format(it, s.unit) } ?: "Cooking"
            setSubtitle(tile, s.timeLeft?.let { "$it left" } ?: s.status.lowercase().replaceFirstChar { it.uppercase() })
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "TempWatch"
            setSubtitle(tile, "Start a cook")
        }
        tile.contentDescription = "TempWatch: ${tile.label}"
        tile.updateTile()
    }

    private fun setSubtitle(tile: Tile, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = text
    }

    override fun onClick() {
        super.onClick()
        val open = {
            val toPicker = TempHub.cook.value == null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(Widgets.openIntent(this, toPicker, requestCode = 13))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(Widgets.EXTRA_OPEN_PICKER, toPicker),
                )
            }
        }
        if (isLocked) unlockAndRun { open() } else open()
    }
}
