package com.ofer.ideanotes

/**
 * זיהוי אוטומטי של הוצאה מטקסט חופשי: סכום, מטבע ותיאור.
 *   "30 שקל עבור שוארמה"      -> 30 ILS, "שוארמה"
 *   "10 דולר עבור שוארמה"     -> 10 USD, "שוארמה"
 *   "קפה $4.5" / "שילמתי ב-12 אירו על ארוחה"
 * ברירת המחדל היא שקלים. אין כאן תלות באנדרואיד, כדי שאפשר יהיה לבדוק את זה בנפרד.
 */
object Expense {

    data class Parsed(val amount: Double, val currency: String, val description: String)

    /** מילה/סימן -> קוד מטבע. */
    private val currencies: Map<String, String> = mapOf(
        "₪" to "ILS", "שקל" to "ILS", "שקלים" to "ILS", "ש\"ח" to "ILS", "שח" to "ILS",
        "nis" to "ILS", "ils" to "ILS", "shekel" to "ILS", "shekels" to "ILS",
        "$" to "USD", "דולר" to "USD", "דולרים" to "USD", "usd" to "USD", "dollar" to "USD", "dollars" to "USD",
        "€" to "EUR", "אירו" to "EUR", "יורו" to "EUR", "eur" to "EUR", "euro" to "EUR", "euros" to "EUR",
        "£" to "GBP", "פאונד" to "GBP", "פאונדים" to "GBP", "ליש\"ט" to "GBP", "gbp" to "GBP",
        "pound" to "GBP", "pounds" to "GBP"
    )

    private val symbolAlt = "₪|\\$|€|£"
    private val wordAlt = currencies.keys
        .filter { it.first().isLetter() }
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }

    // מספר: 1,250.50 | 30 | 4.5 | 4,5   (לפני מספר אפשר ב- / ב צמוד)
    private const val NUM = "(?:ב-?)?(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:[.,]\\d+)?)"
    private val CUR = "(?:($symbolAlt)|(?<![\\p{L}])($wordAlt)(?![\\p{L}]))"

    private val numThenCur = Regex("(?<![\\p{L}\\d])$NUM\\s*$CUR", RegexOption.IGNORE_CASE)
    private val curThenNum = Regex("$CUR\\s*(?:ב-?)?(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:[.,]\\d+)?)", RegexOption.IGNORE_CASE)
    private val plainNum = Regex("(?<![\\p{L}\\d])$NUM")

    private val leadingWords = setOf("שילמתי", "הוצאתי", "שילמנו", "עבור", "על", "בשביל", "for", "on", "paid", "spent")

    private fun toNumber(s: String): Double? {
        var t = s
        t = if (Regex("\\d{1,3}(,\\d{3})+(\\.\\d+)?").matches(t)) t.replace(",", "") else t.replace(',', '.')
        return t.toDoubleOrNull()
    }

    private fun code(symbolOrWord: String): String =
        currencies[symbolOrWord] ?: currencies[symbolOrWord.lowercase()] ?: "ILS"

    fun parse(raw: String): Parsed? {
        val text = raw.replace('״', '"').replace('’', '\'').trim()
        if (text.isEmpty()) return null

        val span: IntRange
        val value: Double
        var cur = "ILS"

        // 1) מספר עם מטבע צמוד (30 שקל / 10$ / $10) - הראשון בטקסט
        val a = numThenCur.find(text)
        val b = curThenNum.find(text)
        val hit = listOfNotNull(a, b).minByOrNull { it.range.first }
        if (hit != null) {
            val g = hit.groupValues
            if (hit === a) {
                value = toNumber(g[1]) ?: return null
                cur = code(g[2].ifEmpty { g[3] })
            } else {
                cur = code(g[1].ifEmpty { g[2] })
                value = toNumber(g[3]) ?: return null
            }
            span = hit.range
        } else {
            // 2) בלי מטבע: המספר האחרון בטקסט, בשקלים
            val last = plainNum.findAll(text).lastOrNull() ?: return null
            value = toNumber(last.groupValues[1]) ?: return null
            span = last.range
        }
        if (value <= 0.0) return null

        // תיאור = הטקסט בלי הסכום (כולל חזרות זהות של אותו סכום, למשל הכתבה כפולה)
        val removed = text.substring(span)
        var desc = text.replace(removed, " ")
        desc = desc.replace(Regex("\\s+"), " ").trim().trim(',', '.', ':', ';', '-', '–', '—', ' ')

        // הכתבה כפולה: "X X" -> "X"
        val words = desc.split(" ").filter { it.isNotEmpty() }
        if (words.size >= 2 && words.size % 2 == 0) {
            val h = words.size / 2
            if (words.subList(0, h) == words.subList(h, words.size)) desc = words.subList(0, h).joinToString(" ")
        }

        // הסרת מילות קישור בתחילת התיאור (עבור / על / שילמתי ...) ובסופו
        var parts = desc.split(" ").filter { it.isNotEmpty() }.toMutableList()
        while (parts.isNotEmpty() && parts.first().lowercase() in leadingWords) parts.removeAt(0)
        while (parts.isNotEmpty() && parts.last().lowercase() in leadingWords) parts.removeAt(parts.size - 1)
        desc = parts.joinToString(" ").trim(',', '.', ':', ';', '-', '–', '—', ' ')

        return Parsed(Math.round(value * 100) / 100.0, cur, desc)
    }
}
