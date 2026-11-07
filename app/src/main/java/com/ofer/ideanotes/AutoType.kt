package com.ofer.ideanotes

/**
 * סיווג אוטומטי לפי המילה הראשונה בטקסט, למשל "אירוע, מחר יומולדת לניסים".
 * המילה (עם הפסיק/נקודתיים שאחריה) מוסרת, ומה שנשאר הוא תוכן ההערה.
 */
object AutoType {

    data class Detected(val type: String, val rest: String)

    private val words = mapOf(
        "הערה" to Store.TYPE_NOTE,
        "הוצאה" to Store.TYPE_EXPENSE,
        "אירוע" to Store.TYPE_EVENT,
        "התראה" to Store.TYPE_ALARM,
        "שיפור" to Store.TYPE_FEEDBACK
    )

    // אחרי המילה חייב לבוא מפריד (פסיק, נקודתיים, מקף, רווח) או סוף הטקסט
    private val sep = Regex("^[\\s,:;.\\-–—]+")

    fun detect(raw: String): Detected? {
        val t = raw.trim()
        val m = Regex("^\\p{L}+").find(t) ?: return null
        val type = words[m.value] ?: return null
        val after = t.substring(m.value.length)
        if (after.isNotEmpty() && !sep.containsMatchIn(after)) return null
        return Detected(type, after.replace(sep, "").trim())
    }
}
