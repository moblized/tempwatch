package app.tempwatch

import android.app.Application
import app.tempwatch.cook.Alerts
import app.tempwatch.cook.TempHub
import app.tempwatch.data.Prefs

class TempWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Alerts.ensureChannels(this)
        TempHub.init(this) // picks up a cook that was running if the app got closed mid-cook
    }
}
