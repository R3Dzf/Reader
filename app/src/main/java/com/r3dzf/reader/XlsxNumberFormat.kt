package com.r3dzf.reader

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

internal data class XlsxFormattedNumber(val text: String, val supported: Boolean = true, val colorRgb: Long? = null)

/** A bounded subset of Excel formats. Unknown syntax keeps the saved value and reports a fallback. */
internal class XlsxNumberFormatter(private val date1904: Boolean = false) {
    private data class Token(val value: String, val literal: Boolean = false)
    private data class Section(val tokens: List<Token>, val color: Long? = null)
    private val cache = mutableMapOf<String, List<Section?>>()

    fun format(raw: String, code: String?): XlsxFormattedNumber {
        fun fallback() = XlsxFormattedNumber(raw, false)
        if (raw.length > 128 || code == null || code.length > 512) return fallback()
        val value = raw.toBigDecimalOrNull() ?: return fallback()
        if (value.precision() > 128 || value.scale() !in -308..308 || !value.toDouble().isFinite()) return fallback()
        if (code.equals("General", true) || code == "@") return XlsxFormattedNumber(raw)
        val sections = cache.getOrPut(code) {
            if (cache.size >= 4096) cache.clear()
            splitSections(code)?.map(::tokenize) ?: listOf(null)
        }
        val index = when {
            value.signum() < 0 && sections.size >= 2 -> 1
            value.signum() == 0 && sections.size >= 3 -> 2
            else -> 0
        }
        val section = sections.getOrNull(index) ?: return fallback()
        val magnitude = if (index == 1) value.abs() else value
        val text = if (section.tokens.any { !it.literal && (it.value.startsWith('[') || it.value.firstOrNull()?.lowercaseChar()?.let { c -> c in "ymdhs" } == true) }) {
            dateText(magnitude, section.tokens)
        } else numberText(magnitude, section.tokens)
        return text?.takeIf { it.length <= 1024 }?.let { XlsxFormattedNumber(it, colorRgb = section.color) } ?: fallback()
    }

    private fun splitSections(code: String): List<String>? {
        val sections = mutableListOf<String>()
        var start = 0
        var quoted = false
        var escaped = false
        code.forEachIndexed { index, c ->
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (c == '"') quoted = !quoted
            else if (c == ';' && !quoted) { sections += code.substring(start, index); start = index + 1 }
        }
        if (quoted || escaped || sections.size >= 4) return null
        sections += code.substring(start)
        return sections
    }

