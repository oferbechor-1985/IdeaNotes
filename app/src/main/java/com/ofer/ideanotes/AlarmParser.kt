package com.ofer.ideanotes

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * זיהוי התראה לשעון המעורר של הטלפון, מטקסט חופשי בעברית:
 *   "מחר ב-8 בבוקר להתקשר לרופא"        -> חד-פעמית
 *   "בעוד שעתיים לבדוק את התנור"          -> חד-פעמית
 *   "כל יום ב-9 בבוקר לקחת תרופה"         -> כל יום
 *   "כל יום ראשון וחמישי ב-18:00 זבל"     -> ימים קבועים
 *   "בימי חול ב-7:00 לקום"                -> ראשון עד חמישי
 * מגבלות שעון מעורר באנדרואיד: חד-פעמית עד 24 שעות קדימה בלבד, וחזרה רק לפי ימי שבוע
 * (לא חודשית או שנתית). את השעה והתיאור מזהה EventParser.
 * אין כאן תלות באנדרואיד, כדי שאפשר יהיה לבדוק את זה בנפרד.
 */
object AlarmParser {

    /** days = null: חד-פעמית (once = מתי תצלצל). אחרת: חוזרת בימים האלה. */
    data class Alarm(val time: LocalTime, val days: Set<DayOfWeek>?, val message: String, val once: LocalDateTime?) {
        fun describe(): String {
            val hm = String.format("%02d:%02d", time.hour, time.minute)
            val d = days
            return if (d == null) {
                "חד-פעמית " + (once?.toLocalDate()?.toString() ?: "") + " " + hm
            } else if (d.size == 7) {
                "כל יום $hm"
            } else {
                "ימים " + ORDER.filter { it in d }.joinToString(" ") { LETTER.getValue(it) } + " " + hm
            }
        }
    }

    data class Result(val alarm: Alarm?, val error: String?)

    // סדר ימי השבוע בישראל: ראשון ראשון
    val ORDER = listOf(
        DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY
    )
    val LETTER = mapOf(
        DayOfWeek.SUNDAY to "א׳", DayOfWeek.MONDAY to "ב׳", DayOfWeek.TUESDAY to "ג׳",
        DayOfWeek.WEDNESDAY to "ד׳", DayOfWeek.THURSDAY to "ה׳", DayOfWeek.FRIDAY to "ו׳",
        DayOfWeek.SATURDAY to "ש׳"
    )
    private val names = mapOf(
        "ראשון" to DayOfWeek.SUNDAY, "שני" to DayOfWeek.MONDAY, "שלישי" to DayOfWeek.TUESDAY,
        "רביעי" to DayOfWeek.WEDNESDAY, "חמישי" to DayOfWeek.THURSDAY, "שישי" to DayOfWeek.FRIDAY,
        "שבת" to DayOfWeek.SATURDAY
    )
    private val workdays = setOf(
        DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY
    )

    private const val DAY = "(?:ראשון|שני|שלישי|רביעי|חמישי|שישי|שבת)"
    private val unsupportedRe = Regex(
        "(?<![\\p{L}])(?:בכל|כל)\\s+(?:חודש|שנה|שבועיים|יומיים|\\d+\\s+(?:ימים|שבועות|חודשים))(?![\\p{L}])"
    )
    private val weekdayListRe = Regex("(?<![\\p{L}])(?:בכל|כל|בימי|ימי)\\s+(?:יום\\s+)?(?:ו?$DAY(?![\\p{L}])[\\s,]*)+")
    private val workdaysRe = Regex("(?<![\\p{L}])(?:בימי|ימי)\\s+(?:חול|השבוע|עבודה)(?![\\p{L}])")
    private val weekendRe = Regex("(?<![\\p{L}])(?:בסופי\\s+שבוע|בסוף\\s+שבוע|בסופ\"ש|בסופש)(?![\\p{L}])")
    private val dailyRe = Regex("(?<![\\p{L}])(?:בכל|כל)\\s+יום(?![\\p{L}])")
    private val everyPeriodRe = Regex("(?<![\\p{L}])כל\\s+(בוקר|ערב|לילה)(?![\\p{L}])")
    private val weeklyRe = Regex("(?<![\\p{L}])(?:בכל|כל)\\s+שבוע(?![\\p{L}])")

    const val ERR_NOTHING = "כתוב שעה, למשל: מחר ב-8 בבוקר להתקשר לרופא"
    const val ERR_NO_TIME = "לא זיהיתי שעה. כתוב למשל: כל יום ב-9 בבוקר לקחת תרופה"
    const val ERR_TOO_FAR = "שעון מעורר מוגבל ל-24 שעות קדימה. לתאריך רחוק יותר השתמש ב-📅 אירוע"
    const val ERR_PAST = "השעה כבר עברה"
    const val ERR_UNSUPPORTED = "שעון מעורר לא תומך בחזרה חודשית או שנתית, רק יומית או לפי ימי שבוע"

    fun parse(raw: String, now: LocalDateTime): Result {
        var t = HebrewNumbers.normalize(raw).trim()
        if (t.isEmpty()) return Result(null, ERR_NOTHING)
        if (unsupportedRe.containsMatchIn(t)) return Result(null, ERR_UNSUPPORTED)

        var days: Set<DayOfWeek>? = null
        var weekly = false

        weekdayListRe.find(t)?.let { m ->
            days = Regex("ו?($DAY)(?![\\p{L}])").findAll(m.value).map { names.getValue(it.groupValues[1]) }.toSet()
            t = t.replace(m.value, " ")
        }
        if (days == null) workdaysRe.find(t)?.let { m -> days = workdays; t = t.replace(m.value, " ") }
        if (days == null) weekendRe.find(t)?.let { m ->
            days = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY); t = t.replace(m.value, " ")
        }
        if (days == null) dailyRe.find(t)?.let { m -> days = ORDER.toSet(); t = t.replace(m.value, " ") }
        if (days == null) everyPeriodRe.find(t)?.let { m ->
            days = ORDER.toSet()
            val p = when (m.groupValues[1]) { "בוקר" -> "בבוקר"; "ערב" -> "בערב"; else -> "בלילה" }
            t = t.replace(m.value, " $p ")
        }
        if (days == null) weeklyRe.find(t)?.let { m -> weekly = true; t = t.replace(m.value, " ") }

        val p = EventParser.parse(t, now) ?: return Result(null, ERR_NO_TIME)
        if (p.allDay) return Result(null, ERR_NO_TIME)

        val message = if (p.title == "אירוע" && !raw.contains("אירוע")) "התראה" else p.title
        val time = p.start.toLocalTime()

        if (days == null && weekly) days = setOf(p.start.dayOfWeek)

        val d = days
        if (d != null) return Result(Alarm(time, d, message, null), null)

        // חד-פעמית: שעון מעורר מצלצל בפעם הבאה שהשעה הזו מגיעה, ולכן רק עד 24 שעות
        val wait = Duration.between(now, p.start)
        if (wait.isNegative || wait.isZero) return Result(null, ERR_PAST)
        if (wait > Duration.ofHours(24)) return Result(null, ERR_TOO_FAR)
        return Result(Alarm(time, null, message, p.start), null)
    }
}
