package com.ofer.ideanotes

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * זיהוי אירוע מטקסט חופשי בעברית: תאריך, שעה ותיאור.
 *   "מחר ב-16:00 פגישה עם דנה"
 *   "ביום שלישי ב-10 בבוקר רופא שיניים"
 *   "12 בנובמבר בשעה 15:30 ארוחה עם ההורים"
 *   "בעוד שעתיים להתקשר לאמא"
 *   "מחרתיים ב-9:00 למשך שעתיים סדנה"
 * תאריך בלי שעה = אירוע של יום שלם. שעה בלי תאריך = היום (או מחר, אם השעה עברה).
 * אין כאן תלות באנדרואיד, כדי שאפשר יהיה לבדוק את זה בנפרד.
 */
object EventParser {

    data class Parsed(val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean, val title: String)

    private val months = listOf(
        "ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני",
        "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר"
    )
    private val monthAlt = (months + "מרס").joinToString("|")

    private fun monthIndex(name: String): Int = if (name == "מרס") 3 else months.indexOf(name) + 1

    private val weekdays = mapOf(
        "ראשון" to DayOfWeek.SUNDAY, "שני" to DayOfWeek.MONDAY, "שלישי" to DayOfWeek.TUESDAY,
        "רביעי" to DayOfWeek.WEDNESDAY, "חמישי" to DayOfWeek.THURSDAY, "שישי" to DayOfWeek.FRIDAY,
        "שבת" to DayOfWeek.SATURDAY
    )

    private val durationRe = Regex(
        "(?<![\\p{L}])למשך\\s+(?:(\\d+)\\s*(שעות|שעה|דקות|דקה)|חצי\\s+שעה|שעה\\s+וחצי|שעתיים|שעה)"
    )
    private val relativeRe = Regex(
        "(?<![\\p{L}])ב?עוד\\s+(?:(\\d+)\\s*(דקות|דקה|שעות|שעה|ימים|יום|שבועות|שבוע)|חצי\\s+שעה|שעתיים|שעה|יומיים|יום|שבועיים|שבוע)"
    )
    private val keywordDateRe = Regex("(?<![\\p{L}])ל?(מחרתיים|מחר|היום)(?![\\p{L}])")
    private val weekdayRe = Regex(
        "(?<![\\p{L}])(?:ביום|יום|ב(?=שבת))\\s*(ראשון|שני|שלישי|רביעי|חמישי|שישי|שבת)(?:\\s+(?:הבא|הקרוב))?(?![\\p{L}])"
    )
    private val explicitDateRe = Regex(
        "(?<![\\p{L}\\d])(?:[בלה]-?)?(\\d{1,2})\\s*[בל]?-?\\s*($monthAlt)(?:\\s+(\\d{4}))?(?![\\p{L}])"
    )
    private val numericDateRe = Regex(
        "(?<![\\p{L}\\d:])(?:[בלה]-?)?(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?(?![\\d:])"
    )
    private val colonTimeRe = Regex("(?<![\\p{L}\\d])(?:(?:בשעה|שעה|ב-?)\\s*)?(\\d{1,2}):(\\d{2})(?!\\d)")
    private val quarterToRe = Regex("(?<![\\p{L}])רבע\\s+ל-?(\\d{1,2})(?![\\d:])")
    private val hourRe = Regex("(?<![\\p{L}\\d])(?:בשעה|שעה|ב-?)\\s*(\\d{1,2})(?![\\d:./])(?:\\s*(וחצי|ורבע))?")
    private val periodRe = Regex(
        "(?<![\\p{L}])(לפנות\\s+בוקר|בבוקר|אחר\\s+הצהריים|אחרי\\s+הצהריים|אחה\"צ|בצהריים|בערב|בלילה)(?![\\p{L}])"
    )

    private val filler = setOf(
        "ב", "ל", "ה", "בשעה", "שעה", "ביום", "יום", "קבע", "קבעי", "תקבע", "תקבעי", "לי",
        "תוסיף", "תוסיפי", "הוסף", "הוסיפי", "ליומן", "ביומן", "אירוע", "את", "הבא", "הקרוב",
        "ב-", "ל-", "ה-", "-", "–", "—", ","
    )

    private fun periodKey(s: String) = s.replace(Regex("\\s+"), " ")

