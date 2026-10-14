package com.example.parkinson.medication

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the person's Parkinson's medication is working at test time, as they report it. Results from ON and OFF
 * periods differ a lot, so they are only compared with each other when this is known. [id] is stable and stored.
 */
enum class MedicationState(val id: String) {
    /** The medication is working (the best period of the day). */
    ON("on"),

    /** The effect has worn off, or the dose has not been taken yet. */
    OFF("off"),
    UNSURE("unsure");

    companion object {
        fun fromId(id: String?): MedicationState? = entries.firstOrNull { it.id == id }
    }
}

/** The medication state [profileId] reported at [epochMs]. */
data class MedicationCheck(val profileId: Int, val state: MedicationState, val epochMs: Long) {

    /** Still describes the person at [nowEpochMs]; after [VALID_MS] it is asked again. */
    fun isCurrent(nowEpochMs: Long): Boolean = nowEpochMs - epochMs in 0..VALID_MS

    companion object {
        /** A levodopa dose usually works for a few hours, so a report is reused for at most 2 hours. */
        const val VALID_MS: Long = 2 * 60 * 60 * 1000L
    }
}

/** The last medication report of each person on this phone. */
interface MedicationCheckStore {
    val checks: StateFlow<Map<Int, MedicationCheck>>

    fun record(check: MedicationCheck)

    /** The report of [profileId] that still applies at [nowEpochMs], or null when it must be asked. */
    fun current(profileId: Int, nowEpochMs: Long): MedicationCheck? =
        checks.value[profileId]?.takeIf { it.isCurrent(nowEpochMs) }
}

class InMemoryMedicationCheckStore : MedicationCheckStore {
    private val all = MutableStateFlow<Map<Int, MedicationCheck>>(emptyMap())
    override val checks: StateFlow<Map<Int, MedicationCheck>> = all.asStateFlow()

    override fun record(check: MedicationCheck) {
        all.value = all.value + (check.profileId to check)
    }
}

/** Keeps only the last report per person (state and time), in this app's private preferences. */
class PrefsMedicationCheckStore(context: Context) : MedicationCheckStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val all = MutableStateFlow(load())
    override val checks: StateFlow<Map<Int, MedicationCheck>> = all.asStateFlow()

    override fun record(check: MedicationCheck) {
        prefs.edit().putString(KEY_PREFIX + check.profileId, "${check.state.id}|${check.epochMs}").apply()
        all.value = all.value + (check.profileId to check)
    }

    private fun load(): Map<Int, MedicationCheck> = prefs.all.mapNotNull { (key, value) ->
        val id = key.removePrefix(KEY_PREFIX).toIntOrNull() ?: return@mapNotNull null
        val parts = (value as? String)?.split('|') ?: return@mapNotNull null
        val state = MedicationState.fromId(parts.getOrNull(0)) ?: return@mapNotNull null
        val time = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        id to MedicationCheck(id, state, time)
    }.toMap()

    private companion object {
        const val PREFS = "medication_checks"
        const val KEY_PREFIX = "profile_"
    }
}
