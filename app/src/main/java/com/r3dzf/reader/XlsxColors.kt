package com.r3dzf.reader

import org.w3c.dom.Element
import kotlin.math.*

/** Spreadsheet colors use their own lt1/dk1 order, not DrawingML's XML child order. */
internal class XlsxColors(theme: Element?=null,styles: Element?=null) {
    private val defaults=listOf(0xFFFFFFL,0x000000L,0xEEECE1L,0x1F497DL,0x4F81BDL,0xC0504DL,0x9BBB59L,0x8064A2L,0x4BACC6L,0xF79646L,0x0000FFL,0x800080L)
    private val scheme=theme?.directChild("themeElements")?.directChild("clrScheme")
    private val themeColors=listOf("lt1","dk1","lt2","dk2","accent1","accent2","accent3","accent4","accent5","accent6","hlink","folHlink").mapIndexed { index,name ->
        val node=scheme?.directChild(name)?.elementChildren()?.firstOrNull()
        rgb(node?.attributeByLocalName("val")) ?: rgb(node?.attributeByLocalName("lastClr")) ?: defaults[index]
    }
    private val indexed=styles?.directChild("colors")?.directChild("indexedColors")?.elementChildren()?.mapNotNull { rgb(it.attributeByLocalName("rgb")) }.orEmpty()
    fun theme(index: Int): Long = themeColors.getOrElse(index) { defaults[4] }
    fun read(node: Element?): Long? {
        node ?: return null
        val base=rgb(node.attributeByLocalName("rgb"))
            ?: node.attributeByLocalName("theme")?.toIntOrNull()?.let { themeColors.getOrNull(it) }
            ?: node.attributeByLocalName("indexed")?.toIntOrNull()?.let { index -> indexed.getOrNull(index) ?: INDEXED.getOrNull(index) }
            ?: return null
        val tint=node.attributeByLocalName("tint")?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -1.0..1.0 } ?: 0.0
        return tint(base,tint)
    }
    companion object {
        private fun rgb(value: String?)=value?.takeLast(6)?.takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toLongOrNull(16)
        internal fun tint(rgb: Long,tint: Double): Long {
            if(tint==0.0) return rgb
            val r=((rgb shr 16) and 255)/255.0;val g=((rgb shr 8) and 255)/255.0;val b=(rgb and 255)/255.0
            val max=maxOf(r,g,b);val min=minOf(r,g,b);val delta=max-min;val light=(max+min)/2
            val saturation=if(delta==0.0) 0.0 else delta/(1-abs(2*light-1))
            val hue=when {delta==0.0 -> 0.0;max==r -> ((g-b)/delta+6)%6;max==g -> (b-r)/delta+2;else -> (r-g)/delta+4}*60
            val l=if(tint<0) light*(1+tint) else light*(1-tint)+tint
            val c=(1-abs(2*l-1))*saturation;val x=c*(1-abs((hue/60)%2-1));val m=l-c/2
            val channels=when(hue) {in 0.0..<60.0 -> listOf(c,x,0.0);in 60.0..<120.0 -> listOf(x,c,0.0);in 120.0..<180.0 -> listOf(0.0,c,x);in 180.0..<240.0 -> listOf(0.0,x,c);in 240.0..<300.0 -> listOf(x,0.0,c);else -> listOf(c,0.0,x)}
            return channels.fold(0L) { result,v -> (result shl 8) or ((v+m)*255).roundToInt().coerceIn(0,255).toLong() }
        }
        private val INDEXED=listOf(0x000000L,0xFFFFFFL,0xFF0000L,0x00FF00L,0x0000FFL,0xFFFF00L,0xFF00FFL,0x00FFFFL,0x000000L,0xFFFFFFL,0xFF0000L,0x00FF00L,0x0000FFL,0xFFFF00L,0xFF00FFL,0x00FFFFL,0x800000L,0x008000L,0x000080L,0x808000L,0x800080L,0x008080L,0xC0C0C0L,0x808080L,0x9999FFL,0x993366L,0xFFFFCCL,0xCCFFFFL,0x660066L,0xFF8080L,0x0066CCL,0xCCCCFFL,0x000080L,0xFF00FFL,0xFFFF00L,0x00FFFFL,0x800080L,0x800000L,0x008080L,0x0000FFL,0x00CCFFL,0xCCFFFFL,0xCCFFCCL,0xFFFF99L,0x99CCFFL,0xFF99CCL,0xCC99FFL,0xFFCC99L,0x3366FFL,0x33CCCCL,0x99CC00L,0xFFCC00L,0xFF9900L,0xFF6600L,0x666699L,0x969696L,0x003366L,0x339966L,0x003300L,0x333300L,0x993300L,0x993366L,0x333399L,0x333333L)
    }
}
