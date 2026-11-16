package com.ofer.ideanotes

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/** חיפוש איש קשר בטלפון לפי שם (לצורך "הודעה לאמא, ..."). */
object Contacts {

    /** נמען שנמצא: שם להצגה, רשימת (שם, מספר בפורמט ווטסאפ), וגוף ההודעה. */
    data class Resolved(val name: String, val numbers: List<Pair<String, String>>, val body: String)

    private data class Row(val name: String, val number: String, val isMobile: Boolean)

    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun query(c: Context, variants: List<String>): List<Row> {
        val rows = mutableListOf<Row>()
        val P = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        for (v in variants) {
            val cur = try {
                c.contentResolver.query(
                    P, cols, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$v%"), null
                )
            } catch (e: Exception) {
                null
            } ?: continue
            cur.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val num = MessageParser.whatsappNumber(it.getString(1) ?: continue) ?: continue
                    rows.add(Row(name, num, it.getInt(2) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE))
                }
            }
        }
        return rows
    }

    /** מחזיר את ההתאמה הטובה ביותר (דרגה, נמענים) לפיצול נתון, או null. */
    private fun find(c: Context, name: String): Pair<Int, List<Pair<String, String>>>? {
        val variants = MessageParser.nameVariants(name)
        val ranked = query(c, variants).mapNotNull { r -> MessageParser.rank(r.name, variants)?.let { it to r } }
        if (ranked.isEmpty()) return null
        val best = ranked.minOf { it.first }
        var group = ranked.filter { it.first == best }.map { it.second }
        if (group.any { it.isMobile }) group = group.filter { it.isMobile } // מעדיפים נייד על קווי
        val numbers = group.distinctBy { it.number }.map { it.name to it.number }
        return best to numbers
    }

    /** כל אנשי הקשר עם מספר: (שם, מספר ווטסאפ), מספר אחד לשם (נייד אם יש), ממוין. לבחירה ידנית. */
    fun all(c: Context): List<Pair<String, String>> {
        val rows = query(c, listOf(""))
        return rows.groupBy { it.name }.map { (name, rs) ->
            val r = rs.firstOrNull { it.isMobile } ?: rs.first()
            name to r.number
        }.sortedBy { it.first }
    }

    fun resolve(c: Context, text: String): Resolved? {
        // כינוי שהוגדר (למשל "לאה" = אמא) קודם לחיפוש רגיל; שם ארוך יותר גובר
        val viaAlias = MessageParser.splits(text)
            .mapNotNull { s -> Aliases.find(c, MessageParser.nameVariants(s.name))?.let { s to it } }
            .maxByOrNull { it.first.name.length }
        if (viaAlias != null) {
            val (s, e) = viaAlias
            return Resolved(e.name, listOf(e.name to e.number), s.body)
        }
        val found = MessageParser.splits(text).mapNotNull { s -> find(c, s.name)?.let { Triple(s, it.first, it.second) } }
        if (found.isEmpty()) return null
        // עדיפות: התאמה מדויקת (גם אם השם ארוך יותר), אחרת הדרגה הטובה ביותר, ובשוויון השם הקצר
        val exact = found.filter { it.second == 0 }
        val pick = if (exact.isNotEmpty()) exact.maxByOrNull { it.first.name.length }!!
        else found.minWithOrNull(compareBy({ it.second }, { it.first.name.length }))!!
        val names = pick.third.map { it.first }.distinct()
        return Resolved(names.first(), pick.third, pick.first.body)
    }
}
