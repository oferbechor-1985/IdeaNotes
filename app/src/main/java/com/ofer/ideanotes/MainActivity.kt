package com.ofer.ideanotes

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** החלון המהיר: נפתח ישר מהאייקון, המקלדת כבר פתוחה. */
class MainActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var recBtn: Button
    private lateinit var status: TextView
    private lateinit var preview: TextView
    private var type = Store.TYPE_NOTE

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
        // תצוגה מקדימה של מה שזוהה בהוצאה (סכום, מטבע, תיאור)
        preview = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(4), 0, dp(4))
            visibility = View.GONE
        }

        // בורר סוג: הערה / הוצאה / שיפור לאפליקציה
        val kinds = listOf(
            Triple(Store.TYPE_NOTE, "📝 הערה", "מה הרעיון?"),
            Triple(Store.TYPE_EXPENSE, "💸 הוצאה", "למשל: 30 שקל עבור שוארמה / 10 דולר עבור קפה"),
            Triple(Store.TYPE_EVENT, "📅 אירוע", "למשל: מחר ב-16:00 פגישה עם דנה"),
            Triple(Store.TYPE_ALARM, "⏰ התראה", "למשל: כל יום ב-9 בבוקר לקחת תרופה / מחר ב-8 להתקשר לרופא"),
            Triple(Store.TYPE_FEEDBACK, "🛠 שיפור", "מה לתקן או לשפר באפליקציה?")
        )
        val byId = HashMap<Int, Triple<String, String, String>>()
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        var firstId = 0
        for (k in kinds) {
            val rb = RadioButton(this).apply {
                text = k.second
                id = View.generateViewId()
            }
            if (firstId == 0) firstId = rb.id
            byId[rb.id] = k
            group.addView(rb)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val k = byId[checkedId] ?: return@setOnCheckedChangeListener
            type = k.first
            input.hint = k.third
            updatePreview()
        }

        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(group)
        })
        root.addView(input)
        root.addView(preview)
        group.check(firstId)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updatePreview()
        })

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

        if (savedInstanceState == null) {
            Rates.refresh(this)
            UpdateChecker.check(this)
        }
    }

    // ---------- זיהוי הוצאה ----------

    private fun updatePreview() {
        if (type != Store.TYPE_EXPENSE && type != Store.TYPE_EVENT && type != Store.TYPE_ALARM) {
            preview.visibility = View.GONE
            return
        }
        preview.visibility = View.VISIBLE
        val raw = input.text.toString()
        if (type == Store.TYPE_ALARM) {
            val r = AlarmParser.parse(raw, LocalDateTime.now())
            val a = r.alarm
            preview.text = when {
                raw.isBlank() -> ""
                a != null -> "⏰ " + a.describe() + "  ·  " + a.message
                else -> "⚠ " + (r.error ?: "")
            }
            return
        }
        if (type == Store.TYPE_EVENT) {
            val e = EventParser.parse(raw, LocalDateTime.now())
            preview.text = when {
                raw.isBlank() -> ""
                e == null -> "⚠ לא זיהיתי תאריך או שעה"
                else -> "📅 " + describe(e) + "  ·  " + e.title
            }
            return
        }
        val p = Expense.parse(raw)
        preview.text = when {
            raw.isBlank() -> ""
            p == null -> "⚠ לא זיהיתי סכום"
            else -> {
                val rate = Rates.ilsPer(this, p.currency)
                val ils = if (rate != null) Store.money(p.amount * rate) else "אין שער המרה"
                val orig = if (p.currency != "ILS") String.format(Locale.US, "  (%.2f %s)", p.amount, p.currency) else ""
                "💸 $ils$orig  ·  " + p.description.ifEmpty { "ללא פירוט" }
            }
        }
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
        if (code == 3) {
            val p = pendingEvent ?: return
            pendingEvent = null
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) addToCalendar(p)
            else finishEvent(p, false)
        }
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

    // ---------- התראה בשעון המעורר ----------

    private fun saveAlarm(text: String) {
        val r = AlarmParser.parse(text, LocalDateTime.now())
        val a = r.alarm
        if (a == null) {
            Toast.makeText(this, r.error ?: AlarmParser.ERR_NO_TIME, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(AlarmHelper.intent(a))
        } catch (e: Exception) {
            Toast.makeText(this, "לא נמצאה אפליקציית שעון בטלפון", Toast.LENGTH_LONG).show()
            return
        }
        Store.save(this, a.message, audioFile, Store.TYPE_ALARM, alarm = a.describe())
        saved = true
        Toast.makeText(this, "⏰ ההתראה הוגדרה בשעון", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ---------- אירוע ביומן ----------

    private var pendingEvent: EventParser.Parsed? = null

    private fun describe(e: EventParser.Parsed): String {
        val day = e.start.format(DateTimeFormatter.ofPattern("EEEE d/M", Locale("he", "IL")))
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        return if (e.allDay) "$day · כל היום" else day + " · " + e.start.format(hm) + "–" + e.end.format(hm)
    }

    private fun saveEvent(text: String) {
        val p = EventParser.parse(text, LocalDateTime.now())
        if (p == null) {
            Toast.makeText(this, "לא זיהיתי תאריך או שעה. כתוב למשל: מחר ב-16:00 פגישה עם דנה", Toast.LENGTH_LONG).show()
            return
        }
        if (!CalendarHelper.hasPermission(this)) {
            pendingEvent = p
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 3)
            return
        }
        addToCalendar(p)
    }

    private fun addToCalendar(p: EventParser.Parsed) {
        val list = CalendarHelper.targets(this)
        if (list.isEmpty()) {
            finishEvent(p, false)
            return
        }
        val target = CalendarHelper.saved(this, list)
        if (target != null) {
            finishEvent(p, CalendarHelper.insert(this, target, p))
            return
        }
        // כמה חשבונות Google במכשיר: בוחרים פעם אחת לאיזה יומן להוסיף
        AlertDialog.Builder(this)
            .setTitle("לאיזה יומן להוסיף?")
            .setItems(list.map { it.account }.toTypedArray()) { _, i ->
                CalendarHelper.remember(this, list[i])
                finishEvent(p, CalendarHelper.insert(this, list[i], p))
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun finishEvent(p: EventParser.Parsed, inserted: Boolean) {
        val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val dtFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val f = if (p.allDay) dayFmt else dtFmt
        val info = Store.EventInfo(p.start.format(f), p.end.format(f), p.allDay, inserted)
        Store.save(this, p.title, audioFile, Store.TYPE_EVENT, event = info)
        saved = true
        if (inserted) {
            Toast.makeText(this, "נוסף ליומן ✓", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "לא נוסף אוטומטית. פותח את היומן, לחץ שמור", Toast.LENGTH_LONG).show()
            try {
                startActivity(CalendarHelper.insertIntent(p))
            } catch (e: ActivityNotFoundException) {
                // אין אפליקציית יומן: ההערה נשמרת בכל זאת
            }
        }
        finish()
    }

    // ---------- שמירה ----------

    private fun save() {
        if (recorder != null) stopRecording()
        val text = input.text.toString().trim()
        if (type == Store.TYPE_EVENT) {
            saveEvent(text)
            return
        }
        if (type == Store.TYPE_ALARM) {
            saveAlarm(text)
            return
        }
        var textToSave = text
        var amount: Double? = null
        var origCur: String? = null
        var origAmt: Double? = null
        var rate: Double? = null
        if (type == Store.TYPE_EXPENSE) {
            // הסכום, המטבע והתיאור מזוהים אוטומטית מהטקסט; המרה לשקלים לפי השער השמור
            val p = Expense.parse(text)
            if (p == null) {
                Toast.makeText(this, "לא זיהיתי סכום. כתוב למשל: 30 שקל עבור שוארמה", Toast.LENGTH_LONG).show()
                return
            }
            val r = Rates.ilsPer(this, p.currency)
            if (r == null) {
                Toast.makeText(this, "אין שער המרה שמור ל-${p.currency}. התחבר לרשת ונסה שוב", Toast.LENGTH_LONG).show()
                Rates.refresh(this, force = true)
                return
            }
            amount = Math.round(p.amount * r * 100) / 100.0
            origCur = p.currency
            origAmt = p.amount
            rate = r
            textToSave = p.description
        } else if (text.isEmpty() && audioFile == null) {
            Toast.makeText(this, "אין מה לשמור", Toast.LENGTH_SHORT).show()
            return
        }
        Store.save(this, textToSave, audioFile, type, amount, origCur, origAmt, rate)
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
