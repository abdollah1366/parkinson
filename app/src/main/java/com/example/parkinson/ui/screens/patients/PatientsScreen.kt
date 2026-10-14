package com.example.parkinson.ui.screens.patients

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.profile.Gender
import com.example.parkinson.profile.UserProfile
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.ui.format.toPersianDigits

/**
 * Everyone registered on this phone. Choosing a person makes the tests and results theirs; each person can be edited
 * (or deleted from the edit screen), and new people can be added.
 */
@Composable
fun PatientsScreen(
    profiles: List<UserProfile>,
    activeId: Int?,
    onSelect: (UserProfile) -> Unit,
    onEdit: (UserProfile) -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            stringResource(R.string.patients_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        Text(stringResource(R.string.patients_intro), style = MaterialTheme.typography.bodyLarge)

        for (person in profiles) {
            PatientCard(person, active = person.id == activeId, onSelect = { onSelect(person) }, onEdit = { onEdit(person) })
        }

        PrimaryButton(text = stringResource(R.string.patients_add), onClick = onAdd)
    }
}

@Composable
private fun PatientCard(person: UserProfile, active: Boolean, onSelect: () -> Unit, onEdit: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(person.fullName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                stringResource(
                    R.string.home_profile_line,
                    person.age.toPersianDigits(),
                    stringResource(if (person.gender == Gender.MALE) R.string.reg_gender_male else R.string.reg_gender_female),
                    PersianFormat.digits(person.maskedNationalId),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (active) {
                    Text(
                        stringResource(R.string.patients_active),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = onSelect) { Text(stringResource(R.string.patients_select)) }
                } else {
                    OutlinedButton(onClick = onSelect, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.patients_select))
                    }
                }
                TextButton(onClick = onEdit) { Text(stringResource(R.string.patients_edit)) }
            }
        }
    }
}
