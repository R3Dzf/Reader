package com.r3dzf.reader

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private enum class LibraryTab { HOME, HISTORY, FAVORITES }
@Composable
internal fun ReaderIcon(@DrawableRes resource: Int,description: String? = null,modifier: Modifier = Modifier,tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(painterResource(resource),description,modifier.size(22.dp),tint=tint)
}

@Composable
fun ReaderHome(documents: List<LibraryDocument>,busy: Boolean = false,error: String? = null,
    onOpen: () -> Unit,onOpenDocument: (LibraryDocument) -> Unit,onFavorite: (LibraryDocument) -> Unit,
    onRemove: (LibraryDocument) -> Unit,onClear: () -> Unit,onErrorDismissed: () -> Unit = {}) {
    var tab by rememberSaveable { mutableStateOf(LibraryTab.HOME) }
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<DocumentKind?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var remove by remember { mutableStateOf<LibraryDocument?>(null) }
    var clear by rememberSaveable { mutableStateOf(false) }
    val context=LocalContext.current
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    val snackbar=remember { SnackbarHostState() }
    LaunchedEffect(error) { if(error!=null) { snackbar.showSnackbar(error);onErrorDismissed() } }
    val filtered=remember(documents,query,kind,tab) { selectDocuments(documents,query,kind,tab==LibraryTab.FAVORITES) }
    val featured=documents.firstOrNull { it.available }
    val showHero=tab==LibraryTab.HOME && query.isBlank() && kind==null
    val shown=if(showHero) filtered.filterNot { it.id==featured?.id }.take(5) else filtered
    Scaffold(
        containerColor=MaterialTheme.colorScheme.background,
        topBar={ Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=22.dp,vertical=10.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF174D40)),contentAlignment=Alignment.Center) {
                Image(painterResource(R.drawable.ic_rafolio_mark),null,Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f)) {
                val wordmark=remember { FontFamily(DocumentFonts.typeface(context,"Manrope",bold=true)) }
                Text("Rafolio",fontFamily=wordmark,fontSize=25.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onBackground,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(stringResource(R.string.brand_tagline),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            if(documents.isNotEmpty() && tab==LibraryTab.HOME) IconButton(onClick={if(!busy) onOpen()},enabled=!busy,modifier=Modifier.testTag("open-file")) {
                if(busy) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp) else ReaderIcon(R.drawable.ic_add,stringResource(R.string.open_file),tint=MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick={showSettings=true},modifier=Modifier.testTag("home-settings")) { ReaderIcon(R.drawable.ic_settings,stringResource(R.string.settings)) }
        } },
        bottomBar={ NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
            listOf(Triple(LibraryTab.HOME,R.drawable.ic_home,R.string.home),Triple(LibraryTab.HISTORY,R.drawable.ic_history,R.string.history),Triple(LibraryTab.FAVORITES,R.drawable.ic_star,R.string.favorites)).forEach { (item,icon,label) ->
                NavigationBarItem(selected=tab==item,onClick={keyboard?.hide();focus.clearFocus();tab=item;query="";kind=null},icon={ReaderIcon(icon,tint=if(tab==item) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)},
                    label={Text(stringResource(label),style=MaterialTheme.typography.labelMedium)},modifier=Modifier.testTag("nav-${item.name.lowercase(Locale.ROOT)}"))
            }
        } },
        floatingActionButton={ if(documents.isNotEmpty() && tab!=LibraryTab.HOME) ExtendedFloatingActionButton(onClick={if(!busy) onOpen()},
            containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary,
            icon={if(busy) CircularProgressIndicator(Modifier.size(20.dp),color=MaterialTheme.colorScheme.onPrimary,strokeWidth=2.dp) else ReaderIcon(R.drawable.ic_add,tint=MaterialTheme.colorScheme.onPrimary)},
            text={Text(stringResource(if(busy) R.string.importing else R.string.open_file))},modifier=Modifier.testTag("open-file")) },
        snackbarHost={SnackbarHost(snackbar)}
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("library-list"),contentPadding=PaddingValues(start=22.dp,end=22.dp,top=14.dp,bottom=100.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            if(documents.isNotEmpty() || tab!=LibraryTab.HOME) item {
                Text(stringResource(when(tab) { LibraryTab.HOME -> R.string.your_library;LibraryTab.HISTORY -> R.string.reading_history;LibraryTab.FAVORITES -> R.string.your_favorites }),
                    style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                Text(pluralStringResource(R.plurals.library_file_count,documents.size,documents.size),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
            }
            if(documents.isNotEmpty()) item {
                OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().testTag("library-search"),singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={keyboard?.hide();focus.clearFocus()}),
                    placeholder={Text(stringResource(R.string.search_files),style=MaterialTheme.typography.bodyMedium)},
                    leadingIcon={ReaderIcon(R.drawable.ic_search)},trailingIcon={if(query.isNotEmpty()) IconButton(onClick={query=""}) { ReaderIcon(R.drawable.ic_close,stringResource(R.string.clear_search)) }},
                    shape=RoundedCornerShape(18.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=MaterialTheme.colorScheme.outlineVariant,
                        unfocusedContainerColor=MaterialTheme.colorScheme.surface,focusedContainerColor=MaterialTheme.colorScheme.surface))
            }
            if(showHero) item {
                if(featured==null) WelcomeCard(busy,onOpen) else ContinueCard(featured,{onOpenDocument(featured)})
            }
            if(documents.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("file-filters"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=kind==null,onClick={kind=null},label={Text(stringResource(R.string.all_files))},modifier=Modifier.testTag("filter-all"))
                    DocumentKind.entries.forEach { option -> FilterChip(selected=kind==option,onClick={kind=option},
                        label={Text(stringResource(kindLabel(option)))},modifier=Modifier.testTag("filter-${option.name.lowercase(Locale.ROOT)}")) }
                }
            }
            if(shown.isNotEmpty()) {
                item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(if(tab==LibraryTab.FAVORITES) R.string.favorites else if(showHero) R.string.recent_files else R.string.all_files),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    if(showHero && filtered.size>1) TextButton(onClick={tab=LibraryTab.HISTORY}) { Text(stringResource(R.string.see_all)) }
                } }
                var previousGroup: Int?=null
                shown.forEach { document ->
                    val group=dateGroup(document.openedAt)
                    if(tab==LibraryTab.HISTORY && previousGroup!=group) item(key="group-$group-${document.id}") {
                        Text(stringResource(group),Modifier.padding(top=4.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    previousGroup=group
                    item(key=document.id) { DocumentRow(document,busy,{onOpenDocument(document)},{onFavorite(document)},{remove=document}) }
                }
            } else if(showHero && documents.isEmpty()) item {
                Text(stringResource(R.string.history_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=8.dp,vertical=8.dp))
            } else if(!(showHero && featured!=null)) item {
                EmptyLibrary(tab,query.isNotBlank() || kind!=null,documents.isEmpty(),onOpen) { query="";kind=null }
            }
        }
    }
    if(showSettings) AppearanceDialog(documents,onDismiss={showSettings=false},onClear={showSettings=false;clear=true})
    remove?.let { document -> AlertDialog(onDismissRequest={remove=null},title={Text(stringResource(R.string.remove_file))},
        text={Text(stringResource(R.string.remove_file_message,document.displayName))},
        confirmButton={TextButton(onClick={remove=null;onRemove(document)},modifier=Modifier.testTag("confirm-remove")) { Text(stringResource(R.string.remove),color=MaterialTheme.colorScheme.error) }},
        dismissButton={TextButton(onClick={remove=null}) { Text(stringResource(R.string.cancel)) }}) }
    if(clear) AlertDialog(onDismissRequest={clear=false},title={Text(stringResource(R.string.clear_library))},text={Text(stringResource(R.string.clear_library_message))},
        confirmButton={TextButton(onClick={clear=false;query="";kind=null;onClear()},modifier=Modifier.testTag("confirm-clear")) { Text(stringResource(R.string.clear_library),color=MaterialTheme.colorScheme.error) }},
        dismissButton={TextButton(onClick={clear=false}) { Text(stringResource(R.string.cancel)) }})
}

