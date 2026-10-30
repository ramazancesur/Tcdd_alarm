package app.tcddalarm

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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

/** Tek ekranlı arayüz: güzergah, tarihler, saat aralığı, yolcular ve takip. */
class MainActivity : Activity() {

    companion object {
        private const val REQ_NOTIFICATIONS = 11
    }

    private val widgets by lazy { FormWidgets(this) }
    private val picker by lazy { StationPicker(this, store, widgets, ::toast) }

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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(widgets.dp(16), widgets.dp(8), widgets.dp(16), widgets.dp(32))
        }
        addRoute(root)
        addSchedule(root)
        addPassengers(root)
        addSeatOptions(root)
        addAdvanced(root)
        addControls(root)
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
        watchPassengerCount()
        rebuildGenders(1, emptyList())
    }

    private fun addRoute(root: LinearLayout) {
        root.addView(widgets.header("Güzergah"))
        btnFrom = widgets.button("Nereden: seçin") { pickStation(true) }
        btnTo = widgets.button("Nereye: seçin") { pickStation(false) }
        root.addView(btnFrom)
        root.addView(btnTo)
    }

    private fun addSchedule(root: LinearLayout) {
        root.addView(widgets.header("Tarih ve saat aralığı"))
        tvDates = TextView(this).apply { textSize = 15f }
        root.addView(tvDates)
        val datesRow = widgets.row()
        datesRow.addView(widgets.button("Tarih ekle") { addDate() }, widgets.half())
        datesRow.addView(
            widgets.button("Tarihleri temizle") {
                dates.clear()
                renderDates()
            },
            widgets.half(),
        )
        root.addView(datesRow)
        val timeRow = widgets.row()
        btnTimeFrom = widgets.button("") { pickTime(true) }
        btnTimeTo = widgets.button("") { pickTime(false) }
        timeRow.addView(btnTimeFrom, widgets.half())
        timeRow.addView(btnTimeTo, widgets.half())
        root.addView(timeRow)
        root.addView(widgets.hint("Yalnızca bu saat aralığında kalkan seferler izlenir."))
    }

    private fun addPassengers(root: LinearLayout) {
        root.addView(widgets.header("Yolcular"))
        spCount = widgets.spinner(
            listOf("1 yolcu", "2 yolcu", "3 yolcu", "4 yolcu", "5 yolcu", "6 yolcu"),
        )
        root.addView(spCount)
        boxGenders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(boxGenders)
        cbSide = widgets.check("Yan yana oturmak istiyoruz")
        cbFacing = widgets.check("Karşılıklı koltuk istiyoruz")
        root.addView(cbSide)
        root.addView(cbFacing)
    }

    private fun addSeatOptions(root: LinearLayout) {
        root.addView(widgets.header("Koltuk türü"))
        cbEco = widgets.check("Ekonomi").apply { isChecked = true }
        cbBiz = widgets.check("Business").apply { isChecked = true }
        cbWheel = widgets.check("Tekerlekli sandalye")
        root.addView(cbEco)
        root.addView(cbBiz)
        root.addView(cbWheel)

        root.addView(widgets.header("Koltuk yönü"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        rbAny = widgets.radio("Fark etmez")
        rbFwd = widgets.radio("Trenin gidiş yönüne (ileri)")
        rbBack = widgets.radio("Gidiş yönüne ters (geri)")
        group.addView(rbAny)
        group.addView(rbFwd)
        group.addView(rbBack)
        rbAny.isChecked = true
        root.addView(group)
        cbStrict = widgets.check("Yalnızca tüm tercihlere uyan koltuk varsa haber ver")
        root.addView(cbStrict)
        root.addView(
            widgets.hint(
                "İşaretli değilse en yakın koltuk önerilir ve " +
                    "karşılanamayan tercih bildirimde belirtilir.",
            ),
        )
    }

    private fun addAdvanced(root: LinearLayout) {
        root.addView(widgets.header("Kontrol aralığı"))
        etInterval = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("180")
        }
        root.addView(etInterval)
        root.addView(
            widgets.hint(
                "Saniye cinsinden, en az 60. Çok sık sorgulamak " +
                    "sunucu tarafından engellenmenize yol açabilir.",
            ),
        )

        root.addView(widgets.header("Gelişmiş"))
        etToken = EditText(this).apply {
            hint = "TCDD Authorization değeri (token)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        root.addView(etToken)
        root.addView(
            widgets.hint(
                "e-bilet sitesinde (bilgisayar tarayıcısı) F12 → Network → " +
                    "bir isteğin 'Authorization' başlığı. Süresi dolunca yenileyin.",
            ),
        )
        etBase = EditText(this).apply {
            setText(TcddApi.DEFAULT_BASE)
            setSingleLine(true)
        }
        root.addView(etBase)
        root.addView(widgets.hint("Sunucu adresi (genelde değiştirmeyin)."))
        spRot = widgets.spinner(
            listOf(
                "İleri yön rotation değeri: ayarlanmadı",
                "İleri yön = rotation 0",
                "İleri yön = rotation 180",
            ),
        )
        root.addView(spRot)
        root.addView(
            widgets.hint(
                "Koltuk yönü tercihi için gerekli. Günlükte/dökümde trenin " +
                    "gidiş yönüne bakan koltuğun rotation değerini görüp seçin.",
            ),
        )
        root.addView(widgets.button("İstasyon listesini yenile") {
            store.clearStations()
            toast("İstasyon listesi bir sonraki seçimde yeniden indirilecek.")
        })
        root.addView(widgets.button("Pil kısıtlamasını kaldır (arka planda çalışması için)") {
            openBatterySettings()
        })
        root.addView(widgets.button("Bildirimi dene") { sendTestNotification() })
    }

    private fun addControls(root: LinearLayout) {
        root.addView(widgets.header("Takip"))
        tvStatus = TextView(this).apply { textSize = 15f }
        root.addView(tvStatus)
        val row = widgets.row()
        btnStart = widgets.button("Takibi başlat") { startClicked() }
        btnStop = widgets.button("Durdur") { stopClicked() }
        row.addView(btnStart, widgets.half())
        row.addView(btnStop, widgets.half())
        root.addView(row)
        tvLog = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, widgets.dp(8), 0, 0)
        }
        root.addView(tvLog)
    }

    private fun watchPassengerCount() {
        spCount.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) countTouched = true
            false
        }
        spCount.setOnKeyListener { _, _, event ->
            if (event.action == KeyEvent.ACTION_DOWN) countTouched = true
            false
        }
        spCount.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (!countTouched) return
                countTouched = false
                rebuildGenders(position + 1, emptyList())
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (error: Exception) {
            toast("Ayarlar açılamadı: ${error.message}")
        }
    }

    private fun sendTestNotification() {
        withNotificationPermission {
            Notifier.ensureChannels(this)
            Notifier.alert(this, 99, "Deneme bildirimi", "Bildirimler çalışıyor ✅")
        }
    }

    private fun currentGenders(): List<String> =
        genderSpinners.map { if (it.selectedItemPosition == 1) "E" else "K" }

    private fun rebuildGenders(count: Int, keep: List<String>) {
        val previous = keep.ifEmpty { currentGenders() }
        boxGenders.removeAllViews()
        genderSpinners.clear()
        for (index in 0 until count) {
            val label = TextView(this).apply {
                text = "${index + 1}. yolcunun cinsiyeti"
                setPadding(0, widgets.dp(6), 0, 0)
            }
            val spinner = widgets.spinner(listOf("Kadın", "Erkek"))
            spinner.setSelection(if (previous.getOrNull(index) == "E") 1 else 0)
            boxGenders.addView(label)
            boxGenders.addView(spinner)
            genderSpinners.add(spinner)
        }
        cbSide.text = if (count > 2) {
            "Aynı sırada oturmak istiyoruz"
        } else {
            "Yan yana oturmak istiyoruz"
        }
        cbSide.isEnabled = count > 1
        cbFacing.isEnabled = count > 1
        if (count == 1) {
            cbSide.isChecked = false
            cbFacing.isChecked = false
        }
    }

    private fun renderStations() {
        btnFrom.text = "Nereden: ${from?.name ?: "seçin"}"
        btnTo.text = "Nereye: ${to?.name ?: "seçin"}"
    }

    private fun pickStation(isFrom: Boolean) {
        picker.show(
            choosingOrigin = isFrom,
            origin = { from },
            destination = { to },
            token = etToken.text.toString().trim(),
            base = etBase.text.toString().trim().ifEmpty { TcddApi.DEFAULT_BASE },
        ) { nextFrom, nextTo ->
            from = nextFrom
            to = nextTo
            renderStations()
        }
    }

    private fun renderDates() {
        val format = DateTimeFormatter.ofPattern("dd.MM.yyyy, EEEE", Locale.forLanguageTag("tr-TR"))
        tvDates.text = if (dates.isEmpty()) {
            "Henüz tarih seçilmedi"
        } else {
            dates.joinToString("\n") { LocalDate.parse(it).format(format) }
        }
    }

    private fun renderTimes() {
        btnTimeFrom.text = "Başlangıç: $timeFrom"
        btnTimeTo.text = "Bitiş: $timeTo"
    }

    private fun addDate() {
        val calendar = Calendar.getInstance()
        val dialog = DatePickerDialog(
            this,
            { _, year, month, day ->
                dates.add(String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day))
                renderDates()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        )
        dialog.datePicker.minDate = System.currentTimeMillis() - 1000L
        dialog.show()
    }

    private fun pickTime(isFrom: Boolean) {
        val parts = (if (isFrom) timeFrom else timeTo).split(":")
        TimePickerDialog(
            this,
            { _, hour, minute ->
                val text = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
                if (isFrom) timeFrom = text else timeTo = text
                renderTimes()
            },
            parts[0].toInt(),
            parts[1].toInt(),
            true,
        ).show()
    }

    private fun restore() {
        etToken.setText(store.token)
        val saved = store.config
        if (saved == null) {
            renderStations()
            renderDates()
            renderTimes()
            return
        }
        val cached = store.cachedStations()
        from = cached?.firstOrNull { it.id == saved.fromId }
            ?: Station(saved.fromId, saved.fromName, emptyList())
        to = cached?.firstOrNull { it.id == saved.toId }
            ?: Station(saved.toId, saved.toName, emptyList())
        dates.clear()
        dates.addAll(saved.dates)
        timeFrom = saved.timeFrom
        timeTo = saved.timeTo
        spCount.setSelection((saved.genders.size - 1).coerceIn(0, 5))
        rebuildGenders(saved.genders.size, saved.genders)
        cbSide.isChecked = saved.sideBySide && saved.genders.size > 1
        cbFacing.isChecked = saved.facing && saved.genders.size > 1
        cbEco.isChecked = Kind.ECONOMY in saved.kinds
        cbBiz.isChecked = Kind.BUSINESS in saved.kinds
        cbWheel.isChecked = Kind.WHEELCHAIR in saved.kinds
        when (saved.direction) {
            "F" -> rbFwd.isChecked = true
            "B" -> rbBack.isChecked = true
            else -> rbAny.isChecked = true
        }
        cbStrict.isChecked = saved.strict
        etInterval.setText(saved.intervalSec.toString())
        etBase.setText(saved.baseUrl)
        spRot.setSelection(
            when (saved.forwardRotation) {
                0 -> 1
                180 -> 2
                else -> 0
            },
        )
        renderStations()
        renderDates()
        renderTimes()
    }

    private fun buildConfig(): Config? {
        val origin = from
        val destination = to
        val error = configError(origin, destination)
        if (error != null) {
            toast(error)
            return null
        }
        val interval = etInterval.text.toString().trim().toIntOrNull()
        if (interval == null || interval < 60 || interval > 86400) {
            toast("Kontrol aralığı 60 ile 86400 saniye arasında olmalı")
            return null
        }
        val kinds = selectedKinds()
        if (kinds.isEmpty()) {
            toast("En az bir koltuk türü seçin")
            return null
        }
        return Config(
            fromId = origin!!.id,
            fromName = origin.name,
            toId = destination!!.id,
            toName = destination.name,
            dates = dates.toList(),
            timeFrom = timeFrom,
            timeTo = timeTo,
            genders = currentGenders(),
            kinds = kinds,
            sideBySide = genderSpinners.size > 1 && cbSide.isChecked,
            facing = genderSpinners.size > 1 && cbFacing.isChecked,
            direction = selectedDirection(),
            strict = cbStrict.isChecked,
            intervalSec = interval,
            forwardRotation = selectedRotation(),
            baseUrl = etBase.text.toString().trim().ifEmpty { TcddApi.DEFAULT_BASE },
        )
    }

    private fun configError(origin: Station?, destination: Station?): String? {
        if (origin == null || destination == null) {
            return "Önce nereden ve nereye istasyonlarını seçin"
        }
        if (origin.id == destination.id) return "Kalkış ve varış istasyonu aynı olamaz"
        if (dates.isEmpty()) return "En az bir tarih ekleyin"
        if (timeFrom > timeTo) return "Başlangıç saati bitişten sonra olamaz"
        return null
    }

    private fun selectedKinds(): LinkedHashSet<Kind> {
        val kinds = LinkedHashSet<Kind>()
        if (cbEco.isChecked) kinds.add(Kind.ECONOMY)
        if (cbBiz.isChecked) kinds.add(Kind.BUSINESS)
        if (cbWheel.isChecked) kinds.add(Kind.WHEELCHAIR)
        return kinds
    }

    private fun selectedDirection(): String = when {
        rbFwd.isChecked -> "F"
        rbBack.isChecked -> "B"
        else -> "A"
    }

    private fun selectedRotation(): Int? = when (spRot.selectedItemPosition) {
        1 -> 0
        2 -> 180
        else -> null
    }

    private fun withNotificationPermission(action: () -> Unit) {
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingAction = action
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        } else {
            action()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIFICATIONS) return
        val action = pendingAction
        pendingAction = null
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action?.invoke()
        } else {
            toast("Bildirim izni verilmedi; uyarı alamazsınız. Telefon ayarlarından izin verin.")
        }
    }

    private fun startClicked() {
        val config = buildConfig() ?: return
        withNotificationPermission {
            store.config = config
            store.token = etToken.text.toString().trim()
            store.clearState()
            if (store.token.isEmpty()) {
                Bus.log(
                    "Uyarı: token girilmedi. Sunucu isteği reddederse " +
                        "Gelişmiş bölümünden token ekleyin.",
                )
            }
            Notifier.ensureChannels(this)
            val intent = Intent(this, MonitorService::class.java)
                .setAction(MonitorService.ACTION_START)
            startForegroundService(intent)
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
        tvLog.text = if (lines.isEmpty()) {
            "Henüz kayıt yok."
        } else {
            lines.takeLast(80).reversed().joinToString("\n")
        }
    }
}
