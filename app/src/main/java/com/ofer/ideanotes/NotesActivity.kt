package com.ofer.ideanotes

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.DialogInterface
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** רשימת ההערות. נעולה בנעילת המסך של המכשיר (טביעת אצבע / קוד). */
class NotesActivity : Activity() {

    private lateinit var list: ListView
    private var notes = listOf<Store.Note>()
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ההערות שלי"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val export = Button(this).apply {
            text = "ייצוא הכול (zip)"
            setOnClickListener { exportAll() }
        }
        list = ListView(this)
        root.addView(export)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        list.setOnItemClickListener { _, _, i, _ -> showNote(notes[i]) }

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
        notes = Store.list(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, notes.map { it.summary() })
        if (notes.isEmpty()) Toast.makeText(this, "עדיין אין הערות", Toast.LENGTH_SHORT).show()
    }

    private fun showNote(n: Store.Note) {
        val tagLine = if (n.tags.isEmpty()) "" else n.tags.joinToString(" ") { "#$it" } + "\n\n"
        val b = AlertDialog.Builder(this)
            .setTitle(n.date)
            .setMessage(tagLine + n.body)
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
                n.file.delete()
                n.audio?.delete()
                reload()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun exportAll() {
        val files = Store.dir(this).listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) {
            Toast.makeText(this, "אין מה לייצא", Toast.LENGTH_SHORT).show()
            return
        }
        val outDir = File(cacheDir, "export").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }
        val zip = File(outDir, "ideas-${Store.newBase()}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            for (f in files) {
                z.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "ייצוא ההערות"))
    }

    override fun onDestroy() {
        stopPlayer()
        super.onDestroy()
    }
}
