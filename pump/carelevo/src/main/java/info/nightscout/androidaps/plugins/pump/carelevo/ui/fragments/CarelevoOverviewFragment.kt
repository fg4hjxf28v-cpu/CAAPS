package info.nightscout.androidaps.plugins.pump.carelevo.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import app.aaps.core.ui.toast.ToastUtils
import info.nightscout.androidaps.plugins.pump.carelevo.R
import info.nightscout.androidaps.plugins.pump.carelevo.common.model.Event
import info.nightscout.androidaps.plugins.pump.carelevo.common.model.PatchState
import info.nightscout.androidaps.plugins.pump.carelevo.common.model.State
import info.nightscout.androidaps.plugins.pump.carelevo.common.model.UiState
import info.nightscout.androidaps.plugins.pump.carelevo.databinding.FragmentCarelevoOverviewBinding
import info.nightscout.androidaps.plugins.pump.carelevo.ui.activities.CarelevoActivity
import info.nightscout.androidaps.plugins.pump.carelevo.ui.activities.CarelevoAlarmActivity
import info.nightscout.androidaps.plugins.pump.carelevo.ui.base.CarelevoBaseCircleProgress
import info.nightscout.androidaps.plugins.pump.carelevo.ui.base.CarelevoBaseFragment
import info.nightscout.androidaps.plugins.pump.carelevo.ui.dialog.TextBottomSheetDialog
import info.nightscout.androidaps.plugins.pump.carelevo.ui.ext.repeatOnStartedWithViewOwner
import info.nightscout.androidaps.plugins.pump.carelevo.ui.ext.showDialogDiscardConfirm
import info.nightscout.androidaps.plugins.pump.carelevo.ui.ext.showDialogPumpStopDurationSelect
import info.nightscout.androidaps.plugins.pump.carelevo.ui.model.CarelevoOverviewEvent
import info.nightscout.androidaps.plugins.pump.carelevo.ui.type.CarelevoScreenType
import info.nightscout.androidaps.plugins.pump.carelevo.ui.viewModel.CarelevoOverviewViewModel

class CarelevoOverviewFragment : CarelevoBaseFragment<FragmentCarelevoOverviewBinding>(R.layout.fragment_carelevo_overview) {

    private val viewModel: CarelevoOverviewViewModel by viewModels { viewModelFactory }

