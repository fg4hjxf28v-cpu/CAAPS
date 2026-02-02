package info.nightscout.androidaps.plugins.pump.carelevo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.model.BS
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAppInitialized
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveListIntPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import info.nightscout.androidaps.plugins.pump.carelevo.ble.CarelevoBleSource
import info.nightscout.androidaps.plugins.pump.carelevo.ble.core.CarelevoBleController
import info.nightscout.androidaps.plugins.pump.carelevo.ble.core.Connect
import info.nightscout.androidaps.plugins.pump.carelevo.ble.core.DiscoveryService
import info.nightscout.androidaps.plugins.pump.carelevo.ble.core.EnableNotifications
import info.nightscout.androidaps.plugins.pump.carelevo.ble.data.BondingState.Companion.codeToBondingResult
import info.nightscout.androidaps.plugins.pump.carelevo.ble.data.CommandResult
import info.nightscout.androidaps.plugins.pump.carelevo.ble.data.DeviceModuleState.Companion.codeToDeviceResult
import info.nightscout.androidaps.plugins.pump.carelevo.ble.data.PeripheralConnectionState
import info.nightscout.androidaps.plugins.pump.carelevo.ble.data.isAbnormalBondingFailed
import info.nightscout.androidaps.plugins.pump.carelevo.ble.data.isDiscoverCleared
import info.nightscout.androidaps.plugins.pump.carelevo.ble.data.isReInitialized
import info.nightscout.androidaps.plugins.pump.carelevo.ble.data.shouldBeConnected
import info.nightscout.androidaps.plugins.pump.carelevo.ble.data.shouldBeDiscovered
import info.nightscout.androidaps.plugins.pump.carelevo.common.CarelevoAlarmNotifier
import info.nightscout.androidaps.plugins.pump.carelevo.common.CarelevoObserveReceiver
import info.nightscout.androidaps.plugins.pump.carelevo.common.CarelevoPatch
import info.nightscout.androidaps.plugins.pump.carelevo.common.keys.CarelevoBooleanPreferenceKey
import info.nightscout.androidaps.plugins.pump.carelevo.common.keys.CarelevoIntPreferenceKey
import info.nightscout.androidaps.plugins.pump.carelevo.common.model.PatchState
import info.nightscout.androidaps.plugins.pump.carelevo.data.protocol.parser.CarelevoProtocolParserRegister
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.ResponseResult
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.alarm.CarelevoAlarmInfo
import info.nightscout.androidaps.plugins.pump.carelevo.domain.type.AlarmCause
import info.nightscout.androidaps.plugins.pump.carelevo.domain.type.AlarmType.Companion.isCritical
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.alarm.CarelevoAlarmInfoUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.basal.CarelevoCancelTempBasalInfusionUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.basal.CarelevoStartTempBasalInfusionUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.basal.CarelevoUpdateBasalProgramUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.basal.model.SetBasalProgramRequestModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.basal.model.StartTempBasalInfusionRequestModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.bolus.CarelevoCancelExtendBolusInfusionUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.bolus.CarelevoCancelImmeBolusInfusionUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.bolus.CarelevoFinishImmeBolusInfusionUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.bolus.CarelevoStartExtendBolusInfusionUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.bolus.CarelevoStartImmeBolusInfusionUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.bolus.model.CancelBolusInfusionResponseModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.bolus.model.StartExtendBolusInfusionRequestModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.bolus.model.StartImmeBolusInfusionRequestModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.bolus.model.StartImmeBolusInfusionResponseModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.patch.CarelevoPatchTimeZoneUpdateUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.patch.CarelevoRequestPatchInfusionInfoUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.patch.model.CarelevoPatchTimeZoneRequestModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.userSetting.CarelevoDeleteUserSettingInfoUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.userSetting.CarelevoPatchBuzzModifyUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.userSetting.CarelevoPatchExpiredThresholdModifyUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.userSetting.CarelevoUpdateLowInsulinNoticeAmountUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.userSetting.CarelevoUpdateMaxBolusDoseUseCase
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.userSetting.model.CarelevoPatchBuzzRequestModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.userSetting.model.CarelevoPatchExpiredThresholdModifyRequestModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.userSetting.model.CarelevoUserSettingInfoRequestModel
import info.nightscout.androidaps.plugins.pump.carelevo.ui.base.AppForegroundObserver
import info.nightscout.androidaps.plugins.pump.carelevo.ui.fragments.CarelevoOverviewFragment
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.jvm.optionals.getOrNull
import kotlin.math.ceil
import kotlin.math.min

