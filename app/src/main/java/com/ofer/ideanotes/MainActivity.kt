package com.ofer.ideanotes

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** החלון המהיר: נפתח ישר מהאייקון, המקלדת כבר פתוחה. */
class MainActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var recBtn: Button
    private lateinit var status: TextView

    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var recStart = 0L
    private var saved = false
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            val s = (System.currentTimeMillis() - recStart) / 1000
            status.text = "🔴 מקליט… %d:%02d".format(s / 60, s % 60)
            handler.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        input = EditText(this).apply {
            hint = "מה הרעיון?"
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        root.addView(input)

        status = TextView(this).apply { setPadding(0, dp(6), 0, dp(6)) }
        root.addView(status)

        recBtn = button("🎙 הקלט") { toggleRecording() }
        val dictBtn = button("🗣 הכתבה") { dictate() }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row1.addView(recBtn, lp)
        row1.addView(dictBtn, lp)
        root.addView(row1)

        val saveBtn = button("שמור") { save() }
        val listBtn = button("ההערות שלי") { startActivity(Intent(this, NotesActivity::class.java)) }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(saveBtn, lp)
        row2.addView(listBtn, lp)
        root.addView(row2)

        setContentView(root)
    }

    // ---------- הקלטה ----------

    private fun toggleRecording() {
        if (recorder != null) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        // הקלטה חדשה מחליפה הקלטה קודמת שלא נשמרה
        audioFile?.delete()
        val f = File(Store.dir(this), Store.newBase() + ".m4a")
        try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            audioFile = f
            recStart = System.currentTimeMillis()
            recBtn.text = "⏹ עצור"
            handler.post(tick)
        } catch (e: Exception) {
            f.delete()
            audioFile = null
            Toast.makeText(this, "לא הצלחתי להתחיל הקלטה", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        handler.removeCallbacks(tick)
        val r = recorder ?: return
        recorder = null
        try {
            r.stop()
            status.text = "🎙 הקלטה מוכנה (תישמר עם ההערה)"
        } catch (e: RuntimeException) {
            // הקלטה קצרה מדי
            audioFile?.delete()
            audioFile = null
            status.text = ""
        }
        r.release()
        recBtn.text = if (audioFile != null) "🎙 הקלט מחדש" else "🎙 הקלט"
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 1 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
        else if (code == 1) Toast.makeText(this, "אין הרשאת הקלטה", Toast.LENGTH_SHORT).show()
    }

    // ---------- הכתבה (טקסט בלבד, דרך זיהוי הדיבור של גוגל) ----------

    private fun dictate() {
        if (recorder != null) {
            Toast.makeText(this, "עצור קודם את ההקלטה", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
        try {
            startActivityForResult(i, 2)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "הכתבה קולית לא זמינה במכשיר", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2 && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                val cur = input.text.toString()
                input.setText(if (cur.isBlank()) spoken else "$cur $spoken")
                input.setSelection(input.text.length)
            }
        }
    }

    // ---------- שמירה ----------

    private fun save() {
        if (recorder != null) stopRecording()
        val text = input.text.toString().trim()
        if (text.isEmpty() && audioFile == null) {
            Toast.makeText(this, "אין מה לשמור", Toast.LENGTH_SHORT).show()
            return
        }
        Store.save(this, text, audioFile)
        saved = true
        Toast.makeText(this, "נשמר ✓", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (recorder != null) stopRecording()
        // הקלטה שלא נשמרה עם הערה לא נשארת יתומה בתיקייה
        if (!saved) audioFile?.delete()
        super.onDestroy()
    }
}
