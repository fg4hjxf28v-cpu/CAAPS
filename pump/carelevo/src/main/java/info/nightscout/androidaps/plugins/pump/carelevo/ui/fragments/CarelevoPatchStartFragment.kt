package info.nightscout.androidaps.plugins.pump.carelevo.ui.fragments

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import info.nightscout.androidaps.plugins.pump.carelevo.R
import info.nightscout.androidaps.plugins.pump.carelevo.databinding.FragmentCarelevoPatchStartBinding
import info.nightscout.androidaps.plugins.pump.carelevo.ui.base.CarelevoBaseCircleProgress
import info.nightscout.androidaps.plugins.pump.carelevo.ui.base.CarelevoBaseFragment
import info.nightscout.androidaps.plugins.pump.carelevo.ui.dialog.InsulinRefillGuideDialog
import info.nightscout.androidaps.plugins.pump.carelevo.ui.dialog.TenStepNumberPickerBottomSheet
import info.nightscout.androidaps.plugins.pump.carelevo.ui.ext.showDialogDiscardConfirm
import info.nightscout.androidaps.plugins.pump.carelevo.ui.type.CarelevoPatchStep
import info.nightscout.androidaps.plugins.pump.carelevo.ui.viewModel.CarelevoPatchConnectionFlowViewModel
import info.nightscout.androidaps.plugins.pump.carelevo.ui.viewModel.CarelevoPatchStartViewModel

class CarelevoPatchStartFragment : CarelevoBaseFragment<FragmentCarelevoPatchStartBinding>(R.layout.fragment_carelevo_patch_start) {

    companion object {

        fun getInstance(): CarelevoPatchStartFragment = CarelevoPatchStartFragment()
    }

    private val viewModel: CarelevoPatchStartViewModel by viewModels { viewModelFactory }
    private val sharedViewModel: CarelevoPatchConnectionFlowViewModel by activityViewModels { viewModelFactory }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupView()
    }

    override fun setupView() {
        loadingProgress = CarelevoBaseCircleProgress(requireContext())

        with(binding) {
            btnDiscard.setOnClickListener {
                showDialogDiscardConfirm(
                    positiveCallback = {
                        requireActivity().finish()
                    })
            }

            btnNext.setOnClickListener {
                showNumberPickerDialog()
            }

            btnGuide.setOnClickListener {
                InsulinRefillGuideDialog.newInstance().show(parentFragmentManager, "InsulinRefillGuideDialog")
            }
        }
    }

    private fun showNumberPickerDialog() {
        TenStepNumberPickerBottomSheet(
            initialValue = sharedViewModel.inputInsulin,
        ) { selected ->
            if (checkPermissions()) {
                sharedViewModel.setInputInsulin(selected)
                sharedViewModel.setPage(CarelevoPatchStep.PATCH_CONNECT)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 100)
                } else {
                    ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
                }
            }
        }.show(parentFragmentManager, "Picker")
    }

    private fun checkPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasPermission(permissionType: String): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), permissionType) == PackageManager.PERMISSION_GRANTED
    }
}