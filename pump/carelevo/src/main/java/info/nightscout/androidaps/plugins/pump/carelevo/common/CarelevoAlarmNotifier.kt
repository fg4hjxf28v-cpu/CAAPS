package info.nightscout.androidaps.plugins.pump.carelevo.common

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.ui.UiInteraction
import info.nightscout.androidaps.plugins.pump.carelevo.R
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.alarm.CarelevoAlarmInfo
import info.nightscout.androidaps.plugins.pump.carelevo.domain.type.AlarmCause
import info.nightscout.androidaps.plugins.pump.carelevo.ui.activities.CarelevoAlarmActivity
import info.nightscout.androidaps.plugins.pump.carelevo.ui.ext.transformNotificationStringResources
import info.nightscout.androidaps.plugins.pump.carelevo.ui.model.AlarmEvent
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CarelevoAlarmNotifier @Inject constructor(
    private val context: Context,
    private val aapsSchedulers: AapsSchedulers,
    private val uiInteraction: UiInteraction,
    private val alarmActionHandler: CarelevoAlarmActionHandler
) {

    private val disposables = CompositeDisposable()
    private var onAlarmsUpdated: ((List<CarelevoAlarmInfo>) -> Unit)? = null
    private val channelId = "carelevo_alarm_channel"

    fun startObserving(
        onAlarmsUpdated: (List<CarelevoAlarmInfo>) -> Unit
    ) {
        this.onAlarmsUpdated = onAlarmsUpdated
        createNotificationChannel()

        disposables += alarmActionHandler.observeAlarms()
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe(
                { alarms -> handleAlarmsInternal(alarms) },
                { e -> Log.e("AlarmObserver", "observeAlarms error", e) }
            )
    }

    fun refreshAlarms(includeUnacknowledged: Boolean = false) {
        disposables += alarmActionHandler.getAlarmsOnce(includeUnacknowledged)
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe(
                { alarms -> handleAlarmsInternal(alarms) },
                { e -> Log.e("AlarmObserver", "refreshAlarms error", e) }
            )
    }

    private fun handleAlarmsInternal(alarms: List<CarelevoAlarmInfo>) {
        Log.d("AlarmObserver", "handleAlarmsInternal: $alarms")

        if (!isInForeground) {
            alarms.forEach { alarm ->
                showNotification(alarm)
            }
        }

        onAlarmsUpdated?.invoke(alarms)
    }

    fun showTopNotification(alarms: List<CarelevoAlarmInfo>) {
        alarms.forEach { newAlarm ->
            val (titleRes, descRes, btnRes) = newAlarm.cause.transformNotificationStringResources()

            val descArgs = buildDescArgsFor(newAlarm)
            val desc = buildDescription(descRes, descArgs)

            uiInteraction.addNotificationWithAction(
                id = app.aaps.core.interfaces.notifications.Notification.CARELEVO_PATCH_ALERTS + (newAlarm.alarmType.code * 1000) + (newAlarm.cause.code ?: 0),
                text = context.getString(titleRes) + "\n" + HtmlCompat.fromHtml(desc, HtmlCompat.FROM_HTML_MODE_LEGACY),
                level = app.aaps.core.interfaces.notifications.Notification.NORMAL,
                buttonText = btnRes,
                action = {
                    alarmActionHandler.triggerEvent(AlarmEvent.ClearAlarm(info = newAlarm))
                },
                validityCheck = null,
                soundId = app.aaps.core.ui.R.raw.alarm,
            )
        }
    }

    fun stopObserving() {
        disposables.clear()
    }

    fun showNotification(alarm: CarelevoAlarmInfo) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val (titleRes, descRes, _) = alarm.cause.transformNotificationStringResources()
        val description = buildNotificationDescription(alarm, descRes)

        val contentPendingIntent = createAlarmActivityPendingIntent()

        val notification = buildNotification(
            title = context.getString(titleRes),
            description = description,
            contentIntent = contentPendingIntent
        )

        notificationManager.notify(alarm.alarmId.hashCode(), notification)
    }

    /** descRes와 alarm 정보로 최종 본문 문자열 생성 */
    private fun buildNotificationDescription(
        alarm: CarelevoAlarmInfo,
        @StringRes descRes: Int?
    ): String {
        if (descRes == null) return ""
        return when (alarm.cause) {
            AlarmCause.ALARM_ALERT_OUT_OF_INSULIN,
            AlarmCause.ALARM_NOTICE_LOW_INSULIN -> {
                // 인슐린 부족(주의/알림)
                val remain = (alarm.value ?: 0).toString()
                context.getString(descRes, remain)
            }

            AlarmCause.ALARM_NOTICE_PATCH_EXPIRED -> {
                // 패치 사용 기간 알림
                formatPatchExpired(descRes, alarm.value ?: 0)
            }

            AlarmCause.ALARM_NOTICE_BG_CHECK -> {
                // 혈당 체크 알림
                val span = formatBgCheckSpan(alarm.value ?: 0)
                context.getString(descRes, span)
            }

            else -> context.getString(descRes)
        }
    }

    private fun formatPatchExpired(@StringRes descRes: Int, totalHours: Int): String {
        val days = totalHours / 24
        val remainHours = totalHours % 24
        return context.getString(descRes, days, remainHours)
    }

    private fun formatBgCheckSpan(totalMinutes: Int): String {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) {
            context.getString(
                R.string.common_label_unit_value_duration_hour_and_minute,
                hours, minutes
            )
        } else {
            context.getString(
                R.string.common_label_unit_value_minute,
                minutes
            )
        }
    }

    private fun buildNotification(
        title: String,
        description: String,
        contentIntent: PendingIntent
    ): Notification {
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(app.aaps.core.ui.R.drawable.notif_icon)
            .setContentTitle(title)
            .setContentText(description)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
    }

    private fun createAlarmActivityPendingIntent(): PendingIntent {
        val launchIntent =
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        return PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            flags
        )
    }

    private fun createNotificationChannel() {
        val name = "Carelevo Alarm Channel"
        val descriptionText = "케어레보 패치 알람 알림 채널"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(channelId, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun cancelNotification(alarmId: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(alarmId.hashCode())
    }

    private fun playBeep() {
        val player = MediaPlayer.create(context, app.aaps.core.ui.R.raw.error) // res/raw/alarm_sound.mp3
        player.setOnCompletionListener { it.release() }
        player.start()
    }

    fun showAlarmScreen() {
        val intent = Intent(context, CarelevoAlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }

    private fun buildDescArgsFor(alarm: CarelevoAlarmInfo): List<String> = when (alarm.cause) {
        AlarmCause.ALARM_NOTICE_LOW_INSULIN,
        AlarmCause.ALARM_ALERT_OUT_OF_INSULIN -> {
            listOf((alarm.value ?: 0).toString())
        }

        AlarmCause.ALARM_NOTICE_PATCH_EXPIRED -> {
            val totalHours = alarm.value ?: 0
            val (days, hours) = splitDaysAndHours(totalHours)
            listOf(days.toString(), hours.toString())
        }

        AlarmCause.ALARM_NOTICE_BG_CHECK -> {
            val totalMinutes = alarm.value ?: 0
            listOf(formatBgCheckDuration(totalMinutes))
        }

        else -> emptyList()
    }

    private fun buildDescription(@androidx.annotation.StringRes descRes: Int?, args: List<String>): String {
        return descRes?.let { resId ->
            if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args.toTypedArray())
        } ?: ""
    }

    private fun splitDaysAndHours(totalHours: Int): Pair<Int, Int> {
        val days = totalHours / 24
        val hours = totalHours % 24
        return days to hours
    }

    private fun formatBgCheckDuration(totalMinutes: Int): String {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 ->
                context.getString(R.string.common_label_unit_value_duration_hour_and_minute, hours, minutes)
            hours > 0 ->
                context.getString(R.string.common_label_unit_value_duration_hour, hours)
            else ->
                context.getString(R.string.common_label_unit_value_minute, minutes)
        }
    }

    val isInForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}