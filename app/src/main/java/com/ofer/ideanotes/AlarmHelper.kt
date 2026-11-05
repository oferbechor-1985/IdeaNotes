package com.ofer.ideanotes

import android.content.Intent
import android.provider.AlarmClock
import java.time.DayOfWeek

/** הגדרת שעון מעורר באפליקציית השעון של הטלפון (Google Clock / סמסונג). */
object AlarmHelper {

    // java.time: שני=1..ראשון=7. java.util.Calendar: ראשון=1..שבת=7.
    private fun calendarDay(d: DayOfWeek): Int = (d.value % 7) + 1

    fun intent(a: AlarmParser.Alarm): Intent {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, a.time.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, a.time.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.message)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        a.days?.let { days ->
            i.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { calendarDay(it) }))
        }
        return i
    }
}
