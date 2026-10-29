package com.ofer.ideanotes

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Store {

    data class Note(
        val file: File,
        val date: String,
        val tags: List<String>,
        val audio: File?,
        val body: String
    ) {
        fun summary(): String {
            val mic = if (audio != null) "  🎙" else ""
            val tagLine = tags.joinToString(" ") { "#$it" }
            val first = body.lineSequence().firstOrNull().orEmpty().take(80)
            return listOf(date + mic, tagLine, first).filter { it.isNotBlank() }.joinToString("\n")
        }
    }

    /** תיקייה פרטית של האפליקציה: Android/data/<package>/files/notes
     *  אפליקציות אחרות לא יכולות לקרוא אותה, ואפשר לגשת אליה מהמחשב בכבל USB. */
    fun dir(c: Context): File {
        val d = File(c.getExternalFilesDir(null) ?: c.filesDir, "notes")
        d.mkdirs()
        return d
    }

    fun newBase(): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

    fun save(c: Context, text: String, audio: File?): File {
        val base = audio?.nameWithoutExtension ?: newBase()
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val tags = Tags.extract(text)
        val sb = StringBuilder()
        sb.append("תאריך: ").append(date).append('\n')
        sb.append("תגיות: ").append(tags.joinToString(", ")).append('\n')
        if (audio != null) sb.append("שמע: ").append(audio.name).append('\n')
        sb.append("---\n").append(text)
        val f = File(dir(c), "$base.txt")
        f.writeText(sb.toString())
        return f
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
        var tags = emptyList<String>()
        var audio: File? = null
        for (line in header.lines()) {
            when {
                line.startsWith("תאריך: ") -> date = line.removePrefix("תאריך: ")
                line.startsWith("תגיות: ") -> tags = line.removePrefix("תגיות: ")
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                line.startsWith("שמע: ") -> {
                    val a = File(f.parentFile, line.removePrefix("שמע: ").trim())
                    if (a.exists()) audio = a
                }
            }
        }
        return Note(f, date, tags, audio, body)
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
