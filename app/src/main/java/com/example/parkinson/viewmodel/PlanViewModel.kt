package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.plan.Dose
import com.example.parkinson.plan.Intake
import com.example.parkinson.plan.IntakeStatus
import com.example.parkinson.plan.PlanKind
import com.example.parkinson.plan.PlanSchedule
import com.example.parkinson.plan.PlanSync
import com.example.parkinson.plan.Prescription
import com.example.parkinson.profile.UserProfile
import com.example.parkinson.sync.ServerLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.TimeZone

/** A dose of today and what the person marked. */
data class DoseRow(val dose: Dose, val status: IntakeStatus?)

/** An exercise of the plan and how often it was done. */
data class ExerciseRow(val prescription: Prescription, val doneLast7Days: Int, val doneToday: Boolean)

/** The active person's plan for today. */
data class PlanState(
    val personName: String = "",
    val linked: Boolean = false,
    /** A plan was received from the server at least once (it may be empty). */
    val hasPlan: Boolean = false,
    val refreshing: Boolean = false,
    /** The last refresh did not reach the server; the saved plan is shown. */
    val offline: Boolean = false,
    val doses: List<DoseRow> = emptyList(),
    val exercises: List<ExerciseRow> = emptyList(),
    /** Marks the doctor's server does not have yet. */
    val pending: Int = 0,
) {
    /** Today's doses not marked yet, for Home. */
    val dosesLeft: Int get() = doses.count { it.status == null }
}

class PlanViewModel(
    private val sync: PlanSync,
    links: StateFlow<Map<Int, ServerLink>>,
    private val activeProfile: Flow<UserProfile?>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::getDefault,
) : ViewModel() {

    private data class Flags(val refreshing: Boolean = false, val offline: Boolean = false)

    private val flags = MutableStateFlow(Flags())

    val state: StateFlow<PlanState> = combine(activeProfile, links, sync.store.version, flags) { profile, links, _, f ->
        build(profile, links, f)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlanState())

    private fun build(profile: UserProfile?, links: Map<Int, ServerLink>, f: Flags): PlanState {
        if (profile == null) return PlanState()
        val saved = sync.store.plans()[profile.id]
        val plan = saved?.prescriptions.orEmpty()
        val marks = sync.store.marks(profile.id)
        val now = clock()
        val tz = zone()
        val today = PlanSchedule.startOfDay(now, tz)
        val doses = PlanSchedule.dosesOfDay(plan, now, tz).map { d ->
            DoseRow(d, marks[Intake(d.prescription.id, d.atEpochMs, IntakeStatus.TAKEN).key]?.status)
        }
        val exercises = plan.filter { it.kind == PlanKind.EXERCISE && PlanSchedule.runsOn(it, today, tz) }.map { p ->
            val done = marks.values.filter { it.prescriptionId == p.id && it.status == IntakeStatus.DONE }
            ExerciseRow(p, done.count { it.scheduledAtEpochMs >= now - 7 * PlanSchedule.DAY_MS }, done.any { it.scheduledAtEpochMs >= today })
        }
        return PlanState(
            personName = profile.firstName,
            linked = links[profile.id] != null,
            hasPlan = saved != null,
            refreshing = f.refreshing,
            offline = f.offline,
            doses = doses,
            exercises = exercises,
            pending = sync.store.pending(profile.id).size,
        )
    }

    /** Reads the active person's plan from their doctor's server again. */
    fun refresh() {
        if (flags.value.refreshing) return
        viewModelScope.launch {
            val profile = activeProfile.first() ?: return@launch
            flags.update { it.copy(refreshing = true) }
            val offline = try {
                sync.refresh(profile.id, profile.firstName)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                true // ServerException, or an answer this version cannot read.
            }
            flags.value = Flags(refreshing = false, offline = offline)
        }
    }

    /** Marks a dose taken or missed (again, to change an earlier mark). */
    fun mark(row: DoseRow, status: IntakeStatus) = record(Intake(row.dose.prescription.id, row.dose.atEpochMs, status))

    fun exerciseDone(p: Prescription) = record(Intake(p.id, clock(), IntakeStatus.DONE))

    private fun record(intake: Intake) {
        viewModelScope.launch {
            val profile = activeProfile.first() ?: return@launch
            sync.mark(profile.id, intake)
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ParkinsonApplication
                PlanViewModel(app.planSync, app.resultSync.links, app.activePatient.profile)
            }
        }
    }
}