@Singleton
class CarelevoPumpPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    commandQueue: CommandQueue,
    private val aapsSchedulers: AapsSchedulers,
    private val rxBus: RxBus,
    private val dateUtil: DateUtil,
    private val pumpSync: PumpSync,
    private val sp: SP,
    private val uiInteraction: UiInteraction,
    private val profileFunction: ProfileFunction,
    private val context: Context,
    private var pumpEnactResultProvider: Provider<PumpEnactResult>,
    private val carelevoProtocolParserRegister: CarelevoProtocolParserRegister,
    private val carelevoPatch: CarelevoPatch,
    private val bleController: CarelevoBleController,

    private val updateBasalProgramUseCase: CarelevoUpdateBasalProgramUseCase,
    private val startTempBasalInfusionUseCase: CarelevoStartTempBasalInfusionUseCase,
    private val cancelTempBasalInfusionUseCase: CarelevoCancelTempBasalInfusionUseCase,
    private val startImmeBolusInfusionUseCase: CarelevoStartImmeBolusInfusionUseCase,
    private val startExtendBolusInfusionUseCase: CarelevoStartExtendBolusInfusionUseCase,
    private val cancelImmeBolusInfusionUseCase: CarelevoCancelImmeBolusInfusionUseCase,
    private val cancelExtendBolusInfusionUseCase: CarelevoCancelExtendBolusInfusionUseCase,
    private val finishImmeBolusInfusionUseCase: CarelevoFinishImmeBolusInfusionUseCase,

    private val updateMaxBolusDoseUseCase: CarelevoUpdateMaxBolusDoseUseCase,
    private val updateLowInsulinNoticeAmountUseCase: CarelevoUpdateLowInsulinNoticeAmountUseCase,
    private val deleteUserSettingInfoUseCase: CarelevoDeleteUserSettingInfoUseCase,

    private val requestPatchInfusionInfoUseCase: CarelevoRequestPatchInfusionInfoUseCase,
    private val carelevoAlarmNotifier: CarelevoAlarmNotifier,

    private val carelevoPatchTimeZoneUpdateUseCase: CarelevoPatchTimeZoneUpdateUseCase,
    private val carelevoPatchExpiredThresholdModifyUseCase: CarelevoPatchExpiredThresholdModifyUseCase,
    private val carelevoPatchBuzzModifyUseCase: CarelevoPatchBuzzModifyUseCase,
    private val alarmUseCase: CarelevoAlarmInfoUseCase,
) : PumpPluginBase(
    PluginDescription()
        .mainType(PluginType.PUMP)
        .fragmentClass(CarelevoOverviewFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_carelevo_128)
        .pluginName(R.string.carelevo)
        .shortName(R.string.carelevo_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .description(R.string.carelevo_description),
    ownPreferences = listOf(CarelevoBooleanPreferenceKey::class.java, CarelevoIntPreferenceKey::class.java),
    aapsLogger, rh, preferences, commandQueue
), Pump {

    private var bleReceiverDisposable: Disposable? = null
    private val pluginDisposable = CompositeDisposable()

    private var _lastDateTime: Long = 0

    private var _pumpType: PumpType = PumpType.CAREMEDI_CARELEVO
    private val _pumpDescription = PumpDescription().fillFor(_pumpType)
    private var isImmeBolusStop = false
    private var isTryReconnected = false
    private var tempBasalRunningSince: Long? = null

    @Inject @Named("characterTx") lateinit var txUuid: UUID
    private var reconnectDisposable = CompositeDisposable()

    override fun onStart() {
        super.onStart()
        pluginDisposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe { event ->
                if (event.isChanged(DoubleKey.SafetyMaxBolus.key)) {
                    updateMaxBolusDose()
                }
                if (event.isChanged(CarelevoIntPreferenceKey.CARELEVO_PATCH_EXPIRATION_REMINDER_HOURS.key)) {
                    updatePatchExpiredThreshold()
                }
                if (event.isChanged(CarelevoIntPreferenceKey.CARELEVO_LOW_INSULIN_EXPIRATION_REMINDER_HOURS.key)) {
                    updateLowInsulinNoticeAmount()
                }
                if (event.isChanged(CarelevoBooleanPreferenceKey.CARELEVO_BUZZER_REMINDER.key)) {
                    updatePatchBuzzer()
                }
            }

        pluginDisposable += rxBus
            .toObservable(EventAppInitialized::class.java)
            .observeOn(aapsSchedulers.io)
            .flatMapCompletable {
                Completable.fromAction { carelevoProtocolParserRegister.registerParser() }
                    .doOnSubscribe { aapsLogger.debug(LTag.PUMP, "onStart", "1) parser registered start") }
                    .doOnComplete { aapsLogger.debug(LTag.PUMP, "onStart", "1) parser registered done") }

                    .andThen(
                        carelevoPatch.initPatchOnce()
                            .timeout(5, TimeUnit.SECONDS)
                            .onErrorComplete()
                            .doOnSubscribe { aapsLogger.debug(LTag.PUMP, "onStart", "2) initPatchOnce waiting") }
                            .doOnComplete { aapsLogger.debug(LTag.PUMP, "onStart", "2) initPatchOnce completed") }
                    )
                    .andThen(
                        Single.fromCallable {
                            Log.d("onStart", "3) getProfile start")
                            requireNotNull(profileFunction.getProfile()) { "profile is null" }
                        }
                            .doOnSuccess { aapsLogger.debug(LTag.PUMP, "onStart", "3) getProfile ok: $it") }
                    )
                    .flatMapCompletable { profile ->
                        Completable.fromAction { carelevoPatch.setProfile(profile) }
                            .doOnComplete { aapsLogger.debug(LTag.PUMP, "onStart", "3) setProfile done") }
                    }
                    .andThen(
                        Completable.fromAction {
                            aapsLogger.debug(LTag.PUMP, "onStart", "4) snapshot check start")
                            val state = carelevoPatch.patchState.value?.getOrNull()
                            val shouldReconnect = state == null ||
                                (state != PatchState.NotConnectedNotBooting && state != PatchState.ConnectedBooted)
                            aapsLogger.debug(LTag.PUMP, "onStart", "4) shouldReconnect=$shouldReconnect, state=$state")
                            if (shouldReconnect) startReconnect()
                        }.doOnComplete { aapsLogger.debug(LTag.PUMP, "onStart", "4) snapshot check done") }
                    )
            }
            .subscribe(
                { aapsLogger.debug(LTag.PUMP, "onStart", "ALL COMPLETE") },
                { e -> aapsLogger.debug(LTag.PUMP, "onStart", "chain error", e) }
            )

        val filter = IntentFilter().apply {
            // 기존: 페어링 상태 변화
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)

            // 추가: 어댑터 전원 상태 변화 (켜짐/꺼짐 등)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }

        if (bleReceiverDisposable?.isDisposed != false) {
            bleReceiverDisposable = CarelevoObserveReceiver(context, filter)
                .subscribe { intent ->
                    aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::onStart] CarelevoObserveReceiver called: ${intent.action}")
                    when (intent.action) {
                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val bondState = intent.getIntExtra(
                                BluetoothDevice.EXTRA_BOND_STATE,
                                BluetoothDevice.ERROR
                            )
                            CarelevoBleSource.bluetoothState.value
                                ?.copy(isBonded = bondState.codeToBondingResult())
                                ?.let { CarelevoBleSource._bluetoothState.onNext(it) }
                        }

                        BluetoothAdapter.ACTION_STATE_CHANGED -> {
                            val value = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                            if (value in setOf(BluetoothAdapter.STATE_ON, BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_ON, BluetoothAdapter.STATE_TURNING_OFF)) {
                                val isConnected = value == BluetoothAdapter.STATE_ON

                                CarelevoBleSource._bluetoothState.value?.copy(
                                    isEnabled = value.codeToDeviceResult(),
                                    isConnected = if (isConnected) {
                                        PeripheralConnectionState.CONN_STATE_NONE
                                    } else {
                                        CarelevoBleSource._bluetoothState.value?.isConnected ?: PeripheralConnectionState.CONN_STATE_NONE
                                    },
                                )?.let { CarelevoBleSource._bluetoothState.onNext(it) }
                            }
                        }

                        BluetoothDevice.ACTION_ACL_CONNECTED -> {
                            // 연결됨 표시
                        }

                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                            // 연결 해제 표시
                        }
                    }
                }

            bleReceiverDisposable?.let {
                pluginDisposable.add(it)
            }
        }

        startAlarmObserver()
    }

    fun startAlarmObserver() {
        aapsLogger.debug(LTag.NOTIFICATION, "startAlarmObserving:: onStart")

        CoroutineScope(Dispatchers.Main).launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                AppForegroundObserver {
                    aapsLogger.debug(LTag.NOTIFICATION, "Foreground 전환 → 알람 refresh")
                    carelevoAlarmNotifier.refreshAlarms()
                }
            )
        }

        carelevoAlarmNotifier.startObserving { alarms ->
            aapsLogger.debug(LTag.NOTIFICATION, "observe alarms size=${alarms.size}, $alarms")
            handleAlarms(alarms)
        }
    }

    private fun handleAlarms(alarms: List<CarelevoAlarmInfo>) {
        aapsLogger.debug(LTag.NOTIFICATION, "startAlarmObserving handleAlarms:: $alarms")
        if (alarms.isEmpty()) return

        if (
            alarms.any {
                it.alarmType.isCritical() ||
                    it.cause == AlarmCause.ALARM_ALERT_BLUETOOTH_OFF
            }
        ) {
            carelevoAlarmNotifier.showAlarmScreen()
        } else {
            carelevoAlarmNotifier.showTopNotification(alarms)
        }

    }

    override fun onStop() {
        super.onStop()
        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::onStop] onStop called")
        deleteUserSettingInfo()
        pluginDisposable.clear()
        reconnectDisposable.clear()
        //carelevoAlarmNotifier.stopObserving()
    }

    private fun updateMaxBolusDose() {
        val maxBolusDose = preferences.get(DoubleKey.SafetyMaxBolus)
        val patchState = carelevoPatch.patchState.value?.getOrNull()
        pluginDisposable += updateMaxBolusDoseUseCase.execute(
            CarelevoUserSettingInfoRequestModel(
                patchState = patchState,
                maxBolusDose = maxBolusDose
            )
        )
            .observeOn(aapsSchedulers.io)
            .subscribeOn(aapsSchedulers.io)
            .timeout(3000L, TimeUnit.MILLISECONDS)
            .subscribe { response ->
                when (response) {
                    is ResponseResult.Success -> {
                        _lastDateTime = System.currentTimeMillis()
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::updateMaxBolusDose] response success")
                    }

                    is ResponseResult.Error -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::updateMaxBolusDose] response error : ${response.e}")
                    }

                    else -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::updateMaxBolusDose] response failed")
                    }
                }
            }
    }

    private fun updateLowInsulinNoticeAmount() {
        val lowInsulinNoticeAmount = sp.getInt(CarelevoIntPreferenceKey.CARELEVO_LOW_INSULIN_EXPIRATION_REMINDER_HOURS.key, 0)
        val patchState = carelevoPatch.patchState.value?.getOrNull()

        Log.d("CarelevoPumpPlugin", "lowInsulinNoticeAmount($lowInsulinNoticeAmount)")
        if (lowInsulinNoticeAmount == 0) {
            return
        }

        pluginDisposable += updateLowInsulinNoticeAmountUseCase.execute(
            CarelevoUserSettingInfoRequestModel(
                patchState = patchState,
                lowInsulinNoticeAmount = lowInsulinNoticeAmount
            )
        )
            .observeOn(aapsSchedulers.io)
            .timeout(3000L, TimeUnit.MILLISECONDS)
            .subscribe { response ->
                when (response) {
                    is ResponseResult.Success -> {
                        _lastDateTime = System.currentTimeMillis()
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::updateLowInsulinNoticeAmount] response success")
                    }

                    is ResponseResult.Error -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::updateLowInsulinNoticeAmount] response error : ${response.e}")
                    }

                    else -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::updateLowInsulinNoticeAmount] response failed")
                    }
                }
            }
    }

    fun updatePatchExpiredThreshold() {
        val patchExpiredThreshold = sp.getInt(CarelevoIntPreferenceKey.CARELEVO_PATCH_EXPIRATION_REMINDER_HOURS.key, 0)
        val patchState = carelevoPatch.patchState.value?.getOrNull()

        Log.d("CarelevoPumpPlugin", "updatePatchExpiredThreshold($patchExpiredThreshold)")

        val request = CarelevoPatchExpiredThresholdModifyRequestModel(
            patchState = patchState,
            patchExpiredThreshold = patchExpiredThreshold
        )

        pluginDisposable += carelevoPatchExpiredThresholdModifyUseCase.execute(request)
            .observeOn(aapsSchedulers.io)
            .timeout(3000L, TimeUnit.MILLISECONDS)
            .subscribe { response ->
                when (response) {
                    is ResponseResult.Success -> {
                        _lastDateTime = System.currentTimeMillis()
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::updatePatchExpiredThreshold] response success")
                    }

                    is ResponseResult.Error -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::updatePatchExpiredThreshold] response error : ${response.e}")
                    }

                    else -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::updatePatchExpiredThreshold] response failed")
                    }
                }
            }
    }

    private fun updatePatchBuzzer() {
        val isBuzzOn = sp.getBoolean(CarelevoBooleanPreferenceKey.CARELEVO_BUZZER_REMINDER.key, false)
        val patchState = carelevoPatch.patchState.value?.getOrNull()

        val request = CarelevoPatchBuzzRequestModel(
            patchState = patchState,
            settingsAlarmBuzz = isBuzzOn
        )

        pluginDisposable += carelevoPatchBuzzModifyUseCase.execute(request)
            .observeOn(aapsSchedulers.io)
            .timeout(3000L, TimeUnit.MILLISECONDS)
            .subscribe { response ->
                when (response) {
                    is ResponseResult.Success -> {
                        _lastDateTime = System.currentTimeMillis()
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::updatePatchBuzzer] response success")
                    }

                    is ResponseResult.Error -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::updatePatchBuzzer] response error : ${response.e}")
                    }

                    else -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::updatePatchBuzzer] response failed")
                    }
                }
            }
    }

    private fun deleteUserSettingInfo() {
        pluginDisposable += deleteUserSettingInfoUseCase.execute()
            .observeOn(aapsSchedulers.io)
            .subscribeOn(aapsSchedulers.io)
            .timeout(3000L, TimeUnit.MILLISECONDS)
            .subscribe { response ->
                when (response) {
                    is ResponseResult.Success -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::deleteUserSettingInfo] response success")
                    }

                    is ResponseResult.Error -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::deleteUserSettingInfo] response error : ${response.e}")
                    }

                    else -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::deleteUserSettingInfo] response failed")
                    }
                }
            }
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null) return

        val lowReservoirEntries = arrayOf<CharSequence>("20 U", "25 U", "30 U", "35 U", "40 U", "45 U", "50 U")
        val lowReservoirValues = arrayOf<CharSequence>("20", "25", "30", "35", "40", "45", "50")
        val expirationRemindersEntries =
            arrayOf<CharSequence>("1 hr", "2 hr", "3 hr", "4 hr", "5 hr", "6 hr", "7 hr", "8 hr", "9 hr", "10 hr", "11 hr", "12 hr", "13 hr", "14 hr", "15 hr", "16 hr", "17 hr", "18 hr", "19 hr", "20 hr", "21 hr", "22 hr", "23 hr", "24 hr")
        val expirationRemindersValues = arrayOf<CharSequence>("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24")

        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "carelevo_beeps"
            title = rh.gs(R.string.carelevo_preferences_category_confirmation_beeps)
            initialExpandedChildrenCount = 0
            addPreference(
                AdaptiveListIntPreference(
                    ctx = context,
                    intKey = CarelevoIntPreferenceKey.CARELEVO_LOW_INSULIN_EXPIRATION_REMINDER_HOURS,
                    title = R.string.carelevo_low_reservoir_reminders_title,
                    entries = lowReservoirEntries,
                    entryValues = lowReservoirValues
                )
            )
            addPreference(
                AdaptiveIntPreference(
                    ctx = context,
                    intKey = CarelevoIntPreferenceKey.CARELEVO_PATCH_EXPIRATION_REMINDER_HOURS,
                    title = R.string.carelevo_patch_expiration_reminders_title_value,
                    dialogMessage = R.string.carelevo_patch_expiration_reminders_message
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = CarelevoBooleanPreferenceKey.CARELEVO_BUZZER_REMINDER,
                    title = R.string.carelevo_patch_buzzer_alarm_title
                )
            )
        }

        /*
                val alertsCategory = PreferenceCategory(context)
                parent.addPreference(alertsCategory)
                alertsCategory.apply {
                    key = "omnipod_dash_alerts"
                    title = rh.gs(R.string.carelevo_preferences_category_alerts)
                    initialExpandedChildrenCount = 0

                    addPreference(
                        AdaptiveSwitchPreference(
                            ctx = context,
                            booleanKey = OmnipodBooleanPreferenceKey.ExpirationReminder,
                            title = R.string.carelevo_preferences_expiration_reminder_enabled,
                            summary = R.string.carelevo_preferences_expiration_reminder_enabled_summary
                        )
                    )
                    addPreference(
                        AdaptiveIntPreference(
                            ctx = context,
                            intKey = OmnipodIntPreferenceKey.ExpirationReminderHours,
                            title = R.string.carelevo_preferences_expiration_reminder_hours_before_expiry
                        )
                    )
                    addPreference(
                        AdaptiveSwitchPreference(
                            ctx = context,
                            booleanKey = OmnipodBooleanPreferenceKey.ExpirationAlarm,
                            title = R.string.carelevo_preferences_expiration_alarm_enabled,
                            summary = R.string.carelevo_preferences_expiration_alarm_enabled_summary
                        )
                    )
                    addPreference(
                        AdaptiveIntPreference(
                            ctx = context,
                            intKey = OmnipodIntPreferenceKey.ExpirationAlarmHours,
                            title = R.string.carelevo_preferences_expiration_alarm_hours_before_shutdown
                        )
                    )
                    addPreference(
                        AdaptiveSwitchPreference(
                            ctx = context,
                            booleanKey = OmnipodBooleanPreferenceKey.LowReservoirAlert,
                            title = R.string.carelevo_preferences_low_reservoir_alert_enabled
                        )
                    )
                    addPreference(
                        AdaptiveIntPreference(
                            ctx = context,
                            intKey = OmnipodIntPreferenceKey.LowReservoirAlertUnits,
                            title = R.string.carelevo_preferences_low_reservoir_alert_units
                        )
                    )

                }
                val notificationsCategory = PreferenceCategory(context)
                parent.addPreference(notificationsCategory)
                notificationsCategory.apply {
                    key = "omnipod_dash_notifications"
                    title = rh.gs(R.string.carelevo_preferences_category_notifications)
                    initialExpandedChildrenCount = 0

                    addPreference(
                        AdaptiveSwitchPreference(
                            ctx = context,
                            booleanKey = OmnipodBooleanPreferenceKey.SoundUncertainTbrNotification,
                            title = R.string.carelevo_preferences_notification_uncertain_tbr_sound_enabled
                        )
                    )
                    addPreference(
                        AdaptiveSwitchPreference(
                            ctx = context,
                            booleanKey = OmnipodBooleanPreferenceKey.SoundUncertainSmbNotification,
                            title = R.string.carelevo_preferences_notification_uncertain_smb_sound_enabled
                        )
                    )
                    addPreference(
                        AdaptiveSwitchPreference(
                            ctx = context,
                            booleanKey = OmnipodBooleanPreferenceKey.SoundUncertainBolusNotification,
                            title = R.string.carelevo_preferences_notification_uncertain_bolus_sound_enabled
                        )
                    )
                    addPreference(
                        AdaptiveSwitchPreference(
                            ctx = context,
                            booleanKey = DashBooleanPreferenceKey.SoundDeliverySuspendedNotification,
                            title = app.aaps.pump.omnipod.dash.R.string.carelevo_preferences_notification_delivery_suspended_sound_enabled
                        )
                    )
                }*/
    }

    /* 패치가 실제 연결 중 인지 확인 */
    override fun isInitialized(): Boolean {
        val address = carelevoPatch.patchInfo.value?.getOrNull()?.address?.uppercase()
        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::isInitialized] address: $address")
        if (address == null) {
            return false
        }
        val isConnected = carelevoPatch.isBleConnectedNow(address)
        return isConnected
    }

    override fun isSuspended(): Boolean {
        val patchState = carelevoPatch.getPatchState()
        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::isSuspended] result: $patchState")
        return patchState == PatchState.NotConnectedBooted
    }

    override fun isBusy(): Boolean {
        return false
    }

    override fun isConnected(): Boolean {
        val connected = carelevoPatch.isCarelevoConnected()
        val working = carelevoPatch.isWorking

        val result = connected || working

        //return result
        val address = carelevoPatch.patchInfo.value?.getOrNull()?.address?.uppercase()
        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::isConnected] address: $address")
        if (address == null) {
            return false
        }
        val isConnected = carelevoPatch.isBleConnectedNow(address)
        Log.d("PUMP_STATE", "isConnected() -> $isConnected (thread=${Thread.currentThread().name})")

        forceQueueClear()
        return isConnected
    }

    private fun forceQueueClear() {
        val isTempBasalRunning = commandQueue.isRunning(Command.CommandType.TEMPBASAL)

        if (isTempBasalRunning) {
            if (tempBasalRunningSince == null) {
                tempBasalRunningSince = System.currentTimeMillis()
            } else {
                val elapsed = System.currentTimeMillis() - tempBasalRunningSince!!

                if (elapsed > 5 * 60 * 1000) { // 5분
                    aapsLogger.error(LTag.PUMP, "TEMP BASAL stuck for ${elapsed / 1000}s → force reset")

                    // 🔥 강제 복구
                    commandQueue.resetPerforming()
                    commandQueue.clear()

                    // TEMP BASAL 무효화 (선택)
                    // pumpSync.invalidateTemporaryBasalWithTempId(...)

                    tempBasalRunningSince = null
                }
            }
        } else {
            tempBasalRunningSince = null
        }
    }

    override fun isConnecting(): Boolean {
        return false
    }

    override fun isHandshakeInProgress(): Boolean {
        return false
    }

    override fun connect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::connect] connect called : $reason")
        _lastDateTime = System.currentTimeMillis()

        val patchState = carelevoPatch.getPatchState()
        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::connect] disconnect called : $reason, patchState : $patchState")

        if (reason == "Connection needed") {
            if (patchState == PatchState.NotConnectedBooted) {
                startReconnect()
            }
        }
    }

    override fun disconnect(reason: String) {
        val patchState = carelevoPatch.patchState.value?.getOrNull()
        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::disconnect] disconnect called : $reason, patchState : $patchState")
    }

    override fun stopConnecting() {
        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::stopConnecting] stopConnecting called")
    }

    override fun getPumpStatus(reason: String) {
        if (!carelevoPatch.isBluetoothEnabled()) {
            return
        }
        if (!carelevoPatch.isCarelevoConnected()) {
            return
        }

        pluginDisposable += requestPatchInfusionInfoUseCase.execute()
            .observeOn(aapsSchedulers.main)
            .subscribeOn(aapsSchedulers.io)
            .timeout(3000L, TimeUnit.MILLISECONDS)
            .subscribe { response ->
                when (response) {
                    is ResponseResult.Success -> {
                        _lastDateTime = System.currentTimeMillis()
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::getPumpState] response success")
                    }

                    is ResponseResult.Error -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::getPumpState] response error : ${response.e}")
                    }

                    else -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::getPumpState] response failed")
                    }
                }
            }
    }

    override fun setNewBasalProfile(profile: Profile): PumpEnactResult {
        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::setNewBasalProfile] setNewBasalProfile timezoneOrDSTChanged called - ${carelevoPatch.getPatchState()}")
        _lastDateTime = System.currentTimeMillis()
        return when (carelevoPatch.getPatchState()) {
            is PatchState.ConnectedBooted -> {
                startUpdateBasalProgram(profile)
            }

            is PatchState.NotConnectedNotBooting -> {
                carelevoPatch.setProfile(profile)
                uiInteraction.addNotificationValidFor(Notification.PROFILE_SET_OK, rh.gs(app.aaps.core.ui.R.string.profile_set_ok), Notification.INFO, 60)
                pumpEnactResultProvider.get().success(true).enacted(true)
            }

            else -> {
                pumpEnactResultProvider.get()
            }
        }
    }

    private fun startUpdateBasalProgram(profile: Profile): PumpEnactResult {
        aapsLogger.debug("[CarelevoPumpPlugin::startUpdateBasalProgram]: $profile")
        val result = pumpEnactResultProvider.get()
        carelevoPatch.infusionInfo.value?.getOrNull()?.let {
            if (it.extendBolusInfusionInfo != null) {
                val cancelExtendBolusResult = cancelExtendedBolus()
                if (!cancelExtendBolusResult.success) {
                    return result
                }
            }

            if (it.tempBasalInfusionInfo != null) {
                val cancelTempBasalResult = cancelTempBasal(true)
                if (!cancelTempBasalResult.success) {
                    return result
                }
            }
        }

        return try {
            updateBasalProgramUseCase.execute(SetBasalProgramRequestModel(profile))
                .subscribeOn(aapsSchedulers.io)
                .timeout(10, TimeUnit.SECONDS)
                .blockingGet()   // <- 여기서 timeout은 throw 됨
                .let { response ->
                    when (response) {
                        is ResponseResult.Success -> {
                            _lastDateTime = System.currentTimeMillis()
                            carelevoPatch.setProfile(profile)

                            uiInteraction.addNotificationValidFor(
                                Notification.PROFILE_SET_OK,
                                rh.gs(app.aaps.core.ui.R.string.profile_set_ok),
                                Notification.INFO, 60
                            )

                            result.success = true
                            result.enacted = true
                        }

                        is ResponseResult.Error -> {
                            aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::startUpdateBasal] error: ${response.e}")
                        }

                        else -> {
                            aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::startUpdateBasal] response failed")
                        }
                    }
                    result
                }

        } catch (e: Throwable) {
            aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::startUpdateBasal] timeout or exception: $e")
            result.success = false
            result.enacted = false
            return result
        }
    }

    override fun isThisProfileSet(profile: Profile): Boolean {
        val checkResult = carelevoPatch.checkIsSameProfile(profile)
        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::isThisProfileSet] checkResult : $checkResult")
        return checkResult
    }

    override val lastDataTime: Long
        get() = lastDataTime()
    override val lastBolusTime: Long?
        get() = null
    override val lastBolusAmount: Double?
        get() = null

    fun lastDataTime(): Long {
        val patchState = carelevoPatch.getPatchState()

        val lastDateTime = when (patchState) {
            is PatchState.ConnectedBooted,
            is PatchState.NotConnectedNotBooting -> System.currentTimeMillis()

            is PatchState.NotConnectedBooted -> {
                startReconnect()
                _lastDateTime
            }

            else -> _lastDateTime
        }

        aapsLogger.debug(LTag.CORE, "[CarelevoPumpPlugin::lastDataTime] Last connection: $patchState, : " + dateUtil.dateAndTimeString(lastDateTime))
        return lastDateTime
    }

    override val baseBasalRate: Double
        get() {
            return carelevoPatch.profile.value?.getOrNull()?.getBasal() ?: 0.0
        }
    override val reservoirLevel: Double
        get() {
            return carelevoPatch.patchInfo.value?.getOrNull()?.insulinRemain ?: 0.0
        }
    override val batteryLevel: Int
        get() = 0

    // start imme bolus infusion
    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::deliverTreatment] detailedBolusInfo : ${detailedBolusInfo.bolusType}")
        require(detailedBolusInfo.carbs == 0.0) { detailedBolusInfo.toString() }
        require(detailedBolusInfo.insulin > 0) { detailedBolusInfo.toString() }

        val result = pumpEnactResultProvider.get()
        if (!carelevoPatch.isBluetoothEnabled()) {
            return result
        }
        if (!carelevoPatch.isCarelevoConnected()) {
            return result
        }
        isImmeBolusStop = false
        val actionId = (carelevoPatch.patchInfo.value?.getOrNull()?.bolusActionSeq ?: 0) + 1

        return try {
            startImmeBolusInfusionUseCase.execute(
                StartImmeBolusInfusionRequestModel(
                    actionSeq = actionId,
                    volume = detailedBolusInfo.insulin
                )
            )
                .timeout(30, TimeUnit.SECONDS)
                .observeOn(aapsSchedulers.io)
                .subscribeOn(aapsSchedulers.io)
                .doOnSuccess { response -> handleBolusSuccess(response, detailedBolusInfo, result) }
                .doOnError { e -> handleBolusError(e, result) }
                .map { result }
                .blockingGet()

        } catch (e: Throwable) {
            aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::deliverTreatment] timeout or unexpected error: $e")

            result.success = false
            result.enacted = false
            result.bolusDelivered = 0.0
            result
        }
    }

    private fun handleBolusSuccess(
        response: ResponseResult<*>,
        detailedInfo: DetailedBolusInfo,
        result: PumpEnactResult
    ) {
        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::deliverTreatment] Success: $detailedInfo")
        if (response !is ResponseResult.Success) return

        val data = response.data as StartImmeBolusInfusionResponseModel

        _lastDateTime = System.currentTimeMillis()

        val stepUnit = 0.05
        val totalInsulin = detailedInfo.insulin
        val totalSteps = ceil(totalInsulin / stepUnit).toInt()

        val delayMs = (data.expectSec * 1000L) / totalSteps
        (0..totalSteps).forEach { step ->
            if (!isImmeBolusStop) {
                if (step == totalSteps) {
                    rxBus.send(
                        EventOverviewBolusProgress(
                            rh,
                            percent = 100,
                            id = detailedInfo.id
                        )
                    )

                    pumpSync.syncBolusWithPumpId(
                        detailedInfo.timestamp,
                        detailedInfo.insulin,
                        detailedInfo.bolusType,
                        dateUtil.now(),
                        PumpType.CAREMEDI_CARELEVO,
                        serialNumber()
                    )
                    handleFinishImmeBolus()
                } else {
                    SystemClock.sleep(delayMs)

                    val delivering = min(
                        step * stepUnit,
                        detailedInfo.insulin
                    )

                    val percentage = min(
                        (delivering / detailedInfo.insulin * 100).toInt(),
                        100
                    )

                    rxBus.send(EventOverviewBolusProgress(rh, delivered = delivering, id = detailedInfo.id))
                }
            } else {
                return@forEach
            }
        }

        result.success = true
        result.enacted = true
        result.bolusDelivered = detailedInfo.insulin
    }

    private fun handleBolusError(e: Throwable, result: PumpEnactResult) {
        aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::deliverTreatment] error: $e")

        result.success = false
        result.enacted = false
        result.bolusDelivered = 0.0
        if (e is TimeoutException) {
            result.comment(rh.gs(R.string.alarm_feat_msg_check_patch_connect))
        }

    }

    private fun handleFinishImmeBolus() {
        pluginDisposable += finishImmeBolusInfusionUseCase.execute()
            .observeOn(aapsSchedulers.main)
            .subscribeOn(aapsSchedulers.io)
            .timeout(3000L, TimeUnit.MILLISECONDS)
            .subscribe { response ->
                when (response) {
                    is ResponseResult.Success -> {
                        _lastDateTime = System.currentTimeMillis()
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::handleFinishImmeBolus] response success")
                    }

                    is ResponseResult.Error -> {
                        aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::handleFinishImmeBolus] response error : ${response.e}")
                    }

                    else -> {
                        aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::handleFinishImmeBolus] response failed")
                    }
                }
            }
    }

    // cancel imme bolus
    override fun stopBolusDelivering() {
        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::stopBolusDelivering] start cancel immediate bolus")

        pluginDisposable += cancelImmeBolusInfusionUseCase.execute()
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .timeout(3000L, TimeUnit.MILLISECONDS)
            .subscribe(
                { response ->
                    when (response) {
                        is ResponseResult.Success -> {
                            _lastDateTime = System.currentTimeMillis()
                            val result = response.data as CancelBolusInfusionResponseModel
                            aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::stopBolusDelivering] response success result : $result")
                            rxBus.send(EventOverviewBolusProgress(status = rh.gs(app.aaps.core.interfaces.R.string.bolus_delivered_successfully, result.infusedAmount.toFloat())))
                            pumpSync.syncBolusWithPumpId(
                                dateUtil.now(),
                                result.infusedAmount,
                                BS.Type.NORMAL,
                                dateUtil.now(),
                                PumpType.CAREMEDI_CARELEVO,
                                serialNumber()
                            )
                            isImmeBolusStop = true
                        }

                        is ResponseResult.Error -> {
                            aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::stopBolusDelivering] response error : ${response.e}")
                        }

                        else -> {
                            aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::stopBolusDelivering] response failed")
                        }
                    }
                },
                { throwable ->
                    if (throwable is TimeoutException) {
                        aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::stopBolusDelivering] TIMEOUT (3000ms)")
                    } else {
                        aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::stopBolusDelivering] error : $throwable")
                    }
                }
            )
    }

    override fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        aapsLogger.info(LTag.PUMP, "setTempBasalAbsolute - absoluteRate: ${absoluteRate.toFloat()}, durationInMinutes: ${durationInMinutes.toLong()}, enforceNew: $enforceNew")
        val result = pumpEnactResultProvider.get()
        if (!carelevoPatch.isBluetoothEnabled()) {
            aapsLogger.info(LTag.PUMP, "[CarelevoPumpPlugin::setTempBasalAbsolute] bluetooth is not enabled")
            return result
        }
        if (!carelevoPatch.isCarelevoConnected()) {
            aapsLogger.info(LTag.PUMP, "[CarelevoPumpPlugin::setTempBasalAbsolute] carelevo is not connected")
            return result
        }

        return startTempBasalInfusionUseCase.execute(
            StartTempBasalInfusionRequestModel(
                isUnit = true,
                speed = absoluteRate,
                minutes = durationInMinutes
            )
        )
            .observeOn(aapsSchedulers.io)
            .subscribeOn(aapsSchedulers.io)
            .doOnSuccess { response ->
                when (response) {
                    is ResponseResult.Success -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::setTempBasalAbsolute] response success")
                        _lastDateTime = System.currentTimeMillis()
                        pumpSync.syncTemporaryBasalWithPumpId(
                            timestamp = dateUtil.now(),
                            rate = absoluteRate,
                            duration = T.mins(durationInMinutes.toLong()).msecs(),
                            isAbsolute = true,
                            type = tbrType,
                            pumpId = dateUtil.now(),
                            pumpType = PumpType.CAREMEDI_CARELEVO,
                            pumpSerial = serialNumber()
                        )
                        result.success(true).enacted(true).duration(durationInMinutes).absolute(absoluteRate).isPercent(false).isTempCancel(false)
                    }

                    is ResponseResult.Error -> {
                        aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::setTempBasalAbsolute] response error : ${response.e}")
                    }

                    else -> {
                        aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::setTempBasalAbsolute] response failed")
                    }
                }
            }.doOnError {
                result.success(false).enacted(false)
            }.map {
                result
            }.blockingGet()
    }

    override fun setTempBasalPercent(percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        val result = pumpEnactResultProvider.get()
        if (!carelevoPatch.isBluetoothEnabled()) {
            aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::setTempBasalPercent] bluetooth is not enabled")
            return result
        }
        if (!carelevoPatch.isCarelevoConnected()) {
            aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::setTempBasalPercent] carelevo is not connected")
            return result
        }

        return startTempBasalInfusionUseCase.execute(
            StartTempBasalInfusionRequestModel(
                isUnit = false,
                percent = percent,
                minutes = durationInMinutes
            )
        )
            .observeOn(aapsSchedulers.io)
            .subscribeOn(aapsSchedulers.io)
            .timeout(3000L, TimeUnit.MILLISECONDS)
            .doOnSuccess { response ->
                when (response) {
                    is ResponseResult.Success -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::setTempBasalPercent] response success")
                        _lastDateTime = System.currentTimeMillis()
                        pumpSync.syncTemporaryBasalWithPumpId(
                            timestamp = dateUtil.now(),
                            rate = percent.toDouble(),
                            duration = T.mins(durationInMinutes.toLong()).msecs(),
                            isAbsolute = false,
                            type = tbrType,
                            pumpId = dateUtil.now(),
                            pumpType = PumpType.CAREMEDI_CARELEVO,
                            pumpSerial = serialNumber()
                        )
                        result.success = true
                        result.enacted = true
                        result.duration = durationInMinutes
                        result.percent = percent
                        result.isPercent = true
                        result.isTempCancel = false
                    }

                    is ResponseResult.Error -> {
                        aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::setTempBasalPercent] response error : ${response.e}")
                    }

                    else -> {
                        aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::setTempBasalPercent] response failed")
                    }
                }
            }.doOnError {
                result.success = false
                result.enacted = false
            }.map {
                result
            }.blockingGet()
    }

    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        val result = pumpEnactResultProvider.get()
        if (!carelevoPatch.isBluetoothEnabled()) {
            return result
        }
        if (!carelevoPatch.isCarelevoConnected()) {
            return result
        }

        return cancelTempBasalInfusionUseCase.execute()
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.io)
            .timeout(3000L, TimeUnit.MILLISECONDS)
            .map { response ->
                when (response) {
                    is ResponseResult.Success -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::cancelTempBasal] response success")
                        _lastDateTime = System.currentTimeMillis()
                        pumpSync.syncStopTemporaryBasalWithPumpId(
                            timestamp = dateUtil.now(),
                            endPumpId = dateUtil.now(),
                            pumpType = PumpType.CAREMEDI_CARELEVO,
                            pumpSerial = serialNumber()
                        )
                        result.success = true
                        result.enacted = true
                        result.isTempCancel = true
                    }

                    else -> {
                        result.success = false
                        result.enacted = false
                    }
                }
                result
            }
            .onErrorReturn { e ->
                aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::cancelTempBasal] timeout or error : $e")
                result.success = false
                result.enacted = false
                result
            }
            .blockingGet()
    }

    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult {
        val result = pumpEnactResultProvider.get()
        if (!carelevoPatch.isBluetoothEnabled()) {
            return result
        }
        if (!carelevoPatch.isCarelevoConnected()) {
            return result
        }

        return startExtendBolusInfusionUseCase.execute(
            StartExtendBolusInfusionRequestModel(
                volume = insulin,
                minutes = durationInMinutes
            )
        )
            .observeOn(aapsSchedulers.io)
            .subscribeOn(aapsSchedulers.io)
            .timeout(3000L, TimeUnit.MILLISECONDS)
            .doOnSuccess { response ->
                when (response) {
                    is ResponseResult.Success -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::setExtendedBolus] response success")
                        _lastDateTime = System.currentTimeMillis()
                        pumpSync.syncExtendedBolusWithPumpId(
                            timestamp = dateUtil.now(),
                            amount = insulin,
                            duration = T.mins(durationInMinutes.toLong()).msecs(),
                            isEmulatingTB = false,
                            pumpId = dateUtil.now(),
                            pumpType = PumpType.CAREMEDI_CARELEVO,
                            pumpSerial = serialNumber()
                        )
                        result.success = true
                        result.enacted = true
                    }

                    is ResponseResult.Error -> {
                        aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::setExtendedBolus] response error : ${response.e}")
                    }

                    else -> {
                        aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::setExtendedBolus] response failed")
                    }
                }
            }.doOnError {
                result.success = false
                result.enacted = false
            }.map {
                result
            }.blockingGet()
    }

    override fun cancelExtendedBolus(): PumpEnactResult {
        val result = pumpEnactResultProvider.get()
        if (!carelevoPatch.isBluetoothEnabled()) {
            return result
        }
        if (!carelevoPatch.isCarelevoConnected()) {
            return result
        }

        return cancelExtendBolusInfusionUseCase.execute()
            .observeOn(aapsSchedulers.io)
            .subscribeOn(aapsSchedulers.io)
            .timeout(3000L, TimeUnit.MILLISECONDS)
            .doOnSuccess { response ->
                when (response) {
                    is ResponseResult.Success -> {
                        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::cancelExtendedBolus] response success")
                        _lastDateTime = System.currentTimeMillis()
                        pumpSync.syncStopExtendedBolusWithPumpId(
                            timestamp = dateUtil.now(),
                            endPumpId = dateUtil.now(),
                            pumpType = PumpType.CAREMEDI_CARELEVO,
                            pumpSerial = serialNumber()
                        )
                        result.success = true
                        result.enacted = true
                        result.isTempCancel = true
                    }

                    is ResponseResult.Error -> {
                        aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::cancelExtendedBolus] response error : ${response.e}")
                    }

                    else -> {
                        aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::cancelExtendedBolus] response failed")
                    }
                }
            }.doOnError {
                result.success = false
                result.enacted = false
            }.map {
                result
            }.blockingGet()
    }

    fun getJSONStatus(profile: Profile, profileName: String, version: String): JSONObject {
        val now = System.currentTimeMillis()
        val pumpJson = JSONObject()
        val battery = JSONObject()
        val status = JSONObject()
        val extended = JSONObject()
        try {
            battery.put("percent", 100)
            val isPumpStop = carelevoPatch.patchInfo.value?.getOrNull()?.isStopped ?: false
            status.put("status", isPumpStop)
            status.put("timestamp", dateUtil.toISOString(lastDataTime()))
            extended.put("Version", version)
            val tb = pumpSync.expectedPumpState().temporaryBasal
            if (tb != null) {
                extended.put("TempBasalAbsoluteRate", tb.convertedToAbsolute(now, profile))
                extended.put("TempBasalStart", dateUtil.dateAndTimeString(tb.timestamp))
                extended.put("TempBasalRemaining", tb.plannedRemainingMinutes)
            }
            val eb = pumpSync.expectedPumpState().extendedBolus
            if (eb != null) {
                extended.put("ExtendedBolusAbsoluteRate", eb.rate)
                extended.put("ExtendedBolusStart", dateUtil.dateAndTimeString(eb.timestamp))
                extended.put("ExtendedBolusRemaining", eb.plannedRemainingMinutes)
            }
            extended.put("BaseBasalRate", baseBasalRate)
            try {
                extended.put("ActiveProfile", profileFunction.getProfile())
            } catch (e: Exception) {
                e.printStackTrace()
            }
            pumpJson.put("battery", battery)
            pumpJson.put("status", status)
            pumpJson.put("extended", extended)
            pumpJson.put("reservoir", carelevoPatch.patchInfo.value?.getOrNull()?.insulinRemain ?: 0)
            pumpJson.put("clock", dateUtil.toISOString(now))
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        return pumpJson
    }

    override fun manufacturer(): ManufacturerType {
        return ManufacturerType.Carelevo
    }

    override fun model(): PumpType {
        return PumpType.CAREMEDI_CARELEVO
    }

    override fun serialNumber(): String {
        return carelevoPatch.patchInfo.value?.getOrNull()?.manufactureNumber ?: ""
    }

    override val pumpDescription: PumpDescription
        get() = _pumpDescription

    override val isFakingTempsByExtendedBoluses: Boolean
        get() = false

    override fun loadTDDs(): PumpEnactResult {
        return pumpEnactResultProvider.get()
    }

    override fun canHandleDST(): Boolean {
        return false
    }

    @SuppressLint("CheckResult")
    private fun startReconnect() {
        reconnectDisposable.clear()

        if (!carelevoPatch.isBluetoothEnabled()) {
            aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::startReconnect] bluetooth disabled")
            return
        }

        val address = carelevoPatch.patchInfo.value?.getOrNull()?.address?.uppercase() ?: return

        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::startReconnect] start reconnect : $address")

        isTryReconnected = true
        reconnectDisposable.add(
            bleController.execute(Connect(address))
                .subscribeOn(aapsSchedulers.io)
                .observeOn(aapsSchedulers.io)
                .subscribe(
                    { result ->
                        isTryReconnected = false
                        when (result) {
                            is CommandResult.Success -> {
                                aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::startReconnect] connect success")
                            }

                            else -> {
                                aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::startReconnect] connect failed")
                                stopReconnect()
                            }
                        }
                    },
                    { e ->
                        isTryReconnected = false
                        aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::startReconnect] connect error : $e")
                        stopReconnect()
                    }
                )
        )

        reconnectDisposable.add(
            carelevoPatch.btState
                .subscribeOn(aapsSchedulers.io)
                .observeOn(aapsSchedulers.io)
                .distinctUntilChanged()
                .timeout(10, TimeUnit.SECONDS)
                .subscribe(
                    { btState ->
                        btState.getOrNull()?.let { state ->
                            when {
                                state.shouldBeConnected() -> {
                                    aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::startReconnect] state=CONNECTED")

                                    bleController.execute(DiscoveryService(address))
                                        .subscribeOn(aapsSchedulers.io)
                                        .observeOn(aapsSchedulers.io)
                                        .subscribe { result ->
                                            if (result !is CommandResult.Success) {
                                                stopReconnect()
                                            }
                                        }
                                }

                                state.shouldBeDiscovered() -> {
                                    aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::startReconnect] state=DISCOVERED")
                                    bleController.execute(EnableNotifications(address, txUuid))
                                        .subscribeOn(aapsSchedulers.io)
                                        .observeOn(aapsSchedulers.io)
                                        .subscribe { result ->
                                            aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::startReconnect] DISCOVERED :$result")
                                            if (result !is CommandResult.Success) {
                                                stopReconnect()
                                            } else {
                                                aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::startReconnect] reconnect finished")
                                                stopReconnect()
                                            }
                                        }
                                }

                                state.isDiscoverCleared() ||
                                    state.isAbnormalBondingFailed() ||
                                    state.isReInitialized() -> {
                                    aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::startReconnect] abnormal state : $state")
                                    stopReconnect()
                                }
                            }
                        }
                    },
                    { e ->
                        aapsLogger.error(LTag.PUMP, "[CarelevoPumpPlugin::startReconnect] reconnect timeout/error : $e")
                        stopReconnect()
                    }
                )
        )
    }

    private fun stopReconnect() {
        aapsLogger.debug(LTag.PUMP, "[CarelevoPumpPlugin::stopReconnect] stop reconnect")
        reconnectDisposable.clear()
    }

    override fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {
        super.timezoneOrDSTChanged(timeChangeType)
        val insulin = carelevoPatch.patchInfo.value?.getOrNull()?.insulinRemain?.toInt() ?: 0
        pluginDisposable += carelevoPatchTimeZoneUpdateUseCase.execute(CarelevoPatchTimeZoneRequestModel(insulinAmount = insulin))
            .observeOn(aapsSchedulers.main)
            .subscribeOn(aapsSchedulers.io)
            .timeout(3000L, TimeUnit.MILLISECONDS)
            .subscribe()
    }
}