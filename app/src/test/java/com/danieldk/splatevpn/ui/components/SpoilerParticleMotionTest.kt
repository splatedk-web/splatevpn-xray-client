package com.danieldk.splatevpn.ui.components

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class SpoilerParticleMotionTest {
    @Test fun particlesStayWithinAFewDpOfTheirAnchors() {
        for (speedX in 1..4) for (speedY in 1..4) {
            val motion = SpoilerParticleMotion(.8f, 2.1f, speedX, speedY, 2.4f, 2f)
            for (frame in 0..240) {
                val offset = motion.displacementAt(frame / 240f)
                assertTrue("Horizontal movement must stay local", abs(offset.x) <= 3.001f)
                assertTrue("Vertical movement must stay local", abs(offset.y) <= 2.401f)
            }
        }
    }

    @Test fun differentParticlesMoveInAllFourDirectionsInTheSameFrame() {
        val movements = (0..7).map { index ->
            val motion = SpoilerParticleMotion(index * PI.toFloat() / 4f, (index + 2) * PI.toFloat() / 4f,
                2, 3, 2f, 1.5f)
            motion.displacementAt(.035f) - motion.displacementAt(.025f)
        }
        assertTrue(movements.any { it.x > .01f })
        assertTrue(movements.any { it.x < -.01f })
        assertTrue(movements.any { it.y > .01f })
        assertTrue(movements.any { it.y < -.01f })
    }

    @Test fun theLoopHasNoPositionJump() {
        for (speed in 1..4) {
            val motion = SpoilerParticleMotion(.7f, 2.4f, speed, 5 - speed, 2.4f, 2f)
            val first = motion.displacementAt(0f)
            val last = motion.displacementAt(1f)
            assertEquals(first.x, last.x, .0001f)
            assertEquals(first.y, last.y, .0001f)
        }
    }
}
