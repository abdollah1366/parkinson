package com.example.parkinson.profile

import android.content.Context
import com.example.parkinson.data.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Remembers which registered person was chosen last on this phone (only an id, no personal data). */
interface ActiveProfileStore {
    val selectedId: StateFlow<Int?>

    fun select(id: Int?)
}

class PrefsActiveProfileStore(context: Context) : ActiveProfileStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val selected = MutableStateFlow(prefs.getInt(KEY, 0).takeIf { it != 0 })
    override val selectedId: StateFlow<Int?> = selected.asStateFlow()

    override fun select(id: Int?) {
        prefs.edit().apply { if (id == null) remove(KEY) else putInt(KEY, id) }.apply()
        selected.value = id
    }

    private companion object {
        const val PREFS = "active_profile"
        const val KEY = "profile_id"
    }
}

class InMemoryActiveProfileStore(initial: Int? = null) : ActiveProfileStore {
    private val selected = MutableStateFlow(initial)
    override val selectedId: StateFlow<Int?> = selected.asStateFlow()

    override fun select(id: Int?) {
        selected.value = id
    }
}

/**
 * The person the app is working for now: tests are saved for them, and only their results are shown and compared.
 * It is the chosen person, or the only registered person when nobody was chosen (for example right after the
 * update from the one-person version). With several people and no valid choice it is nobody, and Home asks for one.
 */
class ActivePatient(private val profiles: ProfileRepository, private val store: ActiveProfileStore) {

    val profile: Flow<UserProfile?> = combine(profiles.observeAll(), store.selectedId, ::resolve)

    val id: Flow<Int?> = profile.map { it?.id }.distinctUntilChanged()

    val selectedId: StateFlow<Int?> get() = store.selectedId

    fun select(id: Int?) = store.select(id)

    companion object {
        fun resolve(all: List<UserProfile>, selected: Int?): UserProfile? =
            all.firstOrNull { it.id == selected } ?: all.singleOrNull()
    }
}