    /** שעה לפי מילת זמן (בבוקר / בערב ...). בלי מילה: 1-6 נחשב אחה"צ, 7-11 בוקר. */
    private fun adjustHour(h: Int, leadingZero: Boolean, period: String?): Int = when {
        h >= 13 -> h
        period == "בבוקר" || period == "לפנות בוקר" -> if (h == 12) 0 else h
        period == "בצהריים" -> if (h in 1..5) h + 12 else h
        period == "אחר הצהריים" || period == "אחרי הצהריים" || period == "אחה\"צ" -> if (h < 12) h + 12 else h
        period == "בערב" -> if (h < 12) h + 12 else h
        period == "בלילה" -> if (h in 6..11) h + 12 else if (h == 12) 0 else h
        leadingZero || h == 0 -> h
        h in 1..6 -> h + 12
        else -> h
    }

    private fun defaultTimeFor(period: String): LocalTime = when (period) {
        "בבוקר", "לפנות בוקר" -> LocalTime.of(9, 0)
        "בצהריים" -> LocalTime.of(12, 0)
        "אחר הצהריים", "אחרי הצהריים", "אחה\"צ" -> LocalTime.of(16, 0)
        "בערב" -> LocalTime.of(19, 0)
        else -> LocalTime.of(22, 0) // בלילה
    }