    private fun tokenize(code: String): Section? {
        val tokens = mutableListOf<Token>()
        var color: Long? = null
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> {
                    val end = code.indexOf('"', i + 1)
                    if (end < 0) return null
                    tokens += Token(code.substring(i + 1, end), true); i = end + 1
                }
                c == '\\' -> { if (i + 1 >= code.length) return null; tokens += Token(code[i + 1].toString(), true); i += 2 }
                c == '[' -> {
                    val end = code.indexOf(']', i + 1)
                    if (end < 0) return null
                    val tag = code.substring(i + 1, end).lowercase(Locale.ROOT)
                    if (tag in listOf("h", "hh", "m", "mm", "s", "ss")) tokens += Token("[$tag]")
                    else { color = COLORS[tag] ?: return null }
                    i = end + 1
                }
                code.regionMatches(i, "AM/PM", 0, 5, true) -> { tokens += Token("AM/PM"); i += 5 }
                c.lowercaseChar() in "ymdhs" -> {
                    var end = i + 1
                    while (end < code.length && code[end].equals(c, true)) end++
                    tokens += Token(code.substring(i, end).lowercase(Locale.ROOT)); i = end
                }
                c in "0#.,%Ee+-" -> { tokens += Token(c.toString()); i++ }
                c in " /:()$€£¥" -> { tokens += Token(c.toString(), true); i++ }
                else -> return null
            }
        }
        return Section(tokens, color)
    }

    private fun numberText(value: BigDecimal, tokens: List<Token>): String? {
        if (tokens.isEmpty()) return ""
        val first = tokens.indexOfFirst { !it.literal && it.value in listOf("0", "#") }
        if (first < 0) return if (tokens.all { it.literal }) tokens.joinToString("") { it.value } else null
        val last = tokens.indexOfLast { !it.literal && it.value in listOf("0", "#") }
        var end = last + 1
        while (end < tokens.size && !tokens[end].literal && tokens[end].value == ",") end++
        val bodyTokens = tokens.subList(first, end)
        if (bodyTokens.any { it.literal }) return null
        val body = bodyTokens.joinToString("") { it.value }
        val outer = tokens.take(first) + tokens.drop(end)
        if (outer.any { !it.literal && it.value !in listOf("%", "-", "+") }) return null
        val percents = outer.count { !it.literal && it.value == "%" }
        if (percents > 1) return null
        val scaled = if (percents == 1) value.multiply(BigDecimal(100)) else value
        val text = if ('E' in body.uppercase(Locale.ROOT)) scientific(scaled.abs(), body) else decimal(scaled.abs(), body)
        text ?: return null
        val prefix = tokens.take(first).joinToString("") { it.value }
        val suffix = tokens.drop(end).joinToString("") { it.value }
        return (if (scaled.signum() < 0) "-" else "") + prefix + text + suffix
    }

    private fun decimal(value: BigDecimal, body: String): String? {
        val scaling = body.length - body.trimEnd(',').length
        if (scaling > 3) return null
        val pattern = body.trimEnd(',').split('.')
        if (pattern.size > 2) return null
        val integer = pattern[0]
        val fraction = pattern.getOrElse(1) { "" }
        if (!integer.matches(Regex("[0#]+(,[0#]{3})*")) || !fraction.matches(Regex("0*#*")) || fraction.length > 15) return null
        val places = fraction.length
        val number = value.movePointLeft(scaling * 3).setScale(places, RoundingMode.HALF_UP).toPlainString().split('.')
        var whole = number[0].padStart(integer.count { it == '0' }, '0')
        if (whole == "0" && '0' !in integer) whole = ""
        if (',' in integer) whole = whole.reversed().chunked(3).joinToString(",").reversed()
        var decimals = number.getOrElse(1) { "" }
        val mandatory = fraction.count { it == '0' }
        while (decimals.length > mandatory && decimals.endsWith('0')) decimals = decimals.dropLast(1)
        return whole + if (decimals.isNotEmpty()) ".$decimals" else ""
    }

    private fun scientific(value: BigDecimal, pattern: String): String? {
        val match = Regex("0(?:\\.(0{1,15}))?[Ee]([+-])(0{1,3})").matchEntire(pattern) ?: return null
        val decimals = match.groupValues[1].length
        var exponent = if (value.signum() == 0) 0 else value.precision() - value.scale() - 1
        var mantissa = value.movePointLeft(exponent).setScale(decimals, RoundingMode.HALF_UP)
        if (mantissa >= BigDecimal.TEN) { exponent++; mantissa = mantissa.movePointLeft(1).setScale(decimals) }
        val sign = if (exponent < 0) "-" else if (match.groupValues[2] == "+") "+" else ""
        return mantissa.toPlainString() + "E" + sign + kotlin.math.abs(exponent).toString().padStart(match.groupValues[3].length, '0')
    }

    private fun dateText(value: BigDecimal, tokens: List<Token>): String? {
        if (value.signum() < 0 || value >= BigDecimal(2_958_466)) return null
        val elapsed = tokens.filter { !it.literal && it.value.startsWith('[') }
        if (elapsed.size > 1) return null
        val fields = tokens.filter { !it.literal && it.value.firstOrNull()?.let { c -> c in "ymdhs" } == true }
        if (elapsed.isNotEmpty() && fields.any { it.value.startsWith('y') || it.value.startsWith('d') }) return null
        val secondsField = tokens.indexOfLast { !it.literal && it.value in listOf("s", "ss") }
        var fractionalPlaces = 0
        if (secondsField >= 0 && tokens.getOrNull(secondsField + 1) == Token(".")) {
            fractionalPlaces = tokens.drop(secondsField + 2).takeWhile { it == Token("0") }.size
            if (fractionalPlaces !in 1..3) return null
        }
        val totalSeconds = value.multiply(BigDecimal(86400)).setScale(fractionalPlaces, RoundingMode.HALF_UP)
        val wholeSeconds = totalSeconds.toLong()
        val day = wholeSeconds / 86400
        val hour = (wholeSeconds / 3600 % 24).toInt()
        val minute = (wholeSeconds / 60 % 60).toInt()
        val second = (wholeSeconds % 60).toInt()
        val fakeLeapDay = !date1904 && day == 60L
        val zeroDay = !date1904 && day == 0L
        val date = if (date1904) LocalDate.of(1904, 1, 1).plusDays(day)
            else LocalDate.of(1899, 12, 31).plusDays(day - if (day >= 60) 1 else 0)
        if (date.year > 9999) return null
        val year = if (zeroDay) 1900 else date.year
        val month = if (zeroDay) 1 else if (fakeLeapDay) 2 else date.monthValue
        val dayOfMonth = if (zeroDay) 0 else if (fakeLeapDay) 29 else date.dayOfMonth
        val weekday = if (!date1904 && day < 60) date.minusDays(1).dayOfWeek else date.dayOfWeek
        val amPm = tokens.any { !it.literal && it.value == "AM/PM" }
        fun padded(n: Long, length: Int) = n.toString().padStart(length, '0')
        return buildString {
            var i = 0
            while (i < tokens.size) {
                val t = tokens[i]
                val v = t.value
                if (t.literal) append(v)
                else when {
                    v == "AM/PM" -> append(if (hour < 12) "AM" else "PM")
                    v.startsWith('[') -> {
                        val unit = v.substring(1, v.length - 1)
                        val count = when (unit.first()) { 'h' -> wholeSeconds / 3600; 'm' -> wholeSeconds / 60; else -> wholeSeconds }
                        append(padded(count, unit.length))
                    }
                    v == "." && i == secondsField + 1 && fractionalPlaces > 0 -> {
                        append('.'); append(totalSeconds.remainder(BigDecimal.ONE).movePointRight(fractionalPlaces).toInt().toString().padStart(fractionalPlaces, '0'))
                        i += fractionalPlaces
                    }
                    v in listOf("-", "+", ",", ".") -> append(v)
                    v in listOf("yy", "yyyy") -> append(padded(if (v == "yy") (year % 100).toLong() else year.toLong(), v.length))
                    v in listOf("d", "dd") -> append(padded(dayOfMonth.toLong(), v.length))
                    v == "ddd" || v == "dddd" -> append(weekday.getDisplayName(if (v == "ddd") TextStyle.SHORT else TextStyle.FULL, Locale.US))
                    v in listOf("h", "hh") -> append(padded((if (amPm) (hour + 11) % 12 + 1 else hour).toLong(), v.length))
                    v in listOf("s", "ss") -> append(padded(second.toLong(), v.length))
                    v.firstOrNull() == 'm' && v.length in 1..5 -> {
                        fun isTimeField(token: Token) = !token.literal && (token.value.startsWith('[') || token.value.firstOrNull()?.let { it in "ymdhs" } == true)
                        val previous = tokens.take(i).lastOrNull(::isTimeField)
                        val next = tokens.drop(i + 1).firstOrNull(::isTimeField)
                        val isMinute = v.length <= 2 && (previous?.value?.trim('[', ']')?.startsWith('h') == true || next?.value?.startsWith('s') == true)
                        if (isMinute) append(padded(minute.toLong(), v.length))
                        else if (v.length <= 2) append(padded(month.toLong(), v.length))
                        else {
                            val name = java.time.Month.of(month).getDisplayName(if (v.length == 3) TextStyle.SHORT else TextStyle.FULL, Locale.US)
                            append(if (v.length == 5) name.take(1) else name)
                        }
                    }
                    else -> return null
                }
                i++
            }
        }
    }

    companion object {
        private val COLORS = mapOf("black" to 0x000000L, "blue" to 0x0000FFL, "cyan" to 0x00FFFFL,
            "green" to 0x008000L, "magenta" to 0xFF00FFL, "red" to 0xFF0000L, "white" to 0xFFFFFFL, "yellow" to 0xFFFF00L)
        private val BUILT_INS = mapOf(0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
            9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??",
            14 to "mm-dd-yy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy", 18 to "h:mm AM/PM",
            19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss", 22 to "m/d/yy h:mm",
            37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;[Red](#,##0)", 39 to "#,##0.00;(#,##0.00)",
            40 to "#,##0.00;[Red](#,##0.00)", 45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mmss.0", 48 to "##0.0E+0", 49 to "@")
        fun builtIn(id: Int): String? = BUILT_INS[id]
    }
}
