package com.ofer.ideanotes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * כינויים לאנשי קשר: "לאה" / "אימוש" -> אמא. נשמרים בטלפון (SharedPreferences) ומקבלים עדיפות על חיפוש רגיל.
 * כל כינוי הוא רשימת ניסוחים (כולל הצורה עם ל' בהתחלה), איש הקשר ומספר הווטסאפ שלו.
 */
object Aliases {

    data class Entry(val words: List<String>, val name: String, val number: String) {
        /** הצורה לתצוגה: הניסוח האחרון (בלי ל' של "ל..."). */
        fun label(): String = words.last()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("aliases", Context.MODE_PRIVATE)

    fun all(c: Context): List<Entry> {
        return try {
            val arr = JSONArray(prefs(c).getString("list", "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val w = o.getJSONArray("words")
                Entry((0 until w.length()).map { w.getString(it) }, o.getString("name"), o.getString("number"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(c: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().put("words", JSONArray(e.words)).put("name", e.name).put("number", e.number))
        }
        prefs(c).edit().putString("list", arr.toString()).apply()
    }

    private fun overlaps(a: List<String>, b: List<String>): Boolean {
        val s = a.map { MessageParser.norm(it) }.toSet()
        return b.any { MessageParser.norm(it) in s }
    }

    fun find(c: Context, variants: List<String>): Entry? = all(c).firstOrNull { overlaps(it.words, variants) }

    /** מוסיף (או מחליף כינוי קיים באותו שם). */
    fun add(c: Context, words: List<String>, name: String, number: String) {
        val list = all(c).filter { !overlaps(it.words, words) }.toMutableList()
        list.add(Entry(words, name, number))
        write(c, list)
    }

    fun remove(c: Context, e: Entry) = write(c, all(c).filter { it != e })
}
