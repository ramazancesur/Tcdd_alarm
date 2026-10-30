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
import android.net.Uri
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

    private var originStation: Station? = null
    private var destinationStation: Station? = null
    private val dates = TreeSet<String>()
    private var timeFrom = "00:00"
    private var timeTo = "23:59"
    private val genderSpinners = ArrayList<Spinner>()
    private var pendingAction: (() -> Unit)? = null
    private var passengerCountTouched = false

    private lateinit var originButton: Button
    private lateinit var destinationButton: Button
    private lateinit var datesText: TextView
    private lateinit var timeFromButton: Button
    private lateinit var timeToButton: Button
    private lateinit var passengerCountSpinner: Spinner
    private lateinit var genderLayout: LinearLayout
    private lateinit var sideBySideCheck: CheckBox
    private lateinit var facingCheck: CheckBox
    private lateinit var economyCheck: CheckBox
    private lateinit var businessCheck: CheckBox
    private lateinit var wheelchairCheck: CheckBox
    private lateinit var anyDirectionRadio: RadioButton
    private lateinit var forwardRadio: RadioButton
    private lateinit var backwardRadio: RadioButton
    private lateinit var strictCheck: CheckBox
    private lateinit var intervalInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var baseUrlInput: EditText
    private lateinit var rotationSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        buildUi()
        restore()
    }

    override fun onResume() {
        super.onResume()
        WatchStatus.listener = { runOnUiThread { refreshStatus() } }
        refreshStatus()
    }

    override fun onPause() {
        WatchStatus.listener = null
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
        originButton = widgets.button("Nereden: seçin") { pickStation(choosingOrigin = true) }
        destinationButton = widgets.button("Nereye: seçin") { pickStation(choosingOrigin = false) }
        root.addView(originButton)
        root.addView(destinationButton)
    }

    private fun addSchedule(root: LinearLayout) {
        root.addView(widgets.header("Tarih ve saat aralığı"))
        datesText = TextView(this).apply { textSize = 15f }
        root.addView(datesText)
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
        timeFromButton = widgets.button("") { pickTime(choosingStart = true) }
        timeToButton = widgets.button("") { pickTime(choosingStart = false) }
        timeRow.addView(timeFromButton, widgets.half())
        timeRow.addView(timeToButton, widgets.half())
        root.addView(timeRow)
        root.addView(widgets.hint("Yalnızca bu saat aralığında kalkan seferler izlenir."))
    }

    private fun addPassengers(root: LinearLayout) {
        root.addView(widgets.header("Yolcular"))
        passengerCountSpinner = widgets.spinner(
            listOf("1 yolcu", "2 yolcu", "3 yolcu", "4 yolcu", "5 yolcu", "6 yolcu"),
        )
        root.addView(passengerCountSpinner)
        genderLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(genderLayout)
        sideBySideCheck = widgets.check("Yan yana oturmak istiyoruz")
        facingCheck = widgets.check("Karşılıklı koltuk istiyoruz")
        root.addView(sideBySideCheck)
        root.addView(facingCheck)
    }

    private fun addSeatOptions(root: LinearLayout) {
        root.addView(widgets.header("Koltuk türü"))
        economyCheck = widgets.check("Ekonomi").apply { isChecked = true }
        businessCheck = widgets.check("Business").apply { isChecked = true }
        wheelchairCheck = widgets.check("Tekerlekli sandalye")
        root.addView(economyCheck)
        root.addView(businessCheck)
        root.addView(wheelchairCheck)

        root.addView(widgets.header("Koltuk yönü"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        anyDirectionRadio = widgets.radio("Fark etmez")
        forwardRadio = widgets.radio("Trenin gidiş yönüne (ileri)")
        backwardRadio = widgets.radio("Gidiş yönüne ters (geri)")
        group.addView(anyDirectionRadio)
        group.addView(forwardRadio)
        group.addView(backwardRadio)
        anyDirectionRadio.isChecked = true
        root.addView(group)
        strictCheck = widgets.check("Yalnızca tüm tercihlere uyan koltuk varsa haber ver")
        root.addView(strictCheck)
        root.addView(
            widgets.hint(
                "İşaretli değilse en yakın koltuk önerilir ve " +
                    "karşılanamayan tercih bildirimde belirtilir.",
            ),
        )
    }

    private fun addAdvanced(root: LinearLayout) {
        root.addView(widgets.header("Kontrol aralığı"))
        intervalInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("180")
        }
        root.addView(intervalInput)
        root.addView(
            widgets.hint(
                "Saniye cinsinden, en az 60. Çok sık sorgulamak " +
                    "sunucu tarafından engellenmenize yol açabilir.",
            ),
        )

        root.addView(widgets.header("Gelişmiş"))
        tokenInput = EditText(this).apply {
            hint = "TCDD Authorization değeri (token)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        root.addView(tokenInput)
        root.addView(
            widgets.hint(
                "e-bilet sitesinde (bilgisayar tarayıcısı) F12 → Network → " +
                    "bir isteğin 'Authorization' başlığı. Süresi dolunca yenileyin.",
            ),
        )
        baseUrlInput = EditText(this).apply {
            setText(TcddApi.DEFAULT_BASE)
            setSingleLine(true)
        }
        root.addView(baseUrlInput)
        root.addView(widgets.hint("Sunucu adresi (genelde değiştirmeyin)."))
        rotationSpinner = widgets.spinner(
            listOf(
                "İleri yön rotation değeri: ayarlanmadı",
                "İleri yön = rotation 0",
                "İleri yön = rotation 180",
            ),
        )
        root.addView(rotationSpinner)
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
        statusText = TextView(this).apply { textSize = 15f }
        root.addView(statusText)
        val row = widgets.row()
        startButton = widgets.button("Takibi başlat") { startClicked() }
        stopButton = widgets.button("Durdur") { stopClicked() }
        row.addView(startButton, widgets.half())
        row.addView(stopButton, widgets.half())
        root.addView(row)
        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, widgets.dp(8), 0, 0)
        }
        root.addView(logText)
    }

    private fun watchPassengerCount() {
        passengerCountSpinner.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) passengerCountTouched = true
            false
        }
        passengerCountSpinner.setOnKeyListener { _, _, event ->
            if (event.action == KeyEvent.ACTION_DOWN) passengerCountTouched = true
            false
        }
        passengerCountSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (!passengerCountTouched) return
                passengerCountTouched = false
                rebuildGenders(position + 1, emptyList())
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun openBatterySettings() {
        val exemption = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        try {
            startActivity(exemption)
        } catch (error: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (fallback: Exception) {
                toast("Ayarlar açılamadı: ${fallback.message}")
            }
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
        genderLayout.removeAllViews()
        genderSpinners.clear()
        for (index in 0 until count) {
            val label = TextView(this).apply {
                text = "${index + 1}. yolcunun cinsiyeti"
                setPadding(0, widgets.dp(6), 0, 0)
            }
            val spinner = widgets.spinner(listOf("Kadın", "Erkek"))
            spinner.setSelection(if (previous.getOrNull(index) == "E") 1 else 0)
            genderLayout.addView(label)
            genderLayout.addView(spinner)
            genderSpinners.add(spinner)
        }
        sideBySideCheck.text = if (count > 2) {
            "Aynı sırada oturmak istiyoruz"
        } else {
            "Yan yana oturmak istiyoruz"
        }
        sideBySideCheck.isEnabled = count > 1
        facingCheck.isEnabled = count > 1
        if (count == 1) {
            sideBySideCheck.isChecked = false
            facingCheck.isChecked = false
        }
    }

    private fun renderStations() {
        originButton.text = "Nereden: ${originStation?.name ?: "seçin"}"
        destinationButton.text = "Nereye: ${destinationStation?.name ?: "seçin"}"
    }

    private fun pickStation(choosingOrigin: Boolean) {
        picker.show(
            choosingOrigin = choosingOrigin,
            origin = { originStation },
            destination = { destinationStation },
            token = tokenInput.text.toString().trim(),
            base = baseUrlInput.text.toString().trim().ifEmpty { TcddApi.DEFAULT_BASE },
        ) { nextOrigin, nextDestination ->
            originStation = nextOrigin
            destinationStation = nextDestination
            renderStations()
        }
    }

    private fun renderDates() {
        val format = DateTimeFormatter.ofPattern("dd.MM.yyyy, EEEE", Locale.forLanguageTag("tr-TR"))
        datesText.text = if (dates.isEmpty()) {
            "Henüz tarih seçilmedi"
        } else {
            dates.joinToString("\n") { LocalDate.parse(it).format(format) }
        }
    }

    private fun renderTimes() {
        timeFromButton.text = "Başlangıç: $timeFrom"
        timeToButton.text = "Bitiş: $timeTo"
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

    private fun pickTime(choosingStart: Boolean) {
        val parts = (if (choosingStart) timeFrom else timeTo).split(":")
        TimePickerDialog(
            this,
            { _, hour, minute ->
                val text = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
                if (choosingStart) timeFrom = text else timeTo = text
                renderTimes()
            },
            parts[0].toInt(),
            parts[1].toInt(),
            true,
        ).show()
    }

    private fun restore() {
        tokenInput.setText(store.token)
        val saved = store.config
        if (saved == null) {
            renderStations()
            renderDates()
            renderTimes()
            return
        }
        val cached = store.cachedStations()
        originStation = cached?.firstOrNull { it.id == saved.originId }
            ?: Station(saved.originId, saved.originName, emptyList())
        destinationStation = cached?.firstOrNull { it.id == saved.destinationId }
            ?: Station(saved.destinationId, saved.destinationName, emptyList())
        dates.clear()
        dates.addAll(saved.dates)
        timeFrom = saved.timeFrom
        timeTo = saved.timeTo
        passengerCountSpinner.setSelection((saved.genders.size - 1).coerceIn(0, 5))
        rebuildGenders(saved.genders.size, saved.genders)
        sideBySideCheck.isChecked = saved.sideBySide && saved.genders.size > 1
        facingCheck.isChecked = saved.facing && saved.genders.size > 1
        economyCheck.isChecked = Kind.ECONOMY in saved.kinds
        businessCheck.isChecked = Kind.BUSINESS in saved.kinds
        wheelchairCheck.isChecked = Kind.WHEELCHAIR in saved.kinds
        when (saved.direction) {
            "F" -> forwardRadio.isChecked = true
            "B" -> backwardRadio.isChecked = true
            else -> anyDirectionRadio.isChecked = true
        }
        strictCheck.isChecked = saved.strict
        intervalInput.setText(saved.intervalSec.toString())
        baseUrlInput.setText(saved.baseUrl)
        rotationSpinner.setSelection(
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
        val origin = originStation
        val destination = destinationStation
        val error = configError(origin, destination)
        if (error != null) {
            toast(error)
            return null
        }
        val interval = intervalInput.text.toString().trim().toIntOrNull()
        val minimum = SeatAlarmPolicy.MINIMUM_INTERVAL_SECONDS
        val maximum = SeatAlarmPolicy.MAXIMUM_INTERVAL_SECONDS
        if (interval == null || interval < minimum || interval > maximum) {
            toast("Kontrol aralığı $minimum ile $maximum saniye arasında olmalı")
            return null
        }
        val kinds = selectedKinds()
        if (kinds.isEmpty()) {
            toast("En az bir koltuk türü seçin")
            return null
        }
        return Config(
            originId = origin!!.id,
            originName = origin.name,
            destinationId = destination!!.id,
            destinationName = destination.name,
            dates = dates.toList(),
            timeFrom = timeFrom,
            timeTo = timeTo,
            genders = currentGenders(),
            kinds = kinds,
            sideBySide = genderSpinners.size > 1 && sideBySideCheck.isChecked,
            facing = genderSpinners.size > 1 && facingCheck.isChecked,
            direction = selectedDirection(),
            strict = strictCheck.isChecked,
            intervalSec = interval,
            forwardRotation = selectedRotation(),
            baseUrl = baseUrlInput.text.toString().trim().ifEmpty { TcddApi.DEFAULT_BASE },
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
        if (economyCheck.isChecked) kinds.add(Kind.ECONOMY)
        if (businessCheck.isChecked) kinds.add(Kind.BUSINESS)
        if (wheelchairCheck.isChecked) kinds.add(Kind.WHEELCHAIR)
        return kinds
    }

    private fun selectedDirection(): String = when {
        forwardRadio.isChecked -> "F"
        backwardRadio.isChecked -> "B"
        else -> "A"
    }

    private fun selectedRotation(): Int? = when (rotationSpinner.selectedItemPosition) {
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
            store.token = tokenInput.text.toString().trim()
            store.clearState()
            if (store.token.isEmpty()) {
                WatchStatus.log(
                    "Uyarı: token girilmedi. Sunucu isteği reddederse " +
                        "Gelişmiş bölümünden token ekleyin.",
                )
            }
            Notifier.ensureChannels(this)
            val intent = Intent(this, SeatAlarmService::class.java)
                .setAction(SeatAlarmPolicy.ACTION_START)
            startForegroundService(intent)
            toast("Takip başlatıldı")
        }
    }

    private fun stopClicked() {
        store.running = false
        WatchStatus.running = false
        startService(
            Intent(this, SeatAlarmService::class.java)
                .setAction(SeatAlarmPolicy.ACTION_STOP),
        )
        refreshStatus()
    }

    private fun refreshStatus() {
        val running = WatchStatus.running
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        statusText.text = if (running) "● Takip çalışıyor" else "○ Takip kapalı"
        val lines = WatchStatus.snapshot()
        logText.text = if (lines.isEmpty()) {
            "Henüz kayıt yok."
        } else {
            lines.takeLast(80).reversed().joinToString("\n")
        }
    }
}
