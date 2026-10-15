package com.r3dzf.reader

import org.w3c.dom.Element
import kotlin.math.abs

enum class SlideChartKind { COLUMN, BAR, LINE, AREA, PIE, DOUGHNUT, SCATTER, UNSUPPORTED }
enum class SlideChartGrouping { STANDARD, STACKED, PERCENT }
enum class SlideChartNotice { UNSUPPORTED_TYPE, UNSUPPORTED_AXIS, MISSING_VALUES, PARTIAL_VALUES, UNSUPPORTED_DATA }
enum class SlideChartBlanks { GAP, ZERO, SPAN }

data class SlideChartAxis(val minimum: Double? = null, val maximum: Double? = null, val majorUnit: Double? = null,
    val reversed: Boolean = false, val format: String = "General", val title: String = "", val hidden: Boolean = false)
data class SlideChartSeries(val name: String, val values: List<Double?>, val colorRgb: Long,
    val xValues: List<Double?> = emptyList(), val pointColors: Map<Int, Long> = emptyMap(),
    val format: String = "General", val lineVisible: Boolean = true, val markerVisible: Boolean = true, val rawValues: List<String?> = emptyList())
data class NativeSlideChart(val kind: SlideChartKind, val title: String, val categories: List<String>,
    val series: List<SlideChartSeries>, val grouping: SlideChartGrouping = SlideChartGrouping.STANDARD,
    val valueAxis: SlideChartAxis = SlideChartAxis(), val categoryAxis: SlideChartAxis = SlideChartAxis(),
    val legendPosition: String? = "b", val backgroundRgb: Long? = null, val plotRgb: Long? = null,
    val gapWidth: Int = 150, val firstSliceAngle: Int = 0, val holeSize: Int = 50,
    val showValues: Boolean = false, val showPercent: Boolean = false, val showCategory: Boolean = false,
    val blanks: SlideChartBlanks = SlideChartBlanks.GAP, val date1904: Boolean = false,
    val labelStyle: NativeSlideTextRun = NativeSlideTextRun("",fontSizePoints=10f),
    val notices: Set<SlideChartNotice> = emptySet()) {
    val drawable: Boolean get() = kind != SlideChartKind.UNSUPPORTED && notices.none {
        it in setOf(SlideChartNotice.UNSUPPORTED_TYPE,SlideChartNotice.UNSUPPORTED_AXIS,SlideChartNotice.MISSING_VALUES,SlideChartNotice.UNSUPPORTED_DATA)
    }
}

internal class PptxChartBudget {
    private var points = 0
    private var characters = 0
    private var charts = 0
    fun chart() { if (++charts > 256) invalidChart("Too many chart parts") }
    fun points(count: Int) { points += count; if (points > 50_000) invalidChart("Chart values exceed the presentation reading limit") }
    fun text(value: String): String {
        characters += value.length
        if (value.length > 4096 || characters > 200_000) invalidChart("Chart text exceeds the reading limit")
        return value
    }
}
private fun invalidChart(message: String): Nothing = throw InvalidOfficeDocumentException(message + ".")
private fun Element?.chartValue(name: String): String? = this?.directChild(name)?.attributeByLocalName("val")
private fun Element?.chartFlag(name: String, default: Boolean = false) = when(this?.chartValue(name)) {
    "1","true" -> true; "0","false" -> false; else -> default
}

