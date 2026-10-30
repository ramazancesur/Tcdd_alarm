package app.tcddalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri

/** Android bildirimleri: boş koltuk uyarıları ve servisin kalıcı (ön plan) bildirimi. */
object Notifier {
    const val CHANNEL_ALERT = "alerts"
    const val CHANNEL_MONITOR = "monitor"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val alert = NotificationChannel(CHANNEL_ALERT, "Boş koltuk uyarıları", NotificationManager.IMPORTANCE_HIGH)
        alert.description = "Seçtiğiniz trenlerde uygun koltuk çıkınca gelir"
        alert.enableVibration(true)
        nm.createNotificationChannel(alert)

        val monitor = NotificationChannel(CHANNEL_MONITOR, "Takip durumu", NotificationManager.IMPORTANCE_LOW)
        monitor.description = "Takip çalışırken görünen kalıcı bildirim"
        nm.createNotificationChannel(monitor)
    }

    /** Uyarıya dokununca TCDD e-bilet sitesini açar. */
    fun alert(context: Context, id: Int, title: String, body: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(TcddApi.SITE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }

    /** Servisin kalıcı bildirimi; "Durdur" düğmesi taşır. */
    fun foreground(context: Context, text: String): Notification {
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = Intent(context, SeatAlarmService::class.java).setAction(SeatAlarmPolicy.ACTION_STOP)
        val stopPi = PendingIntent.getService(context, 1, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel), "Durdur", stopPi
        ).build()
        return Notification.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("TCDD koltuk takibi çalışıyor")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }
}
