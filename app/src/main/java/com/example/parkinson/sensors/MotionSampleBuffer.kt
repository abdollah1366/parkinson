package com.example.parkinson.sensors

/**
 * Growable primitive-array store for sensor events. [add] does not allocate while the capacity
 * is sufficient (pre-size it for the expected recording), so it is cheap enough to call from a
 * sensor callback. Not thread-safe: the owner synchronizes.
 */
class MotionSampleBuffer(initialCapacity: Int = 4096) {

    private var types = ByteArray(initialCapacity)
    private var timestamps = LongArray(initialCapacity)
    private var xs = FloatArray(initialCapacity)
    private var ys = FloatArray(initialCapacity)
    private var zs = FloatArray(initialCapacity)
    private var unreliable = BooleanArray(initialCapacity)

    var size: Int = 0
        private set

    fun add(type: MotionSensorType, timestampNs: Long, x: Float, y: Float, z: Float, isUnreliable: Boolean) {
        if (size == timestamps.size) grow()
        types[size] = type.ordinal.toByte()
        timestamps[size] = timestampNs
        xs[size] = x
        ys[size] = y
        zs[size] = z
        unreliable[size] = isUnreliable
        size++
    }

    fun clear() {
        size = 0
    }

    fun count(type: MotionSensorType): Int {
        var n = 0
        val t = type.ordinal.toByte()
        for (i in 0 until size) if (types[i] == t) n++
        return n
    }

    /** Timestamps of one sensor, in arrival order. */
    fun timestamps(type: MotionSensorType): LongArray {
        val t = type.ordinal.toByte()
        val out = LongArray(count(type))
        var k = 0
        for (i in 0 until size) if (types[i] == t) out[k++] = timestamps[i]
        return out
    }

    /** Largest timestamp in the buffer (ns), or null when empty. */
    fun lastTimestampNs(): Long? {
        if (size == 0) return null
        var last = timestamps[0]
        for (i in 1 until size) if (timestamps[i] > last) last = timestamps[i]
        return last
    }

    /** Copies everything into immutable samples (done once, off the sensor thread). */
    fun toSamples(): List<MotionSample> = List(size) { i ->
        MotionSample(MotionSensorType.entries[types[i].toInt()], timestamps[i], xs[i], ys[i], zs[i], unreliable[i])
    }

    private fun grow() {
        val capacity = timestamps.size * 2
        types = types.copyOf(capacity)
        timestamps = timestamps.copyOf(capacity)
        xs = xs.copyOf(capacity)
        ys = ys.copyOf(capacity)
        zs = zs.copyOf(capacity)
        unreliable = unreliable.copyOf(capacity)
    }
}
