package app.tcddalarm

/**
 * Takibin başlayıp başlamayacağı ve sorgu aralığı.
 *
 * Android sınıflarına bağlı değildir; birim testler servis başlatmadan bu kuralları doğrular.
 */
object SeatAlarmPolicy {
    const val ACTION_START = "app.tcddalarm.action.START"
    const val ACTION_STOP = "app.tcddalarm.action.STOP"
    const val MINIMUM_INTERVAL_SECONDS = 60
    const val MAXIMUM_INTERVAL_SECONDS = 24 * 60 * 60

    /**
     * Açık başlatma her zaman çalışır, açık durdurma her zaman durur.
     * Eylem yoksa (sistem servisi öldürüp geri getirdiyse) kayıtlı isteğe bakılır.
     */
    fun shouldRun(action: String?, savedRunning: Boolean): Boolean = when (action) {
        ACTION_START -> true
        ACTION_STOP -> false
        else -> savedRunning
    }

    fun intervalSeconds(requested: Int): Int =
        requested.coerceIn(MINIMUM_INTERVAL_SECONDS, MAXIMUM_INTERVAL_SECONDS)
}
