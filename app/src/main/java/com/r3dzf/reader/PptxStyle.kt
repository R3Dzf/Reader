package com.r3dzf.reader

import org.w3c.dom.Element
import kotlin.math.abs
import kotlin.math.roundToInt

enum class SlideParagraphAlignment { START, LEFT, CENTER, RIGHT, JUSTIFY }

data class NativeSlideTextRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikeThrough: Boolean = false,
    val fontSizePoints: Float? = null,
    val textRgb: Long? = null,
    val fontFamilyName: String? = null
)

data class NativeSlideTextParagraph(
    val runs: List<NativeSlideTextRun>,
    val alignment: SlideParagraphAlignment = SlideParagraphAlignment.START,
    val rightToLeft: Boolean = false,
    val spaceBeforePoints: Float = 0f,
    val spaceAfterPoints: Float = 0f,
    val defaultStyle: NativeSlideTextRun = NativeSlideTextRun(""),
    val bulletMarker: String? = null,
    val marginStartPoints: Float = 0f,
    val indentPoints: Float = 0f,
    val lineSpacingPoints: Float? = null,
    val lineSpacingMultiplier: Float? = null
) {
    val plainText: String get() = runs.joinToString("") { it.text }
}

internal class PptxTextBudget {
    private var paragraphs = 0
    private var runs = 0
    private var characters = 0L

    fun paragraph() {
        if (++paragraphs > 25_000) throw InvalidOfficeDocumentException("The presentation exceeds the text paragraph limit.")
    }

    fun run(text: String) {
        characters += text.length
        if (++runs > 100_000 || characters > 4_000_000L) {
            throw InvalidOfficeDocumentException("The presentation exceeds the text reading limit.")
        }
    }
}

