package dev.duolauncher

import android.app.ActivityOptions
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Lets the launcher show your latest notifications and control what's playing.
 * Only runs once you turn on notification access for Duo Launcher.
 */
class NotifService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        changed()
    }

    override fun onListenerDisconnected() {
        instance = null
        changed()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = changed()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = changed()

    companion object {
        @Volatile var instance: NotifService? = null
            private set
        private val main = Handler(Looper.getMainLooper())
        private val listeners = mutableListOf<() -> Unit>()

        fun addListener(l: () -> Unit) { listeners += l }
        fun removeListener(l: () -> Unit) { listeners -= l }
        private fun changed() {
            main.post { listeners.toList().forEach { it() } }
        }

        fun hasAccess(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
            return flat.contains(ComponentName(ctx, NotifService::class.java).flattenToString())
        }

        /** Newest notifications people care about (not ongoing ones like music or downloads). */
        fun recent(max: Int): List<StatusBarNotification> = try {
            instance?.activeNotifications.orEmpty()
                .filter { !it.isOngoing && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
                .sortedByDescending { it.postTime }
                .take(max)
        } catch (e: Exception) {
            emptyList()
        }

        fun open(ctx: Context, sbn: StatusBarNotification) {
            val pi = sbn.notification.contentIntent ?: return
            try {
                val opts = ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= 34) {
                    opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                pi.send(ctx, 0, null, null, null, null, opts.toBundle())
                if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) instance?.cancelNotification(sbn.key)
            } catch (e: Exception) {
                // The app withdrew it.
            }
        }

        /** Whatever is playing right now (music, podcasts, video), if anything. */
        fun media(ctx: Context): MediaController? = try {
            ctx.getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(ctx, NotifService::class.java))
                ?.firstOrNull()
        } catch (e: SecurityException) {
            null
        }
    }
}
