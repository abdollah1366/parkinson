package com.example.parkinson.norms

/** Who a reference value describes. */
enum class NormGroup(val id: String) {
    HEALTHY("healthy"),
    PARKINSON("pd");

    companion object {
        fun fromId(id: String): NormGroup? = entries.firstOrNull { it.id == id }
    }
}

/** The sex a reference value is for; [ANY] when the source does not split by sex. */
enum class NormSex(val id: String) {
    ANY("any"),
    MALE("male"),
    FEMALE("female");

    companion object {
        fun fromId(id: String): NormSex? = entries.firstOrNull { it.id == id }
    }
}

/** Which way a measure gets worse: [BAND] values are classified by the [NormEntry.low]..[NormEntry.high] range. */
enum class NormDirection(val id: String) {
    HIGHER_IS_BETTER("higher"),
    LOWER_IS_BETTER("lower"),
    BAND("band");

    companion object {
        fun fromId(id: String): NormDirection? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One published reference value (one row of `assets/norms/reference_norms.csv`). Numbers the source does not give are
 * null. [applied] is false for rows kept only for reference (the app does not compare results with them).
 */
data class NormEntry(
    val test: String,
    val metric: String,
    val unit: String,
    val group: NormGroup,
    val sex: NormSex,
    val ageMin: Int,
    val ageMax: Int,
    val mean: Double?,
    val sd: Double?,
    val low: Double?,
    val high: Double?,
    val cutoff: Double?,
    val direction: NormDirection,
    val n: Int?,
    val applied: Boolean,
    val source: String,
    val doi: String,
    val note: String,
) {
    fun coversAge(age: Int): Boolean = age in ageMin..ageMax

    /** True when the row is not limited to an age band, so it can be used when the age is unknown. */
    val anyAge: Boolean get() = ageMin <= 0 && ageMax >= 120

    val doiUrl: String get() = "https://doi.org/$doi"
}

/** The bundled table of published reference values and the lookup by age, sex and group. */
class ReferenceNorms(val entries: List<NormEntry>) {

    /**
     * The applied row for [metric] and [group] that fits [age] and [sex] best: a row for the person's own sex wins over
     * an [NormSex.ANY] row. With an unknown age only rows without an age band qualify; with an unknown sex only
     * [NormSex.ANY] rows. [withCutoff] picks rows with a cut-off; otherwise rows with a mean or a band.
     */
    fun find(metric: String, group: NormGroup, age: Int?, sex: NormSex?, withCutoff: Boolean = false): NormEntry? =
        entries.asSequence()
            .filter { it.applied && it.metric == metric && it.group == group }
            .filter { if (withCutoff) it.cutoff != null else it.mean != null || it.low != null }
            .filter { if (age == null) it.anyAge else it.coversAge(age) }
            .filter { it.sex == NormSex.ANY || it.sex == sex }
            .sortedBy { if (it.sex == NormSex.ANY) 1 else 0 }
            .firstOrNull()

    /** Every row (applied or not) for [metric], for listing the sources. */
    fun all(metric: String): List<NormEntry> = entries.filter { it.metric == metric }

    companion object {
        const val ASSET_PATH = "norms/reference_norms.csv"

        private val HEADER = listOf(
            "test", "metric", "unit", "group", "sex", "age_min", "age_max", "mean", "sd", "low", "high", "cutoff",
            "better", "n", "applied", "source", "doi", "note",
        )

        /** Parses the CSV table. Lines starting with `#` and blank lines are skipped; a malformed row throws. */
        fun parse(csv: String): ReferenceNorms {
            val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            require(lines.isNotEmpty()) { "empty reference table" }
            require(lines.first().split(',') == HEADER) { "unexpected header: ${lines.first()}" }
            val rows = lines.drop(1).mapIndexed { i, line ->
                val c = line.split(',')
                require(c.size == HEADER.size) { "row ${i + 1}: ${c.size} cells instead of ${HEADER.size}" }
                fun num(k: Int) = c[k].takeIf { it.isNotBlank() }?.toDouble()
                NormEntry(
                    test = c[0],
                    metric = c[1],
                    unit = c[2],
                    group = requireNotNull(NormGroup.fromId(c[3])) { "row ${i + 1}: group ${c[3]}" },
                    sex = requireNotNull(NormSex.fromId(c[4])) { "row ${i + 1}: sex ${c[4]}" },
                    ageMin = c[5].toInt(),
                    ageMax = c[6].toInt(),
                    mean = num(7),
                    sd = num(8),
                    low = num(9),
                    high = num(10),
                    cutoff = num(11),
                    direction = requireNotNull(NormDirection.fromId(c[12])) { "row ${i + 1}: better ${c[12]}" },
                    n = c[13].takeIf { it.isNotBlank() }?.toInt(),
                    applied = when (c[14]) {
                        "yes" -> true
                        "no" -> false
                        else -> throw IllegalArgumentException("row ${i + 1}: applied ${c[14]}")
                    },
                    source = c[15],
                    doi = c[16],
                    note = c[17],
                )
            }
            return ReferenceNorms(rows)
        }
    }
}