internal data class PptxTheme(
    val colors: Map<String, Long> = emptyMap(),
    val majorFonts: Map<String, String> = emptyMap(),
    val minorFonts: Map<String, String> = emptyMap(),
    val fillStyles: List<Element> = emptyList(),
    val lineStyles: List<Element> = emptyList(),
    val backgroundFillStyles: List<Element> = emptyList(),
    val colorMap: Map<String, String> = mapOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2")
) {
    fun overlay(root: Element?): PptxTheme {
        val elements = root?.directChild("themeElements") ?: root ?: return this
        val scheme = elements.directChild("clrScheme")
        val parsedColors = scheme?.elementChildren()?.mapNotNull { entry ->
            val color = entry.elementChildren().firstOrNull()?.let(::directColor)
            color?.let { entry.localName() to it }
        }?.toMap().orEmpty()
        fun fonts(group: Element?): Map<String, String> = group?.elementChildren()?.mapNotNull { font ->
            val face = font.attributeByLocalName("typeface")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val key = if (font.localName() == "font") font.attributeByLocalName("script") else font.localName()
            key?.let { it to face }
        }?.toMap().orEmpty()
        val fontScheme = elements.directChild("fontScheme")
        return copy(
            colors = colors + parsedColors,
            majorFonts = majorFonts + fonts(fontScheme?.directChild("majorFont")),
            minorFonts = minorFonts + fonts(fontScheme?.directChild("minorFont")),
            lineStyles = elements.directChild("fmtScheme")?.directChild("lnStyleLst")?.elementChildren() ?: lineStyles,
            fillStyles = elements.directChild("fmtScheme")?.directChild("fillStyleLst")?.elementChildren() ?: fillStyles,
            backgroundFillStyles = elements.directChild("fmtScheme")?.directChild("bgFillStyleLst")?.elementChildren() ?: backgroundFillStyles
        )
    }

    fun mapColors(mapping: Element?): PptxTheme {
        if (mapping == null) return this
        val map = colorMap.toMutableMap()
        for (index in 0 until mapping.attributes.length) {
            val attribute = mapping.attributes.item(index)
            if (!attribute.nodeName.startsWith("xmlns")) map[attribute.localName ?: attribute.nodeName] = attribute.nodeValue
        }
        return copy(colorMap = map)
    }

    fun solidFill(parent: Element?): Long? = parent?.directChild("solidFill")?.elementChildren()?.firstOrNull()?.let { color(it) }

    fun color(element: Element, placeholder: Long? = null): Long? {
        val source = if (element.localName() == "schemeClr") {
            val name = element.attributeByLocalName("val") ?: return null
            if (name == "phClr") placeholder else colors[colorMap[name] ?: name]
        } else directColor(element)
        source ?: return null
        var rgb = doubleArrayOf(((source shr 16) and 255).toDouble() / 255, ((source shr 8) and 255).toDouble() / 255, (source and 255).toDouble() / 255)
        element.elementChildren().forEach { transform ->
            val raw = transform.attributeByLocalName("val") ?: return@forEach
            val ratio = if (raw.endsWith('%')) raw.dropLast(1).toDoubleOrNull()?.div(100) else raw.toDoubleOrNull()?.div(100_000)
            if (ratio == null || !ratio.isFinite()) return@forEach
            when (transform.localName()) {
                "tint" -> rgb = rgb.map { it * ratio.coerceIn(0.0, 1.0) + 1 - ratio.coerceIn(0.0, 1.0) }.toDoubleArray()
                "shade" -> rgb = rgb.map { it * ratio.coerceIn(0.0, 1.0) }.toDoubleArray()
                "lumMod", "lumOff" -> rgb = adjustLuminance(rgb, ratio, transform.localName() == "lumMod")
            }
        }
        val channels = rgb.map { (it.coerceIn(0.0, 1.0) * 255).roundToInt().toLong() }
        return (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
    }

    fun referencedFill(reference: Element?): SlideFill? {
        reference ?: return null
        val index = reference.attributeByLocalName("idx")?.toIntOrNull() ?: return null
        if (index == 0) return SlideFill(alpha = 0f)
        val list = if (index >= 1001) backgroundFillStyles else fillStyles
        val fill = list.getOrNull(if (index >= 1001) index - 1001 else index - 1) ?: return null
        return readFill(fill, reference.elementChildren().firstOrNull()?.let { color(it) })
    }

    fun typeface(token: String?, arabic: Boolean): String? {
        if (token == null || !token.startsWith('+')) return token
        val fonts = when { token.startsWith("+mj-") -> majorFonts; token.startsWith("+mn-") -> minorFonts; else -> return null }
        val family = when (token.substringAfterLast('-')) { "cs" -> "cs"; "ea" -> "ea"; else -> "latin" }
        return fonts[family] ?: (if (arabic && family == "cs") fonts["Arab"] else null) ?: fonts["latin"]
    }

    private fun directColor(element: Element): Long? {
        val value = when (element.localName()) {
            "srgbClr" -> element.attributeByLocalName("val")
            "sysClr" -> element.attributeByLocalName("lastClr")
            else -> null
        }
        return value?.takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toLongOrNull(16)
    }

    private fun adjustLuminance(rgb: DoubleArray, amount: Double, multiply: Boolean): DoubleArray {
        val max = rgb.maxOrNull()!!
        val min = rgb.minOrNull()!!
        val delta = max - min
        val lightness = (max + min) / 2
        val saturation = if (delta == 0.0) 0.0 else delta / (1 - abs(2 * lightness - 1))
        val hue = if (delta == 0.0) 0.0 else when (max) {
            rgb[0] -> ((rgb[1] - rgb[2]) / delta).let { (it + 6) % 6 }
            rgb[1] -> (rgb[2] - rgb[0]) / delta + 2
            else -> (rgb[0] - rgb[1]) / delta + 4
        }
        val nextLightness = (if (multiply) lightness * amount else lightness + amount).coerceIn(0.0, 1.0)
        val chroma = (1 - abs(2 * nextLightness - 1)) * saturation
        val x = chroma * (1 - abs(hue % 2 - 1))
        val base = when {
            hue < 1 -> doubleArrayOf(chroma, x, 0.0)
            hue < 2 -> doubleArrayOf(x, chroma, 0.0)
            hue < 3 -> doubleArrayOf(0.0, chroma, x)
            hue < 4 -> doubleArrayOf(0.0, x, chroma)
            hue < 5 -> doubleArrayOf(x, 0.0, chroma)
            else -> doubleArrayOf(chroma, 0.0, x)
        }
        return base.map { it + nextLightness - chroma / 2 }.toDoubleArray()
    }
}

internal data class PptxSlideStyleContext(
    val theme: PptxTheme = PptxTheme(),
    val presentationDefaults: Element? = null,
    val master: Element? = null,
    val layout: Element? = null,
    val masterPart: String? = null,
    val layoutPart: String? = null
) {
    fun placeholder(shape: Element, root: Element?, matchType: Boolean = false): Element? {
        val source = placeholderProperties(shape) ?: return null
        val candidates = root?.directChild("cSld")?.directChild("spTree")?.elementChildren().orEmpty()
        val type = source.attributeByLocalName("type") ?: "obj"
        val index = source.attributeByLocalName("idx") ?: "0"
        return candidates.firstOrNull { candidate ->
            val properties = placeholderProperties(candidate) ?: return@firstOrNull false
            if (matchType) {
                fun normalized(value: String) = if (value == "ctrTitle") "title" else value
                normalized(properties.attributeByLocalName("type") ?: "obj") == normalized(type)
            }
            else (properties.attributeByLocalName("idx") ?: "0") == index
        }
    }

    fun inheritedShapes(shape: Element): List<Element> {
        val layoutShape = placeholder(shape, layout)
        val masterShape = placeholder(layoutShape ?: shape, master, matchType = true)
        return listOfNotNull(shape, layoutShape, masterShape)
    }

    fun textDefaults(shape: Element): List<Element> {
        val chain = inheritedShapes(shape).reversed()
        val type = chain.mapNotNull { placeholderProperties(it)?.attributeByLocalName("type") }.lastOrNull()
        val group = when (type) { "title", "ctrTitle" -> "titleStyle"; "body" -> "bodyStyle"; else -> "otherStyle" }
        return listOfNotNull(presentationDefaults, master?.directChild("txStyles")?.directChild(group)) +
            chain.dropLast(1).flatMap { inherited -> listOfNotNull(
                inherited.directChild("txBody")?.directChild("lstStyle"),
                inherited.directChild("txBody")?.elementChildren()?.firstOrNull { it.localName() == "p" }?.directChild("pPr")
            ) }
    }

    private fun placeholderProperties(shape: Element): Element? =
        shape.elementChildren().firstOrNull { it.localName() in setOf("nvSpPr", "nvPicPr", "nvGraphicFramePr") }
            ?.directChild("nvPr")?.directChild("ph")
}

private data class PptxRunStyle(
    val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val strikeThrough: Boolean = false,
    val size: Float? = null, val color: Long? = null, val latin: String? = null, val complex: String? = null
) {
    fun overlay(properties: Element?, theme: PptxTheme): PptxRunStyle {
        if (properties == null) return this
        fun flag(name: String, default: Boolean) = when (properties.attributeByLocalName(name)) { "1", "true" -> true; "0", "false" -> false; else -> default }
        return copy(
            bold = flag("b", bold), italic = flag("i", italic),
            underline = properties.attributeByLocalName("u")?.let { it != "none" } ?: underline,
            strikeThrough = properties.attributeByLocalName("strike")?.let { it != "noStrike" } ?: strikeThrough,
            size = properties.attributeByLocalName("sz")?.toFloatOrNull()?.div(100)?.takeIf { it in 4f..288f } ?: size,
            color = theme.solidFill(properties) ?: color,
            latin = properties.directChild("latin")?.attributeByLocalName("typeface")?.takeIf { it.isNotBlank() } ?: latin,
            complex = properties.directChild("cs")?.attributeByLocalName("typeface")?.takeIf { it.isNotBlank() } ?: complex
        )
    }

    fun run(text: String, theme: PptxTheme, arabic: Boolean) = NativeSlideTextRun(
        text, bold, italic, underline, strikeThrough, size, color,
        theme.typeface(if (arabic) complex ?: latin else latin, arabic)
    )
}

private fun pptxHasArabic(text: String): Boolean = text.codePoints().anyMatch {
    Character.UnicodeScript.of(it) == Character.UnicodeScript.ARABIC
}

private fun pptxInferredRtl(runs: List<NativeSlideTextRun>): Boolean = runs.asSequence().mapNotNull { run ->
    val strong = run.text.codePoints().filter {
        Character.getDirectionality(it) in setOf(
            Character.DIRECTIONALITY_LEFT_TO_RIGHT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        )
    }.findFirst().orElse(-1)
    if (strong < 0) null else Character.getDirectionality(strong) != Character.DIRECTIONALITY_LEFT_TO_RIGHT
}.firstOrNull() ?: false

internal fun readPptxRichText(
    body: Element?, theme: PptxTheme, defaults: List<Element>, budget: PptxTextBudget
): List<NativeSlideTextParagraph> {
    if (body == null) return emptyList()
    var bodyRuns = 0
    var bodyCharacters = 0
    val paragraphElements = body.elementChildren().filter { it.localName() == "p" }
    if (paragraphElements.size > 2_000) throw InvalidOfficeDocumentException("A PowerPoint text body contains too many paragraphs.")
    val numbering = mutableMapOf<Int, Int>()
    return paragraphElements.map { paragraph ->
        budget.paragraph()
        val properties = paragraph.directChild("pPr")
        val level = properties?.attributeByLocalName("lvl")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
        val inherited = (defaults + listOfNotNull(body.directChild("lstStyle"))).mapNotNull { element ->
            if (element.localName() == "pPr") element else element.directChild("lvl${level + 1}pPr") ?: element.directChild("defPPr")
        } + listOfNotNull(properties)
        var style = PptxRunStyle()
        inherited.forEach { style = style.overlay(it.directChild("defRPr"), theme) }
        fun property(name: String): String? = inherited.asReversed().firstNotNullOfOrNull { it.attributeByLocalName(name) }
        fun spacing(name: String): Float = inherited.asReversed().firstNotNullOfOrNull {
            it.directChild(name)?.directChild("spcPts")?.attributeByLocalName("val")?.toFloatOrNull()?.div(100)
                ?.takeIf { value -> value.isFinite() && value >= 0f }?.coerceAtMost(1000f)
        } ?: 0f
        val runs = paragraph.elementChildren().mapNotNull { child ->
            val text = when (child.localName()) {
                "r", "fld" -> child.directChild("t")?.textContent.orEmpty()
                "br" -> "\n"
                else -> return@mapNotNull null
            }
            budget.run(text)
            bodyCharacters += text.length
            if (++bodyRuns > 10_000 || bodyCharacters > 100_000) {
                throw InvalidOfficeDocumentException("A PowerPoint text body exceeds the text reading limit.")
            }
            val arabic = pptxHasArabic(text)
            style.overlay(child.directChild("rPr"), theme).run(text, theme, arabic)
        }
        val hasArabic = runs.any { pptxHasArabic(it.text) }
        val bullet = inherited.asReversed().firstNotNullOfOrNull { element -> element.elementChildren().firstOrNull {
            it.localName() in setOf("buNone", "buChar", "buAutoNum", "buBlip")
        } }
        val marker = when (bullet?.localName()) {
            "buChar" -> bullet.attributeByLocalName("char")?.take(8)
            "buAutoNum" -> {
                numbering.keys.filter { it > level }.toList().forEach { numbering.remove(it) }
                val start = bullet.attributeByLocalName("startAt")?.toIntOrNull()?.coerceIn(1,32767)
                val number = if (start != null) start else (numbering[level] ?: 0) + 1
                numbering[level] = number
                pptxNumberMarker(number, bullet.attributeByLocalName("type") ?: "arabicPeriod")
            }
            else -> null
        }
        fun points(name: String) = property(name)?.toFloatOrNull()?.div(12700)?.takeIf { it.isFinite() }?.coerceIn(-1000f,1000f) ?: 0f
        val lineSpacing = inherited.asReversed().firstNotNullOfOrNull { it.directChild("lnSpc") }
        NativeSlideTextParagraph(
            runs,
            when (property("algn")) { "l" -> SlideParagraphAlignment.LEFT; "r" -> SlideParagraphAlignment.RIGHT; "ctr", "center" -> SlideParagraphAlignment.CENTER; "just", "dist", "justLow" -> SlideParagraphAlignment.JUSTIFY; else -> SlideParagraphAlignment.START },
            when (property("rtl")) { "1", "true" -> true; "0", "false" -> false; else -> pptxInferredRtl(runs) },
            spacing("spcBef"), spacing("spcAft"),
            style.overlay(paragraph.directChild("endParaRPr"), theme).run("", theme, hasArabic),
            marker, points("marL").coerceAtLeast(0f), points("indent"),
            lineSpacing?.directChild("spcPts")?.attributeByLocalName("val")?.toFloatOrNull()?.div(100)?.takeIf { it.isFinite() && it in 0.1f..1000f },
            lineSpacing?.directChild("spcPct")?.attributeByLocalName("val")?.toFloatOrNull()?.div(100000)?.takeIf { it.isFinite() && it in 0.1f..10f }
        )
    }
}

internal fun pptxNumberMarker(number: Int, type: String): String {
    fun alphabet(value: Int): String {
        var remaining = value.coerceAtLeast(1)
        var text = ""
        while (remaining > 0) { remaining--; text = ('a' + remaining % 26) + text; remaining /= 26 }
        return text
    }
    fun roman(value: Int): String {
        if (value !in 1..3999) return value.toString()
        var remaining = value
        return buildString { listOf(1000 to "m",900 to "cm",500 to "d",400 to "cd",100 to "c",90 to "xc",50 to "l",40 to "xl",10 to "x",9 to "ix",5 to "v",4 to "iv",1 to "i").forEach { (n,s) ->
            while (remaining >= n) { append(s); remaining -= n }
        } }
    }
    var text = when { type.startsWith("alpha") -> alphabet(number); type.startsWith("roman") -> roman(number); else -> number.toString() }
    if (type.contains("Uc")) text = text.uppercase(java.util.Locale.ROOT)
    return when { type.endsWith("ParenBoth") -> "($text)"; type.endsWith("ParenR") -> "$text)"; type.endsWith("Plain") -> text; else -> "$text." }
}
