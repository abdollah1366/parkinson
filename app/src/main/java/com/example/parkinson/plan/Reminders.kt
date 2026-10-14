package com.example.parkinson.plan

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.R
import com.example.parkinson.ui.format.PersianFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Reminders of the treatment plan: one alarm at a time, set for the next dose (or the afternoon exercise reminder) of
 * anyone on the phone, and set again after each reminder, each change of the plans and each restart of the phone.
 * A dose reminder has "taken" and "missed" buttons, which mark every dose in it.
 */
object PlanReminders {
    private const val CHANNEL_MEDICATION = "plan_medication"
    private const val CHANNEL_EXERCISE = "plan_exercise"

    /** A reminder that fires this much later than its time is not shown (the phone was off, say). */
    private const val LATE_MS = 3 * 60 * 60 * 1000L

    internal const val ACTION_REMIND = "com.example.parkinson.plan.REMIND"
    internal const val ACTION_MARK = "com.example.parkinson.plan.MARK"
    internal const val EXTRA_AT = "at"
    internal const val EXTRA_PROFILE = "profile"
    internal const val EXTRA_IDS = "ids"
    internal const val EXTRA_STATUS = "status"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MEDICATION, context.getString(R.string.plan_channel_medication), NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(sound(context, R.raw.voice_med_reminder), audio); enableVibration(true) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EXERCISE, context.getString(R.string.plan_channel_exercise), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { setSound(sound(context, R.raw.voice_exercise_reminder), audio) }
        )
    }

    private fun sound(context: Context, raw: Int): Uri = Uri.parse("android.resource://${context.packageName}/$raw")

    /** Sets the alarm for the next reminder of anyone's plan, or clears it when there is none. */
    fun schedule(context: Context, store: PlanStore, now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val zone = TimeZone.getDefault()
        val next = store.plans().values.mapNotNull { PlanSchedule.nextReminder(it.prescriptions, now, zone) }.minOrNull()
        val intent = Intent(context, PlanReminderReceiver::class.java).setAction(ACTION_REMIND)
        if (next == null) {
            PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                ?.let { alarms.cancel(it); it.cancel() }
            return
        }
        intent.putExtra(EXTRA_AT, next)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        try {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            else alarms.setWindow(AlarmManager.RTC_WAKEUP, next, 10 * 60 * 1000L, pending)
        } catch (e: SecurityException) {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, next, 10 * 60 * 1000L, pending)
        }
    }

    /** Shows what is due at [at]: each person's doses at that time not marked yet, and the afternoon exercise reminder. */
    fun showDue(context: Context, store: PlanStore, at: Long, now: Long = System.currentTimeMillis()) {
        if (now - at > LATE_MS) return
        val zone = TimeZone.getDefault()
        for ((profileId, plan) in store.plans()) {
            val marks = store.marks(profileId)
            val doses = plan.prescriptions.flatMap { PlanSchedule.doses(it, at, at + 60_000, zone) }
                .filter { Intake(it.prescription.id, it.atEpochMs, IntakeStatus.TAKEN).key !in marks }
            if (doses.isNotEmpty()) showDoses(context, profileId, plan.personName, at, doses)
            if (PlanSchedule.isExerciseReminder(at, zone)) {
                val today = PlanSchedule.startOfDay(at, zone)
                val left = plan.prescriptions.filter { p ->
                    p.weekly && PlanSchedule.runsOn(p, today, zone) &&
                        marks.values.none { it.prescriptionId == p.id && it.scheduledAtEpochMs >= today } &&
                        marks.values.count { it.prescriptionId == p.id && it.scheduledAtEpochMs >= at - 7 * PlanSchedule.DAY_MS } < (p.perWeek ?: 0)
                }
                if (left.isNotEmpty()) showExercises(context, profileId, plan.personName, left)
            }
        }
    }

    private fun showDoses(context: Context, profileId: Int, person: String, at: Long, doses: List<Dose>) {
        val id = notificationId(profileId, at)
        val ids = doses.map { it.prescription.id }.toLongArray()
        fun action(status: IntakeStatus, request: Int) = PendingIntent.getBroadcast(
            context, id * 4 + request,
            Intent(context, PlanReminderReceiver::class.java).setAction(ACTION_MARK)
                .putExtra(EXTRA_PROFILE, profileId).putExtra(EXTRA_AT, at).putExtra(EXTRA_IDS, ids).putExtra(EXTRA_STATUS, status.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = doses.joinToString("، ") { d -> d.prescription.name + (d.prescription.dose?.let { " ($it)" } ?: "") }
        val builder = base(context, CHANNEL_MEDICATION, R.raw.voice_med_reminder)
            .setContentTitle(context.getString(R.string.plan_notify_dose_title, person, PersianFormat.time(at)))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setWhen(at)
            .addAction(0, context.getString(R.string.plan_taken), action(IntakeStatus.TAKEN, 1))
            .addAction(0, context.getString(R.string.plan_missed), action(IntakeStatus.MISSED, 2))
        notify(context, id, builder)
    }

    private fun showExercises(context: Context, profileId: Int, person: String, exercises: List<Prescription>) {
        val text = exercises.joinToString("، ") { it.name }
        val builder = base(context, CHANNEL_EXERCISE, R.raw.voice_exercise_reminder)
            .setContentTitle(context.getString(R.string.plan_notify_exercise_title, person))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        notify(context, notificationId(profileId, -1), builder)
    }

    private fun base(context: Context, channel: String, sound: Int): NotificationCompat.Builder {
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_plan)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(sound(context, sound)) // Before Android 8; later the channel's sound plays.
            .setAutoCancel(true)
            .setContentIntent(open)
    }

    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            // Notifications were turned off meanwhile; the plan screen still shows what is due.
        }
    }

    internal fun cancel(context: Context, profileId: Int, at: Long) =
        NotificationManagerCompat.from(context).cancel(notificationId(profileId, at))

    private fun notificationId(profileId: Int, at: Long): Int = 7_000 + profileId * 10_007 + (at / 60_000 % 10_000).toInt()
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** The alarm of the next reminder and the buttons of a dose reminder (not exported: only this app sends them). */
class PlanReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? ParkinsonApplication ?: return
        val result = goAsync()
        receiverScope.launch {
            try {
                when (intent.action) {
                    PlanReminders.ACTION_REMIND -> {
                        val at = intent.getLongExtra(PlanReminders.EXTRA_AT, 0)
                        if (at > 0) PlanReminders.showDue(app, app.planSync.store, at)
                        PlanReminders.schedule(app, app.planSync.store, maxOf(System.currentTimeMillis(), at))
                    }
                    PlanReminders.ACTION_MARK -> {
                        val profileId = intent.getIntExtra(PlanReminders.EXTRA_PROFILE, -1)
                        val at = intent.getLongExtra(PlanReminders.EXTRA_AT, 0)
                        val status = runCatching { IntakeStatus.valueOf(intent.getStringExtra(PlanReminders.EXTRA_STATUS) ?: "") }.getOrNull()
                        val ids = intent.getLongArrayExtra(PlanReminders.EXTRA_IDS) ?: LongArray(0)
                        PlanReminders.cancel(app, profileId, at)
                        if (profileId >= 0 && at > 0 && status != null) {
                            ids.forEach { app.planSync.store.mark(profileId, Intake(it, at, status)) }
                            runCatching { app.planSync.flush(profileId) }
                        }
                    }
                }
            } finally {
                result.finish()
            }
        }
    }
}

/** Sets the reminder again after a restart, an update of the app or a change of the clock. */
class PlanBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                val app = context.applicationContext as? ParkinsonApplication ?: return
                PlanReminders.schedule(app, app.planSync.store)
            }
        }
    }
}
