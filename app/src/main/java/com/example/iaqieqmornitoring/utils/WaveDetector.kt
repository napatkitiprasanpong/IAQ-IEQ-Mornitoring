package com.example.iaqieqmornitoring.utils

import kotlin.math.hypot

/**
 * Decides from pose keypoints whether a person is waving.
 *
 * A wave = one hand raised (wrist above the elbow) whose wrist keeps swinging left and right of the
 * elbow: at least [minReversals] direction changes inside [windowMs]. A hand that is only raised and
 * still, or an arm that is down, never counts. Swing size is measured relative to the shoulder width,
 * so it does not depend on how far away the person is.
 */
class WaveDetector(
    private val windowMs: Long = 1600L,
    private val minReversals: Int = 3,
    /** Smallest swing (in shoulder widths) that counts as a direction change. */
    private val swingThreshold: Float = 0.18f,
    private val minScore: Float = 0.25f,
    /** Keeps reporting "waving" briefly after the last detection so the flag does not flicker. */
    private val holdMs: Long = 700L
) {
    private class Sample(val t: Long, val v: Float)

    private val left = ArrayDeque<Sample>()
    private val right = ArrayDeque<Sample>()
    private var wavingUntil = 0L

    fun reset() {
        left.clear(); right.clear(); wavingUntil = 0L
    }

    /** Call for every pose result; returns true while a wave is in progress. */
    fun update(pose: PoseResult, nowMs: Long): Boolean {
        val l = checkArm(pose, Kp.L_SHOULDER, Kp.L_ELBOW, Kp.L_WRIST, left, nowMs)
        val r = checkArm(pose, Kp.R_SHOULDER, Kp.R_ELBOW, Kp.R_WRIST, right, nowMs)
        if (l || r) wavingUntil = nowMs + holdMs
        return nowMs < wavingUntil
    }

    private fun checkArm(
        pose: PoseResult, shoulderIdx: Int, elbowIdx: Int, wristIdx: Int,
        buf: ArrayDeque<Sample>, now: Long
    ): Boolean {
        val p = pose.points
        val shoulder = p[shoulderIdx]
        val otherShoulder = p[if (shoulderIdx == Kp.L_SHOULDER) Kp.R_SHOULDER else Kp.L_SHOULDER]
        val elbow = p[elbowIdx]
        val wrist = p[wristIdx]

        if (shoulder.score < minScore || otherShoulder.score < minScore ||
            elbow.score < minScore || wrist.score < minScore
        ) {
            buf.clear(); return false
        }

        val w = pose.imageWidth.toFloat()
        val h = pose.imageHeight.toFloat()
        val shoulderWidth = hypot((shoulder.x - otherShoulder.x) * w, (shoulder.y - otherShoulder.y) * h)
        if (shoulderWidth < 8f) { buf.clear(); return false }

        // Image y grows downwards, so "above" means a smaller y.
        if (wrist.y >= elbow.y) { buf.clear(); return false }

        buf.addLast(Sample(now, (wrist.x - elbow.x) * w / shoulderWidth))
        while (buf.isNotEmpty() && buf.first().t < now - windowMs) buf.removeFirst()

        if (buf.size < 6 || buf.last().t - buf.first().t < windowMs * 0.6) return false
        return countReversals(buf) >= minReversals
    }

    private fun countReversals(buf: ArrayDeque<Sample>): Int {
        var dir = 0
        var extreme = buf.first().v
        var reversals = 0
        for (i in 1 until buf.size) {
            val v = buf[i].v
            when (dir) {
                0 -> if (v - extreme > swingThreshold) { dir = 1; extreme = v }
                     else if (extreme - v > swingThreshold) { dir = -1; extreme = v }
                1 -> if (v > extreme) extreme = v
                     else if (extreme - v > swingThreshold) { dir = -1; reversals++; extreme = v }
                else -> if (v < extreme) extreme = v
                        else if (v - extreme > swingThreshold) { dir = 1; reversals++; extreme = v }
            }
        }
        return reversals
    }
}

