package app.tempwatch.cook

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Buttons on the notifications (Stop alarm, End cook), and Chroma Noise's replies. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            STOP_ALARM -> TempHub.stopAlarm()
            END_COOK -> TempHub.endCook()
            CHROMA_RINGING -> TempHub.onChromaRinging()
        }
    }

    companion object {
        const val STOP_ALARM = "app.tempwatch.STOP_ALARM"
        const val END_COOK = "app.tempwatch.END_COOK"
        /** Chroma Noise: "the alarm is ringing here". (Its Stop sends [STOP_ALARM].) */
        const val CHROMA_RINGING = "app.tempwatch.CHROMA_RINGING"

        fun pending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, ActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
