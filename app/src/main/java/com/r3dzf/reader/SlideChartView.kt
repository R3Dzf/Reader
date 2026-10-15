package com.r3dzf.reader

import android.content.Context
import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Path as NativePath
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.*

@Composable
internal fun SlideChartView(chart: NativeSlideChart, pointScale: Float, modifier: Modifier = Modifier) {
    val context=LocalContext.current
    val density=LocalDensity.current.density
    var showData by remember(chart) { mutableStateOf(false) }
    val dataLabel=stringResource(R.string.chart_read_data)
    val notice = when {
        SlideChartNotice.MISSING_VALUES in chart.notices -> R.string.chart_missing_values
        !chart.drawable -> R.string.chart_unsupported
        SlideChartNotice.PARTIAL_VALUES in chart.notices -> R.string.chart_partial_values
        else -> null
    }
    val description=remember(chart) { (listOf(chart.title)+chart.series.map { it.name }).filter { it.isNotBlank() }.joinToString(" — ").take(1200) }
    Box(modifier.testTag("native-chart").semantics { contentDescription=description }) {
        if(chart.drawable) Canvas(Modifier.fillMaxSize().testTag("chart-canvas")) {
            drawNativeChart(drawContext.canvas.nativeCanvas,chart,size.width,size.height,pointScale*density,context)
        }
        if(notice!=null) Text(stringResource(notice),style=MaterialTheme.typography.labelSmall,color=Color(0xFF843B16),
            modifier=Modifier.align(Alignment.BottomStart).background(Color.White.copy(alpha=.94f)).padding(2.dp).testTag("chart-notice"))
        TextButton(onClick={showData=true},modifier=Modifier.align(Alignment.TopEnd).size(28.dp).testTag("chart-read-data")
            .semantics { contentDescription=dataLabel },contentPadding=PaddingValues(0.dp)) { Text("≡",color=Color(0xFF145DA0)) }
    }
    if(showData) {
        val rows=remember(chart) { chart.series.flatMap { series -> series.values.indices.map { index ->
            val category=chart.categories.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: (index+1).toString()
            val raw=series.rawValues.getOrNull(index) ?: series.values[index]?.toString()
            val value=raw?.let { XlsxNumberFormatter(chart.date1904).format(it,series.format).text } ?: "—"
            val x=if(chart.kind==SlideChartKind.SCATTER) "${series.xValues.getOrNull(index) ?: "—"}, " else ""
            listOf(series.name,category,"$x$value").filter { it.isNotEmpty() }.joinToString(": ")
        } } }
        AlertDialog(onDismissRequest={showData=false},title={Text(chart.title.ifBlank { dataLabel })},
            text={LazyColumn(Modifier.fillMaxWidth().heightIn(max=360.dp).testTag("chart-data-list")) {
                items(rows) { row -> Text(row,modifier=Modifier.fillMaxWidth().padding(vertical=5.dp),color=MaterialTheme.colorScheme.onSurface) }
                if(rows.isEmpty()) item { Text(stringResource(R.string.chart_missing_values)) }
            }},confirmButton={TextButton(onClick={showData=false}) { Text(stringResource(R.string.close)) }})
    }
}

