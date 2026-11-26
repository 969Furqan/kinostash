package app.kinostash.android

import android.app.Application
import app.kinostash.android.data.AddonRepository
import app.kinostash.android.data.StremioAccount
import app.kinostash.android.data.WatchHistory
import app.kinostash.android.downloads.OfflineDownloads
import app.kinostash.android.server.StremioServer
import timber.log.Timber

class App : Application() {
    lateinit var addons: AddonRepository
        private set
    lateinit var account: StremioAccount
        private set
    lateinit var history: WatchHistory
        private set
    lateinit var server: StremioServer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Timber.plant(Timber.DebugTree())
        addons = AddonRepository(this)
        account = StremioAccount(this)
        history = WatchHistory(this)
        server = StremioServer(this)
        OfflineDownloads.init(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
