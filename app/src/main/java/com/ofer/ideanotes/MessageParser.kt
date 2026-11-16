package com.ofer.ideanotes

/**
 * ניתוח "הודעה לאמא, תבואי בשלוש": מי הנמען ומה ההודעה, התאמת שמות ופורמט מספר לווטסאפ.
 * (קוד טהור, בלי אנדרואיד, כדי שאפשר לבדוק אותו.)
 */
object MessageParser {

    data class Split(val name: String, val body: String)

    /** אפשרויות פיצול לשם + גוף. עם פסיק/נקודתיים: פיצול אחד. בלי (הכתבה): 1–3 מילים ראשונות כשם. */
    fun splits(raw: String): List<Split> {
        val t = raw.trim().replace(Regex("^אל\\s+(?=\\S)"), "") // "הודעה אל דנה"
        val m = Regex("^([^,:،]+)[,:،]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL).find(t)
        if (m != null) {
            val name = m.groupValues[1].trim()
            val body = m.groupValues[2].trim()
            if (name.isNotEmpty() && body.isNotEmpty()) return listOf(Split(name, body))
        }
        val tokens = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = mutableListOf<Split>()
        for (k in 1..3) {
            if (tokens.size > k) out.add(Split(tokens.take(k).joinToString(" "), tokens.drop(k).joinToString(" ")))
        }
        return out
    }

    /** "לאמא" -> ["לאמא", "אמא"]; "אל דנה" -> ["אל דנה", "דנה"] (כי יש שמות שמתחילים ב-ל, כמו לאה). */
    fun nameVariants(name: String): List<String> {
        val n = name.trim()
        val v = mutableListOf(n)
        when {
            n.startsWith("אל ") -> v.add(n.removePrefix("אל ").trim())
            n.startsWith("ל-") -> v.add(n.removePrefix("ל-").trim())
            n.startsWith("ל") && n.length > 2 -> v.add(n.substring(1))
        }
        return v.filter { it.isNotEmpty() }.distinct()
    }

    private val niqqud = Regex("[\\u0591-\\u05C7]")

    fun norm(s: String): String =
        s.replace(niqqud, "").replace(Regex("[\"'׳״\\-_]"), " ").lowercase().replace(Regex("\\s+"), " ").trim()

    /** 0 = שם זהה, 1 = אחת המילים בשם זהה לחיפוש, 2 = מכיל, null = לא מתאים. */
    fun rank(display: String, variants: List<String>): Int? {
        val d = norm(display)
        var best: Int? = null
        for (v in variants) {
            val nv = norm(v)
            if (nv.isEmpty()) continue
            val r = when {
                d == nv -> 0
                d.split(" ").contains(nv) -> 1
                d.contains(nv) -> 2
                else -> null
            }
            if (r != null && (best == null || r < best)) best = r
        }
        return best
    }

    /** מספר טלפון בפורמט בינלאומי בלי + (כמו שווטסאפ מצפה), ישראל כברירת מחדל: 054-1234567 -> 972541234567. */
    fun whatsappNumber(raw: String): String? {
        val t = raw.trim()
        val digits = t.filter { it.isDigit() }
        if (digits.length < 7) return null
        return when {
            t.startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            digits.startsWith("0") -> "972" + digits.drop(1)
            else -> digits
        }
    }
}
