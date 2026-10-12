package com.example.parkinson

import android.app.Application
import android.content.pm.ApplicationInfo
import com.example.parkinson.assessment.AndroidDeviceCapabilities
import com.example.parkinson.assessment.DeviceCapabilities
import com.example.parkinson.data.AssessmentDatabase
import com.example.parkinson.data.AssessmentRepository
import com.example.parkinson.data.ProfileRepository
import com.example.parkinson.data.RoomProfileRepository
import com.example.parkinson.data.RoomAssessmentRepository
import com.example.parkinson.diagnostics.SensorDiagnostics
import com.example.parkinson.diagnostics.TapDiagnostics
import com.example.parkinson.sensors.AndroidMotionSensorSource
import com.example.parkinson.sensors.MotionSensorSource
import com.example.parkinson.gait.GaitDiagnostics
import com.example.parkinson.medication.MedicationCheckStore
import com.example.parkinson.medication.PrefsMedicationCheckStore
import com.example.parkinson.norms.NormComparator
import com.example.parkinson.profile.ActivePatient
import com.example.parkinson.profile.PrefsActiveProfileStore
import com.example.parkinson.norms.ReferenceNorms
import com.example.parkinson.tremor.RestingTremorDiagnostics
import com.example.parkinson.data.ResultRowReader
import com.example.parkinson.sync.HttpServerApi
import com.example.parkinson.sync.PrefsServerLinkStore
import com.example.parkinson.sync.ResultSync
import com.example.parkinson.sync.ServerApi
import com.example.parkinson.sync.ServerException
import com.example.parkinson.sync.ServerLinkStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Holds the app-wide singletons (a minimal manual dependency container). */
class ParkinsonApplication : Application() {

    private val database: AssessmentDatabase by lazy { AssessmentDatabase.build(this) }

    val profileRepository: ProfileRepository by lazy { RoomProfileRepository.from(database) }

    /** Who the tests are for; several people can be registered on one phone. */
    val activePatient: ActivePatient by lazy { ActivePatient(profileRepository, PrefsActiveProfileStore(this)) }

    /** The medication state each person reported before their tests (only the last report per person). */
    val medicationChecks: MedicationCheckStore by lazy { PrefsMedicationCheckStore(this) }

    val assessmentRepository: AssessmentRepository by lazy {
        RoomAssessmentRepository.from(database, activePatient.id) { profileId ->
            profileId?.let { medicationChecks.current(it, System.currentTimeMillis())?.state }
        }
    }

    /** The doctors' servers; tests replace it before anything is sent. */
    var serverApi: ServerApi = HttpServerApi()

    private val serverLinks: ServerLinkStore by lazy { PrefsServerLinkStore(this) }

    /** Sends each connected person's results to their doctor; nothing is sent for people who are not connected. */
    val resultSync: ResultSync by lazy {
        val api = object : ServerApi {
            override suspend fun link(serverUrl: String, inviteCode: String, patient: JSONObject) =
                serverApi.link(serverUrl, inviteCode, patient)
            override suspend fun upload(serverUrl: String, token: String, results: List<JSONObject>) =
                serverApi.upload(serverUrl, token, results)
            override suspend fun updateProfile(serverUrl: String, token: String, patient: JSONObject) =
                serverApi.updateProfile(serverUrl, token, patient)
            override suspend fun revoke(serverUrl: String, token: String) = serverApi.revoke(serverUrl, token)
        }
        val rows = ResultRowReader(database)
        ResultSync(api, serverLinks, rows::rows)
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val motionSensorSource: MotionSensorSource by lazy { AndroidMotionSensorSource(this) }

    val deviceCapabilities: DeviceCapabilities by lazy { AndroidDeviceCapabilities(this) }

    /** Published reference values bundled in assets (no server), read once on first use. */
    val normComparator: NormComparator by lazy {
        NormComparator(ReferenceNorms.parse(assets.open(ReferenceNorms.ASSET_PATH).bufferedReader().use { it.readText() }))
    }

    override fun onCreate() {
        super.onCreate()
        // Diagnostic Logcat output only in debuggable builds, never in release builds.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        TapDiagnostics.enabled = debuggable
        SensorDiagnostics.enabled = debuggable
        RestingTremorDiagnostics.enabled = debuggable
        GaitDiagnostics.enabled = debuggable
        startAutoSync()
    }

    /**
     * While someone is connected to a doctor: sends the active person's new results and profile edits shortly after
     * they are saved, and retries with growing pauses while the phone is offline or the server is down.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startAutoSync() {
        appScope.launch {
            resultSync.links.map { it.keys }.distinctUntilChanged().flatMapLatest { linked ->
                if (linked.isEmpty()) emptyFlow()
                else combine(activePatient.profile, assessmentRepository.observeStoredHistory()) { p, h -> p to h }
            }.collectLatest { (profile, history) ->
                if (profile == null || resultSync.links.value[profile.id] == null) return@collectLatest
                delay(AUTO_SYNC_DELAY_MS)
                var pause = RETRY_FIRST_MS
                while (true) {
                    val retry = try {
                        resultSync.sync(profile, history)
                        false
                    } catch (e: ServerException) {
                        e.code == ServerException.NETWORK || e.httpStatus >= 500
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false // Never crash the app over a background send; the link screen offers "send now".
                    }
                    if (!retry) break
                    delay(pause)
                    pause = (pause * 2).coerceAtMost(RETRY_MAX_MS)
                }
            }
        }
    }

    private companion object {
        const val AUTO_SYNC_DELAY_MS = 2_000L
        const val RETRY_FIRST_MS = 30_000L
        const val RETRY_MAX_MS = 15 * 60_000L
    }
}
