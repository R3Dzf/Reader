package com.r3dzf.reader

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

enum class ReaderThemeMode { SYSTEM, LIGHT, DARK }
internal fun readerThemeMode(context: Context): ReaderThemeMode = runCatching {
    ReaderThemeMode.valueOf(context.getSharedPreferences("appearance",Context.MODE_PRIVATE).getString("theme","SYSTEM")!!)
}.getOrDefault(ReaderThemeMode.SYSTEM)
internal fun saveReaderTheme(context: Context,mode: ReaderThemeMode) {
    context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().putString("theme",mode.name).apply()
}
private val LightColors=lightColorScheme(
    primary=Color(0xFF216B55),onPrimary=Color.White,primaryContainer=Color(0xFFDDF2E5),onPrimaryContainer=Color(0xFF153C2E),
    secondary=Color(0xFF946444),secondaryContainer=Color(0xFFF4E4D4),onSecondaryContainer=Color(0xFF513B29),
    background=Color(0xFFF5F7F3),onBackground=Color(0xFF182E27),surface=Color(0xFFFFFFFF),onSurface=Color(0xFF182E27),
    surfaceTint=Color(0xFF216B55),surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xFFF0F5EE),surfaceContainer=Color(0xFFECF1E9),surfaceContainerHigh=Color(0xFFE8EEE5),surfaceContainerHighest=Color(0xFFE2EADD),
    surfaceVariant=Color(0xFFEAF0E9),onSurfaceVariant=Color(0xFF5B6E63),outline=Color(0xFF84988B),outlineVariant=Color(0xFFDEE6DC)
)
private val DarkColors=darkColorScheme(
    primary=Color(0xFF9ADBB7),onPrimary=Color(0xFF123E2C),primaryContainer=Color(0xFF2C4E3E),onPrimaryContainer=Color(0xFFD6F1DE),
    secondary=Color(0xFFE7BD9A),secondaryContainer=Color(0xFF594735),onSecondaryContainer=Color(0xFFF8E8D9),
    background=Color(0xFF18241F),onBackground=Color(0xFFE5EEE6),surface=Color(0xFF22322A),onSurface=Color(0xFFE5EEE6),
    surfaceTint=Color(0xFF9ADBB7),surfaceContainerLowest=Color(0xFF14201B),surfaceContainerLow=Color(0xFF1D2C24),surfaceContainer=Color(0xFF24362B),surfaceContainerHigh=Color(0xFF2A3D31),surfaceContainerHighest=Color(0xFF324738),
    surfaceVariant=Color(0xFF2F4036),onSurfaceVariant=Color(0xFFBDCDC0),outline=Color(0xFF889F8E),outlineVariant=Color(0xFF3A5041)
)

@Composable
fun ReaderTheme(themeOverride: ReaderThemeMode? = null,content: @Composable () -> Unit) {
    val context=LocalContext.current
    val preferences=remember(context) { context.getSharedPreferences("appearance",Context.MODE_PRIVATE) }
    var savedMode by remember(context) { mutableStateOf(readerThemeMode(context)) }
    DisposableEffect(preferences) {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key -> if(key=="theme") savedMode=readerThemeMode(context) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val dark=when(themeOverride ?: savedMode) { ReaderThemeMode.SYSTEM -> isSystemInDarkTheme();ReaderThemeMode.DARK -> true;ReaderThemeMode.LIGHT -> false }
    val colors=if(dark) DarkColors else LightColors
    val arabic=LocalConfiguration.current.locales[0]?.language=="ar"
    val family=remember(context,arabic) { FontFamily(DocumentFonts.typeface(context,if(arabic) "Noto Sans Arabic" else "Manrope",arabic=arabic)) }
    val base=Typography()
    val typography=remember(family) { base.copy(
        headlineLarge=base.headlineLarge.copy(fontFamily=family,letterSpacing=0.sp),
        headlineMedium=base.headlineMedium.copy(fontFamily=family,letterSpacing=0.sp),
        headlineSmall=base.headlineSmall.copy(fontFamily=family,letterSpacing=0.sp),
        titleLarge=base.titleLarge.copy(fontFamily=family,letterSpacing=0.sp),
        titleMedium=base.titleMedium.copy(fontFamily=family,letterSpacing=0.sp),
        titleSmall=base.titleSmall.copy(fontFamily=family,letterSpacing=0.sp),
        bodyLarge=base.bodyLarge.copy(fontFamily=family,letterSpacing=0.sp),
        bodyMedium=base.bodyMedium.copy(fontFamily=family,letterSpacing=0.sp),
        bodySmall=base.bodySmall.copy(fontFamily=family,letterSpacing=0.sp),
        labelLarge=base.labelLarge.copy(fontFamily=family,letterSpacing=0.sp),
        labelMedium=base.labelMedium.copy(fontFamily=family,letterSpacing=0.sp),
        labelSmall=base.labelSmall.copy(fontFamily=family,letterSpacing=0.sp)) }
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            window.statusBarColor=colors.background.toArgb();window.navigationBarColor=colors.background.toArgb()
            WindowCompat.getInsetsController(window,window.decorView).apply { isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark }
        }
    }
    MaterialTheme(colorScheme=colors,typography=typography,content=content)
}