private fun kindLabel(kind: DocumentKind): Int = when(kind) { DocumentKind.PDF -> R.string.kind_pdf;DocumentKind.WORD -> R.string.kind_word;DocumentKind.SHEET -> R.string.kind_sheet;DocumentKind.SLIDES -> R.string.kind_slides }
private fun kindColor(kind: DocumentKind)=when(kind) { DocumentKind.PDF -> Color(0xFFB5564D);DocumentKind.WORD -> Color(0xFF4779B8);DocumentKind.SHEET -> Color(0xFF398467);DocumentKind.SLIDES -> Color(0xFFC28544) }
private fun dateGroup(time: Long): Int {
    val date=Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
    return when(date) { LocalDate.now() -> R.string.today;LocalDate.now().minusDays(1) -> R.string.yesterday;else -> R.string.earlier }
}
@Composable
private fun FileBadge(document: LibraryDocument,large: Boolean=false,inDarkSurface: Boolean=false) {
    val dark=inDarkSurface || MaterialTheme.colorScheme.background.luminance()<.2f
    val color=if(!dark) kindColor(document.kind) else when(document.kind) {
        DocumentKind.PDF -> Color(0xFFF0A79D);DocumentKind.WORD -> Color(0xFF9CC7F4)
        DocumentKind.SHEET -> Color(0xFF99D8B5);DocumentKind.SLIDES -> Color(0xFFEBC38C)
    }
    Column(Modifier.size(if(large) 68.dp else 52.dp).clip(RoundedCornerShape(if(large) 18.dp else 14.dp)).background(color.copy(alpha=.12f)),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        ReaderIcon(R.drawable.ic_file,tint=color)
        Text(document.extension.uppercase(Locale.ROOT),fontSize=9.sp,fontWeight=FontWeight.Bold,color=color,letterSpacing=.5.sp)
    }
}
@Composable
private fun progressText(document: LibraryDocument): String = if(!document.available) stringResource(R.string.file_missing)
    else if(document.progress.total<=0) stringResource(R.string.ready_to_read)
    else stringResource(when(document.kind) { DocumentKind.PDF -> R.string.last_page;DocumentKind.SLIDES -> R.string.last_slide;DocumentKind.SHEET -> R.string.last_sheet;DocumentKind.WORD -> R.string.last_section },document.progress.index+1,document.progress.total)

