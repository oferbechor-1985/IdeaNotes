package com.ofer.ideanotes

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Store {

    const val TYPE_NOTE = "note"
    const val TYPE_EXPENSE = "expense"
    const val TYPE_FEEDBACK = "feedback"
    const val TYPE_EVENT = "event"
    const val TYPE_ALARM = "alarm"

    /** פרטי אירוע: התחלה וסיום כטקסט (תאריך בלבד לאירוע של יום שלם) והאם נוסף ליומן. */
    data class EventInfo(val start: String, val end: String, val allDay: Boolean, val inCalendar: Boolean)

    data class Note(
        val file: File,
        val date: String,
        val type: String,
        val amount: Double?,
        val tags: List<String>,
        val audio: File?,
        val body: String,
        // הוצאה במטבע זר: המטבע, הסכום המקורי והשער ששימש להמרה לשקלים
        val origCurrency: String? = null,
        val origAmount: Double? = null,
        val rate: Double? = null,
        val event: EventInfo? = null,
        val alarm: String? = null,
        // הערת שיפור: האם נשלחה אוטומטית (null = לא רלוונטי)
        val sent: Boolean? = null
    ) {
        fun isForeign(): Boolean = origCurrency != null && origCurrency != "ILS"

        fun foreignText(): String =
            if (isForeign()) String.format(Locale.US, "%.2f %s", origAmount ?: 0.0, origCurrency) else ""

        fun summary(): String {
            val mic = if (audio != null) "  🎙" else ""
            val first = body.lineSequence().firstOrNull().orEmpty().take(80)
            val tagLine = tags.joinToString(" ") { "#$it" }
            val lines = when (type) {
                TYPE_EXPENSE -> listOf(
                    "💸 " + money(amount ?: 0.0) + (if (isForeign()) " (${foreignText()})" else "") +
                        "  ·  " + date + mic,
                    first, tagLine
                )
                TYPE_FEEDBACK -> listOf(
                    "🛠 שיפור  ·  " + date + mic + when (sent) { true -> "  ✓ נשלח"; false -> "  ⏳ ממתין לשליחה"; else -> "" },
                    first
                )
                TYPE_ALARM -> listOf("⏰ " + (alarm ?: date) + mic, first)
                TYPE_EVENT -> listOf(
                    "📅 " + (event?.start ?: date) + (if (event?.inCalendar == false) "  (לא נוסף ליומן)" else "") + mic,
                    first
                )
                else -> listOf(date + mic, tagLine, first)
            }
            return lines.filter { it.isNotBlank() }.joinToString("\n")
        }
    }

    fun money(v: Double): String = "₪" + String.format(Locale.US, "%,.2f", v)

    /** תיקייה פרטית של האפליקציה: Android/data/<package>/files/notes
     *  אפליקציות אחרות לא יכולות לקרוא אותה, ואפשר לגשת אליה מהמחשב בכבל USB. */
    fun dir(c: Context): File {
        val d = File(c.getExternalFilesDir(null) ?: c.filesDir, "notes")
        d.mkdirs()
        return d
    }

    fun newBase(): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

    fun save(
        c: Context,
        text: String,
        audio: File?,
        type: String = TYPE_NOTE,
        amount: Double? = null,
        origCurrency: String? = null,
        origAmount: Double? = null,
        rate: Double? = null,
        event: EventInfo? = null,
        alarm: String? = null
    ): File {
        val base = audio?.nameWithoutExtension ?: newBase()
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val tags = Tags.extract(text)
        val sb = StringBuilder()
        sb.append("תאריך: ").append(date).append('\n')
        sb.append("סוג: ").append(type).append('\n')
        if (type == TYPE_EXPENSE && amount != null) {
            // סכום: תמיד בשקלים. במטבע זר נשמרים גם המקור והשער.
            sb.append("סכום: ").append(String.format(Locale.US, "%.2f", amount)).append('\n')
            if (origCurrency != null && origCurrency != "ILS" && origAmount != null && rate != null) {
                sb.append("מטבע מקור: ").append(origCurrency).append('\n')
                sb.append("סכום מקור: ").append(String.format(Locale.US, "%.2f", origAmount)).append('\n')
                sb.append("שער: ").append(String.format(Locale.US, "%.4f", rate)).append('\n')
            }
        }
        if (type == TYPE_ALARM && alarm != null) {
            sb.append("התראה: ").append(alarm).append('\n')
        }
        if (type == TYPE_EVENT && event != null) {
            sb.append("התחלה: ").append(event.start).append('\n')
            sb.append("סיום: ").append(event.end).append('\n')
            if (event.allDay) sb.append("כל היום: כן\n")
            sb.append("ביומן: ").append(if (event.inCalendar) "כן" else "לא").append('\n')
        }
        if (type == TYPE_FEEDBACK) sb.append("נשלח: לא\n")
        sb.append("תגיות: ").append(tags.joinToString(", ")).append('\n')
        if (audio != null) sb.append("שמע: ").append(audio.name).append('\n')
        sb.append("---\n").append(text)
        val f = File(dir(c), "$base.txt")
        f.writeText(sb.toString())
        if (type == TYPE_EXPENSE) writeExpensesCsv(c)
        return f
    }

    fun delete(c: Context, n: Note) {
        n.file.delete()
        n.audio?.delete()
        if (n.type == TYPE_EXPENSE) writeExpensesCsv(c)
    }

    fun list(c: Context): List<Note> =
        (dir(c).listFiles { f -> f.extension == "txt" } ?: emptyArray())
            .sortedByDescending { it.name }
            .map { parse(it) }

    private fun parse(f: File): Note {
        val raw = f.readText()
        val parts = raw.split("\n---\n", limit = 2)
        val header = if (parts.size == 2) parts[0] else ""
        val body = if (parts.size == 2) parts[1] else raw
        var date = f.nameWithoutExtension
        var type = TYPE_NOTE
        var amount: Double? = null
        var origCurrency: String? = null
        var origAmount: Double? = null
        var rate: Double? = null
        var evStart: String? = null
        var evEnd = ""
        var evAll = false
        var evCal = false
        var alarmDesc: String? = null
        var sent: Boolean? = null
        var tags = emptyList<String>()
        var audio: File? = null
        for (line in header.lines()) {
            when {
                line.startsWith("תאריך: ") -> date = line.removePrefix("תאריך: ")
                line.startsWith("סוג: ") -> type = line.removePrefix("סוג: ").trim()
                line.startsWith("סכום: ") -> amount = line.removePrefix("סכום: ").trim().toDoubleOrNull()
                line.startsWith("מטבע מקור: ") -> origCurrency = line.removePrefix("מטבע מקור: ").trim()
                line.startsWith("סכום מקור: ") -> origAmount = line.removePrefix("סכום מקור: ").trim().toDoubleOrNull()
                line.startsWith("שער: ") -> rate = line.removePrefix("שער: ").trim().toDoubleOrNull()
                line.startsWith("התחלה: ") -> evStart = line.removePrefix("התחלה: ").trim()
                line.startsWith("סיום: ") -> evEnd = line.removePrefix("סיום: ").trim()
                line.startsWith("כל היום: ") -> evAll = true
                line.startsWith("ביומן: ") -> evCal = line.removePrefix("ביומן: ").trim() == "כן"
                line.startsWith("התראה: ") -> alarmDesc = line.removePrefix("התראה: ").trim()
                line.startsWith("נשלח: ") -> sent = line.removePrefix("נשלח: ").trim() == "כן"
                line.startsWith("תגיות: ") -> tags = line.removePrefix("תגיות: ")
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                line.startsWith("שמע: ") -> {
                    val a = File(f.parentFile, line.removePrefix("שמע: ").trim())
                    if (a.exists()) audio = a
                }
            }
        }
        val start = evStart
        val event = if (start != null) EventInfo(start, evEnd, evAll, evCal) else null
        return Note(f, date, type, amount, tags, audio, body, origCurrency, origAmount, rate, event, alarmDesc, sent)
    }

    /** הערות שיפור עם טקסט שעוד לא נשלחו (ישנות קודם). */
    fun pending(c: Context): List<Note> =
        list(c).filter { it.type == TYPE_FEEDBACK && it.sent == false && it.body.isNotBlank() }
            .sortedBy { it.date }

    /** מסמן הערת שיפור כנשלחה, בכותרת הקובץ בלבד (לא נוגע בגוף ההערה). */
    fun markSent(file: File) {
        val raw = file.readText()
        val i = raw.indexOf("\n---\n")
        if (i < 0) return
        val header = raw.substring(0, i).replace("נשלח: לא", "נשלח: כן")
        file.writeText(header + raw.substring(i))
    }

    /** טבלת הוצאות: expenses.csv בתוך תיקיית ההערות. נפתחת באקסל / Google Sheets.
     *  מתעדכנת אוטומטית בכל הוספה או מחיקה של הוצאה. */
    fun writeExpensesCsv(c: Context): File {
        val rows = list(c).filter { it.type == TYPE_EXPENSE }.sortedBy { it.date }
        val sb = StringBuilder("\uFEFF") // BOM כדי שאקסל יציג עברית נכון
        sb.append("תאריך,סכום (₪),פירוט,מטבע מקור,סכום מקור,שער,תגיות\r\n")
        for (r in rows) {
            val ils = r.amount ?: 0.0
            val foreign = r.isForeign()
            sb.append(r.date).append(',')
                .append(String.format(Locale.US, "%.2f", ils)).append(',')
                .append(csv(r.body.replace("\n", " "))).append(',')
                .append(if (foreign) r.origCurrency else "ILS").append(',')
                .append(String.format(Locale.US, "%.2f", if (foreign) (r.origAmount ?: ils) else ils)).append(',')
                .append(if (foreign && r.rate != null) String.format(Locale.US, "%.4f", r.rate) else "1").append(',')
                .append(csv(r.tags.joinToString(" "))).append("\r\n")
        }
        val f = File(dir(c), "expenses.csv")
        f.writeText(sb.toString(), Charsets.UTF_8)
        return f
    }

    private fun csv(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

    /** כל הערות השיפור בטקסט אחד ממוספר, להדבקה בשיחה. */
    fun feedbackText(c: Context): String {
        val items = list(c).filter { it.type == TYPE_FEEDBACK }.sortedBy { it.date }
        val sb = StringBuilder("הערות שיפור לאפליקציית רעיונות (${items.size})\n\n")
        items.forEachIndexed { i, n ->
            val t = n.body.trim().ifEmpty { "(הקלטה בלבד, לא מתומללת)" }
            sb.append("${i + 1}. [${n.date}] ").append(t).append("\n\n")
        }
        return sb.toString().trimEnd()
    }
}

object Tags {
    private val stop = setOf(
        "של", "את", "על", "עם", "לא", "כל", "אבל", "או", "גם", "זה", "זו", "זאת", "הוא", "היא",
        "הם", "הן", "אני", "אנחנו", "אתה", "יש", "אין", "כי", "אם", "מה", "איך", "למה", "מאוד",
        "עוד", "רק", "כמו", "אחרי", "לפני", "היה", "להיות", "צריך", "אפשר", "שאני", "שזה", "ואז",
        "כך", "בין", "עד", "מן", "אל", "הזה", "הזאת", "אולי", "כבר", "פשוט", "וגם", "שלי", "שלו",
        "the", "and", "for", "that", "this", "with", "you", "are", "was", "not", "but", "have",
        "from", "they", "his", "her", "can", "will", "just", "about", "what", "when", "there"
    )

    /** תגיות = המילים הנפוצות/הארוכות ביותר בהערה, בלי מילות קישור. כאן נדייק בהמשך. */
    fun extract(text: String, max: Int = 5): List<String> {
        val words = Regex("[\\p{L}\\p{N}']{3,}").findAll(text.lowercase())
            .map { it.value }
            .filter { it !in stop }
            .toList()
        return words.groupingBy { it }.eachCount().entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { it.key.length }
            )
            .take(max)
            .map { it.key }
    }
}
