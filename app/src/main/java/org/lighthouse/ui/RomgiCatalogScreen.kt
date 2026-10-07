// Copyright 2026 r0mn-creator
// SPDX-License-Identifier: Apache-2.0

package org.lighthouse.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lighthouse.data.DownloadQueue
import org.lighthouse.data.PlatformProfile
import org.lighthouse.data.RomgiCatalog
import org.lighthouse.theme.LocalTheme

private const val COLUMNS = 6
private const val PAGE_SIZE = 120
/** Grid index of the first game: index 0 is the scrolling header. */
private const val HEADER_ITEMS = 1

private sealed interface CatalogTab {
    val label: String
    data object Installed : CatalogTab { override val label = "Instalados" }
    data class Console(val platform: RomgiCatalog.Platform) : CatalogTab {
        override val label get() = platform.name
    }
}

/** Title, platform, local cover path. */
typealias InstalledGame = Triple<String, String, String?>

/**
 * Steam-style store: a cover grid per console, L1/R1 switch tabs, X searches,
 * A opens the game's actions. Pad input arrives via [navEvents] because the
 * activity owns key dispatch.
 */
@Composable
fun RomgiCatalogScreen(
    catalog: RomgiCatalog,
    profiles: List<PlatformProfile>,
    installedGames: List<InstalledGame>,
    revision: Int,
    refreshing: Boolean,
    loadError: String?,
    navEvents: Flow<Nav>,
    onSearch: (current: String, apply: (String) -> Unit) -> Unit,
    onRefresh: () -> Unit,
    onLibraryChanged: () -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val tabRowState = rememberLazyListState()

    var platforms by remember { mutableStateOf<List<RomgiCatalog.Platform>>(emptyList()) }
    var installed by remember { mutableStateOf<List<RomgiCatalog.Entry>>(emptyList()) }
    var tabIndex by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<RomgiCatalog.Entry>>(emptyList()) }
    var endReached by remember { mutableStateOf(false) }
    var loadingPage by remember { mutableStateOf(false) }
    var cursor by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var actionFor by remember { mutableStateOf<RomgiCatalog.Entry?>(null) }
    var actionCursor by remember { mutableIntStateOf(0) }

    val tabs = remember(platforms) { listOf<CatalogTab>(CatalogTab.Installed) + platforms.map { CatalogTab.Console(it) } }
    val tab = tabs[tabIndex.coerceIn(0, tabs.lastIndex)]
    val platformNames = remember(platforms) { platforms.associate { it.id to it.name } }
    val installedSlugs = remember(installed) { installed.mapTo(HashSet()) { it.slug } }

    LaunchedEffect(revision, installedGames) {
        withContext(Dispatchers.IO) {
            platforms = runCatching { if (catalog.isInstalled()) catalog.platforms() else emptyList() }.getOrDefault(emptyList())
            installed = runCatching { catalog.matchInstalled(installedGames) }.getOrDefault(emptyList())
        }
    }

    suspend fun loadMore() {
        if (loadingPage || endReached) return
        loadingPage = true
        val page = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    query.isNotBlank() -> catalog.search(query, null, limit = 300)
                    tab is CatalogTab.Console -> catalog.search("", tab.platform.id, PAGE_SIZE, items.size)
                    else -> installed
                }
            }
        }
        page.onSuccess { got ->
            items = if (query.isNotBlank() || tab is CatalogTab.Installed) got else items + got
            endReached = query.isNotBlank() || tab is CatalogTab.Installed || got.size < PAGE_SIZE
        }.onFailure {
            status = "Falha ao listar: ${it.message}"
            endReached = true
        }
        loadingPage = false
    }

    LaunchedEffect(tab, query, installed) {
        items = emptyList(); endReached = false; loadingPage = false; cursor = 0
        gridState.scrollToItem(0)
        loadMore()
    }

    // Fetch the next page as touch scrolling or the cursor nears the end.
    val loadMoreLatest by rememberUpdatedState<suspend () -> Unit> { loadMore() }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= items.size + HEADER_ITEMS - COLUMNS * 2) loadMoreLatest() }
    }

    LaunchedEffect(tabIndex) { if (tabs.size > 1) tabRowState.animateScrollToItem(tabIndex) }

    LaunchedEffect(cursor) {
        val target = cursor + HEADER_ITEMS
        val info = gridState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == target }
        val fullyVisible = item != null && item.offset.y >= 0 && item.offset.y + item.size.height <= info.viewportEndOffset
        when {
            cursor < COLUMNS -> gridState.animateScrollToItem(0)
            !fullyVisible -> gridState.animateScrollToItem((cursor - COLUMNS).coerceAtLeast(0) + HEADER_ITEMS)
        }
    }

    fun rootFor(entry: RomgiCatalog.Entry): String? {
        val consoleName = platformNames[entry.platform] ?: entry.platform
        val profile = profiles.firstOrNull { RomgiCatalog.matchesProfile(entry.platform, it.id) }
        val root = profile?.source?.roots?.firstOrNull()
        status = when {
            profile == null -> "Adicione o console $consoleName no LH (Y no início) para instalar jogos dele."
            root == null -> "Escolha a pasta de ${profile.name} no LH primeiro."
            else -> return root
        }
        return null
    }

    val downloads by DownloadQueue.states.collectAsState()

    fun install(entry: RomgiCatalog.Entry) {
        val root = rootFor(entry) ?: return
        DownloadQueue.dismiss(entry.slug)
        status = if (DownloadQueue.enqueue(catalog, entry, root)) "Na fila: ${entry.title}"
        else "${entry.title} já está na fila"
    }

    fun delete(entry: RomgiCatalog.Entry) {
        val root = rootFor(entry) ?: return
        scope.launch {
            busy = true
            status = runCatching {
                val deleted = withContext(Dispatchers.IO) { catalog.deleteByEntry(entry, root) }
                if (deleted) { onLibraryChanged(); "Removido: ${entry.title}" }
                else "Nenhum arquivo encontrado para ${entry.title}"
            }.getOrElse { "Falha ao excluir: ${it.message}" }
            busy = false
        }
    }

    fun isInstalled(e: RomgiCatalog.Entry) = e.installed || e.slug in installedSlugs

    fun isQueued(e: RomgiCatalog.Entry) = downloads[e.slug].let { it is DownloadQueue.State.Queued || it is DownloadQueue.State.Running }

    fun runAction(entry: RomgiCatalog.Entry, index: Int) {
        actionFor = null
        if (index != 0 || busy) return
        if (isInstalled(entry)) delete(entry) else install(entry)
    }

    /** A on a game that is not installed queues it straight away; installed games get the menu. */
    fun press(entry: RomgiCatalog.Entry) {
        when {
            isQueued(entry) -> status = "${entry.title} já está na fila"
            isInstalled(entry) -> { actionFor = entry; actionCursor = 0 }
            else -> install(entry)
        }
    }

    fun openSearch() = onSearch(query) { query = it.trim() }

    val onNav by rememberUpdatedState<(Nav) -> Unit> { nav ->
        val action = actionFor
        if (action != null) {
            when (nav) {
                Nav.LEFT, Nav.UP -> actionCursor = 0
                Nav.RIGHT, Nav.DOWN -> actionCursor = 1
                Nav.LAUNCH -> runAction(action, actionCursor)
                Nav.BACK -> actionFor = null
                else -> Unit
            }
            return@rememberUpdatedState
        }
        when (nav) {
            Nav.PREV_SYSTEM, Nav.NEXT_SYSTEM -> {
                query = ""
                val step = if (nav == Nav.NEXT_SYSTEM) 1 else -1
                tabIndex = (tabIndex + step + tabs.size) % tabs.size
            }
            Nav.LEFT, Nav.RIGHT, Nav.UP, Nav.DOWN ->
                cursor = GridCursor(cursor, COLUMNS).move(nav, items.size).index
            Nav.LAUNCH -> items.getOrNull(cursor)?.let { press(it) }
            Nav.SEARCH -> openSearch()
            Nav.MENU -> if (!refreshing) onRefresh()
            Nav.BACK -> if (query.isNotEmpty()) query = "" else onClose()
        }
    }
    LaunchedEffect(navEvents) { navEvents.collect { onNav(it) } }

    Box(Modifier.fillMaxSize().background(theme.background)) {
        Column(Modifier.fillMaxSize()) {
            // Same tab strip as the home screen's systems: L1/R1 chips around the row.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BumperChip("L1") { onNav(Nav.PREV_SYSTEM) }
                Spacer(Modifier.width(12.dp))
                LazyRow(
                    state = tabRowState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rowItemsIndexed(tabs) { i, t ->
                        val on = i == tabIndex && query.isBlank()
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (on) theme.primary.copy(alpha = 0.28f) else Color.Transparent)
                                .clickable { query = ""; tabIndex = i }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        ) {
                            Text(
                                (if (t is CatalogTab.Installed) "${t.label} (${installed.size})" else t.label).uppercase(),
                                color = if (on) theme.textPrimary else theme.textSecondary,
                                fontSize = 17.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                BumperChip("R1") { onNav(Nav.NEXT_SYSTEM) }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(COLUMNS),
                state = gridState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // Scrolls away with the covers rather than eating fixed screen height.
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text(
                            when {
                                query.isNotBlank() -> "Busca: \"$query\""
                                tab is CatalogTab.Console -> tab.platform.name
                                else -> "Jogos instalados"
                            },
                            color = theme.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            when {
                                refreshing -> "Atualizando catálogo..."
                                tab is CatalogTab.Console && query.isBlank() -> "${tab.platform.count} jogos"
                                else -> "${items.size} jogos"
                            },
                            color = theme.textSecondary, fontSize = 15.sp,
                        )
                        loadError?.let { Text(it, color = theme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }

                if (items.isEmpty() && !loadingPage) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            when {
                                !catalog.isInstalled() && refreshing -> "Baixando o catálogo pela primeira vez..."
                                !catalog.isInstalled() -> "Catálogo não baixado. Aperte Y para atualizar."
                                query.isNotBlank() -> "Nada encontrado para \"$query\"."
                                tab is CatalogTab.Installed -> "Nenhum jogo instalado ainda. Aperte R1 para ver os consoles."
                                else -> "Nenhum jogo neste console."
                            },
                            color = theme.textSecondary,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        )
                    }
                }

                itemsIndexed(items, key = { _, e -> e.slug }) { i, entry ->
                    CatalogTile(
                        entry = entry,
                        installed = isInstalled(entry),
                        download = downloads[entry.slug],
                        focused = i == cursor,
                        onClick = { if (i == cursor) press(entry) else cursor = i },
                    )
                }
            }

            // Same bottom bar as the home screen: every pad action shown and tappable.
            val selected = items.getOrNull(cursor)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PadHint("B", if (query.isNotEmpty()) "Limpar busca" else "Fechar", { onNav(Nav.BACK) })
                Spacer(Modifier.width(14.dp))
                PadHint("Y", if (refreshing) "Atualizando..." else "Atualizar", { onNav(Nav.MENU) }, dim = refreshing)
                Spacer(Modifier.width(14.dp))
                PadHint("X", "Pesquisar", { onNav(Nav.SEARCH) })
                Spacer(Modifier.weight(1f))
                val active = downloads.values.count { it !is DownloadQueue.State.Failed }
                val selectedDownload = selected?.let { downloads[it.slug] }
                // The selected game's failure reason beats the generic status line.
                val line = (selectedDownload as? DownloadQueue.State.Failed)?.let { "Falhou: ${it.reason}" }
                    ?: (selectedDownload as? DownloadQueue.State.Running)?.text
                    ?: status
                    ?: if (active > 0) "$active na fila de downloads" else null
                line?.let {
                    Text(it, color = theme.textSecondary, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(14.dp))
                }
                PadHint(
                    "A",
                    when {
                        selected == null -> "—"
                        selectedDownload is DownloadQueue.State.Queued -> "Na fila"
                        selectedDownload is DownloadQueue.State.Running -> "Baixando"
                        selectedDownload is DownloadQueue.State.Failed -> "Tentar de novo"
                        isInstalled(selected) -> "Opções"
                        else -> "Baixar"
                    },
                    { onNav(Nav.LAUNCH) },
                    dim = selected == null || busy || (selected.let { isQueued(it) }),
                )
            }
        }

        actionFor?.let { entry ->
            GameContextMenu(
                title = entry.title,
                items = actionItems(isInstalled(entry)),
                confirming = false,
                cursor = actionCursor,
                onSelect = { actionCursor = it },
                onActivate = { runAction(entry, actionCursor) },
                onDismiss = { actionFor = null },
            )
        }
    }
}

