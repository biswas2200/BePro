package app.bepro.collector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.bepro.R

object Notifier {

  private const val CHANNEL_INSIGHTS = "insights"

  fun createChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_INSIGHTS, "Insights", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "Weekly digest and alerts"
      },
    )
  }

  fun showInsight(context: Context, id: Int, title: String, text: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
      context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    val contentIntent = PendingIntent.getActivity(
      context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_INSIGHTS)
      .setSmallIcon(R.drawable.ic_stat_bepro)
      .setContentTitle(title)
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setContentIntent(contentIntent)
      .setAutoCancel(true)
      .build()
    NotificationManagerCompat.from(context).notify(id, notification)
  }
}
