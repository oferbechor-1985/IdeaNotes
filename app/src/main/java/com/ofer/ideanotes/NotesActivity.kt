package com.ofer.ideanotes

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.DialogInterface
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** רשימת ההערות. נעולה בנעילת המסך של המכשיר (טביעת אצבע / קוד). */
class NotesActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var totalView: TextView
    private lateinit var actionBtn: Button
    private val filterButtons = mutableListOf<Pair<String?, Button>>()

    private var all = listOf<Store.Note>()
    private var shown = listOf<Store.Note>()
    private var filter: String? = null // null = הכול
    private var player: MediaPlayer? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ההערות שלי"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // שורת סינון לפי סוג
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val options = listOf<Pair<String?, String>>(
            null to "הכול",
            Store.TYPE_NOTE to "הערות",
            Store.TYPE_EXPENSE to "הוצאות",
            Store.TYPE_FEEDBACK to "שיפורים"
        )
        for ((type, label) in options) {
            val b = Button(this).apply {
                text = label
                setOnClickListener {
                    filter = type
                    applyFilter()
                }
            }
            filterButtons.add(type to b)
            filterRow.addView(b, lp)
        }
        root.addView(filterRow)

        totalView = TextView(this).apply {
            textSize = 18f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
        }
        root.addView(totalView)

        list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        actionBtn = Button(this).apply { setOnClickListener { onAction() } }
        root.addView(actionBtn)

        setContentView(root)

        list.setOnItemClickListener { _, _, i, _ -> showNote(shown[i]) }

        val km = getSystemService(KeyguardManager::class.java)
        if (km.isDeviceSecure) {
            val intent = km.createConfirmDeviceCredentialIntent("ההערות שלי", "אמת זהות כדי לצפות בהערות")
            if (intent != null) startActivityForResult(intent, 10) else reload()
        } else {
            reload() // אין נעילת מסך במכשיר – אין מה לאמת
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10) {
            if (resultCode == RESULT_OK) reload() else finish()
        }
    }

    private fun reload() {
        all = Store.list(this)
        applyFilter()
        if (all.isEmpty()) Toast.makeText(this, "עדיין אין הערות", Toast.LENGTH_SHORT).show()
    }

    private fun applyFilter() {
        val f = filter
        shown = if (f == null) all else all.filter { it.type == f }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { it.summary() })

        for ((type, b) in filterButtons) b.alpha = if (type == f) 1f else 0.5f

        if (f == Store.TYPE_EXPENSE) {
            val total = shown.fold(0.0) { acc, n -> acc + (n.amount ?: 0.0) }
            totalView.text = "סה״כ: ${Store.money(total)}  (${shown.size} הוצאות)"
            totalView.visibility = View.VISIBLE
        } else {
            totalView.visibility = View.GONE
        }

        actionBtn.text = when (f) {
            Store.TYPE_EXPENSE -> "ייצוא טבלת הוצאות (CSV)"
            Store.TYPE_FEEDBACK -> "שתף הערות שיפור"
            else -> "ייצוא הכול (zip)"
        }
    }

    private fun onAction() {
        when (filter) {
            Store.TYPE_EXPENSE -> exportCsv()
            Store.TYPE_FEEDBACK -> shareFeedback()
            else -> exportAll()
        }
    }

    private fun showNote(n: Store.Note) {
        val sb = StringBuilder()
        if (n.type == Store.TYPE_EXPENSE) sb.append("סכום: ").append(Store.money(n.amount ?: 0.0)).append("\n\n")
        if (n.type == Store.TYPE_FEEDBACK) sb.append("🛠 שיפור לאפליקציה\n\n")
        if (n.tags.isNotEmpty()) sb.append(n.tags.joinToString(" ") { "#$it" }).append("\n\n")
        sb.append(n.body)

        val b = AlertDialog.Builder(this)
            .setTitle(n.date)
            .setMessage(sb.toString())
            .setPositiveButton("סגור", null)
            .setNegativeButton("מחק") { _, _ -> confirmDelete(n) }
        if (n.audio != null) b.setNeutralButton("▶ נגן", null)
        val dlg = b.create()
        dlg.setOnDismissListener { stopPlayer() }
        dlg.show()
        // מעקף כדי שלחיצה על "נגן" לא תסגור את החלון
        dlg.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener { btn ->
            togglePlay(n.audio!!, btn as Button)
        }
    }

    private fun togglePlay(audio: File, btn: Button) {
        if (player != null) {
            stopPlayer()
            btn.text = "▶ נגן"
            return
        }
        try {
            player = MediaPlayer().apply {
                setDataSource(audio.absolutePath)
                prepare()
                setOnCompletionListener {
                    stopPlayer()
                    btn.text = "▶ נגן"
                }
                start()
            }
            btn.text = "⏹ עצור"
        } catch (e: Exception) {
            stopPlayer()
            Toast.makeText(this, "לא ניתן לנגן את ההקלטה", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayer() {
        player?.release()
        player = null
    }

    private fun confirmDelete(n: Store.Note) {
        AlertDialog.Builder(this)
            .setMessage("למחוק את ההערה?")
            .setPositiveButton("מחק") { _, _ ->
                Store.delete(this, n)
                reload()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    // ---------- ייצוא ושיתוף ----------

    private fun exportDir(): File {
        val d = File(cacheDir, "export").apply { mkdirs() }
        d.listFiles()?.forEach { it.delete() }
        return d
    }

    private fun share(file: File, mime: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, chooserTitle))
    }

    private fun exportAll() {
        if (all.any { it.type == Store.TYPE_EXPENSE }) Store.writeExpensesCsv(this)
        val files = Store.dir(this).listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) {
            Toast.makeText(this, "אין מה לייצא", Toast.LENGTH_SHORT).show()
            return
        }
        val zip = File(exportDir(), "ideas-${Store.newBase()}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            for (f in files) {
                z.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        share(zip, "application/zip", "ייצוא ההערות")
    }

    private fun exportCsv() {
        if (shown.isEmpty()) {
            Toast.makeText(this, "אין הוצאות לייצוא", Toast.LENGTH_SHORT).show()
            return
        }
        val csv = Store.writeExpensesCsv(this)
        val out = File(exportDir(), "expenses-${Store.newBase()}.csv")
        csv.copyTo(out, overwrite = true)
        share(out, "text/csv", "ייצוא הוצאות")
    }

    private fun shareFeedback() {
        if (shown.isEmpty()) {
            Toast.makeText(this, "אין הערות שיפור", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, Store.feedbackText(this))
        startActivity(Intent.createChooser(send, "שיתוף הערות שיפור"))
    }

    override fun onDestroy() {
        stopPlayer()
        super.onDestroy()
    }
}