/** Native canvas text uses the same offline registry as slide text and Android's bidi/shaping. */
internal fun drawNativeChart(canvas: NativeCanvas, chart: NativeSlideChart, width: Float, height: Float, pointPx: Float, context: Context) {
    if(width<=1 || height<=1) return
    val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    fun rgb(color: Long)=(0xFF000000L or color).toInt()
    fun line(x1: Float,y1: Float,x2: Float,y2: Float,color: Long=0xB6BDC7L,stroke: Float=0.6f) {
        paint.color=rgb(color);paint.strokeWidth=maxOf(.5f,stroke*pointPx);paint.style=Paint.Style.STROKE
        canvas.drawLine(x1,y1,x2,y2,paint)
    }
    fun text(value: String,x: Float,y: Float,w: Float,h: Float,size: Float=chart.labelStyle.fontSizePoints ?: 10f,bold: Boolean=false,align: Layout.Alignment=Layout.Alignment.ALIGN_CENTER) {
        if(value.isBlank() || w<2 || h<2) return
        val textPaint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color=rgb(chart.labelStyle.textRgb ?: 0x202124L)
            textSize=(size*pointPx).coerceIn(1f,4096f)
            typeface=DocumentFonts.typeface(context,chart.labelStyle.fontFamilyName,bold=bold,
                arabic=value.codePoints().anyMatch { Character.UnicodeScript.of(it)==Character.UnicodeScript.ARABIC })
        }
        val layout=StaticLayout.Builder.obtain(value,0,value.length,textPaint,w.toInt().coerceAtLeast(1))
            .setAlignment(align).setIncludePad(false).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).build()
        val save=canvas.save()
        canvas.clipRect(x,y,x+w,y+h)
        canvas.translate(x,y+(h-layout.height).coerceAtLeast(0f)/2)
        layout.draw(canvas)
        canvas.restoreToCount(save)
    }
    chart.backgroundRgb?.let { paint.color=rgb(it);paint.style=Paint.Style.FILL;canvas.drawRect(0f,0f,width,height,paint) }
    val pad=minOf(width,height)*.025f
    val titleHeight=if(chart.title.isBlank()) pad else height*.13f
    if(chart.title.isNotBlank()) text(chart.title,pad,pad,width-2*pad,titleHeight-pad,size=14f,bold=true)
    val pie=chart.kind in setOf(SlideChartKind.PIE,SlideChartKind.DOUGHNUT)
    val legendEntries=if(pie) chart.series.firstOrNull()?.values?.indices?.map { i ->
        (chart.categories.getOrNull(i) ?: (i+1).toString()) to (chart.series[0].pointColors[i] ?: chart.series[0].colorRgb)
    }.orEmpty() else chart.series.mapIndexed { i,s -> s.name.ifBlank { (i+1).toString() } to s.colorRgb }
    val sideLegend=chart.legendPosition in setOf("r","l","tr")
    val legendWidth=if(chart.legendPosition!=null && sideLegend) width*.22f else 0f
    val legendHeight=if(chart.legendPosition!=null && !sideLegend) height*.17f else 0f
    val legendTop=chart.legendPosition=="t"
    val left=pad+(if(chart.legendPosition=="l") legendWidth else 0f)+(if(pie) 0f else if(chart.kind==SlideChartKind.BAR) width*.20f else width*.14f)
    val top=titleHeight+pad+if(legendTop) legendHeight else 0f
    val right=width-pad-if(sideLegend && chart.legendPosition!="l") legendWidth else 0f
    val bottom=height-pad-(if(!legendTop) legendHeight else 0f)-(if(pie) 0f else height*.16f)
    if(right<=left || bottom<=top) return
    val plotW=right-left;val plotH=bottom-top
    chart.plotRgb?.let { paint.style=Paint.Style.FILL;paint.color=rgb(it);canvas.drawRect(left,top,right,bottom,paint) }
    if(chart.legendPosition!=null) {
        val entries=legendEntries.take(12)
        val x=if(sideLegend) if(chart.legendPosition=="l") pad else right+pad else pad
        val y=if(sideLegend) top else if(legendTop) titleHeight+pad else height-legendHeight
        val rowHeight=if(sideLegend) minOf(plotH/maxOf(1,entries.size),height*.12f) else legendHeight/2
        val columns=if(sideLegend) 1 else minOf(4,maxOf(1,entries.size))
        val itemW=if(sideLegend) legendWidth-pad else (width-2*pad)/columns
        entries.forEachIndexed { i,(label,color) ->
            val itemX=x+(i%columns)*itemW;val itemY=y+(i/columns)*rowHeight
            if(itemY+rowHeight <= height) {
                val swatch=minOf(rowHeight*.25f,itemW*.08f)
                paint.style=Paint.Style.FILL;paint.color=rgb(color);canvas.drawRect(itemX,itemY+rowHeight/2-swatch/2,itemX+swatch,itemY+rowHeight/2+swatch/2,paint)
                text(label,itemX+swatch+pad*.4f,itemY,itemW-swatch-pad,rowHeight,size=9f)
            }
        }
    }
    if(pie) {
        val slices=chartSlices(chart)
        val diameter=minOf(plotW,plotH)*.94f
        val bounds=RectF(left+(plotW-diameter)/2,top+(plotH-diameter)/2,left+(plotW+diameter)/2,top+(plotH+diameter)/2)
        val inner=RectF(bounds).apply { val gap=diameter*(1-chart.holeSize/100f)/2;inset(gap,gap) }
        slices.forEach { slice ->
            paint.color=rgb(chart.series[0].pointColors[slice.index] ?: chart.series[0].colorRgb);paint.style=Paint.Style.FILL
            if(chart.kind==SlideChartKind.PIE) canvas.drawArc(bounds,slice.startDegrees,slice.sweepDegrees,true,paint)
            else {
                val path=NativePath().apply {
                    arcTo(bounds,slice.startDegrees,slice.sweepDegrees)
                    val angle=Math.toRadians((slice.startDegrees+slice.sweepDegrees).toDouble())
                    lineTo(inner.centerX()+cos(angle).toFloat()*inner.width()/2,inner.centerY()+sin(angle).toFloat()*inner.height()/2)
                    arcTo(inner,slice.startDegrees+slice.sweepDegrees,-slice.sweepDegrees);close()
                }
                canvas.drawPath(path,paint)
            }
            if(slices.size<=24 && (chart.showValues || chart.showPercent || chart.showCategory)) {
                val angle=Math.toRadians((slice.startDegrees+slice.sweepDegrees/2).toDouble())
                val radius=diameter*(if(chart.kind==SlideChartKind.DOUGHNUT) (1+chart.holeSize/100f)/4 else .32f)
                val label=listOfNotNull(chart.categories.getOrNull(slice.index).takeIf { chart.showCategory },
                    chartNumber(slice.value,chart.series[0].format,chart.date1904).takeIf { chart.showValues },
                    chartNumber(slice.sweepDegrees/360.0,"0%",false).takeIf { chart.showPercent }).joinToString(" ")
                text(label,bounds.centerX()+cos(angle).toFloat()*radius-diameter*.18f,bounds.centerY()+sin(angle).toFloat()*radius-12*pointPx,diameter*.36f,24*pointPx,size=9f)
            }
        }
        return
    }
    val segments=chartSegments(chart)
    val percent=chart.grouping==SlideChartGrouping.PERCENT
    val horizontal=chart.kind==SlideChartKind.BAR
    val scatter=chart.kind==SlideChartKind.SCATTER
    val valueRange=chartRange(if(percent) listOf(0.0,1.0) else if(scatter) chart.series.flatMap { it.values.filterNotNull() }
        else segments.flatMap { listOf(it.start,it.end) },chart.valueAxis,includeZero=!scatter)
    val xRange=if(scatter) chartRange(chart.series.flatMap { it.xValues.filterNotNull() },chart.categoryAxis,false) else null
    val count=maxOf(chart.categories.size,chart.series.maxOfOrNull { it.values.size } ?: 0,1)
    fun category(index: Int): Float {
        val fraction=if(chart.kind in setOf(SlideChartKind.COLUMN,SlideChartKind.BAR)) (index+.5f)/count else if(count==1) .5f else index.toFloat()/(count-1)
        val logical=if(chart.categoryAxis.reversed) 1f-fraction else fraction
        return if(horizontal) 1f-logical else logical
    }
    fun vx(value: Double)=left+valueRange.fraction(value,chart.valueAxis.reversed)*plotW
    fun vy(value: Double)=bottom-valueRange.fraction(value,chart.valueAxis.reversed)*plotH
    val format=if(percent && chart.valueAxis.format=="General") "0%" else chart.valueAxis.format
    if(!chart.valueAxis.hidden) valueRange.ticks.forEach { tick ->
        val label=chartNumber(tick,format,chart.date1904)
        if(horizontal) { val x=vx(tick);line(x,top,x,bottom,0xDFE3E8L);text(label,x-width*.07f,bottom+pad,width*.14f,height*.08f,size=9f) }
        else { val y=vy(tick);line(left,y,right,y,0xDFE3E8L);text(label,0f,y-7*pointPx,left-pad,14*pointPx,size=9f) }
    }
    if(!chart.categoryAxis.hidden) {
        if(scatter) xRange!!.ticks.forEach { tick -> val x=left+xRange.fraction(tick,chart.categoryAxis.reversed)*plotW;text(chartNumber(tick,chart.categoryAxis.format,chart.date1904),x-width*.07f,bottom+pad,width*.14f,height*.08f,size=9f) }
        else {
            val every=maxOf(1,ceil(count/12.0).toInt())
            for(i in 0 until count step every) {
                val label=chart.categories.getOrNull(i) ?: (i+1).toString()
                if(horizontal) text(label,0f,top+category(i)*plotH-plotH/count/2,left-pad,plotH/count,size=9f)
                else text(label,left+category(i)*plotW-plotW/maxOf(2,count).toFloat()/2,bottom+pad,plotW/maxOf(2,count),height*.11f,size=9f)
            }
        }
    }
    line(left,bottom,right,bottom);line(left,top,left,bottom)
    if(chart.categoryAxis.title.isNotBlank()) text(chart.categoryAxis.title,left,bottom+height*.09f,plotW,height*.06f,size=9f)
    if(chart.valueAxis.title.isNotBlank()) text(chart.valueAxis.title,left,top-height*.06f,plotW,height*.06f,size=9f)
    val markers=mutableListOf<Triple<Float,Float,Long>>()
    val dataLabels=mutableListOf<() -> Unit>()
    val save=canvas.save();canvas.clipRect(left,top,right,bottom)
    if(chart.kind in setOf(SlideChartKind.COLUMN,SlideChartKind.BAR)) {
        val band=(if(horizontal) plotH else plotW)/count
        val available=band/(1+chart.gapWidth/100f)
        val cluster=chart.grouping==SlideChartGrouping.STANDARD
        val bar=available/(if(cluster) maxOf(1,chart.series.size) else 1)
        segments.forEach { segment ->
            val series=chart.series[segment.series]
            val position=(if(horizontal) top else left)+category(segment.index)*(if(horizontal) plotH else plotW)-available/2+(if(cluster) segment.series*bar else 0f)
            paint.style=Paint.Style.FILL;paint.color=rgb(series.pointColors[segment.index] ?: series.colorRgb)
            if(horizontal) canvas.drawRect(minOf(vx(segment.start),vx(segment.end)),position,maxOf(vx(segment.start),vx(segment.end)),position+bar,paint)
            else canvas.drawRect(position,minOf(vy(segment.start),vy(segment.end)),position+bar,maxOf(vy(segment.start),vy(segment.end)),paint)
            if(chart.showValues && segments.size<=64) {
                val label=chartNumber(segment.value,series.format,chart.date1904)
                dataLabels += {
                    if(horizontal) text(label,(vx(segment.end)-20*pointPx).coerceIn(left,maxOf(left,right-40*pointPx)),position,40*pointPx,bar,size=9f)
                    else text(label,position,(vy(segment.end)-14*pointPx).coerceIn(titleHeight,maxOf(titleHeight,bottom-14*pointPx)),bar,14*pointPx,size=9f)
                }
            }
        }
    } else chart.series.forEachIndexed { seriesIndex,series ->
        val points=segments.filter { it.series==seriesIndex }.mapNotNull { segment ->
            val x=if(scatter) series.xValues.getOrNull(segment.index)?.let { left+xRange!!.fraction(it,chart.categoryAxis.reversed)*plotW } ?: return@mapNotNull null else left+category(segment.index)*plotW
            Triple(segment,x,vy(segment.end))
        }
        val groups=mutableListOf<MutableList<Triple<ChartSegment,Float,Float>>>()
        points.forEach { point ->
            if(groups.isEmpty() || chart.blanks==SlideChartBlanks.GAP && groups.last().last().first.index+1!=point.first.index) groups+=mutableListOf(point) else groups.last()+=point
        }
        groups.forEach { group ->
            val path=NativePath();group.forEachIndexed { i,p -> if(i==0) path.moveTo(p.second,p.third) else path.lineTo(p.second,p.third) }
            if(chart.kind==SlideChartKind.AREA) {
                group.asReversed().forEach { p -> path.lineTo(p.second,vy(p.first.start)) };path.close()
                paint.style=Paint.Style.FILL;paint.color=rgb(series.colorRgb);canvas.drawPath(path,paint)
            } else if(series.lineVisible) {
                paint.style=Paint.Style.STROKE;paint.color=rgb(series.colorRgb);paint.strokeWidth=maxOf(1f,1.5f*pointPx);canvas.drawPath(path,paint)
            }
            if(series.markerVisible || scatter && !series.lineVisible) group.forEach { p ->
                if(p.second in left..right && p.third in top..bottom)
                    markers.add(Triple(p.second,p.third,series.pointColors[p.first.index] ?: series.colorRgb))
            }
        }
    }
    canvas.restoreToCount(save)
    markers.forEach { (x,y,color) ->
        paint.style=Paint.Style.FILL;paint.color=rgb(color);canvas.drawCircle(x,y,maxOf(1.5f,2.5f*pointPx),paint)
    }
    dataLabels.forEach { it() }
}
