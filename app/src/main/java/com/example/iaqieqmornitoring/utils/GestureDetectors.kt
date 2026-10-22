package com.example.iaqieqmornitoring.utils

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Detects a raised hand, either a full arm stretched up or a half raise (elbow bent, forearm up,
 * like asking a question), held for [holdMs].
 *
 * Per arm: the wrist is clearly above the shoulder line, clearly above its own elbow, and the
 * forearm points roughly upwards. Distances are measured in shoulder widths, so the result does
 * not depend on how far away the person stands. A T-pose (wrists at shoulder height) and arms
 * hanging down never count.
 */
class RaisedHandDetector(
    private val holdMs: Long = 800L,
    /** Short detection dropouts shorter than this do not restart the hold timer. */
    private val graceMs: Long = 300L,
    private val minScore: Float = 0.3f,
    /** How far the wrist must be above the shoulder line, in shoulder widths. */
    private val minAboveShoulder: Float = 0.2f,
    /** How far the wrist must be above the elbow, in shoulder widths (the forearm points up). */
    private val minForearmUp: Float = 0.3f,
    /** Largest sideways lean of the forearm, as a multiple of its upward part (1.2 = about 50 degrees). */
    private val maxForearmLean: Float = 1.2f
) {
    private var heldSince = 0L
    private var lastRaisedMs = 0L

    fun reset() { heldSince = 0L; lastRaisedMs = 0L }

    /** True once a hand has been up long enough (and stays true while it is up). */
    fun update(pose: PoseResult, nowMs: Long): Boolean {
        if (isHandUp(pose)) {
            lastRaisedMs = nowMs
            if (heldSince == 0L) heldSince = nowMs
            return nowMs - heldSince >= holdMs
        }
        if (heldSince != 0L && nowMs - lastRaisedMs <= graceMs) {
            return nowMs - heldSince >= holdMs
        }
        heldSince = 0L
        return false
    }

    private fun isHandUp(pose: PoseResult): Boolean {
        val p = pose.points
        val ls = p[Kp.L_SHOULDER]; val rs = p[Kp.R_SHOULDER]
        if (ls.score < minScore || rs.score < minScore) return false

        val w = pose.imageWidth.toFloat()
        val h = pose.imageHeight.toFloat()
        val shoulderWidth = hypot((ls.x - rs.x) * w, (ls.y - rs.y) * h)
        if (shoulderWidth < 8f) return false

        return armUp(pose, ls, p[Kp.L_ELBOW], p[Kp.L_WRIST], shoulderWidth, w, h) ||
            armUp(pose, rs, p[Kp.R_ELBOW], p[Kp.R_WRIST], shoulderWidth, w, h)
    }

    private fun armUp(
        pose: PoseResult, shoulder: Keypoint, elbow: Keypoint, wrist: Keypoint,
        shoulderWidth: Float, w: Float, h: Float
    ): Boolean {
        if (elbow.score < minScore || wrist.score < minScore) return false

        // Image y grows downwards, so "above" means a smaller y.
        val aboveShoulder = (shoulder.y - wrist.y) * h / shoulderWidth
        if (aboveShoulder < minAboveShoulder) return false

        val forearmUp = (elbow.y - wrist.y) * h
        if (forearmUp < minForearmUp * shoulderWidth) return false

        val forearmSideways = abs(wrist.x - elbow.x) * w
        return forearmSideways <= maxForearmLean * forearmUp
    }
}

/**
 * Detects the T-pose stop gesture: both arms stretched straight out to the sides at shoulder height,
 * held for [holdMs].
 *
 * Per arm: the line shoulder -> wrist is close to horizontal, points away from the body, is long
 * (an extended arm, not a bent one) and the elbow lies on that line. A single raised or waving arm
 * never counts because both arms have to match at the same time.
 */
class TPoseDetector(
    private val holdMs: Long = 800L,
    private val minScore: Float = 0.3f,
    /** Largest tilt of the arm away from horizontal, in degrees. */
    private val maxTiltDeg: Float = 25f,
    /** Shortest shoulder-to-wrist distance, in shoulder widths (a bent arm is shorter). */
    private val minArmReach: Float = 1.0f,
    /** Largest distance of the elbow from the shoulder-wrist line, as a share of the shoulder-wrist distance. */
    private val maxElbowBend: Float = 0.25f
) {
    private var heldSince = 0L

    fun reset() { heldSince = 0L }

    /** True once the T-pose has been held long enough (and stays true while it is held). */
    fun update(pose: PoseResult, nowMs: Long): Boolean {
        if (isTPose(pose)) {
            if (heldSince == 0L) heldSince = nowMs
            return nowMs - heldSince >= holdMs
        }
        heldSince = 0L
        return false
    }

    private fun isTPose(pose: PoseResult): Boolean {
        val p = pose.points
        val ls = p[Kp.L_SHOULDER]; val rs = p[Kp.R_SHOULDER]
        if (ls.score < minScore || rs.score < minScore) return false

        val w = pose.imageWidth.toFloat()
        val h = pose.imageHeight.toFloat()
        val shoulderWidth = hypot((ls.x - rs.x) * w, (ls.y - rs.y) * h)
        if (shoulderWidth < 8f) return false

        val midX = (ls.x + rs.x) / 2f
        return armOut(pose, Kp.L_SHOULDER, Kp.L_ELBOW, Kp.L_WRIST, midX, shoulderWidth, w, h) &&
            armOut(pose, Kp.R_SHOULDER, Kp.R_ELBOW, Kp.R_WRIST, midX, shoulderWidth, w, h)
    }

    private fun armOut(
        pose: PoseResult, sIdx: Int, eIdx: Int, wIdx: Int,
        midX: Float, shoulderWidth: Float, w: Float, h: Float
    ): Boolean {
        val s = pose.points[sIdx]; val e = pose.points[eIdx]; val wr = pose.points[wIdx]
        if (e.score < minScore || wr.score < minScore) return false

        val dx = (wr.x - s.x) * w
        val dy = (wr.y - s.y) * h
        val reach = hypot(dx, dy)
        if (reach < minArmReach * shoulderWidth) return false

        // Points away from the body: the wrist is on the same side of the centre line as its shoulder, and further out.
        val outward = if (s.x >= midX) 1f else -1f
        if (dx * outward <= 0f) return false

        // Roughly horizontal.
        val tilt = Math.toDegrees(kotlin.math.atan2(kotlin.math.abs(dy), kotlin.math.abs(dx)).toDouble())
        if (tilt > maxTiltDeg) return false

        // Straight arm: the elbow sits on (or very near) the shoulder-wrist line.
        val ex = (e.x - s.x) * w
        val ey = (e.y - s.y) * h
        val distFromLine = kotlin.math.abs(ex * dy - ey * dx) / reach
        return distFromLine <= maxElbowBend * reach
    }
}