/** Only saved chart caches/literals are read. Workbook formulas and external relationships are not evaluated. */
internal fun readPptxChart(root: Element, theme: PptxTheme, budget: PptxChartBudget): NativeSlideChart {
    if (root.localName() != "chartSpace" || root.namespaceURI != "http://schemas.openxmlformats.org/drawingml/2006/chart") invalidChart("Invalid chart root")
    budget.chart()
    val chart = root.directChild("chart") ?: invalidChart("Missing chart definition")
    val plot = chart.directChild("plotArea") ?: invalidChart("Missing chart plot area")
    val plots = plot.elementChildren().filter { it.localName().endsWith("Chart") }
    if (plots.size > 16) invalidChart("Too many combined chart types")
    val type = plots.firstOrNull()
    val kind = if (plots.size != 1) SlideChartKind.UNSUPPORTED else when(type?.localName()) {
        "barChart" -> if(type.chartValue("barDir") == "bar") SlideChartKind.BAR else SlideChartKind.COLUMN
        "lineChart" -> SlideChartKind.LINE; "areaChart" -> SlideChartKind.AREA
        "pieChart" -> SlideChartKind.PIE; "doughnutChart" -> SlideChartKind.DOUGHNUT
        "scatterChart" -> SlideChartKind.SCATTER; else -> SlideChartKind.UNSUPPORTED
    }
    val notices = linkedSetOf<SlideChartNotice>()
    if (kind == SlideChartKind.UNSUPPORTED) notices += SlideChartNotice.UNSUPPORTED_TYPE
    fun text(element: Element?): String {
        element ?: return ""
        val texts = element.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/main","t")
        if (texts.length > 2048) invalidChart("Too many chart label runs")
        val rich = (0 until texts.length).joinToString("") { texts.item(it).textContent }
        val value = if(rich.isNotEmpty()) rich else element.directChild("tx")?.directChild("strRef")?.directChild("strCache")
            ?.elementChildren()?.firstOrNull { it.localName() == "pt" }?.directChild("v")?.textContent.orEmpty()
        return budget.text(value)
    }
    data class Cache(val values: List<String?>, val format: String = "General", val available: Boolean = false)
    fun cache(parent: Element?): Cache {
        val node = parent?.directChild("numRef")?.directChild("numCache") ?: parent?.directChild("strRef")?.directChild("strCache")
            ?: parent?.directChild("numLit") ?: parent?.directChild("strLit") ?: return Cache(emptyList())
        val entries = node.elementChildren().filter { it.localName() == "pt" }
        if(entries.size > 512) invalidChart("Too many points in a chart series")
        val indexed = linkedMapOf<Int,String?>()
        entries.forEach { point ->
            val index = point.attributeByLocalName("idx")?.toIntOrNull()?.takeIf { it in 0..511 } ?: invalidChart("Invalid chart point index")
            if(indexed.containsKey(index)) invalidChart("Duplicate chart point index")
            indexed[index] = point.directChild("v")?.textContent?.let { budget.text(it) }
        }
        val declared = node.chartValue("ptCount")?.toIntOrNull()
        if(node.directChild("ptCount") != null && (declared == null || declared !in 0..512)) invalidChart("Invalid chart point count")
        val actual = (indexed.keys.maxOrNull() ?: -1) + 1
        if(declared != null && declared < actual) invalidChart("Chart point index exceeds its declared count")
        val count = declared ?: actual
        budget.points(count)
        return Cache(List(count) { indexed[it] },budget.text(node.directChild("formatCode")?.textContent ?: "General"),true)
    }
    fun number(value: String?): Double? {
        if(value.isNullOrBlank() || value.startsWith('#')) return null
        val n = value.toDoubleOrNull() ?: return null
        if(!n.isFinite() || abs(n) > 1e100) invalidChart("Invalid chart numeric value")
        return n
    }
    val date1904 = root.chartFlag("date1904")
    val formatter = XlsxNumberFormatter(date1904)
    fun categories(source: Element?): List<String> {
        val level = source?.directChild("multiLvlStrRef")?.directChild("multiLvlStrCache")
        if(level != null) {
            val levels = level.elementChildren().filter { it.localName() == "lvl" }
            if(levels.size > 8) invalidChart("Too many category levels")
            val parsed = levels.map { l ->
                val wrapped = root.ownerDocument.createElement("strLit")
                l.elementChildren().forEach { wrapped.appendChild(it.cloneNode(true)) }
                val parent = root.ownerDocument.createElement("cat");parent.appendChild(wrapped)
                cache(parent).values
            }
            return List(parsed.maxOfOrNull { it.size } ?: 0) { i -> parsed.mapNotNull { it.getOrNull(i) }.joinToString(" / ") }
        }
        val data = cache(source)
        val numeric = source?.directChild("numRef") != null || source?.directChild("numLit") != null
        return data.values.map { raw -> if(raw == null) "" else if(numeric) formatter.format(raw,data.format).text else raw }
    }
    var categoryLabels = emptyList<String>()
    var cachedPoints = 0
    val seriesNodes = plots.flatMap { it.elementChildren().filter { e -> e.localName() == "ser" } }
        .withIndex().sortedBy { it.value.chartValue("order")?.toIntOrNull() ?: it.index }.map { it.value }
    if(seriesNodes.size > 32) invalidChart("Too many chart series")
    val palette = listOf(0x4472C4L,0xED7D31L,0xA5A5A5L,0xFFC000L,0x5B9BD5L,0x70AD47L)
    val series = seriesNodes.mapIndexed { index, node ->
        val category = categories(node.directChild("cat"))
        if(categoryLabels.isEmpty()) categoryLabels = category
        else if(category.isNotEmpty() && category != categoryLabels) notices += SlideChartNotice.UNSUPPORTED_DATA
        val values = cache(node.directChild(if(kind == SlideChartKind.SCATTER) "yVal" else "val"))
        val x = if(kind == SlideChartKind.SCATTER) cache(node.directChild("xVal")) else Cache(emptyList())
        cachedPoints += values.values.size + x.values.size
        if(cachedPoints > 8192) invalidChart("Too many values in one chart")
        val name = budget.text(node.directChild("tx")?.directChild("v")?.textContent
            ?: cache(node.directChild("tx")).values.firstOrNull().orEmpty())
        val order = node.chartValue("idx")?.toIntOrNull()?.takeIf { it >= 0 } ?: index
        val color = theme.solidFill(node.directChild("spPr")) ?: theme.solidFill(node.directChild("spPr")?.directChild("ln")) ?: theme.colors["accent${order % 6 + 1}"] ?: palette[order % palette.size]
        val pointColors = linkedMapOf<Int,Long>()
        node.elementChildren().filter { it.localName() == "dPt" }.forEach { point ->
            if(pointColors.size >= 512) invalidChart("Too many point styles")
            val position = point.chartValue("idx")?.toIntOrNull()?.takeIf { it in 0..511 } ?: invalidChart("Invalid point style index")
            if(pointColors.containsKey(position)) invalidChart("Duplicate point style index")
            pointColors[position] = theme.solidFill(point.directChild("spPr")) ?: theme.colors["accent${position % 6 + 1}"] ?: palette[position % palette.size]
        }
        if((kind == SlideChartKind.PIE || kind == SlideChartKind.DOUGHNUT) && type.chartFlag("varyColors",true)) {
            for(i in values.values.indices) if(i !in pointColors) pointColors[i] = theme.colors["accent${i % 6 + 1}"] ?: palette[i % palette.size]
        }
        if(!values.available || (kind == SlideChartKind.SCATTER && !x.available)) notices += SlideChartNotice.MISSING_VALUES
        val scatterStyle = type.chartValue("scatterStyle") ?: "lineMarker"
        if (scatterStyle.startsWith("smooth") && kind == SlideChartKind.SCATTER || node.chartFlag("smooth")) notices += SlideChartNotice.UNSUPPORTED_DATA
        val marker = node.directChild("marker")
        val showMarker = if(kind == SlideChartKind.SCATTER) scatterStyle in setOf("marker","lineMarker","smoothMarker")
            else marker != null || type.chartFlag("marker")
        SlideChartSeries(name,values.values.map(::number),color,x.values.map(::number),pointColors,values.format,
            node.directChild("spPr")?.directChild("ln")?.directChild("noFill") == null && (kind != SlideChartKind.SCATTER || scatterStyle !in setOf("marker","none")),
            marker.chartValue("symbol") != "none" && showMarker, values.values)
    }
    if(series.isEmpty() || series.all { it.values.all { value -> value == null } }) notices += SlideChartNotice.MISSING_VALUES
    if(series.any { it.values.any { value -> value == null } }) notices += SlideChartNotice.PARTIAL_VALUES
    if(kind == SlideChartKind.SCATTER) {
        if(series.any { it.xValues.size != it.values.size || it.xValues.any { x -> x == null } }) notices += SlideChartNotice.PARTIAL_VALUES
        if(series.none { s -> s.values.indices.any { s.values[it] != null && s.xValues.getOrNull(it) != null } }) notices += SlideChartNotice.MISSING_VALUES
    }
    val grouping = when(type.chartValue("grouping")) { "stacked" -> SlideChartGrouping.STACKED; "percentStacked" -> SlideChartGrouping.PERCENT; else -> SlideChartGrouping.STANDARD }
    if(kind in setOf(SlideChartKind.PIE,SlideChartKind.DOUGHNUT) && (series.size != 1 || series.any { s -> s.values.any { it != null && it < 0 } })) notices += SlideChartNotice.UNSUPPORTED_DATA
    if(kind in setOf(SlideChartKind.PIE,SlideChartKind.DOUGHNUT) && series.all { s -> s.values.none { it != null && it > 0 } }) notices += SlideChartNotice.UNSUPPORTED_DATA
    if(grouping == SlideChartGrouping.PERCENT && series.any { s -> s.values.any { it != null && it < 0 } }) notices += SlideChartNotice.UNSUPPORTED_DATA
    val axisIds = type?.elementChildren()?.filter { it.localName() == "axId" }?.mapNotNull { it.attributeByLocalName("val") }.orEmpty()
    val axes = plot.elementChildren().filter { it.localName() in setOf("catAx","dateAx","valAx") && (axisIds.isEmpty() || it.chartValue("axId") in axisIds) }
    fun axis(element: Element?): SlideChartAxis {
        val scaling = element?.directChild("scaling")
        if(scaling?.directChild("logBase") != null) notices += SlideChartNotice.UNSUPPORTED_AXIS
        fun n(parent: Element?, tag: String): Double? = parent.chartValue(tag)?.let { number(it) ?: invalidChart("Invalid chart axis bound") }
        val min = n(scaling,"min");val max = n(scaling,"max");val major = n(element,"majorUnit")
        if(min != null && max != null && min >= max || major != null && major <= 0) invalidChart("Invalid chart axis range")
        return SlideChartAxis(min,max,major,scaling.chartValue("orientation") == "maxMin",
            budget.text(element?.directChild("numFmt")?.attributeByLocalName("formatCode") ?: "General"),text(element?.directChild("title")),element.chartFlag("delete"))
    }
    val valueAxis = axes.firstOrNull { it.localName() == "valAx" && it.chartValue("axPos") in (if(kind == SlideChartKind.BAR) setOf("b","t") else setOf("l","r")) }
        ?: axes.firstOrNull { it.localName() == "valAx" }
    val categoryAxis = if(kind == SlideChartKind.SCATTER) axes.firstOrNull { it != valueAxis } else axes.firstOrNull { it.localName() in setOf("catAx","dateAx") }
    if(axes.any { it.localName() == "dateAx" }) notices += SlideChartNotice.UNSUPPORTED_AXIS
    val legend = chart.directChild("legend")
    val labelProperties = type?.directChild("dLbls")
    val defaults = root.directChild("txPr")?.elementChildren()?.firstOrNull { it.localName() == "p" }?.directChild("pPr")?.directChild("defRPr")
    val labelStyle = NativeSlideTextRun("",fontSizePoints=defaults?.attributeByLocalName("sz")?.toFloatOrNull()?.div(100)?.takeIf { it in 4f..72f } ?: 10f,
        textRgb=theme.solidFill(defaults) ?: 0x202124L,fontFamilyName=theme.typeface(defaults?.directChild("latin")?.attributeByLocalName("typeface"),false))
    return NativeSlideChart(kind,text(chart.directChild("title")),categoryLabels,series,grouping,axis(valueAxis),axis(categoryAxis),
        legend?.chartValue("legendPos")?.takeIf { it in setOf("b","t","l","r","tr") } ?: if(legend == null) null else "b",
        theme.solidFill(root.directChild("spPr")),theme.solidFill(plot.directChild("spPr")),
        type.chartValue("gapWidth")?.toIntOrNull()?.coerceIn(0,500) ?: 150,
        type.chartValue("firstSliceAng")?.toIntOrNull()?.coerceIn(0,360) ?: 0,
        type.chartValue("holeSize")?.toIntOrNull()?.coerceIn(10,90) ?: 50,
        labelProperties.chartFlag("showVal"),labelProperties.chartFlag("showPercent"),labelProperties.chartFlag("showCatName"),
        when(chart.chartValue("dispBlanksAs")) { "zero" -> SlideChartBlanks.ZERO; "span" -> SlideChartBlanks.SPAN; else -> SlideChartBlanks.GAP },date1904,labelStyle,notices)
}