@Composable
private fun WelcomeCard(busy: Boolean,onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF174D40),Color(0xFF103B33)))).padding(22.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.welcome_eyebrow),color=Color(0xFFB3D9C3),style=MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.welcome_title),color=Color(0xFFF2F8EF),fontSize=27.sp,lineHeight=38.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=8.dp))
                Text("PDF · Word · Excel\nPowerPoint",color=Color(0xFFB3D9C3),fontSize=11.sp,lineHeight=19.sp,modifier=Modifier.padding(top=9.dp))
            }
            PaperIllustration()
        }
        Button(onClick=onOpen,enabled=!busy,modifier=Modifier.fillMaxWidth().padding(top=20.dp).testTag("welcome-open"),shape=RoundedCornerShape(14.dp),
            colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFD5EFCD),contentColor=Color(0xFF163D2B)),contentPadding=PaddingValues(vertical=13.dp)) {
            if(busy) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else ReaderIcon(R.drawable.ic_add,tint=Color(0xFF163D2B))
            Spacer(Modifier.width(8.dp));Text(stringResource(if(busy) R.string.importing else R.string.open_first_file),fontWeight=FontWeight.Bold)
        }
    }
}
@Composable
private fun PaperIllustration() {
    Box(Modifier.width(94.dp).height(125.dp),contentAlignment=Alignment.Center) {
        Box(Modifier.size(76.dp,100.dp).graphicsLayer { rotationZ=13f;translationX=8.dp.toPx() }.clip(RoundedCornerShape(12.dp)).background(Color(0xFF7AB998)))
        Column(Modifier.size(76.dp,100.dp).graphicsLayer { rotationZ=-8f }.clip(RoundedCornerShape(12.dp)).background(Color(0xFFF2F6E8)).padding(13.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(22.dp,25.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFD4E9D1)))
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFFA0B7A5),RoundedCornerShape(2.dp)))
            Box(Modifier.fillMaxWidth(.7f).height(4.dp).background(Color(0xFFD1DCCB),RoundedCornerShape(2.dp)))
            Box(Modifier.fillMaxWidth(.85f).height(4.dp).background(Color(0xFFD1DCCB),RoundedCornerShape(2.dp)))
        }
        Box(Modifier.align(Alignment.BottomEnd).size(34.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFE4B388)),contentAlignment=Alignment.Center) { ReaderIcon(R.drawable.ic_check,tint=Color(0xFF4D442E)) }
    }
}
@Composable
private fun ContinueCard(document: LibraryDocument,onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(Color(0xFF174D40),Color(0xFF103B33)))).padding(20.dp).testTag("continue-reading")) {
        Text(stringResource(R.string.continue_reading),color=Color(0xFFB8DBC5),style=MaterialTheme.typography.labelMedium)
        Row(Modifier.padding(top=16.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
            FileBadge(document,true,true)
            Column(Modifier.weight(1f)) {
                Text(document.displayName,color=Color.White,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(progressText(document),color=Color(0xFFB8DBC5),style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=6.dp))
            }
        }
        if(document.progress.total>1) LinearProgressIndicator(progress={document.progress.fraction},modifier=Modifier.fillMaxWidth().padding(top=17.dp).height(3.dp),color=Color(0xFFD5EFCD),trackColor=Color(0xFF3B6B54))
        Button(onClick=onOpen,modifier=Modifier.fillMaxWidth().padding(top=16.dp).testTag("resume-file"),shape=RoundedCornerShape(13.dp),
            colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFD5EFCD),contentColor=Color(0xFF173F2E))) {
            Text(stringResource(R.string.resume_reading),fontWeight=FontWeight.Bold);Spacer(Modifier.width(8.dp));ReaderIcon(R.drawable.ic_arrow,tint=Color(0xFF173F2E))
        }
    }
}
@Composable
private fun DocumentRow(document: LibraryDocument,busy: Boolean,onOpen: () -> Unit,onFavorite: () -> Unit,onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val context=LocalContext.current
    Card(onClick=onOpen,enabled=!busy,shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),modifier=Modifier.fillMaxWidth().testTag("document-${document.id}")) {
        Row(Modifier.fillMaxWidth().padding(start=14.dp,end=4.dp,top=14.dp,bottom=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            FileBadge(document)
            Column(Modifier.weight(1f)) {
                Text(document.displayName,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text("${document.extension.uppercase(Locale.ROOT)}  ·  ${Formatter.formatShortFileSize(context,document.bytes)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
                Text(progressText(document),style=MaterialTheme.typography.labelSmall,color=if(document.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=3.dp))
            }
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                Box {
                    IconButton(onClick={menu=true},modifier=Modifier.testTag("document-menu-${document.id}")) { ReaderIcon(R.drawable.ic_more,stringResource(R.string.file_actions)) }
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                        DropdownMenuItem(text={Text(stringResource(if(document.favorite) R.string.unfavorite else R.string.add_favorite))},onClick={menu=false;onFavorite()},leadingIcon={ReaderIcon(R.drawable.ic_star)})
                        DropdownMenuItem(text={Text(stringResource(R.string.remove_file))},onClick={menu=false;onRemove()},leadingIcon={ReaderIcon(R.drawable.ic_trash)})
                    }
                }
                if(document.favorite) ReaderIcon(R.drawable.ic_star,stringResource(R.string.favorite_file),Modifier.size(14.dp),tint=MaterialTheme.colorScheme.secondary)
            }
        }
    }
}
@Composable
private fun EmptyLibrary(tab: LibraryTab,filtered: Boolean,empty: Boolean,onOpen: () -> Unit,onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical=26.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(66.dp).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(22.dp)),contentAlignment=Alignment.Center) {
            ReaderIcon(if(filtered) R.drawable.ic_search else if(tab==LibraryTab.FAVORITES) R.drawable.ic_star else R.drawable.ic_folder,tint=MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(if(filtered) R.string.no_results else if(tab==LibraryTab.FAVORITES) R.string.no_favorites else R.string.no_history),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        Text(stringResource(if(filtered) R.string.try_search else if(tab==LibraryTab.FAVORITES) R.string.favorites_hint else R.string.history_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(filtered) TextButton(onClick=onReset) { Text(stringResource(R.string.reset_filters)) }
        else if(empty && tab!=LibraryTab.HOME) TextButton(onClick=onOpen) { Text(stringResource(R.string.open_file)) }
    }
}
@Composable
private fun AppearanceDialog(documents: List<LibraryDocument>,onDismiss: () -> Unit,onClear: () -> Unit) {
    val context=LocalContext.current
    var mode by remember { mutableStateOf(readerThemeMode(context)) }
    AlertDialog(onDismissRequest=onDismiss,modifier=Modifier.testTag("appearance-dialog"),title={Text(stringResource(R.string.settings),fontWeight=FontWeight.Bold)},
        text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.appearance),style=MaterialTheme.typography.titleSmall)
            ReaderThemeMode.entries.forEach { option -> Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(12.dp)).clickable(role=Role.RadioButton) { mode=option;saveReaderTheme(context,option) }.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected=option==mode,onClick=null)
                Spacer(Modifier.width(12.dp));Text(stringResource(when(option) { ReaderThemeMode.SYSTEM -> R.string.theme_system;ReaderThemeMode.LIGHT -> R.string.theme_light;ReaderThemeMode.DARK -> R.string.theme_dark }))
            } }
            HorizontalDivider(Modifier.padding(vertical=12.dp))
            Text(stringResource(R.string.local_library),style=MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.library_storage,Formatter.formatShortFileSize(context,documents.sumOf { it.bytes })),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.library_private_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick=onClear,enabled=documents.isNotEmpty(),modifier=Modifier.testTag("clear-library")) { ReaderIcon(R.drawable.ic_trash,tint=MaterialTheme.colorScheme.error);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.clear_library),color=MaterialTheme.colorScheme.error) }
            Text("Rafolio · 0.8.0",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        } },confirmButton={TextButton(onClick=onDismiss) { Text(stringResource(R.string.done)) }})
}

@Composable
internal fun DocumentTopBar(title: String,subtitle: String,onClose: () -> Unit) {
    Surface(color=MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start=6.dp,end=18.dp,top=5.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            IconButton(onClick=onClose,modifier=Modifier.testTag("viewer-back")) { ReaderIcon(R.drawable.ic_back,stringResource(R.string.close),tint=MaterialTheme.colorScheme.primary) }
            Column(Modifier.weight(1f)) {
                Text(title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(subtitle,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
