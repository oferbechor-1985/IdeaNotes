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
        window.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(Ui.CREAM)
            cornerRadius = dp(24).toFloat()
        })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            // החלון רחב כמעט כמו המסך, גם כשאין הרבה טקסט (אחרת תגים וכפתורים נחתכים)
            minimumWidth = (resources.displayMetrics.widthPixels * 0.94f).toInt()
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
            setTextColor(Ui.INK)
            visibility = View.GONE
        }

        // בורר סוג: הערה / הוצאה / שיפור לאפליקציה
        val kinds = listOf(
            Triple(Store.TYPE_NOTE, "הערה", "מה הרעיון?"),
            Triple(Store.TYPE_EXPENSE, "הוצאה", "למשל: 30 שקל עבור שוארמה / 10 דולר עבור קפה"),
            Triple(Store.TYPE_EVENT, "אירוע", "למשל: מחר ב-16:00 פגישה עם דנה"),
            Triple(Store.TYPE_ALARM, "התראה", "למשל: כל יום ב-9 בבוקר לקחת תרופה / מחר ב-8 להתקשר לרופא"),
            Triple(Store.TYPE_FEEDBACK, "שיפור", "מה לתקן או לשפר באפליקציה?"),
            Triple(AutoType.TYPE_SEARCH, "חיפוש", "מה לחפש בגוגל? (לא נשמר)"),
            Triple(AutoType.TYPE_MESSAGE, "הודעה", "הודעה בווטסאפ: לאמא, תבואי בשלוש")
        )
        val chips = mutableListOf<Pair<TextView, String>>()
        fun select(k: Triple<String, String, String>) {
            type = k.first
            input.hint = k.third
            for ((tv, t) in chips) Ui.chip(tv, t == type)
            updatePreview()
        }
        // שתי שורות (4 + 3), עמודות שוות
        val chipGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chipRows = listOf(
            LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL },
            LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        )
        chipRows.forEach { chipGrid.addView(it) }
        for ((idx, k) in kinds.withIndex()) {
            val tv = TextView(this).apply {
                text = k.second
                textSize = 14f
                setOnClickListener { select(k) }
            }
            chips.add(tv to k.first)
            chipRows[if (idx < 4) 0 else 1].addView(tv, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(dp(4), dp(4), dp(4), dp(6)) })
        }

        root.addView(chipGrid)
        root.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(4), dp(8), dp(4), dp(8)) })
        root.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(4), dp(0), dp(4), dp(8)) })
        Ui.card(input, Ui.WHITE, 18, 5, 16)
        input.textSize = 18f
        Ui.card(preview, Ui.MINT_L, 14, 0, 12)
        select(kinds[0])
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updatePreview()
        })

        status = TextView(this).apply { setPadding(0, dp(6), 0, dp(6)); setTextColor(Ui.INK) }
        root.addView(status)

        recBtn = button("🎙 הקלט") { toggleRecording() }
        val dictBtn = button("🗣 הכתבה") { dictate() }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        Ui.button(recBtn, Ui.CORAL)
        Ui.button(dictBtn, Ui.SKY)
        row1.addView(recBtn, lp)
        row1.addView(dictBtn, lp)
        root.addView(row1)

        val saveBtn = button("שמור") { save() }
        val listBtn = button("ארכיון") { startActivity(Intent(this, NotesActivity::class.java)) }
        Ui.button(saveBtn, Ui.MINT, 20f, 60, 5)
        Ui.button(listBtn, Ui.WHITE, 15f, 60, 0)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(saveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { setMargins(dp(4), dp(8), dp(4), dp(4)) })
        row2.addView(listBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(8), dp(4), dp(4)) })
        root.addView(row2)

        setContentView(root)

        if (savedInstanceState == null) {
            Thread { Store.cleanup(applicationContext) }.start()
            Rates.refresh(this)
            Feedback.flush(this)
            UpdateChecker.check(this)
        }
    }

    // ---------- זיהוי הוצאה ----------

    /** הסוג בפועל: אם נבחרה "הערה" והטקסט פותח במילת סוג (אירוע, התראה...) – הסוג הזה והטקסט בלעדיה. */
    private fun effective(): Pair<String, String> {
        val raw = input.text.toString()
        if (forceNote) return Store.TYPE_NOTE to raw
        if (type == Store.TYPE_NOTE) {
            val d = AutoType.detect(raw)
            if (d != null) return d.type to d.rest
        }
        return type to raw
    }

    private var forceNote = false

    private fun updatePreview() {
        val (t, raw) = effective()
        val auto = type == Store.TYPE_NOTE && t != Store.TYPE_NOTE
        val fb = if (auto) " (תישמר כהערה רגילה)" else ""
        if (t != Store.TYPE_EXPENSE && t != Store.TYPE_EVENT && t != Store.TYPE_ALARM && !(auto && t == Store.TYPE_FEEDBACK) && t != AutoType.TYPE_SEARCH && t != AutoType.TYPE_MESSAGE) {
            preview.visibility = View.GONE
            return
        }
        preview.visibility = View.VISIBLE
        if (t == AutoType.TYPE_MESSAGE) {
            when {
                raw.isBlank() -> {
                    if (auto) preview.text = "⚠ כתוב: הודעה לשם, ואז את ההודעה$fb" else preview.visibility = View.GONE
                }
                !Contacts.hasPermission(this) ->
                    preview.text = "💬 הודעה בווטסאפ  ·  בשמירה תתבקש הרשאה לאנשי קשר"
                else -> {
                    val r = Contacts.resolve(this, raw)
                    preview.text = if (r == null) "⚠ לא נמצא איש קשר, בשמירה תוכל לבחור ולהגדיר כינוי"
                    else "💬 " + r.name + (if (r.numbers.size > 1) " (ועוד " + (r.numbers.size - 1) + " מספרים)" else "") +
                        "  ·  " + r.body.take(50) + (if (AutoSend.enabled(this)) "  ·  ⚡ יישלח אוטומטית" else "")
                }
            }
            return
        }
        if (t == AutoType.TYPE_SEARCH) {
            if (raw.isBlank() && !auto) {
                preview.visibility = View.GONE
                return
            }
            preview.text = if (raw.isBlank()) "⚠ כתוב מה לחפש אחרי המילה$fb" else "🔍 חיפוש בגוגל  ·  " + raw.take(60)
            return
        }
        if (t == Store.TYPE_FEEDBACK) {
            preview.text = if (raw.isBlank()) "⚠ אין טקסט אחרי המילה$fb" else "🛠 שיפור לאפליקציה  ·  " + raw.take(60)
            return
        }
        if (t == Store.TYPE_ALARM) {
            val r = AlarmParser.parse(raw, LocalDateTime.now())
            val a = r.alarm
            preview.text = when {
                raw.isBlank() -> if (auto) "⚠ חסר תוכן$fb" else ""
                a != null -> "⏰ " + a.describe() + "  ·  " + a.message
                else -> "⚠ " + (r.error ?: "") + fb
            }
            return
        }
        if (t == Store.TYPE_EVENT) {
            val e = EventParser.parse(raw, LocalDateTime.now())
            preview.text = when {
                raw.isBlank() -> if (auto) "⚠ חסר תוכן$fb" else ""
                e == null -> "⚠ לא זיהיתי תאריך או שעה$fb"
                else -> "📅 " + describe(e) + "  ·  " + e.title
            }
            return
        }
        val p = Expense.parse(raw)
        preview.text = when {
            raw.isBlank() -> if (auto) "⚠ חסר תוכן$fb" else ""
            p == null -> "⚠ לא זיהיתי סכום$fb"
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
        if (code == 4) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) save()
            else Toast.makeText(this, "אין הרשאה לאנשי קשר", Toast.LENGTH_LONG).show()
            return
        }
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
                val stripped = AutoType.stripSaveCommand(spoken)
                val add = stripped ?: spoken
                input.setText(if (cur.isBlank()) add else if (add.isBlank()) cur else "$cur $add")
                input.setSelection(input.text.length)
                // "...שמור" בסוף ההכתבה = שמירה אוטומטית, בלי לחיצה על הכפתור
                if (stripped != null) {
                    if (input.text.isNotBlank() || audioFile != null) save()
                    else Toast.makeText(this, "אין מה לשמור", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------- הודעה בווטסאפ (לא נשמרת) ----------

    private fun sendMessage(text: String, auto: Boolean) {
        if (!Contacts.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 4)
            return
        }
        val r = Contacts.resolve(this, text)
        if (r == null) {
            val split = MessageParser.splits(text).firstOrNull()
            if (split == null) {
                Toast.makeText(this, "כתוב: הודעה לשם, ואז ההודעה", Toast.LENGTH_LONG).show()
            } else {
                pickContact(split, auto)
            }
            return
        }
        if (r.numbers.size == 1) {
            openWhatsApp(r.numbers[0].second, r.body)
            return
        }
        // כמה התאמות: בוחרים מהן, או מחפשים באנשי קשר אחרים; הבחירה נזכרת ככינוי
        pickContact(MessageParser.Split(r.spoken, r.body), auto, r.numbers)
    }

    private data class Pick(val label: String, val name: String, val number: String)

    private var pickRefresh: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        // חזרה מאפליקציית אנשי הקשר (אחרי הוספת איש קשר): מרעננים את הרשימה הפתוחה
        pickRefresh?.invoke()
    }

    /**
     * בחירת איש קשר: אם יש כמה התאמות (candidates) הן מוצגות קודם; שורת החיפוש מחפשת בכל אנשי הקשר.
     * מה שנבחר נשמר ככינוי למה שנאמר, וההודעה נפתחת בווטסאפ. השורה הראשונה פותחת את הבחירה בתוך
     * ווטסאפ (לקבוצות ולצ'אטים שאינם אנשי קשר) ולא נשמרת ככינוי.
     */
    private fun pickContact(split: MessageParser.Split, auto: Boolean, candidates: List<Pair<String, String>>? = null) {
        fun load() = Contacts.all(this).map { Pick(it.first, it.first, it.second) }
        var all = load()
        val shareRow = Pick("📤 קבוצה או צ'אט אחר (בוחרים בתוך ווטסאפ)", "", "")
        val said = MessageParser.nameVariants(split.name).last()
        val initial = listOf(shareRow) +
            (candidates?.map { Pick(it.first + "  ·  +" + it.second, it.first, it.second) } ?: all)
        var shown = initial
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, initial.map { it.label }.toMutableList())
        val search = EditText(this).apply {
            hint = if (candidates != null) "חיפוש באנשי קשר אחרים" else "חיפוש"
            isSingleLine = true
        }
        val listView = android.widget.ListView(this).apply { this.adapter = adapter }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(search)
            addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))
        }
        fun refilter() {
            val q = MessageParser.norm(search.text?.toString() ?: "")
            shown = if (q.isEmpty() && candidates != null) initial
            else listOf(shareRow) + all.filter { q.isEmpty() || MessageParser.norm(it.name).contains(q) }
            adapter.clear()
            adapter.addAll(shown.map { it.label })
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refilter()
        })
        val b = AlertDialog.Builder(this)
            .setTitle(if (candidates != null) "למי לשלוח \"" + said + "\"?" else "לא נמצא \"" + said + "\". מי זה?")
            .setView(box)
            .setNegativeButton("ביטול", null)
            .setNeutralButton("איש קשר חדש", null) // מאזין משלו למטה, כדי שהחלון לא ייסגר
        if (auto && candidates == null) b.setPositiveButton("שמור כהערה") { _, _ ->
            forceNote = true
            save()
        }
        val dialog = b.create()
        dialog.setOnDismissListener { pickRefresh = null }
        listView.setOnItemClickListener { _, _, i, _ ->
            val p = shown[i]
            dialog.dismiss()
            if (p === shareRow) {
                shareToWhatsApp(split.body)
                return@setOnItemClickListener
            }
            Aliases.add(this, MessageParser.nameVariants(split.name), p.name, p.number)
            Toast.makeText(this, "נזכר: " + said + " = " + p.name, Toast.LENGTH_SHORT).show()
            openWhatsApp(p.number, split.body)
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_INSERT, android.provider.ContactsContract.Contacts.CONTENT_URI)
                        .putExtra(android.provider.ContactsContract.Intents.Insert.NAME, said)
                )
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "לא נמצאה אפליקציית אנשי קשר", Toast.LENGTH_LONG).show()
            }
        }
        pickRefresh = {
            all = load()
            refilter()
        }
    }

    /** פותח את הבחירה בתוך ווטסאפ (אנשי קשר וקבוצות) עם ההודעה כתובה; בוחרים ושולחים שם. */
    private fun shareToWhatsApp(body: String) {
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                startActivity(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body).setPackage(pkg)
                )
                finish()
                return
            } catch (e: ActivityNotFoundException) {
                // ננסה את הבא
            }
        }
        Toast.makeText(this, "ווטסאפ לא מותקן", Toast.LENGTH_LONG).show()
    }

    /** פותח את שיחת הווטסאפ עם ההודעה כתובה; נשאר ללחוץ על שליחה. */
    private fun openWhatsApp(number: String, body: String) {
        val uri = android.net.Uri.parse("https://wa.me/" + number + "?text=" + android.net.Uri.encode(body))
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b", null)) {
            try {
                if (AutoSend.enabled(this)) AutoSend.arm() // שירות הנגישות ילחץ על "שלח"
                startActivity(Intent(Intent.ACTION_VIEW, uri).apply { if (pkg != null) setPackage(pkg) })
                finish()
                return
            } catch (e: ActivityNotFoundException) {
                // ננסה את הבא
            }
        }
        Toast.makeText(this, "ווטסאפ לא מותקן", Toast.LENGTH_LONG).show()
    }

    // ---------- חיפוש בגוגל (לא נשמר) ----------

    private fun googleSearch(query: String) {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, query)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(query))))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, "לא נמצא דפדפן לחיפוש", Toast.LENGTH_LONG).show()
                return
            }
        }
        finish() // לא נשמר, וגם הקלטה שלא נשמרה נמחקת ב-onDestroy
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
        val original = input.text.toString().trim()
        var (type, text) = effective()
        text = text.trim()
        // סיווג אוטומטי שלא הצליח (אין תאריך/שעה/סכום) נשמר כהערה רגילה עם הטקסט המלא
        if (this.type == Store.TYPE_NOTE && type != Store.TYPE_NOTE) {
            val ok = when (type) {
                Store.TYPE_EVENT -> EventParser.parse(text, LocalDateTime.now()) != null
                Store.TYPE_ALARM -> AlarmParser.parse(text, LocalDateTime.now()).alarm != null
                Store.TYPE_EXPENSE -> Expense.parse(text) != null
                else -> text.isNotEmpty()
            }
            if (!ok) {
                type = Store.TYPE_NOTE
                text = original
            }
        }
        if (type == AutoType.TYPE_MESSAGE) {
            sendMessage(text, this.type == Store.TYPE_NOTE)
            return
        }
        if (type == AutoType.TYPE_SEARCH) {
            if (text.isEmpty()) {
                Toast.makeText(this, "כתוב מה לחפש", Toast.LENGTH_SHORT).show()
                return
            }
            googleSearch(text)
            return
        }
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
        if (type == Store.TYPE_FEEDBACK) Feedback.flush(this)
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
