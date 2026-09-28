package io.github.brunovinicioslg.alumia.core.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoliciesTest {

    @Test
    fun `service keeps running while detection is on or the app holds the light`() {
        for (loaded in listOf(true, false)) for (detection in listOf(true, false)) for (torch in listOf(true, false)) {
            for (pending in 0..2) {
                val expected = !loaded || pending > 0 || detection || torch
                assertEquals(expected, Policies.shouldKeepServiceRunning(loaded, detection, torch, pending))
            }
        }
        assertFalse(Policies.shouldKeepServiceRunning(true, false, false, 0))
    }

    @Test
    fun `low battery fires only when crossing the threshold`() {
        assertTrue(Policies.crossedLowBattery(11, 10, 10))
        assertTrue(Policies.crossedLowBattery(30, 5, 10))
        assertFalse(Policies.crossedLowBattery(10, 9, 10), "already below: user may turn it back on")
        assertFalse(Policies.crossedLowBattery(null, 5, 10), "unknown previous level")
        assertFalse(Policies.crossedLowBattery(50, 1, 0), "disabled")
        assertFalse(Policies.crossedLowBattery(9, 12, 10), "charging")
    }

    @Test
    fun `gesture is blocked during calls and in the pocket`() {
        assertEquals(GestureBlock.IN_CALL, Policies.gestureBlock(inCall = true, proximityCovered = true, ignoreInPocket = true))
        assertEquals(GestureBlock.IN_POCKET, Policies.gestureBlock(inCall = false, proximityCovered = true, ignoreInPocket = true))
        assertNull(Policies.gestureBlock(inCall = false, proximityCovered = true, ignoreInPocket = false))
        assertNull(Policies.gestureBlock(inCall = false, proximityCovered = false, ignoreInPocket = true))
    }

    @Test
    fun `torch level maps percentage into hardware range`() {
        assertEquals(1, Policies.torchLevel(100, maxLevel = 1))
        assertEquals(1, Policies.torchLevel(1, maxLevel = 5))
        assertEquals(5, Policies.torchLevel(100, maxLevel = 5))
        assertEquals(3, Policies.torchLevel(50, maxLevel = 5))
        assertEquals(1, Policies.torchLevel(-20, maxLevel = 5))
        assertEquals(5, Policies.torchLevel(400, maxLevel = 5))
        for (max in 1..20) for (p in 1..100) {
            assertTrue(Policies.torchLevel(p, max) in 1..max)
        }
    }
}
