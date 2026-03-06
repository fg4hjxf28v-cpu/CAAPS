package info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.patch

import android.util.Log
import info.nightscout.androidaps.plugins.pump.carelevo.domain.CarelevoPatchObserver
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.RequestResult
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.ResponseResult
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.bt.AlertAlarmSetResultModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.bt.AppAuthAckResultModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.bt.PatchInformationInquiryDetailModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.bt.PatchInformationInquiryModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.bt.Result
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.bt.RetrieveAddressRequest
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.bt.RetrieveAddressResultModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.bt.SetAlertAlarmModeRequest
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.bt.SetTimeRequest
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.bt.ThresholdSetRequest
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.bt.ThresholdSetResultModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.patch.CarelevoPatchInfoDomainModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.result.ResultSuccess
import info.nightscout.androidaps.plugins.pump.carelevo.domain.repository.CarelevoPatchInfoRepository
import info.nightscout.androidaps.plugins.pump.carelevo.domain.repository.CarelevoPatchRepository
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.CarelevoUseCaseRequest
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.CarelevoUseCaseResponse
import info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.patch.model.CarelevoConnectNewPatchRequestModel
import info.nightscout.androidaps.plugins.pump.carelevo.ext.checkSumV2
import info.nightscout.androidaps.plugins.pump.carelevo.ext.convertHexToByteArray
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.kotlin.ofType
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class CarelevoConnectNewPatchUseCase @Inject constructor(
    private val patchObserver: CarelevoPatchObserver,
    private val patchRepository: CarelevoPatchRepository,
    private val patchInfoRepository: CarelevoPatchInfoRepository,
) {
    companion object {
        private const val PATCH_INFO_ROUND_RETRY_COUNT = 2
        private const val PATCH_EVENT_TIMEOUT_SEC = 10L
    }

    fun execute(request: CarelevoUseCaseRequest): Single<ResponseResult<CarelevoUseCaseResponse>> {
        return Single.fromCallable {
            runCatching {
                if (request !is CarelevoConnectNewPatchRequestModel) {
                    throw IllegalArgumentException("request is not CarelevoConnectNewPatchRequestModel")
                }

                Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] execute called")

                val randomKey = generateRandomKey(0..255)

                Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] 1. 랜덤키 생성 : $randomKey")

                patchRepository.requestRetrieveMacAddress(RetrieveAddressRequest(randomKey.toByte()))
                    .blockingGet()
                    .takeIf { it is RequestResult.Pending }
                    ?: throw IllegalStateException("")

                Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] 2. 맥 어드레스 요청")

                val addressInfoResult = patchObserver.patchEvent
                    .ofType<RetrieveAddressResultModel>()
                    .blockingFirst()

                Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] 3. 맥 어드레스 요청 결과 수신 : $addressInfoResult")

                if (addressInfoResult.address.isEmpty()) {
                    throw NullPointerException("mac address must be not empty")
                }

                val address = buildString {
                    for (i in 2 until 24 step 4) {
                        append(addressInfoResult.address.subSequence(i, i + 2))
                        if (i < 20) {
                            append(":")
                        }
                    }
                }

                Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] 4. 맥 어드레스 합성 : $address")

                val checkSum = (addressInfoResult.address + addressInfoResult.checkSum).convertHexToByteArray()

                val checkSumData = checkSum.checkSumV2(randomKey)

                Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] 5. 체크섬 결과 생성 : $checkSumData")

                patchRepository.requestAppAuth(checkSumData)
                    .blockingGet()
                    .takeIf { it is RequestResult.Pending }
                    ?: throw IllegalStateException("")

                Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] 6. 체크섬 결과 확인 요청")

                val appAuthResult = patchObserver.patchEvent
                    .ofType<AppAuthAckResultModel>()
                    .blockingFirst()

                Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] 7. 체크섬 결과 확인 요청 결과 수신 : $appAuthResult")

                if (appAuthResult.result != Result.SUCCESS) {
                    throw IllegalStateException("")
                }

                var patchInfoResult: PatchInformationInquiryModel? = null
                var inquiryDetailModel: PatchInformationInquiryDetailModel? = null

                for (round in 1..PATCH_INFO_ROUND_RETRY_COUNT) {
                    Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] 8. SET TIME 요청 round=$round/$PATCH_INFO_ROUND_RETRY_COUNT")
                    val (info, detail) = requestPatchInfoRound(request, round)
                    Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] 9. 패치 기본 정보 수신 : $info")
                    Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] 10. 패치 세부 정보 수신 : $detail")

                    val serial = info.serialNum.trim()
                    if (info.result == Result.SUCCESS && serial.isNotEmpty()) {
                        patchInfoResult = info
                        inquiryDetailModel = detail
                        break
                    }

                    Log.w(
                        "connect_test",
                        "[CarelevoRxConnectNewPatchUseCase] invalid patch info round=$round/$PATCH_INFO_ROUND_RETRY_COUNT result=${info.result} serial=$serial"
                    )
                }

                val finalPatchInfo = patchInfoResult ?: throw IllegalStateException("patch info invalid after retry")
                val finalPatchDetail = inquiryDetailModel ?: throw IllegalStateException("patch detail missing after retry")
                val serial = finalPatchInfo.serialNum

                if (finalPatchDetail.result != Result.SUCCESS) {
                    throw IllegalStateException("")
                }

                patchRepository.requestSetAlertAlarmMode(SetAlertAlarmModeRequest(0))
                    .blockingGet()
                    .takeIf { it is RequestResult.Pending }
                    ?: throw IllegalStateException("request set alarm mode is not pending")

                Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] 11. 알람 모드 설정 요청")

                val setAlarmModeResultModel = patchObserver.patchEvent
                    .ofType<AlertAlarmSetResultModel>()
                    .blockingFirst()

                Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] 12. 알람 모드 설정 요청 결과 수신 : $setAlarmModeResultModel")

                if (setAlarmModeResultModel.result != Result.SUCCESS) {
                    throw IllegalStateException("")
                }

                Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] : ${request.remains}, ${request.expiry}, ${request.maxBasalSpeed}, ${request.maxVolume}")
                patchRepository.requestSetThreshold(ThresholdSetRequest(request.remains, request.expiry, request.maxBasalSpeed, request.maxVolume, request.isBuzzOn))
                    .blockingGet()
                    .takeIf { it is RequestResult.Pending } ?: throw IllegalStateException("request set time is not pending")




                Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] 13. 사용자 설정값 설정 요청")

                val setThresholdResult = patchObserver.patchEvent
                    .ofType<ThresholdSetResultModel>()
                    .blockingFirst()

                Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] 14. 사용자 설정값 요청 결과 수신 : $setThresholdResult")

                if (setThresholdResult.result != Result.SUCCESS) {
                    throw IllegalStateException("")
                }

                val updatePatchInfoResult = patchInfoRepository.updatePatchInfo(
                    CarelevoPatchInfoDomainModel(
                        address = address,
                        manufactureNumber = serial,
                        firmwareVersion = finalPatchDetail.firmwareVer,
                        bootDateTime = finalPatchDetail.bootDateTime,
                        modelName = finalPatchDetail.modelName,
                        insulinAmount = request.volume,
                        insulinRemain = request.volume.toDouble(),
                        thresholdInsulinRemain = request.remains,
                        thresholdExpiry = request.expiry,
                        thresholdMaxBasalSpeed = request.maxBasalSpeed,
                        thresholdMaxBolusDose = request.maxVolume
                    )
                )

                Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] 15. 패치 정보 저장 : $updatePatchInfoResult")

                if (!updatePatchInfoResult) {
                    throw IllegalStateException("update patch info is failed")
                }
                ResultSuccess
            }.fold(
                onSuccess = {
                    ResponseResult.Success(it as CarelevoUseCaseResponse)
                },
                onFailure = {
                    ResponseResult.Error(it)
                }
            )
        }.observeOn(Schedulers.io())
    }

    private fun requestPatchInfoRound(
        request: CarelevoConnectNewPatchRequestModel,
        round: Int
    ): Pair<PatchInformationInquiryModel, PatchInformationInquiryDetailModel> {
        patchRepository.requestSetTime(SetTimeRequest("", request.volume, 0, 0))
            .blockingGet()
            .takeIf { it is RequestResult.Pending }
            ?: throw IllegalStateException("request set time is not pending")

        Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] waiting 9. 패치 기본 정보 수신 round=$round timeout=${PATCH_EVENT_TIMEOUT_SEC}s")
        val patchInfo = patchObserver.patchEvent
            .ofType<PatchInformationInquiryModel>()
            .timeout(PATCH_EVENT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .blockingFirst()

        Log.d("connect_test", "[CarelevoRxConnectNewPatchUseCase] waiting 10. 패치 세부 정보 수신 round=$round timeout=${PATCH_EVENT_TIMEOUT_SEC}s")
        val patchDetail = patchObserver.patchEvent
            .ofType<PatchInformationInquiryDetailModel>()
            .timeout(PATCH_EVENT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .blockingFirst()

        return patchInfo to patchDetail
    }

    private fun generateRandomKey(range: ClosedRange<Int>): Int {
        return range.run {
            (Math.random() * (endInclusive - start + 1) + start).toInt()
        }
    }
}
