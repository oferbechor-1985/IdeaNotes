package com.ofer.ideanotes

/**
 * ממיר מספרים שנכתבו במילים בעברית לספרות, לפני הניתוח: "חמש" -> 5, "עשרים וחמש" -> 25,
 * "בשלוש" -> "ב-3". כך גם הכתבה שמתומללת למילים ("שלושים שקל", "בעוד חמש דקות") מזוהה.
 * מילים דו-משמעיות (אחד, שני, שתי) מומרות רק כשההקשר ברור.
 */
object HebrewNumbers {

    private enum class K { UNIT, TEEN, TENS, HUND, HUNDM, THOU, THOUM }
    private data class W(val kind: K, val v: Int)

    private val words: Map<String, W> = buildMap {
        fun u(v: Int, vararg ws: String) = ws.forEach { put(it, W(K.UNIT, v)) }
        u(1, "אחד", "אחת"); u(2, "שניים", "שתיים", "שתים"); u(3, "שלוש", "שלושה")
        u(4, "ארבע", "ארבעה"); u(5, "חמש", "חמישה"); u(6, "שש", "שישה")
        u(7, "שבע", "שבעה"); u(8, "שמונה"); u(9, "תשע", "תשעה")
        put("עשר", W(K.TEEN, 10)); put("עשרה", W(K.TEEN, 10))
        mapOf("עשרים" to 20, "שלושים" to 30, "ארבעים" to 40, "חמישים" to 50,
            "שישים" to 60, "שבעים" to 70, "שמונים" to 80, "תשעים" to 90)
            .forEach { (k, v) -> put(k, W(K.TENS, v)) }
        put("מאה", W(K.HUND, 100)); put("מאתיים", W(K.HUND, 200)); put("מאות", W(K.HUNDM, 100))
        put("אלף", W(K.THOU, 1000)); put("אלפיים", W(K.THOU, 2000)); put("אלפים", W(K.THOUM, 1000))
    }

    /** "שני"/"שתי" (גם יום שני, גם 2): מומרים רק לפני יחידת מידה או מילת כפל. */
    private val construct = setOf("שני", "שתי", "שנים")
    private val afterConstruct = setOf(
        "דקות", "שעות", "ימים", "שבועות", "חודשים", "שקל", "שקלים", "דולר", "דולרים",
        "אירו", "יורו", "פאונד", "פאונדים", "מאות", "אלפים"
    )
    private val prefixes = setOf('ב', 'ל', 'כ')

    private data class Tok(val range: IntRange, val prefix: String, val word: String, val w: W?)

    private fun classify(raw: String, range: IntRange): Tok {
        words[raw]?.let { return Tok(range, "", raw, it) }
        if (raw.length > 1) {
            val rest = raw.substring(1)
            val pre = raw[0]
            if (pre == 'ו' || pre in prefixes) {
                words[rest]?.let { return Tok(range, pre.toString(), rest, it) }
                if (rest in construct) return Tok(range, pre.toString(), rest, null)
            }
        }
        return Tok(range, "", raw, null)
    }

    fun normalize(text: String): String {
        val toks = Regex("[\\p{L}]+").findAll(text).map { classify(it.value, it.range) }.toList()
        if (toks.none { it.w != null || it.word in construct }) return text

        val sb = StringBuilder()
        var pos = 0
        var i = 0
        while (i < toks.size) {
            val first = toks[i]
            var w0 = first.w
            val isConstruct = w0 == null && first.word in construct && first.prefix != "ו"
            if (isConstruct) {
                val nxt = toks.getOrNull(i + 1)
                val teenOnly = first.word == "שנים" // "שנים עשר" = 12, אבל "שנים" לבד = years
                val nextOk = nxt != null && gapIsSpace(text, first.range, nxt.range) && nxt.prefix == "" && (
                    if (teenOnly) nxt.w?.kind == K.TEEN
                    else nxt.word in afterConstruct || nxt.w?.kind == K.UNIT || nxt.w?.kind == K.TEEN
                )
                if (!nextOk) { i++; continue }
                w0 = W(K.UNIT, 2)
            }
            if (w0 == null || first.prefix == "ו" || w0.kind == K.THOUM) { i++; continue }
            // אחד/אחת לבד: רק עם ב/ל/כ (באחת); עם שותפים ("עשרים ואחד") זה בסדר
            val soloOne = w0.kind == K.UNIT && w0.v == 1 && first.prefix.isEmpty()

            var total = 0
            var cur = 0
            var lastUnit = 0
            var prev = w0.kind
            when (w0.kind) {
                K.UNIT -> { cur = w0.v; lastUnit = w0.v }
                else -> cur = w0.v
            }
            if (w0.kind == K.THOU) { total = w0.v; cur = 0 }
            var j = i
            while (true) {
                val t = toks.getOrNull(j + 1) ?: break
                val w = t.w ?: break
                if (!gapIsSpace(text, toks[j].range, t.range)) break
                val conn = t.prefix == "ו"
                if (t.prefix.isNotEmpty() && !conn) break
                // "ו" מחבר רק עשרות/מאות/אלפים ליחידות שאחריהם ("עשרים ואחד"), לא שני מספרים נפרדים
                val connOk = conn && (
                    (prev == K.TENS && w.kind == K.UNIT) ||
                    (prev == K.HUND && (w.kind == K.TENS || w.kind == K.UNIT || w.kind == K.TEEN)) ||
                    ((prev == K.THOU || prev == K.THOUM) && (w.kind == K.HUND || w.kind == K.TENS || w.kind == K.UNIT))
                )
                if (conn && !connOk) break
                val ok = connOk ||
                    (prev == K.UNIT && (w.kind == K.TEEN || w.kind == K.HUNDM || w.kind == K.THOUM || w.kind == K.THOU)) ||
                    ((prev == K.HUND || prev == K.THOU || prev == K.THOUM) &&
                        (w.kind == K.TENS || w.kind == K.UNIT || w.kind == K.HUND || w.kind == K.TEEN)) ||
                    (prev == K.TENS && w.kind == K.THOU) || (prev == K.HUND && w.kind == K.THOU)
                if (!ok) break
                when (w.kind) {
                    K.UNIT -> { cur += w.v; lastUnit = w.v }
                    K.TEEN -> cur += 10
                    K.TENS, K.HUND -> cur += w.v
                    K.HUNDM -> { cur = cur - lastUnit + lastUnit * 100; lastUnit = 0 }
                    K.THOU, K.THOUM -> {
                        if (w.v == 2000) { total += 2000 } else if (cur > 0 && prev != K.THOU && prev != K.THOUM) { total += cur * 1000; cur = 0 } else total += 1000
                    }
                }
                prev = w.kind
                j++
            }
            if (soloOne && j == i) { i++; continue }
            val n = total + cur
            val last = toks[j]
            sb.append(text, pos, first.range.first)
            sb.append(first.prefix)
            if (first.prefix.isNotEmpty()) sb.append('-')
            sb.append(n)
            pos = last.range.last + 1
            i = j + 1
        }
        sb.append(text, pos, text.length)
        return sb.toString()
    }

    private fun gapIsSpace(text: String, a: IntRange, b: IntRange): Boolean {
        val gap = text.substring(a.last + 1, b.first)
        return gap.isNotEmpty() && gap.all { it == ' ' || it == '\t' }
    }
}
