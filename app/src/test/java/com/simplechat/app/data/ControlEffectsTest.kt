package com.simplechat.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlEffectsTest {
    @Test fun defaultAndPerControlOverridesAreIndependent() {
        for (target in InterfaceControl.entries) {
            val noBlur = ControlEffects().withAcrylic(target, false)
            val noTint = ControlEffects().withAccent(target, false)
            for (control in InterfaceControl.entries) {
                assertTrue(noBlur.usesAccent(control))
                assertTrue(noTint.usesAcrylic(control))
                if (control == target) {
                    assertFalse(noBlur.usesAcrylic(control))
                    assertFalse(noTint.usesAccent(control))
                } else {
                    assertTrue(noBlur.usesAcrylic(control))
                    assertTrue(noTint.usesAccent(control))
                }
            }
            val restored = noBlur.withAccent(target, false).withAcrylic(target, true)
            assertTrue(restored.usesAcrylic(target))
            assertFalse(restored.usesAccent(target))
        }
    }
}
