package info.nightscout.androidaps.plugins.pump.carelevo.ui.activities

import android.app.Activity
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import info.nightscout.androidaps.plugins.pump.carelevo.R
import info.nightscout.androidaps.plugins.pump.carelevo.di.CarelevoPluginQualifier
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.alarm.CarelevoAlarmInfo
import info.nightscout.androidaps.plugins.pump.carelevo.domain.type.AlarmCause
import info.nightscout.androidaps.plugins.pump.carelevo.ui.dialog.CarelevoAlarmDialog
import info.nightscout.androidaps.plugins.pump.carelevo.ui.ext.repeatOnStarted
import info.nightscout.androidaps.plugins.pump.carelevo.ui.ext.transformStringResources
import info.nightscout.androidaps.plugins.pump.carelevo.ui.model.AlarmEvent
import info.nightscout.androidaps.plugins.pump.carelevo.ui.viewModel.CarelevoAlarmViewModel
import javax.inject.Inject

class CarelevoAlarmActivity : TranslatedDaggerAppCompatActivity() {

    @Inject
    @CarelevoPluginQualifier
    lateinit var viewModelFactory: ViewModelProvider.Factory

    private val viewModel: CarelevoAlarmViewModel by viewModels { viewModelFactory }

    private val requestBtLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.alarmInfo?.let {
                //viewModel.triggerEvent(AlarmEvent.ClearAlarm(it))
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 알람 불러오기
        viewModel.loadUnacknowledgedAlarms()
        setupObserver()
        cancelNotification()
    }

    private fun setupObserver() {
        repeatOnStarted {
            viewModel.alarmQueue.collect { alarms ->
                if (alarms.isNotEmpty()) {
                    showAlarmDialog(alarms.first())
                }
            }
        }

        repeatOnStarted {
            viewModel.alarmQueueEmptyEvent.collect {
                finish()
            }
        }

        repeatOnStarted {
            viewModel.event.collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: AlarmEvent) {
        when (event) {
            AlarmEvent.RequestBluetoothEnable -> handleRequestBluetoothEnable()
            else -> Unit
        }
    }

    private fun cancelNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
    }

    private fun handleRequestBluetoothEnable() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        requestBtLauncher.launch(enableBtIntent)
    }

    private fun showAlarmDialog(alarm: CarelevoAlarmInfo) {
        val (titleRes, descRes, btnRes) = alarm.cause.transformStringResources()

        val descArgs = buildDescArgsFor(alarm)
        val desc = buildDescription(descRes, descArgs)

        val dialog = CarelevoAlarmDialog.Builder()
        dialog.setTitle(getString(titleRes))
            .setContent(desc)
            .setAlarmInfo(alarm)
            .setPrimaryButton(
                CarelevoAlarmDialog.Button(
                    text = getString(btnRes),
                    onClickListener = {
                        viewModel.triggerEvent(AlarmEvent.ClearAlarm(info = alarm))
                    }
                )
            )
            .setMuteButton(
                CarelevoAlarmDialog.Button(
                    text = getString(app.aaps.core.ui.R.string.mute),
                    onClickListener = {
                        viewModel.triggerEvent(AlarmEvent.Mute)
                    }
                )
            )
            .setMute5minButton(
                CarelevoAlarmDialog.Button(
                    text = getString(app.aaps.core.ui.R.string.mute5min),
                    onClickListener = {
                        viewModel.triggerEvent(AlarmEvent.Mute5min)
                    }
                )
            )
            .build()
            .show(supportFragmentManager, "")
        viewModel.triggerEvent(AlarmEvent.StartAlarm)
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
            if (args.isEmpty()) getString(resId) else getString(resId, *args.toTypedArray())
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
                getString(R.string.common_label_unit_value_duration_hour_and_minute, hours, minutes)
            hours > 0 ->
                getString(R.string.common_label_unit_value_duration_hour, hours)
            else ->
                getString(R.string.common_label_unit_value_minute, minutes)
        }
    }
}