package app.tcddalarm

import java.time.LocalTime

/** Servis ile ekran arasındaki günlük ve çalışma köprüsü. Yalnızca aynı süreçte geçerlidir. */
object WatchStatus {
    private val lines = ArrayList<String>()

    @Volatile
    var listener: (() -> Unit)? = null

    @Volatile
    var running: Boolean = false
        set(value) {
            field = value
            listener?.invoke()
        }

    @Synchronized
    fun log(message: String) {
        val time = LocalTime.now(TR_ZONE).withNano(0)
        lines.add("$time  $message")
        while (lines.size > 200) lines.removeAt(0)
        listener?.invoke()
    }

    @Synchronized
    fun snapshot(): List<String> = ArrayList(lines)
}
