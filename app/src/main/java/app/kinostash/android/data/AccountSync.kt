package app.kinostash.android.data

import app.kinostash.android.App

/** What happens after signing in or creating a Stremio account. */
object AccountSync {
    data class Result(val addonsAddedToAccount: Int, val addonsImported: Int, val libraryImported: Int, val libraryPushed: Int)

    suspend fun afterSignIn(): Result {
        val app = App.instance
        val account = app.account
        // 1. Make sure the account has the essentials (Cinemeta, Torrentio, OpenSubtitles) and the
        //    addons already installed here, so Stremio on other devices has them too.
        val defaults = AddonRepository.DEFAULT_ADDONS.mapNotNull { url ->
            runCatching { url to AddonClient.getJson(url) }.getOrNull()
        }
        val local = app.addons.rawEntries()
        val toAccount = (defaults + local).distinctBy { it.first }
        val added = runCatching { account.addToAccount(toAccount) }.getOrDefault(0)
        // 2. Use the account's addons here.
        val accountAddons = account.addons()
        app.addons.replaceAll(accountAddons)
        // 3. Library both ways: import the account's titles, then push titles only saved here.
        val (remote, removed) = account.librarySnapshot()
        val imported = app.library.applyRemote(remote, removed)
        val remoteIds = remote.map { it.id }.toSet() + removed
        var pushed = 0
        for (item in app.library.items.value.filter { it.id !in remoteIds }) {
            if (runCatching { account.pushLibrary(item, removed = false) }.isSuccess) pushed++
        }
        return Result(added, accountAddons.size, imported, pushed)
    }
}
