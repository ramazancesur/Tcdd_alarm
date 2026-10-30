package app.tcddalarm

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.TreeSet

/** Tek ekranlı arayüz: güzergah, tarihler, saat aralığı, yolcular, koltuk tercihleri ve takip kontrolleri. */
class MainActivity : Activity() {

    companion object {
        private const val REQ_NOTIFICATIONS = 11
    }

    private lateinit var store: Store

    private var from: Station? = null
    private var to: Station? = null
    private val dates = TreeSet<String>()
    private var timeFrom = "00:00"
    private var timeTo = "23:59"
    private val genderSpinners = ArrayList<Spinner>()
    private var pendingAction: (() -> Unit)? = null
    private var countTouched = false

    private lateinit var btnFrom: Button
    private lateinit var btnTo: Button
    private lateinit var tvDates: TextView
    private lateinit var btnTimeFrom: Button
    private lateinit var btnTimeTo: Button
    private lateinit var spCount: Spinner
    private lateinit var boxGenders: LinearLayout
    private lateinit var cbSide: CheckBox
    private lateinit var cbFacing: CheckBox
    private lateinit var cbEco: CheckBox
    private lateinit var cbBiz: CheckBox
    private lateinit var cbWheel: CheckBox
    private lateinit var rbAny: RadioButton
    private lateinit var rbFwd: RadioButton
    private lateinit var rbBack: RadioButton
    private lateinit var cbStrict: CheckBox
    private lateinit var etInterval: EditText
    private lateinit var etToken: EditText
    private lateinit var etBase: EditText
    private lateinit var spRot: Spinner
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    // --- Yaşam döngüsü ---------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        buildUi()
        restore()
    }

    override fun onResume() {
        super.onResume()
        Bus.listener = { runOnUiThread { refreshStatus() } }
        refreshStatus()
    }

    override fun onPause() {
        Bus.listener = null
        super.onPause()
    }

    // --- Arayüz kurulumu -------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun header(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 17f
        tv.setTypeface(null, Typeface.BOLD)
        tv.setPadding(0, dp(20), 0, dp(4))
        return tv
    }

    private fun hint(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 12f
        return tv
    }

    private fun button(t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.transformationMethod = null
        b.setOnClickListener { onClick() }
        return b
    }

    private fun check(t: String): CheckBox {
        val c = CheckBox(this)
        c.text = t
        return c
    }

    private fun radio(t: String): RadioButton {
        val r = RadioButton(this)
        r.id = View.generateViewId()
        r.text = t
        return r
    }

    private fun spinner(items: List<String>): Spinner {
        val s = Spinner(this)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        return s
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(8), dp(16), dp(32))

        // Güzergah
        root.addView(header("Güzergah"))
        btnFrom = button("Nereden: seçin") { pickStation(true) }
        btnTo = button("Nereye: seçin") { pickStation(false) }
        root.addView(btnFrom)
        root.addView(btnTo)

        // Tarih ve saat
        root.addView(header("Tarih ve saat aralığı"))
        tvDates = TextView(this)
        tvDates.textSize = 15f
        root.addView(tvDates)
        val dateRow = LinearLayout(this)
        dateRow.orientation = LinearLayout.HORIZONTAL
        dateRow.addView(button("Tarih ekle") { addDate() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dateRow.addView(button("Tarihleri temizle") { dates.clear(); renderDates() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(dateRow)
        val timeRow = LinearLayout(this)
        timeRow.orientation = LinearLayout.HORIZONTAL
        btnTimeFrom = button("") { pickTime(true) }
        btnTimeTo = button("") { pickTime(false) }
        timeRow.addView(btnTimeFrom, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(btnTimeTo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(timeRow)
        root.addView(hint("Yalnızca bu saat aralığında kalkan seferler izlenir."))

        // Yolcular
        root.addView(header("Yolcular"))
        spCount = spinner(listOf("1 yolcu", "2 yolcu", "3 yolcu", "4 yolcu", "5 yolcu", "6 yolcu"))
        root.addView(spCount)
        boxGenders = LinearLayout(this)
        boxGenders.orientation = LinearLayout.VERTICAL
        root.addView(boxGenders)
        cbSide = check("Yan yana oturmak istiyoruz")
        cbFacing = check("Karşılıklı koltuk istiyoruz")
        root.addView(cbSide)
        root.addView(cbFacing)

        // Koltuk türü
        root.addView(header("Koltuk türü"))
        cbEco = check("Ekonomi")
        cbBiz = check("Business")
        cbWheel = check("Tekerlekli sandalye")
        cbEco.isChecked = true
        cbBiz.isChecked = true
        root.addView(cbEco)
        root.addView(cbBiz)
        root.addView(cbWheel)

        // Koltuk yönü
        root.addView(header("Koltuk yönü"))
        val rg = RadioGroup(this)
        rg.orientation = RadioGroup.VERTICAL
        rbAny = radio("Fark etmez")
        rbFwd = radio("Trenin gidiş yönüne (ileri)")
        rbBack = radio("Gidiş yönüne ters (geri)")
        rg.addView(rbAny)
        rg.addView(rbFwd)
        rg.addView(rbBack)
        rbAny.isChecked = true
        root.addView(rg)
        cbStrict = check("Yalnızca tüm tercihlere uyan koltuk varsa haber ver")
        root.addView(cbStrict)
        root.addView(hint("İşaretli değilse en yakın koltuk önerilir ve karşılanamayan tercih bildirimde belirtilir."))

        // Takip
        root.addView(header("Kontrol aralığı"))
        etInterval = EditText(this)
        etInterval.inputType = InputType.TYPE_CLASS_NUMBER
        etInterval.setText("180")
        root.addView(etInterval)
        root.addView(hint("Saniye cinsinden, en az 60. Çok sık sorgulamak sunucu tarafından engellenmenize yol açabilir."))

        // Gelişmiş
        root.addView(header("Gelişmiş"))
        etToken = EditText(this)
        etToken.hint = "TCDD Authorization değeri (token)"
        etToken.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        etToken.minLines = 2
        root.addView(etToken)
        root.addView(hint("e-bilet sitesinde (bilgisayar tarayıcısı) F12 → Network → bir isteğin 'Authorization' başlığı. Süresi dolunca yenileyin."))
        etBase = EditText(this)
        etBase.setText(TcddApi.DEFAULT_BASE)
        etBase.setSingleLine(true)
        root.addView(etBase)
        root.addView(hint("Sunucu adresi (genelde değiştirmeyin)."))
        spRot = spinner(listOf("İleri yön rotation değeri: ayarlanmadı", "İleri yön = rotation 0", "İleri yön = rotation 180"))
        root.addView(spRot)
        root.addView(hint("Koltuk yönü tercihi için gerekli. Günlükte/dökümde trenin gidiş yönüne bakan koltuğun rotation değerini görüp seçin."))
        root.addView(button("İstasyon listesini yenile") {
            store.clearStations()
            toast("İstasyon listesi bir sonraki seçimde yeniden indirilecek.")
        })
        root.addView(button("Pil kısıtlamasını kaldır (arka planda çalışması için)") {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                toast("Ayarlar açılamadı: ${e.message}")
            }
        })
        root.addView(button("Bildirimi dene") {
            withNotificationPermission {
                Notifier.ensureChannels(this)
                Notifier.alert(this, 99, "Deneme bildirimi", "Bildirimler çalışıyor ✅")
            }
        })

        // Başlat / Durdur
        root.addView(header("Takip"))
        tvStatus = TextView(this)
        tvStatus.textSize = 15f
        root.addView(tvStatus)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        btnStart = button("Takibi başlat") { startClicked() }
        btnStop = button("Durdur") { stopClicked() }
        row.addView(btnStart, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btnStop, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        tvLog = TextView(this)
        tvLog.typeface = Typeface.MONOSPACE
        tvLog.textSize = 12f
        tvLog.setTextIsSelectable(true)
        tvLog.setPadding(0, dp(8), 0, 0)
        root.addView(tvLog)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(root)
        setContentView(scroll)

        // Programatik setSelection da onItemSelected tetikler ve kayıtlı cinsiyetleri siler.
        // Yalnızca kullanıcının dokunuşu veya tuşu yeni satır açsın.
        spCount.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) countTouched = true
            false
        }
        spCount.setOnKeyListener { _, _, event ->
            if (event.action == KeyEvent.ACTION_DOWN) countTouched = true
            false
        }
        spCount.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!countTouched) return
                countTouched = false
                rebuildGenders(position + 1, emptyList())
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        rebuildGenders(1, emptyList())
    }

    // --- Yolcular --------------------------------------------------------------

    private fun currentGenders(): List<String> =
        genderSpinners.map { if (it.selectedItemPosition == 1) "E" else "K" }

    private fun rebuildGenders(n: Int, keep: List<String>) {
        val prev = if (keep.isNotEmpty()) keep else currentGenders()
        boxGenders.removeAllViews()
        genderSpinners.clear()
        for (i in 0 until n) {
            val label = TextView(this)
            label.text = "${i + 1}. yolcunun cinsiyeti"
            label.setPadding(0, dp(6), 0, 0)
            val sp = spinner(listOf("Kadın", "Erkek"))
            sp.setSelection(if (prev.getOrNull(i) == "E") 1 else 0)
            boxGenders.addView(label)
            boxGenders.addView(sp)
            genderSpinners.add(sp)
        }
        cbSide.text = if (n > 2) "Aynı sırada oturmak istiyoruz" else "Yan yana oturmak istiyoruz"
        cbSide.isEnabled = n > 1
        cbFacing.isEnabled = n > 1
        if (n == 1) {
            cbSide.isChecked = false
            cbFacing.isChecked = false
        }
    }

    // --- İstasyon seçimi -------------------------------------------------------

    private fun renderStations() {
        btnFrom.text = "Nereden: ${from?.name ?: "seçin"}"
        btnTo.text = "Nereye: ${to?.name ?: "seçin"}"
    }

    private fun loadStations(token: String, base: String): List<Station> {
        val cached = store.cachedStations()
        if (cached != null) return cached
        val raw = TcddApi(base, TcddApi.DEFAULT_CDN, token).fetchStationsRaw()
        val list = TcddApi.parseStations(parseJson(raw))
        if (list.isEmpty()) throw IllegalStateException("Liste boş ya da biçimi tanınmadı")
        store.cacheStationsRaw(raw)
        return list
    }

    private fun pickStation(isFrom: Boolean) {
        if (!isFrom && from == null) {
            toast("Önce kalkış istasyonunu seçin")
            return
        }
        val token = etToken.text.toString().trim()
        val base = etBase.text.toString().trim().ifEmpty { TcddApi.DEFAULT_BASE }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), 0)
        val search = EditText(this)
        search.hint = "İstasyon adı yazın"
        search.setSingleLine(true)
        val status = TextView(this)
        status.text = "İstasyonlar yükleniyor…"
        val list = ListView(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        list.adapter = adapter
        box.addView(search)
        box.addView(status)
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isFrom) "Nereden" else "Nereye")
            .setView(box)
            .setNegativeButton("Kapat", null)
            .create()
        dialog.show()

        var all: List<Station> = emptyList()
        var shown: List<Station> = emptyList()

        fun refilter() {
            val q = trNorm(search.text.toString().trim())
            var base2 = all
            val f = from
            if (!isFrom && f != null && f.pairs.isNotEmpty()) {
                base2 = base2.filter { it.id in f.pairs }
            }
            shown = base2.filter { q.isEmpty() || trNorm(it.name).contains(q) }.take(80)
            adapter.clear()
            adapter.addAll(shown.map { it.name })
            adapter.notifyDataSetChanged()
            status.text = "${shown.size} istasyon"
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                refilter()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        list.setOnItemClickListener { _, _, position, _ ->
            val st = shown.getOrNull(position)
            if (st != null) {
                if (isFrom) {
                    from = st
                    val t = to
                    if (t != null && (t.id == st.id || (st.pairs.isNotEmpty() && t.id !in st.pairs))) to = null
                } else {
                    to = st
                }
                renderStations()
                dialog.dismiss()
            }
        }

        Thread {
            try {
                val loaded = loadStations(token, base)
                runOnUiThread {
                    all = loaded
                    // Kayıttan geri yüklenen kalkış istasyonunun "pairs" bilgisini tamamla
                    val f = from
                    if (f != null && f.pairs.isEmpty()) {
                        from = loaded.firstOrNull { it.id == f.id } ?: f
                    }
                    refilter()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "İstasyon listesi alınamadı: ${e.message}\nGelişmiş bölümüne token girip tekrar deneyin."
                }
            }
        }.start()
    }

    // --- Tarih / saat ----------------------------------------------------------

    private fun renderDates() {
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy, EEEE", Locale.forLanguageTag("tr-TR"))
        tvDates.text = if (dates.isEmpty()) {
            "Henüz tarih seçilmedi"
        } else {
            dates.joinToString("\n") { LocalDate.parse(it).format(fmt) }
        }
    }

    private fun renderTimes() {
        btnTimeFrom.text = "Başlangıç: $timeFrom"
        btnTimeTo.text = "Bitiş: $timeTo"
    }

    private fun addDate() {
        val c = Calendar.getInstance()
        val dlg = DatePickerDialog(
            this,
            { _, y, m, d ->
                dates.add(String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d))
                renderDates()
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        )
        dlg.datePicker.minDate = System.currentTimeMillis() - 1000L
        dlg.show()
    }

    private fun pickTime(isFrom: Boolean) {
        val parts = (if (isFrom) timeFrom else timeTo).split(":")
        TimePickerDialog(
            this,
            { _, h, m ->
                val s = String.format(Locale.ROOT, "%02d:%02d", h, m)
                if (isFrom) timeFrom = s else timeTo = s
                renderTimes()
            },
            parts[0].toInt(), parts[1].toInt(), true
        ).show()
    }

    // --- Kayıttan geri yükleme -------------------------------------------------

    private fun restore() {
        etToken.setText(store.token)
        val c = store.config
        if (c == null) {
            renderStations()
            renderDates()
            renderTimes()
            return
        }
        val cached = store.cachedStations()
        from = cached?.firstOrNull { it.id == c.fromId } ?: Station(c.fromId, c.fromName, emptyList())
        to = cached?.firstOrNull { it.id == c.toId } ?: Station(c.toId, c.toName, emptyList())
        dates.clear()
        dates.addAll(c.dates)
        timeFrom = c.timeFrom
        timeTo = c.timeTo
        spCount.setSelection((c.genders.size - 1).coerceIn(0, 5))
        rebuildGenders(c.genders.size, c.genders)
        cbSide.isChecked = c.sideBySide && c.genders.size > 1
        cbFacing.isChecked = c.facing && c.genders.size > 1
        cbEco.isChecked = Kind.ECONOMY in c.kinds
        cbBiz.isChecked = Kind.BUSINESS in c.kinds
        cbWheel.isChecked = Kind.WHEELCHAIR in c.kinds
        when (c.direction) {
            "F" -> rbFwd.isChecked = true
            "B" -> rbBack.isChecked = true
            else -> rbAny.isChecked = true
        }
        cbStrict.isChecked = c.strict
        etInterval.setText(c.intervalSec.toString())
        etBase.setText(c.baseUrl)
        spRot.setSelection(
            when (c.forwardRotation) {
                0 -> 1
                180 -> 2
                else -> 0
            }
        )
        renderStations()
        renderDates()
        renderTimes()
    }

    // --- Ayarlardan yapılandırma ----------------------------------------------

    private fun buildConfig(): Config? {
        val f = from
        val t = to
        if (f == null || t == null) {
            toast("Önce nereden ve nereye istasyonlarını seçin")
            return null
        }
        if (f.id == t.id) {
            toast("Kalkış ve varış istasyonu aynı olamaz")
            return null
        }
        if (dates.isEmpty()) {
            toast("En az bir tarih ekleyin")
            return null
        }
        if (timeFrom > timeTo) {
            toast("Başlangıç saati bitişten sonra olamaz")
            return null
        }
        val interval = etInterval.text.toString().trim().toIntOrNull()
        if (interval == null || interval < 60 || interval > 86400) {
            toast("Kontrol aralığı 60 ile 86400 saniye arasında olmalı")
            return null
        }
        val kinds = LinkedHashSet<Kind>()
        if (cbEco.isChecked) kinds.add(Kind.ECONOMY)
        if (cbBiz.isChecked) kinds.add(Kind.BUSINESS)
        if (cbWheel.isChecked) kinds.add(Kind.WHEELCHAIR)
        if (kinds.isEmpty()) {
            toast("En az bir koltuk türü seçin")
            return null
        }
        val direction = when {
            rbFwd.isChecked -> "F"
            rbBack.isChecked -> "B"
            else -> "A"
        }
        val rot: Int? = when (spRot.selectedItemPosition) {
            1 -> 0
            2 -> 180
            else -> null
        }
        val n = genderSpinners.size
        return Config(
            fromId = f.id,
            fromName = f.name,
            toId = t.id,
            toName = t.name,
            dates = dates.toList(),
            timeFrom = timeFrom,
            timeTo = timeTo,
            genders = currentGenders(),
            kinds = kinds,
            sideBySide = n > 1 && cbSide.isChecked,
            facing = n > 1 && cbFacing.isChecked,
            direction = direction,
            strict = cbStrict.isChecked,
            intervalSec = interval,
            forwardRotation = rot,
            baseUrl = etBase.text.toString().trim().ifEmpty { TcddApi.DEFAULT_BASE }
        )
    }

    // --- Başlat / durdur -------------------------------------------------------

    private fun withNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAction = action
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        } else {
            action()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) {
            val action = pendingAction
            pendingAction = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                action?.invoke()
            } else {
                toast("Bildirim izni verilmedi; uyarı alamazsınız. Telefon ayarlarından izin verin.")
            }
        }
    }

    private fun startClicked() {
        val cfg = buildConfig() ?: return
        withNotificationPermission {
            store.config = cfg
            store.token = etToken.text.toString().trim()
            store.clearState()
            if (store.token.isEmpty()) {
                Bus.log("Uyarı: token girilmedi. Sunucu isteği reddederse Gelişmiş bölümünden token ekleyin.")
            }
            Notifier.ensureChannels(this)
            startForegroundService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_START))
            toast("Takip başlatıldı")
        }
    }

    private fun stopClicked() {
        store.running = false
        stopService(Intent(this, MonitorService::class.java))
        refreshStatus()
    }

    private fun refreshStatus() {
        val running = Bus.running
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
        tvStatus.text = if (running) "● Takip çalışıyor" else "○ Takip kapalı"
        val lines = Bus.snapshot()
        tvLog.text = if (lines.isEmpty()) "Henüz kayıt yok." else lines.takeLast(80).reversed().joinToString("\n")
    }
}
