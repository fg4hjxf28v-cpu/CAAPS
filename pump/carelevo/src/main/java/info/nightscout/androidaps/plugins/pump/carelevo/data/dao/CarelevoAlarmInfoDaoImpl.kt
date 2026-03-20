package info.nightscout.androidaps.plugins.pump.carelevo.data.dao

import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import info.nightscout.androidaps.plugins.pump.carelevo.config.PrefEnvConfig
import info.nightscout.androidaps.plugins.pump.carelevo.data.common.CarelevoGsonHelper
import info.nightscout.androidaps.plugins.pump.carelevo.data.model.entities.CarelevoAlarmInfoEntity
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.subjects.BehaviorSubject
import jakarta.inject.Inject
import java.util.Optional

class CarelevoAlarmInfoDaoImpl @Inject constructor(
    private val prefManager: SP
) : CarelevoAlarmInfoDao {

    // 캐시: 알람 리스트
    private val _alarms: BehaviorSubject<Optional<List<CarelevoAlarmInfoEntity>>> = BehaviorSubject.create()

    override fun getAlarms(): Observable<Optional<List<CarelevoAlarmInfoEntity>>> {
        if (_alarms.value == null) {
            runCatching {
                val json = prefManager.getString(PrefEnvConfig.CARELEVO_ALARM_INFO_LIST, "")
                if (json.isBlank()) {
                    emptyList<CarelevoAlarmInfoEntity>()
                } else {
                    CarelevoGsonHelper.sharedGson().fromJson(json, Array<CarelevoAlarmInfoEntity>::class.java).toList().filter { it.acknowledged }
                }
            }.fold(
                onSuccess = { list ->
                    _alarms.onNext(Optional.of(list))
                },
                onFailure = { e ->
                    e.printStackTrace()
                    _alarms.onNext(Optional.ofNullable(null))
                }
            )
        }
        return _alarms
    }

    override fun getAlarmsOnce(includeUnacknowledged: Boolean): Single<Optional<List<CarelevoAlarmInfoEntity>>> {
        return Single.fromCallable {
            val list = ensureLoaded().let { current ->
                current.filter { it.acknowledged == includeUnacknowledged }
            }
            Optional.of(list)
        }
    }

    override fun setAlarms(list: List<CarelevoAlarmInfoEntity>): Completable {
        return Completable.fromAction {
            saveList(list)
            _alarms.onNext(Optional.of(list))
        }
    }

    override fun clearAlarms(): Completable = Completable.fromAction {
        prefManager.remove(PrefEnvConfig.CARELEVO_ALARM_INFO_LIST)
        _alarms.onNext(Optional.ofNullable(null))
    }

    override fun upsertAlarm(entity: CarelevoAlarmInfoEntity): Completable {
        return Completable.fromAction {
            val current = ensureLoaded()

            // 동일한 타입+원인 & 미확인 알람 찾기
            val idx = current.indexOfFirst {
                it.alarmType == entity.alarmType &&
                    it.cause == entity.cause &&
                    !it.acknowledged
            }

            val next = if (idx >= 0) {
                // 기존 알람 → count + 1, updatedAt 갱신
                current.toMutableList().apply {
                    val existing = this[idx]
                    this[idx] = existing.copy(
                        updatedAt = entity.updatedAt,
                        occurrenceCount = existing.occurrenceCount + 1
                    )
                }
            } else {
                // 신규 알람 추가
                current + entity.copy(occurrenceCount = 1)
            }

            saveList(next)
            _alarms.onNext(Optional.of(next))
        }
    }

    override fun markAcknowledged(alarmId: String, acknowledged: Boolean, updatedAt: String): Completable {
        return Completable.fromAction {
            val current = ensureLoaded()
            val next = current.filterNot { it.alarmId == alarmId }      // 제거

            saveList(next)
            _alarms.onNext(Optional.of(next))
        }
    }

    // ---------- 내부 유틸 ----------

    private fun ensureLoaded(): List<CarelevoAlarmInfoEntity> {
        // 캐시에 값이 없으면 Pref에서 즉시 로드
        val cached = _alarms.value?.orElse(null)
        if (cached != null) return cached

        val json = prefManager.getString(PrefEnvConfig.CARELEVO_ALARM_INFO_LIST, "")
        val list = if (json.isBlank()) emptyList() else CarelevoGsonHelper.sharedGson()
            .fromJson(json, Array<CarelevoAlarmInfoEntity>::class.java)
            .toList()
        _alarms.onNext(Optional.of(list))
        return list
    }

    private fun saveList(list: List<CarelevoAlarmInfoEntity>) {
        val json = CarelevoGsonHelper.sharedGson().toJson(list)
        prefManager.putString(PrefEnvConfig.CARELEVO_ALARM_INFO_LIST, json)
    }
}
