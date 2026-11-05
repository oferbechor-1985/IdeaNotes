package com.ofer.ideanotes

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * הוספת אירוע ליומן Google של הטלפון. האירוע נכתב ליומן הראשי של חשבון Google במכשיר,
 * ו-Google מסנכרנת אותו אוטומטית לענן (כך הוא מופיע גם במחשב ובשאר המכשירים).
 */
object CalendarHelper {

    data class Target(val id: Long, val account: String)

    private const val PREFS = "calendar"
    private const val KEY_ID = "calendar_id"

    fun hasPermission(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** היומנים הראשיים של חשבונות Google במכשיר, שאפשר לכתוב אליהם ושמסונכרנים. */
    fun targets(c: Context): List<Target> {
        val out = mutableListOf<Target>()
        try {
            val proj = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.OWNER_ACCOUNT,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.SYNC_EVENTS
            )
            val cur = c.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, proj,
                "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?", arrayOf("com.google"), null
            ) ?: return out
            cur.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val account = it.getString(1) ?: continue
                    val owner = it.getString(2)
                    val level = it.getInt(3)
                    val sync = it.getInt(4)
                    if (owner == account &&
                        level >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR &&
                        sync == 1
                    ) out.add(Target(id, account))
                }
            }
        } catch (e: Exception) {
            // אין הרשאה או ספק יומן לא זמין: מחזירים רשימה ריקה
        }
        return out
    }

    /** יומן יחיד, או היומן שנבחר בעבר. null = צריך לשאול את המשתמש. */
    fun saved(c: Context, list: List<Target>): Target? {
        if (list.size == 1) return list[0]
        val id = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_ID, -1L)
        return list.firstOrNull { it.id == id }
    }

    fun remember(c: Context, t: Target) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_ID, t.id).apply()
    }

    private fun startMillis(p: EventParser.Parsed): Long =
        if (p.allDay) p.start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        else p.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun endMillis(p: EventParser.Parsed): Long =
        if (p.allDay) p.start.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        else p.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun insert(c: Context, t: Target, p: EventParser.Parsed): Boolean = try {
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, t.id)
            put(CalendarContract.Events.TITLE, p.title)
            put(CalendarContract.Events.DTSTART, startMillis(p))
            put(CalendarContract.Events.DTEND, endMillis(p))
            if (p.allDay) {
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        }
        c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) != null
    } catch (e: Exception) {
        false
    }

    /** גיבוי: פותח את אפליקציית היומן עם האירוע ממולא, והמשתמש לוחץ שמור. */
    fun insertIntent(p: EventParser.Parsed): Intent =
        Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, p.title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis(p))
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis(p))
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, p.allDay)
}
