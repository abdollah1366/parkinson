package com.example.parkinson.mediapipe

/**
 * One hand landmark.
 *
 * @param index MediaPipe landmark index (0..20)
 * @param x normalized x coordinate (0..1)
 * @param y normalized y coordinate (0..1)
 * @param z relative depth
 */
data class HandLandmark(
    val index: Int,
    val x: Float,
    val y: Float,
    val z: Float
)

enum class HandSide {
    LEFT,
    RIGHT;

    fun opposite(): HandSide =
        if (this == LEFT) RIGHT else LEFT
}

/** Indices of the 21 MediaPipe hand landmarks. */
object HandLandmarkIndex {

    const val WRIST = 0

    const val THUMB_CMC = 1
    const val THUMB_MCP = 2
    const val THUMB_IP = 3
    const val THUMB_TIP = 4

    const val INDEX_FINGER_MCP = 5
    const val INDEX_FINGER_PIP = 6
    const val INDEX_FINGER_DIP = 7
    const val INDEX_FINGER_TIP = 8

    const val MIDDLE_FINGER_MCP = 9
    const val MIDDLE_FINGER_PIP = 10
    const val MIDDLE_FINGER_DIP = 11
    const val MIDDLE_FINGER_TIP = 12

    const val RING_FINGER_MCP = 13
    const val RING_FINGER_PIP = 14
    const val RING_FINGER_DIP = 15
    const val RING_FINGER_TIP = 16

    const val PINKY_MCP = 17
    const val PINKY_PIP = 18
    const val PINKY_DIP = 19
    const val PINKY_TIP = 20

    const val COUNT = 21

    /** Pairs of landmark indices that form the hand skeleton. */
    val CONNECTIONS: List<Pair<Int, Int>> = listOf(
        0 to 1,
        1 to 2,
        2 to 3,
        3 to 4,

        0 to 5,
        5 to 6,
        6 to 7,
        7 to 8,

        5 to 9,
        9 to 10,
        10 to 11,
        11 to 12,

        9 to 13,
        13 to 14,
        14 to 15,
        15 to 16,

        13 to 17,
        17 to 18,
        18 to 19,
        19 to 20,

        0 to 17
    )
}