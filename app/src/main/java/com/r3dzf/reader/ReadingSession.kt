package com.r3dzf.reader

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged

internal class ReadingSession(context: Context,private val id: String?,private val scope: CoroutineScope) {
    private val library=DocumentLibrary.get(context)
    private var latest: ReadingProgress?=null
    private var checkpoint: Job?=null
    suspend fun initial(): ReadingProgress = if(id==null) ReadingProgress() else library.documents().firstOrNull { it.id==id }?.progress ?: ReadingProgress()
    fun changed(progress: ReadingProgress) {
        if(id==null) return
        latest=progress.bounded();checkpoint?.cancel()
        checkpoint=scope.launch { delay(600);latest?.let { library.saveProgress(id,it) } }
    }
    fun stop() { checkpoint?.cancel();if(id!=null) latest?.let { library.saveProgress(id,it) } }
}

@Composable
internal fun ObserveReadingProgress(state: LazyListState,total: Int,onProgress: (ReadingProgress) -> Unit) {
    val callback by rememberUpdatedState(onProgress)
    LaunchedEffect(state,total) {
        snapshotFlow { ReadingProgress(state.firstVisibleItemIndex,state.firstVisibleItemScrollOffset,total).bounded() }
            .distinctUntilChanged().collect { callback(it) }
    }
}
