package com.r3dzf.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

internal enum class ReaderPageMode { CONTINUOUS,HORIZONTAL,VERTICAL }
internal fun dominantPage(items: List<Pair<Int,Pair<Int,Int>>>,start: Int,end: Int): Int? = items.maxByOrNull { (_,bounds) -> (minOf(end,bounds.first+bounds.second)-maxOf(start,bounds.first)).coerceAtLeast(0) }?.first
private fun Context.activity(): Activity? = when(this) {is Activity -> this;is ContextWrapper -> baseContext.activity();else -> null}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ReaderPages(title: String,count: Int,kind: String,initial: ReadingProgress,onProgress: (ReadingProgress)->Unit,onClose: ()->Unit,
    content: @Composable (index: Int,continuous: Boolean,reset: Int,onTap: ()->Unit,onInteraction: ()->Unit,onZoom: (Float)->Unit)->Unit) {
    val context=LocalContext.current
    val prefs=remember {context.getSharedPreferences("reader-view",Context.MODE_PRIVATE)}
    var mode by rememberSaveable(kind) {mutableStateOf(runCatching {ReaderPageMode.valueOf(prefs.getString("$kind-mode",if(kind=="pdf") "CONTINUOUS" else "HORIZONTAL")!!)}.getOrDefault(ReaderPageMode.CONTINUOUS))}
    var current by rememberSaveable(title) {mutableIntStateOf(initial.index.coerceIn(0,(count-1).coerceAtLeast(0)))}
    var chrome by rememberSaveable(title) {mutableStateOf(true)}
    var menu by remember {mutableStateOf(false)}
    var jump by remember {mutableStateOf(false)}
    var address by remember {mutableStateOf("")}
    var reset by remember {mutableIntStateOf(0)}
    val zooms=remember(title) {mutableStateMapOf<Int,Float>()}
    var navigate by remember {mutableStateOf<(Int)->Unit>({})}
    val callback by rememberUpdatedState(onProgress)
    val observed by rememberUpdatedState<(ReadingProgress)->Unit>({p -> current=p.index;callback(p)})
    val scroll=remember {object: NestedScrollConnection {
        override fun onPreScroll(available: Offset,source: NestedScrollSource): Offset {
            if(source==NestedScrollSource.UserInput && (abs(available.x)>1 || abs(available.y)>1)) chrome=false
            return Offset.Zero
        }
    }}
    val activity=context.activity()
    DisposableEffect(activity,chrome) {
        val window=activity?.window
        val controller=window?.let {WindowCompat.getInsetsController(it,it.decorView)}
        controller?.systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if(chrome) controller?.show(WindowInsetsCompat.Type.systemBars()) else controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {controller?.show(WindowInsetsCompat.Type.systemBars())}
    }
    BackHandler(!chrome) {chrome=true}
    Scaffold(containerColor=Color(0xFFE8ECEB),topBar={if(chrome) Surface(color=MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=6.dp,vertical=5.dp).testTag("reader-header"),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onClose,modifier=Modifier.testTag("viewer-back")) {ReaderIcon(R.drawable.ic_back,stringResource(R.string.close))}
            Column(Modifier.weight(1f)) {
                Text(title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.reader_position,current+1,count),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if((zooms[current]?:1f)>1.01f) IconButton(onClick={reset++},modifier=Modifier.testTag("reader-reset-zoom")) {ReaderIcon(R.drawable.ic_search,stringResource(R.string.reader_reset_zoom))}
            Box {
                IconButton(onClick={menu=true},modifier=Modifier.testTag("reader-mode-menu")) {ReaderIcon(R.drawable.ic_more,stringResource(R.string.reader_mode))}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    ReaderPageMode.entries.forEach {option -> DropdownMenuItem(text={Text(stringResource(when(option) {ReaderPageMode.CONTINUOUS -> R.string.reader_continuous;ReaderPageMode.HORIZONTAL -> R.string.reader_horizontal;ReaderPageMode.VERTICAL -> R.string.reader_vertical}))},
                        modifier=Modifier.testTag("reader-mode-${option.name.lowercase()}"),onClick={mode=option;zooms.clear();prefs.edit().putString("$kind-mode",option.name).apply();menu=false}) }
                }
            }
        }
    }},bottomBar={if(chrome && count>0) Surface(color=MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={navigate(current-1)},enabled=current>0,modifier=Modifier.testTag("reader-previous")) {Text(stringResource(R.string.reader_previous))}
            TextButton(onClick={address=(current+1).toString();jump=true},modifier=Modifier.testTag("reader-page-counter")) {Text("${current+1} / $count")}
            TextButton(onClick={navigate(current+1)},enabled=current<count-1,modifier=Modifier.testTag("reader-next")) {Text(stringResource(R.string.reader_next))}
        }
    }}) {padding ->
        Box(Modifier.fillMaxSize().padding(padding).nestedScroll(scroll).testTag("reader-pages")) {
            if(count>0) key(mode) {
                val scope=rememberCoroutineScope()
                if(mode==ReaderPageMode.CONTINUOUS) {
                    val list=rememberLazyListState(current,if(current==initial.index) initial.offset else 0)
                    SideEffect {navigate={index -> scope.launch {list.scrollToItem(index.coerceIn(0,count-1))}}}
                    LaunchedEffect(list,count) {
                        snapshotFlow {
                            val info=list.layoutInfo
                            val page=dominantPage(info.visibleItemsInfo.map {it.index to (it.offset to it.size)},info.viewportStartOffset,info.viewportEndOffset) ?: current
                            ReadingProgress(page,if(page==list.firstVisibleItemIndex) list.firstVisibleItemScrollOffset else 0,count)
                        }.distinctUntilChanged().collect {observed(it)}
                    }
                    LazyColumn(state=list,modifier=Modifier.fillMaxSize().testTag("$kind-list"),userScrollEnabled=(zooms[current]?:1f)<=1.01f,
                        contentPadding=PaddingValues(8.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        items(count,key={it}) {index -> content(index,true,reset,{chrome=!chrome},{chrome=false},{zooms[index]=it})}
                    }
                } else {
                    val pager=rememberPagerState(initialPage=current,pageCount={count})
                    SideEffect {navigate={index -> scope.launch {pager.scrollToPage(index.coerceIn(0,count-1))}}}
                    LaunchedEffect(pager,count) {snapshotFlow {pager.settledPage}.distinctUntilChanged().collect {observed(ReadingProgress(it,0,count))}}
                    val modifier=Modifier.fillMaxSize().testTag("$kind-pager")
                    if(mode==ReaderPageMode.HORIZONTAL) HorizontalPager(pager,modifier,userScrollEnabled=(zooms[current]?:1f)<=1.01f,key={it}) {index -> content(index,false,reset,{chrome=!chrome},{chrome=false},{zooms[index]=it})}
                    else VerticalPager(pager,modifier,userScrollEnabled=(zooms[current]?:1f)<=1.01f,key={it}) {index -> content(index,false,reset,{chrome=!chrome},{chrome=false},{zooms[index]=it})}
                }
            }
        }
    }
    if(jump) AlertDialog(onDismissRequest={jump=false},title={Text(stringResource(R.string.reader_go_to))},text={OutlinedTextField(address,{address=it.filter(Char::isDigit).take(7)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.testTag("reader-page-input"))},
        confirmButton={TextButton(onClick={address.toIntOrNull()?.takeIf {it in 1..count}?.let {navigate(it-1);jump=false}},enabled=address.toIntOrNull()?.let {it in 1..count}==true,modifier=Modifier.testTag("reader-page-confirm")) {Text(stringResource(R.string.sheet_go))}},dismissButton={TextButton(onClick={jump=false}) {Text(stringResource(R.string.cancel))}})
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ZoomableReaderPage(ratio: Float,modifier: Modifier=Modifier,reset: Int=0,tag: String,onTap: ()->Unit,onInteraction: ()->Unit,onZoom: (Float)->Unit,content: @Composable (Float)->Unit) {
    var scale by remember(tag,reset) {mutableFloatStateOf(1f)}
    var offset by remember(tag,reset) {mutableStateOf(Offset.Zero)}
    var size by remember {mutableStateOf(IntSize.Zero)}
    val safeRatio=ratio.coerceIn(.001f,1000f)
    val w=minOf(size.width.toFloat(),size.height*safeRatio)
    val h=w/safeRatio
    fun transform(zoom: Float,pan: Offset=Offset.Zero) {
        val next=(scale*zoom).coerceIn(1f,5f)
        val limitX=((w*next-size.width)/2).coerceAtLeast(0f);val limitY=((h*next-size.height)/2).coerceAtLeast(0f)
        offset=Offset((offset.x+pan.x).coerceIn(-limitX,limitX),(offset.y+pan.y).coerceIn(-limitY,limitY))
        scale=next
    }
    val gestures=rememberTransformableState {zoom,pan,_ -> onInteraction();transform(zoom,pan)}
    LaunchedEffect(scale) {onZoom(scale)}
    val density=androidx.compose.ui.platform.LocalDensity.current
    Box(Modifier.then(modifier).clipToBounds().onSizeChanged {size=it}.testTag(tag)
        .semantics {stateDescription="zoom:${(scale*100).toInt()}"}
        .transformable(gestures,canPan={scale>1.01f})
        .pointerInput(tag,reset) {detectTapGestures(onTap={onTap()},onDoubleTap={onInteraction();transform(if(scale>1.01f) 1f/scale else 2f)})},contentAlignment=Alignment.Center) {
        Box(Modifier.size(with(density) {w.toDp()},with(density) {h.toDp()}).graphicsLayer {scaleX=scale;scaleY=scale;translationX=offset.x;translationY=offset.y}) {content(scale)}
    }
}