    private val launchConnectActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->

        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.viewModel = viewModel
        binding.lifecycleOwner = this.viewLifecycleOwner
        if (!viewModel.isCreated) {
            viewModel.observePatchInfo()
            viewModel.observePatchState()
            viewModel.observeInfusionInfo()
            viewModel.observeBleState()
            viewModel.observeProfile()
            viewModel.setIsCreated(true)
        }
        setupView()
        setupObserver()
        alignKeyWidthsWithCap()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPatchInfusionInfo()
    }

    override fun setupView() {
        loadingProgress = CarelevoBaseCircleProgress(requireContext())
        with(binding) {
            btnConnect.setOnClickListener {
                when (this@CarelevoOverviewFragment.viewModel.patchState.value) {
                    PatchState.NotConnectedBooted -> startCarelevoActivity(CarelevoScreenType.COMMUNICATION_CHECK)
                    PatchState.NotConnectedNotBooting -> startCarelevoActivity(CarelevoScreenType.CONNECTION_FLOW_START)
                    PatchState.ConnectedBooted -> ToastUtils.infoToast(requireContext(), ContextCompat.getString(requireContext(), R.string.carelevo_toast_patch_connecting))
                    else -> Unit
                }
            }

            btnDiscard.setOnClickListener {
                showDiscardDialog()
            }

            btnPump.setOnClickListener {
                this@CarelevoOverviewFragment.viewModel.triggerEvent(CarelevoOverviewEvent.ClickPumpStopResumeBtn)
            }
        }
    }

    private fun alignKeyWidthsWithCap() {
        val rows = listOf(
            binding.containerBluetoothState,
            binding.containerSerialNum,
            binding.containerLotNum,
            binding.containerBootTime,
            binding.containerExpiredTime,
            binding.containerRunningRemainMinutes,
            binding.containerPatchState,
            binding.containerBasalRate,
            binding.containerTempBasalRate,
            binding.containerInsulinRemain,
            binding.containerTotalDeliveryDoze
        )

        rows.first().post {
            val sampleRow = rows.first()
            val contentWidthPx = sampleRow.getContentWidthPx()
            val capPx = (contentWidthPx * 0.5f).toInt()
            val paint = sampleRow.getKeyPaint()
            val maxKeyTextWidthPx = rows.maxOf { row ->
                paint.measureText(row.keyText).toInt()
            }
            val finalKeyWidthPx = minOf(maxKeyTextWidthPx + 30, capPx) // 앞에 공간을 주기 위해 30정도 더해줌.

            rows.forEach { it.setKeyWidthPx(finalKeyWidthPx) }
        }
    }

    private fun startCarelevoActivity(type: CarelevoScreenType) {
        Intent(requireContext(), CarelevoActivity::class.java).apply {
            putExtra("screenType", type.name)
            launchConnectActivity.launch(this)
        }
    }

    private fun setupObserver() {
        repeatOnStartedWithViewOwner {
            viewModel.event.collect {
                handleEvent(it)
            }
        }

        repeatOnStartedWithViewOwner {
            viewModel.uiState.collect {
                handleState(it)
            }
        }

        repeatOnStartedWithViewOwner {
            viewModel.isCheckScreen.collect { screenType ->
                screenType?.let {
                    startCarelevoActivity(it)
                }
            }
        }

        repeatOnStartedWithViewOwner {
            viewModel.hasUnacknowledgedAlarms.collect { hasAlarm ->
                if (hasAlarm) {
                    viewModel.initUnacknowledgedAlarms()
                    showAlarmScreen()
                }
            }
        }
    }

    fun showAlarmScreen() {
        val intent = Intent(context, CarelevoAlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        requireContext().startActivity(intent)
    }

    private fun handleState(state: State) {
        when (state) {
            is UiState.Idle -> hideFullScreenProgress()
            is UiState.Loading -> showFullScreenProgress()
            else -> hideFullScreenProgress()
        }
    }

    private fun handleEvent(event: Event) {
        when (event) {
            is CarelevoOverviewEvent.ShowMessageBluetoothNotEnabled -> {
                ToastUtils.infoToast(requireContext(), getString(R.string.carelevo_toast_msg_bluetooth_not_enabled))
            }

            is CarelevoOverviewEvent.ShowMessageCarelevoIsNotConnected -> {
                ToastUtils.infoToast(requireContext(), getString(R.string.carelevo_toast_msg_patch_not_connected))
            }

            is CarelevoOverviewEvent.DiscardComplete -> {
                ToastUtils.infoToast(requireContext(), getString(R.string.carelevo_toast_msg_discard_complete))
            }

            is CarelevoOverviewEvent.DiscardFailed -> {
                ToastUtils.infoToast(requireContext(), getString(R.string.carelevo_toast_msg_discard_failed))
            }

            is CarelevoOverviewEvent.ResumePumpComplete -> {
                ToastUtils.infoToast(requireContext(), getString(R.string.carelevo_toast_mag_set_basal_resume_success))
            }

            is CarelevoOverviewEvent.ResumePumpFailed -> {
                ToastUtils.infoToast(requireContext(), getString(R.string.carelevo_toast_mag_set_basal_resume_fail))
            }

            is CarelevoOverviewEvent.StopPumpComplete -> {
                ToastUtils.infoToast(requireContext(), getString(R.string.carelevo_toast_mag_set_basal_suspend_success))
            }

            is CarelevoOverviewEvent.StopPumpFailed -> {
                ToastUtils.infoToast(requireContext(), getString(R.string.carelevo_toast_mag_set_basal_suspend_fail))
            }

            is CarelevoOverviewEvent.ShowPumpResumeDialog -> {
                showPumpResumeConfirmDialog()
            }

            is CarelevoOverviewEvent.ShowPumpStopDurationSelectDialog -> {
                showPumpStopDurationSelectDialog()
            }

            else -> Unit
        }
    }

    private fun showDiscardDialog() {
        showDialogDiscardConfirm(
            positiveCallback = {
                viewModel.startDiscardProcess()
            }
        )
    }

    private fun showPumpStopDurationSelectDialog() {
        showDialogPumpStopDurationSelect(
            positiveCallback = {
                viewModel.startPumpStopProcess(it)
            }
        )
    }

    private fun showPumpResumeConfirmDialog() {
        TextBottomSheetDialog.Builder()
            .setTitle(requireContext().getString(R.string.carelevo_pump_resume_title))
            .setContent(requireContext().getString(R.string.carelevo_pump_resume_description))
            .setSecondaryButton(
                TextBottomSheetDialog.Button(
                    text = requireContext().getString(R.string.carelevo_btn_cancel)
                )
            ).setPrimaryButton(
                TextBottomSheetDialog.Button(
                    text = requireContext().getString(R.string.carelevo_btn_confirm),
                    onClickListener = {
                        viewModel.startPumpResume()
                    }
                )).build().show(childFragmentManager, "dialog_carelevo_discard_confirm")

    }
}
