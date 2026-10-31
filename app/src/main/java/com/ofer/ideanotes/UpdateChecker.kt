package com.ofer.ideanotes

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * בדיקת עדכון בכל פתיחה: קורא את ה-Release האחרון מ-GitHub, ואם הגרסה חדשה יותר
 * מציג חלון "לעדכן?". ההורדה מותרת רק מכתובת ה-Releases של הפרויקט, ואנדרואיד עצמו
 * מאשר התקנה רק אם החתימה זהה לאפליקציה המותקנת.
 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/oferbechor-1985/IdeaNotes/releases/latest"
    private const val DL_PREFIX = "https://github.com/oferbechor-1985/IdeaNotes/releases/download/"
    private const val ASSET = "IdeaNotes.apk"

    private data class Release(val code: Long, val url: String)

    private fun installedCode(c: Context): Long {
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** רץ ברקע; כל כשל (אין רשת, אין Release) נבלע בשקט. */
    fun check(activity: Activity) {
        Thread {
            val rel = try { fetchLatest() } catch (e: Exception) { null } ?: return@Thread
            if (rel.code <= installedCode(activity)) return@Thread
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) ask(activity, rel)
            }
        }.start()
    }

    private fun fetchLatest(): Release? {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "IdeaNotes-App")
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val code = json.getString("tag_name").removePrefix("build-").toLongOrNull() ?: return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == ASSET) {
                    val url = a.getString("browser_download_url")
                    if (url.startsWith(DL_PREFIX)) return Release(code, url)
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun ask(a: Activity, rel: Release) {
        AlertDialog.Builder(a)
            .setTitle("יש גרסה חדשה")
            .setMessage("גרסה ${rel.code} זמינה (אצלך: ${installedCode(a)}).\nלעדכן עכשיו? ההערות שלך נשמרות.")
            .setPositiveButton("עדכן") { _, _ -> startUpdate(a, rel) }
            .setNegativeButton("אחר כך", null)
            .show()
    }

    private fun startUpdate(a: Activity, rel: Release) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, "אשר התקנת עדכונים לאפליקציה, וחזור לפתוח אותה שוב", Toast.LENGTH_LONG).show()
            a.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))
            )
            return
        }
        val progress = AlertDialog.Builder(a)
            .setMessage("מוריד עדכון…")
            .setCancelable(false)
            .show()
        Thread {
            val file = try { download(a, rel.url) } catch (e: Exception) { null }
            a.runOnUiThread {
                progress.dismiss()
                if (file == null) {
                    Toast.makeText(a, "ההורדה נכשלה, נסה שוב בפתיחה הבאה", Toast.LENGTH_LONG).show()
                } else {
                    install(a, file)
                }
            }
        }.start()
    }

    private fun download(c: Context, url: String): File? {
        val dir = File(c.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, ASSET)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "IdeaNotes-App")
        try {
            if (conn.responseCode != 200) return null
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        return if (out.length() > 0) out else null
    }

    private fun install(a: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        a.startActivity(i)
    }
}
