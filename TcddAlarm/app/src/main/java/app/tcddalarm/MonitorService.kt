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
 * Ön plan servisi: ekran kapalıyken de seçilen seferleri belirli aralıklarla sorgular,
 * uygun koltuk çıkınca Android bildirimi gönderir. Bilet SATIN ALMAZ.
 */
class MonitorService : Service() {

    companion object {
        const val ACTION_START = "app.tcddalarm.START"
        const val ACTION_STOP = "app.tcddalarm.STOP"
        const val FOREGROUND_ID = 1
    }

    @Volatile
    private var stopFlag = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var alertCounter = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = Store(this)
        if (intent?.action == ACTION_STOP) {
            stopFlag = true
            store.running = false
            worker?.interrupt()
            stopSelf()
            return START_NOT_STICKY
        }
        val cfg = store.config
        Notifier.ensureChannels(this)
        try {
            val n = Notifier.foreground(this, if (cfg == null) "Takip kapanıyor…" else "Takip başlatılıyor…")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FOREGROUND_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(FOREGROUND_ID, n)
            }
        } catch (e: Exception) {
            Bus.log("Ön plan servisi başlatılamadı: ${e.message}")
            store.running = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (cfg == null) {
            store.running = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (worker?.isAlive == true) return START_STICKY

        store.running = true
        Bus.running = true
        stopFlag = false
        val token = store.token
        val t = Thread { runLoop(cfg, token, store) }
        t.name = "tcdd-monitor"
        worker = t
        t.start()
        return START_STICKY
    }

    override fun onDestroy() {
        stopFlag = true
        worker?.interrupt()
        releaseWakeLock()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // Servis ön plana hiç geçmediyse bildirim zaten yoktur.
        }
        Bus.running = false
        Bus.log("Takip durdu.")
        super.onDestroy()
    }

    // --- Takip döngüsü ---------------------------------------------------------

    private fun runLoop(cfg: Config, token: String, store: Store) {
        val api = TcddApi(cfg.baseUrl, TcddApi.DEFAULT_CDN, token)
        val route = "${cfg.fromName} → ${cfg.toName}"
        val need = cfg.genders.size
        val state = store.loadState()
        val alerter = Alerter(cfg.prefs(), { subject, body -> notifyUser(subject, body) }, { Bus.log(it) })
        val nm = getSystemService(NotificationManager::class.java)
        var blocked = 0

        Bus.log("İzleniyor: $route · ${cfg.dates.size} gün · $need yolcu")

        while (!stopFlag) {
            val wait = cfg.intervalSec * 1000L + Random.nextLong(0L, 15000L)
            // Ekran kapalıyken de aralık kaymasın diye kilit, sorgu ve beklemeyi birlikte kapsar.
            acquireWakeLock(wait + 120_000L)
            try {
                val today = LocalDate.now(TR_ZONE)
                val days = cfg.dates.filter { !LocalDate.parse(it).isBefore(today) }
                if (days.isEmpty()) {
                    Bus.log("Seçilen tüm tarihler geçti, takip bitti.")
                    notifyUser("Takip bitti", "Seçtiğiniz tüm tarihler geçti.")
                    store.running = false
                    stopSelf()
                    return
                }

                var hits = 0
                for (day in days) {
                    if (stopFlag) break
                    try {
                        val trains = api.trains(cfg, day)
                        val matching = trains.filter { inRange(it.departure, cfg.timeFrom, cfg.timeTo) }
                        val withSeats = matching.count { it.seats >= need }
                        hits += withSeats
                        Bus.log("$day: ${matching.size} sefer aralıkta, $withSeats tanesinde yer var")

                        for (t in matching) {
                            val key = "$day|${t.name}|${t.hhmm()}"
                            var seats: List<Seat> = emptyList()
                            if (t.seats >= need) {
                                try {
                                    seats = api.seatMap(cfg, t)
                                } catch (e: BlockedException) {
                                    throw e
                                } catch (e: Exception) {
                                    Bus.log("Koltuk haritası alınamadı: ${e.message}")
                                }
                            }
                            alerter.handle(state, key, "$route\n$day ${t.hhmm()} — ${t.name}", t, seats)
                        }
                        blocked = 0
                    } catch (e: BlockedException) {
                        blocked++
                        Bus.log("Sunucu isteği reddetti (${e.message}). Token süresi dolmuş olabilir.")
                        if (blocked >= 3) {
                            notifyUser(
                                "Takip durdu",
                                "Sunucu isteği 3 kez reddetti (${e.message}). Gelişmiş bölümünden yeni token girip tekrar başlatın."
                            )
                            store.running = false
                            stopSelf()
                            return
                        }
                        break
                    } catch (e: Exception) {
                        Bus.log("$day: sorgu hatası: ${e.message}")
                    }
                }

                store.saveState(state)
                val now = LocalTime.now(TR_ZONE).withNano(0)
                nm?.notify(FOREGROUND_ID, Notifier.foreground(this, "Son kontrol $now · $hits seferde yer var"))
                sleepMs(wait)
            } finally {
                releaseWakeLock()
            }
        }
    }

    private fun notifyUser(subject: String, body: String) {
        Bus.log(subject)
        alertCounter = (alertCounter + 1) % 900
        Notifier.alert(this, 100 + alertCounter, subject, body)
    }

    private fun sleepMs(ms: Long) {
        var left = ms
        while (left > 0 && !stopFlag) {
            try {
                Thread.sleep(minOf(left, 1000L))
            } catch (e: InterruptedException) {
                return
            }
            left -= 1000L
        }
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tcddalarm:poll")?.apply {
                setReferenceCounted(false)
            }
        }
        wakeLock?.acquire(timeoutMs.coerceIn(60_000L, 30 * 60 * 1000L))
    }

    private fun releaseWakeLock() {
        val wl = wakeLock
        if (wl != null && wl.isHeld) wl.release()
    }
}
