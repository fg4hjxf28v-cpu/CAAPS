package info.nightscout.androidaps.plugins.pump.carelevo.domain.usecase.patch

import info.nightscout.androidaps.plugins.pump.carelevo.domain.CarelevoPatchObserver
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.RequestResult
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.bt.SafetyCheckResult
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.bt.SafetyCheckResultModel
import info.nightscout.androidaps.plugins.pump.carelevo.domain.repository.CarelevoPatchInfoRepository
import info.nightscout.androidaps.plugins.pump.carelevo.domain.repository.CarelevoPatchRepository
import info.nightscout.androidaps.plugins.pump.carelevo.domain.type.SafetyProgress
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.joda.time.DateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class CarelevoPatchSafetyCheckUseCase @Inject constructor(
    private val patchObserver: CarelevoPatchObserver,
    private val patchRepository: CarelevoPatchRepository,
    private val patchInfoRepository: CarelevoPatchInfoRepository
) {

    /*fun execute() : Single<ResponseResult<CarelevoUseCaseResponse>> {
        return Single.fromCallable {
            runCatching {
                patchRepository.requestSafetyCheck()
                    .observeOn(Schedulers.io())
                    .blockingGet()
                    .takeIf { it is RequestResult.Pending }
                    ?: throw IllegalStateException("request safety check is not pending")

                aapsLogger.debug(LTag.PUMP, "[CarelevoRxPatchSafetyCheckUseCase] 1. 안전점검 요청")

                val requestSafetyCheckResult = patchObserver.patchEvent
                    .ofType<SafetyCheckResultModel>()
                    .blockingFirst()

                aapsLogger.debug(LTag.PUMP, "[CarelevoRxPatchSafetyCheckUseCase] 2. 안전점검 요청 결과 수신 : $requestSafetyCheckResult")

                if(!(requestSafetyCheckResult.result == SafetyCheckResult.REP_REQUEST || requestSafetyCheckResult.result == SafetyCheckResult.REP_REQUEST1)) {
                    throw IllegalStateException("request safety check result is failed")
                }

                val currentThread = Thread.currentThread().name
                aapsLogger.debug(LTag.PUMP, "[CarelevoRxPatchSafetyCheckUseCase] current thread : $currentThread")

                val ackResult = patchObserver.patchEvent
                    .ofType<SafetyCheckResultModel>()
                    .blockingFirst()

                aapsLogger.debug(LTag.PUMP, "[CarelevoRxPatchSafetyCheckUseCase] 3. 안전점검 요청 결과 액크 수신 : $ackResult")

                if(ackResult.result != SafetyCheckResult.SUCCESS) {
                    throw IllegalStateException("safety check ack result is failed")
                }

                val patchInfo = patchInfoRepository.getPatchInfoBySync()
                    ?: throw NullPointerException("patch info must be not null")

                val updatePatchInfoResult = patchInfoRepository.updatePatchInfo(
                    patchInfo.copy(checkSafety = true, updatedAt = DateTime.now())
                )
                if(!updatePatchInfoResult) {
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
        }.subscribeOn(Schedulers.io())
    }*/
    /*    fun execute(): Single<ResponseResult<CarelevoUseCaseResponse>> {
            return patchRepository.requestSafetyCheck()
                .subscribeOn(Schedulers.io())
                .flatMap { req ->
                    if (req !is RequestResult.Pending) {
                        return@flatMap Single.error(IllegalStateException("request safety check is not pending"))
                    }

                    aapsLogger.debug(LTag.PUMP, "[UseCase] 1. 안전점검 요청 보냄")

                    // 1) REQ/REQ1 기다리기 (짧은 기본 타임아웃)
                    val requestReplySingle = patchObserver.patchEvent
                        .ofType(SafetyCheckResultModel::class.java)
                        .filter { it.result == SafetyCheckResult.REP_REQUEST || it.result == SafetyCheckResult.REP_REQUEST1 }
                        .firstOrError()
                        .timeout(100, TimeUnit.SECONDS) // 첫 응답은 보통 빨리 옴(필요 시 조정)

                    requestReplySingle.flatMap { requestReply ->
                        aapsLogger.debug(LTag.PUMP, "[UseCase] 2. 요청 결과 수신: $requestReply, ${requestReply.durationSeconds}")

                        val timeoutSec = (requestReply.durationSeconds + 30).toLong()
                        aapsLogger.debug(LTag.PUMP, "[UseCase] ACK 타임아웃: ${timeoutSec}s")

                        // 2) ACK(SUCCESS) 기다리기 - durationSeconds 만큼
                        patchObserver.patchEvent
                            .ofType(SafetyCheckResultModel::class.java)
                            .filter { it.result == SafetyCheckResult.SUCCESS }
                            .firstOrError()
                            .timeout(timeoutSec, TimeUnit.SECONDS)
                    }
                }
                .flatMap {
                    aapsLogger.debug(LTag.PUMP, "[UseCase] 3. ACK 수신: $it")

                    val patchInfo = patchInfoRepository.getPatchInfoBySync()
                        ?: return@flatMap Single.error(NullPointerException("patch info must be not null"))

                    val ok = patchInfoRepository.updatePatchInfo(
                        patchInfo.copy(checkSafety = true, updatedAt = DateTime.now())
                    )
                    if (!ok) return@flatMap Single.error(IllegalStateException("update patch info is failed"))

                    Single.just<ResponseResult<CarelevoUseCaseResponse>>(
                        ResponseResult.Success(ResultSuccess as CarelevoUseCaseResponse)
                    )
                }
                .onErrorReturn { e ->
                    ResponseResult.Error(e)
                }
        }*/
    fun execute(): Observable<SafetyProgress> {
        return patchRepository.requestSafetyCheck()
            .subscribeOn(Schedulers.io())
            .flatMapObservable { req ->
                if (req !is RequestResult.Pending) {
                    return@flatMapObservable Observable.error(
                        IllegalStateException("request safety check is not pending")
                    )
                }

                // 1) REQ/REQ1 기다리기 (짧은 기본 타임아웃)
                val requestReplySingle = patchObserver.patchEvent
                    .ofType(SafetyCheckResultModel::class.java)
                    .filter { it.result == SafetyCheckResult.REP_REQUEST || it.result == SafetyCheckResult.REP_REQUEST1 }
                    .firstOrError()
                    .timeout(100, TimeUnit.SECONDS)

                requestReplySingle
                    .toObservable()
                    .flatMap { requestReply ->
                        val timeoutSec = (requestReply.durationSeconds + 30).toLong()

                        val progress: Observable<SafetyProgress> = Observable.just(SafetyProgress.Progress(timeoutSec))

                        val success: Observable<SafetyProgress> =
                            patchObserver.patchEvent
                                .ofType(SafetyCheckResultModel::class.java)
                                .filter { it.result == SafetyCheckResult.SUCCESS }
                                .firstOrError()
                                .timeout(timeoutSec, TimeUnit.SECONDS)
                                .toObservable()
                                .map {
                                    val patchInfo = patchInfoRepository.getPatchInfoBySync()
                                        ?: return@map SafetyProgress.Error(NullPointerException("patch info must be not null"))

                                    val ok = patchInfoRepository.updatePatchInfo(
                                        patchInfo.copy(checkSafety = true, updatedAt = DateTime.now())
                                    )
                                    if (!ok) return@map SafetyProgress.Error(IllegalStateException("update patch info is failed"))

                                    SafetyProgress.Success(it)
                                }


                        progress.concatWith(success)
                    }
            }
            .onErrorReturn { SafetyProgress.Error(it) }
    }
}
