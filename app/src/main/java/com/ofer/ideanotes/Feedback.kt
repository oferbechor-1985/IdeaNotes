package com.ofer.ideanotes

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * שליחה אוטומטית של הערות שיפור לתיבת המשוב הפרטית (Cloudflare Worker -> repo פרטי ב-GitHub).
 * הערה שלא נשלחה (אין רשת) נשארת "ממתינה" ונשלחת בפתיחה הבאה של האפליקציה.
 * המפתח כאן רק מזהה את האפליקציה מול ה-Worker; הוא לא נותן גישה ל-GitHub.
 */
object Feedback {

    private const val URL_WORKER = "https://brush-feedback.ofer-bechor.workers.dev"
    private const val APP_KEY = "8dc5bbbd647f35e6f81e7453b068c148"
    private val lock = Any()

    /** שולח ברקע את כל הערות השיפור הממתינות. כל כשל נבלע; ננסה שוב בפעם הבאה. */
    fun flush(context: Context) {
        val c = context.applicationContext
        Thread {
            synchronized(lock) {
                for (n in Store.pending(c)) {
                    val ok = try { send(c, n.body) } catch (e: Exception) { false }
                    if (!ok) break
                    try { Store.markSent(n.file) } catch (e: Exception) { break }
                }
            }
        }.start()
    }

    private fun send(c: Context, text: String): Boolean {
        val dm = c.resources.displayMetrics
        val version = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        val body = JSONObject()
            .put("text", text.trim().take(1500))
            .put("kind", "idea")
            .put("voice", false)
            .put("version", version.take(10))
            .put("input", "touch")
            .put("screen", "${dm.widthPixels}x${dm.heightPixels}")
            .put("lang", Locale.getDefault().toLanguageTag())
        val conn = URL(URL_WORKER).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-App-Key", APP_KEY)
            conn.setRequestProperty("User-Agent", "IdeaNotes-App/Android-" + Build.VERSION.SDK_INT)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return false
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(resp).optBoolean("ok", false)
        } finally {
            conn.disconnect()
        }
    }
}
