package app.tcddalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Telefon yeniden açılınca, kullanıcı takibi durdurmadıysa servisi geri başlatır. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = Store(context)
        if (!store.running || store.config == null) return
        val start = Intent(context, SeatAlarmService::class.java)
            .setAction(SeatAlarmPolicy.ACTION_START)
        try {
            context.startForegroundService(start)
        } catch (e: Exception) {
            store.running = false
        }
    }
}
