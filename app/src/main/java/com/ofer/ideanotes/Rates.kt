package com.ofer.ideanotes

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * שערי המרה לשקל (דולר / אירו / פאונד). השערים נטענים ברקע בפתיחת האפליקציה ונשמרים במטמון,
 * כך ששמירת הוצאה במטבע זר מהירה ועובדת גם בלי רשת. מקור ראשי: Frankfurter (שערי הבנק המרכזי
 * האירופי). גיבוי: open.er-api.com (ExchangeRate-API).
 */
object Rates {

    private const val PREFS = "rates"
    private const val MAX_AGE_MS = 6L * 60 * 60 * 1000
    private val CURRENCIES = listOf("USD", "EUR", "GBP")
    private val SOURCES = listOf(
        "https://api.frankfurter.dev/v1/latest?base=ILS&symbols=USD,EUR,GBP",
        "https://open.er-api.com/v6/latest/ILS"
    )

    /** כמה שקלים שווה יחידה אחת של המטבע, לפי השער השמור. null אם אין שער שמור. */
    fun ilsPer(c: Context, cur: String): Double? {
        if (cur == "ILS") return 1.0
        return c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(cur, null)?.toDoubleOrNull()
    }

    /** מרענן ברקע אם אין שער או שהוא ישן. כשל ברשת נבלע בשקט (נשאר השער האחרון). */
    fun refresh(c: Context, force: Boolean = false) {
        val prefs = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val age = System.currentTimeMillis() - prefs.getLong("at", 0L)
        if (!force && age < MAX_AGE_MS && prefs.contains("USD")) return
        Thread {
            for (src in SOURCES) {
                val r = try { fetch(src) } catch (e: Exception) { null } ?: continue
                prefs.edit().apply {
                    r.forEach { (k, v) -> putString(k, v.toString()) }
                    putLong("at", System.currentTimeMillis())
                }.apply()
                break
            }
        }.start()
    }

    private fun fetch(url: String): Map<String, Double>? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "IdeaNotes-App")
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val rates = json.optJSONObject("rates") ?: return null
            val out = HashMap<String, Double>()
            for (cur in CURRENCIES) {
                val perIls = rates.optDouble(cur, 0.0) // כמה יחידות של המטבע שוות שקל אחד
                if (perIls > 0.0) out[cur] = 1.0 / perIls
            }
            val usd = out["USD"] ?: return null
            if (usd !in 1.5..8.0) return null // הגנה מפני נתון שגוי
            return out
        } finally {
            conn.disconnect()
        }
    }
}