/** Index 0 is always the real action, so the cursor logic does not care which one it is. */
private fun actionItems(installed: Boolean): List<Pair<String, Boolean>> =
    listOf((if (installed) "Excluir do aparelho" else "Instalar") to installed, "Cancelar" to false)

/** Same look as the home screen's cover tiles: title only on the focused one. */
@Composable
private fun CatalogTile(
    entry: RomgiCatalog.Entry,
    installed: Boolean,
    download: DownloadQueue.State?,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalTheme.current
    val scale by animateFloatAsState(if (focused) 1f else 0.94f, tween(140), label = "tile")
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .scale(scale)
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(shape)
            .background(theme.surfaceVariant)
            .then(if (focused) Modifier.border(3.dp, theme.primary, shape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        if (entry.boxartUrl.isNullOrBlank()) {
            Text(
                entry.title,
                color = theme.textSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(10.dp),
            )
        } else {
            val model: Any = entry.boxartUrl.let { if (it.startsWith("/")) java.io.File(it) else it }
            AsyncImage(
                model = model,
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (focused) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(entry.title, color = theme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        when (download) {
            is DownloadQueue.State.Queued, is DownloadQueue.State.Running -> {
                val pct = (download as? DownloadQueue.State.Running)?.percent
                Column(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        when {
                            download is DownloadQueue.State.Queued -> "Na fila"
                            pct != null -> "$pct%"
                            else -> "Baixando"
                        },
                        color = theme.textPrimary,
                        fontSize = if (pct != null) 22.sp else 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Box(
                        Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth(0.75f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(theme.textPrimary.copy(alpha = 0.25f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(((pct ?: 0) / 100f).coerceIn(0f, 1f))
                                .background(theme.primary),
                        )
                    }
                }
            }
            is DownloadQueue.State.Failed -> TileBadge("falhou", theme.error)
            null -> if (installed) TileBadge("instalado", theme.primary)
        }
    }
}

@Composable
private fun BoxScope.TileBadge(text: String, color: Color) {
    Box(
        Modifier
            .align(Alignment.TopStart)
            .padding(6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}
