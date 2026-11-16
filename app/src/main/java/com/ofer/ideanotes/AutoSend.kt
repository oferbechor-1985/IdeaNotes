package com.ofer.ideanotes

import android.content.Context
import android.provider.Settings

/**
 * שליחה אוטומטית בווטסאפ: אחרי שהאפליקציה פתחה הודעה, שירות הנגישות לוחץ על "שלח".
 * השירות פועל רק בחלונות של ווטסאפ, ורק בחלון זמן קצר אחרי שהאפליקציה "חימשה" אותו.
 */
object AutoSend {
    @Volatile private var armedAt = 0L

    fun arm() { armedAt = System.currentTimeMillis() }
    fun disarm() { armedAt = 0L }
    fun armed() = System.currentTimeMillis() - armedAt < 20_000

    private fun prefs(c: Context) = c.getSharedPreferences("autosend", Context.MODE_PRIVATE)

    /** האם שירות הנגישות מופעל בהגדרות הטלפון. */
    fun serviceEnabled(c: Context): Boolean {
        val s = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any { it.startsWith(c.packageName + "/") && it.endsWith("SendService") }
    }

    /** מתג בתוך האפליקציה (ברירת מחדל: מופעל), כדי לכבות בלי לגעת בהגדרות הנגישות. */
    fun switchOn(c: Context) = prefs(c).getBoolean("on", true)
    fun setSwitch(c: Context, on: Boolean) = prefs(c).edit().putBoolean("on", on).apply()

    fun enabled(c: Context) = serviceEnabled(c) && switchOn(c)
}
