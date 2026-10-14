package com.example.parkinson.data

import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Every stored column of one result: the test's own measurements, as the doctor's server keeps them. */
data class ResultRow(val table: String, val columns: JSONObject)

/**
 * Reads the stored rows of one person straight from the result tables, keyed by assessmentId. Column names are the
 * Room column names, so new columns are sent without changes here. Owner and medication columns are left out (they
 * travel as separate fields).
 */
class ResultRowReader(private val db: AssessmentDatabase) {

    suspend fun rows(profileId: Int): Map<String, ResultRow> = withContext(Dispatchers.IO) {
        val sql = db.openHelper.readableDatabase
        buildMap {
            for (table in ResultTables.NAMES) {
                sql.query("SELECT * FROM `$table` WHERE profileId = ?", arrayOf<Any?>(profileId)).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(cursor.getColumnIndexOrThrow("assessmentId"))
                        put(id, ResultRow(table, cursor.rowJson()))
                    }
                }
            }
        }
    }

    private fun Cursor.rowJson(): JSONObject {
        val json = JSONObject()
        for (i in 0 until columnCount) {
            val name = getColumnName(i)
            if (name in SKIPPED) continue
            when (getType(i)) {
                Cursor.FIELD_TYPE_NULL -> json.put(name, JSONObject.NULL)
                Cursor.FIELD_TYPE_INTEGER -> json.put(name, getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> getDouble(i).let { json.put(name, if (it.isFinite()) it else JSONObject.NULL) }
                Cursor.FIELD_TYPE_STRING -> json.put(name, getString(i))
                else -> Unit // No blobs are stored; skip rather than guess.
            }
        }
        return json
    }

    private companion object {
        val SKIPPED = setOf("assessmentId", "profileId", "medicationState")
    }
}
