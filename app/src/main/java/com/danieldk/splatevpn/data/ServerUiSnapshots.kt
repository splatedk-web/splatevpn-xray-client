package com.danieldk.splatevpn.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*

/** Latest cumulative measurements, at most ten list projections per second, off the UI thread. */
@OptIn(FlowPreview::class)
internal fun serverUiSnapshots(nodes: Flow<List<ServerNode>>, pings: Flow<Map<String, Long>>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default): Flow<List<ServerNode>> =
    combine(nodes, pings) { list, results -> list to results }
        .sample(100)
        .map { (list, results) -> list.map { it.withSessionPing(results) } }
        .conflate()
        .flowOn(dispatcher)