    fun parse(raw: String, now: LocalDateTime): Parsed? {
        var t = HebrewNumbers.normalize(raw).replace('״', '"').replace('”', '"').replace('“', '"').trim()
        if (t.isEmpty()) return null
        val today = now.toLocalDate()

        var date: LocalDate? = null
        var time: LocalTime? = null
        var duration: Int? = null
        var relativeStart: LocalDateTime? = null

        fun consume(m: MatchResult) { t = t.replace(m.value, " ") }

        // 1) משך: "למשך שעתיים"
        durationRe.find(t)?.let { m ->
            val num = m.groupValues[1]
            duration = if (num.isNotEmpty()) {
                val n = num.toInt()
                if (m.groupValues[2].startsWith("שע")) n * 60 else n
            } else {
                val s = m.value.replace(Regex("\\s+"), " ")
                when {
                    "שעה וחצי" in s -> 90
                    "חצי שעה" in s -> 30
                    "שעתיים" in s -> 120
                    else -> 60
                }
            }
            consume(m)
        }

        // 2) יחסי: "בעוד שעתיים" / "עוד 3 ימים"
        relativeRe.find(t)?.let { m ->
            val num = m.groupValues[1]
            var minutes = 0L
            var days = 0L
            if (num.isNotEmpty()) {
                val n = num.toLong()
                val u = m.groupValues[2]
                when {
                    u.startsWith("דק") -> minutes = n
                    u.startsWith("שע") -> minutes = n * 60
                    u.startsWith("שבוע") -> days = n * 7
                    else -> days = n
                }
            } else {
                val s = m.value.replace(Regex("\\s+"), " ")
                when {
                    "חצי שעה" in s -> minutes = 30
                    "שעתיים" in s -> minutes = 120
                    "שעה" in s -> minutes = 60
                    "יומיים" in s -> days = 2
                    "שבועיים" in s -> days = 14
                    "שבוע" in s -> days = 7
                    else -> days = 1
                }
            }
            if (minutes > 0) relativeStart = now.withSecond(0).withNano(0).plusMinutes(minutes)
            else date = today.plusDays(days)
            consume(m)
        }

        // 3) תאריך: היום / מחר / מחרתיים
        if (date == null && relativeStart == null) {
            keywordDateRe.find(t)?.let { m ->
                date = when (m.groupValues[1]) {
                    "היום" -> today
                    "מחר" -> today.plusDays(1)
                    else -> today.plusDays(2)
                }
                consume(m)
            }
        }

        // 4) יום בשבוע: "ביום שלישי" -> המופע הבא (לא היום)
        if (date == null && relativeStart == null) {
            weekdayRe.find(t)?.let { m ->
                val target = weekdays[m.groupValues[1]]!!
                var delta = (target.value - today.dayOfWeek.value + 7) % 7
                if (delta == 0) delta = 7
                date = today.plusDays(delta.toLong())
                consume(m)
            }
        }

        // 5) תאריך מפורש: "12 בנובמבר" / "ה-20 לדצמבר 2027"
        if (date == null && relativeStart == null) {
            explicitDateRe.findAll(t).firstOrNull { m ->
                try {
                    LocalDate.of(2000, monthIndex(m.groupValues[2]), m.groupValues[1].toInt()); true
                } catch (e: DateTimeException) { false }
            }?.let { m ->
                val mo = monthIndex(m.groupValues[2])
                val d = m.groupValues[1].toInt()
                val y = m.groupValues[3]
                date = if (y.isNotEmpty()) LocalDate.of(y.toInt(), mo, d) else {
                    val c = LocalDate.of(today.year, mo, d)
                    if (c.isBefore(today)) c.plusYears(1) else c
                }
                consume(m)
            }
        }

        // 6) תאריך מספרי: 12.11 / 12/11/2026
        if (date == null && relativeStart == null) {
            numericDateRe.findAll(t).firstOrNull { m ->
                val d = m.groupValues[1].toInt()
                val mo = m.groupValues[2].toInt()
                try { LocalDate.of(2000, mo, d); true } catch (e: DateTimeException) { false }
            }?.let { m ->
                val d = m.groupValues[1].toInt()
                val mo = m.groupValues[2].toInt()
                val ys = m.groupValues[3]
                date = if (ys.isNotEmpty()) {
                    LocalDate.of(if (ys.length == 2) 2000 + ys.toInt() else ys.toInt(), mo, d)
                } else {
                    val c = LocalDate.of(today.year, mo, d)
                    if (c.isBefore(today)) c.plusYears(1) else c
                }
                consume(m)
            }
        }

        // 7) שעה (רק אם לא הוגדרה שעה יחסית)
        if (relativeStart == null) {
            val periodMatch = periodRe.find(t)
            val period = periodMatch?.let { periodKey(it.groupValues[1]) }
            var h: Int? = null
            var min = 0
            var zero = false
            var timeMatch: MatchResult? = null

            colonTimeRe.findAll(t).firstOrNull { m ->
                m.groupValues[1].toInt() <= 23 && m.groupValues[2].toInt() <= 59
            }?.let { m ->
                h = m.groupValues[1].toInt(); min = m.groupValues[2].toInt()
                zero = m.groupValues[1].length == 2 && m.groupValues[1].startsWith("0")
                timeMatch = m
            }
            if (h == null) {
                quarterToRe.find(t)?.let { m ->
                    val n = m.groupValues[1].toInt()
                    if (n in 1..24) { h = n - 1; min = 45; timeMatch = m }
                }
            }
            if (h == null) {
                hourRe.findAll(t).firstOrNull { it.groupValues[1].toInt() <= 23 }?.let { m ->
                    h = m.groupValues[1].toInt()
                    min = when (m.groupValues[2]) { "וחצי" -> 30; "ורבע" -> 15; else -> 0 }
                    zero = m.groupValues[1].length == 2 && m.groupValues[1].startsWith("0")
                    timeMatch = m
                }
            }
            val hh = h
            if (hh != null) {
                time = LocalTime.of(adjustHour(hh, zero, period).coerceIn(0, 23), min)
                timeMatch?.let { consume(it) }
            } else if (period != null) {
                time = defaultTimeFor(period)
            }
            periodMatch?.let { consume(it) }
        }

        // לא זוהה כלום
        if (relativeStart == null && date == null && time == null) return null

        val start: LocalDateTime
        val allDay: Boolean
        if (relativeStart != null) {
            start = relativeStart!!
            allDay = false
        } else {
            val tm: LocalTime? = time
            val d: LocalDate = date ?: if (tm != null && tm > now.toLocalTime()) today else today.plusDays(1)
            if (tm == null) {
                start = d.atStartOfDay()
                allDay = true
            } else {
                start = d.atTime(tm)
                allDay = false
            }
        }
        val end = if (allDay) start.plusDays(1) else start.plusMinutes((duration ?: 60).toLong())

        // תיאור = מה שנשאר, בלי מילות קישור
        var title = t.replace(Regex("\\s+"), " ").trim()
        val words = title.split(" ").filter { it.isNotEmpty() }
        if (words.size >= 2 && words.size % 2 == 0) {
            val half = words.size / 2
            if (words.subList(0, half) == words.subList(half, words.size)) {
                title = words.subList(0, half).joinToString(" ")
            }
        }
        val parts = title.split(" ").filter { it.isNotEmpty() }.toMutableList()
        while (parts.isNotEmpty() && parts.first() in filler) parts.removeAt(0)
        while (parts.isNotEmpty() && parts.last() in filler) parts.removeAt(parts.size - 1)
        title = parts.joinToString(" ").trim(',', '.', ':', ';', '-', '–', '—', ' ')

        return Parsed(start, end, allDay, title.ifEmpty { "אירוע" })
    }
}
