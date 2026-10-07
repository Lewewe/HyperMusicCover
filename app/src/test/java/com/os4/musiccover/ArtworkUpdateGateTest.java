package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArtworkUpdateGateTest {
    @Test public void nativeQueriesCannotSpawnAnotherDetectorWhileItRuns() {
        ArtworkUpdateGate gate = new ArtworkUpdateGate();
        assertTrue(gate.request());
        // A detector's native query can synchronously trigger an intercepted method.
        for (int i = 0; i < 1000; i++) assertFalse(gate.request());
        gate.complete(true);
        assertFalse(gate.request());
        gate.complete(false);
        assertTrue(gate.request());
    }

    @Test public void cancellationReleasesTheSingleSlot() {
        ArtworkUpdateGate gate = new ArtworkUpdateGate();
        assertTrue(gate.request());
        gate.complete(false);
        assertTrue(gate.request());
        assertFalse(gate.request());
    }
}
