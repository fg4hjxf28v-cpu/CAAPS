package info.nightscout.androidaps.plugins.pump.carelevo.ui.binding

import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.databinding.BindingAdapter
import info.nightscout.androidaps.plugins.pump.carelevo.R
import info.nightscout.androidaps.plugins.pump.carelevo.ble.data.DeviceModuleState
import info.nightscout.androidaps.plugins.pump.carelevo.common.model.PatchState
import info.nightscout.androidaps.plugins.pump.carelevo.ui.widget.CarelevoKeyValueRowView

@BindingAdapter("patchStateText")
fun CarelevoKeyValueRowView.bindPatchStateText(state: PatchState?) {
    val txt = when (state) {
        PatchState.NotConnectedNotBooting -> context.getString(R.string.carelevo_state_none_value)
        PatchState.ConnectedBooted -> context.getString(R.string.carelevo_state_connected_value)
        else -> context.getString(R.string.carelevo_state_disconnected_value)
    }
    valueText = txt
}

@BindingAdapter("bleStateImg")
fun CarelevoKeyValueRowView.bindBleStateText(btState: DeviceModuleState?) {
    val drawableRes = when (btState) {
        DeviceModuleState.DEVICE_STATE_ON -> {
            R.drawable.ic_bt_connected
        }

        DeviceModuleState.DEVICE_STATE_OFF -> {
            R.drawable.ic_bt_disconneted
        }

        else -> {
            R.drawable.ic_bt_disconneting
        }
    }
    setImageValue(drawableRes)
    valueText = ""
}

@BindingAdapter("patchStateColor")
fun CarelevoKeyValueRowView.bindPatchStateColor(state: PatchState?) {
    val color = when (state) {
        PatchState.NotConnectedNotBooting -> app.aaps.core.ui.R.color.red
        PatchState.ConnectedBooted -> app.aaps.core.ui.R.color.omni_green
        else -> app.aaps.core.ui.R.color.omni_yellow
    }
    valueTextColor = ContextCompat.getColor(context, color)
}

@BindingAdapter("bleButtonText")
fun Button.bindBleButtonText(state: PatchState?) {
    val ctx = context
    text = when (state) {
        PatchState.NotConnectedBooted -> ctx.getString(R.string.carelevo_overview_communication_btn_label)
        else -> ctx.getString(R.string.carelevo_overview_connect_btn_label)
    }
    isEnabled = state != PatchState.ConnectedBooted
}

@BindingAdapter("bleButtonEnable")
fun Button.bindBleButtonEnable(state: PatchState?) {
    isEnabled = state != PatchState.NotConnectedNotBooting
}

@BindingAdapter(value = ["patchState", "isPump"])
fun Button.bindResumButtonState(state: PatchState?, isPump: Boolean) {
    val btnName = if (isPump) context.getString(R.string.carelevo_overview_pump_resume_btn_label) else context.getString(R.string.carelevo_overview_pump_stop_btn_label)
    text = btnName
    isVisible = state != PatchState.NotConnectedNotBooting
}

@BindingAdapter("remainTimeText")
fun TextView.setRemainTimeText(remainSeconds: Long?) {
    remainSeconds ?: return

    isVisible = true
    val minutes = remainSeconds / 60
    val seconds = remainSeconds % 60
    text = String.format(context.getString(R.string.common_unit_remain_min_sec), minutes, seconds)
}

@BindingAdapter("progressFractionText")
fun TextView.setProgressFractionText(progress: Int?) {
    progress ?: return
    isVisible = true
    text = "$progress/100"
}
