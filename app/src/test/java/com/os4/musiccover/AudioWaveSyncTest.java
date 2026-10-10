package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioWaveSyncTest {
    @Test public void speakerAndWiredOutputAreNeverDelayed() {
        assertFalse(AudioWaveSync.isBluetooth(2));
        assertFalse(AudioWaveSync.isBluetooth(4));
        assertEquals(0, AudioWaveSync.estimateDelay(false, 500, 250));
    }
    @Test public void classicAndLeBluetoothAreRecognized() {
        for (int type : new int[] {7, 8, 26, 27, 30}) assertTrue(AudioWaveSync.isBluetooth(type));
    }
    @Test public void reportedLatencyAndManualAdjustmentAreBounded() {
        int base = AudioWaveSync.estimateDelay(true, 400, 0);
        assertEquals(100, AudioWaveSync.estimateDelay(true, 400, 100) - base);
        assertEquals(100, base - AudioWaveSync.estimateDelay(true, 400, -100));
        assertEquals(0, AudioWaveSync.estimateDelay(true, 10, -250));
        assertEquals(1000, AudioWaveSync.estimateDelay(true, 1900, 250));
        assertTrue(AudioWaveSync.estimateDelay(true, 0, 0) > 0);
        assertEquals(AudioWaveSync.estimateDelay(true, 0, 0), AudioWaveSync.estimateDelay(true, 5000, 0));
    }
    @Test public void queueOwnsItsSamplesAndDoesNotDeliverEarly() {
        SpectrumDelayBuffer<String> queue = new SpectrumDelayBuffer<>(3);
        byte[] fft = {1, 2, 3};
        Object source = new Object();
        queue.offer(source, fft, 16000000, 100, "subscribers");
        fft[1] = 99;
        assertNull(queue.poll(99));
        SpectrumDelayBuffer.Frame<String> frame = queue.poll(100);
        assertSame(source, frame.source);
        assertArrayEquals(new byte[] {1, 2, 3}, frame.data);
        assertEquals("subscribers", frame.targets);
        assertNull(queue.poll(101));
    }
    @Test public void overflowAndRouteResetCannotLeaveAnUnboundedBacklog() {
        SpectrumDelayBuffer<String> queue = new SpectrumDelayBuffer<>(2);
        for (int i = 1; i <= 3; i++) queue.offer(null, new byte[] {(byte) i}, 16000000, i * 10, "targets");
        assertEquals(2, queue.size());
        assertNull(queue.poll(19));
        assertEquals(2, queue.poll(20).data[0]);
        queue.clear();
        assertEquals(-1, queue.nextAt());
        assertNull(queue.poll(1000));
    }
}
