package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SlidingTabStripTest {
    @get:Rule val compose = createComposeRule()

    private fun host() = mutableIntStateOf(0).also { selected ->
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SlidingTabStrip(selected.intValue, listOf(Color.Red, Color.Green, Color.Blue),
                onSelect = { selected.intValue = it }, modifier = Modifier.width(300.dp).testTag("strip")) {
                repeat(3) { index ->
                    Box(Modifier.weight(1f).fillMaxHeight().clickable { selected.intValue = index }.testTag("tab$index"))
                }
            }
        }
        compose.mainClock.advanceTimeBy(64)
    }

    private fun capsuleLeft() = compose.onNodeWithTag("categoryCapsule").fetchSemanticsNode().boundsInRoot.left

    @Test fun releasingFastDragDoesNotJumpBackToLaggingAnimation() {
        val selected = host()
        val strip = compose.onNodeWithTag("strip")
        val bounds = strip.fetchSemanticsNode().boundsInRoot
        strip.performTouchInput {
            down(Offset(width / 6f, centerY))
            moveTo(Offset(width * .75f, centerY), delayMillis = 16)
        }
        compose.mainClock.advanceTimeByFrame()
        val beforeRelease = capsuleLeft()
        assertTrue("Indicator must follow the finger", beforeRelease > bounds.left + bounds.width / 3f)
        strip.performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("Release must not jump backwards", capsuleLeft() >= beforeRelease - 1f)
        compose.mainClock.advanceTimeBy(300)
        assertEquals(2, selected.intValue)
        val capsule = compose.onNodeWithTag("categoryCapsule").fetchSemanticsNode().boundsInRoot
        assertTrue(capsule.right <= bounds.right + 1f)
    }

    @Test fun rapidDirectionChangeSettlesWithoutRecoilOrOvershoot() {
        host()
        val start = capsuleLeft()
        compose.onNodeWithTag("tab2").performClick()
        compose.mainClock.advanceTimeBy(96)
        assertTrue(capsuleLeft() > start)
        compose.onNodeWithTag("tab0").performClick()
        compose.mainClock.advanceTimeByFrame()
        var previous = capsuleLeft()
        repeat(18) {
            compose.mainClock.advanceTimeByFrame()
            val current = capsuleLeft()
            assertTrue("Indicator must not recoil in the old direction", current <= previous + 1f)
            assertTrue("Indicator must not overshoot the first tab", current >= start - 1f)
            previous = current
        }
        assertEquals(start, capsuleLeft(), 1f)
    }
}
