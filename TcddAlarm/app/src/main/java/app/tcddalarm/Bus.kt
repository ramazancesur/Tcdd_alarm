package app.tcddalarm

import java.time.LocalTime

/** Servis ile ekran arasındaki basit günlük/durum köprüsü (aynı süreçte çalışır). */
object Bus {
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
    fun log(msg: String) {
        val t = LocalTime.now(TR_ZONE).withNano(0)
        lines.add("$t  $msg")
        while (lines.size > 200) lines.removeAt(0)
        listener?.invoke()
    }

    @Synchronized
    fun snapshot(): List<String> = ArrayList(lines)
}
