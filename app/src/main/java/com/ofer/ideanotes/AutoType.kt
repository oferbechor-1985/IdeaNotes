package com.ofer.ideanotes

/**
 * סיווג אוטומטי לפי המילה הראשונה בטקסט, למשל "אירוע, מחר יומולדת לניסים".
 * המילה (עם הפסיק/נקודתיים שאחריה) מוסרת, ומה שנשאר הוא תוכן ההערה.
 */
object AutoType {

    /** חיפוש בגוגל: לא סוג שנשמר, רק קיצור שפותח את הדפדפן. */
    const val TYPE_SEARCH = "search"

    /** הודעה בווטסאפ לאיש קשר: גם לא נשמרת כהערה. */
    const val TYPE_MESSAGE = "message"

    data class Detected(val type: String, val rest: String)

    private val saveCmd = Regex("^(.*?)(?:^|[\\s,.\\-])(?:שמור|תשמור)[\\s.!]*$", RegexOption.DOT_MATCHES_ALL)

    /** אם ההכתבה מסתיימת במילה "שמור": מחזיר את הטקסט בלעדיה (אפשר ריק). אחרת null. */
    fun stripSaveCommand(spoken: String): String? {
        val m = saveCmd.find(spoken.trim()) ?: return null
        return m.groupValues[1].trim().trimEnd(',', '.', '-', ' ')
    }

    private val words = mapOf(
        "הערה" to Store.TYPE_NOTE,
        "הוצאה" to Store.TYPE_EXPENSE,
        "אירוע" to Store.TYPE_EVENT,
        "התראה" to Store.TYPE_ALARM,
        "שיפור" to Store.TYPE_FEEDBACK,
        "חיפוש" to TYPE_SEARCH,
        "הודעה" to TYPE_MESSAGE
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
