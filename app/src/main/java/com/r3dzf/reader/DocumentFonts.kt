package com.r3dzf.reader

import android.content.Context
import android.graphics.Typeface
import android.util.LruCache
import org.json.JSONObject
import java.util.Locale

/** All files are APK assets. No provider, network request or installed Office app is used. */
internal object DocumentFonts {
    data class Face(val asset: String, val italic: Boolean, val variableWeight: Boolean, val weight: Int)
    data class Family(val name: String, val arabic: Boolean, val faces: List<Face>)
    @Volatile private var catalog: Map<String, Family>? = null
    private val cache = LruCache<String, Typeface>(48)
    private fun key(name: String) = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    // These are documented substitutes, never claimed to be the proprietary original font.
    private val aliases = mapOf(
        "arial" to "Arimo", "arialmt" to "Arimo", "helvetica" to "Arimo",
        "calibri" to "Carlito", "calibrilight" to "Carlito", "aptos" to "Carlito",
        "timesnewroman" to "Gelasio", "timesnewromanpsmt" to "Gelasio",
        "cambria" to "Caladea", "couriernew" to "Cousine", "consolas" to "Cousine",
        "segoeui" to "Open Sans", "tahoma" to "Noto Sans", "verdana" to "Noto Sans",
        "traditionalarabic" to "Amiri", "simplifiedarabic" to "Noto Naskh Arabic",
        "arabictransparent" to "Noto Naskh Arabic", "andalus" to "Aref Ruqaa",
        "sakkalmajalla" to "Noto Naskh Arabic", "arabictypesetting" to "Amiri"
    )
    @Synchronized fun families(context: Context): List<Family> {
        catalog?.let { return it.values.toList() }
        val json = context.assets.open("fonts/catalog.json").bufferedReader().use { JSONObject(it.readText()) }
        val entries = json.getJSONArray("families")
        val result = linkedMapOf<String, Family>()
        for (i in 0 until entries.length()) {
            val item = entries.getJSONObject(i)
            val files = item.getJSONArray("files")
            val faces = (0 until files.length()).map { files.getJSONObject(it) }
                .filter { it.getString("style") != "license" }.map {
                    Face(it.getString("asset"), it.getString("style") == "italic", it.optBoolean("variableWeight"), it.optInt("weight", 400))
                }
            val family = Family(item.getString("name"), item.getString("script") == "arabic", faces)
            result[key(family.name)] = family
        }
        check(result.size == 100) { "The offline font catalog is incomplete" }
        catalog = result
        return result.values.toList()
    }
    fun resolve(context: Context, requested: String?, arabic: Boolean): Family {
        families(context)
        val all = catalog!!
        val normalized = key(requested.orEmpty())
        val family = all[normalized] ?: aliases[normalized]?.let { all[key(it)] }
        // A Latin font without Arabic coverage must not turn Arabic into missing-glyph boxes.
        return family?.takeIf { !arabic || it.arabic }
            ?: all.getValue(key(if (arabic) "Noto Naskh Arabic" else "Carlito"))
    }
    @Synchronized fun typeface(context: Context, requested: String?, bold: Boolean = false, italic: Boolean = false, arabic: Boolean = false): Typeface {
        val family = resolve(context, requested, arabic)
        val weight = if (bold) 700 else 400
        val candidates = family.faces.filter { it.italic == italic }.ifEmpty { family.faces.filter { !it.italic } }
        val face = candidates.minBy { if (it.variableWeight) 0 else kotlin.math.abs(it.weight - weight) }
        val cacheKey = "${face.asset}:$weight:$italic"
        cache.get(cacheKey)?.let { return it }
        val builder = Typeface.Builder(context.assets, face.asset).setWeight(if (face.variableWeight) weight else face.weight).setItalic(face.italic)
        if (face.variableWeight) builder.setFontVariationSettings("'wght' $weight")
        val loaded = checkNotNull(builder.build()) { "Cannot load bundled font: ${face.asset}" }
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val result = Typeface.create(loaded, style)
        cache.put(cacheKey, result)
        return result
    }
}
