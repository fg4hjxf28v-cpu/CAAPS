package info.nightscout.androidaps.plugins.pump.carelevo.ui.viewModel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.rx.AapsSchedulers
import info.nightscout.androidaps.plugins.pump.carelevo.ble.core.CarelevoBleController
import info.nightscout.androidaps.plugins.pump.carelevo.common.CarelevoPatch
import info.nightscout.androidaps.plugins.pump.carelevo.common.MutableEventFlow
import info.nightscout.androidaps.plugins.pump.carelevo.common.asEventFlow
import info.nightscout.androidaps.plugins.pump.carelevo.common.model.Event
import info.nightscout.androidaps.plugins.pump.carelevo.common.model.State
import info.nightscout.androidaps.plugins.pump.carelevo.common.model.UiState
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.ResponseResult
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.alarm.CarelevoAlarmInfo
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.patch.NeedleCheckFailed
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.patch.NeedleCheckSuccess
import info.nightscout.androidaps.plugins.pump.carelevo.domain.type.AlarmCause
import info.nightscout.androidaps.plugins.pump.carelevo.domain.type.AlarmType
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.alarm.CarelevoAlarmInfoUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.basal.CarelevoSetBasalProgramUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.basal.model.SetBasalProgramRequestModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.patch.CarelevoPatchDiscardUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.patch.CarelevoPatchForceDiscardUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.patch.CarelevoPatchNeedleInsertionCheckUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.ui.model.CarelevoConnectNeedleEvent
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.jvm.optionals.getOrNull

