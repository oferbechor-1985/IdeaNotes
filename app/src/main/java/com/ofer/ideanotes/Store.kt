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

    data class Note(
        val file: File,
        val date: String,
        val type: String,
        val amount: Double?,
        val tags: List<String>,
        val audio: File?,
        val body: String
    ) {
        fun summary(): String {
            val mic = if (audio != null) "  🎙" else ""
            val first = body.lineSequence().firstOrNull().orEmpty().take(80)
            val tagLine = tags.joinToString(" ") { "#$it" }
            val lines = when (type) {
                TYPE_EXPENSE -> listOf("💸 " + money(amount ?: 0.0) + "  ·  " + date + mic, first, tagLine)
                TYPE_FEEDBACK -> listOf("🛠 שיפור  ·  " + date + mic, first)
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
        amount: Double? = null
    ): File {
        val base = audio?.nameWithoutExtension ?: newBase()
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val tags = Tags.extract(text)
        val sb = StringBuilder()
        sb.append("תאריך: ").append(date).append('\n')
        sb.append("סוג: ").append(type).append('\n')
        if (type == TYPE_EXPENSE && amount != null) {
            sb.append("סכום: ").append(String.format(Locale.US, "%.2f", amount)).append('\n')
        }
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
        var tags = emptyList<String>()
        var audio: File? = null
        for (line in header.lines()) {
            when {
                line.startsWith("תאריך: ") -> date = line.removePrefix("תאריך: ")
                line.startsWith("סוג: ") -> type = line.removePrefix("סוג: ").trim()
                line.startsWith("סכום: ") -> amount = line.removePrefix("סכום: ").trim().toDoubleOrNull()
                line.startsWith("תגיות: ") -> tags = line.removePrefix("תגיות: ")
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                line.startsWith("שמע: ") -> {
                    val a = File(f.parentFile, line.removePrefix("שמע: ").trim())
                    if (a.exists()) audio = a
                }
            }
        }
        return Note(f, date, type, amount, tags, audio, body)
    }

    /** טבלת הוצאות: expenses.csv בתוך תיקיית ההערות. נפתחת באקסל / Google Sheets.
     *  מתעדכנת אוטומטית בכל הוספה או מחיקה של הוצאה. */
    fun writeExpensesCsv(c: Context): File {
        val rows = list(c).filter { it.type == TYPE_EXPENSE }.sortedBy { it.date }
        val sb = StringBuilder("\uFEFF") // BOM כדי שאקסל יציג עברית נכון
        sb.append("תאריך,סכום,פירוט,תגיות\r\n")
        for (r in rows) {
            sb.append(r.date).append(',')
                .append(String.format(Locale.US, "%.2f", r.amount ?: 0.0)).append(',')
                .append(csv(r.body.replace("\n", " "))).append(',')
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
