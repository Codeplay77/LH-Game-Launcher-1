// Copyright 2026 r0mn-creator
// SPDX-License-Identifier: Apache-2.0

package org.lighthouse.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Catalog installs, one at a time, outside any screen: closing the catalog does not
 * cancel them, and a second press on a queued game is a no-op instead of a second download.
 */
object DownloadQueue {

    sealed interface State {
        data object Queued : State
        data class Running(val text: String, val percent: Int?) : State
        data class Failed(val reason: String) : State
    }

    private class Job(val catalog: RomgiCatalog, val entry: RomgiCatalog.Entry, val treeUri: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = Channel<Job>(Channel.UNLIMITED)
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())

    /** Keyed by entry slug; finished downloads leave the map. */
    val states: StateFlow<Map<String, State>> = _states

    /** Called on the main thread after each successful install, so the library can rescan. */
    @Volatile var onInstalled: (RomgiCatalog.Entry) -> Unit = {}

    init {
        scope.launch { for (job in jobs) run(job) }
    }

    /** @return false when the game is already queued or downloading. */
    fun enqueue(context: android.content.Context, catalog: RomgiCatalog, entry: RomgiCatalog.Entry, treeUri: String): Boolean {
        val current = _states.value[entry.slug]
        if (current is State.Queued || current is State.Running) return false
        _states.update { it + (entry.slug to State.Queued) }
        jobs.trySend(Job(catalog, entry, treeUri))
        DownloadService.start(context.applicationContext)
        return true
    }

    fun dismiss(slug: String) = _states.update { if (it[slug] is State.Failed) it - slug else it }

    private suspend fun run(job: Job) {
        val slug = job.entry.slug
        fun set(state: State) = _states.update { it + (slug to state) }
        set(State.Running("Procurando link...", null))
        val failure = runCatching {
            val links = job.catalog.links(slug)
            check(links.isNotEmpty()) { "nenhum link de download disponível" }
            // A dead mirror should not end the attempt while other sources remain.
            var last: Throwable? = null
            for (link in links) {
                val ok = runCatching {
                    job.catalog.install(link, job.entry.platform, job.treeUri) { text, pct -> set(State.Running(text, pct)) }
                }.onFailure { if (it is CancellationException) throw it; last = it }.isSuccess
                if (ok) return@runCatching
            }
            throw last!!
        }.exceptionOrNull()

        if (failure == null) {
            _states.update { it - slug }
            withContext(Dispatchers.Main) { onInstalled(job.entry) }
        } else {
            set(State.Failed(failure.message ?: failure::class.simpleName ?: "erro"))
        }
    }
}
