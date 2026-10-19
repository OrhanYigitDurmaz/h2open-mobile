package tr.h2open.driver.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import tr.h2open.driver.MainActivity
import tr.h2open.driver.R

/**
 * Notifications for orders waiting for a driver (h2o_driver `order_open`
 * pushes). Tapping one opens the Orders tab; an `order_claimed` push takes it
 * away again once another driver has the order.
 */
object OrderNotifications {
    private const val CHANNEL = "orders"
    private const val TAG = "H2Open.Orders"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL, context.getString(R.string.channel_orders), NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.channel_orders_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Push data: `order_id`, `customer`, `address`, `zone`, `delivery_date`. */
    fun showOpen(context: Context, data: Map<String, String>) {
        val orderId = data["order_id"]?.toIntOrNull() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(context)
        val customer = data["customer"].orEmpty()
        val details = listOf(data["address"].orEmpty(), data["zone"].orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
        val open = PendingIntent.getActivity(
            context, orderId,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_ORDERS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_order)
            .setContentTitle(context.getString(R.string.order_notification_title, customer))
            .setContentText(details)
            .setStyle(Notification.BigTextStyle().bigText(details))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(TAG, orderId, notification)
    }

    fun cancel(context: Context, orderId: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(TAG, orderId.toInt())
    }
}
