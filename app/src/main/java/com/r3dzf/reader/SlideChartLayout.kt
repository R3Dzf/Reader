package com.r3dzf.reader

import kotlin.math.*

internal data class ChartRange(val minimum: Double, val maximum: Double, val ticks: List<Double>) {
    fun fraction(value: Double, reversed: Boolean = false): Float {
        val valueFraction = ((value-minimum)/(maximum-minimum)).coerceIn(-100.0,100.0).toFloat()
        return if(reversed) 1f-valueFraction else valueFraction
    }
}
internal fun chartRange(values: List<Double>, axis: SlideChartAxis, includeZero: Boolean = true): ChartRange {
    val finite=values.filter { it.isFinite() }
    var low=axis.minimum ?: minOf(finite.minOrNull() ?: 0.0,if(includeZero) 0.0 else Double.POSITIVE_INFINITY)
    var high=axis.maximum ?: maxOf(finite.maxOrNull() ?: 1.0,if(includeZero) 0.0 else Double.NEGATIVE_INFINITY)
    if(low>=high) {
        val padding=maxOf(abs(low)*0.1,abs(high)*0.1,1.0)
        when {
            axis.minimum!=null && axis.maximum==null -> high=low+padding
            axis.maximum!=null && axis.minimum==null -> low=high-padding
            includeZero && low==0.0 -> high=1.0
            else -> { low-=padding;high+=padding }
        }
    }
    if(low>=high) return ChartRange(0.0,1.0,listOf(0.0,1.0))
    val span=high-low
    val ideal=(span/5).takeIf { it>0 } ?: span
    val power=10.0.pow(floor(log10(ideal))).takeIf { it>0 } ?: Double.MIN_VALUE
    val step=axis.majorUnit ?: (listOf(1.0,2.0,5.0,10.0).first { it*power>=ideal }*power)
    if(axis.minimum==null) (floor(low/step)*step).takeIf { it.isFinite() }?.let { low=it }
    if(axis.maximum==null) (ceil(high/step)*step).takeIf { it.isFinite() }?.let { high=it }
    val safeStep=maxOf(step,(high-low)/10)
    val first=ceil(low/safeStep)*safeStep
    val ticks=(0..10).map { first+it*safeStep }.filter { it<=high+safeStep*1e-8 }.map { if(abs(it)<safeStep*1e-10) 0.0 else it }
    return ChartRange(low,high,ticks)
}
internal data class ChartSegment(val series: Int,val index: Int,val start: Double,val end: Double,val value: Double)
internal fun chartSegments(chart: NativeSlideChart): List<ChartSegment> {
    val count=chart.series.maxOfOrNull { it.values.size } ?: 0
    val positive=DoubleArray(count);val negative=DoubleArray(count)
    val totals=DoubleArray(count) { i -> chart.series.sumOf { it.values.getOrNull(i)?.let(::abs) ?: 0.0 } }
    return chart.series.flatMapIndexed { seriesIndex,series -> series.values.mapIndexedNotNull { index,raw ->
        val value=raw ?: if(chart.blanks==SlideChartBlanks.ZERO) 0.0 else return@mapIndexedNotNull null
        val scaled=if(chart.grouping==SlideChartGrouping.PERCENT) { if(totals[index]==0.0) 0.0 else value/totals[index] } else value
        val start=if(chart.grouping==SlideChartGrouping.STANDARD) 0.0 else if(scaled>=0) positive[index] else negative[index]
        val end=start+scaled
        if(chart.grouping!=SlideChartGrouping.STANDARD) { if(scaled>=0) positive[index]=end else negative[index]=end }
        ChartSegment(seriesIndex,index,start,end,value)
    } }
}
internal data class ChartSlice(val index: Int,val startDegrees: Float,val sweepDegrees: Float,val value: Double)
internal fun chartSlices(chart: NativeSlideChart): List<ChartSlice> {
    val values=chart.series.firstOrNull()?.values.orEmpty()
    val total=values.sumOf { if(it!=null && it>0) it else 0.0 }
    if(total<=0) return emptyList()
    var start=-90f+chart.firstSliceAngle
    return values.mapIndexedNotNull { index,value ->
        if(value==null || value<=0) return@mapIndexedNotNull null
        val sweep=(value/total*360).toFloat()
        ChartSlice(index,start,sweep,value).also { start+=sweep }
    }
}
