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
    lateinit var library: app.kinostash.android.data.Library
        private set
    lateinit var upcoming: app.kinostash.android.data.Upcoming
        private set
    lateinit var settings: app.kinostash.android.data.AppSettings
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReporter.install(this)
        Timber.plant(Timber.DebugTree())
        addons = AddonRepository(this)
        account = StremioAccount(this)
        history = WatchHistory(this)
        server = StremioServer(this)
        library = app.kinostash.android.data.Library(this)
        upcoming = app.kinostash.android.data.Upcoming(this, addons, library)
        settings = app.kinostash.android.data.AppSettings(this)
        OfflineDownloads.init(this)
        runCatching { app.kinostash.android.data.EpisodeNotifier.schedule(this) }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
