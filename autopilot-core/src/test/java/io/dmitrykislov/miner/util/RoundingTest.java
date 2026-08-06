package io.dmitrykislov.miner.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rounding sits on the path from a raw device reading to the autopilot's surplus, so what it does
 * with a value that isn't a number matters as much as what it does with one that is.
 */
class RoundingTest {

    @Test void roundsHalfUpToTheRequestedPlaces() {
        assertThat(Rounding.toPlaces(1.2345, 2)).isEqualTo(1.23);
        assertThat(Rounding.toPlaces(1.2355, 2)).isEqualTo(1.24);
        assertThat(Rounding.toPlaces(1.0005, 3)).isEqualTo(1.001);
        assertThat(Rounding.toPlaces(0.0, 3)).isEqualTo(0.0);
        assertThat(Rounding.toPlaces(-1.2345, 2)).isEqualTo(-1.23);
    }

    // ---- non-finite input must stay non-finite ----------------------------------------------
    // The engine defends itself by rejecting NaN/Infinity (RollingWindow.add, AutopilotGovernor,
    // IngestController). Every one of those guards asks Double.isFinite. Rounding used to answer
    // that question wrongly: Math.round(+∞) is Long.MAX_VALUE, so an infinite reading came out as a
    // FINITE 9.22e15 — and after the kW→W conversion, 9.22e18 W of "surplus". The guards all passed
    // and the autopilot would have ramped the miner to its ceiling. Laundering a non-number into a
    // plausible number is worse than either keeping it or dropping it, because it defeats the checks
    // downstream that exist precisely to catch it.

    @Test void positiveInfinityIsNotLaunderedIntoAFiniteNumber() {
        double r = Rounding.toPlaces(Double.POSITIVE_INFINITY, 3);
        assertThat(Double.isFinite(r))
                .as("an infinite reading must not become a finite one that passes isFinite() guards")
                .isFalse();
        assertThat(r).isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test void negativeInfinityIsPreserved() {
        assertThat(Rounding.toPlaces(Double.NEGATIVE_INFINITY, 3))
                .isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test void naNStaysNaNRatherThanBecomingZero() {
        // Math.round(NaN) is 0, so NaN used to arrive downstream as a plausible 0 W reading. That is
        // fail-safe for the autopilot but it is still a fabricated measurement, and it hid the fault.
        assertThat(Rounding.toPlaces(Double.NaN, 3)).isNaN();
    }

    @Test void aValueTooLargeForALongIsNotWrappedToLongMaxValue() {
        // Math.round saturates at Long.MAX_VALUE, so a huge-but-finite reading was silently clamped
        // to ~9.22e15 rather than kept. Preserve the magnitude and let the callers' range checks act.
        double huge = 1e300;
        assertThat(Rounding.toPlaces(huge, 3)).isEqualTo(huge);
    }
}
