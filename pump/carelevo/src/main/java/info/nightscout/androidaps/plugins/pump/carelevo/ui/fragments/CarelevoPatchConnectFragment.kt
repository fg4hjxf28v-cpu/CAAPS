package info.nightscout.androidaps.plugins.pump.carelevo.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import app.aaps.core.ui.toast.ToastUtils
import info.nightscout.androidaps.plugins.pump.carelevo.R
import info.nightscout.androidaps.plugins.pump.carelevo.common.model.Event
import info.nightscout.androidaps.plugins.pump.carelevo.common.model.State
import info.nightscout.androidaps.plugins.pump.carelevo.common.model.UiState
import info.nightscout.androidaps.plugins.pump.carelevo.databinding.FragmentCarelevoPatchConnectBinding
import info.nightscout.androidaps.plugins.pump.carelevo.ui.base.CarelevoBaseCircleProgress
import info.nightscout.androidaps.plugins.pump.carelevo.ui.base.CarelevoBaseFragment
import info.nightscout.androidaps.plugins.pump.carelevo.ui.ext.repeatOnStartedWithViewOwner
import info.nightscout.androidaps.plugins.pump.carelevo.ui.ext.showDialogConnect
import info.nightscout.androidaps.plugins.pump.carelevo.ui.ext.showDialogDiscardConfirm
import info.nightscout.androidaps.plugins.pump.carelevo.ui.model.CarelevoConnectPrepareEvent
import info.nightscout.androidaps.plugins.pump.carelevo.ui.type.CarelevoPatchStep
import info.nightscout.androidaps.plugins.pump.carelevo.ui.viewModel.CarelevoPatchConnectViewModel
import info.nightscout.androidaps.plugins.pump.carelevo.ui.viewModel.CarelevoPatchConnectionFlowViewModel

class CarelevoPatchConnectFragment : CarelevoBaseFragment<FragmentCarelevoPatchConnectBinding>(R.layout.fragment_carelevo_patch_connect) {

    companion object {

        fun getInstance(): CarelevoPatchConnectFragment = CarelevoPatchConnectFragment()
    }

    private val viewModel: CarelevoPatchConnectViewModel by viewModels { viewModelFactory }
    private val sharedViewModel: CarelevoPatchConnectionFlowViewModel by activityViewModels { viewModelFactory }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupView()
        setObserver()
    }

    override fun setupView() {
        loadingProgress = CarelevoBaseCircleProgress(requireContext())

        with(binding) {
            btnDiscard.setOnClickListener {
                showDialogDiscardConfirm(
                    positiveCallback = {
                        viewModel.startPatchDiscardProcess()
                    })
            }

            btnNext.setOnClickListener {
                viewModel.startScan()
            }
        }
    }

    private fun setObserver() {
        repeatOnStartedWithViewOwner {
            viewModel.event.collect {
                handleEvent(it)
            }
        }

        repeatOnStartedWithViewOwner {
            viewModel.uiState.collect {
                handelState(it)
            }
        }

    }

    private fun showConnectDialog() {
        viewModel.selectedDevice?.let {
            showDialogConnect(address = it.device.address, negativeCallback = {
                viewModel.startScan()
            }, positiveCallback = {
                viewModel.startConnect(sharedViewModel.inputInsulin)
            })
        }
    }

    private fun handelState(state: State) {
        when (state) {
            is UiState.Idle -> hideFullScreenProgress()
            is UiState.Loading -> showFullScreenProgress()
            else -> hideFullScreenProgress()
        }
    }

    private fun handleEvent(event: Event) {
        when (event) {
            is CarelevoConnectPrepareEvent.ShowConnectDialog -> {
                showConnectDialog()
            }

            is CarelevoConnectPrepareEvent.ShowMessageScanFailed -> {
                ToastUtils.infoToast(requireContext(), "스캔 실패했습니다. 다시 시도해 주세요.")
            }

            is CarelevoConnectPrepareEvent.ShowMessageBluetoothNotEnabled -> {
                ToastUtils.infoToast(requireContext(), "블루투스 연결 상태를 확인해 주세요.")
            }

            is CarelevoConnectPrepareEvent.ShowMessageScanIsWorking -> {
                ToastUtils.infoToast(requireContext(), "스캔 중 입니다. 잠시만 기다려 주세요.")
            }

            is CarelevoConnectPrepareEvent.ShowMessageNotSetUserSettingInfo -> {
                ToastUtils.infoToast(requireContext(), "설정 정보가 없습니다.")
            }

            is CarelevoConnectPrepareEvent.ShowMessageSelectedDeviceIseEmpty -> {
                ToastUtils.infoToast(requireContext(), "검색된 패치가 없습니다. 다시 시도해 주세요.")
            }

            is CarelevoConnectPrepareEvent.ConnectFailed -> {
                ToastUtils.infoToast(requireContext(), "패치 연결 실패 했습니다. 다시 시도해 주세요.")
            }

            is CarelevoConnectPrepareEvent.ConnectComplete -> {
                ToastUtils.infoToast(requireContext(), "패치 연결 성공 했습니다.")
                sharedViewModel.setPage(CarelevoPatchStep.SAFETY_CHECK)
            }

            is CarelevoConnectPrepareEvent.DiscardComplete -> {
                ToastUtils.infoToast(requireContext(), "사용 종료 되었습니다.")
                requireActivity().finish()
            }
        }
    }
}