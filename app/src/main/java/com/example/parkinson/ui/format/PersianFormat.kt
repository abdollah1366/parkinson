package com.example.parkinson.ui.format

import android.annotation.SuppressLint
import android.icu.text.SimpleDateFormat
import android.icu.util.ULocale
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** Number and date formatting for the Persian UI (Persian digits, Solar Hijri calendar). */
object PersianFormat {

    private val persianLocale: Locale = Locale.forLanguageTag("fa-IR")

    fun integer(value: Int): String = NumberFormat.getIntegerInstance(persianLocale).format(value)

    fun integer(value: Long): String = NumberFormat.getIntegerInstance(persianLocale).format(value)

    fun decimal(value: Double, fractionDigits: Int = 1): String =
        NumberFormat.getNumberInstance(persianLocale).apply {
            minimumFractionDigits = fractionDigits
            maximumFractionDigits = fractionDigits
            isGroupingUsed = false
        }.format(value)

    /** ASCII digits in [text] as Persian digits, without grouping (codes, phone numbers). */
    fun digits(text: String): String = buildString(text.length) {
        for (c in text) append(if (c in '0'..'9') '۰' + (c - '0') else c)
    }

    /** e.g. ۱۴۰۵/۰۷/۱۵ - ۱۴:۳۰ */
    @SuppressLint("SimpleDateFormat") // The locale is explicit: fa_IR with the Persian calendar.
    fun dateTime(epochMs: Long): String =
        SimpleDateFormat("yyyy/MM/dd - HH:mm", ULocale("fa_IR@calendar=persian")).format(Date(epochMs))

    /** e.g. ۱۴:۳۰ */
    @SuppressLint("SimpleDateFormat")
    fun time(epochMs: Long): String = SimpleDateFormat("HH:mm", ULocale("fa_IR@calendar=persian")).format(Date(epochMs))

    /** e.g. ۰۷/۱۵ (month/day, Solar Hijri), for short chart labels. */
    @SuppressLint("SimpleDateFormat")
    fun shortDate(epochMs: Long): String = SimpleDateFormat("MM/dd", ULocale("fa_IR@calendar=persian")).format(Date(epochMs))
}

fun Int.toPersianDigits(): String = PersianFormat.integer(this)
