package app.tcddalarm

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/**
 * Ön plan servisi: uygulama kapatılsa ve ekran kapansa da seçilen seferleri sorgular,
 * uygun koltuk çıkınca bildirim gönderir. Bilet satın almaz.
 *
 * Kullanıcı durdurmadıkça [android.app.Service.START_STICKY] ile ayakta kalır.
 * Sistem süreci öldürüp niyetsiz geri getirirse yalnızca kayıtlı "çalışıyor" isteği varsa sürer.
 */
class SeatAlarmService : Service() {

    private val foregroundNotificationId = 1

    @Volatile
    private var stopRequested = false
    private var worker: Thread? = null
    private val wakeLockGuard = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var alertCounter = 0
    private var consecutiveRejections = 0

    /** Bu turda yer bulunan sefer sayısı; bildirim metni için. */
    @Volatile
    private var lastHitCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = Store(this)
        if (!SeatAlarmPolicy.shouldRun(intent?.action, store.running)) {
            stopRequested = true
            store.running = false
            worker?.interrupt()
            stopSelf()
            return START_NOT_STICKY
        }
        val config = store.config
        val status = if (config == null) "Takip kapanıyor…" else "Takip başlatılıyor…"
        if (!enterForeground(status)) {
            store.running = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (config == null) {
            store.running = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_STICKY

        store.running = true
        WatchStatus.running = true
        stopRequested = false
        val token = store.token
        worker = Thread {
            runLoop(config, token, store)
        }.apply {
            name = "tcdd-seat-alarm"
            start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRequested = true
        worker?.interrupt()
        releaseWakeLock()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (error: Exception) {
            // Servis ön plana hiç geçmediyse bildirim zaten yoktur.
        }
        WatchStatus.running = false
        WatchStatus.log("Takip durdu.")
        super.onDestroy()
    }

    private fun enterForeground(text: String): Boolean {
        Notifier.ensureChannels(this)
        return try {
            val notification = Notifier.foreground(this, text)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    foregroundNotificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(foregroundNotificationId, notification)
            }
            true
        } catch (error: Exception) {
            WatchStatus.log("Ön plan servisi başlatılamadı: ${error.message}")
            false
        }
    }

    private fun runLoop(config: Config, token: String, store: Store) {
        val api = TcddApi(config.baseUrl, TcddApi.DEFAULT_CDN, token)
        val route = "${config.originName} → ${config.destinationName}"
        val passengerCount = config.genders.size
        val state = store.loadState()
        val alerter = Alerter(
            config.prefs(),
            { subject, body -> notifyUser(subject, body) },
            { WatchStatus.log(it) },
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        consecutiveRejections = 0
        WatchStatus.log("İzleniyor: $route · ${config.dates.size} gün · $passengerCount yolcu")

        try {
            while (!stopRequested) {
                ensureWakeLock()
                val waitMs = waitMillis(config)
                if (poll(config, api, route, passengerCount, state, alerter, store)) return
                store.saveState(state)
                val now = LocalTime.now(TR_ZONE).withNano(0)
                val hits = lastHitCount
                notificationManager?.notify(
                    foregroundNotificationId,
                    Notifier.foreground(this, "Son kontrol $now · $hits seferde yer var"),
                )
                sleepMillis(waitMs)
            }
        } finally {
            releaseWakeLock()
        }
    }

    /** true dönerse takip bitti ve servis kapanıyor. */
    private fun poll(
        config: Config,
        api: TcddApi,
        route: String,
        passengerCount: Int,
        state: AlertState,
        alerter: Alerter,
        store: Store,
    ): Boolean {
        val today = LocalDate.now(TR_ZONE)
        val days = config.dates.filter { !LocalDate.parse(it).isBefore(today) }
        if (days.isEmpty()) {
            WatchStatus.log("Seçilen tüm tarihler geçti, takip bitti.")
            notifyUser("Takip bitti", "Seçtiğiniz tüm tarihler geçti.")
            return stopWatch(store, state)
        }

        var hits = 0
        for (day in days) {
            if (stopRequested) break
            try {
                val trains = api.trains(config, day)
                val matching = trains.filter { inRange(it.departure, config.timeFrom, config.timeTo) }
                val withSeats = matching.count { it.seats >= passengerCount }
                hits += withSeats
                WatchStatus.log("$day: ${matching.size} sefer aralıkta, $withSeats tanesinde yer var")
                for (train in matching) {
                    val departure = train.departureClock()
                    val key = "$day|${train.name}|$departure"
                    val seats = readSeats(api, config, train, passengerCount)
                    val headline = "$route\n$day $departure — ${train.name}"
                    alerter.handle(state, key, headline, train, seats)
                }
                consecutiveRejections = 0
            } catch (error: BlockedException) {
                consecutiveRejections++
                WatchStatus.log(
                    "Sunucu isteği reddetti (${error.message}). Token süresi dolmuş olabilir.",
                )
                if (consecutiveRejections >= 3) {
                    notifyUser(
                        "Takip durdu",
                        "Sunucu isteği 3 kez reddetti (${error.message}). " +
                            "Gelişmiş bölümünden yeni token girip tekrar başlatın.",
                    )
                    return stopWatch(store, state)
                }
                break
            } catch (error: Exception) {
                WatchStatus.log("$day: sorgu hatası: ${error.message}")
            }
        }
        lastHitCount = hits
        return false
    }

    private fun stopWatch(store: Store, state: AlertState): Boolean {
        store.saveState(state)
        store.running = false
        stopSelf()
        return true
    }

    private fun readSeats(
        api: TcddApi,
        config: Config,
        train: Train,
        passengerCount: Int,
    ): List<Seat> {
        if (train.seats < passengerCount) return emptyList()
        return try {
            api.seatMap(config, train)
        } catch (error: BlockedException) {
            throw error
        } catch (error: Exception) {
            WatchStatus.log("Koltuk haritası alınamadı: ${error.message}")
            emptyList()
        }
    }

    private fun waitMillis(config: Config): Long {
        val seconds = SeatAlarmPolicy.intervalSeconds(config.intervalSec)
        return seconds * 1000L + Random.nextLong(0L, 15_000L)
    }

    private fun notifyUser(subject: String, body: String) {
        WatchStatus.log(subject)
        alertCounter = (alertCounter + 1) % 900
        Notifier.alert(this, 100 + alertCounter, subject, body)
    }

    private fun sleepMillis(millis: Long) {
        var left = millis
        while (left > 0 && !stopRequested) {
            ensureWakeLock()
            val slice = minOf(left, 60_000L)
            try {
                Thread.sleep(minOf(slice, 1_000L))
            } catch (error: InterruptedException) {
                return
            }
            left -= 1_000L
        }
    }

    /**
     * Ekran kapalıyken aralık kaymasın diye kısmi uyanık tutma kilidi.
     * Süre sınırlı kilit, uzun aralıkta (bir güne kadar) sorguyu geciktirir;
     * kilit takip bitene kadar tutulur ve her dilimde düşmüşse yenilenir.
     */
    private fun ensureWakeLock() {
        synchronized(wakeLockGuard) {
            val existing = wakeLock
            if (existing != null && existing.isHeld) return
            val power = getSystemService(PowerManager::class.java) ?: return
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tcddalarm:poll")
            lock.setReferenceCounted(false)
            lock.acquire()
            wakeLock = lock
        }
    }

    private fun releaseWakeLock() {
        synchronized(wakeLockGuard) {
            val lock = wakeLock ?: return
            if (lock.isHeld) lock.release()
            wakeLock = null
        }
    }
}
