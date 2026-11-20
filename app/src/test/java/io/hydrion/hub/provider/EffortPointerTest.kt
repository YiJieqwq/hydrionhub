package io.hydrion.hub.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class EffortPointerTest {
    @Test fun touchMatchesTheDrawnThumbCentersWithoutQuantizingOrLosingReversals() {
        // 272 dp card, 12 dp visual rail margin, 15 dp thumb radius -> 218 dp of travel.
        for (fraction in listOf(0f, .46f, .451f, .77f, .22f, 1f)) {
            val x = 27 + 218 * fraction
            assertEquals(fraction, effortPointerFraction(x, 272f, 27f, false, .5f), .0001f)
            assertEquals(1 - fraction, effortPointerFraction(x, 272f, 27f, true, .5f), .0001f)
        }
    }

    @Test fun talkBackCanStepBetweenTwoEndpointsAndSelectRealStopsOnLongerScales() {
        assertEquals(1, accessibleEffortIndex(.05f, 0, 1))
        assertEquals(0, accessibleEffortIndex(.95f, 1, 1))
        assertEquals(1, accessibleEffortIndex(1f, 1, 1))
        assertEquals(2, accessibleEffortIndex(2.28f, 1, 4))
        assertEquals(3, accessibleEffortIndex(Float.NaN, 3, 4))
    }

    @Test fun aCapturedPointerCanCrossEitherEndAndTheLastValidSampleSurvivesAnUnusableCoordinate() {
        assertEquals(0f, effortPointerFraction(-40f, 272f, 27f, false, .63f), 0f)
        assertEquals(1f, effortPointerFraction(302f, 272f, 27f, false, .63f), 0f)
        assertEquals(.63f, effortPointerFraction(Float.NaN, 272f, 27f, false, .63f), 0f)
        assertEquals(.63f, effortPointerFraction(10f, 50f, 27f, true, .63f), 0f)
    }
}
