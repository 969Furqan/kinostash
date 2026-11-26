package app.kinostash.android

import android.app.Application
import app.kinostash.android.data.AddonRepository
import app.kinostash.android.data.StremioAccount
import app.kinostash.android.data.WatchHistory
import app.kinostash.android.downloads.OfflineDownloads
import app.kinostash.android.server.StremioServer
import kotlinx.coroutines.launch
import timber.log.Timber

class App : Application() {
    /** For app-wide background work (sync etc.). */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

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
    lateinit var searches: app.kinostash.android.data.SearchHistory
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
        searches = app.kinostash.android.data.SearchHistory(this)
        OfflineDownloads.init(this)
        runCatching { app.kinostash.android.data.EpisodeNotifier.schedule(this) }
        app.kinostash.android.update.AppUpdater.init(this)
        // Stremio account: push library changes, and pull the account's library on start.
        library.onChange = { item, added ->
            if (account.isSignedIn) appScope.launch { runCatching { account.pushLibrary(item, removed = !added) } }
        }
        if (account.isSignedIn) appScope.launch {
            runCatching { val (items, removed) = account.librarySnapshot(); library.applyRemote(items, removed) }
        }
        runCatching { app.kinostash.android.update.UpdateWorker.schedule(this) }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
