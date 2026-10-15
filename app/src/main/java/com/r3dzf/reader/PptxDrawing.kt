package com.r3dzf.reader

import org.w3c.dom.Element
import kotlin.math.*

data class SlideGradientStop(val position: Float, val rgb: Long, val alpha: Float = 1f)
data class SlideFill(val rgb: Long? = null, val alpha: Float = 1f, val stops: List<SlideGradientStop> = emptyList(), val angleDegrees: Float = 0f)
data class SlideTextBox(val left: Float = 7.2f, val right: Float = 7.2f, val top: Float = 3.6f, val bottom: Float = 3.6f,
    val vertical: SlideCellVerticalAlignment = SlideCellVerticalAlignment.TOP)
data class SlideImageCrop(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f)

/** Affine group coordinates, bounded before any drawing allocation. */
internal data class SlideTransform(val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0, val d: Double = 1.0,
    val x: Double = 0.0, val y: Double = 0.0) {
    init {
        if (listOf(a,b,c,d,x,y).any { !it.isFinite() || abs(it) > 1e15 })
            throw InvalidOfficeDocumentException("A PowerPoint group transform exceeds the coordinate limit.")
    }
    fun then(t: SlideTransform) = SlideTransform(a*t.a+c*t.b,b*t.a+d*t.b,a*t.c+c*t.d,b*t.c+d*t.d,a*t.x+c*t.y+x,b*t.x+d*t.y+y)
    fun point(px: Double, py: Double) = (a*px+c*py+x) to (b*px+d*py+y)
    companion object {
        fun from(element: Element?, group: Boolean = false): SlideTransform {
            fun coordinate(tag: String, name: String, default: Double = 0.0): Double {
                val raw = element?.directChild(tag)?.attributeByLocalName(name) ?: return default
                return raw.toDoubleOrNull()?.takeIf { it.isFinite() && abs(it) <= 1e12 }
                    ?: throw InvalidOfficeDocumentException("A PowerPoint transform has an invalid coordinate.")
            }
            val x = coordinate("off","x"); val y = coordinate("off","y")
            val w = coordinate("ext","cx"); val h = coordinate("ext","cy")
            val angle = (element?.attributeByLocalName("rot")?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0) / 60000.0 * PI / 180
            val fx = if (element?.attributeByLocalName("flipH") in listOf("1","true")) -1.0 else 1.0
            val fy = if (element?.attributeByLocalName("flipV") in listOf("1","true")) -1.0 else 1.0
            val rotation = SlideTransform(cos(angle)*fx,sin(angle)*fx,-sin(angle)*fy,cos(angle)*fy)
            val start = SlideTransform(x=x+w/2, y=y+h/2).then(rotation).then(SlideTransform(x=-w/2,y=-h/2))
            if (!group) return start
            val cw = coordinate("chExt","cx",w); val ch = coordinate("chExt","cy",h)
            if (cw <= 0.0 || ch <= 0.0) throw InvalidOfficeDocumentException("A PowerPoint group has invalid child dimensions.")
            return start.then(SlideTransform(a=w/cw,d=h/ch)).then(SlideTransform(x=-coordinate("chOff","x"),y=-coordinate("chOff","y")))
        }
    }
}

internal fun PptxTheme.readFill(parent: Element?, placeholder: Long? = null): SlideFill? {
    parent ?: return null
    val fill = if (parent.localName() in setOf("solidFill","gradFill","noFill")) parent else parent.elementChildren()
        .firstOrNull { it.localName() in setOf("solidFill","gradFill","noFill","blipFill","pattFill") } ?: return null
    if (fill.localName() == "noFill") return SlideFill(alpha=0f)
    if (fill.localName() == "solidFill") {
        val element = fill.elementChildren().firstOrNull() ?: return null
        return SlideFill(color(element, placeholder), colorAlpha(element))
    }
    if (fill.localName() != "gradFill") return null
    val stops = fill.directChild("gsLst")?.elementChildren().orEmpty().take(64).mapNotNull { stop ->
        val position = stop.attributeByLocalName("pos")?.toFloatOrNull()?.div(100000)?.takeIf { it.isFinite() } ?: return@mapNotNull null
        val element = stop.elementChildren().firstOrNull() ?: return@mapNotNull null
        color(element, placeholder)?.let { SlideGradientStop(position.coerceIn(0f,1f),it,colorAlpha(element)) }
    }.sortedBy { it.position }
    if (stops.isEmpty()) return null
    if (stops.size == 1) return SlideFill(stops[0].rgb, stops[0].alpha)
    return SlideFill(stops=stops, angleDegrees=(fill.directChild("lin")?.attributeByLocalName("ang")?.toFloatOrNull()?.div(60000)?.takeIf { it.isFinite() } ?: 0f) % 360)
}

internal fun colorAlpha(color: Element): Float {
    var alpha = 1f
    color.elementChildren().forEach { transform ->
        val amount = transform.attributeByLocalName("val")?.toFloatOrNull()?.div(100000)?.takeIf { it.isFinite() } ?: return@forEach
        when (transform.localName()) { "alpha" -> alpha=amount; "alphaMod" -> alpha*=amount; "alphaOff" -> alpha+=amount }
    }
    return alpha.coerceIn(0f,1f)
}

internal fun readSlideTextBox(shapes: List<Element>): SlideTextBox {
    val bodies = shapes.mapNotNull { it.directChild("txBody")?.directChild("bodyPr") }
    fun attr(name: String) = bodies.firstNotNullOfOrNull { it.attributeByLocalName(name) }
    fun inset(name: String, fallback: Float) = attr(name)?.toFloatOrNull()?.div(12700)?.takeIf { it.isFinite() }?.coerceIn(0f,1000f) ?: fallback
    return SlideTextBox(inset("lIns",7.2f),inset("rIns",7.2f),inset("tIns",3.6f),inset("bIns",3.6f),
        when(attr("anchor")) { "ctr" -> SlideCellVerticalAlignment.CENTER; "b" -> SlideCellVerticalAlignment.BOTTOM; else -> SlideCellVerticalAlignment.TOP })
}
