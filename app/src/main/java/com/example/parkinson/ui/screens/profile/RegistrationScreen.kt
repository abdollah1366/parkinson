package com.example.parkinson.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.profile.DominantHand
import com.example.parkinson.profile.Gender
import com.example.parkinson.profile.ProfileField
import com.example.parkinson.profile.ProfileForm
import com.example.parkinson.profile.ProfileRules
import com.example.parkinson.profile.ProfileValidation
import com.example.parkinson.profile.UserProfile
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.format.toPersianDigits
import com.example.parkinson.viewmodel.ProfileWrite

/**
 * Registration: name, age (10-110), gender, mobile, national ID, a known Parkinson's diagnosis, medication and the
 * dominant hand, with consent to keep the data on this phone. [existing] fills the form for editing; [onDelete] is
 * offered only then.
 */
@Composable
fun RegistrationScreen(
    existing: UserProfile?,
    write: ProfileWrite,
    onSave: (ProfileForm) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val start = remember(existing) { existing?.let(ProfileForm::from) ?: ProfileForm() }
    var firstName by rememberSaveable(existing) { mutableStateOf(start.firstName) }
    var lastName by rememberSaveable(existing) { mutableStateOf(start.lastName) }
    var age by rememberSaveable(existing) { mutableStateOf(start.age) }
    var gender by rememberSaveable(existing) { mutableStateOf(start.gender) }
    var mobile by rememberSaveable(existing) { mutableStateOf(start.mobile) }
    var nationalId by rememberSaveable(existing) { mutableStateOf(start.nationalId) }
    var diagnosed by rememberSaveable(existing) { mutableStateOf(start.parkinsonDiagnosed) }
    var medication by rememberSaveable(existing) { mutableStateOf(start.takesParkinsonMedication) }
    var hand by rememberSaveable(existing) { mutableStateOf(start.dominantHand) }
    var consent by rememberSaveable(existing) { mutableStateOf(start.consent) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val form = ProfileForm(firstName, lastName, age, gender, mobile, nationalId, diagnosed, medication, hand, consent)
    val errors = if (attempted) ProfileValidation.errors(form) else emptySet()
    val saving = write == ProfileWrite.Working

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            stringResource(if (existing == null) R.string.reg_title else R.string.reg_edit_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        Text(stringResource(R.string.reg_intro), style = MaterialTheme.typography.bodyLarge)

        NameField(R.string.reg_first_name, firstName, ProfileField.FIRST_NAME in errors) { firstName = it }
        NameField(R.string.reg_last_name, lastName, ProfileField.LAST_NAME in errors) { lastName = it }

        AgePicker(age, ProfileField.AGE in errors) { age = it }

        Choice(
            title = stringResource(R.string.reg_gender),
            options = listOf(Gender.MALE to stringResource(R.string.reg_gender_male), Gender.FEMALE to stringResource(R.string.reg_gender_female)),
            selected = gender,
            error = ProfileField.GENDER in errors,
        ) { gender = it }

        OutlinedTextField(
            value = mobile,
            onValueChange = { v -> mobile = v.filter { it.isDigit() || it == '+' || it == ' ' }.take(16) },
            label = { Text(stringResource(R.string.reg_mobile)) },
            placeholder = { Text(stringResource(R.string.reg_mobile_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            isError = ProfileField.MOBILE in errors,
            supportingText = { if (ProfileField.MOBILE in errors) Text(stringResource(R.string.reg_mobile_error)) },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = nationalId,
            onValueChange = { v -> nationalId = v.filter { it.isDigit() }.take(10) },
            label = { Text(stringResource(R.string.reg_national_id)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            isError = ProfileField.NATIONAL_ID in errors,
            supportingText = {
                Text(stringResource(if (ProfileField.NATIONAL_ID in errors) R.string.reg_national_id_error else R.string.reg_national_id_hint))
            },
            modifier = Modifier.fillMaxWidth()
        )

        val yes = stringResource(R.string.reg_yes)
        val no = stringResource(R.string.reg_no)
        Choice(
            title = stringResource(R.string.reg_diagnosed),
            options = listOf(true to yes, false to no),
            selected = diagnosed,
            error = ProfileField.DIAGNOSIS in errors,
        ) { diagnosed = it }
        if (diagnosed == true) {
            Choice(
                title = stringResource(R.string.reg_medication),
                options = listOf(true to yes, false to no),
                selected = medication,
                error = ProfileField.MEDICATION in errors,
            ) { medication = it }
        }

        Choice(
            title = stringResource(R.string.reg_dominant_hand),
            options = listOf(DominantHand.RIGHT to stringResource(R.string.reg_hand_right), DominantHand.LEFT to stringResource(R.string.reg_hand_left)),
            selected = hand,
            error = ProfileField.DOMINANT_HAND in errors,
        ) { hand = it }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.reg_privacy), style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .toggleable(value = consent, role = Role.Checkbox, onValueChange = { consent = it }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = consent, onCheckedChange = null)
                    Text(
                        stringResource(R.string.reg_consent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ProfileField.CONSENT in errors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        if (errors.isNotEmpty()) {
            Text(stringResource(R.string.reg_fix_errors), color = MaterialTheme.colorScheme.error)
        }
        if (write == ProfileWrite.Failed) {
            Text(stringResource(R.string.reg_save_failed), color = MaterialTheme.colorScheme.error)
        }
        if (write == ProfileWrite.DuplicateNationalId) {
            Text(stringResource(R.string.reg_duplicate_national_id), color = MaterialTheme.colorScheme.error)
        }
        PrimaryButton(
            text = stringResource(if (saving) R.string.reg_saving else R.string.reg_save),
            enabled = !saving,
            onClick = {
                attempted = true
                if (ProfileValidation.errors(form).isEmpty()) onSave(form)
            }
        )
        if (onDelete != null) {
            TextButton(onClick = { confirmDelete = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.reg_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.reg_delete_title)) },
            text = { Text(stringResource(R.string.reg_delete_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.reg_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.reg_delete_cancel)) } }
        )
    }
}

@Composable
private fun NameField(label: Int, value: String, error: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(ProfileRules.NAME_MAX_LENGTH)) },
        label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        singleLine = true,
        isError = error,
        supportingText = { if (error) Text(stringResource(R.string.reg_name_error)) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AgePicker(age: Int?, error: Boolean, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.reg_age), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(age?.let { stringResource(R.string.reg_age_value, it.toPersianDigits()) } ?: stringResource(R.string.reg_age_pick))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                ProfileRules.AGES.forEach { value ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reg_age_value, value.toPersianDigits())) },
                        onClick = { onSelect(value); open = false }
                    )
                }
            }
        }
        if (error) Text(stringResource(R.string.reg_age_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun <T> Choice(title: String, options: List<Pair<T, String>>, selected: T?, error: Boolean, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            options.forEach { (value, label) ->
                Row(
                    modifier = Modifier.selectable(selected = selected == value, role = Role.RadioButton, onClick = { onSelect(value) })
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected == value, onClick = null)
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        if (error) Text(stringResource(R.string.reg_choice_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