class CarelevoPatchNeedleInsertionViewModel @Inject constructor(
    private val pumpSync: PumpSync,
    private val aapsSchedulers: AapsSchedulers,
    private val carelevoPatch: CarelevoPatch,
    private val bleController: CarelevoBleController,
    private val patchNeedleInsertionCheckUseCase: CarelevoPatchNeedleInsertionCheckUseCase,
    private val patchDiscardUseCase: CarelevoPatchDiscardUseCase,
    private val patchForceDiscardUseCase: CarelevoPatchForceDiscardUseCase,
    private val setBasalProgramUseCase: CarelevoSetBasalProgramUseCase,
    private val carelevoAlarmInfoUseCase: CarelevoAlarmInfoUseCase
) : ViewModel() {

    private val _isNeedleInsert: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isNeedleInsert = _isNeedleInsert.asStateFlow()

    private val _event = MutableEventFlow<Event>()
    val event = _event.asEventFlow()

    private val _uiState: MutableStateFlow<State> = MutableStateFlow(UiState.Idle)
    val uiState = _uiState.asStateFlow()

    private var _isCreated = false
    val isCreated get() = _isCreated

    private val compositeDisposable = CompositeDisposable()

    fun setIsCreated(isCreated: Boolean) {
        _isCreated = isCreated
    }

    fun triggerEvent(event: Event) {
        viewModelScope.launch {
            when (event) {
                is CarelevoConnectNeedleEvent -> generateEventType(event).run { _event.emit(this) }
            }
        }
    }

    private fun generateEventType(event: Event): Event {
        return when (event) {
            is CarelevoConnectNeedleEvent.ShowMessageBluetoothNotEnabled -> event
            is CarelevoConnectNeedleEvent.ShowMessageCarelevoIsNotConnected -> event
            is CarelevoConnectNeedleEvent.ShowMessageProfileNotSet -> event
            is CarelevoConnectNeedleEvent.CheckNeedleComplete -> event
            is CarelevoConnectNeedleEvent.CheckNeedleFailed -> event
            is CarelevoConnectNeedleEvent.CheckNeedleError -> event
            is CarelevoConnectNeedleEvent.DiscardComplete -> event
            is CarelevoConnectNeedleEvent.DiscardFailed -> event
            is CarelevoConnectNeedleEvent.SetBasalComplete -> event
            is CarelevoConnectNeedleEvent.SetBasalFailed -> event
            else -> CarelevoConnectNeedleEvent.NoAction
        }
    }

    private fun setUiState(state: State) {
        viewModelScope.launch {
            _uiState.tryEmit(state)
        }
    }

    fun observePatchInfo() {
        compositeDisposable += carelevoPatch.patchInfo
            .observeOn(aapsSchedulers.main)
            .subscribeOn(aapsSchedulers.io)
            .subscribe {
                val patchInfo = it?.getOrNull() ?: return@subscribe
                Log.d("observePatchInfo", "patchInfo needle Insert: $patchInfo")
                _isNeedleInsert.tryEmit(patchInfo.checkNeedle ?: false)

                val failedCount = patchInfo.needleFailedCount ?: 0
                if (failedCount >= 3) {
                    recordNeedleInsertFailAlarm()
                    triggerEvent(CarelevoConnectNeedleEvent.CheckNeedleFailed(failedCount))
                }
            }
    }

    fun startCheckNeedle() {
        if (!carelevoPatch.isBluetoothEnabled()) {
            triggerEvent(CarelevoConnectNeedleEvent.ShowMessageBluetoothNotEnabled)
            return
        }
        if (!carelevoPatch.isCarelevoConnected()) {
            triggerEvent(CarelevoConnectNeedleEvent.ShowMessageCarelevoIsNotConnected)
            return
        }

        setUiState(UiState.Loading)
        compositeDisposable += patchNeedleInsertionCheckUseCase.execute()
            .timeout(30, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribeOn(aapsSchedulers.io)
            .doOnError {
                Log.d("connect_test", "[CarelevoConnectNeedleViewModel::startCheckNeedle] doOnError called $it")
                setUiState(UiState.Idle)
                val failedCount = carelevoPatch.patchInfo.value?.getOrNull()?.needleFailedCount ?: return@doOnError
                triggerEvent(CarelevoConnectNeedleEvent.CheckNeedleFailed(failedCount))
            }.subscribe { response ->
                setUiState(UiState.Idle)
                when (response) {
                    is ResponseResult.Success -> {
                        when (val body = response.data) {
                            is NeedleCheckSuccess -> {
                                triggerEvent(CarelevoConnectNeedleEvent.CheckNeedleComplete(true))
                            }

                            is NeedleCheckFailed -> {
                                val failedCount = body.failedCount
                                triggerEvent(CarelevoConnectNeedleEvent.CheckNeedleFailed(failedCount))
                            }

                            else -> Unit
                        }
                    }

                    else -> {
                        triggerEvent(CarelevoConnectNeedleEvent.CheckNeedleError)
                    }
                }
            }
    }

    fun startSetBasal() {
        if (!carelevoPatch.isBluetoothEnabled()) {
            triggerEvent(CarelevoConnectNeedleEvent.ShowMessageBluetoothNotEnabled)
            return
        }
        if (!carelevoPatch.isCarelevoConnected()) {
            triggerEvent(CarelevoConnectNeedleEvent.ShowMessageCarelevoIsNotConnected)
            return
        }
        if (carelevoPatch.profile.value == null) {
            triggerEvent(CarelevoConnectNeedleEvent.ShowMessageProfileNotSet)
            return
        }

        carelevoPatch.profile.value?.getOrNull()?.let { profile ->
            setUiState(UiState.Loading)
            compositeDisposable += setBasalProgramUseCase.execute(SetBasalProgramRequestModel(profile))
                .timeout(30000L, TimeUnit.MILLISECONDS)
                .observeOn(aapsSchedulers.io)
                .subscribeOn(aapsSchedulers.io)
                .doOnError {
                    Log.e("connect_test", "[CarelevoConnectNeedleViewModel::startSetBasal] response timeout")
                    setUiState(UiState.Idle)
                    triggerEvent(CarelevoConnectNeedleEvent.SetBasalFailed)
                }.subscribe { response ->
                    when (response) {
                        is ResponseResult.Success -> {
                            Log.d("connect_test", "[CarelevoConnectNeedleViewModel::startSetBasal] response success")
                            pumpSync.connectNewPump(true)
                            pumpSync.insertTherapyEventIfNewWithTimestamp(
                                timestamp = System.currentTimeMillis(),
                                type = TE.Type.INSULIN_CHANGE,
                                pumpType = PumpType.CAREMEDI_CARELEVO,
                                pumpSerial = carelevoPatch.patchInfo.value?.getOrNull()?.manufactureNumber ?: ""
                            )
                            setUiState(UiState.Idle)
                            triggerEvent(CarelevoConnectNeedleEvent.SetBasalComplete)
                        }

                        is ResponseResult.Error -> {
                            Log.d("connect_test", "[CarelevoConnectNeedleViewModel::startSetBasal] response error : ${response.e}")
                            setUiState(UiState.Idle)
                            triggerEvent(CarelevoConnectNeedleEvent.SetBasalFailed)
                        }

                        else -> {
                            Log.d("connect_test", "[CarelevoConnectNeedleViewModel::startSetBasal] response failed")
                            setUiState(UiState.Idle)
                            triggerEvent(CarelevoConnectNeedleEvent.SetBasalFailed)
                        }
                    }
                }
        } ?: run {
            triggerEvent(CarelevoConnectNeedleEvent.ShowMessageProfileNotSet)
        }
    }

    fun startDiscardProcess() {
        if (!carelevoPatch.isCarelevoConnected()) {
            startForceDiscard()
        } else {
            startDiscard()
        }
    }

    private fun startDiscard() {
        setUiState(UiState.Loading)
        compositeDisposable += patchDiscardUseCase.execute()
            .timeout(3000L, TimeUnit.MILLISECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribeOn(aapsSchedulers.io)
            .doOnError {
                Log.d("connect_test", "[CarelevoConnectNeedleViewModel::startDiscard] doOnError called : $it")
                setUiState(UiState.Idle)
                triggerEvent(CarelevoConnectNeedleEvent.DiscardFailed)
            }.subscribe { response ->
                when (response) {
                    is ResponseResult.Success -> {
                        Log.d("connect_test", "[CarelevoConnectNeedleViewModel::startDiscard] response success")
                        bleController.unBondDevice()
                        carelevoPatch.releasePatch()
                        setUiState(UiState.Idle)
                        triggerEvent(CarelevoConnectNeedleEvent.DiscardComplete)
                    }

                    is ResponseResult.Error -> {
                        Log.d("connect_test", "[CarelevoConnectNeedleViewModel::startDiscard] response error : ${response.e}")
                        setUiState(UiState.Idle)
                        triggerEvent(CarelevoConnectNeedleEvent.DiscardFailed)
                    }

                    else -> {
                        Log.d("connect_test", "[CarelevoConnectNeedleViewModel::startDiscard] response failed")
                        setUiState(UiState.Idle)
                        triggerEvent(CarelevoConnectNeedleEvent.DiscardFailed)
                    }
                }
            }
    }

    private fun startForceDiscard() {
        setUiState(UiState.Loading)
        compositeDisposable += patchForceDiscardUseCase.execute()
            .timeout(3000L, TimeUnit.MILLISECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribeOn(aapsSchedulers.io)
            .doOnError {
                Log.d("connect_test", "[CarelevoConnectNeedleViewModel::startForceDiscard] doOnError called : $it")
                setUiState(UiState.Idle)
                triggerEvent(CarelevoConnectNeedleEvent.DiscardFailed)
            }.subscribe { response ->
                when (response) {
                    is ResponseResult.Success -> {
                        Log.d("connect_test", "[CarelevoConnectNeedleViewModel::startForceDiscard] response success")
                        bleController.unBondDevice()
                        carelevoPatch.releasePatch()
                        setUiState(UiState.Idle)
                        triggerEvent(CarelevoConnectNeedleEvent.DiscardComplete)
                    }

                    is ResponseResult.Error -> {
                        Log.d("connect_test", "[CarelevoConnectNeedleViewModel::startForceDiscard] response error : ${response.e}")
                        setUiState(UiState.Idle)
                        triggerEvent(CarelevoConnectNeedleEvent.DiscardFailed)
                    }

                    else -> {
                        Log.d("connect_test", "[CarelevoConnectNeedleViewModel::startForceDiscard] response failed")
                        setUiState(UiState.Idle)
                        triggerEvent(CarelevoConnectNeedleEvent.DiscardFailed)
                    }
                }
            }
    }

    private fun recordNeedleInsertFailAlarm() {
        val info = CarelevoAlarmInfo(
            alarmId = System.currentTimeMillis().toString(),
            alarmType = AlarmType.WARNING,
            cause = AlarmCause.ALARM_WARNING_NEEDLE_INSERTION_ERROR,
            createdAt = LocalDateTime.now().toString(),
            updatedAt = LocalDateTime.now().toString(),
            isAcknowledged = false,

            )
        compositeDisposable += carelevoAlarmInfoUseCase.upsertAlarm(info)
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.io)
            .subscribe(
                { Log.d("alarm", "upsert complete") },
                { e -> Log.e("alarm", "upsert error", e) }
            )
    }

    fun needleFailCount() = carelevoPatch.patchInfo.value?.getOrNull()?.needleFailedCount

    override fun onCleared() {
        compositeDisposable.clear()
        super.onCleared()
    }
}